/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshDemoModelsTest {
    @Test
    fun truncateUtf8_preservesByteLimitWithoutSplittingCharacters() {
        val value = "a".repeat(231) + "₹" + "z"

        val result = truncateUtf8(value)

        assertEquals("a".repeat(231), result)
        assertTrue(result.encodeToByteArray().size <= MAX_TEXT_BYTES)
    }

    @Test
    fun truncateUtf8_doesNotSplitEmojiSurrogatePair() {
        val value = "a".repeat(231) + "😀"

        val result = truncateUtf8(value)

        assertEquals("a".repeat(231), result)
        assertFalse(result.contains('\uFFFD'))
    }

    @Test
    fun bluetoothAddress_requiresCanonicalAndroidMacAddress() {
        assertTrue(isBluetoothAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(isBluetoothAddress("aa:bb:cc:dd:ee:ff"))
        assertFalse(isBluetoothAddress(""))
        assertFalse(isBluetoothAddress("LT1"))
    }

    @Test
    fun meshtasticBluetoothName_acceptsConferenceRadioNames() {
        assertTrue(isMeshtasticBluetoothName("Meshtastic_e8e8"))
        assertTrue(isMeshtasticBluetoothName("meshtastic_063c"))
        assertFalse(isMeshtasticBluetoothName("Headphones"))
        assertFalse(isMeshtasticBluetoothName(null))
    }

    @Test
    fun onePairedRadio_isAutomaticallySelected() {
        val attached = DiscoveredRadio("AA:BB:CC:DD:EE:01", "LT1", -48, isBonded = true)
        val unpaired = DiscoveredRadio("AA:BB:CC:DD:EE:02", "LT2", -35, isBonded = false)

        assertEquals(attached, singlePairedRadio(listOf(attached, unpaired)))
    }

    @Test
    fun multiplePairedRadios_requireSelectionEvenWhenOneIsStronger() {
        val attached = DiscoveredRadio("AA:BB:CC:DD:EE:01", "LT1", -42, isBonded = true)
        val nearby = DiscoveredRadio("AA:BB:CC:DD:EE:02", "LT2", -70, isBonded = true)

        assertEquals(null, singlePairedRadio(listOf(attached, nearby)))
        assertEquals(listOf(attached, nearby), pairedRadios(listOf(nearby, attached)))
    }

    @Test
    fun operatorChangeRadio_neverAutomaticallyReselectsTheOnlyPairedRadio() {
        val current = DiscoveredRadio("AA:BB:CC:DD:EE:01", "Meshtastic current", -42, isBonded = true)

        assertEquals(
            null,
            singlePairedRadio(listOf(current), allowAutomaticSelection = false),
        )
        assertEquals(current, singlePairedRadio(listOf(current), allowAutomaticSelection = true))
    }

    @Test
    fun unpairedRadios_areNeverAutomaticallySelected() {
        val radio = DiscoveredRadio("AA:BB:CC:DD:EE:01", "LT1", -40, isBonded = false)

        assertEquals(null, singlePairedRadio(listOf(radio)))
        assertTrue(pairedRadios(listOf(radio)).isEmpty())
    }

    @Test
    fun sendIsEnabledOnlyForConnectedValidDraft() {
        val bob = MeshRecipient("bravo", 42, "Bob", "Direct message", RecipientKind.PERSON, true)
        val connected = MeshDemoState(
            stationRole = StationRole.ALPHA,
            radioStatus = RadioStatus.CONNECTED,
            selectedRecipient = bob,
            draft = "CODEX TEST",
        )

        assertTrue(connected.canSend)
        assertFalse(connected.copy(radioStatus = RadioStatus.DISCONNECTED).canSend)
        assertFalse(connected.copy(draft = " ").canSend)
        assertFalse(connected.copy(sendProgress = SendProgress.QUEUED).canSend)
        assertFalse(connected.copy(selectedRecipient = bob.copy(isAvailable = false)).canSend)
    }

    @Test
    fun signedTransaction_isSplitIntoMeshtasticSafeFrames() {
        val rawHex = "ab".repeat(191)

        val chunks = chunkSignedTransaction(rawHex)
        val frames = chunks.mapIndexed { index, chunk ->
            bitcoinChunkFrame("alpha1", index + 1, chunks.size, chunk)
        }

        assertEquals(listOf(100, 100, 100, 82), chunks.map(String::length))
        assertTrue(frames.all { it.encodeToByteArray().size <= MAX_TEXT_BYTES })
        assertEquals(rawHex, chunks.joinToString(""))
    }

    @Test
    fun bitcoinReplies_parseAcknowledgementBroadcastConfirmationAndFailure() {
        assertEquals("BTC_BEGIN|alpha1|4", bitcoinBeginFrame("alpha1", 4))
        assertEquals("BTC_RESULT_ACK|alpha1", bitcoinResultAcknowledgementFrame("alpha1"))
        assertEquals(
            BitcoinReply.SlotReady("alpha1"),
            parseBitcoinReply("BTC_READY|alpha1"),
        )
        assertEquals(
            BitcoinReply.Queued("alpha1", 1),
            parseBitcoinReply("BTC_QUEUED|alpha1|1"),
        )
        assertEquals(
            BitcoinReply.ChunkAcknowledged("alpha1", 2),
            parseBitcoinReply("BTC_CHUNK_ACK|alpha1|2"),
        )
        assertEquals(
            BitcoinReply.Broadcast("alpha1", "a".repeat(64)),
            parseBitcoinReply("BTC_ACK|alpha1|${"A".repeat(64)}"),
        )
        assertEquals(
            BitcoinReply.Confirmed("alpha1", 105),
            parseBitcoinReply("BTC_CONF|alpha1|105"),
        )
        assertEquals(
            BitcoinReply.Result("alpha1", "b".repeat(64), 106),
            parseBitcoinReply("BTC_RESULT|alpha1|${"B".repeat(64)}|106"),
        )
        assertEquals(
            BitcoinReply.Rejected("alpha1", "inputs-spent"),
            parseBitcoinReply("BTC_NACK|alpha1|inputs-spent"),
        )
        assertEquals(null, parseBitcoinReply("ordinary message"))
        assertEquals(null, parseBitcoinReply("BTC_ACK|alpha1|not-a-txid"))
        assertEquals(null, parseBitcoinReply("BTC_RESULT|alpha1|not-a-txid|106"))
    }

    @Test
    fun bitcoinRelayOnlyEnablesForConnectedStationWithRemainingTransaction() {
        val gateway = MeshRecipient(
            id = "gateway",
            nodeNumber = 77,
            displayName = "Gateway",
            description = "Gateway presence active",
            kind = RecipientKind.GATEWAY,
            isAvailable = true,
        )
        val ready = MeshDemoState(
            stationRole = StationRole.BRAVO,
            radioStatus = RadioStatus.CONNECTED,
            recipients = listOf(gateway),
            bitcoinQueueTotal = 2,
            bitcoinQueueIndex = 0,
        )

        assertTrue(ready.canOpenBitcoin)
        assertTrue(ready.canRelayBitcoin)
        assertFalse(ready.copy(radioStatus = RadioStatus.DISCONNECTED).canRelayBitcoin)
        assertFalse(ready.copy(bitcoinQueueIndex = 2).canRelayBitcoin)
        assertFalse(ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.SENDING).canRelayBitcoin)
        assertFalse(ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.QUEUED).canRelayBitcoin)
        assertFalse(ready.copy(recipients = emptyList()).canOpenBitcoin)
        assertFalse(ready.copy(recipients = listOf(gateway.copy(isAvailable = false))).canRelayBitcoin)
    }

    @Test
    fun stationRoles_haveIndependentNamesAssetsAndQueueProgress() {
        assertEquals(StationRole.ALPHA, StationRole.fromStorageId("alpha"))
        assertEquals(StationRole.BRAVO, StationRole.fromStorageId("bravo"))
        assertEquals(null, StationRole.fromStorageId("unknown"))
        assertFalse(StationRole.ALPHA.transactionAssetName == StationRole.BRAVO.transactionAssetName)
        assertEquals("Alice", StationRole.ALPHA.stationName)
        assertEquals("Bob", StationRole.BRAVO.stationName)
        assertEquals("next_transaction_alpha", nextTransactionPreferenceKey(StationRole.ALPHA))
        assertEquals("next_transaction_bravo", nextTransactionPreferenceKey(StationRole.BRAVO))
    }

    @Test
    fun stationSelection_isRequiredBeforeSendingOrRelaying() {
        val unconfigured = MeshDemoState(
            stationRole = null,
            radioStatus = RadioStatus.CONNECTED,
            draft = "HELLO",
            bitcoinQueueTotal = 2,
        )

        assertFalse(unconfigured.canSend)
        assertFalse(unconfigured.canRelayBitcoin)
    }

    @Test
    fun aliceContacts_prioritizeBobThenGatewayAndKeepBroadcastSeparate() {
        val contacts = conferenceRecipients(
            StationRole.ALPHA,
            listOf(
                KnownMeshNode(21, "MESH-BRAVO"),
                KnownMeshNode(LAPTOP_NODE_NUMBER, "Laptop Gateway"),
            ),
        )

        assertEquals(listOf("Bob", "Gateway", "Everyone"), contacts.map(MeshRecipient::displayName))
        assertEquals(21, contacts[0].nodeNumber)
        assertTrue(contacts[0].isAvailable)
        assertEquals(LAPTOP_NODE_NUMBER, contacts[1].nodeNumber)
        assertTrue(contacts[2].isBroadcast)
        assertEquals(null, contacts[2].nodeNumber)
    }

    @Test
    fun bobContacts_recognizeAliceAndUnavailablePeerCannotBeSelectedForSend() {
        val available = conferenceRecipients(
            StationRole.BRAVO,
            listOf(KnownMeshNode(11, "Alice")),
        )
        val unavailable = conferenceRecipients(StationRole.BRAVO, emptyList())

        assertEquals("Alice", available.first().displayName)
        assertEquals(11, available.first().nodeNumber)
        assertTrue(available.first().isAvailable)
        assertFalse(unavailable.first().isAvailable)
        assertFalse(unavailable[1].isAvailable)
        assertEquals(null, unavailable[1].nodeNumber)
    }

    @Test
    fun participantNames_mapLegacyConferenceNamesToVisitorNames() {
        assertEquals("Alice", participantName(11, "MESH-ALPHA"))
        assertEquals("Bob", participantName(12, "Bravo"))
        assertEquals("Gateway", participantName(LAPTOP_NODE_NUMBER, "anything"))
        assertEquals("Field node", participantName(13, "Field node"))
    }

    @Test
    fun gatewayPresence_suppliesCurrentAddressForChatAndBitcoinReplies() {
        val nowMs = 10_000L
        val gatewayNode = 77
        val presence = StationPresence(
            identity = ConferenceIdentity.GATEWAY,
            nodeNumber = gatewayNode,
            session = "gateway-session",
            lastSeenAtMs = nowMs,
        )
        val contacts = conferenceRecipients(
            role = StationRole.ALPHA,
            nodes = emptyList(),
            presences = listOf(presence),
            nowMs = nowMs,
        )

        assertEquals(gatewayNode, contacts[1].nodeNumber)
        assertTrue(contacts[1].isAvailable)
        assertTrue(isGatewaySource(gatewayNode, listOf(presence), nowMs))
        assertTrue(
            isGatewaySource(
                nodeNumber = gatewayNode,
                presences = emptyList(),
                nowMs = nowMs,
                expectedGatewayNodeNumber = gatewayNode,
            ),
        )
        assertFalse(isGatewaySource(78, listOf(presence), nowMs))
    }

    @Test
    fun genericRadioNames_useOwnNodeToFindTheOtherConferenceParticipant() {
        val aliceNode = KnownMeshNode(11, "Meshtastic 2554")
        val bobNode = KnownMeshNode(12, "Meshtastic e8e8")
        val gatewayNode = KnownMeshNode(LAPTOP_NODE_NUMBER, "Meshtastic a141")

        val aliceContacts = conferenceRecipients(
            role = StationRole.ALPHA,
            nodes = listOf(aliceNode, bobNode, gatewayNode),
            ownNodeNumber = aliceNode.nodeNumber,
        )
        val bobContacts = conferenceRecipients(
            role = StationRole.BRAVO,
            nodes = listOf(aliceNode, bobNode, gatewayNode),
            ownNodeNumber = bobNode.nodeNumber,
        )

        assertEquals(bobNode.nodeNumber, aliceContacts.first().nodeNumber)
        assertEquals("Bob", aliceContacts.first().displayName)
        assertTrue(aliceContacts.first().isAvailable)
        assertEquals(aliceNode.nodeNumber, bobContacts.first().nodeNumber)
        assertEquals("Alice", bobContacts.first().displayName)
        assertTrue(bobContacts.first().isAvailable)
    }

    @Test
    fun presenceFrames_roundTripAndRejectUnknownProtocolValues() {
        val frame = presenceAnnouncementFrame(ConferenceIdentity.ALICE, "alice_session")

        assertEquals(
            PresenceFrame.Announcement(ConferenceIdentity.ALICE, "alice_session"),
            parsePresenceFrame(frame),
        )
        assertEquals(PresenceFrame.Request, parsePresenceFrame(PRESENCE_REQUEST))
        assertEquals(null, parsePresenceFrame("DEMO_PRESENCE|2|ALICE|session"))
        assertEquals(null, parsePresenceFrame("DEMO_PRESENCE|1|UNKNOWN|session"))
        assertEquals(null, parsePresenceFrame("ordinary visitor message"))
    }

    @Test
    fun freshPresenceOverridesRadioNamesAndFollowsThePhoneIdentity() {
        val now = 1_000_000L
        val aliceRadio = KnownMeshNode(11, "Meshtastic 2554")
        val bobRadio = KnownMeshNode(12, "Meshtastic e8e8")
        val presences = listOf(
            StationPresence(ConferenceIdentity.ALICE, bobRadio.nodeNumber, "alice-phone", now),
            StationPresence(ConferenceIdentity.BOB, aliceRadio.nodeNumber, "bob-phone", now),
            StationPresence(ConferenceIdentity.GATEWAY, 13, "laptop", now),
        )

        val aliceContacts = conferenceRecipients(
            role = StationRole.ALPHA,
            nodes = listOf(aliceRadio, bobRadio),
            ownNodeNumber = bobRadio.nodeNumber,
            presences = presences,
            nowMs = now,
        )

        assertEquals(aliceRadio.nodeNumber, aliceContacts[0].nodeNumber)
        assertEquals(13, aliceContacts[1].nodeNumber)
        assertEquals("Bob", presenceName(aliceRadio.nodeNumber, presences, now))
        assertEquals("Alice", presenceName(bobRadio.nodeNumber, presences, now))
    }

    @Test
    fun stalePresenceExpiresAndDuplicateIdentityIsReportedAsConflict() {
        val now = PRESENCE_TTL_MS + 10_000L
        val presences = listOf(
            StationPresence(ConferenceIdentity.ALICE, 11, "old", 1L),
            StationPresence(ConferenceIdentity.BOB, 12, "bob-one", now),
            StationPresence(ConferenceIdentity.BOB, 13, "bob-two", now - 1),
        )

        assertEquals(null, latestPresence(ConferenceIdentity.ALICE, presences, now))
        assertEquals(12, latestPresence(ConferenceIdentity.BOB, presences, now)?.nodeNumber)
        assertEquals(setOf(ConferenceIdentity.BOB), presenceConflicts(presences, now))
    }
}
