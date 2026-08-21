import unittest

from monitor import MessageHub


class MessageHubTest(unittest.TestCase):
    def test_deduplicates_messages_by_transport_id(self):
        hub = MessageHub()
        hub.add_message({"id": "meshtastic-1", "transport": "meshtastic", "text": "one"})
        hub.add_message({"id": "meshtastic-1", "transport": "meshtastic", "text": "duplicate"})
        self.assertEqual(1, len(hub.snapshot()["messages"]))

    def test_transport_updates_preserve_other_fields(self):
        hub = MessageHub()
        hub.update_transport("meshtastic", port="/dev/test", status="connecting")
        hub.update_transport("meshtastic", status="online")
        status = hub.snapshot()["transports"]["meshtastic"]
        self.assertEqual("/dev/test", status["port"])
        self.assertEqual("online", status["status"])

    def test_transaction_updates_preserve_identity_and_timestamp(self):
        hub = MessageHub()
        hub.upsert_transaction(
            {"id": "alpha:one", "sender": "Alpha", "session": "one", "status": "receiving"}
        )
        received_at = hub.snapshot()["transactions"][0]["receivedAt"]
        hub.upsert_transaction({"id": "alpha:one", "status": "confirmed", "blockHeight": 104})
        transaction = hub.snapshot()["transactions"][0]
        self.assertEqual(1, len(hub.snapshot()["transactions"]))
        self.assertEqual("Alpha", transaction["sender"])
        self.assertEqual(received_at, transaction["receivedAt"])
        self.assertEqual("confirmed", transaction["status"])


if __name__ == "__main__":
    unittest.main()
