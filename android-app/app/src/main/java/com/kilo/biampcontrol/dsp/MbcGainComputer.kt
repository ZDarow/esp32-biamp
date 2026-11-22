package com.kilo.biampcontrol.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Многополосная динамика: soft-knee компрессор + экспандер/шумопорог.
 *
 * GR зависит от ФАКТИЧЕСКОГО УРОВНЯ каждый кадр, а не от настроек — вот что
 * отличает компрессор от ручки громкости. Использование: каждый кадр
 * передавай FFT-спектр в computeAllBandGains(), выходной пиксельный дБ =
 * входной дБ + getGainAtFrequency().
 *
 * Адаптировано из bearinmind/equalizer314 (Apache 2.0).
 */
class MbcGainComputer(val numBands: Int) {
    private val bandTotalGains = DoubleArray(numBands)
    private val smoothedGR = DoubleArray(numBands)
    private val smoothedCompressorGR = DoubleArray(numBands)
    private val smoothedExpanderGR = DoubleArray(numBands)
    private var crossoverFreqs = DoubleArray(0)

    data class BandSettings(
        val preGain: Double,
        val postGain: Double,
        val threshold: Double,
        val ratio: Double,
        val kneeWidth: Double,
        val noiseGateThreshold: Double,
        val expanderRatio: Double,
        val attackMs: Double = 1.0,
        val releaseMs: Double = 100.0,
        val lowCutoff: Double,
        val highCutoff: Double
    )

    /**
     * Передаточная функция компрессора: входной дБ → GR дБ. Всегда ≤ 0 при
     * сжатии (ratio > 1), 0 при входе ниже порога. Soft-knee формула
     * Giannoulis et al. (JAES 2012).
     */
    fun computeGainReduction(inputDb: Double, threshold: Double, ratio: Double, kneeWidth: Double): Double {
        if (ratio <= 1.0) return 0.0
        val slope = 1.0 / ratio - 1.0
        if (kneeWidth <= 0.0) {
            return if (inputDb <= threshold) 0.0 else slope * (inputDb - threshold)
        }
        val halfKnee = kneeWidth / 2.0
        return when {
            inputDb < threshold - halfKnee -> 0.0
            inputDb > threshold + halfKnee -> slope * (inputDb - threshold)
            else -> {
                val x = inputDb - threshold + halfKnee
                slope * x * x / (2.0 * kneeWidth)
            }
        }
    }

    fun computeExpanderGR(inputDb: Double, noiseGateThreshold: Double, expanderRatio: Double): Double {
        if (expanderRatio <= 1.0 || inputDb >= noiseGateThreshold) return 0.0
        val belowBy = noiseGateThreshold - inputDb
        return -(expanderRatio - 1.0) * belowBy
    }

    fun measureBandLevel(spectrumDb: DoubleArray, sampleRate: Int, fftSize: Int,
                         lowCutoff: Double, highCutoff: Double): Double {
        val binWidth = sampleRate.toDouble() / fftSize
        val lowBin = (lowCutoff / binWidth).toInt().coerceIn(1, spectrumDb.size - 1)
        val highBin = (highCutoff / binWidth).toInt().coerceIn(lowBin, spectrumDb.size - 1)
        if (lowBin >= highBin) return -96.0
        var sumPower = 0.0
        var count = 0
        for (bin in lowBin..highBin) {
            sumPower += 10.0.pow(spectrumDb[bin] / 10.0)
            count++
        }
        return if (count > 0 && sumPower > 1e-12) 10.0 * log10(sumPower / count) else -96.0
    }

    fun computeAllBandGains(spectrumDb: DoubleArray, sampleRate: Int, fftSize: Int,
                            bandSettings: Array<BandSettings>) {
        if (crossoverFreqs.size != numBands - 1) crossoverFreqs = DoubleArray(numBands - 1)
        for (i in 0 until (numBands - 1).coerceAtMost(bandSettings.size)) {
            crossoverFreqs[i] = bandSettings[i].highCutoff
        }
        val dt = 33.0
        for (i in 0 until numBands.coerceAtMost(bandSettings.size)) {
            val s = bandSettings[i]
            val bandLevel = measureBandLevel(spectrumDb, sampleRate, fftSize, s.lowCutoff, s.highCutoff)
            val levelAfterPreGain = bandLevel + s.preGain
            val compressorGR = computeGainReduction(levelAfterPreGain, s.threshold, s.ratio, s.kneeWidth)
            val expanderGR = computeExpanderGR(levelAfterPreGain, s.noiseGateThreshold, s.expanderRatio)
            val totalGR = compressorGR + expanderGR

            val attackAlpha = (1.0 - exp(-dt / s.attackMs.coerceAtLeast(0.01))).coerceIn(0.0001, 1.0)
            val releaseAlpha = (1.0 - exp(-dt / s.releaseMs.coerceAtLeast(1.0))).coerceIn(0.0001, 1.0)
            val alpha = if (totalGR < smoothedGR[i]) attackAlpha else releaseAlpha
            smoothedGR[i] = alpha * totalGR + (1.0 - alpha) * smoothedGR[i]
            val compAlpha = if (compressorGR < smoothedCompressorGR[i]) attackAlpha else releaseAlpha
            smoothedCompressorGR[i] = compAlpha * compressorGR + (1.0 - compAlpha) * smoothedCompressorGR[i]
            val expAlpha = if (expanderGR < smoothedExpanderGR[i]) attackAlpha else releaseAlpha
            smoothedExpanderGR[i] = expAlpha * expanderGR + (1.0 - expAlpha) * smoothedExpanderGR[i]

            bandTotalGains[i] = s.preGain + smoothedGR[i] + s.postGain
        }
    }

    fun getGainAtFrequency(freq: Double): Double {
        if (numBands <= 1) return bandTotalGains.getOrElse(0) { 0.0 }
        var band = 0
        for (i in crossoverFreqs.indices) if (freq >= crossoverFreqs[i]) band = i + 1
        if (band > 0 && band <= crossoverFreqs.size) {
            val fc = crossoverFreqs[band - 1]
            val octFromCross = ln(freq / fc) / ln(2.0)
            if (octFromCross > -0.5 && octFromCross < 0.5) {
                val t = octFromCross + 0.5
                val smooth = t * t * (3.0 - 2.0 * t)
                val gainA = bandTotalGains[(band - 1).coerceIn(0, numBands - 1)]
                val gainB = bandTotalGains[band.coerceIn(0, numBands - 1)]
                return gainA * (1.0 - smooth) + gainB * smooth
            }
        }
        return bandTotalGains[band.coerceIn(0, numBands - 1)]
    }

    fun getSmoothedGR(bandIndex: Int): Double = smoothedGR.getOrElse(bandIndex) { 0.0 }
    fun getSmoothedCompressorGR(bandIndex: Int): Double = smoothedCompressorGR.getOrElse(bandIndex) { 0.0 }
    fun getSmoothedExpanderGR(bandIndex: Int): Double = smoothedExpanderGR.getOrElse(bandIndex) { 0.0 }

    fun release() {
        smoothedGR.fill(0.0); smoothedCompressorGR.fill(0.0)
        smoothedExpanderGR.fill(0.0); bandTotalGains.fill(0.0)
    }
}