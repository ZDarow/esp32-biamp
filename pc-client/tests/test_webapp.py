from __future__ import annotations

import json
import socket
import threading
import unittest
import urllib.error
import urllib.request

from biamp import protocol as p
from biamp.transport import LoopbackTransport
from biamp.webapp import (
    TOKEN_HEADER as TOKEN,
)
from biamp.webapp import apply_field, check_command, command_for, create_server

SAMPLE = (
    "V0=42% V1=38% bal=1.50\r\n"
    "Fc=500Hz hp=45Hz sub=ON\r\n"
    "XO: LR4 ON\r\n"
    "TLF=-2.00dB THF=1.00dB\r\n"
    "EQ: L=2.00 M=-1.50 H=0.00\r\n"
    "Mute: 1/0\r\n"
    "SWP: 1\r\n"
    "DUP: 0\r\n"
    "BT: ON | SPP: ON\r\n"
    "Src: 48 kHz\r\n"
    "Test: 2 TVol=5%\r\n"
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

    def test_routing_and_mute_fields(self):
        state = p.DeviceState()
        self.assertTrue(apply_field(state, "lr_swap", True).lr_swap)
        self.assertTrue(apply_field(state, "dup_out", "on").dup_out)
        self.assertFalse(apply_field(state, "xo_on", "false").xo_on)
        self.assertTrue(apply_field(state, "muted_z0", 1).muted_z0)

    def test_test_volume_range_matches_firmware(self):
        with self.assertRaises(p.ProtocolError):
            apply_field(p.DeviceState(), "test_volume", 50)

    def test_inversion_field_is_gone(self):
        with self.assertRaises(p.ProtocolError):
            apply_field(p.DeviceState(), "inv0", True)

    def test_command_generation(self):
        state = p.DeviceState()
        after = apply_field(state, "crossover_hz", 600)
        self.assertEqual(command_for("crossover_hz", state, after), "fc:600")
        self.assertEqual(command_for("chhp0", state, apply_field(state, "chhp0", 80)), "chhp:0:80")
        self.assertEqual(command_for("delay1", state, apply_field(state, "delay1", 3)), "delay1:3")
        self.assertEqual(command_for("dup_out", state, apply_field(state, "dup_out", True)), "dup:1")
        self.assertEqual(command_for("muted_z1", state, apply_field(state, "muted_z1", True)), "mute:1:1")

    def test_unknown_field_rejected(self):
        with self.assertRaises(p.ProtocolError):
            apply_field(p.DeviceState(), "nonsense", 1)


class CommandWhitelistTests(unittest.TestCase):
    def test_safe_commands_allowed(self):
        for command in ("status", "stats", "help", "save", "preset:0", "test:sweep", "test:4"):
            self.assertEqual(check_command(command), command)

    def test_destructive_commands_rejected(self):
        for command in ("factory", "reboot", "factory\n", "vol:200"):
            with self.assertRaises(p.ProtocolError):
                check_command(command)

    def test_unknown_command_rejected(self):
        for command in ("save; rm -rf /", "preset:9", "test:loud", ""):
            with self.assertRaises(p.ProtocolError):
                check_command(command)

    def test_non_string_rejected(self):
        with self.assertRaises(p.ProtocolError):
            check_command(123)


class WebServerTests(unittest.TestCase):
    def setUp(self) -> None:
        from biamp.client import BiAmpClient

        self.transport = LoopbackTransport({"status": SAMPLE})
        self.client = BiAmpClient(self.transport)
        self.server = create_server(self.client, "127.0.0.1", 0)
        self.token = self.server.token
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.client.close()

    def _get(self, path: str, headers: dict | None = None) -> dict:
        request = urllib.request.Request(self.base + path, headers=headers or {})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read())

    def _post(
        self,
        path: str,
        payload: dict,
        headers: dict | None = None,
        raw: bytes | None = None,
        content_type: str = "application/json",
    ) -> dict:
        merged = {TOKEN: self.token, "Content-Type": content_type}
        merged.update(headers or {})
        request = urllib.request.Request(
            self.base + path,
            data=raw if raw is not None else json.dumps(payload).encode(),
            headers=merged,
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read())

    def test_index_served(self):
        with urllib.request.urlopen(self.base + "/", timeout=5) as response:
            body = response.read().decode()
        self.assertIn("ESP32 Bi-Amp", body)

    def test_index_carries_token(self):
        with urllib.request.urlopen(self.base + "/", timeout=5) as response:
            body = response.read().decode()
        self.assertIn('name="biamp-token"', body)
        self.assertIn(self.token, body)

    def test_security_headers_present(self):
        with urllib.request.urlopen(self.base + "/", timeout=5) as response:
            headers = response.headers
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(headers["X-Frame-Options"], "DENY")
        self.assertEqual(headers["Referrer-Policy"], "no-referrer")

    def test_state_endpoint(self):
        data = self._get("/api/state", {TOKEN: self.token})
        self.assertTrue(data["ok"])
        self.assertEqual(data["state"]["vol0"], 42)
        self.assertEqual(data["state"]["crossover_type"], 2)

    def test_state_endpoint_rejects_foreign_host(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._get("/api/state", {TOKEN: self.token, "Host": "evil.example.com"})
        self.assertEqual(ctx.exception.code, 403)

    def test_state_endpoint_rejects_foreign_origin(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._get(
                "/api/state",
                {TOKEN: self.token, "Origin": "http://evil.example.com"},
            )
        self.assertEqual(ctx.exception.code, 403)

    def test_api_requires_token(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._get("/api/state")
        self.assertEqual(ctx.exception.code, 403)

    def test_api_rejects_wrong_token(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._get("/api/state", {TOKEN: "wrong-token-0000"})
        self.assertEqual(ctx.exception.code, 403)

    def test_param_endpoint_sends_command(self):
        data = self._post("/api/param", {"name": "balance", "value": -3})
        self.assertTrue(data["ok"])
        self.assertEqual(data["command"], "bal:-3")
        self.assertIn("bal:-3", self.transport.sent)

    def test_param_endpoint_rejects_bad_value(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/param", {"name": "vol0", "value": 900})
        self.assertEqual(ctx.exception.code, 400)

    def test_param_endpoint_rejects_missing_name(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/param", {"value": 1})
        self.assertEqual(ctx.exception.code, 400)

    def test_param_endpoint_rejects_wrong_types(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/param", {"name": "vol0", "value": {"nested": [1]}})
        self.assertEqual(ctx.exception.code, 400)

    def test_text_plain_post_is_refused(self):
        """Простой cross-origin POST с text/plain обязан быть отбит."""
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post(
                "/api/command",
                None,
                raw=b'{"command": "factory"}',
                content_type="text/plain",
            )
        self.assertEqual(ctx.exception.code, 415)
        self.assertNotIn("factory", self.transport.sent)

    def test_factory_command_refused(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/command", {"command": "factory"})
        self.assertEqual(ctx.exception.code, 400)
        self.assertNotIn("factory", self.transport.sent)

    def test_oversized_body_refused(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/param", None, raw=b"x" * 70_000)
        self.assertIn(ctx.exception.code, (400, 413))

    def test_broken_content_length_does_not_kill_server(self):
        """`Content-Length: abc` обязан дать 400, а не уронить поток обработки."""
        port = self.server.server_address[1]
        with socket.create_connection(("127.0.0.1", port), timeout=5) as raw:
            raw.sendall(
                (
                    "POST /api/param HTTP/1.1\r\n"
                    f"Host: 127.0.0.1:{port}\r\n"
                    f"{TOKEN}: {self.token}\r\n"
                    "Content-Type: application/json\r\n"
                    "Content-Length: abc\r\n"
                    "Connection: close\r\n\r\n"
                ).encode()
            )
            answer = raw.recv(4096).decode("utf-8", errors="replace")
        self.assertIn("400", answer.splitlines()[0])

        # Сервер обязан продолжать работать после такой попытки.
        data = self._get("/api/state", {TOKEN: self.token})
        self.assertTrue(data["ok"])

    def test_apply_endpoint_rejects_incomplete_state(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/api/apply", {"state": {"vol0": 30}})
        self.assertEqual(ctx.exception.code, 400)

    def test_apply_endpoint_sends_settings(self):
        state = p.DeviceState(vol0=30, vol1=31, lr_swap=True).to_dict()
        data = self._post("/api/apply", {"state": state})
        self.assertTrue(data["ok"])
        self.assertIn("v0:30", self.transport.sent)
        self.assertIn("swap:1", self.transport.sent)


if __name__ == "__main__":
    unittest.main()
