"""Контрактный тест протокола (раздел 5 `firmware/protocol/status-contract.md`).

Блок `status` берётся ДОСЛОВНО из файла контракта: тест обязан падать, если
в блок добавлена строка, которую парсер не знает. Именно это свойство
защищает от повторения расхождения между прошивкой и клиентом.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

from biamp import protocol as p

CONTRACT = Path(__file__).resolve().parents[2] / "firmware" / "protocol" / "status-contract.md"
SECTION_2 = "## 2."
BLOCK_RE = re.compile(r"##\s*2\.[^\n]*\n+```\n(.*?)```", re.DOTALL)

# Файл контракта принадлежит направлению firmware (docs/BRANCHING.md), поэтому
# в ветке pc-client-dev его нет: ветки живут раздельно до слияния в main.
# Проверять блок status нечем — класс пропускается, а не падает на чужом файле.
# В main и в firmware-dev контракт лежит рядом, и проверка работает как обычно.
HAS_CONTRACT = CONTRACT.is_file()
SKIP_REASON = f"нет файла контракта {CONTRACT} — он в ветке направления firmware"


def contract_block() -> list[str]:
    """Ровно те строки, что прошивка печатает по контракту."""
    text = CONTRACT.read_text(encoding="utf-8")
    assert SECTION_2 in text, "в контракте нет раздела 2"
    match = BLOCK_RE.search(text)
    assert match is not None, "в разделе 2 контракта нет блока status в тройных кавычках"
    return [line for line in match.group(1).splitlines() if line.strip()]


@unittest.skipUnless(HAS_CONTRACT, SKIP_REASON)
class ContractBlockTests(unittest.TestCase):
    def setUp(self) -> None:
        self.lines = contract_block()
        self.state = p.parse_status(self.lines)

    def test_block_has_thirteen_lines(self):
        self.assertEqual(len(self.lines), 13, self.lines)

    def test_block_ends_with_delay(self):
        self.assertTrue(p.is_block_end(self.lines[-1]))
        self.assertTrue(self.lines[-1].startswith("Delay:"))

    def test_parses_at_all(self):
        self.assertIsNotNone(self.state)

    def test_no_unrecognised_lines(self):
        assert self.state is not None
        self.assertEqual(self.state.unparsed_lines, ())

    def test_strict_mode_rejects_unknown_line(self):
        with self.assertRaises(p.ProtocolError):
            p.parse_status([*self.lines, "WAT: 1"], strict=True)

    def test_unknown_line_is_reported_not_defaulted(self):
        state = p.parse_status(["V0=42% V1=42% bal=0.00", "Mystery: 1"])
        assert state is not None
        self.assertEqual(state.vol0, 42)
        self.assertEqual(state.unparsed_lines, ("Mystery: 1",))

    def test_every_contract_field_recognised(self):
        """Все 13 полей контракта обязаны быть разобраны, а не взяты по умолчанию."""
        assert self.state is not None
        state = self.state

        self.assertEqual((state.vol0, state.vol1), (40, 40))
        self.assertAlmostEqual(state.balance, 0.0)

        self.assertAlmostEqual(state.crossover_hz, 400.0)
        self.assertAlmostEqual(state.sub_hp_hz, 45.0)
        self.assertTrue(state.sub_on)

        self.assertEqual(state.crossover_type, 1)  # Butter
        self.assertTrue(state.xo_on)

        self.assertAlmostEqual(state.tilt_low_db, 0.0)
        self.assertAlmostEqual(state.tilt_high_db, -1.0)

        self.assertAlmostEqual(state.eq_low_db, 0.0)
        self.assertAlmostEqual(state.eq_mid_db, 0.0)
        self.assertAlmostEqual(state.eq_high_db, 0.0)

        self.assertFalse(state.muted_z0)
        self.assertFalse(state.muted_z1)

        self.assertFalse(state.lr_swap)
        self.assertFalse(state.dup_out)

        self.assertTrue(state.bt_audio_on)
        self.assertFalse(state.spp_on)

        self.assertEqual(state.source_khz, "44.1")

        self.assertEqual(state.test_mode, 0)
        self.assertEqual(state.test_volume, 4)

        self.assertEqual(
            [(f.high_pass_hz, f.low_pass_hz) for f in state.filters],
            [(0, 0), (0, 0), (0, 0), (0, 0)],
        )
        self.assertEqual(state.delays, (0, 0, 0, 0))

    def test_tail_xo_off_is_not_ignored(self):
        """`XO: Butter OFF` обязан давать xo_on=False, а не дефолт True."""
        lines = [line.replace("XO: Butter ON", "XO: Butter OFF") for line in self.lines]
        state = p.parse_status(lines)
        assert state is not None
        self.assertFalse(state.xo_on)
        self.assertEqual(state.crossover_type, 1)

    def test_tail_lr4_recognised(self):
        lines = [line.replace("XO: Butter ON", "XO: LR4 ON") for line in self.lines]
        state = p.parse_status(lines)
        assert state is not None
        self.assertEqual(state.crossover_type, 2)
        self.assertTrue(state.xo_on)

    def test_merge_with_base_keeps_absent_fields(self):
        """Поля, которых нет в блоке, берутся из базы, а не из дефолтов dataclass."""
        base = p.DeviceState(vol0=77, vol1=66, lr_swap=True, dup_out=True, xo_on=False)
        partial = ["V0=40% V1=40% bal=0.00", "SWP: 0", "Delay: 0/0/0/0"]
        state = p.parse_status(partial, base=base)
        assert state is not None
        self.assertEqual((state.vol0, state.vol1), (40, 40))
        self.assertFalse(state.lr_swap)  # есть в блоке
        self.assertTrue(state.dup_out)  # нет в блоке — сохраняем базу
        self.assertFalse(state.xo_on)  # нет в блоке — сохраняем базу
        self.assertEqual(state.delays, (0, 0, 0, 0))
        self.assertEqual(state.unparsed_lines, ())

    def test_every_contract_field_survives_roundtrip(self):
        """Ни одно поле контракта не должно теряться при to_dict/state_from_dict."""
        assert self.state is not None
        restored = p.state_from_dict(self.state.to_dict())
        for name in self.state.__dataclass_fields__:
            if name == "unparsed_lines":
                continue
            self.assertEqual(getattr(restored, name), getattr(self.state, name), f"поле {name} потеряно")


class ContractCommandTests(unittest.TestCase):
    """Команды-ответчики обязаны соответствовать разделу 4 контракта."""

    def test_test_volume_range(self):
        self.assertEqual(p.set_test_volume(6), "tvol:6")
        with self.assertRaises(p.ProtocolError):
            p.set_test_volume(7)
        with self.assertRaises(p.ProtocolError):
            p.set_test_volume(50)

    def test_mute_is_absolute(self):
        self.assertEqual(p.set_mute(0, True), "mute:0:1")
        self.assertEqual(p.set_mute(1, False), "mute:1:0")

    def test_swap_and_dup_accepted(self):
        self.assertEqual(p.set_swap(True), "swap:1")
        self.assertEqual(p.set_dup(False), "dup:0")
        self.assertEqual(p.set_xo_enabled(False), "xo:0")

    def test_settings_restore_swap_dup_xo_and_mute(self):
        state = p.DeviceState(lr_swap=True, dup_out=True, xo_on=False)
        commands = p.build_settings_commands(state)
        self.assertIn("swap:1", commands)
        self.assertIn("dup:1", commands)
        self.assertIn("xo:0", commands)
        self.assertIn("mute:0:0", commands)
        self.assertIn("mute:1:0", commands)

        cleared = p.build_settings_commands(p.DeviceState())
        self.assertIn("swap:0", cleared)
        self.assertIn("dup:0", cleared)
        self.assertIn("xo:1", cleared)

    def test_no_inversion_command_anywhere(self):
        """`inv:` удалён в v35 — клиент не должен его ни строить, ни знать."""
        self.assertFalse(hasattr(p, "set_inversion"))
        for command in p.build_settings_commands(p.DeviceState()):
            self.assertFalse(command.startswith("inv"), command)

    def test_to_bool_never_silently_inverts(self):
        self.assertFalse(p.to_bool("false"))
        self.assertFalse(p.to_bool("off"))
        self.assertFalse(p.to_bool(0))
        self.assertTrue(p.to_bool("ON"))
        self.assertTrue(p.to_bool(True))
        with self.assertRaises(p.ProtocolError):
            p.to_bool("возможно")


if __name__ == "__main__":
    unittest.main()
