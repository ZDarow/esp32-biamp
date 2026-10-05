from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

from . import protocol as p
from .transport import LineTransport, TransportError, open_transport

STATUS_TIMEOUT = 1.2
COMMAND_SETTLE = 0.06
APPLY_SETTLE = 0.12


class BiAmpClient:
    def __init__(self, transport: LineTransport) -> None:
        self._transport = transport
        self._transport.start()
        self._last_state: p.DeviceState | None = None

    @classmethod
    def open(cls, target: str, baud: int = 115200) -> BiAmpClient:
        return cls(open_transport(target, baud))

    @property
    def description(self) -> str:
        return self._transport.description

    def close(self) -> None:
        self._transport.close()

    def __enter__(self) -> BiAmpClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def send(self, command: str, settle: float = COMMAND_SETTLE) -> None:
        self._transport.send_line(command)
        time.sleep(settle)

    def read_status(self, timeout: float = STATUS_TIMEOUT) -> p.DeviceState:
        self.send(p.status())
        block: list[str] = []
        started = False
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            chunk = self._transport.read_lines(0.15)
            if not chunk:
                if block:
                    break
                continue
            ended = False
            for line in chunk:
                if p.is_block_start(line):
                    block.clear()
                    started = True
                if started:
                    block.append(line)
                if p.is_block_end(line):
                    ended = True
            if ended:
                break
        state = p.parse_status(block, base=self._last_state)
        if state is None:
            preview = " | ".join(block[:4]) if block else "(нет ответа)"
            raise TransportError(f"не удалось разобрать status: {preview}")
        if state.unparsed_lines:
            raise TransportError(f"нераспознанные строки status: {' | '.join(state.unparsed_lines)}")
        self._last_state = state
        return state

    def query(self, command: str, timeout: float = 0.8) -> list[str]:
        self.send(command)
        return self._transport.read_lines(timeout)

    def apply_state(self, state: p.DeviceState, save: bool = False) -> list[str]:
        commands = p.build_settings_commands(state)
        for command in commands:
            self._transport.send_line(command)
            time.sleep(APPLY_SETTLE)
        if save:
            self._transport.send_line(p.save())
            time.sleep(APPLY_SETTLE)
        return commands

    def set_value(self, name: str, value: Any) -> str:
        builders: dict[str, Callable[[Any], str]] = {
            "volume": p.set_volume,
            "balance": p.set_balance,
            "tilt_low": p.set_tilt_low,
            "tilt_high": p.set_tilt_high,
            "crossover": p.set_crossover,
            "sub_hp": p.set_sub_hp,
            "sub": lambda v: p.set_sub_enabled(p.to_bool(v, "sub")),
            "crossover_type": p.set_crossover_type,
            "xo_on": lambda v: p.set_xo_enabled(p.to_bool(v, "xo_on")),
            "test_volume": p.set_test_volume,
            "test_freq": p.set_test_freq,
            "lr_swap": lambda v: p.set_swap(p.to_bool(v, "lr_swap")),
            "dup_out": lambda v: p.set_dup(p.to_bool(v, "dup_out")),
            "mute0": lambda v: p.set_mute(0, p.to_bool(v, "mute0")),
            "mute1": lambda v: p.set_mute(1, p.to_bool(v, "mute1")),
        }
        if name not in builders:
            raise p.ProtocolError(f"параметр {name!r} не поддерживается")
        command = builders[name](value)
        self.send(command)
        return command
