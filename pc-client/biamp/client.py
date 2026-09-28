from __future__ import annotations

import time

from . import protocol as p
from .transport import LineTransport, TransportError, open_transport

STATUS_TIMEOUT = 1.2
COMMAND_SETTLE = 0.06
APPLY_SETTLE = 0.12


class BiAmpClient:
    def __init__(self, transport: LineTransport) -> None:
        self._transport = transport
        self._transport.start()

    @classmethod
    def open(cls, target: str, baud: int = 115200) -> "BiAmpClient":
        return cls(open_transport(target, baud))

    @property
    def description(self) -> str:
        return self._transport.description

    def close(self) -> None:
        self._transport.close()

    def __enter__(self) -> "BiAmpClient":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def send(self, command: str, settle: float = COMMAND_SETTLE) -> None:
        self._transport.send_line(command)
        time.sleep(settle)

    def read_status(self, timeout: float = STATUS_TIMEOUT) -> p.DeviceState:
        self.send(p.status())
        lines: list[str] = []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            chunk = self._transport.read_lines(0.15)
            if not chunk:
                if lines:
                    break
                continue
            lines.extend(chunk)
            if any(p.is_block_end(line) for line in chunk):
                break
        state = p.parse_status(lines)
        if state is None:
            preview = " | ".join(lines[:4]) if lines else "(нет ответа)"
            raise TransportError(f"не удалось разобрать status: {preview}")
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

    def set_value(self, name: str, value) -> str:
        builders = {
            "volume": p.set_volume,
            "balance": p.set_balance,
            "tilt_low": p.set_tilt_low,
            "tilt_high": p.set_tilt_high,
            "crossover": p.set_crossover,
            "sub_hp": p.set_sub_hp,
            "sub": p.set_sub_enabled,
            "crossover_type": p.set_crossover_type,
            "test_volume": p.set_test_volume,
            "test_freq": p.set_test_freq,
        }
        if name not in builders:
            raise p.ProtocolError(f"параметр {name!r} не поддерживается")
        command = builders[name](value)
        self.send(command)
        return command
