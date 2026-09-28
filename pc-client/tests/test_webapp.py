from __future__ import annotations

import json
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

from biamp import protocol as p
from biamp.transport import LoopbackTransport
from biamp.webapp import apply_field, command_for, make_handler

SAMPLE = (
    "V0=42% V1=38% bal=1.50\r\n"
    "Fc=500Hz hp=45Hz sub=ON\r\n"
    "XO: LR4\r\n"
    "TLF=-2.00dB THF=1.00dB\r\n"
    "EQ: L=2.00 M=-1.50 H=0.00\r\n"
    "INV: 1000\r\n"
    "BT: ON | SPP: ON\r\n"
    "Src: 48 kHz\r\n"
    "Test: 2 TVol=9%\r\n"
    "CHF: 80/0 0/10000 0/0 0/0\r\n"
    "Delay: 0/0/3/0\r\n"
)


class FieldTests(unittest.TestCase):
    def test_scalar_field(self):
        state = p.DeviceState()
        updated = apply_field(state, "vol0", 55)
        self.assertEqual(updated.vol0, 55)
        self.assertEqual(updated.vol1, state.vol1)

    def test_rejects_out_of_range(self):
        with self.assertRaises(p.ProtocolError):
            apply_field(p.DeviceState(), "vol0", 150)
        with self.assertRaises(p.ProtocolError):
            apply_field(p.DeviceState(), "crossover_hz", 50)

    def test_channel_fields(self):
        state = p.DeviceState()
        self.assertEqual(apply_field(state, "delay2", 66).delays[2], 66)
        updated = apply_field(state, "chhp1", 120)
        self.assertEqual(updated.filters[1].high_pass_hz, 120)
        self.assertTrue(apply_field(state, "inv3", True).inverted[3])

    def test_command_generation(self):
        state = p.DeviceState()
        after = apply_field(state, "crossover_hz", 600)
        self.assertEqual(command_for("crossover_hz", state, after), "fc:600")
        self.assertEqual(
            command_for("chhp0", state, apply_field(state, "chhp0", 80)), "chhp:0:80"
        )
        self.assertEqual(
            command_for("delay1", state, apply_field(state, "delay1", 3)), "delay1:3"
        )

    def test_unknown_field_rejected(self):
        with self.assertRaises(p.ProtocolError):
            apply_field(p.DeviceState(), "nonsense", 1)


class WebServerTests(unittest.TestCase):
    def setUp(self) -> None:
        from biamp.client import BiAmpClient

        self.transport = LoopbackTransport({"status": SAMPLE})
        self.client = BiAmpClient(self.transport)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.client))
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.client.close()

    def _get(self, path: str) -> dict:
        with urllib.request.urlopen(self.base + path, timeout=5) as response:
            return json.loads(response.read())

    def _post(self, path: str, payload: dict) -> dict:
        request = urllib.request.Request(
            self.base + path,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read())

    def test_index_served(self):
        with urllib.request.urlopen(self.base + "/", timeout=5) as response:
            body = response.read().decode()
        self.assertIn("ESP32 Bi-Amp", body)

    def test_state_endpoint(self):
        data = self._get("/api/state")
        self.assertTrue(data["ok"])
        self.assertEqual(data["state"]["vol0"], 42)
        self.assertEqual(data["state"]["crossover_type"], 2)

    def test_param_endpoint_sends_command(self):
        data = self._post("/api/param", {"name": "balance", "value": -3})
        self.assertTrue(data["ok"])
        self.assertEqual(data["command"], "bal:-3")
        self.assertIn("bal:-3", self.transport.sent)

    def test_param_endpoint_rejects_bad_value(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/param", {"name": "vol0", "value": 900})
        self.assertEqual(ctx.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
