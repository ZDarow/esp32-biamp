/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Геометрия и шкала крутилки вынесены отдельно от отрисовки: это чистые
 * функции без Compose-вызовов, поэтому их можно проверить обычным тестом.
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

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Размах дуги шкалы в градусах: столько занимает рабочий диапазон. */
internal const val DIAL_SWEEP = 270f

/**
 * Начало дуги в градусах системы координат Canvas: 135° — это вниз-влево.
 *
 * Дуга идёт по часовой стрелке от этого положения на 270°: вверх по левой
 * стороне, через верх, вниз по правой и обрывается на 45°, то есть разрыв
 * приходится на низ. Так «нулевое» положение ручки видно снизу, а сама
 * дуга не проходит под пальцем, когда он смотрит на шкалу.
 */
internal const val DIAL_START_ANGLE = 135f

/** Сколько миллисекунд палец должен простоять на месте, чтобы включился точный режим. */
internal const val DIAL_FINE_HOLD_MS = 1000L

/** Во сколько раз точный режим замедляет изменение значения. */
internal const val DIAL_FINE_GAIN = 0.2f

/** Окно двойного тапа, мс: второе касание позже — считается одиночным тапом. */
internal const val DIAL_DOUBLE_TAP_MS = 300L

/**
 * Какая доля диаметра при вертикальном сдвиге проходит весь диапазон.
 *
 * Меньше единицы означает «чувствительнее»: диапазон 20–80 Гц проходится
 * быстрее, а тонкая подстройка делается удержанием. Единица означала бы,
 * что весь диапазон требует сдвига пальцем через диаметр ручки — слишком
 * мелкий ход, на слух его не выставить.
 */
internal const val DIAL_VERTICAL_SPAN_FRACTION = 0.9f

/**
 * Положение точки [p] на шкале в градусах дуги: 0° — начало диапазона.
 *
 * Отсчёт отсюда — тот же, что у [DIAL_START_ANGLE] в Canvas: угол дуги
 * равен углу Canvas минус начало, так что положение стрелки и положение
 * пальца читаются одинаково. Возвращаемое значение лежит в [0, 360) и
 * намеренно не обрезается по [DIAL_SWEEP]: при повороте пальца по кругу
 * переход через разрыв обязан давать непрерывную дельту, иначе ручка
 * прыгает на середину диапазона.
 */
internal fun dialAngleAt(p: Offset, side: Float): Float {
    val c = side / 2f
    val deg = Math.toDegrees(
        atan2((p.y - c).toDouble(), (p.x - c).toDouble())
    ).toFloat()
    var a = (deg - DIAL_START_ANGLE) % 360f
    if (a < 0f) a += 360f
    return a
}

/**
 * Значение, соответствующее углу [angle], без обрезания по диапазону.
 *
 * Отсутствие обрезания нужно при относительном повороте: палец может
 * увести ручку за конец дуги, и тогда при возврате к прежнему положению
 * значение должно восстановиться, а не залипнуть на границе.
 */
internal fun dialValueForAngle(angle: Float, range: ClosedFloatingPointRange<Float>): Float =
    range.start + (angle / DIAL_SWEEP) * (range.endInclusive - range.start)

/** Приращение угла от опорного положения до текущего, в пределах ±180°. */
internal fun dialAngleDelta(angle: Float, reference: Float): Float {
    var d = (angle - reference) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Обрезка значения по диапазону: за его пределами усилитель не примет команду. */
internal fun dialClamp(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    value.coerceIn(range.start, range.endInclusive)

/**
 * Приведение значения к ближайшему делению шкалы.
 *
 * При [step] = 0 (плавное значение) возвращается [value] без изменений.
 */
internal fun dialSnap(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return value
    val snapped = range.start + ((value - range.start) / step).roundToInt() * step
    return dialClamp(snapped, range)
}

/** Номер деления шкалы, ближайшего к [value]: для тактильного отклика при повороте. */
internal fun dialTickIndex(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Int =
    if (step <= 0f) 0 else ((value - range.start) / step).roundToInt()

/**
 * Значение из вертикального сдвига [dy] в пикселях.
 *
 * Вверх увеличивает значение — так же, как вверх идёт шкала на дуге, поэтому
 * два способа вращения не тянут ручку в разные стороны. Сдвиг на
 * [DIAL_VERTICAL_SPAN_FRACTION] диаметра проходит весь диапазон, дальше идёт
 * обрезка.
 */
internal fun dialVerticalValue(
    dy: Float,
    side: Float,
    span: Float,
    gain: Float
): Float = -dy / (side * DIAL_VERTICAL_SPAN_FRACTION) * span * gain

/** Мёртвая зона тапа: попадание в центр ручки ничего не меняет. */
internal fun dialIsCenterTap(p: Offset, side: Float, radius: Float): Boolean =
    (p - Offset(side / 2f, side / 2f)).getDistance() <= radius