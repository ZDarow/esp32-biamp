from __future__ import annotations

import json
import socket
import threading
import unittest

from biamp import cli


def status_block(vol0: int = 42, vol1: int = 38, balance: float = 1.5) -> str:
    return (
        f"V0={vol0}% V1={vol1}% bal={balance:.2f}\r\n"
        "Fc=500Hz hp=45Hz sub=ON\r\n"
        "XO: LR4 ON\r\n"
        "TLF=-2.00dB THF=1.00dB\r\n"
        "EQ: L=2.00 M=-1.50 H=0.00\r\n"
        "Mute: 1/0\r\n"
        "SWP: 1\r\n"
        "DUP: 0\r\n"
        "BT: ON | SPP: ON\r\n"
        "Src: 48 kHz\r\n"
        "Test: 0 TVol=5%\r\n"
        "CHF: 80/0 0/10000 0/0 0/0\r\n"
        "Delay: 0/0/3/0\r\n"
    )


class FakeFirmware(threading.Thread):
    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.received: list[str] = []
        self._server = socket.socket()
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind(("127.0.0.1", 0))
        self._server.listen(1)
        self.port = self._server.getsockname()[1]

    def run(self) -> None:
        while True:
            try:
                conn, _ = self._server.accept()
            except OSError:
                break
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, conn: socket.socket) -> None:
        buffer = b""
        with conn:
            while True:
                try:
                    chunk = conn.recv(1024)
                except OSError:
                    break
                if not chunk:
                    break
                buffer += chunk
                while b"\n" in buffer:
                    raw, buffer = buffer.split(b"\n", 1)
                    command = raw.decode().strip()
                    if not command:
                        continue
                    self.received.append(command)
                    if command == "status":
                        try:
                            conn.sendall(status_block().encode())
                        except OSError:
                            return

    def close(self) -> None:
        self._server.close()


class CliTests(unittest.TestCase):
    def setUp(self) -> None:
        self.device = FakeFirmware()
        self.device.start()
        self.target = f"tcp://127.0.0.1:{self.device.port}"

    def tearDown(self) -> None:
        self.device.close()

    def _run(self, *args: str) -> int:
        return cli.main(["--port", self.target, *args])

    def test_status_command(self):
        self.assertEqual(self._run("status"), 0)
        self.assertIn("status", self.device.received)

    def test_status_json(self):
        import io
        from contextlib import redirect_stdout

        buffer = io.StringIO()
        with redirect_stdout(buffer):
            self._run("status", "--json")
        payload = json.loads(buffer.getvalue()[buffer.getvalue().index("{") :])
        self.assertEqual(payload["vol0"], 42)
        self.assertEqual(payload["delays"], [0, 0, 3, 0])

    def test_set_parameter(self):
        self.assertEqual(self._run("set", "crossover", "600"), 0)
        self.assertIn("fc:600", self.device.received)

    def test_set_rejects_bad_value(self):
        self.assertEqual(self._run("set", "crossover", "50"), 2)

    def test_set_boolean_accepts_words(self):
        """`set sub false` обязан дать sub:0, а не traceback."""
        self.assertEqual(self._run("set", "sub", "false"), 0)
        self.assertIn("sub:0", self.device.received)
        self.assertEqual(self._run("set", "sub", "on"), 0)
        self.assertIn("sub:1", self.device.received)

    def test_set_boolean_rejects_garbage(self):
        self.assertEqual(self._run("set", "sub", "возможно"), 2)

    def test_set_mute_is_absolute(self):
        self.assertEqual(self._run("set", "mute1", "true"), 0)
        self.assertIn("mute:1:1", self.device.received)

    def test_set_test_volume_range(self):
        self.assertEqual(self._run("set", "test_volume", "50"), 2)

    def test_bad_port_rejected_without_traceback(self):
        self.assertEqual(cli.main(["--port", "tcp://127.0.0.1:abc", "status"]), 2)

    def test_traversal_in_profile_name_rejected(self):
        self.assertEqual(cli.main(["--port", self.target, "load", "../escape"]), 2)

    def test_raw_command(self):
        self.assertEqual(self._run("raw", "status"), 0)

    def test_profile_roundtrip(
        self,
    ):
        import io
        import tempfile
        from contextlib import redirect_stdout

        with tempfile.TemporaryDirectory() as tmp:
            original = cli.PROFILES_DIR
            cli.PROFILES_DIR = __import__("pathlib").Path(tmp)
            try:
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(self._run("save", "test"), 0)
                self._run("load", "test")
                self.assertIn("fc:500", self.device.received)
                self.assertIn("delay2:3", self.device.received)
                self.assertIn("chhp:0:80", self.device.received)
            finally:
                cli.PROFILES_DIR = original


if __name__ == "__main__":
    unittest.main()
