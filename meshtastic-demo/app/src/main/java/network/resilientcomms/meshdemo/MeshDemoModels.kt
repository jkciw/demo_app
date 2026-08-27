/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

internal const val MAX_TEXT_BYTES = 233
internal const val BITCOIN_CHUNK_HEX_CHARS = 200
internal const val DEMO_BITCOIN_INPUT_SATS = 1_000_000L
internal const val LAPTOP_NODE_NUMBER = 0x2303A141
internal const val PRESENCE_TTL_MS = 10 * 60 * 1000L
internal const val PRESENCE_PREFIX = "DEMO_PRESENCE"
internal const val PRESENCE_REQUEST = "DEMO_PRESENCE_REQUEST|1"
internal const val GATEWAY_REQUEST_PREFIX = "DEMO_GATEWAY|1"

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
    CHARLIE,
    DANA,
    GATEWAY,
}

sealed interface PresenceFrame {
    data class Announcement(
        val identity: ConferenceIdentity,
        val session: String,
    ) : PresenceFrame

    data object Request : PresenceFrame
}

data class GatewayRequest(
    val identity: ConferenceIdentity,
    val text: String,
)

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
    val recipientKind: RecipientKind,
    val text: String,
    val isBroadcast: Boolean,
    val recordedAtMs: Long,
)

data class BitcoinTransactionPreview(
    val outputSats: Long,
    val feeSats: Long?,
    val inputCount: Int,
    val outputCount: Int,
    val sizeBytes: Int,
    val loRaChunks: Int,
    val isSegwit: Boolean,
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
    val bitcoinPreview: BitcoinTransactionPreview? = null,
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
    val draftByteLimit: Int
        get() = if (selectedRecipient?.kind == RecipientKind.GATEWAY) {
            MAX_TEXT_BYTES - gatewayRequestOverhead(stationRole?.conferenceIdentity)
        } else {
            MAX_TEXT_BYTES
        }
    val canSend: Boolean
        get() = stationRole != null && isConnected && selectedRecipient?.isAvailable == true &&
            draft.isNotBlank() && draftBytes <= draftByteLimit &&
            sendProgress !in setOf(SendProgress.QUEUED, SendProgress.SENT_TO_RADIO)
    val bitcoinRemaining: Int get() = (bitcoinQueueTotal - bitcoinQueueIndex).coerceAtLeast(0)
    val gatewayRecipient: MeshRecipient?
        get() = recipients.firstOrNull { it.kind == RecipientKind.GATEWAY }
    val gatewayNodeNumber: Int? get() = gatewayRecipient?.nodeNumber
    val isGatewayAvailable: Boolean
        get() = gatewayRecipient?.isAvailable == true && gatewayNodeNumber != null
    val canOpenBitcoin: Boolean
        get() = stationRole != null && isConnected
    val isBitcoinRelayActive: Boolean
        get() = bitcoinRelayProgress in setOf(
            BitcoinRelayProgress.REQUESTING_GATEWAY,
            BitcoinRelayProgress.QUEUED,
            BitcoinRelayProgress.SENDING,
            BitcoinRelayProgress.WAITING_FOR_GATEWAY,
            BitcoinRelayProgress.BROADCAST,
        )
    val canCancelBitcoinRelay: Boolean
        get() = bitcoinRelayProgress in setOf(
            BitcoinRelayProgress.REQUESTING_GATEWAY,
            BitcoinRelayProgress.QUEUED,
            BitcoinRelayProgress.SENDING,
        )
    val canLeaveBitcoinScreen: Boolean
        get() = !isBitcoinRelayActive || bitcoinRelayProgress in setOf(
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
        StationRole.CHARLIE -> ConferenceIdentity.CHARLIE
        StationRole.DANA -> ConferenceIdentity.DANA
    }

internal val StationRole.peerRoles: List<StationRole>
    get() = StationRole.entries.filterNot { it == this }

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

internal fun gatewayRequestFrame(identity: ConferenceIdentity, text: String): String {
    require(identity != ConferenceIdentity.GATEWAY)
    val body = text.trim()
    require(body.isNotEmpty())
    return "$GATEWAY_REQUEST_PREFIX|${identity.name}|$body".also {
        require(it.encodeToByteArray().size <= MAX_TEXT_BYTES)
    }
}

internal fun parseGatewayRequest(text: String): GatewayRequest? {
    val parts = text.trim().split('|', limit = 4)
    if (parts.size != 4 || "${parts[0]}|${parts[1]}" != GATEWAY_REQUEST_PREFIX) return null
    val identity = runCatching { ConferenceIdentity.valueOf(parts[2]) }.getOrNull()
        ?.takeUnless { it == ConferenceIdentity.GATEWAY }
        ?: return null
    val body = parts[3].trim().takeIf(String::isNotEmpty) ?: return null
    if (text.encodeToByteArray().size > MAX_TEXT_BYTES) return null
    return GatewayRequest(identity, body)
}

private fun gatewayRequestOverhead(identity: ConferenceIdentity?): Int =
    "$GATEWAY_REQUEST_PREFIX|${identity?.name ?: ConferenceIdentity.CHARLIE.name}|"
        .encodeToByteArray()
        .size

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
    val gatewayPresence = latestPresence(ConferenceIdentity.GATEWAY, presences, nowMs)
    val gatewayNode = gatewayPresence?.let { KnownMeshNode(it.nodeNumber, "Gateway") } ?: nodes.firstOrNull {
        it.nodeNumber == LAPTOP_NODE_NUMBER || normalizeNodeName(it.longName) in GATEWAY_NODE_NAMES
    }
    val peers = role.peerRoles
    val peerPresences = peers.associateWith { peer ->
        latestPresence(peer.conferenceIdentity, presences, nowMs)
    }
    val peerNodes = peers.associateWith { peer ->
        val presence = peerPresences.getValue(peer)
        presence?.let { KnownMeshNode(it.nodeNumber, peer.stationName) }
            ?: nodes.firstOrNull { it.isConferenceNode(peer) }
    }.toMutableMap()

    // Preserve the proven two-phone fallback when generic Meshtastic radio names
    // are present before app presence arrives. Four-way identity is intentionally
    // presence-driven because generic radio names cannot be mapped safely.
    val legacyPeer = when (role) {
        StationRole.ALPHA -> StationRole.BRAVO
        StationRole.BRAVO -> StationRole.ALPHA
        StationRole.CHARLIE,
        StationRole.DANA,
        -> null
    }
    if (legacyPeer != null && peerNodes[legacyPeer] == null && ownNodeNumber != null) {
        val claimedNodes = peerNodes.values.mapNotNull { it?.nodeNumber }.toSet()
        val genericCandidates = nodes.filter { node ->
            node.nodeNumber != ownNodeNumber &&
                node.nodeNumber != gatewayNode?.nodeNumber &&
                node.nodeNumber != LAPTOP_NODE_NUMBER &&
                node.nodeNumber !in claimedNodes &&
                normalizeNodeName(node.longName) !in ALL_CONFERENCE_NODE_NAMES &&
                normalizeNodeName(node.longName) !in GATEWAY_NODE_NAMES
        }
        if (genericCandidates.size == 1) peerNodes[legacyPeer] = genericCandidates.single()
    }

    val people = peers.map { peer ->
        val peerName = peer.stationName
        val peerPresence = peerPresences.getValue(peer)
        val peerNode = peerNodes[peer]
        MeshRecipient(
            id = peer.storageId,
            nodeNumber = peerNode?.nodeNumber,
            displayName = peerName,
            description = when {
                peerPresence != null -> "Direct message · app presence active"
                peerNode != null -> "Direct message · mesh node discovered"
                else -> "Waiting to see $peerName on the mesh"
            },
            kind = RecipientKind.PERSON,
            isAvailable = peerNode != null,
        )
    }
    return people + listOf(
        MeshRecipient(
            id = "gateway",
            nodeNumber = gatewayNode?.nodeNumber,
            displayName = "Gateway",
            description = when {
                gatewayPresence != null -> "Big screen · Gateway recently announced"
                gatewayNode != null -> "Big screen · Gateway radio discovered"
                else -> "Big screen · availability checked when you send"
            },
            kind = RecipientKind.GATEWAY,
            // Presence is useful operator telemetry, but it must not block the
            // participant experience. Chat and Bitcoin both have their own real
            // send/response path, which is the authoritative reachability check.
            isAvailable = true,
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
    normalizeNodeName(advertisedName.orEmpty()) in CHARLIE_NODE_NAMES -> "Charlie"
    normalizeNodeName(advertisedName.orEmpty()) in DANA_NODE_NAMES -> "Dana"
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
        StationRole.CHARLIE -> normalized in CHARLIE_NODE_NAMES
        StationRole.DANA -> normalized in DANA_NODE_NAMES
    }
}

private fun normalizeNodeName(value: String): String = value
    .trim()
    .uppercase()
    .replace(Regex("[^A-Z0-9]+"), "-")
    .trim('-')

private object NodeIdLabel {
    fun from(nodeNumber: Int): String = "!${nodeNumber.toUInt().toString(16)}"
}

private val ALICE_NODE_NAMES = setOf("ALICE", "MESH-ALPHA", "ALPHA")
private val BOB_NODE_NAMES = setOf("BOB", "MESH-BRAVO", "BRAVO")
private val CHARLIE_NODE_NAMES = setOf("CHARLIE", "MESH-CHARLIE")
private val DANA_NODE_NAMES = setOf("DANA", "MESH-DANA")
private val ALL_CONFERENCE_NODE_NAMES =
    ALICE_NODE_NAMES + BOB_NODE_NAMES + CHARLIE_NODE_NAMES + DANA_NODE_NAMES
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

internal fun bitcoinTransactionPreview(
    rawHex: String,
    inputValueSats: Long = DEMO_BITCOIN_INPUT_SATS,
): BitcoinTransactionPreview? = runCatching {
    val normalized = rawHex.trim().lowercase()
    chunkSignedTransaction(normalized)
    val bytes = ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    val reader = BitcoinByteReader(bytes)
    reader.skip(4) // version
    val isSegwit = reader.remaining >= 2 && reader.peek() == 0 && reader.peek(1) != 0
    if (isSegwit) reader.skip(2) // marker and flag

    val inputCount = reader.readCompactSizeAsInt()
    repeat(inputCount) {
        reader.skip(32 + 4) // previous transaction hash and output index
        reader.skip(reader.readCompactSizeAsInt()) // scriptSig
        reader.skip(4) // sequence
    }

    val outputCount = reader.readCompactSizeAsInt()
    var outputSats = 0L
    repeat(outputCount) {
        val value = reader.readLittleEndian(8)
        require(value >= 0 && outputSats <= Long.MAX_VALUE - value)
        outputSats += value
        reader.skip(reader.readCompactSizeAsInt()) // scriptPubKey
    }

    if (isSegwit) {
        repeat(inputCount) {
            repeat(reader.readCompactSizeAsInt()) {
                reader.skip(reader.readCompactSizeAsInt())
            }
        }
    }
    reader.skip(4) // lock time
    require(reader.remaining == 0)

    BitcoinTransactionPreview(
        outputSats = outputSats,
        feeSats = (inputValueSats - outputSats).takeIf { it >= 0 },
        inputCount = inputCount,
        outputCount = outputCount,
        sizeBytes = bytes.size,
        loRaChunks = chunkSignedTransaction(normalized).size,
        isSegwit = isSegwit,
    )
}.getOrNull()

private class BitcoinByteReader(private val bytes: ByteArray) {
    private var position = 0
    val remaining: Int get() = bytes.size - position

    fun peek(offset: Int = 0): Int {
        require(offset >= 0 && position + offset < bytes.size)
        return bytes[position + offset].toInt() and 0xff
    }

    fun skip(count: Int) {
        require(count >= 0 && count <= remaining)
        position += count
    }

    fun readLittleEndian(byteCount: Int): Long {
        require(byteCount in 1..8 && byteCount <= remaining)
        var value = 0L
        repeat(byteCount) { shift ->
            value = value or ((bytes[position++].toLong() and 0xff) shl (shift * 8))
        }
        return value
    }

    fun readCompactSizeAsInt(): Int {
        val value = when (val prefix = readLittleEndian(1).toInt()) {
            in 0..0xfc -> prefix.toLong()
            0xfd -> readLittleEndian(2)
            0xfe -> readLittleEndian(4)
            else -> readLittleEndian(8)
        }
        require(value in 0..Int.MAX_VALUE.toLong())
        return value.toInt()
    }
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

internal fun bitcoinCancelFrame(session: String): String {
    require(Regex("^[A-Za-z0-9_-]{1,24}$").matches(session))
    return "BTC_CANCEL|$session"
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
