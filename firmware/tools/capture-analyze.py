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

    py capture-analyze.py --sniffer COM12 --master COM14 --scenario sweep
    py capture-analyze.py --sniffer COM12 --master COM14 --scenario dup
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

CH_NAMES = ("Z1 НЧ", "Z1 ВЧ", "Z2 НЧ", "Z2 ВЧ")

# Полная шкала отсчёта int16. Все уровни в отчёте — относительно неё.
FULL_SCALE = 32768.0

# Порог разрыва между соседними отсчётами, за которым считаем щелчок.
CLICK_JUMP = 0.35 * FULL_SCALE

# Закон развёртки в прошивке Master: freq = 20 * 1000^(t/10), период 10 с.
SWEEP_LO = 20.0
SWEEP_HI = 20000.0
SWEEP_PERIOD = 10.0


# ── сценарии ────────────────────────────────────────────────────────────
# cmds — команды Master, wait — пауза перед записью, rec — длительность.
SCENARIOS: dict[str, dict] = {
    "dup": {
        "cmds": ["dup:1", "tvol:4", "tf:1000", "test:1"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "оба I²S-порта получают один блок — калибровка выравнивания зон и порядка слотов",
    },
    "silence": {
        "cmds": ["dup:0", "test:off"],
        "wait": 2.0, "rec": 6.0, "tone": None,
        "note": "шумовая полка, смещение и дрейф каждого канала",
    },
    "tone-low": {
        "cmds": ["dup:0", "tvol:4", "tf:100", "test:1"],
        "wait": 2.0, "rec": 6.0, "tone": 100.0,
        "note": "тон ниже кроссовера: НЧ-ветка обоих усилителей",
    },
    "tone-high": {
        "cmds": ["dup:0", "tvol:4", "tf:5000", "test:1"],
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
        "cmds": ["dup:0", "delay0:0", "delay1:0", "delay2:64", "delay3:64",
                 "tvol:4", "tf:1000", "test:1"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "задержка 64 отсчёта на Z2: проверка, что DSP её реально вносит",
    },
    "clip": {
        "cmds": ["dup:0", "tvol:6", "tf:1000", "test:1"],
        "wait": 2.0, "rec": 6.0, "tone": 1000.0,
        "note": "максимально разрешённый уровень теста: ищем клиппинг",
    },
}


# ── приём со сниффера ───────────────────────────────────────────────────
def open_port(port: str, baud: int, timeout: float):
    try:
        import serial  # type: ignore
    except ImportError as exc:  # pragma: no cover - зависит от окружения
        raise SystemExit("Нет pyserial: py -m pip install pyserial numpy") from exc
    return serial.Serial(port, baud, timeout=timeout)


def read_until_marker(ser, marker: bytes = b"\nEND", idle_timeout: float = 30.0) -> bytes:
    """Читает поток до строки END. Таймаут по простою, а не по общему времени."""
    import time

    buf = bytearray()
    deadline = time.monotonic() + idle_timeout
    while True:
        chunk = ser.read(65536)
        if chunk:
            buf += chunk
            if marker in buf[-64:]:
                return bytes(buf)
            deadline = time.monotonic() + idle_timeout
            continue
        if time.monotonic() > deadline:
            raise SystemExit("Сниффер перестал слать данные: прерван по таймауту простоя")


def sniffer_command(ser, cmd: str, expect: bytes | None = None, retries: int = 25) -> bytes:
    import time

    for _ in range(retries):
        ser.reset_input_buffer()
        ser.write(cmd + "\n")
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


def receive(ser, seconds: float, master_port=None, cmds: list[str] | None = None,
            wait: float = 0.0) -> tuple[np.ndarray, dict, dict]:
    import time

    if master_port is not None:
        for cmd in cmds or []:
            master_port.write(cmd + "\n")
            master_port.flush()
            time.sleep(0.15)
    if wait > 0:
        time.sleep(wait)

    ser.reset_input_buffer()
    sniffer_command(ser, f"START {seconds:g}", expect=b"GO")
    blob = read_until_marker(ser)
    if b"\nEND" not in blob:
        raise SystemExit("В потоке нет завершающей строки END")

    end_pos = blob.rindex(b"\nEND")
    head = blob[:end_pos]
    hdr = parse_header(head[:CAP_HEADER_BYTES])
    data_end = head.rindex(b"\nEND", 0, len(head) - 4) if b"\nEND" in head else -1
    tail_start = len(head) - CAP_TRAILER_BYTES if data_end < 0 else data_end - CAP_TRAILER_BYTES
    trailer = parse_trailer(head[tail_start:tail_start + CAP_TRAILER_BYTES])
    payload = head[CAP_HEADER_BYTES:tail_start]
    frames = len(payload) // hdr["frame_bytes"]
    data = np.frombuffer(payload[:frames * hdr["frame_bytes"]], dtype="<i2").reshape(-1, CHANNELS)
    return data.astype(np.int32), hdr, trailer


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
    # Края файла обрезаны настройкой записи, их уровень ничего не значит.
    core = x[len(x) // 10: -len(x) // 10] if len(x) > 40 else x
    rms = float(np.sqrt(np.mean(core.astype(np.float64) ** 2))) if core.size else 0.0
    return {"noise_dbfs": db(rms / FULL_SCALE), "noise_rms": rms}


def xcorr_lag(ref: np.ndarray, sig: np.ndarray, max_lag: int) -> dict:
    a = ref.astype(np.float64) - float(np.mean(ref))
    b = sig.astype(np.float64) - float(np.mean(sig))
    n = 1 << int(math.ceil(math.log2(len(a) + len(b))))
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


# ── отчёт ───────────────────────────────────────────────────────────────
def analyze(data: np.ndarray, rate: float, hdr: dict | None, trailer: dict | None,
            scenario: str | None) -> dict:
    meta = SCENARIOS.get(scenario or "", {})
    tone = meta.get("tone")
    report: dict = {
        "scenario": scenario,
        "scenario_note": meta.get("note"),
        "frames": int(len(data)),
        "seconds": len(data) / rate if rate > 0 else 0.0,
        "rate": rate,
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
    tr = rep.get("trailer")
    if tr:
        add("")
        add("Счётчики сниффера (не нули здесь означают, что файл недостоверен):")
        add("")
        add(f"- кадров принято: {tr['frames']}")
        add(f"- потеряно зоной Z1/Z2: {tr['dropped_z1']} / {tr['dropped_z2']}")
        add(f"- максимальный разрыв синхронизации зон: {tr['max_skew_frames']} кадров")
        add(f"- ошибок USB: {tr['errors']}")
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
                master.reset_input_buffer()
            data, hdr, trailer = receive(sniffer, seconds, master, meta["cmds"], meta["wait"])
        finally:
            sniffer.close()
            if master is not None:
                master.close()
        rate = float(hdr["declared_rate"])
        write_wav(outdir / "capture.wav", data, rate)

    report = analyze(data, rate, hdr, trailer, scenario or args.scenario)
    (outdir / "report.md").write_text(render_markdown(report), encoding="utf-8")
    (outdir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")

    print()
    print(render_markdown(report))
    print(f"Файлы: {outdir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())