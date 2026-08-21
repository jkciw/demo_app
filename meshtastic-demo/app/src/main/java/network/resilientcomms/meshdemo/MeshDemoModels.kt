/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

internal const val MAX_TEXT_BYTES = 233
internal const val BITCOIN_CHUNK_HEX_CHARS = 100
internal const val LAPTOP_NODE_NUMBER = 0x2303A141

enum class DemoStep {
    HOME,
    COMPOSE,
    RESULT,
}

enum class RadioStatus {
    PERMISSION_REQUIRED,
    SCANNING,
    NO_PAIRED_RADIO,
    SELECTION_REQUIRED,
    DISCONNECTED,
    CONNECTING,
    CONFIGURING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class DiscoveredRadio(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val isBonded: Boolean,
) {
    val displayName: String
        get() = name?.takeIf(String::isNotBlank) ?: "Meshtastic ${address.takeLast(5)}"
}

enum class SendProgress {
    IDLE,
    QUEUED,
    SENT_TO_RADIO,
    ACKNOWLEDGED,
    RELAYED_BY_MESH,
    FAILED,
}

enum class BitcoinRelayProgress {
    IDLE,
    SENDING,
    WAITING_FOR_GATEWAY,
    BROADCAST,
    CONFIRMED,
    FAILED,
}

sealed interface BitcoinReply {
    val session: String

    data class ChunkAcknowledged(
        override val session: String,
        val chunk: Int,
    ) : BitcoinReply

    data class Broadcast(
        override val session: String,
        val txid: String,
    ) : BitcoinReply

    data class Confirmed(
        override val session: String,
        val blockHeight: Int,
    ) : BitcoinReply

    data class Rejected(
        override val session: String,
        val reason: String,
    ) : BitcoinReply
}

data class ReceivedText(
    val packetId: String,
    val sender: String,
    val text: String,
)

data class MeshDemoState(
    val step: DemoStep = DemoStep.HOME,
    val radioStatus: RadioStatus = RadioStatus.DISCONNECTED,
    val statusText: String = "Radio disconnected",
    val selectedRadioAddress: String? = null,
    val selectedRadioName: String? = null,
    val discoveredRadios: List<DiscoveredRadio> = emptyList(),
    val draft: String = "CODEX TEST",
    val sendProgress: SendProgress = SendProgress.IDLE,
    val sendStatusText: String = "",
    val lastPacketId: String? = null,
    val received: List<ReceivedText> = emptyList(),
    val bitcoinQueueIndex: Int = 0,
    val bitcoinQueueTotal: Int = 0,
    val bitcoinRelayProgress: BitcoinRelayProgress = BitcoinRelayProgress.IDLE,
    val bitcoinStatusText: String = "Signed transaction ready",
    val bitcoinSession: String? = null,
    val bitcoinCurrentChunk: Int = 0,
    val bitcoinTotalChunks: Int = 0,
    val bitcoinTxid: String? = null,
    val bitcoinBlockHeight: Int? = null,
) {
    val isConnected: Boolean get() = radioStatus == RadioStatus.CONNECTED
    val radioDisplayName: String get() = selectedRadioName ?: StationConfig.radioName
    val draftBytes: Int get() = draft.encodeToByteArray().size
    val canSend: Boolean
        get() = isConnected && draft.isNotBlank() && draftBytes <= MAX_TEXT_BYTES &&
            sendProgress !in setOf(SendProgress.QUEUED, SendProgress.SENT_TO_RADIO)
    val bitcoinRemaining: Int get() = (bitcoinQueueTotal - bitcoinQueueIndex).coerceAtLeast(0)
    val isBitcoinRelayActive: Boolean
        get() = bitcoinRelayProgress in setOf(
            BitcoinRelayProgress.SENDING,
            BitcoinRelayProgress.WAITING_FOR_GATEWAY,
            BitcoinRelayProgress.BROADCAST,
        )
    val canRelayBitcoin: Boolean
        get() = isConnected && bitcoinRemaining > 0 && !isBitcoinRelayActive
}

internal fun pairedRadios(radios: Collection<DiscoveredRadio>): List<DiscoveredRadio> =
    radios
        .filter(DiscoveredRadio::isBonded)
        .sortedWith(
            compareByDescending<DiscoveredRadio> { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.displayName },
        )

internal fun singlePairedRadio(radios: Collection<DiscoveredRadio>): DiscoveredRadio? =
    pairedRadios(radios).singleOrNull()

internal fun chunkSignedTransaction(
    rawHex: String,
    chunkCharacters: Int = BITCOIN_CHUNK_HEX_CHARS,
): List<String> {
    require(chunkCharacters > 0 && chunkCharacters % 2 == 0)
    require(rawHex.isNotEmpty() && rawHex.length % 2 == 0)
    require(rawHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
    return rawHex.lowercase().chunked(chunkCharacters)
}

internal fun bitcoinChunkFrame(
    session: String,
    index: Int,
    total: Int,
    payload: String,
): String {
    require(Regex("^[A-Za-z0-9_-]{1,24}$").matches(session))
    require(index in 1..total)
    return "BTC_TX|$session|$index/$total|$payload".also {
        require(it.encodeToByteArray().size <= MAX_TEXT_BYTES)
    }
}

internal fun parseBitcoinReply(text: String): BitcoinReply? {
    val parts = text.trim().split('|')
    if (parts.size != 3) return null
    val session = parts[1].takeIf { Regex("^[A-Za-z0-9_-]{1,24}$").matches(it) } ?: return null
    return when (parts[0]) {
        "BTC_CHUNK_ACK" -> parts[2].toIntOrNull()?.takeIf { it > 0 }?.let {
            BitcoinReply.ChunkAcknowledged(session, it)
        }
        "BTC_ACK" -> parts[2].takeIf { Regex("^[0-9A-Fa-f]{64}$").matches(it) }?.let {
            BitcoinReply.Broadcast(session, it.lowercase())
        }
        "BTC_CONF" -> parts[2].toIntOrNull()?.takeIf { it >= 0 }?.let {
            BitcoinReply.Confirmed(session, it)
        }
        "BTC_NACK" -> parts[2].takeIf(String::isNotBlank)?.let {
            BitcoinReply.Rejected(session, it)
        }
        else -> null
    }
}

internal fun truncateUtf8(value: String, maxBytes: Int = MAX_TEXT_BYTES): String {
    if (value.encodeToByteArray().size <= maxBytes) return value
    val result = StringBuilder()
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val character = String(Character.toChars(codePoint))
        val candidate = result.toString() + character
        if (candidate.encodeToByteArray().size > maxBytes) break
        result.append(character)
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

internal fun isBluetoothAddress(value: String): Boolean =
    Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(value)

internal fun isMeshtasticBluetoothName(value: String?): Boolean =
    value?.startsWith("Meshtastic", ignoreCase = true) == true
