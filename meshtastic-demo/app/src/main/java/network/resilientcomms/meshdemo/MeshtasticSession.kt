/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.annotation.SuppressLint
import android.app.Application
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
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.AutoReconnectConfig
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
    private val nodeNames = mutableMapOf<Int, String>()
    private val bitcoinReplies = MutableSharedFlow<BitcoinReply>(replay = 32, extraBufferCapacity = 32)
    private var client: RadioClient? = null
    private var bitcoinRelayJob: Job? = null
    private var discoveryJob: Job? = null
    private var recoveryJob: Job? = null
    private var desiredRadioAddress: String? = null
    private var desiredRadioName: String? = null

    fun connect() {
        if (_state.value.stationRole == null) return
        val selectedAddress = _state.value.selectedRadioAddress
        if (selectedAddress != null && isBluetoothAddress(selectedAddress)) {
            connectRadio(selectedAddress, _state.value.selectedRadioName)
        } else {
            discoverRadios()
        }
    }

    fun selectStation(role: StationRole) {
        if (_state.value.isBitcoinRelayActive) return
        val transactions = signedTransactionsByRole[role].orEmpty()
        val queueIndex = preferences
            .getInt(nextTransactionPreferenceKey(role), 0)
            .coerceIn(0, transactions.size)
        _state.update {
            it.copy(
                stationRole = role,
                step = DemoStep.HOME,
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
    }

    fun clearStation() {
        if (_state.value.isBitcoinRelayActive) return
        _state.update {
            it.copy(
                stationRole = null,
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
        discoveryJob?.cancel()
        discoveryJob = null
        saveSelectedRadio(radio)
        connectRadio(radio.address, radio.displayName)
    }

    fun changeRadio() {
        scope.launch {
            discoveryJob?.cancel()
            discoveryJob = null
            desiredRadioAddress = null
            desiredRadioName = null
            recoveryJob?.cancel()
            recoveryJob = null
            connectionMutex.withLock {
                disconnectLocked()
            }
            preferences.edit()
                .remove(SELECTED_RADIO_ADDRESS_KEY)
                .remove(SELECTED_RADIO_NAME_KEY)
                .apply()
            _state.update {
                it.copy(
                    step = DemoStep.HOME,
                    radioStatus = RadioStatus.DISCONNECTED,
                    statusText = "Ready to find the attached radio",
                    selectedRadioAddress = null,
                    selectedRadioName = null,
                    discoveredRadios = emptyList(),
                )
            }
            discoverRadios()
        }
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
            when {
                bonded.size == 1 -> {
                    val radio = bonded.single()
                    discoveryJob = null
                    saveSelectedRadio(radio)
                    connectRadio(radio.address, radio.displayName)
                    return@launch
                }
                bonded.size > 1 -> {
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
            val automatic = singlePairedRadio(radios)
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
        desiredRadioAddress = address
        desiredRadioName = name
        recoveryJob?.cancel()
        recoveryJob = null
        scope.launch {
            connectOnce(address, name, isRecovery = false)
        }
    }

    private suspend fun connectOnce(address: String, name: String?, isRecovery: Boolean): Boolean =
        connectionMutex.withLock {
            if (desiredRadioAddress != address) return@withLock false
            if (client?.connection?.value is ConnectionState.Connected) return@withLock true

            disconnectLocked()
            val displayName = name ?: _state.value.radioDisplayName
            _state.update {
                it.copy(
                    radioStatus = if (isRecovery) RadioStatus.RECONNECTING else RadioStatus.CONNECTING,
                    statusText = if (isRecovery) {
                        "Restoring connection to $displayName…"
                    } else {
                        "Connecting to $displayName…"
                    },
                )
            }

            val radioClient = RadioClient.Builder()
                .transport(
                    BleTransport(address = address) {
                        autoConnectIf { true }
                    },
                )
                .storage(SqlDelightStorageProvider(application.filesDir.absolutePath))
                .logger(SDK_LOGGER)
                .autoReconnect(AutoReconnectConfig())
                .build()
            client = radioClient
            observe(radioClient, address, name)

            try {
                radioClient.connect()
                Log.i(TAG, "Connected station=${_state.value.stationName} radio=$displayName")
                true
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "BLE connection failed", exception)
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
            _state.update { it.copy(step = DemoStep.COMPOSE, sendProgress = SendProgress.IDLE, sendStatusText = "") }
        }
    }

    fun send() {
        val radioClient = client ?: return
        val text = _state.value.draft.trim()
        if (!_state.value.canSend || text.isEmpty()) return

        scope.launch {
            try {
                val handle = radioClient.sendText(
                    text = text,
                    to = NodeId.BROADCAST,
                    channel = ChannelIndex(0),
                )
                val packetId = handle.id.toString()
                Log.i(TAG, "Queued broadcast packet=$packetId bytes=${text.encodeToByteArray().size}")
                _state.update { it.copy(step = DemoStep.RESULT, lastPacketId = packetId) }
                handle.state
                    .onEach { sendState ->
                        val progress = sendState.toProgress()
                        _state.update {
                            it.copy(
                                sendProgress = progress,
                                sendStatusText = sendState.toParticipantText(),
                            )
                        }
                        Log.i(TAG, "Packet=$packetId state=$sendState")
                    }
                    .first { it.toProgress() in TERMINAL_SEND_STATES }
            } catch (exception: Exception) {
                Log.e(TAG, "Send failed", exception)
                _state.update {
                    it.copy(
                        step = DemoStep.RESULT,
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

            try {
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
                        bitcoinStatusText = "Transaction received by laptop · waiting for Bitcoin Core",
                    )
                }
                val broadcast = awaitFinalReply<BitcoinReply.Broadcast>(session)
                    ?: throw BitcoinRelayException("Laptop did not return a transaction ID")
                _state.update {
                    it.copy(
                        bitcoinRelayProgress = BitcoinRelayProgress.BROADCAST,
                        bitcoinStatusText = "Broadcast accepted · mining confirmation",
                        bitcoinTxid = broadcast.txid,
                    )
                }
                val confirmation = awaitFinalReply<BitcoinReply.Confirmed>(session)
                    ?: throw BitcoinRelayException("Laptop did not return a block confirmation")

                val nextIndex = queueIndex + 1
                preferences.edit().putInt(nextTransactionPreferenceKey(role), nextIndex).apply()
                _state.update {
                    it.copy(
                        bitcoinQueueIndex = nextIndex,
                        bitcoinRelayProgress = BitcoinRelayProgress.CONFIRMED,
                        bitcoinStatusText = "Confirmed in Regtest block ${confirmation.blockHeight}",
                        bitcoinBlockHeight = confirmation.blockHeight,
                    )
                }
                Log.i(TAG, "Bitcoin session=$session confirmed txid=${broadcast.txid} height=${confirmation.blockHeight}")
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
                draft = "CODEX TEST",
                sendProgress = SendProgress.IDLE,
                sendStatusText = "",
                lastPacketId = null,
                received = emptyList(),
            )
        }
    }

    suspend fun shutdown() {
        discoveryJob?.cancel()
        discoveryJob = null
        desiredRadioAddress = null
        desiredRadioName = null
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
                if (connection is ConnectionState.Connected) reachedConnected = true
                if (
                    connection is ConnectionState.Disconnected &&
                    reachedConnected &&
                    client === radioClient &&
                    desiredRadioAddress == address
                ) {
                    scheduleRecovery(address, name)
                }
            }
            .launchIn(scope)
        observerJobs += radioClient.nodes
            .onEach(::applyNodeChange)
            .launchIn(scope)
        observerJobs += radioClient.textMessages
            .onEach { packet ->
                val text = packet.asText() ?: return@onEach
                if (packet.from == LAPTOP_NODE_NUMBER && text.startsWith("BTC_")) {
                    val reply = parseBitcoinReply(text)
                    if (reply != null) {
                        bitcoinReplies.emit(reply)
                        Log.i(TAG, "Bitcoin reply=$reply")
                        return@onEach
                    }
                }
                if (text.startsWith("BTC_TX|")) {
                    // A second participant phone may hear the broadcast chunks. They are
                    // gateway protocol traffic, not participant chat messages.
                    return@onEach
                }
                val sender = nodeNames[packet.from] ?: NodeId(packet.from).toString()
                val packetId = packet.id.toUInt().toString()
                Log.i(TAG, "Received text packet=$packetId sender=$sender bytes=${text.encodeToByteArray().size}")
                _state.update {
                    it.copy(
                        received = (listOf(ReceivedText(packetId, sender, text)) + it.received).take(MAX_MESSAGES),
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
                if (connectOnce(address, name, isRecovery = true)) return@launch
                attempt += 1
            }
        }
    }

    private fun applyNodeChange(change: NodeChange) {
        when (change) {
            is NodeChange.Snapshot -> {
                nodeNames.clear()
                change.nodes.values.forEach(::rememberNode)
            }
            is NodeChange.Added -> rememberNode(change.node)
            is NodeChange.Updated -> rememberNode(change.node)
            is NodeChange.Removed -> nodeNames.remove(change.nodeId.raw)
            is NodeChange.CameOnline,
            is NodeChange.WentOffline,
            -> Unit
        }
    }

    private fun rememberNode(node: NodeInfo) {
        val name = node.user?.long_name?.takeIf(String::isNotBlank) ?: NodeId(node.num).toString()
        nodeNames[node.num] = name
    }

    private suspend fun disconnectLocked() {
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
        nodeNames.clear()
        val oldClient = client
        client = null
        runCatching { oldClient?.disconnect() }
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

    private suspend inline fun <reified T : BitcoinReply> awaitFinalReply(session: String): T? {
        val reply = withTimeoutOrNull(BITCOIN_FINAL_TIMEOUT_MS) {
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
        return "${queueId.take(1)}${queueIndex.toString(36)}$timestamp".take(24)
    }

    private companion object {
        const val TAG = "MeshtasticDemo"
        const val MAX_MESSAGES = 20
        const val PREFERENCES_NAME = "bitcoin_regtest_queue"
        const val SELECTED_RADIO_ADDRESS_KEY = "selected_radio_address"
        const val SELECTED_RADIO_NAME_KEY = "selected_radio_name"
        const val BLE_SCAN_WINDOW_MS = 5_000L
        const val BITCOIN_CHUNK_ATTEMPTS = 3
        const val BITCOIN_RADIO_SEND_TIMEOUT_MS = 10_000L
        const val BITCOIN_CHUNK_TIMEOUT_MS = 30_000L
        const val BITCOIN_FINAL_TIMEOUT_MS = 60_000L
        const val RECOVERY_INITIAL_DELAY_MS = 1_000L
        const val RECOVERY_MAX_DELAY_MS = 15_000L
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

private fun SendState.toParticipantText(): String =
    when (this) {
        SendState.Queued -> "Queued for the radio"
        SendState.Sent -> "Transmitted over LoRa"
        SendState.Acked -> "Acknowledged by the destination"
        SendState.Delivered -> "Relayed by the mesh"
        is SendState.Failed -> "Send failed: $reason"
    }
