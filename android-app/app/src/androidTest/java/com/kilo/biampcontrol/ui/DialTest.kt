/*
 * Проверка жестов крутилки на настоящем устройстве.
 *
 * Математика DialMath проверяется обычным тестом, а вот сами жесты —
 * поворот, вертикальный ход, удержание и двойной тап — живут в
 * pointerInput, и их ошибка видна только пальцем. Здесь они прогоняются
 * настоящими событиями касания: результат сравнивается с тем, что ушло
 * бы на усилитель, а не с тем, что нарисовано.
 *
 * Copyright (C) 2026 ZDarow
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kilo.biampcontrol.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kilo.biampcontrol.ui.theme.BiAmpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DialTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val range = 200f..1000f
    private val diameter = 240.dp

    /** Значение, ушедшее на усилитель: null — команда не отправлялась. */
    private var sent: Float? = null

    /** Значение, на котором ручка стоит «в эфире», — источник истины как на ESP32. */
    private val device = mutableStateOf(400f)

    /** Диаметр ручки в пикселях экрана: жесты задаются в пикселях узла. */
    private var sidePx = 0f

    private fun start(enabled: Boolean = true, default: Float? = 400f) {
        sent = null
        compose.setContent {
            BiAmpTheme {
                Surface {
                    Dial(
                        value = device.value,
                        valueRange = range,
                        onValueChangeFinished = { sent = it },
                        enabled = enabled,
                        steps = 15,
                        diameter = diameter,
                        label = "Fc",
                        valueText = "${device.value.roundToInt()} Гц",
                        defaultValue = default
                    )
                }
            }
        }
        compose.waitForIdle()
        sidePx = with(compose.density) { diameter.toPx() }
    }

    private fun dial() = compose.onNodeWithContentDescription("Fc")

    /** Точка на окружности ручки под углом Canvas [deg]. */
    private fun onArc(deg: Float, radius: Float = sidePx / 2f): Offset =
        Offset(
            sidePx / 2f + cos(Math.toRadians(deg.toDouble())).toFloat() * radius,
            sidePx / 2f + sin(Math.toRadians(deg.toDouble())).toFloat() * radius
        )

    // ── Поворот ───────────────────────────────────────────────────

    @Test
    fun поворотПоДугеМеняетЗначениеИОтправляетКомандуПоОтпусканию() {
        start()
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE))
            moveTo(onArc(DIAL_START_ANGLE + 90f))
            assertNull("команда не должна уходить до отпускания", sent)
            up()
        }
        compose.waitForIdle()
        val value = requireNotNull(sent) { "поворот не отправил команду" }
        // Четверть оборота шкалы — треть диапазона 200…1000 Гц, но деления
        // идут по 50 Гц, поэтому ответ округляется к ближайшему: 400 + 267 ≈ 650.
        assertEquals(650f, value, 15f)
    }

    @Test
    fun обратныйПоворотУменьшаетЗначение() {
        start()
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE + 90f))
            moveTo(onArc(DIAL_START_ANGLE))
            up()
        }
        compose.waitForIdle()
        assertEquals(200f, requireNotNull(sent), 15f)
    }

    // ── Вертикальный ход ──────────────────────────────────────────

    @Test
    fun вертикальныйХодВверхУвеличиваетЗначение() {
        start()
        dial().performTouchInput {
            down(Offset(sidePx / 2f, sidePx / 2f + sidePx * 0.25f))
            moveTo(Offset(sidePx / 2f, sidePx / 2f + sidePx * 0.25f - sidePx * 0.3f))
            up()
        }
        compose.waitForIdle()
        assertTrue("значение должно вырасти: ${requireNotNull(sent)}",
            requireNotNull(sent) > 400f)
    }

    @Test
    fun вертикальныйХодОграниченДиапазоном() {
        start()
        dial().performTouchInput {
            down(Offset(sidePx / 2f, sidePx / 2f + sidePx * 0.2f))
            // Далеко за верхним пределом: команда всё равно не выйдет за 1000 Гц.
            moveTo(Offset(sidePx / 2f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1000f, requireNotNull(sent), 1f)
    }

    // ── Удержание: точный режим ───────────────────────────────────

    @Test
    fun удержаниеЗамедляИзменениеВПятьРаз() {
        start()
        val angle = DIAL_START_ANGLE + 60f
        // Палец стоит на месте дольше порога точного режима.
        dial().performTouchInput {
            down(onArc(angle))
            advanceEventTime(DIAL_FINE_HOLD_MS + 200)
            moveTo(onArc(angle + 20f))
            up()
        }
        compose.waitForIdle()
        val fine = requireNotNull(sent) { "удержание не отправило команду" }
        assertTrue("точный режим должен сдвинуть значение, а не стоять: $fine", fine > 405f)
        assertTrue("точный режим слишком чувствителен: $fine", fine < 450f)
    }

    @Test
    fun безУдержанияШагКрупный() {
        start()
        val angle = DIAL_START_ANGLE + 60f
        dial().performTouchInput {
            down(onArc(angle))
            moveTo(onArc(angle + 20f))
            up()
        }
        compose.waitForIdle()
        assertTrue("без удержания ход должен быть крупным: ${requireNotNull(sent)}",
            requireNotNull(sent) >= 440f)
    }

    // ── Тапы ──────────────────────────────────────────────────────

    @Test
    fun тапПоДугеСтавитЗначениеВТочку() {
        start()
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE + DIAL_SWEEP / 2f))
            up()
        }
        compose.waitForIdle()
        assertEquals(600f, requireNotNull(sent), 20f)
    }

    @Test
    fun тапВЦентреНичегоНеМеняет() {
        start()
        dial().performTouchInput {
            down(Offset(sidePx / 2f, sidePx / 2f))
            up()
        }
        compose.waitForIdle()
        assertNull("тап в центр не должен отправлять команду", sent)
    }

    @Test
    fun двойнойТапВозвращаетЗаводскоеЗначение() {
        start(default = 400f)
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE + DIAL_SWEEP))
            up()
            advanceEventTime(80)
            down(onArc(DIAL_START_ANGLE + DIAL_SWEEP))
            up()
        }
        compose.waitForIdle()
        assertEquals(400f, requireNotNull(sent), 1f)
    }

    @Test
    fun безЗаводскогоЗначенияДвойнойТапНеСрабатывает() {
        start(default = null)
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE + DIAL_SWEEP))
            up()
            advanceEventTime(80)
            down(onArc(DIAL_START_ANGLE + DIAL_SWEEP))
            up()
        }
        compose.waitForIdle()
        assertEquals("второй тап остаётся обычным", 1000f, requireNotNull(sent), 1f)
    }

    // ── Заблокированная ручка ─────────────────────────────────────

    @Test
    fun заблокированнаяРучкаИгнорируетЖесты() {
        start(enabled = false)
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE))
            moveTo(onArc(DIAL_START_ANGLE + 90f))
            up()
        }
        compose.waitForIdle()
        assertNull("заблокированная ручка не должна слать команду", sent)
    }

    @Test
    fun внешнееЗначениеПобеждаетЛокальноеПоложение() {
        start()
        device.value = 700f
        compose.waitForIdle()
        dial().performTouchInput {
            down(onArc(DIAL_START_ANGLE))
            moveTo(onArc(DIAL_START_ANGLE + 90f))
            up()
        }
        compose.waitForIdle()
        // Поворот идёт от подтверждённого усилителем 700 Гц, а не от прежних
        // 400: 700 + 267 ≈ 950, а от 400 получилось бы 650.
        assertTrue(
            "команда должна считаться от 700 Гц, а не от 400: ${sent}",
            requireNotNull(sent) > 900f
        )
    }
}