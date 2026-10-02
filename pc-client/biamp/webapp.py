from __future__ import annotations

import dataclasses
import json
import secrets
import sys
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Final
from urllib.parse import urlsplit

from . import protocol as p
from .client import BiAmpClient
from .transport import TransportError

STATIC = Path(__file__).parent / "static"
LOCK = threading.Lock()

LOCK_WAIT: Final[float] = 2.0
MAX_BODY_BYTES: Final[int] = 64 * 1024
DRAIN_LIMIT: Final[int] = 1024 * 1024
SOCKET_TIMEOUT: Final[float] = 15.0
TOKEN_HEADER: Final[str] = "X-BiAmp-Token"
TOKEN_META: Final[str] = "biamp-token"
LOOPBACK_NAMES: Final[frozenset[str]] = frozenset({"127.0.0.1", "localhost", "::1"})

SECURITY_HEADERS: Final[dict[str, str]] = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}

SAFE_COMMANDS: Final[frozenset[str]] = frozenset({"status", "stats", "help", "save"})
PRESET_ARGS: Final[frozenset[str]] = frozenset({"0", "1", "2", "3"})
TEST_MODES: Final[frozenset[str]] = frozenset(
    {"off", "all", "l", "r", "woof", "tweet", "anti", "sweep", "1", "2", "3", "4"}
)

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
}


class HttpError(Exception):
    """Ошибка уровня HTTP: код и текст, которые уходят клиенту."""

    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def generate_token() -> str:
    return secrets.token_urlsafe(24)


def check_command(value: object) -> str:
    """Белый список команд `/api/command`.

    Разрушающие `factory` и `reboot` сюда НЕ входят намеренно: из браузера
    их отправлять нельзя даже при валидном токене.
    """
    if not isinstance(value, str):
        raise p.ProtocolError("command: ожидается строка")
    command = value.strip()
    if not command:
        raise p.ProtocolError("command: пустая команда")
    if len(command.encode("utf-8")) > p.MAX_COMMAND_BYTES:
        raise p.ProtocolError(
            f"command: {len(command.encode('utf-8'))} байт, буфер прошивки {p.MAX_COMMAND_BYTES}"
        )
    name, _, arg = command.partition(":")
    if name in SAFE_COMMANDS and not arg:
        return command
    if name == "preset" and arg in PRESET_ARGS:
        return command
    if name == "test" and arg in TEST_MODES:
        return command
    raise p.ProtocolError(
        f"команда {command!r} отсутствует в белом списке веб-панели "
        f"(разрешены: {', '.join(sorted(SAFE_COMMANDS))}, preset:0..3, test:<режим>)"
    )


def audit(message: str) -> None:
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{stamp}] audit: {message}", file=sys.stderr, flush=True)


MUTABLE = {
    "vol0": lambda s, v: dataclasses.replace(s, vol0=p.check_int(int(v), "vol0", 0, 100)),
    "vol1": lambda s, v: dataclasses.replace(s, vol1=p.check_int(int(v), "vol1", 0, 100)),
    "balance": lambda s, v: dataclasses.replace(s, balance=p.check_number(float(v), "balance", -10, 10)),
    "crossover_hz": lambda s, v: dataclasses.replace(
        s, crossover_hz=p.check_number(float(v), "fc", 200, 1000)
    ),
    "sub_hp_hz": lambda s, v: dataclasses.replace(s, sub_hp_hz=p.check_number(float(v), "hp", 20, 80)),
    "sub_on": lambda s, v: dataclasses.replace(s, sub_on=p.to_bool(v, "sub_on")),
    "xo_on": lambda s, v: dataclasses.replace(s, xo_on=p.to_bool(v, "xo_on")),
    "lr_swap": lambda s, v: dataclasses.replace(s, lr_swap=p.to_bool(v, "lr_swap")),
    "dup_out": lambda s, v: dataclasses.replace(s, dup_out=p.to_bool(v, "dup_out")),
    "muted_z0": lambda s, v: dataclasses.replace(s, muted_z0=p.to_bool(v, "muted_z0")),
    "muted_z1": lambda s, v: dataclasses.replace(s, muted_z1=p.to_bool(v, "muted_z1")),
    "tilt_low_db": lambda s, v: dataclasses.replace(s, tilt_low_db=p.check_number(float(v), "tlf", -6, 3)),
    "tilt_high_db": lambda s, v: dataclasses.replace(s, tilt_high_db=p.check_number(float(v), "thf", -6, 3)),
    "eq_low_db": lambda s, v: dataclasses.replace(s, eq_low_db=p.check_number(float(v), "eql", -12, 12)),
    "eq_mid_db": lambda s, v: dataclasses.replace(s, eq_mid_db=p.check_number(float(v), "eqm", -12, 12)),
    "eq_high_db": lambda s, v: dataclasses.replace(s, eq_high_db=p.check_number(float(v), "eqh", -12, 12)),
    "test_volume": lambda s, v: dataclasses.replace(s, test_volume=p.check_int(int(v), "tvol", 0, 6)),
    "crossover_type": lambda s, v: dataclasses.replace(s, crossover_type=p.check_int(int(v), "xotype", 1, 2)),
}


def _coerce(name: str, build) -> p.DeviceState:
    """Любой мусор из JSON обязан становиться ProtocolError, а не traceback."""
    try:
        return build()
    except p.ProtocolError:
        raise
    except (TypeError, ValueError, IndexError, AttributeError) as exc:
        raise p.ProtocolError(f"{name}: значение недопустимо ({type(exc).__name__}: {exc})") from exc


def apply_field(state: p.DeviceState, name: str, value) -> p.DeviceState:
    if name in MUTABLE:
        return _coerce(name, lambda: MUTABLE[name](state, value))
    if name.startswith("delay"):
        return _coerce(name, lambda: _apply_delay(state, name, value))
    if name.startswith(("chhp", "chlp")):
        return _coerce(name, lambda: _apply_filter(state, name, value))
    raise p.ProtocolError(f"параметр {name!r} не поддерживается")


def _apply_delay(state: p.DeviceState, name: str, value) -> p.DeviceState:
    index = p.check_int(int(name[5:]), "канал", 0, p.CHANNEL_COUNT - 1)
    delays = list(state.delays)
    delays[index] = p.check_int(int(value), "задержка", 0, p.MAX_DELAY_SAMPLES)
    return dataclasses.replace(state, delays=tuple(delays))


def _apply_filter(state: p.DeviceState, name: str, value) -> p.DeviceState:
    low = name.startswith("chhp")
    index = p.check_int(int(name[4:]), "канал", 0, p.CHANNEL_COUNT - 1)
    filters = list(state.filters)
    hz = p.check_int(int(value), "частота", 0, p.MIN_SAMPLE_RATE_HZ)
    effective = 20 if 0 < hz < 20 else hz
    current = filters[index]
    filters[index] = (
        p.ChannelFilter(effective, current.low_pass_hz)
        if low
        else p.ChannelFilter(current.high_pass_hz, effective)
    )
    return dataclasses.replace(state, filters=tuple(filters))


def command_for(name: str, before: p.DeviceState, after: p.DeviceState) -> str | None:
    builders = {
        "vol0": lambda s: p.set_zone_volume(0, s.vol0),
        "vol1": lambda s: p.set_zone_volume(1, s.vol1),
        "balance": lambda s: p.set_balance(s.balance),
        "crossover_hz": lambda s: p.set_crossover(s.crossover_hz),
        "sub_hp_hz": lambda s: p.set_sub_hp(round(s.sub_hp_hz)),
        "sub_on": lambda s: p.set_sub_enabled(s.sub_on),
        "xo_on": lambda s: p.set_xo_enabled(s.xo_on),
        "lr_swap": lambda s: p.set_swap(s.lr_swap),
        "dup_out": lambda s: p.set_dup(s.dup_out),
        "muted_z0": lambda s: p.set_mute(0, s.muted_z0),
        "muted_z1": lambda s: p.set_mute(1, s.muted_z1),
        "tilt_low_db": lambda s: p.set_tilt_low(s.tilt_low_db),
        "tilt_high_db": lambda s: p.set_tilt_high(s.tilt_high_db),
        "eq_low_db": lambda s: p.set_eq("low", s.eq_low_db),
        "eq_mid_db": lambda s: p.set_eq("mid", s.eq_mid_db),
        "eq_high_db": lambda s: p.set_eq("high", s.eq_high_db),
        "test_volume": lambda s: p.set_test_volume(s.test_volume),
        "crossover_type": lambda s: p.set_crossover_type(s.crossover_type),
    }
    if name in builders:
        return builders[name](after)
    if name.startswith("delay"):
        index = int(name[5:])
        return p.channel_delay(index, after.delays[index])
    if name.startswith(("chhp", "chlp")):
        index = int(name[4:])
        low = name.startswith("chhp")
        filt = after.filters[index]
        hz = filt.high_pass_hz if low else filt.low_pass_hz
        return p.channel_filter(index, low, hz)
    return None


def make_handler(
    client: BiAmpClient,
    token: str | None = None,
    allowed_hosts: frozenset[str] | None = None,
):
    auth_token = token if token is not None else generate_token()
    hosts = frozenset(allowed_hosts) if allowed_hosts else LOOPBACK_NAMES

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "BiAmp"
        sys_version = ""
        timeout = SOCKET_TIMEOUT

        def log_message(self, fmt: str, *args) -> None:
            stamp = time.strftime("%Y-%m-%d %H:%M:%S")
            sys.stderr.write(f"[{stamp}] {self.address_string()} {fmt % args}\n")
            sys.stderr.flush()

        def log_error(self, fmt: str, *args) -> None:
            self.log_message(fmt, *args)

        # --- безопасность -------------------------------------------------

        def _host_allowed(self) -> bool:
            raw = self.headers.get("Host", "")
            if not raw:
                return False
            try:
                name = urlsplit("//" + raw).hostname
            except ValueError:
                return False
            if not name:
                return False
            return name.lower() in hosts

        def _origin_allowed(self) -> bool:
            origin = self.headers.get("Origin")
            if origin is None:
                return True
            if origin == "null":
                return False
            try:
                name = urlsplit(origin).hostname
            except ValueError:
                return False
            if not name:
                return False
            return name.lower() in hosts

        def _token_ok(self) -> bool:
            supplied = self.headers.get(TOKEN_HEADER, "")
            return secrets.compare_digest(supplied, auth_token)

        def _guard(self, needs_token: bool) -> None:
            if not self._host_allowed():
                audit(f"ОТКАЗ host={self.headers.get('Host')!r} path={self.path}")
                raise HttpError(403, "запрещённый Host")
            if not self._origin_allowed():
                audit(f"ОТКАЗ origin={self.headers.get('Origin')!r} path={self.path}")
                raise HttpError(403, "запрещённый Origin")
            if needs_token and not self._token_ok():
                audit(f"ОТКАЗ токен path={self.path} client={self.address_string()}")
                raise HttpError(403, "неверный или отсутствующий токен доступа")

        # --- тело запроса ------------------------------------------------

        def _read_body(self) -> bytes:
            raw = self.headers.get("Content-Length")
            try:
                length = int(raw) if raw is not None else 0
            except (TypeError, ValueError) as exc:
                raise HttpError(400, f"Content-Length {raw!r} не число") from exc
            if length < 0:
                raise HttpError(400, "отрицательный Content-Length")
            if length > MAX_BODY_BYTES:
                raise HttpError(413, f"тело {length} байт больше лимита {MAX_BODY_BYTES}")
            data = self.rfile.read(length) if length else b""
            if len(data) != length:
                raise HttpError(400, "тело запроса короче Content-Length")
            return data

        def _read_json(self) -> dict:
            content_type = self.headers.get("Content-Type", "")
            main = content_type.split(";", 1)[0].strip().lower()
            if main != "application/json":
                raise HttpError(415, f"Content-Type {main or 'отсутствует'!r}, ожидался application/json")
            raw = self._read_body()
            if not raw:
                raise HttpError(400, "пустое тело запроса")
            try:
                payload = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise HttpError(400, f"плохой JSON: {exc}") from exc
            if not isinstance(payload, dict):
                raise HttpError(400, "ожидался JSON-объект")
            return payload

        # --- ответы -------------------------------------------------------

        def _send(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            for name, value in SECURITY_HEADERS.items():
                self.send_header(name, value)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, code: int, payload: dict) -> None:
            self._send(
                code,
                json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                "application/json; charset=utf-8",
            )

        def _drain(self) -> None:
            """Считывает и выбрасывает непрочитанное тело запроса.

            Иначе клиент, который ещё пишет запрос, получает на сброс соединения
            вместо нашего ответа. Дренаж ограничен: тело длиннее DRAIN_LIMIT
            закрываем сразу, не тратя на него память и время.
            """
            raw = self.headers.get("Content-Length")
            try:
                length = int(raw)
            except (TypeError, ValueError):
                return
            if not (0 < length <= DRAIN_LIMIT):
                return
            try:
                self.connection.settimeout(2.0)
            except OSError:
                return
            remaining = length
            while remaining > 0:
                try:
                    chunk = self.rfile.read(min(remaining, 8192))
                except OSError:
                    return
                if not chunk:
                    return
                remaining -= len(chunk)

        def _fail(self, code: int, message: str) -> None:
            # Тело запроса могло остаться непрочитанным (403/415/413) — соединение
            # закрываем, иначе остатки попадут в следующий запрос по keep-alive.
            self.close_connection = True
            self._drain()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            body = json.dumps({"ok": False, "error": message}, ensure_ascii=False).encode("utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            for name, value in SECURITY_HEADERS.items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(body)

        def _static(self, name: str, inject_token: bool = False) -> None:
            root = STATIC.resolve()
            path = (root / name).resolve()
            if not path.is_relative_to(root) or not path.is_file():
                self._send(404, b"not found", "text/plain; charset=utf-8")
                return
            body = path.read_bytes()
            content_type = CONTENT_TYPES.get(path.suffix, "application/octet-stream")
            if inject_token:
                tag = f'<meta name="{TOKEN_META}" content="{auth_token}">'
                body = body.replace(b"<head>", b"<head>\n" + tag.encode("ascii"), 1)
            self._send(200, body, content_type)

        # --- маршруты ----------------------------------------------------

        def do_GET(self) -> None:
            route = self.path.split("?", 1)[0]
            try:
                if route in ("/", "/index.html"):
                    self._guard(needs_token=False)
                    self._static("index.html", inject_token=True)
                elif route in ("/style.css", "/app.js"):
                    self._guard(needs_token=False)
                    self._static(route.lstrip("/"))
                elif route == "/api/state":
                    self._guard(needs_token=True)
                    self._json(200, {"ok": True, "state": self._device_state()})
                else:
                    raise HttpError(404, "нет такого маршрута")
            except HttpError as exc:
                self._fail(exc.code, exc.message)
            except p.ProtocolError as exc:
                self._fail(400, str(exc))
            except TransportError as exc:
                self._fail(502, str(exc))
            except Exception as exc:
                audit(f"внутренняя ошибка GET {route}: {exc!r}")
                self._fail(500, f"внутренняя ошибка: {exc}")

        def do_HEAD(self) -> None:
            self.do_GET()

        def do_POST(self) -> None:
            route = self.path.split("?", 1)[0]
            try:
                self._guard(needs_token=True)
                payload = self._read_json()
                if route == "/api/param":
                    self._handle_param(payload)
                elif route == "/api/command":
                    self._handle_command(payload)
                elif route == "/api/apply":
                    self._handle_apply(payload)
                else:
                    raise HttpError(404, "нет такого маршрута")
            except HttpError as exc:
                self._fail(exc.code, exc.message)
            except p.ProtocolError as exc:
                self._fail(400, str(exc))
            except TransportError as exc:
                self._fail(502, str(exc))
            except Exception as exc:
                audit(f"внутренняя ошибка POST {route}: {exc!r}")
                self._fail(500, f"внутренняя ошибка: {exc}")

        # --- операции над устройством -------------------------------------

        @contextmanager
        def _device_lock(self):
            """Один клиент не должен вешать сервер: ждём недолго и отвечаем 503."""
            if not LOCK.acquire(timeout=LOCK_WAIT):
                raise HttpError(503, f"устройство занято дольше {LOCK_WAIT:g} с, запрос отклонён")
            try:
                yield
            finally:
                LOCK.release()

        def _device_state(self) -> dict:
            with self._device_lock():
                return client.read_status().to_dict()

        def _handle_param(self, payload: dict) -> None:
            name = payload.get("name")
            if not isinstance(name, str) or not name:
                raise p.ProtocolError("name: ожидается непустая строка")
            if "value" not in payload:
                raise p.ProtocolError("value: отсутствует")
            value = payload["value"]
            with self._device_lock():
                before = client.read_status()
                after = apply_field(before, name, value)
                command = command_for(name, before, after)
                if command:
                    client.send(command)
                state = client.read_status().to_dict()
            audit(f"param {name}={value!r} -> {command!r}")
            self._json(200, {"ok": True, "command": command, "state": state})

        def _handle_command(self, payload: dict) -> None:
            command = check_command(payload.get("command"))
            with self._device_lock():
                client.send(command)
                state = client.read_status().to_dict()
            audit(f"command {command!r}")
            self._json(200, {"ok": True, "command": command, "state": state})

        def _handle_apply(self, payload: dict) -> None:
            target = p.state_from_dict(payload.get("state"))
            save = p.to_bool(payload.get("save", False), "save")
            with self._device_lock():
                commands = client.apply_state(target, save=save)
                state = client.read_status().to_dict()
            audit(f"apply {len(commands)} команд, save={save}")
            self._json(200, {"ok": True, "applied": len(commands), "state": state})

    return Handler


def create_server(
    client: BiAmpClient,
    host: str = "127.0.0.1",
    port: int = 0,
    token: str | None = None,
) -> ThreadingHTTPServer:
    auth_token = token if token is not None else generate_token()
    allowed = set(LOOPBACK_NAMES)
    allowed.add(host.lower())
    server = ThreadingHTTPServer((host, port), make_handler(client, auth_token, frozenset(allowed)))
    server.token = auth_token  # type: ignore[attr-defined]
    server.allowed_hosts = frozenset(allowed)  # type: ignore[attr-defined]
    return server


def serve(client: BiAmpClient, host: str, port: int) -> None:
    server = create_server(client, host, port)
    token = server.token  # type: ignore[attr-defined]
    # server_address типизирован как str | bytes (для IPv6 — четвёрка), поэтому
    # берём готовые строковые server_name/server_port: без среза и без приведения.
    bound_host = server.server_name
    bound_port = server.server_port
    if bound_host not in ("127.0.0.1", "localhost", "::1"):
        print(
            f"ВНИМАНИЕ: сервер слушает {bound_host}, а не loopback. "
            "Панель управляет усилителем без аутентификации по сети — "
            "ограничьте доступ файрволом.",
            file=sys.stderr,
        )
    print(f"веб-интерфейс: http://{bound_host}:{bound_port}  (устройство: {client.description})")
    print(f"токен доступа (уже подставлен в страницу): {token}")
    print("Ctrl+C для остановки")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nостановлено")
    finally:
        server.server_close()
