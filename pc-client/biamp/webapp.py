from __future__ import annotations

import dataclasses
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import protocol as p
from .client import BiAmpClient
from .transport import TransportError

STATIC = Path(__file__).parent / "static"
LOCK = threading.Lock()

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
}

MUTABLE = {
    "vol0": lambda s, v: dataclasses.replace(s, vol0=p.check_int(int(v), "vol0", 0, 100)),
    "vol1": lambda s, v: dataclasses.replace(s, vol1=p.check_int(int(v), "vol1", 0, 100)),
    "balance": lambda s, v: dataclasses.replace(s, balance=p.check_number(float(v), "balance", -10, 10)),
    "crossover_hz": lambda s, v: dataclasses.replace(s, crossover_hz=p.check_number(float(v), "fc", 200, 1000)),
    "sub_hp_hz": lambda s, v: dataclasses.replace(s, sub_hp_hz=p.check_number(float(v), "hp", 20, 80)),
    "sub_on": lambda s, v: dataclasses.replace(s, sub_on=bool(v)),
    "tilt_low_db": lambda s, v: dataclasses.replace(s, tilt_low_db=p.check_number(float(v), "tlf", -6, 3)),
    "tilt_high_db": lambda s, v: dataclasses.replace(s, tilt_high_db=p.check_number(float(v), "thf", -6, 3)),
    "eq_low_db": lambda s, v: dataclasses.replace(s, eq_low_db=p.check_number(float(v), "eql", -12, 12)),
    "eq_mid_db": lambda s, v: dataclasses.replace(s, eq_mid_db=p.check_number(float(v), "eqm", -12, 12)),
    "eq_high_db": lambda s, v: dataclasses.replace(s, eq_high_db=p.check_number(float(v), "eqh", -12, 12)),
    "test_volume": lambda s, v: dataclasses.replace(s, test_volume=p.check_int(int(v), "tvol", 0, 100)),
    "crossover_type": lambda s, v: dataclasses.replace(s, crossover_type=p.check_int(int(v), "xotype", 1, 2)),
}


def apply_field(state: p.DeviceState, name: str, value) -> p.DeviceState:
    if name in MUTABLE:
        return MUTABLE[name](state, value)
    if name.startswith("delay"):
        index = p.check_int(int(name[5:]), "канал", 0, p.CHANNEL_COUNT - 1)
        delays = list(state.delays)
        delays[index] = p.check_int(int(value), "задержка", 0, p.MAX_DELAY_SAMPLES)
        return dataclasses.replace(state, delays=tuple(delays))
    if name.startswith("chhp") or name.startswith("chlp"):
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
    if name.startswith("inv"):
        index = p.check_int(int(name[3:]), "канал", 0, p.CHANNEL_COUNT - 1)
        inverted = list(state.inverted)
        inverted[index] = bool(value)
        return dataclasses.replace(state, inverted=tuple(inverted))
    raise p.ProtocolError(f"параметр {name!r} не поддерживается")


def command_for(name: str, before: p.DeviceState, after: p.DeviceState) -> str | None:
    builders = {
        "vol0": lambda s: p.set_zone_volume(0, s.vol0),
        "vol1": lambda s: p.set_zone_volume(1, s.vol1),
        "balance": lambda s: p.set_balance(s.balance),
        "crossover_hz": lambda s: p.set_crossover(s.crossover_hz),
        "sub_hp_hz": lambda s: p.set_sub_hp(round(s.sub_hp_hz)),
        "sub_on": lambda s: p.set_sub_enabled(s.sub_on),
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
    if name.startswith("chhp") or name.startswith("chlp"):
        index = int(name[4:])
        low = name.startswith("chhp")
        filt = after.filters[index]
        hz = filt.high_pass_hz if low else filt.low_pass_hz
        return p.channel_filter(index, low, hz)
    if name.startswith("inv"):
        return p.set_inversion(int(name[3:]))
    return None


def make_handler(client: BiAmpClient):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args) -> None:
            return

        def _send(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, code: int, payload: dict) -> None:
            self._send(
                code, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8"
            )

        def _static(self, name: str) -> None:
            path = (STATIC / name).resolve()
            if not str(path).startswith(str(STATIC.resolve())) or not path.exists():
                self._send(404, b"not found", "text/plain; charset=utf-8")
                return
            self._send(
                200, path.read_bytes(), CONTENT_TYPES.get(path.suffix, "application/octet-stream")
            )

        def do_GET(self) -> None:
            route = self.path.split("?")[0]
            if route in ("/", "/index.html"):
                self._static("index.html")
            elif route == "/style.css":
                self._static("style.css")
            elif route == "/app.js":
                self._static("app.js")
            elif route == "/api/state":
                try:
                    with LOCK:
                        state = client.read_status()
                    self._json(200, {"ok": True, "state": state.to_dict()})
                except (TransportError, p.ProtocolError) as exc:
                    self._json(502, {"ok": False, "error": str(exc)})
            else:
                self._send(404, b"not found", "text/plain; charset=utf-8")

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", 0))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._json(400, {"ok": False, "error": "плохой JSON"})
                return
            route = self.path.split("?")[0]
            try:
                if route == "/api/param":
                    with LOCK:
                        before = client.read_status()
                        after = apply_field(before, payload["name"], payload["value"])
                        command = command_for(payload["name"], before, after)
                        if command:
                            client.send(command)
                        state = client.read_status()
                    self._json(200, {"ok": True, "command": command, "state": state.to_dict()})
                elif route == "/api/command":
                    with LOCK:
                        client.send(str(payload["command"]))
                        state = client.read_status()
                    self._json(200, {"ok": True, "state": state.to_dict()})
                elif route == "/api/apply":
                    with LOCK:
                        target = _state_from_payload(payload["state"])
                        commands = client.apply_state(target, save=bool(payload.get("save")))
                        state = client.read_status()
                    self._json(200, {"ok": True, "applied": len(commands), "state": state.to_dict()})
                else:
                    self._json(404, {"ok": False, "error": "нет такого маршрута"})
            except (TransportError, p.ProtocolError, KeyError) as exc:
                self._json(400, {"ok": False, "error": str(exc)})

    return Handler


def _state_from_payload(data: dict) -> p.DeviceState:
    from .cli import _state_from_dict

    return _state_from_dict(data)


def serve(client: BiAmpClient, host: str, port: int) -> None:
    server = ThreadingHTTPServer((host, port), make_handler(client))
    print(f"веб-интерфейс: http://{host}:{port}  (устройство: {client.description})")
    print("Ctrl+C для остановки")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nостановлено")
    finally:
        server.server_close()

