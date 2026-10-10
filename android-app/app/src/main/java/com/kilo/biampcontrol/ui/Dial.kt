/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth SPP: приложение открывает RFCOMM-сокет к
 * ESP32 ("ESP32 BiAmp Speaker") и обменивается текстовыми командами.
 * Формат команд и ответов — в firmware/DOCUMENTATION.md, раздел 4.
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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Смещение указателя жеста: поворот по кругу либо вертикальный ход. */
private const val MODE_ROTATE = 1
private const val MODE_VERTICAL = 2

/**
 * Радиус, на котором ручка считается «за кольцо»: дальше от центра — поворот,
 * ближе — вертикальный ход. Доля от радиуса, а не абсолютные пиксели.
 */
private const val RING_ZONE = 0.45f

/**
 * Круглая ручка набора значения.
 *
 * Заменяет горизонтальный ползунок там, где крутилка уместнее: у кроссовера
 * и сабсоника это основные настройки, их удобнее выставлять пальцем по шкале,
 * а не тянуть узкий трек.
 *
 * Дуга занимает 270° с разрывом внизу, чтобы было видно начало и конец
 * диапазона. Значение уходит на усилитель только по окончании поворота или
 * при отпускании, а не на каждом кадре: команда `fc:`/`hp:` идёт по Bluetooth,
 * и поток таких команд только забьёт очередь, а слышно будет последнюю.
 *
 * Способов выставить значение два, и оба молчаливые:
 *  * поворот — палец берёт ручку за кольцо (ближе к краю) и ведёт по дуге,
 *    значение меняется на дельту угла;
 *  * вертикальный ход — палец берёт ручку за середину и тянет вверх-вниз,
 *    диапазон проходит быстрее.
 *
 * Способ выбирается по месту касания, а не по направлению первого
 * движения: круговое вращение почти всегда идёт «вверх», и по направлению
 * ручка на полпути путала бы поворот с вертикальным ходом.
 *
 * Удержание пальца на месте [DIAL_FINE_HOLD_MS] включает точный режим:
 * чувствительность падает в [DIAL_FINE_GAIN] раз, деления перестают
 * щёлкать, а игла и внутреннее кольцо меняют цвет. Это то, чем частоты
 * и уровни заводятся на слух, — одним касанием такой шаг не сделать.
 * Двойной тап возвращает [defaultValue], если он задан.
 *
 * @param steps число промежутков между делениями; 0 — плавное значение.
 * @param defaultValue значение по двойному тапу; null — сброса нет.
 * @param valueText подпись значения в центре ручки; null — берётся
 *   [stateText], если он есть, иначе центр пуст.
 */
@Composable
fun Dial(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    stateText: String? = null,
    enabled: Boolean = true,
    steps: Int = 0,
    diameter: Dp = 116.dp,
    defaultValue: Float? = null,
    valueText: String? = null
) {
    val span = valueRange.endInclusive - valueRange.start
    val step = if (steps > 0) span / (steps + 1) else 0f

    var pos by remember { mutableFloatStateOf(value) }
    // Внешнее значение — источник истины: ответ усилителя должен побеждать
    // локальное положение, иначе ручка залипает на неотправленном значении.
    LaunchedEffect(value) { pos = value }

    // Точный режим рисуется иначе, поэтому его состояние живёт в композиции,
    // а не внутри жеста: палец держит, а ручка уже показывает, что включено.
    var fine by remember { mutableStateOf(false) }

    // Пружина сглаживает внешние изменения и сброс по двойному тапу; во время
    // собственного жеста цель движется вместе с пальцем, поэтому ручка
    // остаётся «приклеенной», без ощутимого запаздывания.
    val shown by animateFloatAsState(
        targetValue = pos,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 1200f),
        label = "dial"
    )

    // Размер в пикселях нужен для расчёта угла: координаты жестов приходят
    // в пикселях, а диаметр задан в dp.
    var sidePx by remember { mutableIntStateOf(0) }

    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.primary
    val fineColor = MaterialTheme.colorScheme.tertiary
    val tickPassed = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val tickIdle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val readout = valueText ?: stateText
    // Цвета читаются здесь, а не в Canvas: лямбда отрисовки не композабла,
    // и обращение к теме из неё не компилируется.
    val readoutColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.outline
    val fineRing = fineColor.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .size(diameter)
            .onSizeChanged { sidePx = it.width }
            .semantics {
                if (!label.isNullOrEmpty()) contentDescription = label
                if (!stateText.isNullOrEmpty()) stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(pos, valueRange)
            }
            .pointerInput(enabled, sidePx, valueRange, step, defaultValue) {
                if (!enabled || sidePx <= 0) return@pointerInput
                val slop = viewConfiguration.touchSlop
                // Порог «палец стоит»: заметно меньше порога начала жеста,
                // иначе удержание включалось бы слишком поздно, когда палец
                // уже сдвинулся.
                val still = slop * 0.25f
                var lastTapMs = 0L
                var lastTapAt = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val side = sidePx.toFloat()
                    var prevPos = down.position
                    var prevMs = down.uptimeMillis
                    var stillMs = 0L
                    var fineHere = false
                    var dragging = false
                    // Способ выбирается один раз — по месту касания, а не по
                    // направлению первого движения: круговое вращение palmой
                    // почти всегда идёт «вверх», и по направлению ручка
                    // путала бы поворот с вертикальным ходом на полпути.
                    val mode = if ((down.position - Offset(side / 2f, side / 2f)).getDistance() >=
                        side * RING_ZONE
                    ) MODE_ROTATE else MODE_VERTICAL
                    var refAngle = dialAngleAt(down.position, side)
                    val refY = down.position.y
                    var refValue = pos
                    var lastTick = dialTickIndex(pos, valueRange, step)
                    var releasePos = down.position
                    var releaseMs = down.uptimeMillis

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            if (change != null) {
                                releasePos = change.position
                                releaseMs = change.uptimeMillis
                            }
                            break
                        }
                        // Точный режим включается по простою пальца. Пауза
                        // считается по разрыву между событиями: неподвижный
                        // палец не порождает их вовсе, поэтому накопить время
                        // покоя по самим событиям нельзя — иначе удержание
                        // не включалось бы никогда. Проверка идёт до учёта
                        // текущего смещения: первый жест после удержания уже
                        // должен быть точным.
                        val gap = change.uptimeMillis - prevMs
                        if (!fineHere && stillMs + gap >= DIAL_FINE_HOLD_MS) {
                            fineHere = true
                            fine = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        stillMs = if ((change.position - prevPos).getDistance() > still) 0L
                        else stillMs + gap
                        prevPos = change.position
                        prevMs = change.uptimeMillis

                        val moved = (change.position - down.position).getDistance()
                        if (!dragging && moved > slop) dragging = true
                        if (dragging) {
                            change.consume()
                            val gain = if (fineHere) DIAL_FINE_GAIN else 1f
                            val raw = if (mode == MODE_VERTICAL) {
                                refValue + dialVerticalValue(
                                    change.position.y - refY, side, span, gain
                                )
                            } else {
                                val a = dialAngleAt(change.position, side)
                                val delta = dialAngleDelta(a, refAngle)
                                refAngle = a
                                refValue + delta / DIAL_SWEEP * span * gain
                            }
                            // В точном режиме деления не щёлкают: иначе
                            // половина точного хода съедалась бы округлением
                            // до соседнего деления.
                            val next = dialClamp(
                                if (fineHere) raw else dialSnap(raw, valueRange, step),
                                valueRange
                            )
                            pos = next
                            refValue = next
                            if (step > 0f) {
                                val tick = dialTickIndex(next, valueRange, step)
                                if (tick != lastTick) {
                                    lastTick = tick
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                }
                            }
                        }
                    }

                    fine = false
                    // Тап. Второй тап подряд — возврат к исходному значению.
                    val doubleTap = defaultValue != null && lastTapMs > 0L &&
                        releaseMs - lastTapMs <= DIAL_DOUBLE_TAP_MS &&
                        (releasePos - lastTapAt).getDistance() <= slop * 2f
                    when {
                        dragging -> onValueChangeFinished(pos)
                        doubleTap -> {
                            lastTapMs = 0L
                            pos = dialClamp(defaultValue, valueRange)
                            onValueChangeFinished(pos)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        else -> {
                            lastTapMs = releaseMs
                            lastTapAt = releasePos
                            // Центр ручки — мёртвая зона: палец попадает туда
                            // неточно, и установка значения в произвольную
                            // точку сдвинула бы кроссовер на слух.
                            if (!dialIsCenterTap(releasePos, side, side * 0.22f)) {
                                pos = dialClamp(
                                    dialValueForAngle(dialAngleAt(releasePos, side), valueRange),
                                    valueRange
                                )
                                onValueChangeFinished(pos)
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.size(diameter)) {
            val side = size.minDimension
            val stroke = side * 0.075f
            val inset = stroke / 2f
            val arcSize = Size(side - stroke, side - stroke)
            val cx = side / 2f
            val cy = side / 2f
            val frac = if (span <= 0f) 0f
            else ((shown - valueRange.start) / span).coerceIn(0f, 1f)

            drawArc(
                color = track,
                startAngle = DIAL_START_ANGLE,
                sweepAngle = DIAL_SWEEP,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = if (enabled) arc else track,
                startAngle = DIAL_START_ANGLE,
                sweepAngle = DIAL_SWEEP * frac,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            if (step > 0f && steps > 0) {
                // Засечки на внешнем крае дуги: у пройденной части цвет
                // активный, у остальной — приглушённый, поэтому по шкале
                // видно, сколько ещё осталось до конца диапазона.
                val tickRadius = side / 2f - stroke * 1.2f
                val tickLength = side * 0.045f
                val tickWidth = side * 0.014f
                for (i in 0..steps) {
                    val tf = i / steps.toFloat()
                    val rad = Math.toRadians((DIAL_START_ANGLE + DIAL_SWEEP * tf).toDouble())
                    val cs = cos(rad).toFloat()
                    val sn = sin(rad).toFloat()
                    drawLine(
                        color = if (tf <= frac) tickPassed else tickIdle,
                        start = Offset(
                            cx + cs * (tickRadius - tickLength / 2f),
                            cy + sn * (tickRadius - tickLength / 2f)
                        ),
                        end = Offset(
                            cx + cs * (tickRadius + tickLength / 2f),
                            cy + sn * (tickRadius + tickLength / 2f)
                        ),
                        strokeWidth = tickWidth,
                        cap = StrokeCap.Round
                    )
                }
            }

            val needle = when {
                !enabled -> track
                fine -> fineColor
                else -> arc
            }
            val rad = Math.toRadians((DIAL_START_ANGLE + DIAL_SWEEP * frac).toDouble())
            val tail = side * 0.15f
            val tip = side * if (fine) 0.34f else 0.40f
            drawLine(
                color = needle,
                start = Offset(
                    cx + cos(rad).toFloat() * tail,
                    cy + sin(rad).toFloat() * tail
                ),
                end = Offset(
                    cx + cos(rad).toFloat() * tip,
                    cy + sin(rad).toFloat() * tip
                ),
                strokeWidth = side * if (fine) 0.06f else 0.085f,
                cap = StrokeCap.Round
            )

            if (readout.isNullOrEmpty()) {
                drawCircle(color = needle, radius = side * 0.05f, center = Offset(cx, cy))
            } else {
                // Тонкое кольцо внутри дуги — метка точного режима: без
                // изменённого цвета иглы его не отличить от обычного.
                if (fine) {
                    drawCircle(
                        color = fineRing,
                        radius = side * 0.30f,
                        center = Offset(cx, cy),
                        style = Stroke(width = side * 0.012f)
                    )
                }
                val layout = textMeasurer.measure(
                    readout,
                    TextStyle(
                        fontSize = (side * 0.155f).toSp(),
                        fontWeight = FontWeight.Medium
                    )
                )
                // Длинная подпись («350 Гц») не должна вылезать за дугу,
                // поэтому она ужимается под ширину внутреннего круга.
                val fit = min(
                    1f,
                    min(side * 0.60f / layout.size.width, side * 0.34f / layout.size.height)
                )
                withTransform({ scale(fit, fit, Offset(cx, cy)) }) {
                    drawText(
                        textLayoutResult = layout,
                        color = readoutColor,
                        topLeft = Offset(
                            cx - layout.size.width / 2f,
                            cy - layout.size.height / 2f
                        )
                    )
                }
            }
        }
    }
}