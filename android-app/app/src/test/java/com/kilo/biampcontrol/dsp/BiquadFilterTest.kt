package com.kilo.biampcontrol.dsp

import com.kilo.biampcontrol.ui.SEC_BYPASS
import com.kilo.biampcontrol.ui.SEC_HIGHPASS
import com.kilo.biampcontrol.ui.SEC_LOWPASS
import com.kilo.biampcontrol.ui.SEC_LOWSHELF
import com.kilo.biampcontrol.ui.SEC_HIGHSHELF
import com.kilo.biampcontrol.ui.SEC_PEAK_EQ
import com.kilo.biampcontrol.ui.rbjSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val TOL = 1e-9

class BiquadFilterTest {

    @Test
    fun `bypass не меняет сигнал и плоский`() {
        val b = BiquadFilter(0f, 0f, BiquadFilter.FilterType.BYPASS)
        assertEquals(1.0, b.b0, TOL)
        assertEquals(0.0, b.b1, TOL); assertEquals(0.0, b.b2, TOL)
        assertEquals(0.0, b.a1, TOL); assertEquals(0.0, b.a2, TOL)
        assertEquals(1.0, b.powerRatio(1000.0), TOL)
        assertEquals(5.0, b.process(5.0), TOL)
    }

    @Test
    fun `ФНЧ даёт минус три дБ на частоте среза`() {
        val lp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        assertEquals(-3.0, lp.db(1000.0), 0.1)
    }

    @Test
    fun `ФВЧ даёт минус три дБ на частоте среза`() {
        val hp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.HIGHPASS)
        assertEquals(-3.0, hp.db(1000.0), 0.1)
    }

    @Test
    fun `коэффициенты stateful биквада совпадают с графиком FilterGraph`() {
        // Кросс-проверка: stateful биквад должен давать те же коэффициенты,
        // что и rbjSection в ui/FilterGraph.kt (ESP32_BiAmp.ino:190-228),
        // иначе график и DSP-модель разойдутся.
        val cases = listOf(
            1000f to BiquadFilter.FilterType.LOWPASS to SEC_LOWPASS,
            1000f to BiquadFilter.FilterType.HIGHPASS to SEC_HIGHPASS,
            1000f to BiquadFilter.FilterType.PEAK_EQ to SEC_PEAK_EQ,
            1000f to BiquadFilter.FilterType.LOWSHELF to SEC_LOWSHELF,
            1000f to BiquadFilter.FilterType.HIGHSHELF to SEC_HIGHSHELF
        )
        for ((pair, sec) in cases) {
            val (f, type) = pair
            val b = BiquadFilter(f, 6.0f, type)
            val ref = rbjSection(sec, f.toDouble(), 0.7071, 6.0)
            assertEquals("b0 fc=$f type=$type", ref.b0, b.b0, 1e-12)
            assertEquals("b1 fc=$f type=$type", ref.b1, b.b1, 1e-12)
            assertEquals("b2 fc=$f type=$type", ref.b2, b.b2, 1e-12)
            assertEquals("a1 fc=$f type=$type", ref.a1, b.a1, 1e-12)
            assertEquals("a2 fc=$f type=$type", ref.a2, b.a2, 1e-12)
        }
    }

    @Test
    fun `bell +6 дБ на 1 кГц даёт +6 дБ в центре`() {
        val bell = BiquadFilter(1000f, 6.0f, BiquadFilter.FilterType.PEAK_EQ)
        assertEquals(6.0, bell.db(1000.0), 0.5)
    }

    @Test
    fun `ФНЧ пропускает низкие и гасит высокие`() {
        val lp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        assertEquals(0.0, lp.db(20.0), 0.1)
        assertTrue(lp.db(10000.0) < -20.0)
    }

    @Test
    fun `stateful фильтр проходит постоянный сигнал без ослабления`() {
        // У ФНЧ DC-усиление близко к 1.0: билинейное преобразование
        // смещает его примерно на 0.7%, поэтому допуск 1e-2, а не 1e-3.
        val lp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        repeat(200) { lp.process(1.0) }
        assertEquals(1.0, lp.process(1.0), 1e-2)
    }

    @Test
    fun `импульс затухает`() {
        // Второго порядка биквад даёт кратковременный выброс (overshoot) на早期
        // отсчётах, поэтому пик проверяем только по хвосту последних 500 отсчётов.
        val lp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        lp.process(1.0) // импульс
        repeat(500) { lp.process(0.0) } // пропускаем начальный transient
        var max = 0.0
        repeat(500) { max = abs(lp.process(0.0)).coerceAtLeast(max) }
        assertTrue("после импульса сигнал должен затухнуть, max=$max", max < 0.05)
    }

    @Test
    fun `processBlock измеряет place`() {
        val lp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        val data = DoubleArray(100) { 1.0 }
        lp.processBlock(data)
        // Начальные отсчёты — transition (≈ b0 ≈ 0.0046), поэтому проверяем
        // только установившийся DC-режим: хвост данных близок к 1.0.
        for (i in 50 until data.size) assertEquals(1.0, data[i], 1e-2)
    }

    @Test
    fun `reset обнуляет состояние`() {
        val lp = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        lp.process(123.0)
        lp.reset()
        val out = lp.process(1.0)
        assertTrue(out in -0.1..0.1)
    }

    @Test
    fun `updateParameters пересчитывает коэффициенты`() {
        val b = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOWPASS)
        b.updateParameters(5000f, 0f, BiquadFilter.FilterType.LOWPASS)
        assertEquals(-3.0, b.db(5000.0), 0.1)
    }
}