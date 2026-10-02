from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from . import protocol as p
from .client import BiAmpClient
from .transport import TransportError, list_serial_ports

PROFILES_DIR = Path.home() / ".biamp" / "profiles"
_PORT_RE = re.compile(r"^(tcp://[A-Za-z0-9\.\-]+:\d+|[A-Za-z0-9_\-]+)$")
_NAME_RE = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")


def _print_state(state: p.DeviceState) -> None:
    print(f"Громкость   : зона0={state.vol0}%  зона1={state.vol1}%  баланс={state.balance:+g}")
    print(
        f"Кроссовер   : {state.crossover_hz:g} Гц  саб {state.sub_hp_hz:g} Гц "
        f"({'вкл' if state.sub_on else 'выкл'})  тип "
        f"{'LR4' if state.crossover_type == 2 else 'Butterworth'}"
    )
    print(f"Тримы       : НЧ {state.tilt_low_db:+g} дБ  ВЧ {state.tilt_high_db:+g} дБ")
    print(
        f"Эквалайзер  : 120 Гц {state.eq_low_db:+g}  1кГц {state.eq_mid_db:+g}  6кГц {state.eq_high_db:+g} дБ"
    )
    print(
        f"Mute        : зона0={'ON' if state.muted_z0 else 'OFF'}  зона1={'ON' if state.muted_z1 else 'OFF'}"
    )
    print(f"XO          : {'вкл' if state.xo_on else 'выкл'}")
    print(f"LR swap     : {'вкл' if state.lr_swap else 'выкл'}")
    print(f"DUP out     : {'вкл' if state.dup_out else 'выкл'}")
    print(f"Задержки    : {'/'.join(str(d) for d in state.delays)} отсч.")
    for index, filt in enumerate(state.filters):
        print(f"  канал {index} ({p.CHANNELS[index]:<10}) HP={filt.high_pass_hz:<6} LP={filt.low_pass_hz}")
    print(
        f"Связь       : BT {'ON' if state.bt_audio_on else 'OFF'}  "
        f"SPP {'ON' if state.spp_on else 'OFF'}  источник {state.source_khz} кГц"
    )
    print(f"Тест        : режим {state.test_mode}  громкость {state.test_volume}%")


def _profile_path(name: str) -> Path:
    if not _NAME_RE.match(name):
        raise TransportError(f"имя профиля {name!r} недопустимо: только буквы, цифры, «-» и «_», длина 1..64")
    return PROFILES_DIR / f"{name}.json"


def _validate_port(port: str) -> None:
    if not _PORT_RE.match(port):
        raise TransportError(f"формат порта {port!r} некорректен: ожидается COM-порт или tcp://хост:порт")


def _save_profile(name: str, state: p.DeviceState) -> None:
    PROFILES_DIR.mkdir(parents=True, exist_ok=True)
    _profile_path(name).write_text(
        json.dumps(state.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"профиль сохранён: {_profile_path(name)}")


def _load_profile(name: str) -> p.DeviceState:
    path = _profile_path(name)
    if not path.exists():
        raise TransportError(f"профиль не найден: {path}")
    return p.state_from_dict(json.loads(path.read_text(encoding="utf-8")))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="biamp", description="Управление усилителем ESP32 Bi-Amp с ПК")
    parser.add_argument("--port", default="COM14", help="COM-порт или tcp://хост:порт")
    sub = parser.add_subparsers(dest="action", required=True)

    sub.add_parser("ports", help="показать доступные COM-порты")

    status_cmd = sub.add_parser("status", help="прочитать текущие настройки")
    status_cmd.add_argument("--json", action="store_true", help="вывести JSON")

    set_cmd = sub.add_parser("set", help="изменить один параметр")
    set_cmd.add_argument("name")
    set_cmd.add_argument("value")

    raw_cmd = sub.add_parser("raw", help="отправить команду как есть")
    raw_cmd.add_argument("text")

    test_cmd = sub.add_parser("test", help="управление тест-сигналом")
    test_cmd.add_argument("mode")
    test_cmd.add_argument("--freq", type=int)
    test_cmd.add_argument("--volume", type=int)

    save_cmd = sub.add_parser("save", help="сохранить настройки в профиль")
    save_cmd.add_argument("name")

    load_cmd = sub.add_parser("load", help="загрузить профиль на усилитель")
    load_cmd.add_argument("name")
    load_cmd.add_argument("--nvs", action="store_true", help="записать в NVS")

    web_cmd = sub.add_parser("web", help="локальный веб-интерфейс")
    web_cmd.add_argument("--host", default="127.0.0.1")
    web_cmd.add_argument("--web-port", type=int, default=8765)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.action == "ports":
        found = list_serial_ports()
        if not found:
            print("COM-порты не найдены")
            return 1
        for port in found:
            print(port)
        return 0

    try:
        _validate_port(args.port)
    except TransportError as exc:
        print(f"ошибка: {exc}", file=sys.stderr)
        return 2

    try:
        with BiAmpClient.open(args.port) as client:
            print(f"[{client.description}]")
            if args.action == "status":
                state = client.read_status()
                print(json.dumps(state.to_dict(), ensure_ascii=False, indent=2) if args.json else "")
                if not args.json:
                    _print_state(state)
            elif args.action == "set":
                print(f"> {client.set_value(args.name, args.value)}")
            elif args.action == "raw":
                for line in client.query(args.text):
                    print(line)
            elif args.action == "test":
                client.send(p.set_test_mode(args.mode))
                if args.freq is not None:
                    client.send(p.set_test_freq(args.freq))
                if args.volume is not None:
                    client.send(p.set_test_volume(args.volume))
            elif args.action == "save":
                _save_profile(args.name, client.read_status())
            elif args.action == "load":
                state = _load_profile(args.name)
                applied = client.apply_state(state, save=args.nvs)
                print(f"применено команд: {len(applied)}")
                _print_state(client.read_status())
            elif args.action == "web":
                from .webapp import serve

                serve(client, args.host, args.web_port)
        return 0
    except (p.ProtocolError, TransportError) as exc:
        print(f"ошибка: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
