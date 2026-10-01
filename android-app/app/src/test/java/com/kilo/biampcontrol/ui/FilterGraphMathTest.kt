/*
 * Тесты DSP-математики графика полос.
 *
 * Функции считают АЧХ ровно так же, как прошивка: rbjSection повторяет
 * calcSectionTo, channelResponse повторяет calcChannelFilters. Расхождение
 * между графиком в приложении и тем, что реально играет усилитель, не видно
 * на глаз, поэтому сверяем форму кривой и опорные точки.
 *
 * Проверяется без Android-рантайма: чистые функции, никаких Compose-вызовов.
 */

package com.kilo.biampcontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class FilterGraphMathTest {

    private companion object {
        const val SEC_LOWPASS = 0
        const val SEC_HIGHPASS = 1
        const val SEC_BYPASS = 5
        const val TOL = 1e-6

        /** Перевод отношения мощностей в дБ. */
        fun db(powerRatio: Double): Double = 10.0 * kotlin.math.log10(powerRatio)
    }

    // ── Секции RBJ ────────────────────────────────────────────────

    @Test
    fun `bypass не меняет сигнал`() {
        val s = rbjSection(SEC_BYPASS, 0.0)
        assertTrue(s.flat)
        assertEquals(1.0, s.powerRatio(1000.0), TOL)
        assertEquals(0.0, s.phaseDeg(1000.0), TOL)
    }

    @Test
    fun `неизвестный тип секции отвергается`() {
        val e = runCatching { rbjSection(9, 1000.0) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `ФНЧ даёт минус три дБ на частоте среза`() {
        val lp = rbjSection(SEC_LOWPASS, 1000.0)
        assertEquals(-3.0, db(lp.powerRatio(1000.0)), 0.1)
    }

    @Test
    fun `ФВЧ даёт минус три дБ на частоте среза`() {
        val hp = rbjSection(SEC_HIGHPASS, 1000.0)
        assertEquals(-3.0, db(hp.powerRatio(1000.0)), 0.1)
    }

    @Test
    fun `ФНЧ пропускает низкие и гасит высокие`() {
        val lp = rbjSection(SEC_LOWPASS, 1000.0)
        assertEquals(0.0, db(lp.powerRatio(20.0)), 0.1)
        assertTrue(db(lp.powerRatio(10000.0)) < -20.0)
    }

    @Test
    fun `ФВЧ пропускает высокие и гасит низкие`() {
        val hp = rbjSection(SEC_HIGHPASS, 1000.0)
        assertEquals(0.0, db(hp.powerRatio(20000.0)), 0.1)
        assertTrue(db(hp.powerRatio(20.0)) < -20.0)
    }

    @Test
    fun `вне полосы ФНЧ даёт наклон 12 дБ на октаву`() {
        // db() возвращает 10*log10(|H|^2), то есть амплитуду в дБ: на частоте
        // среза это -3 дБ, а вдали наклон 12 дБ/окт у фильтра второго порядка.
        val lp = rbjSection(SEC_LOWPASS, 1000.0)
        // Замер берём на 2·fc и 4·fc, а не у Найквиста: билинейное
        // преобразование уводит нуль передаточной функции в бесконечность, и
        // у самого края спектра затухание не линейно по октавам — там оно
        // круче. Проверять наклон нужно на аналоговом участке.
        val at2x = db(lp.powerRatio(2000.0))
        val at4x = db(lp.powerRatio(4000.0))
        // Разница положительна: чем выше частота, тем глубже затухание.
        assertEquals(12.0, at2x - at4x, 1.5)
    }

    // ── Отклик канала ──────────────────────────────────────────────

    @Test
    fun `канал без фильтров плоский`() {
        val r = channelResponse(0, 0)
        for (f in listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)) {
            assertEquals(0.0, r.db(f), 1e-9)
            assertEquals(0.0, r.phase(f), 1e-9)
        }
    }

    @Test
    fun `граница полосы даёт минус три дБ с каждой стороны`() {
        val r = channelResponse(100, 2000)
        assertEquals(-3.0, r.db(100.0), 0.1)
        assertEquals(-3.0, r.db(2000.0), 0.1)
    }

    @Test
    fun `внутри полосы сигнал идёт почти без потерь`() {
        val r = channelResponse(100, 2000)
        // Не ноль: на 1000 Гц ФВЧ на 100 Гц и ФНЧ на 2000 Гц дают суммарно
        // около -0.26 дБ. Ноль здесь означал бы, что каскад посчитан неверно.
        val mid = r.db(1000.0)
        assertTrue("середина полосы должна быть почти 0 дБ, а не $mid", mid in -0.5..0.0)
    }

    @Test
    fun `вне полосы сигнал гасится с обеих сторон`() {
        val r = channelResponse(100, 2000)
        assertTrue(r.db(20.0) < -20.0)
        assertTrue(r.db(20000.0) < -20.0)
    }

    @Test
    fun `уровень полосы сдвигает всю кривую`() {
        val flat = channelResponse(0, 0, 0.0)
        val plus3 = channelResponse(0, 0, 3.0)
        val minus6 = channelResponse(0, 0, -6.0)
        for (f in listOf(50.0, 500.0, 5000.0)) {
            assertEquals(3.0, plus3.db(f) - flat.db(f), 1e-9)
            assertEquals(-6.0, minus6.db(f) - flat.db(f), 1e-9)
        }
    }

    @Test
    fun `уровень полосы не меняет форму кривой`() {
        val a = channelResponse(100, 2000, -3.0)
        val b = channelResponse(100, 2000, 2.0)
        for (f in listOf(30.0, 300.0, 3000.0)) {
            assertEquals(a.db(f) - b.db(f), -5.0, 1e-9)
        }
    }

    @Test
    fun `фильтр ниже минимума прошивки считается выключенным`() {
        // Прошивка включает секцию только при ch >= 20 Гц, всё остальное — bypass.
        assertTrue(channelResponse(19, 0).hp.flat)
        assertTrue(channelResponse(0, 19).lp.flat)
        assertTrue(!channelResponse(20, 0).hp.flat)
    }

    @Test
    fun `каскад двух секций совпадает с произведением их откликов`() {
        val r = channelResponse(100, 2000)
        val hp = rbjSection(SEC_HIGHPASS, 100.0)
        val lp = rbjSection(SEC_LOWPASS, 2000.0)
        for (f in listOf(20.0, 100.0, 1000.0, 2000.0, 20000.0)) {
            assertEquals(db(hp.powerRatio(f)) + db(lp.powerRatio(f)), r.db(f), 1e-9)
        }
    }

    // ── Ступени и подписи ──────────────────────────────────────────

    @Test
    fun `ступени частоты строго возрастают`() {
        for (i in 1 until FILTER_STEPS.size) {
            assertTrue(
                "ступень $i должна быть выше предыдущей",
                FILTER_STEPS[i] > FILTER_STEPS[i - 1]
            )
        }
    }

    @Test
    fun `ступени идут по сетке трети октавы`() {
        assertEquals(MIN_FILTER_HZ, FILTER_STEPS.first())
        assertEquals(20000, FILTER_STEPS.last())
        // Ступени — округлённые привилегированные числа, а не точная геометрия,
        // поэтому отношение соседних ступеней 31/40 = 1.29, а не 2^(1/3) = 1.26.
        // Проверяем отклонение от идеальной сетки, а не отношение соседей.
        for (i in FILTER_STEPS.indices) {
            val ideal = MIN_FILTER_HZ * 2.0.pow(i / 3.0)
            val actual = FILTER_STEPS[i].toDouble()
            val deviation = abs(actual - ideal) / ideal
            assertTrue(
                "ступень ${FILTER_STEPS[i]} отходит от сетки на $deviation",
                deviation <= 0.04
            )
        }
    }

    @Test
    fun `ближайшая ступень округляет по таблице`() {
        assertEquals(0, nearestStep(0))
        assertEquals(0, nearestStep(19))
        assertEquals(20, nearestStep(20))
        assertEquals(20, nearestStep(22))
        assertEquals(25, nearestStep(27))
        assertEquals(125, nearestStep(130))
        assertEquals(1250, nearestStep(1300))
        assertEquals(20000, nearestStep(19999))
    }

    @Test
    fun `округление до ступени всегда попадает в таблицу`() {
        for (hz in 20..20000 step 7) {
            val s = nearestStep(hz)
            assertTrue("ступень $s отсутствует в таблице", FILTER_STEPS.contains(s))
        }
    }

    /**
     * Единицы измерения передаются явно: функция чистая и проверяется без
     * Android-рантайма, а локализованные подписи подставляет композабла.
     * Русские подписи берём из strings.xml вручную — так тест остаётся
     * независимым от ресурсов.
     */
    private fun freq(hz: Int) = freqValueText(hz, "выкл", "Гц", "кГц")

    @Test
    fun `подпись частоты совпадает со спекой`() {
        assertEquals("выкл", freq(0))
        assertEquals("80Гц", freq(80))
        assertEquals("999Гц", freq(999))
        assertEquals("1кГц", freq(1000))
        assertEquals("1.25кГц", freq(1250))
        assertEquals("1.6кГц", freq(1600))
        assertEquals("2кГц", freq(2000))
        assertEquals("20кГц", freq(20000))
    }

    /** Английские единицы дают те же числа: проверяется сборка подписи, не язык. */
    @Test
    fun `подпись частоты собирается из переданных единиц`() {
        assertEquals("off", freqValueText(0, "off", "Hz", "kHz"))
        assertEquals("80Hz", freqValueText(80, "off", "Hz", "kHz"))
        assertEquals("1.25kHz", freqValueText(1250, "off", "Hz", "kHz"))
        assertEquals("20kHz", freqValueText(20000, "off", "Hz", "kHz"))
    }

    @Test
    fun `подписи ступеней не склеиваются`() {
        // 1600 и 2000 обязаны различаться, иначе соседние ступени неразличимы.
        assertTrue(freq(1600) != freq(2000))
        assertTrue(freq(1250) != freq(1600))
    }

    @Test
    fun `уровень округляется до целого децибела`() {
        assertEquals(-6f, levelSteps(-5.6f), 1e-6f)
        assertEquals(-5f, levelSteps(-5.4f), 1e-6f)
        assertEquals(0f, levelSteps(0.2f), 1e-6f)
        assertEquals(3f, levelSteps(2.6f), 1e-6f)
    }

    @Test
    fun `уровень упирается в границы диапазона`() {
        assertEquals(LEVEL_DB_MIN, levelSteps(-12f), 1e-6f)
        assertEquals(LEVEL_DB_MAX, levelSteps(9f), 1e-6f)
        assertEquals(LEVEL_DB_MIN, levelSteps(LEVEL_DB_MIN), 1e-6f)
        assertEquals(LEVEL_DB_MAX, levelSteps(LEVEL_DB_MAX), 1e-6f)
    }

    @Test
    fun `подпись уровня без дробной части`() {
        assertEquals("0", dbText(0f))
        assertEquals("-2", dbText(-2f))
        assertEquals("3", dbText(3f))
        assertEquals("-6", dbText(-6f))
    }

    @Test
    fun `подпись уровня с дробной частью не теряется`() {
        assertEquals("1.5", dbText(1.5f))
    }
}
