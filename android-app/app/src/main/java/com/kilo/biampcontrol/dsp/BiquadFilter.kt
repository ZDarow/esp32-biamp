package com.kilo.biampcontrol.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stateful биквад: вычисляет коэффициенты RBJ и фильтрует поток отсчётов.
 *
 * Коэффициенты вычисляются теми же формулами, что и в ui/FilterGraph.kt
 * (rbjSection, ESP32_BiAmp.ino:190-228), поэтому отклик в приложении
 * совпадает с графиком и с тем, что делает прошивка, а не приближённо.
 *
 * Поля [b0]..[a2] открыты: MbcGainComputer и ParametricEqualizer читают их
 * напрямую, без дублирования формул.
 */
class BiquadFilter(
    var frequency: Float,
    var gain: Float,
    var filterType: FilterType,
    val sampleRate: Int = 44100,
    var q: Double = 0.7071
) {
    enum class FilterType {
        LOWPASS, HIGHPASS, PEAK_EQ, LOWSHELF, HIGHSHELF, BYPASS
    }

    companion object {
        private const val ANTI_DENORMAL = 1.0e-20
    }

    // a0 — нормализатор, используется только при расчёте коэффициентов.
    private var a0 = 0.0

    var b0 = 0.0; private set
    var b1 = 0.0; private set
    var b2 = 0.0; private set
    var a1 = 0.0; private set
    var a2 = 0.0; private set

    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    init { calculateCoefficients() }

    fun updateParameters(freq: Float, gainDb: Float, type: FilterType, qFactor: Double = 0.7071) {
        frequency = freq; gain = gainDb; filterType = type; q = qFactor
        calculateCoefficients()
    }

    private fun calculateCoefficients() {
        if (filterType == FilterType.BYPASS) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0; return
        }
        val w0 = 2.0 * PI * frequency / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val A = if (filterType == FilterType.PEAK_EQ) 10.0.pow(gain / 40.0) else 10.0.pow(gain / 20.0)
        val sqA = 2.0 * sqrt(A) * alpha
        when (filterType) {
            FilterType.LOWPASS -> {
                b0 = (1 - cw) * 0.5; b1 = 1 - cw; b2 = b0
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            FilterType.HIGHPASS -> {
                b0 = (1 + cw) * 0.5; b1 = -(1 + cw); b2 = b0
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            FilterType.PEAK_EQ -> {
                b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A
                a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A
            }
            FilterType.LOWSHELF -> {
                b0 = A * ((A + 1) - (A - 1) * cw + sqA)
                b1 = 2.0 * A * ((A - 1) - (A + 1) * cw)
                b2 = A * ((A + 1) - (A - 1) * cw - sqA)
                a0 = (A + 1) + (A - 1) * cw + sqA
                a1 = -2.0 * ((A - 1) + (A + 1) * cw)
                a2 = (A + 1) + (A - 1) * cw - sqA
            }
            FilterType.HIGHSHELF -> {
                b0 = A * ((A + 1) + (A - 1) * cw + sqA)
                b1 = -2.0 * A * ((A - 1) + (A + 1) * cw)
                b2 = A * ((A + 1) + (A - 1) * cw - sqA)
                a0 = (A + 1) - (A - 1) * cw + sqA
                a1 = 2.0 * ((A - 1) - (A + 1) * cw)
                a2 = (A + 1) - (A - 1) * cw - sqA
            }
            else -> return
        }
        val inv = 1.0 / a0
        b0 *= inv; b1 *= inv; b2 *= inv; a1 *= inv; a2 *= inv
    }

    /** Отклик на один отсчёт. */
    fun process(sample: Double): Double {
        val out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = sample
        y2 = y1; y1 = out
        return if (out.isNaN()) ANTI_DENORMAL else out
    }

    /** Отклик на массив, измеряется in place. */
    fun processBlock(data: DoubleArray, offset: Int = 0, count: Int = data.size) {
        for (i in offset until offset + count) {
            val s = data[i]
            val out = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = s
            y2 = y1; y1 = out
            data[i] = if (out.isNaN()) ANTI_DENORMAL else out
        }
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    /** Отношение мощностей |H(f)|^2 — те же формулы, что и ui/FilterGraph.kt:powerRatio. */
    fun powerRatio(f: Double): Double {
        val w = 2.0 * PI * f / sampleRate
        val cw = cos(w); val sw = sin(w)
        val c2w = cos(2.0 * w); val s2w = sin(2.0 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1.0 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        val den = dr * dr + di * di
        if (den < 1e-30) return 1e30
        return (nr * nr + ni * ni) / den
    }

    fun db(f: Double): Double = 10.0 * log10(powerRatio(f).coerceAtLeast(1e-30))
}