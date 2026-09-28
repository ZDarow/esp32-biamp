from __future__ import annotations

import abc
import queue
import socket
import threading
import time
from typing import Final

DEFAULT_BAUD: Final[int] = 115200
READ_CHUNK: Final[int] = 4096


class TransportError(RuntimeError):
    pass


def list_serial_ports() -> list[str]:
    try:
        from serial.tools import list_ports
    except ImportError as exc:
        raise TransportError(
            "не установлен pyserial — выполните: pip install -r requirements.txt"
        ) from exc
    return [p.device for p in list_ports.comports()]


class LineTransport(abc.ABC):
    def __init__(self) -> None:
        self._lines: queue.Queue[str] = queue.Queue()
        self._pending = ""
        self._closed = threading.Event()
        self._thread: threading.Thread | None = None

    @abc.abstractmethod
    def write(self, data: bytes) -> None: ...

    @abc.abstractmethod
    def close(self) -> None: ...

    @property
    @abc.abstractmethod
    def description(self) -> str: ...

    def start(self) -> None:
        self._thread = threading.Thread(target=self._pump, daemon=True)
        self._thread.start()

    def read_lines(self, timeout: float) -> list[str]:
        collected: list[str] = []
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                collected.append(self._lines.get(timeout=remaining))
            except queue.Empty:
                break
        return collected

    def send_line(self, command: str) -> None:
        self.write(command.encode("utf-8") + b"\n")

    def _feed(self, text: str) -> None:
        self._pending += text
        while "\n" in self._pending or "\r" in self._pending:
            cut = min(
                (self._pending.find(c) for c in "\r\n" if self._pending.find(c) >= 0),
                default=-1,
            )
            line = self._pending[:cut]
            self._pending = self._pending[cut + 1 :]
            if line.strip():
                self._lines.put(line)

    def _pump(self) -> None:
        while not self._closed.is_set():
            try:
                data = self._read()
            except Exception:
                break
            if data:
                self._feed(data.decode("utf-8", errors="replace"))
            else:
                time.sleep(0.02)
        self._closed.set()

    @abc.abstractmethod
    def _read(self) -> bytes: ...


class SerialTransport(LineTransport):
    def __init__(self, port: str, baud: int = DEFAULT_BAUD, timeout: float = 0.2) -> None:
        super().__init__()
        self._port_name = port
        self._baud = baud
        try:
            import serial
        except ImportError as exc:
            raise TransportError(
                "не установлен pyserial — выполните: pip install -r requirements.txt"
            ) from exc
        try:
            self._serial = serial.Serial(port, baud, timeout=timeout)
        except Exception as exc:
            raise TransportError(f"не удалось открыть {port} @ {baud}: {exc}") from exc

    @property
    def description(self) -> str:
        return f"{self._port_name} @ {self._baud}"

    def _read(self) -> bytes:
        waiting = self._serial.in_waiting
        return self._serial.read(waiting or 1)

    def write(self, data: bytes) -> None:
        self._serial.write(data)

    def close(self) -> None:
        self._closed.set()
        try:
            self._serial.close()
        except Exception:
            pass


class TcpTransport(LineTransport):
    def __init__(self, host: str, port: int) -> None:
        super().__init__()
        self._address = (host, port)
        try:
            self._socket = socket.create_connection(self._address, timeout=5)
        except OSError as exc:
            raise TransportError(f"не удалось подключиться к {host}:{port}: {exc}") from exc
        self._socket.settimeout(0.2)

    @property
    def description(self) -> str:
        return f"tcp://{self._address[0]}:{self._address[1]}"

    def _read(self) -> bytes:
        try:
            return self._socket.recv(READ_CHUNK)
        except socket.timeout:
            return b""
        except OSError as exc:
            raise TransportError(f"соединение потеряно: {exc}") from exc

    def write(self, data: bytes) -> None:
        try:
            self._socket.sendall(data)
        except OSError as exc:
            raise TransportError(f"ошибка отправки: {exc}") from exc

    def close(self) -> None:
        self._closed.set()
        try:
            self._socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self._socket.close()


def open_transport(target: str, baud: int = DEFAULT_BAUD) -> LineTransport:
    if target.startswith("tcp://"):
        rest = target[len("tcp://") :]
        host, _, port = rest.partition(":")
        if not port:
            raise TransportError("формат tcp://хост:порт")
        return TcpTransport(host, int(port))
    return SerialTransport(target, baud)


class LoopbackTransport(LineTransport):
    def __init__(self, auto_responses: dict[str, str] | None = None) -> None:
        super().__init__()
        self.sent: list[str] = []
        self._responses: queue.Queue[str] = queue.Queue()
        self._auto = auto_responses or {}

    @property
    def description(self) -> str:
        return "loopback"

    def _read(self) -> bytes:
        try:
            return self._responses.get(timeout=0.05).encode("utf-8")
        except queue.Empty:
            return b""

    def write(self, data: bytes) -> None:
        for line in data.decode("utf-8").split("\n"):
            command = line.strip()
            if not command:
                continue
            self.sent.append(command)
            reply = self._auto.get(command)
            if reply is not None:
                self._responses.put(reply)

    def close(self) -> None:
        self._closed.set()

    def emit(self, text: str) -> None:
        self._responses.put(text)
