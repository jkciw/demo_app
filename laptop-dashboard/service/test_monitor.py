import unittest
import threading
from types import SimpleNamespace

from monitor import (
    MeshtasticAdapter,
    MessageHub,
    UnavailableBitcoinRpc,
    parse_presence_announcement,
    presence_announcement,
    radio_configuration,
)


class MessageHubTest(unittest.TestCase):
    def test_presence_frames_are_strict_and_round_trip(self):
        frame = presence_announcement("GATEWAY", "session_123")
        self.assertEqual(("GATEWAY", "session_123"), parse_presence_announcement(frame))
        self.assertEqual(
            ("CHARLIE", "charlie_123"),
            parse_presence_announcement(presence_announcement("CHARLIE", "charlie_123")),
        )
        self.assertEqual(
            ("DANA", "dana_123"),
            parse_presence_announcement(presence_announcement("DANA", "dana_123")),
        )
        self.assertIsNone(parse_presence_announcement("DEMO_PRESENCE|1|MALLORY|session"))
        self.assertIsNone(parse_presence_announcement("DEMO_PRESENCE|2|ALICE|session"))

    def test_presence_is_hidden_and_labels_follow_the_latest_app_identity(self):
        hub = MessageHub()
        adapter = MeshtasticAdapter(
            hub,
            "/dev/test",
            threading.Event(),
            UnavailableBitcoinRpc("test"),
        )
        try:
            adapter._on_text(
                {
                    "id": 1,
                    "fromId": "!radio-one",
                    "decoded": {"text": "DEMO_PRESENCE|1|ALICE|alice-phone"},
                }
            )
            self.assertEqual([], hub.snapshot()["messages"])

            adapter._on_text(
                {
                    "id": 2,
                    "fromId": "!radio-one",
                    "decoded": {"text": "Hello from the mesh"},
                }
            )
            message = hub.snapshot()["messages"][0]
            self.assertEqual("Alice", message["sender"])

            adapter._on_text(
                {
                    "id": 3,
                    "fromId": "!radio-two",
                    "decoded": {"text": "DEMO_PRESENCE|1|ALICE|alice-phone"},
                }
            )
            adapter._on_text(
                {
                    "id": 4,
                    "fromId": "!radio-one",
                    "decoded": {"text": "Old radio"},
                }
            )
            self.assertEqual("!radio-one", hub.snapshot()["messages"][-1]["sender"])
        finally:
            adapter.transaction_bridge.close()

    def test_deduplicates_messages_by_transport_id(self):
        hub = MessageHub()
        hub.add_message({"id": "meshtastic-1", "transport": "meshtastic", "text": "one"})
        hub.add_message({"id": "meshtastic-1", "transport": "meshtastic", "text": "duplicate"})
        self.assertEqual(1, len(hub.snapshot()["messages"]))

    def test_radio_configuration_reports_short_fast_and_region(self):
        from meshtastic.protobuf import config_pb2

        lora = config_pb2.Config.LoRaConfig(
            modem_preset=config_pb2.Config.LoRaConfig.SHORT_FAST,
            region=config_pb2.Config.LoRaConfig.TW,
        )
        interface = SimpleNamespace(
            localNode=SimpleNamespace(
                localConfig=SimpleNamespace(lora=lora),
            ),
        )

        self.assertEqual(
            {"modemPreset": "ShortFast", "region": "TW"},
            radio_configuration(interface),
        )

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
