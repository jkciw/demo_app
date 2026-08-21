/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.asText
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.textMessages
import org.meshtastic.sdk.transport.ble.BleTransport

class MeshtasticSession(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val signedTransactions = loadSignedTransactions()
    private val initialQueueIndex = preferences
        .getInt(NEXT_TRANSACTION_KEY, 0)
        .coerceIn(0, signedTransactions.size)
    private val _state = MutableStateFlow(initialState(signedTransactions.size, initialQueueIndex))
    val state: StateFlow<MeshDemoState> = _state.asStateFlow()

    private val connectionMutex = Mutex()
    private val observerJobs = mutableListOf<Job>()
    private val nodeNames = mutableMapOf<Int, String>()
    private val bitcoinReplies = MutableSharedFlow<BitcoinReply>(replay = 32, extraBufferCapacity = 32)
    private var client: RadioClient? = null
    private var bitcoinRelayJob: Job? = null

    fun connect() {
        scope.launch {
            connectionMutex.withLock {
                if (!isBluetoothAddress(StationConfig.bleAddress)) {
                    _state.update {
                        it.copy(
                            radioStatus = RadioStatus.NOT_PROVISIONED,
                            statusText = "${StationConfig.radioName} is not provisioned",
                        )
                    }
                    return@withLock
                }
                if (client?.connection?.value is ConnectionState.Connected) return@withLock

                disconnectLocked()
                _state.update {
                    it.copy(radioStatus = RadioStatus.CONNECTING, statusText = "Connecting to ${StationConfig.radioName}…")
                }

                val radioClient = RadioClient.Builder()
                    .transport(
                        BleTransport(address = StationConfig.bleAddress) {
                            autoConnectIf { true }
                        },
                    )
                    .storage(SqlDelightStorageProvider(application.filesDir.absolutePath))
                    .autoReconnect(AutoReconnectConfig())
                    .build()
                client = radioClient
                observe(radioClient)

                try {
                    radioClient.connect()
                    Log.i(TAG, "Connected station=${StationConfig.stationName} radio=${StationConfig.radioName}")
                } catch (exception: Exception) {
                    Log.e(TAG, "Initial BLE connection failed", exception)
                    _state.update {
                        it.copy(
                            radioStatus = RadioStatus.ERROR,
                            statusText = exception.message ?: "Could not connect to ${StationConfig.radioName}",
                        )
                    }
                }
            }
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
                preferences.edit().putInt(NEXT_TRANSACTION_KEY, nextIndex).apply()
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
        preferences.edit().putInt(NEXT_TRANSACTION_KEY, 0).apply()
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

    fun close() {
        scope.launch {
            connectionMutex.withLock { disconnectLocked() }
        }
    }

    private fun observe(radioClient: RadioClient) {
        observerJobs += radioClient.connection
            .onEach { connection -> _state.update { it.withConnection(connection) } }
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

    private fun loadSignedTransactions(): List<String> =
        runCatching {
            application.assets.open(BITCOIN_ASSET_NAME).bufferedReader().useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .map { raw ->
                        chunkSignedTransaction(raw)
                        raw.lowercase()
                    }
                    .toList()
            }
        }.onFailure { Log.e(TAG, "Could not load signed Regtest transactions", it) }
            .getOrDefault(emptyList())

    private fun newBitcoinSession(queueIndex: Int): String {
        val timestamp = System.currentTimeMillis().toString(36)
        return "${StationConfig.bitcoinQueueId.take(1)}${queueIndex.toString(36)}$timestamp".take(24)
    }

    private companion object {
        const val TAG = "MeshtasticDemo"
        const val MAX_MESSAGES = 20
        const val PREFERENCES_NAME = "bitcoin_regtest_queue"
        const val NEXT_TRANSACTION_KEY = "next_transaction"
        const val BITCOIN_ASSET_NAME = "regtest_transactions.txt"
        const val BITCOIN_CHUNK_ATTEMPTS = 3
        const val BITCOIN_RADIO_SEND_TIMEOUT_MS = 10_000L
        const val BITCOIN_CHUNK_TIMEOUT_MS = 30_000L
        const val BITCOIN_FINAL_TIMEOUT_MS = 60_000L
        val TERMINAL_SEND_STATES = setOf(
            SendProgress.ACKNOWLEDGED,
            SendProgress.RELAYED_BY_MESH,
            SendProgress.FAILED,
        )

        fun initialState(queueTotal: Int, queueIndex: Int): MeshDemoState =
            if (isBluetoothAddress(StationConfig.bleAddress)) {
                MeshDemoState(
                    bitcoinQueueTotal = queueTotal,
                    bitcoinQueueIndex = queueIndex,
                    bitcoinStatusText = if (queueTotal == 0) {
                        "No signed transactions packaged"
                    } else {
                        "Signed transaction ready"
                    },
                )
            } else {
                MeshDemoState(
                    radioStatus = RadioStatus.NOT_PROVISIONED,
                    statusText = "${StationConfig.radioName} is not provisioned",
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
}

private class BitcoinRelayException(message: String) : Exception(message)

private fun MeshDemoState.withConnection(connection: ConnectionState): MeshDemoState =
    when (connection) {
        ConnectionState.Disconnected -> copy(
            radioStatus = RadioStatus.DISCONNECTED,
            statusText = "Radio disconnected",
        )
        is ConnectionState.Connecting -> copy(
            radioStatus = RadioStatus.CONNECTING,
            statusText = "Connecting to ${StationConfig.radioName}…",
        )
        is ConnectionState.Configuring -> copy(
            radioStatus = RadioStatus.CONFIGURING,
            statusText = "Loading mesh ${(connection.progress * 100).toInt()}%",
        )
        ConnectionState.Connected -> copy(
            radioStatus = RadioStatus.CONNECTED,
            statusText = "Radio connected",
        )
        is ConnectionState.Reconnecting -> copy(
            radioStatus = RadioStatus.RECONNECTING,
            statusText = "Reconnecting to ${StationConfig.radioName}…",
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
