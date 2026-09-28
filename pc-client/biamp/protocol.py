from __future__ import annotations

import dataclasses
import re
from dataclasses import dataclass, field, asdict
from typing import Final, Iterable

MAX_COMMAND_BYTES: Final[int] = 63
MIN_SAMPLE_RATE_HZ: Final[int] = 20000
MAX_DELAY_SAMPLES: Final[int] = 220
CHANNEL_COUNT: Final[int] = 4

CHANNELS: Final[dict[int, str]] = {
    0: "LEFT НЧ",
    1: "LEFT ВЧ",
    2: "RIGHT НЧ",
    3: "RIGHT ВЧ",
}

ZONES: Final[dict[int, str]] = {0: "левая", 1: "правая"}


class ProtocolError(ValueError):
    pass


def _fmt(value: float) -> str:
    if float(value).is_integer():
        return str(int(value))
    return f"{value:g}"


def _check_number(value: float, name: str, low: float, high: float) -> float:
    number = float(value)
    if number != number:
        raise ProtocolError(f"{name}: значение не число")
    if not (low <= number <= high):
        raise ProtocolError(f"{name}: {number:g} вне диапазона {low:g}..{high:g}")
    return number


def _check_int(value: int, name: str, low: int, high: int) -> int:
    number = int(value)
    if not (low <= number <= high):
        raise ProtocolError(f"{name}: {number} вне диапазона {low}..{high}")
    return number


def check_int(value: int, name: str, low: int, high: int) -> int:
    return _check_int(value, name, low, high)


def check_number(value: float, name: str, low: float, high: float) -> float:
    return _check_number(value, name, low, high)


def _check_command_size(command: str) -> str:
    raw = command.encode("utf-8")
    if len(raw) > MAX_COMMAND_BYTES:
        raise ProtocolError(
            f"команда {len(raw)} байт, буфер прошивки {MAX_COMMAND_BYTES} — будет отброшена"
        )
    return command


def channel_filter(channel: int, low: bool, freq: int) -> str:
    ch = _check_int(channel, "канал", 0, CHANNEL_COUNT - 1)
    hz = _check_int(freq, "частота", 0, MIN_SAMPLE_RATE_HZ)
    effective = 20 if 0 < hz < 20 else hz
    name = "chhp" if low else "chlp"
    return _check_command_size(f"{name}:{ch}:{effective}")


def channel_delay(channel: int, samples: int) -> str:
    ch = _check_int(channel, "канал", 0, CHANNEL_COUNT - 1)
    n = _check_int(samples, "задержка", 0, MAX_DELAY_SAMPLES)
    return _check_command_size(f"delay{ch}:{n}")


def set_volume(volume: int) -> str:
    return _check_command_size(f"vol:{_check_int(volume, 'громкость', 0, 100)}")


def set_zone_volume(zone: int, volume: int) -> str:
    z = _check_int(zone, "зона", 0, 1)
    return _check_command_size(f"v{z}:{_check_int(volume, 'громкость', 0, 100)}")


def set_mute(zone: int, muted: bool) -> str:
    z = _check_int(zone, "зона", 0, 1)
    return _check_command_size(f"mute:{int(muted)}")


def set_balance(value: float) -> str:
    return _check_command_size(f"bal:{_fmt(_check_number(value, 'баланс', -10, 10))}")


def set_tilt_low(value: float) -> str:
    return _check_command_size(f"tlf:{_fmt(_check_number(value, 'подъём НЧ', -6, 3))}")


def set_tilt_high(value: float) -> str:
    return _check_command_size(f"thf:{_fmt(_check_number(value, 'срез ВЧ', -6, 3))}")


def set_crossover(freq: float) -> str:
    return _check_command_size(f"fc:{_fmt(_check_number(freq, 'кроссовер', 200, 1000))}")


def set_sub_hp(freq: int) -> str:
    return _check_command_size(f"hp:{_check_int(freq, 'срез саба', 20, 80)}")


def set_sub_enabled(enabled: bool) -> str:
    return _check_command_size(f"sub:{int(enabled)}")


def set_crossover_type(kind: int) -> str:
    return _check_command_size(f"xotype:{_check_int(kind, 'тип кроссовера', 1, 2)}")


def set_eq(band: str, gain: float) -> str:
    table = {"low": "eql", "mid": "eqm", "high": "eqh"}
    if band not in table:
        raise ProtocolError(f"полоса: {band!r}, допустимо {sorted(table)}")
    value = _check_number(gain, "усиление", -12, 12)
    return _check_command_size(f"{table[band]}:{_fmt(value)}")


def set_inversion(channel: int) -> str:
    return _check_command_size(f"inv:{_check_int(channel, 'канал', 0, CHANNEL_COUNT - 1)}")


def clear_inversion() -> str:
    return "inv:off"


def apply_preset(index: int) -> str:
    return _check_command_size(f"preset:{_check_int(index, 'пресет', 0, 3)}")


def set_test_mode(mode: str) -> str:
    table = {
        "off": "test:off",
        "all": "test:all",
        "left": "test:l",
        "right": "test:r",
        "woofer": "test:woof",
        "tweeter": "test:tweet",
        "sweep": "test:sweep",
        "anti": "test:anti",
    }
    if mode in table:
        return table[mode]
    if re.fullmatch(r"[1-4]", mode):
        return f"test:{mode}"
    raise ProtocolError(f"режим теста: {mode!r}, допустимо {sorted(table)} или 1..4")


def set_test_freq(freq: int) -> str:
    return _check_command_size(f"tf:{_check_int(freq, 'частота тона', 20, 20000)}")


def set_test_volume(volume: int) -> str:
    return _check_command_size(f"tvol:{_check_int(volume, 'громкость теста', 0, 100)}")


def save() -> str:
    return "save"


def reboot() -> str:
    return "reboot"


def factory_reset() -> str:
    return "factory"


def status() -> str:
    return "status"


def stats() -> str:
    return "stats"


def help_text() -> str:
    return "help"


@dataclass(frozen=True)
class ChannelFilter:
    high_pass_hz: int = 0
    low_pass_hz: int = 0


@dataclass(frozen=True)
class DeviceState:
    vol0: int = 10
    vol1: int = 10
    balance: float = 0.0
    crossover_hz: float = 400.0
    sub_hp_hz: float = 45.0
    sub_on: bool = True
    tilt_low_db: float = 0.0
    tilt_high_db: float = -1.0
    eq_low_db: float = 0.0
    eq_mid_db: float = 0.0
    eq_high_db: float = 0.0
    inverted: tuple[bool, ...] = (False, False, False, False)
    bt_audio_on: bool = False
    spp_on: bool = False
    test_mode: int = 0
    test_volume: int = 6
    crossover_type: int = 1
    source_khz: str = "44.1"
    delays: tuple[int, ...] = (0, 0, 0, 0)
    filters: tuple[ChannelFilter, ...] = field(
        default_factory=lambda: tuple(ChannelFilter() for _ in range(CHANNEL_COUNT))
    )

    @property
    def volume(self) -> int:
        return min(self.vol0, self.vol1)

    def to_dict(self) -> dict:
        data = asdict(self)
        data["inverted"] = list(self.inverted)
        data["delays"] = list(self.delays)
        data["filters"] = [asdict(f) for f in self.filters]
        return data


_PATTERNS: Final[tuple[tuple[re.Pattern[str], str], ...]] = (
    (re.compile(r"V0=(\d+)%\s+V1=(\d+)%\s+bal=(-?[\d.]+)"), "volume"),
    (re.compile(r"Fc=([\d.]+)Hz\s+hp=([\d.]+)Hz\s+sub=(ON|OFF)"), "crossover"),
    (re.compile(r"TLF=(-?[\d.]+)dB\s+THF=(-?[\d.]+)dB"), "tilt"),
    (re.compile(r"EQ:\s*L=(-?[\d.]+)\s+M=(-?[\d.]+)\s+H=(-?[\d.]+)"), "eq"),
    (re.compile(r"INV:\s+([01]{4})"), "inv"),
    (re.compile(r"BT:\s+(ON|OFF)(?:\s*\|\s*SPP:\s+(ON|OFF))?"), "bt"),
    (re.compile(r"Test:\s+(\d+)(?:\s+TVol=(\d+)%)?"), "test"),
    (re.compile(r"Src:\s+([\d.]+)\s*kHz"), "src"),
    (re.compile(r"XO:\s+(Butter|LR4)"), "xo"),
    (
        re.compile(
            r"CHF:\s+(\d+)/(\d+)\s+(\d+)/(\d+)\s+(\d+)/(\d+)\s+(\d+)/(\d+)"
        ),
        "chf",
    ),
    (re.compile(r"Delay:\s+(\d+)/(\d+)/(\d+)/(\d+)"), "delay"),
)

_DELAY_RE: Final[re.Pattern[str]] = _PATTERNS[-1][0]


def is_block_end(line: str) -> bool:
    return _DELAY_RE.search(line) is not None


def _f(value: str, fallback: float) -> float:
    try:
        return float(value)
    except ValueError:
        return fallback


def _i(value: str, fallback: int) -> int:
    try:
        return int(float(value))
    except ValueError:
        return fallback


def parse_status(lines: Iterable[str]) -> DeviceState | None:
    state = DeviceState()
    found = False
    for raw in lines:
        line = raw.strip()
        for pattern, kind in _PATTERNS:
            match = pattern.search(line)
            if match is None:
                continue
            groups = match.groups()
            if kind == "volume":
                state = _replace(
                    state,
                    vol0=_i(groups[0], 10),
                    vol1=_i(groups[1], 10),
                    balance=_f(groups[2], 0.0),
                )
            elif kind == "crossover":
                state = _replace(
                    state,
                    crossover_hz=_f(groups[0], 400.0),
                    sub_hp_hz=_f(groups[1], 45.0),
                    sub_on=groups[2] == "ON",
                )
            elif kind == "tilt":
                state = _replace(
                    state,
                    tilt_low_db=_f(groups[0], 0.0),
                    tilt_high_db=_f(groups[1], -1.0),
                )
            elif kind == "eq":
                state = _replace(
                    state,
                    eq_low_db=_f(groups[0], 0.0),
                    eq_mid_db=_f(groups[1], 0.0),
                    eq_high_db=_f(groups[2], 0.0),
                )
            elif kind == "inv":
                state = _replace(state, inverted=tuple(c == "1" for c in groups[0]))
            elif kind == "bt":
                state = _replace(
                    state,
                    bt_audio_on=groups[0] == "ON",
                    spp_on=len(groups) > 1 and groups[1] == "ON",
                )
            elif kind == "test":
                state = _replace(
                    state,
                    test_mode=_i(groups[0], 0),
                    test_volume=_i(groups[1], state.test_volume) if len(groups) > 1 else state.test_volume,
                )
            elif kind == "src":
                state = _replace(state, source_khz=groups[0])
            elif kind == "xo":
                state = _replace(state, crossover_type=2 if groups[0] == "LR4" else 1)
            elif kind == "chf":
                pairs = [
                    ChannelFilter(_i(groups[i], 0), _i(groups[i + 1], 0))
                    for i in range(0, 8, 2)
                ]
                state = _replace(state, filters=tuple(pairs))
            elif kind == "delay":
                state = _replace(
                    state, delays=tuple(_i(g, 0) for g in groups)
                )
            found = True
            break
    return state if found else None


def _replace(state: DeviceState, **changes) -> DeviceState:
    return dataclasses.replace(state, **changes)


def build_settings_commands(state: DeviceState) -> list[str]:
    commands = [
        set_volume(state.volume),
        set_zone_volume(0, state.vol0),
        set_zone_volume(1, state.vol1),
        set_balance(state.balance),
        set_tilt_low(state.tilt_low_db),
        set_tilt_high(state.tilt_high_db),
        set_crossover(state.crossover_hz),
        set_sub_hp(round(state.sub_hp_hz)),
        set_sub_enabled(state.sub_on),
        set_crossover_type(state.crossover_type),
        set_eq("low", state.eq_low_db),
        set_eq("mid", state.eq_mid_db),
        set_eq("high", state.eq_high_db),
        set_test_volume(state.test_volume),
    ]
    for index, delay in enumerate(state.delays[:CHANNEL_COUNT]):
        commands.append(channel_delay(index, delay))
    for index, filt in enumerate(state.filters[:CHANNEL_COUNT]):
        commands.append(channel_filter(index, True, filt.high_pass_hz))
        commands.append(channel_filter(index, False, filt.low_pass_hz))
    for index, flag in enumerate(state.inverted[:CHANNEL_COUNT]):
        if flag:
            commands.append(set_inversion(index))
    return commands
