package com.kilo.biampcontrol.dsp

import kotlin.math.log10

/**
 * K-weighted LUFS (Loudness Units relative to Full Scale) — ITU-R BS.1770-4.
 *
 * K-weighting = два каскадных биквада:
 * 1. Pre-filter (high shelf): +4 дБ выше ~1,5 кГц — моделирует резонанс уха.
 * 2. RLB highpass: спад ниже ~100 Гц — уши уменьшенно воспринимают бас.
 * Затем RMS по скользящему окну 400 мс → LUFS = -0,691 + 10·log10(meanSquare).
 *
 * Коэффициенты — для 48 кГц (источник: pyloudnorm, MIT; кросс-проверены с
 * libebur128, MIT). Другие частоты дискретизации должны пересчитываться.
 *
 * Адаптировано из bearinmind/equalizer314 (Apache 2.0).
 */
class LufsProcessor(private val sampleRate: Int = 48000) {
    // ── Pre-filter (high shelf) — ITU-R BS.1770-4, 48 кГц ──
    private val preB0 = 1.53512485958697
    private val preB1 = -2.69169618940638
    private val preB2 = 1.19839281085285
    private val preA1 = -1.69065929318241
    private val preA2 = 0.73248077421585

    private var preX1 = 0.0; private var preX2 = 0.0
    private var preY1 = 0.0; private var preY2 = 0.0

    // ── RLB highpass ──
    private val rlbB0 = 1.0
    private val rlbB1 = -2.0
    private val rlbB2 = 1.0
    private val rlbA1 = -1.99004745483398
    private val rlbA2 = 0.99007225036621

    private var rlbX1 = 0.0; private var rlbX2 = 0.0
    private var rlbY1 = 0.0; private var rlbY2 = 0.0

    // Скользящее окно: windowSize последних захватов (на 48 кГц с захватом
    // 2048 отсчётов это ~43 мс на захват ⇒ ~510 мс суммарно).
    private val windowSize = 12
    private val windowBuffer = DoubleArray(windowSize)
    private var windowIdx = 0
    private var windowFilled = false

    /**
     * Обработать один захват waveform (не-normalized отсчёты [-1, 1]).
     * @return Momentary LUFS в дБ (типично -60..0).
     */
    fun processCapture(samples: DoubleArray): Double {
        var sumSq = 0.0
        var count = 0
        for (s in samples) {
            val preOut = preB0 * s + preB1 * preX1 + preB2 * preX2 - preA1 * preY1 - preA2 * preY2
            preX2 = preX1; preX1 = s
            preY2 = preY1; preY1 = preOut

            val rlbOut = rlbB0 * preOut + rlbB1 * rlbX1 + rlbB2 * rlbX2 - rlbA1 * rlbY1 - rlbA2 * rlbY2
            rlbX2 = rlbX1; rlbX1 = preOut
            rlbY2 = rlbY1; rlbY1 = rlbOut

            sumSq += rlbOut * rlbOut
            count++
        }
        val meanSq = if (count > 0) sumSq / count else 0.0
        windowBuffer[windowIdx % windowSize] = meanSq
        windowIdx++
        if (windowIdx >= windowSize) windowFilled = true

        // Скользящее окно: усредняем windowSize последних заполненных ячеек,
        // читая их в порядке кольцевого буфера, а не с индекса 0.
        val len = if (windowFilled) windowSize else windowIdx
        var ws = 0.0
        for (i in 0 until len) {
            val idx = (windowIdx - len + i + windowSize) % windowSize
            ws += windowBuffer[idx]
        }
        val wms = ws / len
        return if (wms > 1e-10) (-0.691 + 10.0 * log10(wms)).coerceIn(-80.0, 10.0) else -80.0
    }

    fun reset() {
        preX1 = 0.0; preX2 = 0.0; preY1 = 0.0; preY2 = 0.0
        rlbX1 = 0.0; rlbX2 = 0.0; rlbY1 = 0.0; rlbY2 = 0.0
        windowBuffer.fill(0.0)
        windowIdx = 0; windowFilled = false
    }
}