from __future__ import annotations

import abc
import queue
import socket
import threading
import time
from typing import Any, Final

DEFAULT_BAUD: Final[int] = 115200
READ_CHUNK: Final[int] = 4096
MAX_PENDING_CHARS: Final[int] = 65536


class TransportError(RuntimeError):
    pass


def list_serial_ports() -> list[str]:
    try:
        from serial.tools import list_ports
    except ImportError as exc:
        raise TransportError("не установлен pyserial — выполните: pip install -r requirements.txt") from exc
    return [p.device for p in list_ports.comports()]


class LineTransport(abc.ABC):
    def __init__(self) -> None:
        self._lines: queue.Queue[str] = queue.Queue()
        self._pending = ""
        self._closed = threading.Event()
        self._disconnected = threading.Event()
        self._last_error: str = ""
        self._thread: threading.Thread | None = None
        self._reconnect_attempts = 0
        self._max_reconnect_attempts = 5
        self._reconnect_backoff = 1.0
        self._reconnect_timeout = 5.0

    @abc.abstractmethod
    def write(self, data: bytes) -> None: ...

    @abc.abstractmethod
    def close(self) -> None: ...

    @abc.abstractmethod
    def _reconnect(self) -> None: ...

    @property
    @abc.abstractmethod
    def description(self) -> str: ...

    @property
    def is_connected(self) -> bool:
        return not self._disconnected.is_set()

    @property
    def last_error(self) -> str:
        return self._last_error

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
        if self._disconnected.is_set():
            raise TransportError(f"транспорт отключён: {self._last_error}")
        self.write(command.encode("utf-8") + b"\n")

    def _feed(self, text: str) -> None:
        self._pending += text
        if len(self._pending) > MAX_PENDING_CHARS and "\n" not in self._pending and "\r" not in self._pending:
            self._last_error = (
                f"входящая строка длиннее {MAX_PENDING_CHARS} символов без перевода строки — буфер сброшен"
            )
            self._disconnected.set()
            self._pending = ""
            raise TransportError(self._last_error)
        while "\n" in self._pending or "\r" in self._pending:
            cut = min(
                (self._pending.find(c) for c in "\r\n" if self._pending.find(c) >= 0),
                default=-1,
            )
            line = self._pending[:cut]
            self._pending = self._pending[cut + 1 :]
            if line.strip():
                self._lines.put(line[:MAX_PENDING_CHARS])

    def _pump(self) -> None:
        backoff = 0.05
        while not self._closed.is_set():
            try:
                data = self._read()
                backoff = 0.05
                self._reconnect_attempts = 0
                self._reconnect_backoff = 1.0
            except TransportError as exc:
                self._last_error = str(exc)
                self._disconnected.set()
                if self._closed.is_set():
                    break
                time.sleep(backoff)
                backoff = min(backoff * 2, 5.0)
                if self._reconnect_attempts >= self._max_reconnect_attempts:
                    self._last_error = "превышено максимальное количество попыток повторного подключения"
                    continue
                try:
                    time.sleep(self._reconnect_backoff)
                    self._reconnect()
                    self._disconnected.clear()
                    self._last_error = ""
                    self._reconnect_attempts += 1
                    self._reconnect_backoff = min(self._reconnect_backoff * 2, 30.0)
                except Exception as exc:
                    self._last_error = f"переподключение не удалось: {exc}"
                    self._reconnect_attempts += 1
                continue
            except Exception as exc:
                self._last_error = f"ошибка приёма: {exc}"
                if self._closed.is_set():
                    break
                time.sleep(0.1)
                continue
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
        self._timeout = timeout
        self._serial = self._open_serial()

    def _open_serial(self) -> Any:
        try:
            import serial
        except ImportError as exc:
            raise TransportError(
                "не установлен pyserial — выполните: pip install -r requirements.txt"
            ) from exc
        try:
            return serial.Serial(self._port_name, self._baud, timeout=self._timeout, write_timeout=self._reconnect_timeout)
        except Exception as exc:
            raise TransportError(f"не удалось открыть {self._port_name} @ {self._baud}: {exc}") from exc

    def _reconnect(self) -> None:
        try:
            self._serial.close()
        except Exception:
            pass
        try:
            self._serial = self._open_serial()
        except Exception as exc:
            raise TransportError(f"не удалось открыть {self._port_name}: {exc}") from exc

    @property
    def description(self) -> str:
        return f"{self._port_name} @ {self._baud}"

    def _read(self) -> bytes:
        try:
            waiting = self._serial.in_waiting
            chunk: bytes = self._serial.read(waiting or 1)
            return chunk
        except Exception as exc:
            raise TransportError(f"ошибка чтения {self._port_name}: {exc}") from exc

    def write(self, data: bytes) -> None:
        try:
            self._serial.write(data)
        except Exception as exc:
            raise TransportError(f"ошибка записи {self._port_name}: {exc}") from exc

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
        self._socket = self._open_socket()

    def _open_socket(self) -> socket.socket:
        try:
            sock = socket.create_connection(self._address, timeout=self._reconnect_timeout)
        except OSError as exc:
            host, port = self._address[0], self._address[1]
            raise TransportError(f"не удалось подключиться к {host}:{port}: {exc}") from exc
        sock.settimeout(0.2)
        return sock

    def _reconnect(self) -> None:
        try:
            self._socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self._socket.close()
        except OSError:
            pass
        try:
            self._socket = self._open_socket()
        except Exception as exc:
            raise TransportError(f"не удалось подключиться к {self._address[0]}:{self._address[1]}: {exc}") from exc

    @property
    def description(self) -> str:
        return f"tcp://{self._address[0]}:{self._address[1]}"

    def _read(self) -> bytes:
        try:
            return self._socket.recv(READ_CHUNK)
        except TimeoutError:
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
        try:
            self._socket.close()
        except OSError:
            pass


def open_transport(target: str, baud: int = DEFAULT_BAUD) -> LineTransport:
    if target.startswith("tcp://"):
        rest = target[len("tcp://") :]
        host, sep, port = rest.rpartition(":")
        if not sep or not host:
            raise TransportError(f"формат tcp://хост:порт, получено {target!r}")
        try:
            number = int(port)
        except ValueError as exc:
            raise TransportError(f"порт {port!r} не число") from exc
        if not (1 <= number <= 65535):
            raise TransportError(f"порт {number} вне диапазона 1..65535")
        return TcpTransport(host, number)
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

    def _reconnect(self) -> None:
        pass

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
