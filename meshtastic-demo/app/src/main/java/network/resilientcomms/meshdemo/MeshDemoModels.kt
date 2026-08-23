/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

internal const val MAX_TEXT_BYTES = 233
internal const val BITCOIN_CHUNK_HEX_CHARS = 100
internal const val LAPTOP_NODE_NUMBER = 0x2303A141
internal const val PRESENCE_TTL_MS = 10 * 60 * 1000L
internal const val PRESENCE_PREFIX = "DEMO_PRESENCE"
internal const val PRESENCE_REQUEST = "DEMO_PRESENCE_REQUEST|1"

enum class DemoStep {
    HOME,
    CONTACTS,
    COMPOSE,
    BITCOIN,
    OPERATOR,
    RADIO_SELECTION,
    RADIO_RECONNECT,
}

enum class ConferenceIdentity {
    ALICE,
    BOB,
    GATEWAY,
}

sealed interface PresenceFrame {
    data class Announcement(
        val identity: ConferenceIdentity,
        val session: String,
    ) : PresenceFrame

    data object Request : PresenceFrame
}

data class StationPresence(
    val identity: ConferenceIdentity,
    val nodeNumber: Int,
    val session: String,
    val lastSeenAtMs: Long,
)

enum class RecipientKind {
    PERSON,
    GATEWAY,
    EVERYONE,
}

data class MeshRecipient(
    val id: String,
    val nodeNumber: Int?,
    val displayName: String,
    val description: String,
    val kind: RecipientKind,
    val isAvailable: Boolean,
) {
    val isBroadcast: Boolean get() = kind == RecipientKind.EVERYONE
}

data class KnownMeshNode(
    val nodeNumber: Int,
    val longName: String,
)

enum class RadioStatus {
    PERMISSION_REQUIRED,
    SCANNING,
    NO_PAIRED_RADIO,
    PAIRING_REQUIRED,
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
    REQUESTING_GATEWAY,
    QUEUED,
    SENDING,
    WAITING_FOR_GATEWAY,
    BROADCAST,
    CONFIRMED,
    FAILED,
}

sealed interface BitcoinReply {
    val session: String

    data class SlotReady(
        override val session: String,
    ) : BitcoinReply

    data class Queued(
        override val session: String,
        val position: Int,
    ) : BitcoinReply

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

    data class Result(
        override val session: String,
        val txid: String,
        val blockHeight: Int,
    ) : BitcoinReply

    data class Rejected(
        override val session: String,
        val reason: String,
    ) : BitcoinReply
}

data class ReceivedText(
    val packetId: String,
    val senderNodeNumber: Int,
    val sender: String,
    val text: String,
    val isBroadcast: Boolean,
    val recordedAtMs: Long,
)

data class SentText(
    val packetId: String,
    val recipientNodeNumber: Int?,
    val recipient: String,
    val text: String,
    val isBroadcast: Boolean,
    val recordedAtMs: Long,
)

data class MeshDemoState(
    val stationRole: StationRole? = null,
    val isChoosingStation: Boolean = false,
    val step: DemoStep = DemoStep.HOME,
    val radioStatus: RadioStatus = RadioStatus.DISCONNECTED,
    val statusText: String = "Radio disconnected",
    val selectedRadioAddress: String? = null,
    val selectedRadioName: String? = null,
    val ownNodeNumber: Int? = null,
    val discoveredRadios: List<DiscoveredRadio> = emptyList(),
    val presences: List<StationPresence> = emptyList(),
    val recipients: List<MeshRecipient> = emptyList(),
    val selectedRecipient: MeshRecipient? = null,
    val draft: String = "",
    val sendProgress: SendProgress = SendProgress.IDLE,
    val sendStatusText: String = "",
    val lastPacketId: String? = null,
    val received: List<ReceivedText> = emptyList(),
    val sent: List<SentText> = emptyList(),
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
    val stationName: String get() = stationRole?.stationName ?: "SELECT STATION"
    val isConnected: Boolean get() = radioStatus == RadioStatus.CONNECTED
    val radioDisplayName: String
        get() = selectedRadioName ?: stationRole?.radioFallbackName ?: "Attached radio"
    val draftBytes: Int get() = draft.encodeToByteArray().size
    val canSend: Boolean
        get() = stationRole != null && isConnected && selectedRecipient?.isAvailable == true &&
            draft.isNotBlank() && draftBytes <= MAX_TEXT_BYTES &&
            sendProgress !in setOf(SendProgress.QUEUED, SendProgress.SENT_TO_RADIO)
    val bitcoinRemaining: Int get() = (bitcoinQueueTotal - bitcoinQueueIndex).coerceAtLeast(0)
    val gatewayRecipient: MeshRecipient?
        get() = recipients.firstOrNull { it.kind == RecipientKind.GATEWAY }
    val gatewayNodeNumber: Int? get() = gatewayRecipient?.nodeNumber
    val isGatewayAvailable: Boolean
        get() = gatewayRecipient?.isAvailable == true && gatewayNodeNumber != null
    val canOpenBitcoin: Boolean
        get() = stationRole != null && isConnected && isGatewayAvailable
    val isBitcoinRelayActive: Boolean
        get() = bitcoinRelayProgress in setOf(
            BitcoinRelayProgress.REQUESTING_GATEWAY,
            BitcoinRelayProgress.QUEUED,
            BitcoinRelayProgress.SENDING,
            BitcoinRelayProgress.WAITING_FOR_GATEWAY,
            BitcoinRelayProgress.BROADCAST,
        )
    val canRelayBitcoin: Boolean
        get() = canOpenBitcoin && bitcoinRemaining > 0 && !isBitcoinRelayActive
}

internal val StationRole.conferenceIdentity: ConferenceIdentity
    get() = when (this) {
        StationRole.ALPHA -> ConferenceIdentity.ALICE
        StationRole.BRAVO -> ConferenceIdentity.BOB
    }

internal val StationRole.peerIdentity: ConferenceIdentity
    get() = when (this) {
        StationRole.ALPHA -> ConferenceIdentity.BOB
        StationRole.BRAVO -> ConferenceIdentity.ALICE
    }

internal fun presenceAnnouncementFrame(identity: ConferenceIdentity, session: String): String {
    require(Regex("^[A-Za-z0-9_-]{1,24}$").matches(session))
    return "$PRESENCE_PREFIX|1|${identity.name}|$session"
}

internal fun parsePresenceFrame(text: String): PresenceFrame? {
    val trimmed = text.trim()
    if (trimmed == PRESENCE_REQUEST) return PresenceFrame.Request
    val parts = trimmed.split('|')
    if (parts.size != 4 || parts[0] != PRESENCE_PREFIX || parts[1] != "1") return null
    val identity = runCatching { ConferenceIdentity.valueOf(parts[2]) }.getOrNull() ?: return null
    val session = parts[3].takeIf { Regex("^[A-Za-z0-9_-]{1,24}$").matches(it) } ?: return null
    return PresenceFrame.Announcement(identity, session)
}

internal fun activePresences(
    presences: Collection<StationPresence>,
    nowMs: Long,
): List<StationPresence> = presences.filter { nowMs - it.lastSeenAtMs <= PRESENCE_TTL_MS }

internal fun latestPresence(
    identity: ConferenceIdentity,
    presences: Collection<StationPresence>,
    nowMs: Long,
): StationPresence? = activePresences(presences, nowMs)
    .filter { it.identity == identity }
    .maxByOrNull(StationPresence::lastSeenAtMs)

internal fun presenceConflicts(
    presences: Collection<StationPresence>,
    nowMs: Long,
): Set<ConferenceIdentity> = activePresences(presences, nowMs)
    .groupBy(StationPresence::identity)
    .filterValues { entries -> entries.map(StationPresence::nodeNumber).distinct().size > 1 }
    .keys

internal fun presenceName(
    nodeNumber: Int,
    presences: Collection<StationPresence>,
    nowMs: Long = System.currentTimeMillis(),
): String? = activePresences(presences, nowMs)
    .filter { it.nodeNumber == nodeNumber }
    .maxByOrNull(StationPresence::lastSeenAtMs)
    ?.identity
    ?.displayName

internal val ConferenceIdentity.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

internal fun conferenceRecipients(
    role: StationRole,
    nodes: Collection<KnownMeshNode>,
    ownNodeNumber: Int? = null,
    presences: Collection<StationPresence> = emptyList(),
    nowMs: Long = System.currentTimeMillis(),
): List<MeshRecipient> {
    val peerRole = if (role == StationRole.ALPHA) StationRole.BRAVO else StationRole.ALPHA
    val peerName = peerRole.stationName.titleCaseDisplay()
    val peerPresence = latestPresence(role.peerIdentity, presences, nowMs)
    val peerNode = peerPresence?.let { KnownMeshNode(it.nodeNumber, peerName) }
        ?: nodes.firstOrNull { it.isConferenceNode(peerRole) } ?: ownNodeNumber?.let { ownNode ->
        nodes.firstOrNull {
            it.nodeNumber != ownNode &&
                it.nodeNumber != LAPTOP_NODE_NUMBER &&
                normalizeNodeName(it.longName) !in GATEWAY_NODE_NAMES
        }
    }
    val gatewayPresence = latestPresence(ConferenceIdentity.GATEWAY, presences, nowMs)
    val gatewayNode = gatewayPresence?.let { KnownMeshNode(it.nodeNumber, "Gateway") } ?: nodes.firstOrNull {
        it.nodeNumber == LAPTOP_NODE_NUMBER || normalizeNodeName(it.longName) in GATEWAY_NODE_NAMES
    }
    return listOf(
        MeshRecipient(
            id = peerRole.storageId,
            nodeNumber = peerNode?.nodeNumber,
            displayName = peerName,
            description = when {
                peerPresence != null -> "Direct message · app presence active"
                peerNode != null -> "Direct message · mesh node discovered"
                else -> "Waiting to see $peerName on the mesh"
            },
            kind = RecipientKind.PERSON,
            isAvailable = peerNode != null,
        ),
        MeshRecipient(
            id = "gateway",
            nodeNumber = gatewayNode?.nodeNumber,
            displayName = "Gateway",
            description = when {
                gatewayPresence != null -> "Big screen · Gateway presence active"
                gatewayNode != null -> "Big screen · Gateway mesh node discovered"
                else -> "Waiting to see Gateway on the mesh"
            },
            kind = RecipientKind.GATEWAY,
            isAvailable = gatewayNode != null,
        ),
        MeshRecipient(
            id = "everyone",
            nodeNumber = null,
            displayName = "Everyone",
            description = "Public conference-channel broadcast",
            kind = RecipientKind.EVERYONE,
            isAvailable = true,
        ),
    )
}

internal fun participantName(nodeNumber: Int, advertisedName: String?): String = when {
    nodeNumber == LAPTOP_NODE_NUMBER -> "Gateway"
    normalizeNodeName(advertisedName.orEmpty()) in ALICE_NODE_NAMES -> "Alice"
    normalizeNodeName(advertisedName.orEmpty()) in BOB_NODE_NAMES -> "Bob"
    normalizeNodeName(advertisedName.orEmpty()) in GATEWAY_NODE_NAMES -> "Gateway"
    else -> advertisedName?.takeIf(String::isNotBlank) ?: NodeIdLabel.from(nodeNumber)
}

internal fun isGatewaySource(
    nodeNumber: Int,
    presences: Collection<StationPresence>,
    nowMs: Long = System.currentTimeMillis(),
    expectedGatewayNodeNumber: Int? = null,
): Boolean = nodeNumber == LAPTOP_NODE_NUMBER ||
    nodeNumber == expectedGatewayNodeNumber ||
    latestPresence(ConferenceIdentity.GATEWAY, presences, nowMs)?.nodeNumber == nodeNumber

private fun KnownMeshNode.isConferenceNode(role: StationRole): Boolean {
    val normalized = normalizeNodeName(longName)
    return when (role) {
        StationRole.ALPHA -> normalized in ALICE_NODE_NAMES
        StationRole.BRAVO -> normalized in BOB_NODE_NAMES
    }
}

private fun normalizeNodeName(value: String): String = value
    .trim()
    .uppercase()
    .replace(Regex("[^A-Z0-9]+"), "-")
    .trim('-')

private fun String.titleCaseDisplay(): String = lowercase().replaceFirstChar(Char::uppercase)

private object NodeIdLabel {
    fun from(nodeNumber: Int): String = "!${nodeNumber.toUInt().toString(16)}"
}

private val ALICE_NODE_NAMES = setOf("ALICE", "MESH-ALPHA", "ALPHA")
private val BOB_NODE_NAMES = setOf("BOB", "MESH-BRAVO", "BRAVO")
private val GATEWAY_NODE_NAMES = setOf("GATEWAY", "LAPTOP-GATEWAY", "BIG-SCREEN")

internal fun nextTransactionPreferenceKey(role: StationRole): String =
    "next_transaction_${role.storageId}"

internal fun pairedRadios(radios: Collection<DiscoveredRadio>): List<DiscoveredRadio> =
    radios
        .filter(DiscoveredRadio::isBonded)
        .sortedWith(
            compareByDescending<DiscoveredRadio> { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.displayName },
        )

internal fun singlePairedRadio(
    radios: Collection<DiscoveredRadio>,
    allowAutomaticSelection: Boolean = true,
): DiscoveredRadio? = if (allowAutomaticSelection) pairedRadios(radios).singleOrNull() else null

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

internal fun bitcoinBeginFrame(session: String, total: Int): String {
    require(Regex("^[A-Za-z0-9_-]{1,24}$").matches(session))
    require(total > 0)
    return "BTC_BEGIN|$session|$total".also {
        require(it.encodeToByteArray().size <= MAX_TEXT_BYTES)
    }
}

internal fun bitcoinResultAcknowledgementFrame(session: String): String {
    require(Regex("^[A-Za-z0-9_-]{1,24}$").matches(session))
    return "BTC_RESULT_ACK|$session"
}

internal fun bitcoinResultRequestFrame(session: String): String {
    require(Regex("^[A-Za-z0-9_-]{1,24}$").matches(session))
    return "BTC_RESULT_REQUEST|$session"
}

internal fun parseBitcoinReply(text: String): BitcoinReply? {
    val parts = text.trim().split('|')
    if (parts.size !in 2..4) return null
    val session = parts[1].takeIf { Regex("^[A-Za-z0-9_-]{1,24}$").matches(it) } ?: return null
    return when (parts[0]) {
        "BTC_READY" -> if (parts.size == 2) BitcoinReply.SlotReady(session) else null
        "BTC_QUEUED" -> if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it > 0 }?.let { BitcoinReply.Queued(session, it) }
        } else {
            null
        }
        "BTC_CHUNK_ACK" -> if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it > 0 }?.let {
                BitcoinReply.ChunkAcknowledged(session, it)
            }
        } else {
            null
        }
        "BTC_ACK" -> if (parts.size == 3) {
            parts[2].takeIf { Regex("^[0-9A-Fa-f]{64}$").matches(it) }?.let {
                BitcoinReply.Broadcast(session, it.lowercase())
            }
        } else {
            null
        }
        "BTC_CONF" -> if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it >= 0 }?.let {
                BitcoinReply.Confirmed(session, it)
            }
        } else {
            null
        }
        "BTC_RESULT" -> if (parts.size == 4) {
            val txid = parts[2].takeIf { Regex("^[0-9A-Fa-f]{64}$").matches(it) }
            val blockHeight = parts[3].toIntOrNull()?.takeIf { it >= 0 }
            if (txid != null && blockHeight != null) {
                BitcoinReply.Result(session, txid.lowercase(), blockHeight)
            } else {
                null
            }
        } else {
            null
        }
        "BTC_NACK" -> if (parts.size == 3) {
            parts[2].takeIf(String::isNotBlank)?.let { BitcoinReply.Rejected(session, it) }
        } else {
            null
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
