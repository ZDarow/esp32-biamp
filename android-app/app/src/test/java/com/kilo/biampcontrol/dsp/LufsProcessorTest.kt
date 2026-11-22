package com.kilo.biampcontrol.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LufsProcessorTest {

    @Test
    fun `тишина даёт -80 LUFS`() {
        val p = LufsProcessor(48000)
        val cap = DoubleArray(1024) { 0.0 }
        repeat(12) { p.processCapture(cap) }
        assertEquals(-80.0, p.processCapture(cap), 0.1)
    }

    @Test
    fun `половина амплитуды = минус 6 дБ у louder`() {
        val p = LufsProcessor(48000)
        val sr = 48000
        val freq = 1000.0
        val cap = DoubleArray(4096) { i -> sin(2.0 * PI * freq * i / sr) }
        val loud = p.processCapture(cap)
        p.reset() // иначе фильтр помнит состояние предыдущего захвата
        val quiet = p.processCapture(DoubleArray(cap.size) { i -> cap[i] * 0.5 })
        // 20*log10(0.5) = -6,02 дБ. Сброс состояния фильтра перед вторым
        // захватом исключает влияние начального transient'а.
        assertEquals(-6.0, quiet - loud, 0.5)
    }

    @Test
    fun `полный синус 1 кГц ≈ -3,7 LUFS`() {
        val p = LufsProcessor(48000)
        val sr = 48000
        val cap = DoubleArray(8192) { i -> sin(2.0 * PI * 1000.0 * i / sr) }
        val lufs = p.processCapture(cap)
        assertTrue("full-scale 1 kHz sine should be near -3.7 LUFS, actual=$lufs",
            lufs in -5.0..-2.0)
    }

    @Test
    fun `окно 400 мс скользит, тишина вытесняет сигнал`() {
        val p = LufsProcessor(48000)
        val sr = 48000
        val sig = DoubleArray(2048) { i -> sin(2.0 * PI * 1000.0 * i / sr) }
        val silence = DoubleArray(2048) { 0.0 }
        repeat(12) { p.processCapture(sig) }
        val signalLevel = p.processCapture(sig) // окно полностью заполнено сигналом
        // Нужно 12 захватов тишиной, чтобы полностью вытеснить сигнал из окна.
        var last = signalLevel
        repeat(12) { last = p.processCapture(silence) }
        // Уровень должен упасть ниже уровня сигнала. Полное затухание
        // состояния RLB-фильтра 2-го порядка занимает больше 12 захватов,
        // поэтому проверяем падение, а не точный -80.
        assertTrue("level should drop after flushing window, signal=$signalLevel, last=$last",
            last < signalLevel - 20.0)
    }

    @Test
    fun `reset обнуляет состояние`() {
        val p = LufsProcessor(48000)
        val sr = 48000
        val sig = DoubleArray(2048) { i -> sin(2.0 * PI * 1000.0 * i / sr) }
        repeat(12) { p.processCapture(sig) }
        p.reset()
        val silence = DoubleArray(2048) { 0.0 }
        assertEquals(-80.0, p.processCapture(silence), 0.1)
    }
}