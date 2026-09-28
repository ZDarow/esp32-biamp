from __future__ import annotations

import unittest

from biamp import protocol as p

SAMPLE_STATUS = [
    "V0=42% V1=38% bal=1.50",
    "Fc=500Hz hp=45Hz sub=ON",
    "XO: LR4",
    "TLF=-2.00dB THF=1.00dB",
    "EQ: L=2.00 M=-1.50 H=0.00",
    "INV: 1000",
    "BT: ON | SPP: ON",
    "Src: 48 kHz",
    "Test: 2 TVol=9%",
    "CHF: 80/0 0/10000 0/0 0/0",
    "Delay: 0/0/3/0",
]


class CommandTests(unittest.TestCase):
    def test_volume(self):
        self.assertEqual(p.set_volume(40), "vol:40")
        self.assertEqual(p.set_zone_volume(1, 45), "v1:45")
        self.assertEqual(p.set_mute(0, True), "mute:1")

    def test_range_rejected(self):
        with self.assertRaises(p.ProtocolError):
            p.set_volume(101)
        with self.assertRaises(p.ProtocolError):
            p.set_balance(11)
        with self.assertRaises(p.ProtocolError):
            p.set_crossover(100)
        with self.assertRaises(p.ProtocolError):
            p.channel_delay(0, 221)

    def test_floats_drop_trailing_zero(self):
        self.assertEqual(p.set_tilt_low(1.0), "tlf:1")
        self.assertEqual(p.set_tilt_low(1.5), "tlf:1.5")
        self.assertEqual(p.set_tilt_high(-1.0), "thf:-1")

    def test_channel_filter_uses_colon(self):
        self.assertEqual(p.channel_filter(0, True, 80), "chhp:0:80")
        self.assertEqual(p.channel_filter(2, False, 10000), "chlp:2:10000")

    def test_channel_filter_raises_low_values(self):
        self.assertEqual(p.channel_filter(1, True, 5), "chhp:1:20")
        self.assertEqual(p.channel_filter(1, True, 0), "chhp:1:0")

    def test_delay_name_embeds_channel(self):
        self.assertEqual(p.channel_delay(3, 66), "delay3:66")

    def test_invalid_channel_rejected(self):
        with self.assertRaises(p.ProtocolError):
            p.channel_filter(4, True, 100)
        with self.assertRaises(p.ProtocolError):
            p.set_inversion(9)

    def test_longest_command_fits_firmware_buffer(self):
        for command in (
            p.channel_filter(0, True, 20000),
            p.channel_filter(3, False, 20000),
            p.set_test_freq(20000),
            p.set_test_volume(100),
            p.channel_delay(3, 220),
        ):
            self.assertLessEqual(len(command.encode()), p.MAX_COMMAND_BYTES, command)

    def test_buffer_limit_is_guarded(self):
        self.assertEqual(p.MAX_COMMAND_BYTES, 63)

    def test_test_modes(self):
        self.assertEqual(p.set_test_mode("sweep"), "test:sweep")
        self.assertEqual(p.set_test_mode("3"), "test:3")
        with self.assertRaises(p.ProtocolError):
            p.set_test_mode("loud")

    def test_eq_bands(self):
        self.assertEqual(p.set_eq("low", 2), "eql:2")
        self.assertEqual(p.set_eq("mid", -1.5), "eqm:-1.5")
        self.assertEqual(p.set_eq("high", 0), "eqh:0")
        with self.assertRaises(p.ProtocolError):
            p.set_eq("bass", 1)


class StatusParserTests(unittest.TestCase):
    def test_block_end_detected(self):
        self.assertTrue(p.is_block_end("Delay: 0/0/3/0"))
        self.assertFalse(p.is_block_end("V0=42% V1=38% bal=1.5"))

    def test_parses_full_block(self):
        state = p.parse_status(SAMPLE_STATUS)
        self.assertIsNotNone(state)
        assert state is not None
        self.assertEqual(state.vol0, 42)
        self.assertEqual(state.vol1, 38)
        self.assertAlmostEqual(state.balance, 1.5)
        self.assertAlmostEqual(state.crossover_hz, 500.0)
        self.assertAlmostEqual(state.sub_hp_hz, 45.0)
        self.assertTrue(state.sub_on)
        self.assertAlmostEqual(state.tilt_low_db, -2.0)
        self.assertAlmostEqual(state.tilt_high_db, 1.0)
        self.assertAlmostEqual(state.eq_low_db, 2.0)
        self.assertAlmostEqual(state.eq_mid_db, -1.5)
        self.assertEqual(state.inverted, (True, False, False, False))
        self.assertTrue(state.bt_audio_on)
        self.assertTrue(state.spp_on)
        self.assertEqual(state.source_khz, "48")
        self.assertEqual(state.crossover_type, 2)
        self.assertEqual(state.delays, (0, 0, 3, 0))
        self.assertEqual(state.test_mode, 2)
        self.assertEqual(state.test_volume, 9)

    def test_parses_channel_filters(self):
        state = p.parse_status(SAMPLE_STATUS)
        assert state is not None
        self.assertEqual(state.filters[0].high_pass_hz, 80)
        self.assertEqual(state.filters[1].low_pass_hz, 10000)
        self.assertEqual(state.filters[2].high_pass_hz, 0)

    def test_returns_none_for_garbage(self):
        self.assertIsNone(p.parse_status(["hello", "world"]))

    def test_partial_block_keeps_defaults(self):
        state = p.parse_status(["V0=10% V1=10% bal=0"])
        assert state is not None
        self.assertEqual(state.vol0, 10)
        self.assertEqual(state.delays, (0, 0, 0, 0))

    def test_roundtrip_state_to_dict(self):
        state = p.parse_status(SAMPLE_STATUS)
        assert state is not None
        data = state.to_dict()
        self.assertEqual(len(data["filters"]), p.CHANNEL_COUNT)
        self.assertEqual(len(data["inverted"]), p.CHANNEL_COUNT)


class ApplyTests(unittest.TestCase):
    def test_every_channel_gets_filters_and_delays(self):
        state = p.DeviceState(
            delays=(1, 2, 3, 4),
            filters=(
                p.ChannelFilter(80, 0),
                p.ChannelFilter(0, 10000),
                p.ChannelFilter(0, 0),
                p.ChannelFilter(0, 0),
            ),
        )
        commands = p.build_settings_commands(state)
        self.assertIn("delay0:1", commands)
        self.assertIn("delay3:4", commands)
        self.assertIn("chhp:0:80", commands)
        self.assertIn("chlp:1:10000", commands)
        self.assertEqual(len([c for c in commands if c.startswith("ch")]), 8)

    def test_inversion_only_when_set(self):
        state = p.DeviceState(inverted=(False, True, False, False))
        commands = p.build_settings_commands(state)
        self.assertIn("inv:1", commands)
        self.assertNotIn("inv:0", commands)

    def test_all_commands_within_buffer(self):
        state = p.DeviceState(
            delays=(220, 220, 220, 220),
            filters=tuple(p.ChannelFilter(20000, 20000) for _ in range(4)),
        )
        for command in p.build_settings_commands(state):
            self.assertLessEqual(len(command.encode()), p.MAX_COMMAND_BYTES, command)


if __name__ == "__main__":
    unittest.main()
