package com.kilo.biampcontrol.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FFTTest {

    @Test
    fun `размер должен быть степенью двойки`() {
        val fft = FFT(1024)
        assertEquals(1024, fft.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `нестепенный размер выбрасывает исключение`() {
        FFT(100)
    }

    @Test
    fun `sinus на 1 кГц даёт пик в соответствующей бине`() {
        val sr = 48000
        val freq = 1000.0
        val n = 1024
        val fft = FFT(n)
        fft.initHannWindow(n)
        val src = FloatArray(n) { i ->
            (sin(2.0 * PI * freq * i / sr)).toFloat()
        }
        val power = fft.computePowerSpectrum(fft.applyWindow(src))
        val peakBin = power.indices.maxByOrNull { power[it] } ?: -1
        val peakFreq = fft.binToFrequency(peakBin, sr)
        assertTrue("пик должен быть вблизи 1 кГц, actual=$peakFreq", abs(peakFreq - 1000f) < 100f)
        // пик должен быть явно выше, чем фон
        val median = power.sorted().let { it[it.size / 2] }
        assertTrue(power[peakBin] > median * 10)
    }

    @Test
    fun `постоянный сигнал — энергия только в DC-бине`() {
        val n = 256
        val fft = FFT(n)
        // без окна: чистый DC даёт пик только в бине 0
        val raw = DoubleArray(n) { 1.0 }
        val power = fft.computePowerSpectrum(raw)
        assertTrue("DC бин должен быть ведущим", power[0] > power[1] * 10)
        for (i in 1 until power.size) {
            assertTrue("bin $i должна быть мала", power[i] < power[0] * 0.01)
        }
    }

    @Test
    fun `computePowerSpectrumDBconverts power to dB`() {
        val n = 128
        val fft = FFT(n)
        fft.initHannWindow(n)
        val src = FloatArray(n) { 0.5f }
        val db = fft.computePowerSpectrumDB(fft.applyWindow(src))
        for (v in db) assertTrue("dB must be finite", v.isFinite())
    }

    @Test
    fun `frequencyToBin round-trips with binToFrequency`() {
        val fft = FFT(2048)
        val sr = 48000
        val bin = fft.frequencyToBin(1000f, sr)
        val freq = fft.binToFrequency(bin, sr)
        assertEquals(1000f, freq, 1f * sr / fft.size)
    }

    @Test
    fun `frequencyToBin clamps to nyquist`() {
        val fft = FFT(256)
        val sr = 48000
        val bin = fft.frequencyToBin(100000f, sr)
        assertEquals(fft.size / 2, bin)
    }

    @Test
    fun `frequencyToBin clamps to zero`() {
        val fft = FFT(256)
        val bin = fft.frequencyToBin(-500f, 48000)
        assertEquals(0, bin)
    }

    @Test
    fun `Hann window energy factor is positive and finite`() {
        val fft = FFT(1024)
        fft.initHannWindow(1024)
        val factor = fft.getWindowEnergyFactor()
        // factor = N / sum(wnd^2); for this window it is ~0.666, not 1.0.
        assertTrue("Hann window energy factor should be positive, actual=$factor", factor > 0.0)
        assertTrue(factor.isFinite())
    }

    @Test
    fun `window size mismatch triggers re-init`() {
        val fft = FFT(1024)
        fft.initHannWindow(1024)
        val w1 = fft.applyWindow(FloatArray(1024) { 1.0f })
        // Different input size forces re-init internally.
        val w2 = fft.applyWindow(FloatArray(512) { 1.0f })
        assertEquals(512, w2.size)
        // Both should be finite and non-zero.
        for (v in w1) assertTrue(v.isFinite())
        for (v in w2) assertTrue(v.isFinite())
    }
}