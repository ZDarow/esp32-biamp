from __future__ import annotations

import unittest

from biamp.client import BiAmpClient
from biamp.transport import LoopbackTransport, TransportError

SAMPLE = "\n".join(
    [
        "V0=42% V1=38% bal=1.50",
        "Fc=500Hz hp=45Hz sub=ON",
        "XO: LR4 ON",
        "TLF=-2.00dB THF=1.00dB",
        "EQ: L=2.00 M=-1.50 H=0.00",
        "Mute: 1/0",
        "SWP: 1",
        "DUP: 0",
        "BT: ON | SPP: ON",
        "Src: 48 kHz",
        "Test: 2 TVol=5%",
        "CHF: 80/0 0/10000 0/0 0/0",
        "Delay: 0/0/3/0",
    ]
)

BLOCK_WITH_UNKNOWN_LINE = "\n".join(
    [
        "V0=42% V1=38% bal=1.50",
        "Fc=500Hz hp=45Hz sub=ON",
        "Вес блока: 2",
        "XO: LR4 ON",
        "TLF=-2.00dB THF=1.00dB",
        "EQ: L=2.00 M=-1.50 H=0.00",
        "Mute: 1/0",
        "SWP: 1",
        "DUP: 0",
        "BT: ON | SPP: ON",
        "Src: 48 kHz",
        "Test: 2 TVol=5%",
        "CHF: 80/0 0/10000 0/0 0/0",
        "Delay: 0/0/3/0",
    ]
)


class ReadStatusTests(unittest.TestCase):
    def _client(self, auto: dict[str, str]) -> tuple[BiAmpClient, LoopbackTransport]:
        transport = LoopbackTransport(auto)
        client = BiAmpClient(transport)
        self.addCleanup(client.close)
        return client, transport

    def test_plain_block(self):
        client, _ = self._client({"status": SAMPLE})
        state = client.read_status()
        self.assertEqual(state.vol0, 42)
        self.assertEqual(state.test_mode, 2)
        self.assertEqual(state.unparsed_lines, ())

    def test_async_notice_before_block_ignored(self):
        client, transport = self._client({"status": SAMPLE})
        transport.emit("Test volume: 5%\n")
        state = client.read_status()
        self.assertEqual(state.vol0, 42)
        self.assertEqual(state.unparsed_lines, ())

    def test_stale_block_dropped_on_new_start(self):
        client, transport = self._client({"status": SAMPLE})
        transport.emit(SAMPLE + "\n")
        state = client.read_status()
        self.assertEqual(state.vol0, 42)
        self.assertEqual(state.unparsed_lines, ())

    def test_unknown_line_inside_block_reported(self):
        client, _ = self._client({"status": BLOCK_WITH_UNKNOWN_LINE})
        with self.assertRaises(TransportError) as ctx:
            client.read_status()
        self.assertIn("Вес блока", str(ctx.exception))

    def test_no_response_raises(self):
        client, _ = self._client({})
        with self.assertRaises(TransportError):
            client.read_status()


class BlockMarkTests(unittest.TestCase):
    def test_markers(self):
        from biamp import protocol as p

        self.assertTrue(p.is_block_start("V0=42% V1=38% bal=1.50"))
        self.assertFalse(p.is_block_start("Test volume: 5%"))
        self.assertTrue(p.is_block_end("Delay: 0/0/3/0"))
        self.assertFalse(p.is_block_end("V0=42% V1=38% bal=1.50"))


if __name__ == "__main__":
    unittest.main()
