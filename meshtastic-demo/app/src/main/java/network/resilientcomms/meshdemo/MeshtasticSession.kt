/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.LogLevel
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.asText
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.textMessages
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport
import kotlin.random.Random

class MeshtasticSession(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val signedTransactionsByRole = StationRole.entries.associateWith(::loadSignedTransactions)
    private val initialRole = (application as MeshDemoApplication).selectedStationRole
    private val initialTransactions = initialRole?.let(signedTransactionsByRole::get).orEmpty()
    private val initialQueueIndex = initialRole
        ?.let { role -> preferences.getInt(nextTransactionPreferenceKey(role), 0) }
        ?.coerceIn(0, initialTransactions.size)
        ?: 0
    private val savedRadioAddress = preferences.getString(SELECTED_RADIO_ADDRESS_KEY, null)
    private val savedRadioName = preferences.getString(SELECTED_RADIO_NAME_KEY, null)
    private val _state = MutableStateFlow(
        initialState(initialRole, initialTransactions.size, initialQueueIndex, savedRadioAddress, savedRadioName),
    )
    val state: StateFlow<MeshDemoState> = _state.asStateFlow()

    private val connectionMutex = Mutex()
    private val observerJobs = mutableListOf<Job>()
    private val knownNodes = mutableMapOf<Int, KnownMeshNode>()
    private var ownNodeNumber: Int? = null
    private val presenceSession = System.currentTimeMillis().toString(36).takeLast(12)
    private var presenceJob: Job? = null
    private var presenceRequestJob: Job? = null
    private var bleKeepaliveJob: Job? = null
    private var bleKeepaliveNonce = 2
    private val bitcoinReplies = MutableSharedFlow<BitcoinReply>(replay = 32, extraBufferCapacity = 32)
    private var client: RadioClient? = null
    private var bitcoinRelayJob: Job? = null
    private var connectJob: Job? = null
    private var discoveryJob: Job? = null
    private var recoveryJob: Job? = null
    private var radioChangeJob: Job? = null
    private var requireExplicitRadioSelection = false
    private var radioSelectionPreviousAddress: String? = null
    private var radioSelectionPreviousName: String? = null
    private var desiredRadioAddress: String? = null
    private var desiredRadioName: String? = null

    fun connect() {
        if (_state.value.stationRole == null) return
        val selectedAddress = _state.value.selectedRadioAddress
        if (selectedAddress != null && isBluetoothAddress(selectedAddress)) {
            val selectedName = _state.value.selectedRadioName
            if (isRadioBonded(selectedAddress)) {
                connectRadio(selectedAddress, selectedName)
            } else {
                stopForMissingBond(selectedAddress, selectedName)
            }
        } else {
            discoverRadios()
        }
    }

    fun scanAgain() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoverRadios()
    }

    fun selectStation(role: StationRole) {
        if (_state.value.isBitcoinRelayActive) return
        val returnStep = if (_state.value.step == DemoStep.OPERATOR) DemoStep.OPERATOR else DemoStep.HOME
        val transactions = signedTransactionsByRole[role].orEmpty()
        val queueIndex = preferences
            .getInt(nextTransactionPreferenceKey(role), 0)
            .coerceIn(0, transactions.size)
        _state.update {
            val presences = it.presences.filterNot { presence -> presence.session == presenceSession }
            it.copy(
                stationRole = role,
                isChoosingStation = false,
                step = returnStep,
                presences = presences,
                recipients = conferenceRecipients(
                    role = role,
                    nodes = knownNodes.values,
                    ownNodeNumber = ownNodeNumber,
                    presences = presences,
                ),
                selectedRecipient = null,
                bitcoinQueueIndex = queueIndex,
                bitcoinQueueTotal = transactions.size,
                bitcoinRelayProgress = BitcoinRelayProgress.IDLE,
                bitcoinStatusText = if (transactions.isEmpty()) {
                    "No signed transactions packaged"
                } else {
                    "Signed transaction ready"
                },
                bitcoinSession = null,
                bitcoinCurrentChunk = 0,
                bitcoinTotalChunks = 0,
                bitcoinTxid = null,
                bitcoinBlockHeight = null,
            )
        }
        client?.takeIf { _state.value.isConnected }?.let { radioClient ->
            stopPresenceLoop()
            startPresenceLoop(radioClient)
        }
    }

    fun beginStationSelection() {
        if (_state.value.isBitcoinRelayActive) return
        _state.update { it.copy(isChoosingStation = true) }
    }

    fun cancelStationSelection() {
        _state.update { it.copy(isChoosingStation = false) }
    }

    fun clearStation() {
        if (_state.value.isBitcoinRelayActive) return
        _state.update {
            it.copy(
                stationRole = null,
                isChoosingStation = false,
                step = DemoStep.HOME,
                bitcoinQueueIndex = 0,
                bitcoinQueueTotal = 0,
                bitcoinRelayProgress = BitcoinRelayProgress.IDLE,
                bitcoinStatusText = "Select a station queue",
                bitcoinSession = null,
                bitcoinCurrentChunk = 0,
                bitcoinTotalChunks = 0,
                bitcoinTxid = null,
                bitcoinBlockHeight = null,
            )
        }
    }

    fun selectRadio(address: String) {
        val radio = _state.value.discoveredRadios.firstOrNull { it.address == address && it.isBonded } ?: return
        val returnToOperator = _state.value.step == DemoStep.RADIO_SELECTION
        val keepCurrentConnection =
            radio.address == desiredRadioAddress && client?.connection?.value is ConnectionState.Connected
        discoveryJob?.cancel()
        discoveryJob = null
        radioChangeJob = null
        requireExplicitRadioSelection = false
        radioSelectionPreviousAddress = null
        radioSelectionPreviousName = null
        if (returnToOperator) _state.update { it.copy(step = DemoStep.OPERATOR) }
        saveSelectedRadio(radio)
        if (keepCurrentConnection) {
            _state.update {
                it.copy(
                    step = if (returnToOperator) DemoStep.OPERATOR else it.step,
                    radioStatus = RadioStatus.CONNECTED,
                    statusText = "Connected to ${radio.displayName}",
                    discoveredRadios = emptyList(),
                )
            }
            return
        }
        connectRadio(radio.address, radio.displayName)
    }

    fun changeRadio() {
        radioChangeJob?.cancel()
        radioChangeJob = null
        Log.i(TAG, "Change radio opened; preserving current=${_state.value.selectedRadioAddress}")
        discoveryJob?.cancel()
        discoveryJob = null
        radioSelectionPreviousAddress = _state.value.selectedRadioAddress
        radioSelectionPreviousName = _state.value.selectedRadioName
        requireExplicitRadioSelection = true
        _state.update {
            it.copy(
                step = DemoStep.RADIO_SELECTION,
                radioStatus = RadioStatus.SCANNING,
                statusText = "Checking paired radios…",
                discoveredRadios = emptyList(),
            )
        }
        discoverRadios()
    }

    fun cancelRadioChange() {
        if (_state.value.step != DemoStep.RADIO_SELECTION) return
        radioChangeJob?.cancel()
        radioChangeJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        requireExplicitRadioSelection = false
        radioSelectionPreviousAddress = null
        radioSelectionPreviousName = null
        val connection = client?.connection?.value
        _state.update { current ->
            val restored = connection?.let(current::withConnection) ?: current.copy(
                radioStatus = RadioStatus.DISCONNECTED,
                statusText = "Radio disconnected",
            )
            restored.copy(
                step = DemoStep.OPERATOR,
                discoveredRadios = emptyList(),
            )
        }
        Log.i(TAG, "Change radio cancelled; existing BLE session preserved")
    }

    private fun discoverRadios() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            val discovered = linkedMapOf<String, DiscoveredRadio>()
            _state.update {
                it.copy(
                    radioStatus = RadioStatus.SCANNING,
                    statusText = "Finding paired Meshtastic radios…",
                    discoveredRadios = emptyList(),
                )
            }

            val bonded = try {
                bondedMeshtasticRadios()
            } catch (exception: SecurityException) {
                permissionRequired()
                return@launch
            }
            // Keep progress visible long enough to acknowledge the participant's tap.
            delay(BLE_SCAN_FEEDBACK_MS)
            when {
                bonded.size == 1 && !requireExplicitRadioSelection -> {
                    val radio = bonded.single()
                    discoveryJob = null
                    saveSelectedRadio(radio)
                    connectRadio(radio.address, radio.displayName)
                    return@launch
                }
                bonded.isNotEmpty() -> {
                    discoveryJob = null
                    _state.update {
                        it.copy(
                            radioStatus = RadioStatus.SELECTION_REQUIRED,
                            statusText = "Choose the radio attached to this phone",
                            discoveredRadios = bonded,
                        )
                    }
                    return@launch
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                discoveryJob = null
                _state.update {
                    it.copy(
                        radioStatus = RadioStatus.NO_PAIRED_RADIO,
                        statusText = "No paired Meshtastic radio found",
                        discoveredRadios = emptyList(),
                    )
                }
                return@launch
            }

            try {
                val scanner = Scanner {
                    filters {
                        match { services = listOf(BleConstants.MESH_SERVICE_UUID) }
                    }
                }
                withTimeoutOrNull(BLE_SCAN_WINDOW_MS) {
                    scanner.advertisements.collect { advertisement ->
                        val radio = advertisement.toDiscoveredRadio()
                        discovered[radio.address] = radio
                        _state.update {
                            it.copy(
                                discoveredRadios = discovered.values.sortedByDescending {
                                    radio -> radio.rssi ?: Int.MIN_VALUE
                                },
                            )
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "BLE discovery failed", exception)
                _state.update {
                    it.copy(
                        radioStatus = RadioStatus.ERROR,
                        statusText = exception.message ?: "Could not scan for Meshtastic radios",
                    )
                }
                return@launch
            } finally {
                discoveryJob = null
            }

            val radios = discovered.values.toList()
            val paired = pairedRadios(radios)
            val automatic = singlePairedRadio(
                radios = radios,
                allowAutomaticSelection = !requireExplicitRadioSelection,
            )
            if (automatic != null) {
                saveSelectedRadio(automatic)
                connectRadio(automatic.address, automatic.displayName)
            } else {
                _state.update {
                    it.copy(
                        radioStatus = if (paired.isEmpty()) {
                            RadioStatus.NO_PAIRED_RADIO
                        } else {
                            RadioStatus.SELECTION_REQUIRED
                        },
                        statusText = if (paired.isEmpty()) {
                            "No paired Meshtastic radio found"
                        } else {
                            "Choose the radio attached to this phone"
                        },
                        discoveredRadios = paired,
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedMeshtasticRadios(): List<DiscoveredRadio> {
        val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList()
        return adapter.bondedDevices
            .mapNotNull { device ->
                val name = device.name
                if (!isMeshtasticBluetoothName(name)) return@mapNotNull null
                DiscoveredRadio(
                    address = device.address.uppercase(),
                    name = name,
                    rssi = null,
                    isBonded = true,
                )
            }
            .sortedBy(DiscoveredRadio::displayName)
    }

    private fun saveSelectedRadio(radio: DiscoveredRadio) {
        preferences.edit()
            .putString(SELECTED_RADIO_ADDRESS_KEY, radio.address)
            .putString(SELECTED_RADIO_NAME_KEY, radio.displayName)
            .apply()
        _state.update {
            it.copy(
                selectedRadioAddress = radio.address,
                selectedRadioName = radio.displayName,
                discoveredRadios = emptyList(),
            )
        }
    }

    private fun connectRadio(address: String, name: String?) {
        if (!isRadioBonded(address)) {
            stopForMissingBond(address, name)
            return
        }
        desiredRadioAddress = address
        desiredRadioName = name
        connectJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        connectJob = scope.launch {
            connectOnce(address, name, isRecovery = false)
        }
    }

    private suspend fun connectOnce(
        address: String,
        name: String?,
        isRecovery: Boolean,
        allowOperatorPairing: Boolean = false,
    ): Boolean {
        if (!allowOperatorPairing && !isRadioBonded(address)) {
            stopForMissingBond(address, name)
            return false
        }
        var alreadyConnected = false
        val radioClient = connectionMutex.withLock {
            if (desiredRadioAddress != address) return@withLock null
            if (client?.connection?.value is ConnectionState.Connected) {
                alreadyConnected = true
                return@withLock null
            }

            disconnectLocked()
            if (desiredRadioAddress != address) return@withLock null
            if (!allowOperatorPairing && !isRadioBonded(address)) {
                stopForMissingBond(address, name)
                return@withLock null
            }
            val displayName = name ?: _state.value.radioDisplayName
            val pairingInsideGatt = allowOperatorPairing && !isRadioBonded(address)
            _state.update {
                it.copy(
                    radioStatus = when {
                        pairingInsideGatt -> RadioStatus.CONNECTING
                        isRecovery -> RadioStatus.RECONNECTING
                        else -> RadioStatus.CONNECTING
                    },
                    statusText = when {
                        pairingInsideGatt -> "Pairing with $displayName · enter the code shown on the radio"
                        isRecovery -> "Restoring connection to $displayName…"
                        else -> "Connecting to $displayName…"
                    },
                )
            }

            RadioClient.Builder()
                .transport(
                    BleTransport(address = address) {
                        // Android's persistent GATT auto-connect can outlive our bond checks and
                        // reopen pairing after a radio has rejected the stored bond. Recovery is
                        // owned by this foreground service, so every connection is explicit.
                        autoConnectIf { false }
                    },
                )
                // SDK 0.1.0 enables a BLE heartbeat by default and tears down an otherwise
                // healthy idle link after 60 seconds when this firmware does not answer it.
                // Real GATT disconnects are still observed and handled by our recovery loop.
                .disableBleHeartbeat()
                .storage(SqlDelightStorageProvider(application.filesDir.absolutePath))
                .logger(SDK_LOGGER)
                .build()
                .also {
                    client = it
                    observe(it, address, name)
                }
        }
        if (alreadyConnected) return true
        radioClient ?: return false

        val displayName = name ?: _state.value.radioDisplayName
        return try {
            radioClient.connect()
            if (client !== radioClient || desiredRadioAddress != address) return false
            Log.i(TAG, "Connected station=${_state.value.stationName} radio=$displayName")
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "BLE connection failed", exception)
            if (client === radioClient && desiredRadioAddress == address) {
                if (!isRadioBonded(address)) {
                    stopForMissingBond(address, name)
                } else {
                    _state.update {
                        it.copy(
                            radioStatus = if (isRecovery) RadioStatus.RECONNECTING else RadioStatus.ERROR,
                            statusText = if (isRecovery) {
                                "Reconnect failed · trying again"
                            } else {
                                exception.message ?: "Could not connect to $displayName"
                            },
                        )
                    }
                }
            }
            false
        }
    }

    fun permissionRequired() {
        _state.update {
            it.copy(
                radioStatus = RadioStatus.PERMISSION_REQUIRED,
                statusText = "Bluetooth permission is required",
            )
        }
    }

    fun updateDraft(value: String) {
        _state.update { it.copy(draft = truncateUtf8(value)) }
    }

    fun start() {
        if (_state.value.isConnected) {
            _state.update {
                it.copy(
                    step = DemoStep.CONTACTS,
                    selectedRecipient = null,
                    sendProgress = SendProgress.IDLE,
                    sendStatusText = "",
                )
            }
        }
    }

    fun selectRecipient(id: String) {
        val recipient = _state.value.recipients.firstOrNull { it.id == id && it.isAvailable } ?: return
        _state.update {
            it.copy(
                step = DemoStep.COMPOSE,
                selectedRecipient = recipient,
                draft = "",
                sendProgress = SendProgress.IDLE,
                sendStatusText = "",
                lastPacketId = null,
            )
        }
    }

    fun returnToContacts() {
        _state.update {
            it.copy(
                step = DemoStep.CONTACTS,
                sendProgress = SendProgress.IDLE,
                sendStatusText = "",
                lastPacketId = null,
            )
        }
    }

    fun openOperator() {
        if (_state.value.isBitcoinRelayActive) return
        _state.update { it.copy(step = DemoStep.OPERATOR) }
    }

    fun returnToOperator() {
        if (_state.value.isBitcoinRelayActive) return
        _state.update { it.copy(step = DemoStep.OPERATOR) }
    }

    fun refreshPresence() {
        val radioClient = client ?: return
        if (!_state.value.isConnected) return
        presenceRequestJob?.cancel()
        presenceRequestJob = scope.launch {
            runCatching {
                radioClient.sendText(
                    text = PRESENCE_REQUEST,
                    to = NodeId.BROADCAST,
                    channel = ChannelIndex(0),
                )
            }.onFailure { Log.w(TAG, "Presence refresh failed", it) }
        }
    }

    fun reconnectRadio() {
        val address = _state.value.selectedRadioAddress?.takeIf(::isBluetoothAddress) ?: return
        val name = _state.value.selectedRadioName
        val needsPairing = !isRadioBonded(address)
        _state.update {
            it.copy(
                step = DemoStep.RADIO_RECONNECT,
                radioStatus = if (needsPairing) RadioStatus.CONNECTING else RadioStatus.RECONNECTING,
                statusText = if (needsPairing) {
                    "Opening a secure pairing session with ${name ?: "attached radio"}…"
                } else {
                    "Restarting the BLE link to ${name ?: "attached radio"}…"
                },
            )
        }
        desiredRadioAddress = address
        desiredRadioName = name
        connectJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        connectJob = scope.launch {
            // An operator action is the only path allowed to open GATT before Android has
            // a bond. Meshtastic then requests its PIN inside the live GATT session, matching
            // the original stable app flow. Automatic startup/recovery remain bond-only.
            val connected = connectOnce(
                address = address,
                name = name,
                isRecovery = !needsPairing,
                allowOperatorPairing = needsPairing,
            )
            if (!connected && desiredRadioAddress == address) {
                _state.update {
                    it.copy(
                        radioStatus = RadioStatus.ERROR,
                        statusText = "Could not reconnect to ${name ?: "attached radio"}",
                    )
                }
            }
        }
    }

    fun onBondStateChanged(address: String, previousState: Int, currentState: Int) {
        val selectedAddress = _state.value.selectedRadioAddress ?: return
        if (!selectedAddress.equals(address, ignoreCase = true)) return
        Log.i(TAG, "Bond state changed address=$address previous=$previousState current=$currentState")

        val storedBondRejected =
            previousState == BluetoothDevice.BOND_BONDED && currentState == BluetoothDevice.BOND_BONDING
        val bondRemoved = previousState != BluetoothDevice.BOND_NONE && currentState == BluetoothDevice.BOND_NONE
        if (!storedBondRejected && !bondRemoved) return

        val displayName = _state.value.selectedRadioName ?: _state.value.radioDisplayName
        desiredRadioAddress = null
        desiredRadioName = null
        connectJob?.cancel()
        connectJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        stopPresenceLoop()
        scope.launch {
            connectionMutex.withLock { disconnectLocked() }
            _state.update {
                it.copy(
                    radioStatus = RadioStatus.PAIRING_REQUIRED,
                    statusText = if (storedBondRejected) {
                        "$displayName rejected the stored bond · automatic recovery stopped"
                    } else {
                        "$displayName is not paired · use Reconnect attached radio to pair once"
                    },
                )
            }
        }
    }

    fun openBitcoin() {
        if (_state.value.canOpenBitcoin) {
            _state.update { it.copy(step = DemoStep.BITCOIN) }
        }
    }

    fun returnHome() {
        if (_state.value.isBitcoinRelayActive) return
        _state.update { it.copy(step = DemoStep.HOME) }
    }

    fun send() {
        val radioClient = client ?: return
        val sendState = _state.value
        val text = sendState.draft.trim()
        val recipient = sendState.selectedRecipient ?: return
        if (!sendState.canSend || text.isEmpty()) return
        val destination = recipient.nodeNumber?.let(::NodeId) ?: NodeId.BROADCAST

        _state.update {
            it.copy(
                sendProgress = SendProgress.QUEUED,
                sendStatusText = "Sending to ${recipient.displayName}…",
                lastPacketId = null,
            )
        }

        scope.launch {
            try {
                val handle = radioClient.sendText(
                    text = text,
                    to = destination,
                    channel = ChannelIndex(0),
                )
                val packetId = handle.id.toString()
                Log.i(
                    TAG,
                    "Queued message packet=$packetId recipient=${recipient.displayName} destination=$destination " +
                        "bytes=${text.encodeToByteArray().size}",
                )
                _state.update {
                    it.copy(
                        step = DemoStep.COMPOSE,
                        draft = "",
                        lastPacketId = packetId,
                        sent = (
                            listOf(
                                SentText(
                                    packetId = packetId,
                                    recipientNodeNumber = recipient.nodeNumber,
                                    recipient = recipient.displayName,
                                    text = text,
                                    isBroadcast = recipient.isBroadcast,
                                    recordedAtMs = System.currentTimeMillis(),
                                ),
                            ) + it.sent
                            ).take(MAX_MESSAGES),
                    )
                }
                handle.state
                    .onEach { packetState ->
                        val progress = packetState.toProgress()
                        _state.update {
                            it.copy(
                                sendProgress = progress,
                                sendStatusText = packetState.toParticipantText(recipient),
                            )
                        }
                        Log.i(TAG, "Packet=$packetId state=$packetState")
                    }
                    .first { it.toProgress() in TERMINAL_SEND_STATES }
            } catch (exception: Exception) {
                Log.e(TAG, "Send failed", exception)
                _state.update {
                    it.copy(
                        step = DemoStep.COMPOSE,
                        sendProgress = SendProgress.FAILED,
                        sendStatusText = exception.message ?: "Message could not be sent",
                    )
                }
            }
        }
    }

    fun relayNextBitcoinTransaction() {
        if (bitcoinRelayJob?.isActive == true || !_state.value.canRelayBitcoin) return
        val radioClient = client ?: return
        val role = _state.value.stationRole ?: return
        val signedTransactions = signedTransactionsByRole[role].orEmpty()
        val queueIndex = _state.value.bitcoinQueueIndex
        val rawHex = signedTransactions.getOrNull(queueIndex) ?: return

        bitcoinRelayJob = scope.launch {
            val session = newBitcoinSession(queueIndex)
            val chunks = chunkSignedTransaction(rawHex)
            _state.update {
                it.copy(
                    bitcoinRelayProgress = BitcoinRelayProgress.SENDING,
                    bitcoinStatusText = "Preparing transaction ${queueIndex + 1} of ${signedTransactions.size}",
                    bitcoinSession = session,
                    bitcoinCurrentChunk = 0,
                    bitcoinTotalChunks = chunks.size,
                    bitcoinTxid = null,
                    bitcoinBlockHeight = null,
                )
            }
            stopPresenceLoop()

            try {
                var slotReady = false
                var queuedForSlot = false
                for (attempt in 1..BITCOIN_SLOT_ATTEMPTS) {
                    if (attempt > 1) {
                        delay(Random.nextLong(BITCOIN_SLOT_RETRY_MIN_MS, BITCOIN_SLOT_RETRY_MAX_MS + 1))
                    }
                    _state.update {
                        it.copy(
                            bitcoinRelayProgress = if (queuedForSlot) {
                                BitcoinRelayProgress.QUEUED
                            } else {
                                BitcoinRelayProgress.REQUESTING_GATEWAY
                            },
                            bitcoinStatusText = if (queuedForSlot) {
                                "Gateway busy · waiting for this transaction's turn"
                            } else if (attempt == 1) {
                                "Requesting the Gateway upload slot"
                            } else {
                                "Retrying Gateway slot request · attempt $attempt"
                            },
                        )
                    }
                    val beginFrame = bitcoinBeginFrame(session, chunks.size)
                    val handle = radioClient.sendText(
                        text = beginFrame,
                        to = NodeId.BROADCAST,
                        channel = ChannelIndex(0),
                    )
                    Log.i(TAG, "Bitcoin session=$session slot request packet=${handle.id} attempt=$attempt")
                    val reply = awaitSlotReply(session, acceptQueued = !queuedForSlot)
                    when (reply) {
                        is BitcoinReply.SlotReady -> {
                            slotReady = true
                            break
                        }
                        is BitcoinReply.Queued -> {
                            queuedForSlot = true
                            _state.update {
                                it.copy(
                                    bitcoinRelayProgress = BitcoinRelayProgress.QUEUED,
                                    bitcoinStatusText = "Gateway busy · queue position ${reply.position}",
                                )
                            }
                        }
                        is BitcoinReply.Rejected -> throw BitcoinRelayException(reply.reason)
                        null -> Unit
                        else -> Unit
                    }
                }
                if (!slotReady) {
                    throw BitcoinRelayException("Gateway did not grant an upload slot")
                }

                chunks.forEachIndexed { zeroBasedIndex, payload ->
                    val chunkIndex = zeroBasedIndex + 1
                    val frame = bitcoinChunkFrame(session, chunkIndex, chunks.size, payload)
                    var acknowledged = false
                    var lastFailure = "Gateway did not acknowledge chunk $chunkIndex"

                    for (attempt in 1..BITCOIN_CHUNK_ATTEMPTS) {
                        _state.update {
                            it.copy(
                                bitcoinRelayProgress = BitcoinRelayProgress.SENDING,
                                bitcoinCurrentChunk = chunkIndex,
                                bitcoinStatusText = if (attempt == 1) {
                                    "Sending chunk $chunkIndex of ${chunks.size}"
                                } else {
                                    "Retrying chunk $chunkIndex · attempt $attempt"
                                },
                            )
                        }
                        try {
                            val handle = radioClient.sendText(
                                text = frame,
                                // Primary-channel broadcast is the proven path shared with the
                                // existing chat demo. The laptop gateway alone consumes BTC_TX
                                // frames and replies directly to this station radio.
                                to = NodeId.BROADCAST,
                                channel = ChannelIndex(0),
                            )
                            Log.i(
                                TAG,
                                "Bitcoin session=$session chunk=$chunkIndex/${chunks.size} packet=${handle.id} attempt=$attempt",
                            )
                            val radioState = withTimeoutOrNull(BITCOIN_RADIO_SEND_TIMEOUT_MS) {
                                handle.state.first { it != SendState.Queued }
                            }
                            Log.i(
                                TAG,
                                "Bitcoin session=$session chunk=$chunkIndex radioState=$radioState",
                            )
                            if (radioState is SendState.Failed) {
                                throw IllegalStateException("Radio rejected chunk $chunkIndex: ${radioState.reason}")
                            }
                            when (val reply = awaitChunkReply(session, chunkIndex)) {
                                is BitcoinReply.ChunkAcknowledged -> {
                                    acknowledged = true
                                    break
                                }
                                is BitcoinReply.Rejected -> throw BitcoinRelayException(reply.reason)
                                null -> lastFailure = "No gateway ACK for chunk $chunkIndex"
                                else -> Unit
                            }
                        } catch (exception: BitcoinRelayException) {
                            throw exception
                        } catch (exception: Exception) {
                            lastFailure = exception.message ?: "Could not send chunk $chunkIndex"
                            Log.w(TAG, "Bitcoin chunk send failed", exception)
                        }
                    }
                    if (!acknowledged) throw BitcoinRelayException(lastFailure)
                }

                _state.update {
                    it.copy(
                        bitcoinRelayProgress = BitcoinRelayProgress.WAITING_FOR_GATEWAY,
                        bitcoinStatusText = "Transaction received by Gateway node · waiting for Bitcoin Core",
                    )
                }
                var result: BitcoinReply.Result? = null
                for (attempt in 1..BITCOIN_RESULT_ATTEMPTS) {
                    _state.update {
                        it.copy(
                            bitcoinStatusText = if (attempt == 1) {
                                "Transaction received by Gateway node · waiting for Bitcoin Core"
                            } else {
                                "Checking confirmed result · attempt $attempt"
                            },
                        )
                    }
                    val handle = radioClient.sendText(
                        text = bitcoinResultRequestFrame(session),
                        to = NodeId.BROADCAST,
                        channel = ChannelIndex(0),
                    )
                    Log.i(TAG, "Bitcoin session=$session result request packet=${handle.id} attempt=$attempt")
                    result = awaitFinalReply<BitcoinReply.Result>(
                        session,
                        BITCOIN_RESULT_REPLY_TIMEOUT_MS,
                    )
                    if (result != null) break
                    if (attempt < BITCOIN_RESULT_ATTEMPTS) {
                        delay(
                            Random.nextLong(
                                BITCOIN_RESULT_RETRY_MIN_MS,
                                BITCOIN_RESULT_RETRY_MAX_MS + 1,
                            ),
                        )
                    }
                }
                val confirmedResult = result ?: throw BitcoinRelayException(
                    "Gateway node did not return the confirmed transaction result",
                )

                repeat(BITCOIN_RESULT_ACK_ATTEMPTS) { zeroBasedAttempt ->
                    runCatching {
                        radioClient.sendText(
                            text = bitcoinResultAcknowledgementFrame(session),
                            to = NodeId.BROADCAST,
                            channel = ChannelIndex(0),
                        )
                    }.onFailure { exception ->
                        if (exception is CancellationException) throw exception
                        Log.w(TAG, "Could not acknowledge Bitcoin result session=$session", exception)
                    }
                    if (zeroBasedAttempt + 1 < BITCOIN_RESULT_ACK_ATTEMPTS) {
                        delay(BITCOIN_RESULT_ACK_RETRY_MS)
                    }
                }

                val nextIndex = queueIndex + 1
                preferences.edit().putInt(nextTransactionPreferenceKey(role), nextIndex).apply()
                _state.update {
                    it.copy(
                        bitcoinQueueIndex = nextIndex,
                        bitcoinRelayProgress = BitcoinRelayProgress.CONFIRMED,
                        bitcoinStatusText = "Confirmed in Regtest block ${confirmedResult.blockHeight}",
                        bitcoinTxid = confirmedResult.txid,
                        bitcoinBlockHeight = confirmedResult.blockHeight,
                    )
                }
                Log.i(
                    TAG,
                    "Bitcoin session=$session confirmed txid=${confirmedResult.txid} height=${confirmedResult.blockHeight}",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: BitcoinRelayException) {
                Log.e(TAG, "Bitcoin relay failed session=$session", exception)
                _state.update {
                    it.copy(
                        bitcoinRelayProgress = BitcoinRelayProgress.FAILED,
                        bitcoinStatusText = "Relay failed: ${exception.message}",
                    )
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Bitcoin relay failed session=$session", exception)
                _state.update {
                    it.copy(
                        bitcoinRelayProgress = BitcoinRelayProgress.FAILED,
                        bitcoinStatusText = exception.message ?: "Transaction relay failed",
                    )
                }
            } finally {
                if (client === radioClient && _state.value.isConnected) {
                    startPresenceLoop(radioClient, initialDelayMs = PRESENCE_INTERVAL_MS)
                }
            }
        }
    }

    fun resetBitcoinQueue() {
        if (_state.value.isBitcoinRelayActive) return
        val role = _state.value.stationRole ?: return
        preferences.edit().putInt(nextTransactionPreferenceKey(role), 0).apply()
        _state.update {
            it.copy(
                bitcoinQueueIndex = 0,
                bitcoinRelayProgress = BitcoinRelayProgress.IDLE,
                bitcoinStatusText = "Signed transaction ready",
                bitcoinSession = null,
                bitcoinCurrentChunk = 0,
                bitcoinTotalChunks = 0,
                bitcoinTxid = null,
                bitcoinBlockHeight = null,
            )
        }
    }

    fun startOver() {
        _state.update {
            it.copy(
                step = DemoStep.HOME,
                selectedRecipient = null,
                draft = "",
                sendProgress = SendProgress.IDLE,
                sendStatusText = "",
                lastPacketId = null,
            )
        }
    }

    suspend fun shutdown() {
        radioChangeJob?.cancel()
        radioChangeJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        desiredRadioAddress = null
        desiredRadioName = null
        connectJob?.cancel()
        connectJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        connectionMutex.withLock { disconnectLocked() }
    }

    private fun observe(radioClient: RadioClient, address: String, name: String?) {
        var reachedConnected = false
        observerJobs += radioClient.connection
            .onEach { connection ->
                Log.i(TAG, "BLE state=$connection radio=${name ?: address}")
                _state.update { it.withConnection(connection) }
                if (connection is ConnectionState.Connected) {
                    reachedConnected = true
                    startPresenceLoop(radioClient)
                    startBleKeepalive(radioClient)
                }
                if (
                    connection is ConnectionState.Disconnected &&
                    reachedConnected &&
                    client === radioClient &&
                    desiredRadioAddress == address
                ) {
                    stopPresenceLoop()
                    stopBleKeepalive()
                    scheduleRecovery(address, name)
                }
            }
            .launchIn(scope)
        observerJobs += radioClient.nodes
            .onEach(::applyNodeChange)
            .launchIn(scope)
        observerJobs += radioClient.ownNode
            .onEach { node ->
                ownNodeNumber = node?.num?.takeIf { it != 0 }
                if (node != null && node.num != 0) rememberNode(node)
                _state.update { it.copy(ownNodeNumber = ownNodeNumber) }
                refreshRecipients()
            }
            .launchIn(scope)
        observerJobs += radioClient.textMessages
            .onEach { packet ->
                val text = packet.asText() ?: return@onEach
                when (val presence = parsePresenceFrame(text)) {
                    is PresenceFrame.Announcement -> {
                        recordPresence(presence, packet.from)
                        return@onEach
                    }
                    PresenceFrame.Request -> {
                        schedulePresenceResponse(radioClient)
                        return@onEach
                    }
                    null -> Unit
                }
                if (text.startsWith("BTC_")) {
                    val current = _state.value
                    if (
                        isGatewaySource(
                            nodeNumber = packet.from,
                            presences = current.presences,
                            expectedGatewayNodeNumber = current.gatewayNodeNumber,
                        )
                    ) {
                        val reply = parseBitcoinReply(text)
                        if (reply != null) {
                            bitcoinReplies.emit(reply)
                            Log.i(TAG, "Bitcoin reply=$reply")
                        }
                    }
                    // Bitcoin chunks and replies use the shared primary channel for
                    // reliability. They are reserved protocol traffic, not chat messages.
                    return@onEach
                }
                val advertisedName = knownNodes[packet.from]?.longName
                val sender = presenceName(packet.from, _state.value.presences)
                    ?: participantName(packet.from, advertisedName)
                val packetId = packet.id.toUInt().toString()
                val isBroadcast = packet.to == NodeId.BROADCAST.raw
                Log.i(TAG, "Received text packet=$packetId sender=$sender bytes=${text.encodeToByteArray().size}")
                _state.update {
                    it.copy(
                        received = (
                            listOf(
                                ReceivedText(
                                    packetId = packetId,
                                    senderNodeNumber = packet.from,
                                    sender = sender,
                                    text = text,
                                    isBroadcast = isBroadcast,
                                    recordedAtMs = System.currentTimeMillis(),
                                ),
                            ) + it.received
                            ).take(MAX_MESSAGES),
                    )
                }
            }
            .launchIn(scope)
    }

    private fun scheduleRecovery(address: String, name: String?) {
        if (recoveryJob?.isActive == true || desiredRadioAddress != address) return
        recoveryJob = scope.launch {
            var attempt = 1
            while (desiredRadioAddress == address) {
                if (!isRadioBonded(address)) {
                    stopForMissingBond(address, name)
                    return@launch
                }
                val delayMs = (RECOVERY_INITIAL_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                    .coerceAtMost(RECOVERY_MAX_DELAY_MS)
                Log.w(TAG, "Scheduling BLE recovery attempt=$attempt delayMs=$delayMs radio=${name ?: address}")
                _state.update {
                    it.copy(
                        radioStatus = RadioStatus.RECONNECTING,
                        statusText = "Radio link stale · reconnecting…",
                    )
                }
                delay(delayMs)
                if (!isRadioBonded(address)) {
                    stopForMissingBond(address, name)
                    return@launch
                }
                if (connectOnce(address, name, isRecovery = true)) return@launch
                attempt += 1
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isRadioBonded(address: String): Boolean {
        val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try {
            adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
        } catch (exception: IllegalArgumentException) {
            false
        } catch (exception: SecurityException) {
            permissionRequired()
            false
        }
    }

    private fun stopForMissingBond(address: String, name: String?) {
        if (desiredRadioAddress == address) {
            desiredRadioAddress = null
            desiredRadioName = null
        }
        recoveryJob = null
        stopPresenceLoop()
        stopBleKeepalive()
        val displayName = name ?: _state.value.radioDisplayName
        Log.w(TAG, "BLE recovery stopped; radio is not bonded address=$address name=$displayName")
        _state.update {
            it.copy(
                radioStatus = RadioStatus.PAIRING_REQUIRED,
                statusText = "$displayName is not paired · use Reconnect attached radio to pair once",
            )
        }
    }

    private fun applyNodeChange(change: NodeChange) {
        when (change) {
            is NodeChange.Snapshot -> {
                knownNodes.clear()
                change.nodes.values.forEach(::rememberNode)
            }
            is NodeChange.Added -> rememberNode(change.node)
            is NodeChange.Updated -> rememberNode(change.node)
            is NodeChange.Removed -> knownNodes.remove(change.nodeId.raw)
            is NodeChange.CameOnline,
            is NodeChange.WentOffline,
            -> Unit
        }
        refreshRecipients()
    }

    private fun rememberNode(node: NodeInfo) {
        val name = node.user?.long_name?.takeIf(String::isNotBlank) ?: NodeId(node.num).toString()
        knownNodes[node.num] = KnownMeshNode(node.num, name)
    }

    private fun refreshRecipients() {
        val role = _state.value.stationRole ?: return
        val nowMs = System.currentTimeMillis()
        _state.update { current ->
            val active = activePresences(current.presences, nowMs)
            val recipients = conferenceRecipients(
                role = role,
                nodes = knownNodes.values,
                ownNodeNumber = ownNodeNumber,
                presences = active,
                nowMs = nowMs,
            )
            val selected = current.selectedRecipient?.let { previous ->
                recipients.firstOrNull { it.id == previous.id }
            }
            current.copy(
                ownNodeNumber = ownNodeNumber,
                presences = active,
                recipients = recipients,
                selectedRecipient = selected,
            )
        }
    }

    private fun startPresenceLoop(
        radioClient: RadioClient,
        initialDelayMs: Long = PRESENCE_INITIAL_DELAY_MS,
    ) {
        if (presenceJob?.isActive == true && client === radioClient) return
        stopPresenceLoop()
        presenceJob = scope.launch {
            val stationOffset = (_state.value.stationRole?.presenceSlot ?: 0) * PRESENCE_STATION_OFFSET_MS
            delay(initialDelayMs + stationOffset)
            if (client !== radioClient || !_state.value.isConnected) return@launch
            sendPresence(radioClient)
            while (client === radioClient && _state.value.isConnected) {
                delay(PRESENCE_INTERVAL_MS)
                if (client === radioClient && _state.value.isConnected) sendPresence(radioClient)
            }
        }
    }

    private fun stopPresenceLoop() {
        presenceJob?.cancel()
        presenceJob = null
        presenceRequestJob?.cancel()
        presenceRequestJob = null
    }

    private fun startBleKeepalive(radioClient: RadioClient) {
        if (bleKeepaliveJob?.isActive == true && client === radioClient) return
        stopBleKeepalive()
        bleKeepaliveJob = scope.launch {
            while (client === radioClient && _state.value.isConnected) {
                delay(BLE_KEEPALIVE_INTERVAL_MS)
                if (client !== radioClient || !_state.value.isConnected) break
                val nonce = bleKeepaliveNonce
                bleKeepaliveNonce = if (nonce == Int.MAX_VALUE) 2 else nonce + 1
                runCatching {
                    radioClient.sendRaw(ToRadio(heartbeat = Heartbeat(nonce = nonce)))
                    Log.d(TAG, "BLE keepalive sent nonce=$nonce")
                }.onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    Log.w(TAG, "BLE keepalive failed nonce=$nonce", exception)
                }
            }
        }
    }

    private fun stopBleKeepalive() {
        bleKeepaliveJob?.cancel()
        bleKeepaliveJob = null
    }

    private fun schedulePresenceResponse(radioClient: RadioClient) {
        if (client !== radioClient || !_state.value.isConnected) return
        presenceRequestJob?.cancel()
        presenceRequestJob = scope.launch {
            delay(
                PRESENCE_REQUEST_RESPONSE_DELAY_MS +
                    (_state.value.stationRole?.presenceSlot ?: 0) * PRESENCE_STATION_OFFSET_MS,
            )
            if (client === radioClient && _state.value.isConnected) sendPresence(radioClient)
        }
    }

    private suspend fun sendPresence(radioClient: RadioClient) {
        val identity = _state.value.stationRole?.conferenceIdentity ?: return
        val frame = presenceAnnouncementFrame(identity, presenceSession)
        runCatching {
            val handle = radioClient.sendText(
                text = frame,
                to = NodeId.BROADCAST,
                channel = ChannelIndex(0),
            )
            ownNodeNumber?.let { nodeNumber ->
                recordPresence(PresenceFrame.Announcement(identity, presenceSession), nodeNumber)
            }
            Log.d(TAG, "Presence announced identity=$identity packet=${handle.id} node=$ownNodeNumber")
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            Log.w(TAG, "Presence announcement failed identity=$identity", exception)
        }
    }

    private fun recordPresence(announcement: PresenceFrame.Announcement, nodeNumber: Int) {
        if (nodeNumber == 0) return
        val nowMs = System.currentTimeMillis()
        _state.update { current ->
            val active = activePresences(current.presences, nowMs)
            current.copy(
                presences = active.filterNot { presence ->
                    presence.identity == announcement.identity &&
                        (presence.session == announcement.session || presence.nodeNumber == nodeNumber)
                } + StationPresence(
                    identity = announcement.identity,
                    nodeNumber = nodeNumber,
                    session = announcement.session,
                    lastSeenAtMs = nowMs,
                ),
            )
        }
        refreshRecipients()
    }

    private suspend fun disconnectLocked() {
        stopPresenceLoop()
        stopBleKeepalive()
        bitcoinRelayJob?.cancel()
        bitcoinRelayJob = null
        if (_state.value.isBitcoinRelayActive) {
            _state.update {
                it.copy(
                    bitcoinRelayProgress = BitcoinRelayProgress.FAILED,
                    bitcoinStatusText = "Relay interrupted · reconnect and retry",
                )
            }
        }
        observerJobs.forEach(Job::cancel)
        observerJobs.clear()
        knownNodes.clear()
        ownNodeNumber = null
        _state.update { it.copy(ownNodeNumber = null) }
        refreshRecipients()
        val oldClient = client
        client = null
        if (oldClient != null) {
            val completed = withTimeoutOrNull(BLE_DISCONNECT_TIMEOUT_MS) {
                try {
                    oldClient.disconnect()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.w(TAG, "BLE disconnect failed; continuing with clean session", exception)
                }
                true
            } ?: false
            if (!completed) {
                Log.w(TAG, "BLE disconnect timed out; continuing with clean session")
            }
        }
    }

    private suspend fun awaitChunkReply(session: String, chunk: Int): BitcoinReply? =
        withTimeoutOrNull(BITCOIN_CHUNK_TIMEOUT_MS) {
            bitcoinReplies.first { reply ->
                reply.session == session && (
                    reply is BitcoinReply.Rejected ||
                        reply is BitcoinReply.ChunkAcknowledged && reply.chunk == chunk
                    )
            }
        }

    private suspend fun awaitSlotReply(session: String, acceptQueued: Boolean): BitcoinReply? =
        withTimeoutOrNull(BITCOIN_SLOT_REPLY_TIMEOUT_MS) {
            bitcoinReplies.first { reply ->
                reply.session == session && (
                    reply is BitcoinReply.SlotReady ||
                        reply is BitcoinReply.Rejected ||
                        acceptQueued && reply is BitcoinReply.Queued
                    )
            }
        }

    private suspend inline fun <reified T : BitcoinReply> awaitFinalReply(
        session: String,
        timeoutMs: Long,
    ): T? {
        val reply = withTimeoutOrNull(timeoutMs) {
            bitcoinReplies.first {
                it.session == session && (it is T || it is BitcoinReply.Rejected)
            }
        } ?: return null
        if (reply is BitcoinReply.Rejected) throw BitcoinRelayException(reply.reason)
        return reply as T
    }

    private fun loadSignedTransactions(role: StationRole): List<String> =
        runCatching {
            application.assets.open(role.transactionAssetName).bufferedReader().useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .map { raw ->
                        chunkSignedTransaction(raw)
                        raw.lowercase()
                    }
                    .toList()
            }
        }.onFailure { Log.e(TAG, "Could not load ${role.storageId} Regtest transactions", it) }
            .getOrDefault(emptyList())

    private fun newBitcoinSession(queueIndex: Int): String {
        val timestamp = System.currentTimeMillis().toString(36)
        val queueId = _state.value.stationRole?.storageId ?: "station"
        // Keep the session compact so a 200-character payload remains below
        // Meshtastic's 233-byte text limit after the BTC_TX frame metadata.
        return "${queueId.take(1)}${queueIndex.toString(36)}$timestamp".take(12)
    }

    private companion object {
        const val TAG = "MeshtasticDemo"
        const val MAX_MESSAGES = 20
        const val PREFERENCES_NAME = "bitcoin_regtest_queue"
        const val SELECTED_RADIO_ADDRESS_KEY = "selected_radio_address"
        const val SELECTED_RADIO_NAME_KEY = "selected_radio_name"
        const val BLE_SCAN_WINDOW_MS = 5_000L
        const val BLE_SCAN_FEEDBACK_MS = 650L
        const val BLE_DISCONNECT_TIMEOUT_MS = 2_000L
        const val BITCOIN_CHUNK_ATTEMPTS = 3
        const val BITCOIN_SLOT_ATTEMPTS = 12
        const val BITCOIN_SLOT_REPLY_TIMEOUT_MS = 45_000L
        const val BITCOIN_SLOT_RETRY_MIN_MS = 2_000L
        const val BITCOIN_SLOT_RETRY_MAX_MS = 6_000L
        const val BITCOIN_RADIO_SEND_TIMEOUT_MS = 10_000L
        const val BITCOIN_CHUNK_TIMEOUT_MS = 30_000L
        const val BITCOIN_RESULT_ATTEMPTS = 6
        const val BITCOIN_RESULT_REPLY_TIMEOUT_MS = 25_000L
        const val BITCOIN_RESULT_RETRY_MIN_MS = 3_000L
        const val BITCOIN_RESULT_RETRY_MAX_MS = 7_000L
        const val BITCOIN_RESULT_ACK_ATTEMPTS = 2
        const val BITCOIN_RESULT_ACK_RETRY_MS = 1_000L
        const val RECOVERY_INITIAL_DELAY_MS = 1_000L
        const val RECOVERY_MAX_DELAY_MS = 15_000L
        const val PRESENCE_INITIAL_DELAY_MS = 5_000L
        const val PRESENCE_REQUEST_RESPONSE_DELAY_MS = 5_000L
        const val PRESENCE_STATION_OFFSET_MS = 5_000L
        const val PRESENCE_INTERVAL_MS = 5 * 60_000L
        const val BLE_KEEPALIVE_INTERVAL_MS = 20_000L
        val SDK_LOGGER = LogSink { level, tag, message, cause ->
            val sdkTag = "MeshtasticSDK/$tag"
            when (level) {
                LogLevel.NONE -> Unit
                LogLevel.VERBOSE -> Log.v(sdkTag, message, cause)
                LogLevel.DEBUG -> Log.d(sdkTag, message, cause)
                LogLevel.INFO -> Log.i(sdkTag, message, cause)
                LogLevel.WARN -> Log.w(sdkTag, message, cause)
                LogLevel.ERROR -> Log.e(sdkTag, message, cause)
            }
        }
        val TERMINAL_SEND_STATES = setOf(
            SendProgress.ACKNOWLEDGED,
            SendProgress.RELAYED_BY_MESH,
            SendProgress.FAILED,
        )

        fun initialState(
            stationRole: StationRole?,
            queueTotal: Int,
            queueIndex: Int,
            savedAddress: String?,
            savedName: String?,
        ): MeshDemoState = MeshDemoState(
            stationRole = stationRole,
            radioStatus = RadioStatus.DISCONNECTED,
            statusText = if (savedAddress != null && isBluetoothAddress(savedAddress)) {
                "Ready to connect to ${savedName ?: stationRole?.radioFallbackName ?: "attached radio"}"
            } else if (stationRole == null) {
                "Choose this phone's station"
            } else {
                "Ready to find the attached radio"
            },
            selectedRadioAddress = savedAddress?.takeIf(::isBluetoothAddress),
            selectedRadioName = savedName,
            recipients = stationRole?.let { conferenceRecipients(it, emptyList()) }.orEmpty(),
            bitcoinQueueTotal = queueTotal,
            bitcoinQueueIndex = queueIndex,
            bitcoinStatusText = if (queueTotal == 0) {
                "No signed transactions packaged"
            } else {
                "Signed transaction ready"
            },
        )
    }
}

private fun PlatformAdvertisement.toDiscoveredRadio(): DiscoveredRadio = DiscoveredRadio(
    address = address.uppercase(),
    name = name ?: peripheralName,
    rssi = rssi,
    isBonded = bondState == PlatformAdvertisement.BondState.Bonded,
)

private class BitcoinRelayException(message: String) : Exception(message)

private fun MeshDemoState.withConnection(connection: ConnectionState): MeshDemoState =
    when (connection) {
        ConnectionState.Disconnected -> copy(
            radioStatus = RadioStatus.DISCONNECTED,
            statusText = "Radio disconnected",
        )
        is ConnectionState.Connecting -> copy(
            radioStatus = RadioStatus.CONNECTING,
            statusText = "Connecting to $radioDisplayName…",
        )
        is ConnectionState.Configuring -> copy(
            radioStatus = RadioStatus.CONFIGURING,
            statusText = "Loading mesh ${(connection.progress * 100).toInt()}%",
        )
        ConnectionState.Connected -> copy(
            radioStatus = RadioStatus.CONNECTED,
            statusText = "Connected to $radioDisplayName",
        )
        is ConnectionState.Reconnecting -> copy(
            radioStatus = RadioStatus.RECONNECTING,
            statusText = "Reconnecting to $radioDisplayName…",
        )
    }

private fun SendState.toProgress(): SendProgress =
    when (this) {
        SendState.Queued -> SendProgress.QUEUED
        SendState.Sent -> SendProgress.SENT_TO_RADIO
        SendState.Acked -> SendProgress.ACKNOWLEDGED
        SendState.Delivered -> SendProgress.RELAYED_BY_MESH
        is SendState.Failed -> SendProgress.FAILED
    }

private fun SendState.toParticipantText(recipient: MeshRecipient): String =
    when (this) {
        SendState.Queued -> "Queued for the radio"
        SendState.Sent -> if (recipient.isBroadcast) {
            "Broadcast transmitted over LoRa"
        } else {
            "Travelling across the mesh to ${recipient.displayName}"
        }
        SendState.Acked -> if (recipient.isBroadcast) {
            "Broadcast accepted by the radio"
        } else {
            "${recipient.displayName} acknowledged the packet"
        }
        SendState.Delivered -> if (recipient.isBroadcast) {
            "Relayed by the mesh"
        } else {
            "Delivered to ${recipient.displayName}"
        }
        is SendState.Failed -> "Send failed: $reason"
    }
