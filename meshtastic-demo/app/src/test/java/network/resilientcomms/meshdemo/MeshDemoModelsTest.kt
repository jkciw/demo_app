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
    fun sendIsEnabledOnlyForConnectedValidDraft() {
        val connected = MeshDemoState(radioStatus = RadioStatus.CONNECTED, draft = "CODEX TEST")

        assertTrue(connected.canSend)
        assertFalse(connected.copy(radioStatus = RadioStatus.DISCONNECTED).canSend)
        assertFalse(connected.copy(draft = " ").canSend)
        assertFalse(connected.copy(sendProgress = SendProgress.QUEUED).canSend)
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
            BitcoinReply.Rejected("alpha1", "inputs-spent"),
            parseBitcoinReply("BTC_NACK|alpha1|inputs-spent"),
        )
        assertEquals(null, parseBitcoinReply("ordinary message"))
        assertEquals(null, parseBitcoinReply("BTC_ACK|alpha1|not-a-txid"))
    }

    @Test
    fun bitcoinRelayOnlyEnablesForConnectedStationWithRemainingTransaction() {
        val ready = MeshDemoState(
            radioStatus = RadioStatus.CONNECTED,
            bitcoinQueueTotal = 2,
            bitcoinQueueIndex = 0,
        )

        assertTrue(ready.canRelayBitcoin)
        assertFalse(ready.copy(radioStatus = RadioStatus.DISCONNECTED).canRelayBitcoin)
        assertFalse(ready.copy(bitcoinQueueIndex = 2).canRelayBitcoin)
        assertFalse(ready.copy(bitcoinRelayProgress = BitcoinRelayProgress.SENDING).canRelayBitcoin)
    }
}
