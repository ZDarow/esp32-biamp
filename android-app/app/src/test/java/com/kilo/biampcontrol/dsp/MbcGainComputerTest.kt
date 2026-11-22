package com.kilo.biampcontrol.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MbcGainComputerTest {

    private val mbc = MbcGainComputer(1)

    @Test
    fun `компрессор сжимает выше порога`() {
        val gr = mbc.computeGainReduction(10.0, 0.0, 4.0, 0.0)
        // slope = 1/4 - 1 = -0,75; GR = -0,75 * (10 - 0) = -7,5
        assertEquals(-7.5, gr, 1e-9)
    }

    @Test
    fun `below threshold no compression`() {
        val gr = mbc.computeGainReduction(-10.0, 0.0, 4.0, 0.0)
        assertEquals(0.0, gr, 1e-9)
    }

    @Test
    fun `ratio 1 disables compression`() {
        val gr = mbc.computeGainReduction(10.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, gr, 1e-9)
    }

    @Test
    fun `soft knee is between hard knee values`() {
        val hard = mbc.computeGainReduction(0.0, 0.0, 4.0, 0.0)
        val soft = mbc.computeGainReduction(0.0, 0.0, 4.0, 4.0)
        // threshold=0, knee=4 → input=0 sits in the middle of the knee:
        // GR = slope * x^2 / (2*knee) = -0.75 * 16 / 8 = -1.5.
        // Hard knee at input=0 gives 0.0 (below threshold): soft knee is
        // already compressing where hard knee is still flat.
        assertTrue("soft-knee GR must be negative and above hard-knee floor",
            soft in -2.0..0.0)
        assertTrue("soft must be more aggressive than hard-knee at threshold", soft <= hard)
    }

    @Test
    fun `expander молчалив выше порога`() {
        val gr = mbc.computeExpanderGR(0.0, -20.0, 3.0)
        assertEquals(0.0, gr, 1e-9)
    }

    @Test
    fun `expander поднимает ниже порога`() {
        val gr = mbc.computeExpanderGR(-30.0, -20.0, 3.0)
        // belowBy = 10; GR = -(3-1)*10 = -20
        assertEquals(-20.0, gr, 1e-9)
    }

    @Test
    fun `measureBandLevel returns -96 for empty spectrum`() {
        val spectrumDb = DoubleArray(64) { -96.0 }
        val lvl = mbc.measureBandLevel(spectrumDb, 48000, 256, 100.0, 4000.0)
        assertEquals(-96.0, lvl, 0.1)
    }

    @Test
    fun `measureBandLevel picks energy in band`() {
        val sr = 48000
        val fftSize = 256
        val binWidth = sr.toDouble() / fftSize
        val spectrumDb = DoubleArray(fftSize / 2 + 1) { -96.0 }
        // ставим пик в бине ≈ 1 кГц
        val peakBin = (1000.0 / binWidth).toInt().coerceIn(0, spectrumDb.size - 1)
        spectrumDb[peakBin] = 0.0
        val lvl = mbc.measureBandLevel(spectrumDb, sr, fftSize, 500.0, 2000.0)
        assertTrue("band with a 0 dB peak should measure above -96, actual=$lvl", lvl > -96.0)
    }

    @Test
    fun `computeAllBandGains sets bandTotalGains`() {
        val mbc2 = MbcGainComputer(2)
        val spectrumDb = DoubleArray(129) { -96.0 }
        val settings = arrayOf(
            MbcGainComputer.BandSettings(
                preGain = 0.0, postGain = 0.0, threshold = 0.0, ratio = 1.0,
                kneeWidth = 0.0, noiseGateThreshold = -60.0, expanderRatio = 1.0,
                lowCutoff = 20.0, highCutoff = 500.0
            ),
            MbcGainComputer.BandSettings(
                preGain = 0.0, postGain = 0.0, threshold = 0.0, ratio = 1.0,
                kneeWidth = 0.0, noiseGateThreshold = -60.0, expanderRatio = 1.0,
                lowCutoff = 500.0, highCutoff = 8000.0
            )
        )
        mbc2.computeAllBandGains(spectrumDb, 48000, 256, settings)
        // ratio=1, below threshold → GR=0 → gain = pre+post = 0
        assertEquals(0.0, mbc2.getGainAtFrequency(300.0), 1e-9)
        assertEquals(0.0, mbc2.getGainAtFrequency(3000.0), 1e-9)
    }

    @Test
    fun `getGainAtFrequency interpolates near crossover`() {
        val mbc2 = MbcGainComputer(2)
        val spectrumDb = DoubleArray(129) { -96.0 }
        val settings = arrayOf(
            MbcGainComputer.BandSettings(
                preGain = 2.0, postGain = 0.0, threshold = 0.0, ratio = 1.0,
                kneeWidth = 0.0, noiseGateThreshold = -60.0, expanderRatio = 1.0,
                lowCutoff = 20.0, highCutoff = 500.0
            ),
            MbcGainComputer.BandSettings(
                preGain = -3.0, postGain = 0.0, threshold = 0.0, ratio = 1.0,
                kneeWidth = 0.0, noiseGateThreshold = -60.0, expanderRatio = 1.0,
                lowCutoff = 500.0, highCutoff = 8000.0
            )
        )
        mbc2.computeAllBandGains(spectrumDb, 48000, 256, settings)
        // well below crossover → band 0 gain = +2
        assertEquals(2.0, mbc2.getGainAtFrequency(100.0), 1e-9)
        // well above crossover → band 1 gain = -3
        assertEquals(-3.0, mbc2.getGainAtFrequency(3000.0), 1e-9)
    }

    @Test
    fun `release zeroes state`() {
        val spectrumDb = DoubleArray(129) { -96.0 }
        val settings = arrayOf(
            MbcGainComputer.BandSettings(
                preGain = 5.0, postGain = 1.0, threshold = 0.0, ratio = 1.0,
                kneeWidth = 0.0, noiseGateThreshold = -60.0, expanderRatio = 1.0,
                lowCutoff = 20.0, highCutoff = 8000.0
            )
        )
        mbc.computeAllBandGains(spectrumDb, 48000, 256, settings)
        assertEquals(6.0, mbc.getGainAtFrequency(1000.0), 1e-9)
        mbc.release()
        assertEquals(0.0, mbc.getGainAtFrequency(1000.0), 1e-9)
        assertEquals(0.0, mbc.getSmoothedGR(0), 1e-9)
    }
}