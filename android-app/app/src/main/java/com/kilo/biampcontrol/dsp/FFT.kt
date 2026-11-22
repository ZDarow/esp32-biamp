package com.kilo.biampcontrol.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Pure-Kotlin radix-2 Cooley-Tukey FFT.
 * Никаких внешних зависимостей: без JTransforms, KissFFT, NDK.
 * Хватает на 4096 точек за ~1 мс на современном Android.
 *
 * Адаптировано из bearinmind/equalizer314 (Apache 2.0).
 */
class FFT(val size: Int) {
    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT size must be a power of 2, got $size"
        }
    }

    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private var wnd: DoubleArray? = null
    private var wndEnergyFactor = 1.0

    init {
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    fun initHannWindow(fftLen: Int) {
        val w = DoubleArray(fftLen)
        for (i in 0 until fftLen) {
            w[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (fftLen - 1.0))) * 2.0
        }
        var normalizeFactor = 0.0
        for (i in 0 until fftLen) normalizeFactor += w[i]
        normalizeFactor = fftLen / normalizeFactor
        wndEnergyFactor = 0.0
        for (i in 0 until fftLen) {
            w[i] *= normalizeFactor
            wndEnergyFactor += w[i] * w[i]
        }
        wndEnergyFactor = fftLen / wndEnergyFactor
        wnd = w
    }

    fun applyWindow(input: FloatArray): DoubleArray {
        val w = wnd
        if (w == null || w.size != input.size) {
            initHannWindow(input.size)
        }
        val window = wnd!!
        val windowed = DoubleArray(input.size)
        for (i in input.indices) windowed[i] = input[i].toDouble() * window[i]
        return windowed
    }

    fun computePowerSpectrum(windowedInput: DoubleArray): DoubleArray {
        require(windowedInput.size == size) {
            "Input size ${windowedInput.size} doesn't match FFT size $size"
        }
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
        for (i in 0 until size) { real[i] = windowedInput[i]; imag[i] = 0.0 }
        fft(real, imag)
        val numBins = size / 2 + 1
        val power = DoubleArray(numBins)
        val scaler = 4.0 / (size.toDouble() * size.toDouble())
        power[0] = (real[0] * real[0] + imag[0] * imag[0]) * scaler / 4.0
        for (i in 1 until numBins - 1) {
            power[i] = (real[i] * real[i] + imag[i] * imag[i]) * scaler
        }
        val nyquist = numBins - 1
        power[nyquist] = (real[nyquist] * real[nyquist] + imag[nyquist] * imag[nyquist]) * scaler / 4.0
        return power
    }

    fun computePowerSpectrumDB(windowedInput: DoubleArray): DoubleArray {
        val power = computePowerSpectrum(windowedInput)
        for (i in power.indices) power[i] = 10.0 * log10(power[i].coerceAtLeast(1e-18))
        return power
    }

    fun getWindowEnergyFactor(): Double = wndEnergyFactor

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var k = n / 2
            while (k <= j) { j -= k; k /= 2 }
            j += k
        }
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val tableFactor = n / step
            for (i in 0 until n step step) {
                var ti = 0
                for (k in 0 until halfStep) {
                    val ei = i + k
                    val oi = ei + halfStep
                    val tR = cosTable[ti] * re[oi] - sinTable[ti] * im[oi]
                    val tI = cosTable[ti] * im[oi] + sinTable[ti] * re[oi]
                    re[oi] = re[ei] - tR; im[oi] = im[ei] - tI
                    re[ei] += tR; im[ei] += tI
                    ti += tableFactor
                }
            }
        }
    }

    fun binToFrequency(binIndex: Int, sampleRate: Int): Float =
        binIndex.toFloat() * sampleRate / size

    fun frequencyToBin(frequency: Float, sampleRate: Int): Int =
        (frequency * size / sampleRate).toInt().coerceIn(0, size / 2)
}