from __future__ import annotations

import dataclasses
import enum
import re
from collections.abc import Iterable
from dataclasses import asdict, dataclass, field
from typing import Any, Final

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


class _BoolWord(enum.Enum):
    ONE = "1"
    ZERO = "0"
    TRUE = "true"
    FALSE = "false"
    ON = "on"
    OFF = "off"
    YES = "yes"
    NO = "no"
    DA = "да"
    NET = "нет"
    VKL = "вкл"
    VYKL = "выкл"


_TRUE_WORDS: Final[frozenset[str]] = frozenset(
    item.value for item in _BoolWord if item.value in ("1", "true", "on", "yes", "да", "вкл")
)
_FALSE_WORDS: Final[frozenset[str]] = frozenset(
    item.value for item in _BoolWord if item.value in ("0", "false", "off", "no", "нет", "выкл")
)

_ON_OFF_RE: Final[str] = f"(?:{_BoolWord.ON.value}|{_BoolWord.OFF.value})"
_BOOL_01_RE: Final[str] = f"(?:{_BoolWord.ONE.value}|{_BoolWord.ZERO.value})"


def to_bool(value: object, name: str = "значение") -> bool:
    """Принимает и числа, и строки CLI/JSON. Молчаливого `bool('false')` не бывает."""
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        if value in (0, 1):
            return bool(value)
        raise ProtocolError(f"{name}: {value!r}, ожидалось 0 или 1")
    if isinstance(value, str):
        text = value.strip().lower()
        if text in _TRUE_WORDS:
            return True
        if text in _FALSE_WORDS:
            return False
    raise ProtocolError(f"{name}: {value!r}, ожидалось true/false, on/off, 1/0 (регистр не важен)")


def _check_command_size(command: str) -> str:
    raw = command.encode("utf-8")
    if len(raw) > MAX_COMMAND_BYTES:
        raise ProtocolError(f"команда {len(raw)} байт, буфер прошивки {MAX_COMMAND_BYTES} — будет отброшена")
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
    return _check_command_size(f"mute:{z}:{int(bool(muted))}")


def set_swap(enabled: bool) -> str:
    return _check_command_size(f"swap:{int(bool(enabled))}")


def set_dup(enabled: bool) -> str:
    return _check_command_size(f"dup:{int(bool(enabled))}")


def set_xo_enabled(enabled: bool) -> str:
    return _check_command_size(f"xo:{int(bool(enabled))}")


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
    return _check_command_size(f"tvol:{_check_int(volume, 'громкость теста', 0, 6)}")


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
    muted_z0: bool = False
    muted_z1: bool = False
    bt_audio_on: bool = False
    spp_on: bool = False
    test_mode: int = 0
    test_volume: int = 6
    crossover_type: int = 1
    xo_on: bool = True
    lr_swap: bool = False
    dup_out: bool = False
    source_khz: str = "44.1"
    delays: tuple[int, ...] = (0, 0, 0, 0)
    filters: tuple[ChannelFilter, ...] = field(
        default_factory=lambda: tuple(ChannelFilter() for _ in range(CHANNEL_COUNT))
    )
    unparsed_lines: tuple[str, ...] = ()

    @property
    def volume(self) -> int:
        return min(self.vol0, self.vol1)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["delays"] = list(self.delays)
        data["filters"] = [asdict(f) for f in self.filters]
        return data


_PATTERNS: Final[tuple[tuple[re.Pattern[str], str], ...]] = (
    (re.compile(r"V0=(\d+)%\s+V1=(\d+)%\s+bal=(-?[\d.]+)"), "volume"),
    (re.compile(r"Fc=([\d.]+)Hz\s+hp=([\d.]+)Hz\s+sub=(?P<sub>" + _ON_OFF_RE + r")"), "crossover"),
    (re.compile(r"XO:\s+(?P<xo_type>Butter|LR4)(?:\s+(?P<xo_on>" + _ON_OFF_RE + r"))?"), "xo"),
    (re.compile(r"TLF=(-?[\d.]+)dB\s+THF=(-?[\d.]+)dB"), "tilt"),
    (re.compile(r"EQ:\s*L=(-?[\d.]+)\s+M=(-?[\d.]+)\s+H=(-?[\d.]+)"), "eq"),
    (re.compile(r"Mute:\s*(?P<m0>" + _BOOL_01_RE + r")/(?P<m1>" + _BOOL_01_RE + r")"), "mute"),
    (re.compile(r"SWP:\s*(?P<swp>" + _BOOL_01_RE + r")"), "swap"),
    (re.compile(r"DUP:\s*(?P<dup>" + _BOOL_01_RE + r")"), "dup"),
    (re.compile(r"BT:\s+(?P<bt>" + _ON_OFF_RE + r")(?:\s*\|\s*SPP:\s+(?P<spp>" + _ON_OFF_RE + r"))?"), "bt"),
    (re.compile(r"Test:\s+(\d+)(?:\s+TVol=(\d+)%)?"), "test"),
    (re.compile(r"Src:\s+([\d.]+)\s*kHz"), "src"),
    (
        re.compile(r"CHF:\s+(\d+)/(\d+)\s+(\d+)/(\d+)\s+(\d+)/(\d+)\s+(\d+)/(\d+)"),
        "chf",
    ),
    (re.compile(r"Delay:\s+(\d+)/(\d+)/(\d+)/(\d+)"), "delay"),
)

_DELAY_RE: Final[re.Pattern[str]] = _PATTERNS[-1][0]
_VOLUME_RE: Final[re.Pattern[str]] = _PATTERNS[0][0]


def is_block_end(line: str) -> bool:
    return _DELAY_RE.search(line) is not None


def is_block_start(line: str) -> bool:
    return _VOLUME_RE.search(line) is not None


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


def parse_status(
    lines: Iterable[str],
    base: DeviceState | None = None,
    strict: bool = False,
) -> DeviceState | None:
    state = base if base is not None else DeviceState()
    found = False
    unparsed: list[str] = []
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        matched = False
        for pattern, kind in _PATTERNS:
            match = pattern.search(line)
            if match is None:
                continue
            matched = True
            groups = match.groups()
            if kind == "volume":
                state = _replace(
                    state,
                    vol0=_i(groups[0], state.vol0),
                    vol1=_i(groups[1], state.vol1),
                    balance=_f(groups[2], state.balance),
                )
            elif kind == "crossover":
                state = _replace(
                    state,
                    crossover_hz=_f(groups[0], state.crossover_hz),
                    sub_hp_hz=_f(groups[1], state.sub_hp_hz),
                    sub_on=groups[2] == _BoolWord.ON.value,
                )
            elif kind == "xo":
                xo_on_value = groups[1] if len(groups) > 1 and groups[1] is not None else _BoolWord.ON.value
                state = _replace(
                    state,
                    crossover_type=2 if groups[0] == "LR4" else 1,
                    xo_on=xo_on_value == _BoolWord.ON.value,
                )
            elif kind == "tilt":
                state = _replace(
                    state,
                    tilt_low_db=_f(groups[0], state.tilt_low_db),
                    tilt_high_db=_f(groups[1], state.tilt_high_db),
                )
            elif kind == "eq":
                state = _replace(
                    state,
                    eq_low_db=_f(groups[0], state.eq_low_db),
                    eq_mid_db=_f(groups[1], state.eq_mid_db),
                    eq_high_db=_f(groups[2], state.eq_high_db),
                )
            elif kind == "mute":
                state = _replace(
                    state,
                    muted_z0=groups[0] == _BoolWord.ONE.value,
                    muted_z1=groups[1] == _BoolWord.ONE.value,
                )
            elif kind == "swap":
                state = _replace(state, lr_swap=groups[0] == _BoolWord.ONE.value)
            elif kind == "dup":
                state = _replace(state, dup_out=groups[0] == _BoolWord.ONE.value)
            elif kind == "bt":
                state = _replace(
                    state,
                    bt_audio_on=groups[0] == _BoolWord.ON.value,
                    spp_on=len(groups) > 1 and groups[1] == _BoolWord.ON.value,
                )
            elif kind == "test":
                state = _replace(
                    state,
                    test_mode=_i(groups[0], state.test_mode),
                    test_volume=_i(groups[1], state.test_volume) if len(groups) > 1 else state.test_volume,
                )
            elif kind == "src":
                state = _replace(state, source_khz=groups[0])
            elif kind == "chf":
                pairs = []
                for index in range(CHANNEL_COUNT):
                    previous = state.filters[index] if index < len(state.filters) else ChannelFilter()
                    pairs.append(
                        ChannelFilter(
                            _i(groups[index * 2], previous.high_pass_hz),
                            _i(groups[index * 2 + 1], previous.low_pass_hz),
                        )
                    )
                state = _replace(state, filters=tuple(pairs))
            elif kind == "delay":
                values: list[int] = []
                for index, raw_value in enumerate(groups):
                    # Имя отличается от переменной ветки chf: там previous — это
                    # ChannelFilter, здесь — int. Общее имя заставляло
                    # проверку типов считать, что здесь тоже ChannelFilter.
                    previous_delay = state.delays[index] if index < len(state.delays) else 0
                    values.append(_i(raw_value, previous_delay))
                state = _replace(state, delays=tuple(values))
            found = True
            break
        if not matched:
            unparsed.append(line)
    if not found:
        return None
    state = _replace(state, unparsed_lines=tuple(unparsed))
    if strict and unparsed:
        raise ProtocolError(f"нераспознанные строки блока: {unparsed}")
    return state


def _replace(state: DeviceState, **changes: Any) -> DeviceState:
    return dataclasses.replace(state, **changes)


def state_from_dict(data: dict[str, Any]) -> DeviceState:
    """Собирает DeviceState из словаря с валидацией типов и длин.

    `delays` и `filters` обязательны: без них `apply` молча обнулил бы
    задержки и фильтры всех каналов усилителя.
    """
    if not isinstance(data, dict):
        raise ProtocolError("state: ожидается объект")
    delays_raw = data.get("delays")
    if not isinstance(delays_raw, (list, tuple)) or len(delays_raw) != CHANNEL_COUNT:
        raise ProtocolError(f"delays: ожидается {CHANNEL_COUNT} значения")
    delays = tuple(_check_int(int(x), "задержка", 0, MAX_DELAY_SAMPLES) for x in delays_raw)

    filters_raw = data.get("filters")
    if not isinstance(filters_raw, (list, tuple)) or len(filters_raw) != CHANNEL_COUNT:
        raise ProtocolError(f"filters: ожидается {CHANNEL_COUNT} элемента")
    filters_list: list[ChannelFilter] = []
    for item in filters_raw:
        if not isinstance(item, dict):
            raise ProtocolError("filters: ожидается объект")
        filters_list.append(
            ChannelFilter(
                _check_int(int(item.get("high_pass_hz", 0)), "HP", 0, MIN_SAMPLE_RATE_HZ),
                _check_int(int(item.get("low_pass_hz", 0)), "LP", 0, MIN_SAMPLE_RATE_HZ),
            )
        )
    filters = tuple(filters_list)

    def _bool(key: str, default: bool) -> bool:
        return bool(data.get(key, default))

    def _int(key: str, default: int, low: int, high: int) -> int:
        return _check_int(int(data.get(key, default)), key, low, high)

    return DeviceState(
        vol0=_int("vol0", 10, 0, 100),
        vol1=_int("vol1", 10, 0, 100),
        balance=_check_number(float(data.get("balance", 0.0)), "balance", -10, 10),
        crossover_hz=_check_number(float(data.get("crossover_hz", 400.0)), "crossover_hz", 200, 1000),
        sub_hp_hz=_check_number(float(data.get("sub_hp_hz", 45.0)), "sub_hp_hz", 20, 80),
        sub_on=_bool("sub_on", True),
        tilt_low_db=_check_number(float(data.get("tilt_low_db", 0.0)), "tilt_low_db", -6, 3),
        tilt_high_db=_check_number(float(data.get("tilt_high_db", -1.0)), "tilt_high_db", -6, 3),
        eq_low_db=_check_number(float(data.get("eq_low_db", 0.0)), "eq_low_db", -12, 12),
        eq_mid_db=_check_number(float(data.get("eq_mid_db", 0.0)), "eq_mid_db", -12, 12),
        eq_high_db=_check_number(float(data.get("eq_high_db", 0.0)), "eq_high_db", -12, 12),
        muted_z0=_bool("muted_z0", False),
        muted_z1=_bool("muted_z1", False),
        bt_audio_on=_bool("bt_audio_on", False),
        spp_on=_bool("spp_on", False),
        test_mode=_int("test_mode", 0, 0, 4),
        test_volume=_int("test_volume", 6, 0, 6),
        crossover_type=_int("crossover_type", 1, 1, 2),
        xo_on=_bool("xo_on", True),
        lr_swap=_bool("lr_swap", False),
        dup_out=_bool("dup_out", False),
        source_khz=str(data.get("source_khz", "44.1")),
        delays=delays,
        filters=filters,
    )


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
        set_xo_enabled(state.xo_on),
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
    commands.append(set_mute(0, state.muted_z0))
    commands.append(set_mute(1, state.muted_z1))
    commands.append(set_swap(state.lr_swap))
    commands.append(set_dup(state.dup_out))
    return commands
