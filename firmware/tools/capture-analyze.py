#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Приём и разбор захвата выхода ESP32 BiAmp (Master) со сниффера ESP32-S3.

Снимает 4 канала Master (Z1 НФ/ВЧ, Z2 НФ/ВЧ), пишет 4-канальный WAV и считает
то, ради чего захват и затевался:

  * уровни, пики, смещение и шумовая полка каждого канала;
  * задержка между каналами в отсчётах (через взаимную корреляцию);
  * THD+N и реальная частота тона по опорному тону — от неё пересчитывается
    настоящая частота дискретизации, а не объявленная платой;
  * АЧХ каждого выхода по логарифмической развёртке: точка кроссовера,
    затухание вне полосы, провалы;
  * побайтовое совпадение каналов в режиме dup:1 — эталон согласованности
    двух усилителей;
  * щелчки по разрывам между соседними отсчётами.

Примеры:

    py capture-analyze.py --sniffer COM13 --master COM14 --scenario sweep
    py capture-analyze.py --sniffer COM13 --master COM14 --scenario dup
    py capture-analyze.py --analyze captures/dup/dup.wav
    py capture-analyze.py --list-scenarios

Зависимости: pyserial, numpy.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import math
import re
import struct
import sys
import wave
from pathlib import Path

import numpy as np

# ── формат потока сниффера (зеркалит firmware/capture/main/capture_proto.h) ──
CAP_MAGIC = 0x314D4142
CAP_MAGIC_END = 0x31444E45
CAP_HEADER_BYTES = 32
CAP_TRAILER_BYTES = 32
CHANNELS = 4
FRAME_BYTES = CHANNELS * 2

# Задержка, которую сценарий `delay` задаёт на канале Z2 НФ. Одно и то же
# число живёт в командах сценария и в проверке: иначе ожидание и запрос могут
# разойтись незаметно.
DELAY_SET_SAMPLES = 64

CH_NAMES = ("Z1 НЧ", "Z1 ВЧ", "Z2 НЧ", "Z2 ВЧ")

# Полная шкала отсчёта int16. Все уровни в отчёте — относительно неё.
FULL_SCALE = 32768.0

# Порог разрыва между соседними отсчётами, за которым считаем щелчок.
CLICK_JUMP = 0.35 * FULL_SCALE

# Чистый захват не содержит щелчков вовсе: тон и развёртка на уровне теста
# (tvol:4) дают разрыв соседних отсчётов не выше ~4000 отсчётов, а порог щелчка
# — 11469. Поэтому чистота = ноль щелчков, и порог не нужен.
CLICK_GATE = 0

# Сколько раз перезаписать захват, если в нём нашёлся брак пути измерения.
# Брак интермиттирующий (~1 захват из двух), поэтому три попытки дают ~15 %
# шанса, что все три грязные; восемь — менее одного процента.
MAX_CAPTURE_ATTEMPTS = 8

# Закон развёртки в прошивке Master: freq = 20 * 1000^(t/10), период 10 с.
SWEEP_LO = 20.0
SWEEP_HI = 20000.0
SWEEP_PERIOD = 10.0


# ── сценарии ────────────────────────────────────────────────────────────
# cmds — команды Master, wait — пауза перед записью, rec — длительность.
#
# Во всех сценариях тон задаётся через `test:all`, а не `test:1`. `test:1`
# (режим 6) кормит только канал 1 — это первая НФ-ветка Z1, — поэтому Z2 и обе
# ВЧ-ветки молчат ровно в четыре раза, и половина отчёта получается пустой.
# `test:all` (режим 1) даёт сигнал всем четырём каналам сразу.
SCENARIOS: dict[str, dict] = {
    "dup": {
        "cmds": ["dup:1", "tvol:4", "tf:1000", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "оба I²S-порта получают один блок — калибровка выравнивания зон и порядка слотов",
    },
    "silence": {
        "cmds": ["dup:0", "test:off"],
        "wait": 2.0, "rec": 6.0, "tone": None,
        "note": "шумовая полка, смещение и дрейф каждого канала",
    },
    "zones": {
        "cmds": ["dup:0", "delay0:0", "delay1:0", "delay2:0", "delay3:0",
                 "tvol:4", "tf:100", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 100.0,
        "note": "все задержки нулевые, тон 100 Гц: чистое смещение между зонами — "
                "это база, от которой отсчитывается проверка задержки",
    },
    "tone-low": {
        "cmds": ["dup:0", "tvol:4", "tf:100", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 100.0,
        "note": "тон ниже кроссовера: НФ-ветка обоих усилителей",
    },
    "tone-high": {
        "cmds": ["dup:0", "tvol:4", "tf:5000", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 5000.0,
        "note": "тон выше кроссовера: ВЧ-ветка обоих усилителей",
    },
    "anti": {
        "cmds": ["dup:0", "tvol:4", "tf:1000", "test:anti"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "инвертированная фаза: проверка синхронности кадров всех четырёх каналов",
    },
    "sweep": {
        "cmds": ["dup:0", "tvol:4", "test:sweep"],
        "wait": 1.0, "rec": 11.0, "tone": None,
        "note": "АЧХ четырёх выходов: точка кроссовера и затухание вне полосы",
    },
    "delay": {
        "cmds": ["dup:0", "delay0:0", "delay1:0", f"delay2:{DELAY_SET_SAMPLES}", "delay3:0",
                 "tvol:4", "tf:100", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 100.0,
        "note": "задержка 64 отсчёта на канале Z2 НФ (Z2 ВЧ без задержки): "
                "разность задержек между каналами внутри одной зоны не зависит "
                "от сдвига между зонами, тон 100 Гц — иначе сдвиг 64 отсчётов "
                "неотличим от 20 (период 1 кГц равен 44.1 отсчёта)",
    },
    "clip": {
        "cmds": ["dup:0", "tvol:6", "tf:1000", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "максимально разрешённый уровень теста: ищем клиппинг",
    },
    "crosstalk": {
        "cmds": ["dup:0", "tvol:4", "tf:1000", "test:1"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "тон только на Z1 НФ (test:1): измеряем утечку на остальные каналы",
    },
    "level-sweep": {
        "cmds": ["dup:0", "tf:1000", "test:all"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "автоматический перебор tvol:1..6: пик, RMS и клиппинг на каждом уровне",
        "levels": [1, 2, 3, 4, 5, 6],
    },
}


# ── приём со сниффера ───────────────────────────────────────────────────
def open_port(port: str, baud: int, timeout: float):
    try:
        import serial  # type: ignore
    except ImportError as exc:  # pragma: no cover - зависит от окружения
        raise SystemExit("Нет pyserial: py -m pip install pyserial numpy") from exc
    return serial.Serial(port, baud, timeout=timeout)


# Порог простоя: при потоке 352 КБ/с это 30 с молчания — заведомый обрыв.
IDLE_TIMEOUT_S = 30.0

# Окно, в котором ищется маркер в конце накопленного буфера. Маркер стоит в
# начале ASCII-строки END, а не в последних байтах: строка «END frames=… errors=0»
# занимает ещё около 45 байт после него. Окно должно быть длиннее этой строки.
TAIL_WINDOW = 128


def read_until_marker(ser, marker: bytes = b"END1END", idle_timeout: float = IDLE_TIMEOUT_S,
                     initial: bytes = b"") -> bytes:
    """Читает поток до завершающей строки END. Таймаут по простою, а не по общему времени.

    Маркер — семь байт: магия трейлера `END1` и сразу за ней ASCII-строка `END`.
    Одной магии мало: кадры — двоичные данные, и в ней она встречается случайно.
    Перевод строки перед `END` искать нельзя: трейлер заканчивается магией
    вплотную к строке, разделителя там нет. `initial` — уже прочитанный хвост
    после `GO`, он проверяется на маркер по общей с ним строке.
    """
    import time

    buf = bytearray(initial)
    if marker in buf[-TAIL_WINDOW:]:
        return bytes(buf)
    deadline = time.monotonic() + idle_timeout
    while True:
        chunk = ser.read(65536)
        if chunk:
            buf += chunk
            if marker in buf[-TAIL_WINDOW:]:
                return bytes(buf)
            deadline = time.monotonic() + idle_timeout
            continue
        if time.monotonic() > deadline:
            raise SystemExit("Сниффер перестал слать данные: прерван по таймауту простоя")


def sniffer_command(ser, cmd: str, expect: bytes | None = None, retries: int = 25) -> bytes:
    import time

    for _ in range(retries):
        ser.reset_input_buffer()
        ser.write(cmd.encode("ascii") + b"\n")
        ser.flush()
        end = time.monotonic() + 0.4
        buf = bytearray()
        while time.monotonic() < end:
            chunk = ser.read(4096)
            if chunk:
                buf += chunk
                if b"\n" in buf:
                    break
        if buf:
            if expect is None or bytes(buf).strip().startswith(expect):
                return bytes(buf)
    raise SystemExit(f"Сниффер не ответил на {cmd!r}")


def parse_header(blob: bytes) -> dict:
    if len(blob) < CAP_HEADER_BYTES:
        raise SystemExit("Поток короче заголовка")
    fields = struct.unpack_from("<IHHHHIIIII", blob, 0)
    hdr = {
        "magic": fields[0], "version": fields[1], "channels": fields[2], "bits": fields[3],
        "slot_bits": fields[4], "declared_rate": fields[5], "frame_bytes": fields[6],
        "zones": fields[7], "flags": fields[8],
    }
    if hdr["magic"] != CAP_MAGIC:
        raise SystemExit(f"Плохая магия заголовка: 0x{hdr['magic']:08X}")
    if hdr["channels"] != CHANNELS or hdr["bits"] != 16:
        raise SystemExit(f"Неожиданный формат: {hdr['channels']} каналов по {hdr['bits']} бит")
    return hdr


def parse_trailer(blob: bytes) -> dict:
    fields = struct.unpack_from("<IIIIII", blob, 0)
    end_magic = struct.unpack_from("<I", blob, 28)[0]
    if end_magic != CAP_MAGIC_END:
        raise SystemExit(f"Плохая магия трейлера: 0x{end_magic:08X}")
    return {
        "frames": fields[0], "dropped_z1": fields[1], "dropped_z2": fields[2],
        "max_skew_frames": fields[3], "errors": fields[4], "elapsed_ms": fields[5],
    }


def master_command(ser, cmd: str, timeout: float = 1.0) -> str:
    """Шлёт команду Master и возвращает её ответ.

    Ответ нужен не для красоты: у `tvol:`, `test:` и `status` он есть, и по
    нему видно, что команда действительно принята. Молча отправленная команда
    выглядит на ПК так же, как сработавшая, а отчёт потом объясняет тишину
    чем угодно, только не тем, что тест не включился.
    """
    import time

    ser.reset_input_buffer()
    ser.write(cmd.encode("ascii") + b"\n")
    ser.flush()
    deadline = time.monotonic() + timeout
    buf = bytearray()
    while time.monotonic() < deadline:
        chunk = ser.read(4096)
        if chunk:
            buf += chunk
            deadline = max(deadline, time.monotonic() + 0.25)
    return bytes(buf).decode(errors="replace").strip()


def wait_master_ready(ser, timeout: float = 12.0) -> str:
    """Дожидается, пока Master поднимется и начнёт отвечать.

    Открытие COM-порта дёргает DTR/RTS, а у ESP32 это аппаратный сброс: первые
    секунды порт молчит. Команды, отправленные сразу после открытия, уходят в
    пустоту, сценарий отрабатывает вхолостую, и тишина в отчёте выглядит как
    результат измерения. Признак готовности — строка `RingDrops` в ответе на
    `stats`: она печатается только после инициализации тракта.
    """
    import time

    deadline = time.monotonic() + timeout
    seen = ""
    while time.monotonic() < deadline:
        seen = master_command(ser, "stats", timeout=1.0)
        if "RingDrops" in seen:
            return seen
        time.sleep(0.3)
    raise SystemExit(
        "Master не отвечает на stats — проверьте питание и порт. "
        f"Последний ответ: {seen!r}")


def receive(ser, seconds: float, master_port=None, cmds: list[str] | None = None,
            wait: float = 0.0) -> tuple[np.ndarray, dict, dict, bytes]:
    import time

    if master_port is not None:
        for cmd in cmds or []:
            reply = master_command(master_port, cmd, timeout=1.0)
            # Троттлинг 150 мс обязателен для SPP; по USB-CDC он не нужен, но
            # пауза оставлена — иначе соседние команды склеиваются в один пакет.
            if reply:
                print(f"Master {cmd} -> {reply.splitlines()[-1]}")
            else:
                print(f"Master {cmd} -> без ответа")
            time.sleep(0.15)
    if wait > 0:
        time.sleep(wait)

    ser.reset_input_buffer()
    ser.write(f"START {seconds:g}\n".encode("ascii"))
    ser.flush()

    # GO и первые кадры приходят одним куском, поэтому строка отделяется от
    # хвоста, а не вычитывается отдельным вызовом: иначе теряется начало записи.
    # Дальше идёт тело — заголовок 32 Б, кадры, трейлер 32 Б и ASCII-строка END.
    deadline = time.monotonic() + 2.0
    head = bytearray()
    while b"\n" not in head:
        if time.monotonic() > deadline:
            raise SystemExit("Сниффер не ответил на START")
        chunk = ser.read(4096)
        if chunk:
            head += chunk
    line, _, rest = bytes(head).partition(b"\n")
    if line.strip() != b"GO":
        raise SystemExit(f"Сниффер отклонил START: {line!r}")

    blob = read_until_marker(ser, initial=rest)
    # Якорь — семь байт: магия трейлера `END1` и сразу за ней ASCII `END`. Поиск
    # только магии даёт ложные срабатывания в двоичных кадрах, а перевода строки
    # перед `END` на проводе нет. Трейлер — это 32 байта ВЫПЕРЕД от якоря:
    # магия стоит в его последнем слове, поэтому в `body` попадают целиком.
    end_idx = blob.rfind(b"END1END")
    if end_idx < CAP_TRAILER_BYTES - 4:
        raise SystemExit("В потоке нет завершающей строки END")
    body = blob[:end_idx + 4]
    if len(body) < CAP_HEADER_BYTES + CAP_TRAILER_BYTES:
        raise SystemExit("Поток короче заголовка с трейлером")

    hdr = parse_header(body[:CAP_HEADER_BYTES])
    trailer = parse_trailer(body[-CAP_TRAILER_BYTES:])
    payload = body[CAP_HEADER_BYTES:len(body) - CAP_TRAILER_BYTES]
    frames = len(payload) // hdr["frame_bytes"]
    if hdr["frame_bytes"] != FRAME_BYTES:
        raise SystemExit(f"Неожиданный размер кадра: {hdr['frame_bytes']} байт")
    data = np.frombuffer(payload[:frames * hdr["frame_bytes"]], dtype="<i2").reshape(-1, CHANNELS)
    # Без этой проверки запись с молча пропавшими байтами выглядит как обычный
    # сигнал: сдвиг на 2 байта — это ровно половина кадра, и четыре канала после
    # него читаются как попарно перепутанные. Считать такое нельзя.
    if frames != trailer["frames"]:
        raise SystemExit(
            f"Запись битая: плата отправила {trailer['frames']} кадров, "
            f"хост принял {frames}. Повторить захват.")
    if len(payload) % FRAME_BYTES:
        raise SystemExit(
            f"Запись битая: в полезной части {len(payload)} байт, "
            f"кратно {FRAME_BYTES} только {frames * FRAME_BYTES}. Повторить захват.")
    # Возвращается тело потока (заголовок + кадры + трейлер), а не весь блоб с
    # строками GO/END: в этом виде файл читается обратно через load_raw без
    # вычитания ASCII-обвязки.
    return data.astype(np.int32), hdr, trailer, body


# ── расчёты ─────────────────────────────────────────────────────────────
def db(x: float) -> float:
    return 20.0 * math.log10(max(x, 1e-12))


def channel_stats(x: np.ndarray) -> dict:
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    rms = float(np.sqrt(np.mean(x.astype(np.float64) ** 2))) if x.size else 0.0
    mean = float(np.mean(x)) if x.size else 0.0
    return {
        "peak": peak,
        "peak_dbfs": db(peak / FULL_SCALE),
        "rms": rms,
        "rms_dbfs": db(rms / FULL_SCALE),
        "dc": mean,
        "crest_db": db(peak / rms) if rms > 0 else 0.0,
    }


def noise_floor(x: np.ndarray) -> dict:
    core = x[len(x) // 10: -len(x) // 10] if len(x) > 40 else x
    if core.size == 0:
        return {"noise_dbfs": -240.0, "noise_rms": 0.0}
    rms = float(np.sqrt(np.mean(core.astype(np.float64) ** 2)))
    return {"noise_dbfs": db(rms / FULL_SCALE), "noise_rms": rms}


def xcorr_lag(ref: np.ndarray, sig: np.ndarray, max_lag: int) -> dict:
    a = ref.astype(np.float64) - float(np.mean(ref))
    b = sig.astype(np.float64) - float(np.mean(sig))
    total = len(a) + len(b)
    if total <= 0:
        return {"lag": 0, "quality": 0.0}
    n = 1 << int(math.ceil(math.log2(total)))
    corr = np.fft.irfft(np.fft.rfft(a, n) * np.conj(np.fft.rfft(b, n)), n)
    corr = np.concatenate((corr[-max_lag:], corr[:max_lag + 1]))
    k = int(np.argmax(np.abs(corr)))
    norm = float(np.linalg.norm(a) * np.linalg.norm(b))
    return {
        "lag": k - max_lag,
        "quality": float(np.abs(corr[k]) / norm) if norm > 0 else 0.0,
    }


def peak_near(mag: np.ndarray, freqs: np.ndarray, f0: float, span: float) -> tuple[float, float]:
    """Максимум в окне вокруг f0 с параболической интерполяцией по логу."""
    k = int(round(f0 / (freqs[1] - freqs[0])))
    half = max(4, int(span / (freqs[1] - freqs[0])))
    lo = max(1, k - half)
    hi = min(len(mag) - 2, k + half)
    idx = lo + int(np.argmax(mag[lo:hi]))
    y0, y1, y2 = mag[idx - 1], mag[idx], mag[idx + 1]
    denom = (y0 - 2 * y1 + y2)
    shift = 0.5 * (y0 - y2) / denom if denom != 0 else 0.0
    df = freqs[1] - freqs[0]
    amp = float(y1 - 0.25 * (y0 - y2) * shift)
    return float(freqs[idx] + shift * df), amp


def tone_metrics(x: np.ndarray, rate: float, f0: float) -> dict:
    """THD+N и фактическая частота тона по одной гармонике.

    Частота в файле объявлена платой, а не измерена. Опорный тон известной
    частоты даёт настоящую: Fs = f0 * rate / f_измеренная. На этом же строится
    весь остальной анализ, поэтому проверка делается первой.
    """
    if x.size < 1024:
        return {"error": "файл слишком короткий для тонкого анализа"}
    win = np.hanning(len(x))
    spec = np.abs(np.fft.rfft((x.astype(np.float64) - float(np.mean(x))) * win)) * 2.0 / float(win.sum())
    freqs = np.fft.rfftfreq(len(x), 1.0 / rate)
    df = float(freqs[1] - freqs[0])
    span = max(40.0, f0 * 0.05)

    f_meas, h1 = peak_near(spec, freqs, f0, span)
    if h1 <= 0:
        return {"error": "опорный тон не найден"}
    rate_true = f0 * rate / f_meas if f_meas > 0 else rate

    harmonics: dict[str, float] = {}
    hsum = 0.0
    m = 2
    while m * f0 < freqs[-1] and m <= 10:
        _, hm = peak_near(spec, freqs, m * f0, span * m)
        harmonics[f"h{m}"] = hm
        hsum += hm * hm
        m += 1
    thd_db = 10.0 * math.log10(max(hsum, 1e-24) / (h1 * h1))

    # Остаток спектра минус основная гармоника — это шум и искажения вместе.
    k0 = int(round(f_meas / df))
    khalf = max(3, int(span / df))
    mask = np.ones_like(spec, dtype=bool)
    mask[max(0, k0 - khalf):k0 + khalf + 1] = False
    mask[0] = False  # постоянная составляющая в счёт шума не идёт
    residual = float(np.sqrt(np.sum(spec[mask] ** 2)))
    sinad_db = 10.0 * math.log10(max(h1 * h1, 1e-24) / max(residual * residual, 1e-24))

    return {
        "f_declared": f0,
        "f_measured": f_meas,
        "rate_declared": rate,
        "rate_true": rate_true,
        "fundamental_dbfs": db(h1 / FULL_SCALE),
        "harmonics_dbfs": {k: db(v / FULL_SCALE) for k, v in harmonics.items()},
        "thd_db": thd_db,
        "sinad_db": sinad_db,
    }


def sweep_response(x: np.ndarray, rate: float) -> dict:
    """АЧХ одной ветки по логарифмической развёртке.

    Две тонкости, из-за которых наивный «пик в окне» врёт на десятки герц и
    на десятки дБ:

    1. Частота. За окно 4096 отсчётов тон успевает пройти 20 Гц → 21 Гц, а
       8 кГц → 8.2 кГц, поэтому ось берётся из закона развёртки прошивки
       (20 Гц → 20 кГц за 10 с), а найденный пик используется только как
       проверка оси.
    2. Амплитуда. Тон, ползущий по окну, энергию не теряет, но раскидывает её
       по десяткам бинов — пик одного бина оказывается на 7…11 дБ ниже
       истинного. Поэтому берётся суммарная энергия по всей полосе, которую тон
       прошёл за окно, и пересчитывается по Парсевалю.
    """
    win = 4096
    hop = 2048
    if len(x) < win:
        return {"error": "файл короче окна анализа"}
    window = np.hanning(win)
    sum_w2 = float(np.sum(window ** 2))
    freqs = np.fft.rfftfreq(win, 1.0 / rate)
    lo_bin = int(np.searchsorted(freqs, 10.0))
    hi_bin = int(np.searchsorted(freqs, min(20000.0, freqs[-1])))
    nominal: list[float] = []
    measured: list[float] = []
    mags: list[float] = []
    for start in range(0, len(x) - win + 1, hop):
        seg = (x[start:start + win].astype(np.float64) - float(np.mean(x[start:start + win]))) * window
        power = np.abs(np.fft.rfft(seg)) ** 2
        t0 = start / rate
        t1 = (start + win) / rate
        f0 = SWEEP_LO * (SWEEP_HI / SWEEP_LO) ** ((t0 % SWEEP_PERIOD) / SWEEP_PERIOD)
        f1 = SWEEP_LO * (SWEEP_HI / SWEEP_LO) ** ((t1 % SWEEP_PERIOD) / SWEEP_PERIOD)
        k0 = max(lo_bin, int(np.searchsorted(freqs, f0 * 0.97)))
        k1 = min(len(power), int(np.searchsorted(freqs, f1 * 1.03)) + 1)
        if k1 <= k0:
            continue
        band_power = float(np.sum(power[k0:k1]))
        # Множитель 4, а не 2: rfft отдаёт только положительные частоты, а
        # Парсеваль суммирует обе половины спектра, поэтому энергия вдвое
        # меньше той, что стоит в формуле. Без этой поправки все уровни
        # занижены на 3 дБ.
        amp = math.sqrt(4.0 * band_power / (win * sum_w2))
        nominal.append(math.sqrt(f0 * f1))
        mags.append(amp / FULL_SCALE)

        if hi_bin > lo_bin:
            idx = lo_bin + int(np.argmax(power[lo_bin:hi_bin]))
            y0, y1, y2 = power[idx - 1], power[idx], power[idx + 1]
            denom = (y0 - 2 * y1 + y2)
            shift = 0.5 * (y0 - y2) / denom if denom != 0 else 0.0
            df = float(freqs[1] - freqs[0])
            measured.append(float(freqs[idx] + shift * df))
        else:
            measured.append(nominal[-1])
    return {"freq": np.array(nominal), "measured": np.array(measured),
            "mag": np.array(mags)}


def branch_crossover(freq: np.ndarray, mag: np.ndarray, low: bool) -> dict:
    """Точка кроссовера ветки: где её отклик падает на 3 дБ от полосы.

    Кадры, где ветка молчит (за пределами её полосы отклик уходит под шум), для
    расчёта не годятся: пик в шуме выбирается случайно и даёт правдоподобную
    частоту с нулевой амплитудой. Поэтому сначала отсекаются кадры тише, чем
    на 60 дБ под максимумом ветки, и только потом ищутся полоса и кроссовер.
    """
    if freq.size < 10:
        return {}
    # Кадры идут по времени, а развёртка начинается заново каждый период, так
    # что массив частот не отсортирован. Искать «последний подходящий» в таком
    # массиве бессмысленно — сначала порядок по частоте.
    order = np.argsort(freq)
    freq = freq[order]
    level = np.array([db(max(m, 1e-12)) for m in mag])[order]
    active = level > (float(np.max(level)) - 60.0)
    if not np.any(active):
        return {"error": "ветка молчит во всём замере"}

    band = (freq >= 40.0) & (freq <= 150.0) if low else (freq >= 4000.0) & (freq <= 12000.0)
    if not np.any(band & active):
        band = active
    passband = float(np.median(level[band & active]))

    cross: float | None = None
    # Для НЧ ветки кроссовер — верхняя граница, где она ещё в полосе; для ВЧ —
    # нижняя. Нижний край спектра у ВЧ ветки не рассматривается: там её отклик
    # ещё не поднялся, и первый «провал» в шуме выглядел бы как кроссовер.
    if low:
        ok = np.where(active & (level >= passband - 3.0))[0]
    else:
        ok = np.where(active & (freq >= 100.0) & (level >= passband - 3.0))[0]
    if ok.size:
        cross = float(freq[ok[-1] if low else ok[0]])

    if low:
        stop_mask = active & (freq <= 30.0)
    else:
        stop_mask = active & (freq >= 18000.0)
    stop = float(np.median(level[stop_mask])) if np.any(stop_mask) else None

    inband = level[band & active]
    ripple = float(np.percentile(inband, 95) - np.percentile(inband, 5))
    return {"passband_dbfs": passband, "crossover_hz": cross, "ripple_db": ripple,
            "stopband_dbfs": stop, "active_frames": int(np.count_nonzero(active))}


def click_scan(x: np.ndarray, rate: float) -> dict:
    if len(x) < 2:
        return {"count": 0, "positions_ms": []}
    d = np.abs(np.diff(x.astype(np.int32), axis=0))
    hits = np.where(d > CLICK_JUMP)[0]
    pos = [round(float(i) * 1000.0 / rate, 3) for i in hits[:8]]
    return {"count": int(hits.size), "positions_ms": pos,
            "max_jump": int(d.max()) if d.size else 0}


def bit_compare(data: np.ndarray) -> dict:
    """Побайтовое совпадение пар каналов — проверка режима dup:1."""
    pairs = ((0, 2, "Z1 НФ против Z2 НФ"), (1, 3, "Z1 ВЧ против Z2 ВЧ"))
    out = []
    for a, b, name in pairs:
        diff = int(np.count_nonzero(data[:, a] != data[:, b]))
        out.append({"pair": name, "diff_samples": diff,
                    "identical": diff == 0,
                    "pct": 100.0 * diff / max(len(data), 1)})
    return {"pairs": out}


def crosstalk_analysis(data: np.ndarray, rate: float, ref_channel: int = 0) -> dict:
    """Измеряет утечку сигнала с опорного канала на остальные.
    
    В режиме test:1 сигнал подаётся только на Z1 НФ (канал 0).
    Утелка на другие каналы — это перекрестные помехи между зонами/ветками.
    """
    ref = data[:, ref_channel]
    ref_rms = float(np.sqrt(np.mean(ref.astype(np.float64) ** 2)))
    ref_peak = float(np.max(np.abs(ref.astype(np.float64))))
    out = {
        "ref_channel": CH_NAMES[ref_channel],
        "ref_rms_dbfs": db(ref_rms),
        "ref_peak_dbfs": db(ref_peak),
        "channels": [],
    }
    for i in range(data.shape[1]):
        if i == ref_channel:
            continue
        ch = data[:, i]
        ch_rms = float(np.sqrt(np.mean(ch.astype(np.float64) ** 2)))
        ch_peak = float(np.max(np.abs(ch.astype(np.float64))))
        if ref_rms > 1e-9 and ch_rms > 1e-9:
            isolation_db = db(ref_rms) - db(ch_rms)
        elif ref_rms > 1e-9:
            isolation_db = 120.0
        else:
            isolation_db = 0.0
        out["channels"].append({
            "channel": CH_NAMES[i],
            "rms_dbfs": db(ch_rms) if ch_rms > 1e-9 else -240.0,
            "peak_dbfs": db(ch_peak) if ch_peak > 1e-9 else -240.0,
            "isolation_db": isolation_db,
        })
    return out


# ── счётчики Master ─────────────────────────────────────────────────────
# Строки `stats`, которые обязаны быть нулевыми. Порядок и начертание заданы
# прошивкой; разбор устойчив к лишним пробелам и к посторонним строкам.
MASTER_COUNTERS = ("RingDrops", "SelfTestErr", "Underrun", "Clips", "Starve",
                   "Ring selftest")


def parse_master_stats(text: str) -> dict:
    """Достаёт из ответа `stats` строки со счётчиками и их значения."""
    out: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        for key in MASTER_COUNTERS:
            if key in line:
                out[key] = line
                break
    return out


def capture_retry(sniffer, master, meta: dict, seconds: float, scenario: str,
                  max_clicks: int = CLICK_GATE) -> tuple[np.ndarray, dict, dict, bytes, int]:
    """Захват с повтором, пока каналы не содержат одиночных выбросов.

    Между выходом Master и файлом на ПК есть путь, который даёт редкие
    одиночные выбросы почти полной шкалы: в режиме dup:1 оба порта Master несут
    побайтово один буфер, а выбросы при этом видны только в Z1 — значит брак
    возникает на проводе или в приёме I²S на S3, и к тракту Master отношения не
    имеет. Такая запись годится только для того, чтобы показать, что брак есть;
    измерять по ней амплитуду, фазу и THD нельзя, поэтому кадр перезаписывается.

    Сценарий `clip` повтором не пользоваться: клиппинг там и есть предмет
    измерения.
    """
    attempts = 0
    gate = None if scenario == "clip" else max_clicks
    for attempts in range(1, MAX_CAPTURE_ATTEMPTS + 1):
        first = attempts == 1
        data, hdr, trailer, body = receive(
            sniffer, seconds, master if first else None,
            meta["cmds"] if first else [], meta["wait"] if first else 0.4)
        if gate is None:
            break
        rate = float(hdr["declared_rate"])
        clicks = [click_scan(data[:, i], rate)["count"] for i in range(data.shape[1])]
        if max(clicks) <= gate:
            break
        print(f"Попытка {attempts}: щелчков по каналам {clicks} — брак пути "
              f"измерения, перезаписываем")
    return data, hdr, trailer, body, attempts


def run_level_sweep(sniffer, master, meta: dict, levels: list[int],
                    seconds: float, outdir: Path) -> list[dict]:
    """Запускает захват на каждом уровне tvol и возвращает метрики."""
    import time

    results = []
    base_cmds = [c for c in meta["cmds"] if not c.startswith("tvol:")]
    for lvl in levels:
        cmds = base_cmds + [f"tvol:{lvl}"]
        print(f"\n=== Уровень {lvl} ===")
        for cmd in cmds:
            reply = master_command(master, cmd, timeout=1.0)
            if reply:
                print(f"Master {cmd} -> {reply.splitlines()[-1]}")
            else:
                print(f"Master {cmd} -> без ответа")
            time.sleep(0.15)
        time.sleep(meta["wait"])
        data, hdr, trailer, body, attempts = capture_retry(
            sniffer, master, meta, seconds, "level-sweep")
        rate = float(hdr["declared_rate"])
        peaks = [float(np.max(np.abs(data[:, i]))) for i in range(data.shape[1])]
        rms = [float(np.sqrt(np.mean(data[:, i].astype(np.float64) ** 2))) for i in range(data.shape[1])]
        clicks = [click_scan(data[:, i], rate)["count"] for i in range(data.shape[1])]
        clipped = [p >= FULL_SCALE * 0.99 for p in peaks]
        results.append({
            "level": lvl,
            "peaks_dbfs": [db(p / FULL_SCALE) for p in peaks],
            "rms_dbfs": [db(r / FULL_SCALE) for r in rms],
            "clicks": clicks,
            "clipped": clipped,
            "frames": len(data),
        })
        lvl_dir = outdir / f"tvol{lvl}"
        lvl_dir.mkdir(parents=True, exist_ok=True)
        write_wav(lvl_dir / "capture.wav", data, rate)
    return results


def delay_residual(base_lag: int, z2lf_lag: int, z2hf_lag: int, d_set: int) -> dict:
    """Проверяет задержку DSP разностью отсчётов внутри одной зоны.

    Сдвиг между зонами у сниффера не фиксирован: каждая зона читается своим DMA
    и своим вызовом `i2s_channel_read`, поэтому момент, с которого кадры зон
    считаются соответствующими друг другу, от прогона к прогону гуляет на
    десятки отсчётов. Из-за этого абсолютная задержка между зонами снаффером не
    измеряется вовсе, зато разность задержек ДВУХ КАНАЛОВ ОДНОЙ ЗОНЫ от него не
    зависит: сдвиг входит в неё одинаковым слагаемым и при вычитании исчезает.

    Пусть O — сдвиг между зонами, P — запаздывание ВЧ-ветки относительно НЧ
    внутри зоны (берётся по паре каналов Z1, где задержки нулевые), D — заданная
    задержка канала Z2 НФ. Тогда, по соглашению xcorr_lag (b отстаёт от a на d →
    lag = −d):

        lag(Z1 НФ → Z2 НФ) = −(O + D)
        lag(Z1 НФ → Z2 ВЧ) = −(O + P)

    Разность даёт lag(ВЧ) − lag(НФ) = D − P, то есть D = разность + P. Остаток
    этой формулы — ошибка модели, а не измерения; он и есть результат проверки.
    """
    phase = -base_lag
    measured_diff = z2hf_lag - z2lf_lag
    predicted_diff = d_set - phase
    return {
        "delay_set": d_set,
        "phase_low_to_high": phase,
        "measured_diff": measured_diff,
        "predicted_diff": predicted_diff,
        "residual": measured_diff - predicted_diff,
        "zone_offset": -z2lf_lag - d_set,
    }


def dac_signal_check(data: np.ndarray, rate: float) -> dict:
    """Проверяет наличие сигнала на линиях DAC (параллельно снифферу).

    DAC подключен параллельно I²S-шине, поэтому если сниффер получает данные,
    DAC получает тот же сигнал. Проверка:
      * сигнал присутствует (не тишина)
      * уровень в ожидаемом диапазоне
      * нет клиппинга
      * нет разрывов (щелчков)
    """
    out = {"channels": []}
    for i in range(data.shape[1]):
        ch = data[:, i]
        if ch.size == 0:
            out["channels"].append({
                "channel": CH_NAMES[i],
                "peak": 0.0,
                "peak_dbfs": -240.0,
                "rms_dbfs": -240.0,
                "clicks": 0,
                "signal_present": False,
                "clipping": False,
            })
            continue
        peak = float(np.max(np.abs(ch)))
        rms = float(np.sqrt(np.mean(ch.astype(np.float64) ** 2)))
        clicks = click_scan(ch, rate)["count"]
        out["channels"].append({
            "channel": CH_NAMES[i],
            "peak": peak,
            "peak_dbfs": db(peak / FULL_SCALE),
            "rms_dbfs": db(rms / FULL_SCALE),
            "clicks": clicks,
            "signal_present": peak > FULL_SCALE * 0.01,
            "clipping": peak >= FULL_SCALE * 0.99,
        })
    out["all_signals_present"] = all(c["signal_present"] for c in out["channels"])
    out["any_clipping"] = any(c["clipping"] for c in out["channels"])
    return out
def analyze(data: np.ndarray, rate: float, hdr: dict | None, trailer: dict | None,
            scenario: str | None, master_stats: dict | None = None) -> dict:
    meta = SCENARIOS.get(scenario or "", "")
    tone = meta.get("tone")
    report: dict = {
        "scenario": scenario,
        "scenario_note": meta.get("note"),
        "frames": int(len(data)),
        "seconds": len(data) / rate if rate > 0 else 0.0,
        "rate": rate,
        "master_counters": master_stats or {},
        "header": hdr,
        "trailer": trailer,
        "channels": {},
        "delays": [],
        "clicks": {},
        "tones": {},
        "sweep": {},
    }

    for i, name in enumerate(CH_NAMES[:data.shape[1]]):
        st = channel_stats(data[:, i])
        st.update(noise_floor(data[:, i]))
        st["clicks"] = click_scan(data[:, i], rate)
        report["channels"][name] = st

    max_lag = int(min(len(data) // 2, 4096))
    for i in range(1, min(data.shape[1], CHANNELS)):
        rel = xcorr_lag(data[:, 0], data[:, i], max_lag)
        rel["ms"] = rel["lag"] / rate * 1000.0
        rel["pair"] = f"{CH_NAMES[0]} → {CH_NAMES[i]}"
        # В шуме и тишине максимум корреляции выбирается случайно, и задержка
        # получается правдоподобной и бессмысленной. Ниже порога канал считается
        # неразличимым, а число не выводится вовсе.
        rel["reliable"] = rel["quality"] >= 0.1
        report["delays"].append(rel)

    if scenario == "dup":
        report["dup"] = bit_compare(data)

    if scenario == "crosstalk":
        report["crosstalk"] = crosstalk_analysis(data, rate)

    report["dac_signal"] = dac_signal_check(data, rate)

    if scenario == "delay":
        by_pair = {d["pair"]: d for d in report["delays"]}
        needed = (f"{CH_NAMES[0]} → {CH_NAMES[1]}",
                  f"{CH_NAMES[0]} → {CH_NAMES[2]}",
                  f"{CH_NAMES[0]} → {CH_NAMES[3]}")
        if all(p in by_pair and by_pair[p].get("reliable") for p in needed):
            report["delay_check"] = delay_residual(
                by_pair[needed[0]]["lag"], by_pair[needed[1]]["lag"],
                by_pair[needed[2]]["lag"], DELAY_SET_SAMPLES)
        else:
            report["delay_check"] = {"error": "каналы неразличимы, проверка невозможна"}

    if tone:
        for i, name in enumerate(CH_NAMES[:data.shape[1]]):
            report["tones"][name] = tone_metrics(data[:, i], rate, tone)

    if scenario == "sweep":
        for i, name in enumerate(CH_NAMES[:data.shape[1]]):
            resp = sweep_response(data[:, i], rate)
            if "error" in resp:
                report["sweep"][name] = resp
                continue
            low = name.endswith("НЧ")
            ratio = resp["measured"] / np.maximum(resp["freq"], 1e-9)
            report["sweep"][name] = {
                "freq": resp["freq"].tolist(),
                "dbfs": [db(m) for m in resp["mag"]],
                "freq_ratio_median": float(np.median(ratio)),
                "low": branch_crossover(resp["freq"], resp["mag"], low),
            }

    # Настоящая частота дискретизации: если есть тон, она известна точно.
    if tone and report["tones"]:
        rates = [v.get("rate_true") for v in report["tones"].values() if "rate_true" in v]
        rates = [r for r in rates if r]
        if rates:
            report["rate_true_measured"] = float(np.median(rates))
    return report


def fmt(v, digits: int = 2) -> str:
    if v is None:
        return "—"
    if isinstance(v, float):
        if math.isnan(v):
            return "—"
        return f"{v:.{digits}f}"
    return str(v)


def render_markdown(rep: dict) -> str:
    lines: list[str] = []
    add = lines.append
    add(f"# Захват выхода Master: {rep.get('scenario') or 'разбор файла'}")
    add("")
    if rep.get("scenario_note"):
        add(f"**Замер:** {rep['scenario_note']}")
        add("")
    rate = rep.get("rate_true_measured") or rep["rate"]
    add(f"Кадров: {rep['frames']} · секунд: {rep['seconds']:.2f} · "
        f"частота: {fmt(rep['rate'], 1)} Гц (заявлена), измерена по тону: {fmt(rate, 1)} Гц")
    if rep.get("capture_attempts", 1) > 1:
        add("")
        add(f"Захват принят с попытки {rep['capture_attempts']}: предыдущие отброшены "
            "из-за брака пути измерения (одиночные выбросы на проводе Z1 или в приёме S3).")
    tr = rep.get("trailer")
    if tr:
        add("")
        add("Счётчики сниффера (не нули здесь означают, что файл недостоверен):")
        add("")
        add(f"- кадров принято: {tr['frames']}")
        add(f"- потеряно зоной Z1/Z2: {tr['dropped_z1']} / {tr['dropped_z2']}")
        add(f"- максимальный разрыв синхронизации зон: {tr['max_skew_frames']} кадров")
        add(f"- ошибок USB: {tr['errors']}")
    mc = rep.get("master_counters") or {}
    add("")
    if mc:
        add("Счётчики Master после захвата (сняты в той же сессии, что и запись — "
            "переоткрытие порта перезагружает плату и обнуляет их):")
        add("")
        for value in mc.values():
            add(f"- {value}")
    else:
        add("Счётчики Master не сняты: запуск шёл без `--master`.")
    add("")
    add("## Каналы")
    add("")
    add("| Канал | Пик | Пик, дБФС | RMS, дБФС | Шум, дБФС | Смещение | Щелчков |")
    add("|---|---|---|---|---|---|---|")
    for name, st in rep["channels"].items():
        add(f"| {name} | {st['peak']:.0f} | {st['peak_dbfs']:.2f} | {st['rms_dbfs']:.2f} | "
            f"{st['noise_dbfs']:.2f} | {st['dc']:.1f} | {st['clicks']['count']} |")
    add("")
    add("## Согласованность каналов")
    add("")
    add("| Пара | Задержка, отсчётов | Задержка, мс | Качество корреляции |")
    add("|---|---|---|---|")
    for d in rep["delays"]:
        if d.get("reliable", True):
            add(f"| {d['pair']} | {d['lag']} | {d['ms']:.3f} | {d['quality']:.3f} |")
        else:
            add(f"| {d['pair']} | — | — | {d['quality']:.3f} (неразличимо) |")
    if "dup" in rep:
        add("")
        add("## Побайтовое совпадение (dup:1)")
        add("")
        for p in rep["dup"]["pairs"]:
            verdict = "совпадает" if p["identical"] else f"расхождение {p['pct']:.3f} %"
            add(f"- {p['pair']}: {verdict}")
    if "delay_check" in rep:
        dc = rep["delay_check"]
        add("")
        add("## Проверка задержки DSP")
        add("")
        if "error" in dc:
            add(f"- {dc['error']}")
        else:
            add("Разность задержек внутри одной зоны не зависит от сдвига между "
                "зонами, поэтому проверка нечувствительна к неточному выравниванию "
                "зон снаффером.")
            add("")
            add(f"- задано на Z2 НФ: {dc['delay_set']} отсчётов")
            add(f"- запаздывание ВЧ-ветки внутри зоны: {dc['phase_low_to_high']} отсчётов")
            add(f"- измеренная разность lag(ВЧ) − lag(НФ): {dc['measured_diff']}")
            add(f"- ожидаемая разность: {dc['predicted_diff']}")
            add(f"- остаток: {dc['residual']} отсчётов")
            add(f"- сдвиг между зонами в этом прогоне (справочно): "
                f"{dc['zone_offset']} отсчётов")
    if rep.get("dac_signal"):
        ds = rep["dac_signal"]
        add("")
        add("## Сигнал на линиях DAC")
        add("")
        add(f"Все сигналы присутствуют: **{'да' if ds['all_signals_present'] else 'нет'}**")
        add("")
        add("| Канал | Пик, дБФС | RMS, дБФС | Щелчков | Сигнал | Клип |")
        add("|---|---|---|---|---|---|")
        for ch in ds["channels"]:
            add(f"| {ch['channel']} | {fmt(ch['peak_dbfs'])} | {fmt(ch['rms_dbfs'])} | "
                f"{ch['clicks']} | {'да' if ch['signal_present'] else 'нет'} | "
                f"{'⚠️' if ch['clipping'] else 'нет'} |")
    if rep["tones"]:
        add("")
        add("## Тон")
        add("")
        add("| Канал | Тон, Гц (измерен) | Основная, дБФС | THD, дБ | SINAD, дБ |")
        add("|---|---|---|---|---|")
        for name, t in rep["tones"].items():
            if "error" in t:
                add(f"| {name} | {t['error']} | — | — | — |")
                continue
            add(f"| {name} | {fmt(t['f_measured'], 3)} | {fmt(t['fundamental_dbfs'])} | "
                f"{fmt(t['thd_db'])} | {fmt(t['sinad_db'])} |")
    if rep["sweep"]:
        add("")
        add("## АЧХ по развёртке")
        add("")
        add("| Канал | Полоса, дБФС | Кроссовер, Гц | Пульсация, дБ | Вне полосы, дБФС | Ось частот |")
        add("|---|---|---|---|---|---|")
    for name, s in rep["sweep"].items():
        if "error" in s:
            add(f"| {name} | {s['error']} | — | — | — | — |")
            continue
        b = s["low"]
        add(f"| {name} | {fmt(b.get('passband_dbfs'))} | {fmt(b.get('crossover_hz'), 1)} | "
            f"{fmt(b.get('ripple_db'))} | {fmt(b.get('stopband_dbfs'))} | "
            f"×{fmt(s.get('freq_ratio_median'), 4)} |")
    if "crosstalk" in rep:
        ct = rep["crosstalk"]
        add("")
        add("## Перекрестные помехи (crosstalk)")
        add("")
        add(f"Опорный канал: **{ct['ref_channel']}** (пик {fmt(ct['ref_peak_dbfs'])} дБФС, "
            f"RMS {fmt(ct['ref_rms_dbfs'])} дБФС)")
        add("")
        add("| Канал | RMS, дБФС | Пик, дБФС | Изоляция, дБ |")
        add("|---|---|---|---|")
        for ch in ct["channels"]:
            add(f"| {ch['channel']} | {fmt(ch['rms_dbfs'])} | {fmt(ch['peak_dbfs'])} | "
                f"{fmt(ch['isolation_db'])} |")
    if "level_sweep" in rep:
        ls = rep["level_sweep"]
        add("")
        add("## Развертка по уровню (level-sweep)")
        add("")
        add("| tvol | Канал | Пик, дБФС | RMS, дБФС | Щелчков | Клип |")
        add("|---|---|---|---|---|---|")
        for step in ls:
            for i, name in enumerate(CH_NAMES):
                add(f"| {step['level']} | {name} | {fmt(step['peaks_dbfs'][i])} | "
                    f"{fmt(step['rms_dbfs'][i])} | {step['clicks'][i]} | "
                    f"{'⚠️ да' if step['clipped'][i] else 'нет'} |")
    add("")
    return "\n".join(lines)


# ── файлы ───────────────────────────────────────────────────────────────
def write_wav(path: Path, data: np.ndarray, rate: float) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(data.shape[1])
        w.setsampwidth(2)
        w.setframerate(int(round(rate)))
        w.writeframes(data.astype("<i2").tobytes())


def read_wav(path: Path) -> tuple[np.ndarray, float]:
    with wave.open(str(path), "rb") as w:
        ch = w.getnchannels()
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").reshape(-1, ch).astype(np.int32), float(rate)


def load_raw(path: Path) -> tuple[np.ndarray, dict, dict]:
    blob = path.read_bytes()
    hdr = parse_header(blob[:CAP_HEADER_BYTES])
    trailer = parse_trailer(blob[-CAP_TRAILER_BYTES:])
    frames = (len(blob) - CAP_HEADER_BYTES - CAP_TRAILER_BYTES) // hdr["frame_bytes"]
    data = np.frombuffer(blob[CAP_HEADER_BYTES:CAP_HEADER_BYTES + frames * hdr["frame_bytes"]],
                         dtype="<i2").reshape(-1, CHANNELS)
    return data.astype(np.int32), hdr, trailer


# ── точка входа ─────────────────────────────────────────────────────────
def cmd_list() -> None:
    print("Сценарии (--scenario):")
    for name, s in SCENARIOS.items():
        if name == "level-sweep":
            levels = s.get("levels", [1, 2, 3, 4, 5, 6])
            print(f"  {name:11s} запись {s['rec']:g} с × {len(levels)} уровней · {s['note']}")
        else:
            print(f"  {name:11s} запись {s['rec']:g} с · {s['note']}")
    print("\n  без --scenario берётся dup — он же калибровка выравнивания зон")


def main(argv: list[str] | None = None) -> int:
    # Консоль Windows по умолчанию живёт в cp1251/cp866 и роняет вывод на
    # кириллице с ошибкой кодирования уже после всей работы.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    ap = argparse.ArgumentParser(description="Захват и разбор выхода ESP32 BiAmp (Master)")
    ap.add_argument("--sniffer", help="COM-порт ESP32-S3 (нативный USB)")
    ap.add_argument("--master", help="COM-порт Master (USB-CDC, 115200)")
    ap.add_argument("--scenario", choices=sorted(SCENARIOS), default="dup")
    ap.add_argument("--seconds", type=float, help="переопределить длительность записи")
    ap.add_argument("--out", default="captures", help="каталог для результатов")
    ap.add_argument("--keep-raw", action="store_true", help="сохранить поток .bin как есть")
    ap.add_argument("--analyze", help="разобрать готовый .wav или .bin без платы")
    ap.add_argument("--list-scenarios", action="store_true")
    args = ap.parse_args(argv)

    if args.list_scenarios:
        cmd_list()
        return 0

    meta = SCENARIOS[args.scenario]
    scenario = args.scenario
    master_stats: dict[str, str] = {}
    capture_attempts = 1

    if args.analyze:
        src = Path(args.analyze)
        if src.suffix.lower() == ".wav":
            data, rate = read_wav(src)
            hdr, trailer = None, None
        else:
            data, hdr, trailer = load_raw(src)
            rate = float(hdr["declared_rate"])
        scenario = args.scenario if args.scenario in SCENARIOS else None
        outdir = src.parent
    else:
        if not args.sniffer:
            ap.error("нужен --sniffer или --analyze")
        seconds = args.seconds if args.seconds else meta["rec"]
        stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        outdir = Path(args.out) / f"{args.scenario}-{stamp}"
        outdir.mkdir(parents=True, exist_ok=True)

        sniffer = open_port(args.sniffer, 115200, timeout=0.2)
        master = None
        try:
            print("Сниффер:", sniffer_command(sniffer, "INFO").decode(errors="replace").strip())
            if args.master:
                master = open_port(args.master, 115200, timeout=0.5)
                master.dtr = False
                master.rts = False
                boot = wait_master_ready(master)
                counters = [ln.strip() for ln in boot.splitlines()
                            if ln.strip().startswith(("RingDrops", "Underrun", "Clips"))]
                print("Master готов:", "; ".join(counters) or "ответ без счётчиков")

            if scenario == "level-sweep":
                levels = meta.get("levels", [1, 2, 3, 4, 5, 6])
                level_results = run_level_sweep(sniffer, master, meta, levels, seconds, outdir)
                master_stats = parse_master_stats(master_command(master, "stats", timeout=2.0)) if master else {}
                data, hdr, trailer, body, capture_attempts = None, None, None, b"", 1
            else:
                data, hdr, trailer, body, capture_attempts = capture_retry(
                    sniffer, master, meta, seconds, scenario)
                if capture_attempts > 1:
                    print(f"Захват принят с попытки {capture_attempts}")
                if master is not None:
                    master_stats = parse_master_stats(master_command(master, "stats", timeout=2.0))
        finally:
            if master is not None:
                master.dtr = False
                master.rts = False
                master.close()
            sniffer.close()

        if scenario == "level-sweep":
            rate = float(44100)
            report = {
                "scenario": "level-sweep",
                "scenario_note": meta.get("note"),
                "frames": sum(r["frames"] for r in level_results),
                "seconds": sum(r["frames"] for r in level_results) / rate,
                "rate": rate,
                "master_counters": master_stats or {},
                "header": hdr,
                "trailer": trailer,
                "channels": {},
                "delays": [],
                "clicks": {},
                "tones": {},
                "sweep": {},
                "level_sweep": level_results,
            }
            for i, name in enumerate(CH_NAMES):
                all_peaks_dbfs = [r["peaks_dbfs"][i] for r in level_results]
                all_rms_dbfs = [r["rms_dbfs"][i] for r in level_results]
                all_clicks = [r["clicks"][i] for r in level_results]
                max_peak_dbfs = max(all_peaks_dbfs)
                max_peak = FULL_SCALE * 10 ** (max_peak_dbfs / 20.0)
                report["channels"][name] = {
                    "peak": max_peak,
                    "peak_dbfs": max_peak_dbfs,
                    "rms_dbfs": max(all_rms_dbfs),
                    "noise_dbfs": 0.0,
                    "dc": 0.0,
                    "clicks": {"count": sum(all_clicks), "positions_ms": [], "max_jump": 0},
                    "crest_db": 0.0,
                }
        else:
            rate = float(hdr["declared_rate"])
            write_wav(outdir / "capture.wav", data, rate)
            if args.keep_raw:
                (outdir / "raw.bin").write_bytes(body)
                print(f"Сырой поток: {outdir / 'raw.bin'}")
            report = analyze(data, rate, hdr, trailer, scenario or args.scenario, master_stats)
    report["capture_attempts"] = capture_attempts
    (outdir / "report.md").write_text(render_markdown(report), encoding="utf-8")
    (outdir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")

    print()
    print(render_markdown(report))
    print(f"Файлы: {outdir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())