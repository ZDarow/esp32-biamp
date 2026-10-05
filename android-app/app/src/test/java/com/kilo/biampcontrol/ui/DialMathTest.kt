/*
 * Тесты геометрии крутилки.
 *
 * Функции вынесены из Dial.kt именно потому, что их можно проверить без
 * Android-рантайма: угол шкалы, приращение при повороте и вертикальном
 * сдвиге — это ровно то, где ошибка выглядит как «ручка едет не туда»,
 * и на глаз в Compose это не отловить.
 *
 * Геометрия дуги повторяет рисование в Canvas: начало 135°, размах 270°,
 * ось Y направлена вниз.
 */

package com.kilo.biampcontrol.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class DialMathTest {

    private companion object {
        const val SIDE = 264f
        val CENTER = Offset(SIDE / 2f, SIDE / 2f)
        val XO = 200f..1000f

        /** Точка на окружности радиуса [r] под углом дуги [angle]°. */
        fun point(angle: Float, r: Float = SIDE / 2f): Offset {
            // Угол дуги отсчитывается от DIAL_START_ANGLE в системе Canvas
            // так же, как рисуется дуга: иначе палец и стрелка окажутся
            // по разные стороны шкалы.
            val rad = Math.toRadians((DIAL_START_ANGLE + angle).toDouble())
            return Offset(
                CENTER.x + kotlin.math.cos(rad).toFloat() * r,
                CENTER.y + kotlin.math.sin(rad).toFloat() * r
            )
        }
    }

    // ── Угол шкалы ─────────────────────────────────────────────────

    @Test
    fun `начало дуги соответствует минимуму диапазона`() {
        val at = point(0f)
        assertEquals(0f, dialAngleAt(at, SIDE), 0.5f)
        assertEquals(XO.start, dialValueForAngle(dialAngleAt(at, SIDE), XO), 0.5f)
    }

    @Test
    fun `конец дуги соответствует максимуму диапазона`() {
        val at = point(DIAL_SWEEP)
        assertEquals(DIAL_SWEEP, dialAngleAt(at, SIDE), 0.5f)
        assertEquals(XO.endInclusive, dialValueForAngle(DIAL_SWEEP, XO), 0.5f)
    }

    @Test
    fun `середина дуги соответствует середине диапазона`() {
        val at = point(DIAL_SWEEP / 2f)
        assertEquals(600f, dialValueForAngle(dialAngleAt(at, SIDE), XO), 1f)
    }

    @Test
    fun `разрыв дуги внизу не прыгает к середине диапазона`() {
        // Разрыв приходится на низ дуги (между 270° и 360°): точка под
        // центром туда попадает, и угол там продолжает расти, а не
        // возвращается к середине шкалы.
        val below = Offset(CENTER.x, CENTER.y + SIDE / 2f)
        val belowAngle = dialAngleAt(below, SIDE)
        assertTrue("угол под центром $belowAngle вне дуги", belowAngle > DIAL_SWEEP)

        // Дельта через разрыв обязана быть положительной и небольшой —
        // именно это отличает настоящую дугу от обрезанной по кругу.
        val across = dialAngleDelta(dialAngleAt(point(350f), SIDE), dialAngleAt(point(280f), SIDE))
        assertTrue("дельта через разрыв $across", across in 1f..90f)
        val wrapped = dialAngleDelta(dialAngleAt(point(10f), SIDE), dialAngleAt(point(350f), SIDE))
        assertTrue("дельта через ноль $wrapped", wrapped in 1f..90f)
    }

    @Test
    fun `стрелка и палец читаются по одной и той же шкале`() {
        // Расхождение на пол-оборота — самый дорогой вид ошибки здесь:
        // ручка нарисована в одном месте, а палец и тап считают другое,
        // и по экрану это не видно. Проверяем, что середина дуги, конец
        // дуги и её физическое положение совпадают с рисованием.
        assertEquals(0f, dialAngleAt(point(0f), SIDE), 0.5f)
        assertEquals(90f, dialAngleAt(point(90f), SIDE), 0.5f)
        assertEquals(135f, dialAngleAt(point(135f), SIDE), 0.5f)
        assertEquals(DIAL_SWEEP, dialAngleAt(point(DIAL_SWEEP), SIDE), 0.5f)
        // Начало дуги — вниз-влево, конец — вниз-вправо, разрыв — внизу.
        assertTrue(point(0f).x < CENTER.x && point(0f).y > CENTER.y)
        assertTrue(point(DIAL_SWEEP).x > CENTER.x && point(DIAL_SWEEP).y > CENTER.y)
        assertEquals(135f, dialAngleAt(Offset(CENTER.x, CENTER.y - SIDE / 2f), SIDE), 0.5f)
    }

    // ── Приращения ────────────────────────────────────────────────

    @Test
    fun `поворот на четверть дуги берёт четверть диапазона`() {
        val span = XO.endInclusive - XO.start
        val d = dialAngleDelta(dialAngleAt(point(67.5f), SIDE), dialAngleAt(point(0f), SIDE))
        assertEquals(span / 4f, d / DIAL_SWEEP * span, 1f)
    }

    @Test
    fun `обратный поворот даёт отрицательную дельту`() {
        val d = dialAngleDelta(dialAngleAt(point(10f), SIDE), dialAngleAt(point(80f), SIDE))
        assertTrue("дельта должна быть отрицательной, получено $d", d < 0f)
        assertEquals(-70f, d, 0.5f)
    }

    @Test
    fun `дельта не зависит от накопленного угла`() {
        // Пять шагов по 10° подряд дают ту же дельту, что один шаг 50°:
        // иначе на 360° ошибка накапливалась бы в круг оборота.
        val from = dialAngleAt(point(5f), SIDE)
        var acc = from
        for (i in 0 until 5) {
            acc += dialAngleDelta(dialAngleAt(point(15f + i * 10f), SIDE), from + i * 10f)
        }
        assertEquals(50f, acc - from, 1f)
    }

    @Test
    fun `вертикальный ход вверх увеличивает значение`() {
        val span = XO.endInclusive - XO.start
        val up = dialVerticalValue(-SIDE * DIAL_VERTICAL_SPAN_FRACTION, SIDE, span, 1f)
        assertEquals(span, up, 1f)
        assertTrue(dialVerticalValue(SIDE * 0.2f, SIDE, span, 1f) < 0f)
    }

    @Test
    fun `точный режим замедляет оба способа в 5 раз`() {
        val span = XO.endInclusive - XO.start
        assertEquals(
            1f / 5f,
            dialVerticalValue(-100f, SIDE, span, DIAL_FINE_GAIN) /
                dialVerticalValue(-100f, SIDE, span, 1f),
            1e-4f
        )
        val fine = dialAngleDelta(dialAngleAt(point(60f), SIDE), dialAngleAt(point(0f), SIDE)) *
            span / DIAL_SWEEP * DIAL_FINE_GAIN
        val coarse = dialAngleDelta(dialAngleAt(point(60f), SIDE), dialAngleAt(point(0f), SIDE)) *
            span / DIAL_SWEEP
        assertEquals(coarse / 5f, fine, 1f)
    }

    // ── Обрезка и деления ─────────────────────────────────────────

    @Test
    fun `значение обрезается по диапазону`() {
        assertEquals(XO.endInclusive, dialClamp(5000f, XO), 0f)
        assertEquals(XO.start, dialClamp(-5f, XO), 0f)
    }

    @Test
    fun `деление округляет к ближайшему`() {
        // Кроссовер: 200…1000 Гц, 16 делений → шаг ровно 50 Гц.
        val step = (XO.endInclusive - XO.start) / 16f
        assertEquals(400f, dialSnap(410f, XO, step), 0.01f)
        assertEquals(450f, dialSnap(440f, XO, step), 0.01f)
        assertEquals(XO.endInclusive, dialSnap(9999f, XO, step), 0.01f)
        assertEquals(XO.start, dialSnap(-1f, XO, step), 0.01f)
    }

    @Test
    fun `нулевой шаг означает плавное значение`() {
        assertEquals(333.3f, dialSnap(333.3f, XO, 0f), 0.01f)
    }

    @Test
    fun `номер деления меняется на соседний при переходе через середину шага`() {
        val step = (XO.endInclusive - XO.start) / 16f
        assertEquals(dialTickIndex(410f, XO, step), dialTickIndex(420f, XO, step))
        assertEquals(
            dialTickIndex(410f, XO, step) + 1,
            dialTickIndex(430f, XO, step)
        )
    }

    @Test
    fun `сабсоник размечается ровно на 20…80 Гц`() {
        val hp = 20f..80f
        val step = (hp.endInclusive - hp.start) / 12f
        assertEquals(20f, dialSnap(20f, hp, step), 0.01f)
        assertEquals(80f, dialSnap(80f, hp, step), 0.01f)
        // 44 Гц тянутся к 45, а 42 — к 40.
        assertEquals(45f, dialSnap(44f, hp, step), 0.01f)
        assertEquals(40f, dialSnap(42f, hp, step), 0.01f)
    }

    // ── Мёртвая зона тапа ─────────────────────────────────────────

    @Test
    fun `тап в центр ручки игнорируется`() {
        assertTrue(dialIsCenterTap(CENTER, SIDE, SIDE * 0.22f))
        assertTrue(dialIsCenterTap(Offset(CENTER.x + 10f, CENTER.y - 8f), SIDE, SIDE * 0.22f))
        assertFalse(dialIsCenterTap(point(0f), SIDE, SIDE * 0.22f))
        assertFalse(dialIsCenterTap(point(20f, SIDE * 0.3f), SIDE, SIDE * 0.22f))
    }

    @Test
    fun `окно двойного тапа короче обычного времени реакции`() {
        assertTrue(DIAL_DOUBLE_TAP_MS <= 400L)
        assertTrue(DIAL_FINE_HOLD_MS >= 700L)
        assertTrue(DIAL_FINE_GAIN in 0.1f..0.3f)
        assertTrue(DIAL_VERTICAL_SPAN_FRACTION in 0.5f..1.2f)
    }

    @Test
    fun `полный круг оборота по вертикали даёт полный диапазон`() {
        val span = XO.endInclusive - XO.start
        val travel = SIDE * DIAL_VERTICAL_SPAN_FRACTION
        val steps = 10
        var v = XO.start
        for (i in 0 until steps) {
            v += dialVerticalValue(-travel / steps, SIDE, span, 1f)
        }
        assertEquals(XO.endInclusive, dialClamp(v, XO).roundToInt().toFloat(), 1f)
    }
}