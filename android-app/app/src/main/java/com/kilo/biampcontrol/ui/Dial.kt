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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.kilo.biampcontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Размах дуги шкалы в градусах: столько занимает рабочий диапазон. */
private const val SWEEP = 270f

/** Начало дуги в градусах системы координат Canvas: 135° — это вниз-влево. */
private const val START_ANGLE = 135f

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
 * @param steps число промежутков между делениями; 0 — плавное значение.
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
    diameter: Dp = 116.dp
) {
    val span = valueRange.endInclusive - valueRange.start
    val step = if (steps > 0) span / (steps + 1) else 0f

    var pos by remember { mutableFloatStateOf(value) }
    // Внешнее значение — источник истины: ответ усилителя должен побеждать
    // локальное положение, иначе ручка залипает на неотправленном значении.
    LaunchedEffect(value) { pos = value }

    // Размер в пикселях нужен для расчёта угла: координаты жестов приходят
    // в пикселях, а диаметр задан в dp.
    var sidePx by remember { mutableIntStateOf(0) }

    val haptics = LocalHapticFeedback.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.primary
    val needle = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline

    fun valueAt(p: Offset): Float {
        if (sidePx <= 0) return pos
        val c = sidePx / 2f
        val deg = Math.toDegrees(
            atan2((p.y - c).toDouble(), (p.x - c).toDouble())
        ).toFloat()
        // Переносим в систему отсчёта дуги: 0° — вправо, дуга идёт вниз.
        var a = (deg - (90f - SWEEP / 2f)) % 360f
        if (a < 0f) a += 360f
        if (a > SWEEP) a = SWEEP
        val raw = valueRange.start + (a / SWEEP) * span
        return if (step > 0f) {
            val snapped = valueRange.start +
                ((raw - valueRange.start) / step).roundToInt() * step
            snapped.coerceIn(valueRange.start, valueRange.endInclusive)
        } else raw
    }

    Box(
        modifier = modifier
            .size(diameter)
            .onSizeChanged { sidePx = it.width }
            .semantics {
                if (!label.isNullOrEmpty()) contentDescription = label
                if (!stateText.isNullOrEmpty()) stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(pos, valueRange)
            }
            .pointerInput(enabled, sidePx, valueRange, step) {
                if (!enabled || sidePx <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { start -> pos = valueAt(start) },
                    onDrag = { change, _ ->
                        change.consume()
                        pos = valueAt(change.position)
                        // Отклик на каждом шаге: ручка физическая, и без него
                        // её невозможно выставить на слух, глядя не на экран.
                        if (step > 0f) haptics.performHapticFeedback(
                            HapticFeedbackType.TextHandleMove
                        )
                    },
                    onDragEnd = { onValueChangeFinished(pos) },
                    onDragCancel = { onValueChangeFinished(pos) }
                )
            }
            .pointerInput(enabled, sidePx, valueRange, step) {
                if (!enabled || sidePx <= 0) return@pointerInput
                // Тап по дуге — установка значения прямо в точку,
                // как на настоящей ручке аналогового усилителя.
                detectTapGestures(
                    onTap = { tap ->
                        pos = valueAt(tap)
                        onValueChangeFinished(pos)
                    }
                )
            }
    ) {
        Canvas(Modifier.size(diameter)) {
            val side = size.minDimension
            val stroke = side * 0.075f
            val inset = stroke / 2f
            val topLeft = Offset(inset, inset)
            val arcSize = Size(side - stroke, side - stroke)

            val frac = if (span <= 0f) 0f
            else ((pos - valueRange.start) / span).coerceIn(0f, 1f)

            drawArc(
                color = track,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = if (enabled) arc else track,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP * frac,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val rad = Math.toRadians((START_ANGLE + SWEEP * frac).toDouble())
            val cx = side / 2f
            val cy = side / 2f
            val tail = side * 0.15f
            val tip = side * 0.40f
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
                strokeWidth = side * 0.085f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = if (enabled) needle else track,
                radius = side * 0.05f,
                center = Offset(cx, cy)
            )
        }
    }
}