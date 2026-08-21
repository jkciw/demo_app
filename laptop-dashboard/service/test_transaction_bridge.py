import unittest
import threading

from monitor import MessageHub
from transaction_bridge import (
    BitcoinRegtest,
    BitcoinRpcError,
    BitcoinTransactionBridge,
    parse_chunk_frame,
)


class InlineExecutor:
    def submit(self, function, *args):
        function(*args)


class FakeBitcoinRpc:
    def __init__(self):
        self.broadcasts = []

    def check_ready(self):
        return {"chain": "regtest", "height": 103}

    def broadcast(self, raw_hex):
        self.broadcasts.append(raw_hex)
        return "a" * 64

    def mine_confirmation(self, txid):
        self.mined_txid = txid
        return 104


class BitcoinTransactionBridgeTest(unittest.TestCase):
    def setUp(self):
        self.hub = MessageHub()
        self.rpc = FakeBitcoinRpc()
        self.replies = []
        self.bridge = BitcoinTransactionBridge(
            self.hub,
            self.rpc,
            lambda text, destination, channel: self.replies.append(
                (text, destination, channel)
            ),
            executor=InlineExecutor(),
        )

    def send(self, text):
        return self.bridge.handle_text(text, "!alpha", "Alpha", 2)

    def test_reassembles_broadcasts_mines_and_confirms(self):
        self.assertTrue(self.send("BTC_TX|demo1|2/3|0304"))
        self.assertTrue(self.send("BTC_TX|demo1|1/3|0102"))
        self.assertTrue(self.send("BTC_TX|demo1|3/3|0506"))

        self.assertEqual(["010203040506"], self.rpc.broadcasts)
        self.assertEqual("a" * 64, self.rpc.mined_txid)
        transaction = self.hub.snapshot()["transactions"][0]
        self.assertEqual("confirmed", transaction["status"])
        self.assertEqual(104, transaction["blockHeight"])
        self.assertEqual("a" * 64, transaction["txid"])
        reply_text = [reply[0] for reply in self.replies]
        self.assertIn("BTC_CHUNK_ACK|demo1|3", reply_text)
        self.assertIn(f"BTC_ACK|demo1|{'a' * 64}", reply_text)
        self.assertIn("BTC_CONF|demo1|104", reply_text)

    def test_duplicate_chunk_and_completed_retry_do_not_rebroadcast(self):
        self.send("BTC_TX|retry1|1/2|0102")
        self.send("BTC_TX|retry1|1/2|0102")
        transaction = self.hub.snapshot()["transactions"][0]
        self.assertEqual(1, transaction["chunksReceived"])

        self.send("BTC_TX|retry1|2/2|0304")
        self.send("BTC_TX|retry1|2/2|0304")
        self.assertEqual(["01020304"], self.rpc.broadcasts)
        confirmations = [reply for reply in self.replies if reply[0] == "BTC_CONF|retry1|104"]
        self.assertEqual(2, len(confirmations))

    def test_invalid_hex_is_rejected_without_rpc(self):
        self.assertTrue(self.send("BTC_TX|broken1|1/1|not-hex"))
        self.assertEqual([], self.rpc.broadcasts)
        transaction = self.hub.snapshot()["transactions"][0]
        self.assertEqual("error", transaction["status"])
        self.assertEqual("bad-hex", transaction["error"])
        self.assertEqual("BTC_NACK|broken1|bad-hex", self.replies[0][0])

    def test_incomplete_transfer_stays_in_receiving_state(self):
        self.send("BTC_TX|partial1|1/3|0102")
        transaction = self.hub.snapshot()["transactions"][0]
        self.assertEqual("receiving", transaction["status"])
        self.assertEqual(1, transaction["chunksReceived"])
        self.assertEqual(3, transaction["chunksTotal"])
        self.assertEqual([], self.rpc.broadcasts)

    def test_non_protocol_text_is_not_consumed(self):
        self.assertFalse(self.send("ordinary field message"))
        self.assertEqual([], self.hub.snapshot()["transactions"])

    def test_parser_uses_one_based_chunk_numbers(self):
        frame = parse_chunk_frame("BTC_TX|s-1|2/4|A0ff")
        self.assertEqual(("s-1", 2, 4, "a0ff"), (frame.session, frame.index, frame.total, frame.payload))
        with self.assertRaisesRegex(ValueError, "bad-position"):
            parse_chunk_frame("BTC_TX|s-1|0/4|a0ff")


class BitcoinRegtestRetryTest(unittest.TestCase):
    def test_already_confirmed_transaction_is_treated_as_success(self):
        client = BitcoinRegtest.__new__(BitcoinRegtest)
        client._lock = threading.Lock()
        client._mining_address = None
        calls = []

        def call(method, *params, wallet=False):
            calls.append(method)
            if method == "decoderawtransaction":
                return {"txid": "b" * 64}
            if method == "sendrawtransaction":
                raise BitcoinRpcError("Transaction outputs already in utxo set")
            if method == "getrawtransaction":
                return {"confirmations": 3, "blockhash": "block-one"}
            if method == "getblockheader":
                return {"height": 99}
            raise AssertionError(method)

        client._call = call
        txid = client.broadcast("00")
        height = client.mine_confirmation(txid)
        self.assertEqual("b" * 64, txid)
        self.assertEqual(99, height)
        self.assertNotIn("generatetoaddress", calls)

    def test_unloaded_wallet_is_recovered_before_mining(self):
        client = BitcoinRegtest.__new__(BitcoinRegtest)
        client._lock = threading.Lock()
        client._mining_address = None
        client.wallet = "conference-demo"
        calls = []
        transaction_reads = 0

        def call(method, *params, wallet=False):
            nonlocal transaction_reads
            calls.append((method, wallet))
            if method == "getrawtransaction":
                transaction_reads += 1
                if transaction_reads == 1:
                    return {"confirmations": 0}
                return {"confirmations": 1, "blockhash": "new-block"}
            if method == "getwalletinfo":
                raise BitcoinRpcError("Requested wallet does not exist or is not loaded")
            if method == "loadwallet":
                return {"name": "conference-demo"}
            if method == "getnewaddress":
                return "bcrt1qmining"
            if method == "generatetoaddress":
                return ["new-block"]
            if method == "getblockheader":
                return {"height": 106}
            raise AssertionError(method)

        client._call = call
        height = client.mine_confirmation("c" * 64)
        self.assertEqual(106, height)
        self.assertIn(("loadwallet", False), calls)
        self.assertIn(("getnewaddress", True), calls)
        self.assertIn(("generatetoaddress", False), calls)


if __name__ == "__main__":
    unittest.main()
