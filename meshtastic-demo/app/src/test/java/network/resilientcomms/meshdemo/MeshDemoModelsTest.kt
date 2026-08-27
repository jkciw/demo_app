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
    fun onlyIncomingDirectMessagesTriggerParticipantNotifications() {
        val direct = ReceivedText("101", 42, "Bob", "Hello Alice", false, 1_000L)

        assertTrue(shouldNotifyDirectMessage(direct, ownNodeNumber = 7))
        assertFalse(shouldNotifyDirectMessage(direct.copy(isBroadcast = true), ownNodeNumber = 7))
        assertFalse(shouldNotifyDirectMessage(direct, ownNodeNumber = 42))
    }

    @Test
    fun unreadDirectTotal_sumsOnlyPerNodeDirectMessageCounters() {
        val state = MeshDemoState(unreadDirectByNode = mapOf(42 to 2, 84 to 1))

        assertEquals(3, state.unreadDirectTotal)
    }

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
    fun gatewayRequests_areApplicationAddressedBroadcastFramesWithSafeByteLimits() {
        val frame = gatewayRequestFrame(ConferenceIdentity.ALICE, "Show this | on the big screen")

        assertEquals(
            GatewayRequest(ConferenceIdentity.ALICE, "Show this | on the big screen"),
            parseGatewayRequest(frame),
        )
        assertEquals(null, parseGatewayRequest("DEMO_GATEWAY|2|ALICE|wrong version"))
        assertEquals(null, parseGatewayRequest("DEMO_GATEWAY|1|GATEWAY|wrong sender"))
        assertTrue(frame.encodeToByteArray().size <= MAX_TEXT_BYTES)

        val gateway = MeshRecipient("gateway", null, "Gateway", "Service", RecipientKind.GATEWAY, true)
        val ready = MeshDemoState(
            stationRole = StationRole.ALPHA,
            radioStatus = RadioStatus.CONNECTED,
            selectedRecipient = gateway,
        )
        assertTrue(ready.copy(draft = "a".repeat(ready.draftByteLimit)).canSend)
        assertFalse(ready.copy(draft = "a".repeat(ready.draftByteLimit + 1)).canSend)
    }

    @Test
    fun signedTransaction_isSplitIntoMeshtasticSafeFrames() {
        val rawHex = "ab".repeat(191)

        val chunks = chunkSignedTransaction(rawHex)
        val frames = chunks.mapIndexed { index, chunk ->
            bitcoinChunkFrame("alpha1", index + 1, chunks.size, chunk)
        }

        assertEquals(listOf(200, 182), chunks.map(String::length))
        assertTrue(frames.all { it.encodeToByteArray().size <= MAX_TEXT_BYTES })
        assertTrue(
            bitcoinChunkFrame("a12345678901", 1, 2, chunks.first())
                .encodeToByteArray().size <= MAX_TEXT_BYTES,
        )
        assertEquals(rawHex, chunks.joinToString(""))
    }

    @Test
    fun signedTransaction_previewExplainsPackagedRegtestSpend() {
        val rawHex = "0200000000010100416fdc347b37dab6fd6c0f3514636db192bb3d4094ee7adcac87f5874456711400000000fdffffff01583e0f00000000001600148b927f29fa2055410afc5c3972625358eca4bb400247304402204162fc54a21754a7f0ac8e1e736b5753bcfd0d3a3a300a517330a0cf1e65e3170220567fdc9014261dce687905290d26737177106fed5e5400ee4449c27d94eb198a0121034d3c3ae639616ee942cd51a06ca7a1a2a1ed7c4cedc9ee1439363ddcab6335c100000000"

        val preview = requireNotNull(bitcoinTransactionPreview(rawHex))

        assertEquals(999_000L, preview.outputSats)
        assertEquals(1_000L, preview.feeSats)
        assertEquals(1, preview.inputCount)
        assertEquals(1, preview.outputCount)
        assertEquals(191, preview.sizeBytes)
        assertEquals(2, preview.loRaChunks)
        assertTrue(preview.isSegwit)
        assertEquals(null, bitcoinTransactionPreview("not-hex"))
    }

    @Test
    fun bitcoinReplies_parseAcknowledgementBroadcastConfirmationAndFailure() {
        assertEquals("BTC_BEGIN|alpha1|4", bitcoinBeginFrame("alpha1", 4))
        assertEquals("BTC_CANCEL|alpha1", bitcoinCancelFrame("alpha1"))
        assertEquals("BTC_RESULT_REQUEST|alpha1", bitcoinResultRequestFrame("alpha1"))
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
    fun bitcoinRelayUsesTheConnectedRadioAndChecksGatewayWhenItStarts() {
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
        assertTrue(ready.copy(recipients = emptyList()).canOpenBitcoin)
        assertTrue(ready.copy(recipients = listOf(gateway.copy(isAvailable = false))).canRelayBitcoin)
        assertTrue(
            ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.REQUESTING_GATEWAY)
                .canCancelBitcoinRelay,
        )
        assertTrue(
            ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.SENDING)
                .canCancelBitcoinRelay,
        )
        assertFalse(
            ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.WAITING_FOR_GATEWAY)
                .canCancelBitcoinRelay,
        )
        assertTrue(
            ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.WAITING_FOR_GATEWAY)
                .canLeaveBitcoinScreen,
        )
    }

    @Test
    fun stationRoles_haveIndependentNamesAssetsAndQueueProgress() {
        assertEquals(StationRole.ALPHA, StationRole.fromStorageId("alpha"))
        assertEquals(StationRole.BRAVO, StationRole.fromStorageId("bravo"))
        assertEquals(StationRole.CHARLIE, StationRole.fromStorageId("charlie"))
        assertEquals(StationRole.DANA, StationRole.fromStorageId("dana"))
        assertEquals(null, StationRole.fromStorageId("unknown"))
        assertEquals(listOf("Alice", "Bob", "Charlie", "Dana"), StationRole.entries.map { it.stationName })
        assertEquals(4, StationRole.entries.map { it.transactionAssetName }.distinct().size)
        assertEquals(listOf(0, 1, 2, 3), StationRole.entries.map { it.presenceSlot })
        assertEquals("next_transaction_alpha", nextTransactionPreferenceKey(StationRole.ALPHA))
        assertEquals("next_transaction_bravo", nextTransactionPreferenceKey(StationRole.BRAVO))
        assertEquals("next_transaction_charlie", nextTransactionPreferenceKey(StationRole.CHARLIE))
        assertEquals("next_transaction_dana", nextTransactionPreferenceKey(StationRole.DANA))
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
    fun aliceContacts_listThreePeopleThenGatewayAndKeepBroadcastSeparate() {
        val contacts = conferenceRecipients(
            StationRole.ALPHA,
            listOf(
                KnownMeshNode(21, "MESH-BRAVO"),
                KnownMeshNode(22, "Charlie"),
                KnownMeshNode(23, "Dana"),
                KnownMeshNode(LAPTOP_NODE_NUMBER, "Laptop Gateway"),
            ),
        )

        assertEquals(
            listOf("Bob", "Charlie", "Dana", "Gateway", "Everyone"),
            contacts.map(MeshRecipient::displayName),
        )
        assertEquals(21, contacts[0].nodeNumber)
        assertEquals(22, contacts[1].nodeNumber)
        assertEquals(23, contacts[2].nodeNumber)
        assertTrue(contacts.take(3).all(MeshRecipient::isAvailable))
        assertEquals(LAPTOP_NODE_NUMBER, contacts[3].nodeNumber)
        assertTrue(contacts[3].isAvailable)
        assertEquals("Big screen · Gateway radio discovered", contacts[3].description)
        assertTrue(contacts[4].isBroadcast)
        assertEquals(null, contacts[4].nodeNumber)
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
        assertTrue(unavailable.first { it.kind == RecipientKind.GATEWAY }.isAvailable)
        assertEquals(null, unavailable.first { it.kind == RecipientKind.GATEWAY }.nodeNumber)
    }

    @Test
    fun participantNames_mapLegacyConferenceNamesToVisitorNames() {
        assertEquals("Alice", participantName(11, "MESH-ALPHA"))
        assertEquals("Bob", participantName(12, "Bravo"))
        assertEquals("Charlie", participantName(13, "MESH-CHARLIE"))
        assertEquals("Dana", participantName(14, "Dana"))
        assertEquals("Gateway", participantName(LAPTOP_NODE_NUMBER, "anything"))
        assertEquals("Field node", participantName(15, "Field node"))
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

        val gateway = contacts.first { it.kind == RecipientKind.GATEWAY }
        assertEquals(gatewayNode, gateway.nodeNumber)
        assertTrue(gateway.isAvailable)
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
        assertEquals(
            PresenceFrame.Announcement(ConferenceIdentity.CHARLIE, "charlie_session"),
            parsePresenceFrame(presenceAnnouncementFrame(ConferenceIdentity.CHARLIE, "charlie_session")),
        )
        assertEquals(
            PresenceFrame.Announcement(ConferenceIdentity.DANA, "dana_session"),
            parsePresenceFrame(presenceAnnouncementFrame(ConferenceIdentity.DANA, "dana_session")),
        )
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
        assertEquals(13, aliceContacts.first { it.kind == RecipientKind.GATEWAY }.nodeNumber)
        assertEquals("Bob", presenceName(aliceRadio.nodeNumber, presences, now))
        assertEquals("Alice", presenceName(bobRadio.nodeNumber, presences, now))
    }

    @Test
    fun charlieContacts_resolveAllOtherPhonesFromPresence() {
        val now = 2_000_000L
        val presences = listOf(
            StationPresence(ConferenceIdentity.ALICE, 11, "alice", now),
            StationPresence(ConferenceIdentity.BOB, 12, "bob", now),
            StationPresence(ConferenceIdentity.CHARLIE, 13, "charlie", now),
            StationPresence(ConferenceIdentity.DANA, 14, "dana", now),
            StationPresence(ConferenceIdentity.GATEWAY, 15, "gateway", now),
        )

        val contacts = conferenceRecipients(
            role = StationRole.CHARLIE,
            nodes = emptyList(),
            ownNodeNumber = 13,
            presences = presences,
            nowMs = now,
        )

        assertEquals(
            listOf("Alice", "Bob", "Dana", "Gateway", "Everyone"),
            contacts.map(MeshRecipient::displayName),
        )
        assertEquals(listOf(11, 12, 14), contacts.take(3).map(MeshRecipient::nodeNumber))
        assertTrue(contacts.take(4).all(MeshRecipient::isAvailable))
        assertTrue(contacts.last().isBroadcast)
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
