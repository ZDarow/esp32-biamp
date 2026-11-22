/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2.
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

import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import java.util.Locale
import kotlin.math.roundToInt

/** Формирует текстовое состояние слайдера для TalkBack (WCAG 4.1.2). */
internal fun sliderStateText(pos: Float, steps: Int, suffix: String): String =
    (if (steps > 0) pos.roundToInt().toString()
     else String.format(Locale.US, "%.1f", pos)) + suffix.trim()

/**
 * Слайдер с подписью значения.
 *
 * Единственная копия значения, которую держит этот компонент, —
 * `mutableFloatStateOf` ниже. Значение с устройства подтверждает её через
 * [LaunchedEffect], а ползунок пишет прямо в неё. Раньше копий было три
 * (`state` → `pos` → внутренняя `pos` у [CompactSlider]), и правка
 * доезжала до `onValueChangeFinished` через две асинхронные ступени.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    suffix: String = "",
    steps: Int = 0,
    onValueChangeFinished: (Float) -> Unit
) {
    val state = remember { mutableFloatStateOf(value) }
    LabeledSlider(label, state, range, enabled, suffix, steps, onValueChangeFinished)
}

@Composable
fun LabeledSlider(
    label: String,
    value: MutableState<Float>,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    suffix: String = "",
    steps: Int = 0,
    onValueChangeFinished: (Float) -> Unit
) {
    // Значение с устройства подтверждает локальное, но не наоборот: пока
    // ответ не пришёл, положение ползунка остаётся тем, куда его довёл
    // пользователь, иначе опрос status откатывал бы недосланную правку.
    val remote = value.value
    LaunchedEffect(remote) { value.value = remote }
    val pos = value.value

    // Tooltip при перетаскивании: палец накрывает значение справа от
    // ползунка (compose-audio-controls, «Our finger hides the knob»),
    // поэтому дублируем его всплывающей меткой сверху. Появляется при
    // первом касании и пропадает при отпускании — без неё значение
    // теряется на фоне пальца.
    val interactionSource = remember { MutableInteractionSource() }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            isDragging = when (interaction) {
                is PressInteraction.Press -> true
                is PressInteraction.Release, is PressInteraction.Cancel -> false
                else -> isDragging
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (isDragging) {
            Text(
                sliderStateText(pos, steps, suffix),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                textAlign = TextAlign.Center
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                sliderStateText(pos, steps, suffix),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // Контейнер height(24.dp): зона касания ползунка остаётся 48 dp
        // (minimumInteractiveComponentSize центрирует трек и ужимает занятую высоту).
        Row(Modifier.fillMaxWidth().height(24.dp)) {
            CompactSlider(
                value = pos,
                onValueChange = { value.value = it },
                onValueChangeFinished = { onValueChangeFinished(value.value) },
                valueRange = range,
                steps = steps,
                enabled = enabled,
                interactionSource = interactionSource,
                contentDescription = label,
                stateDescriptionText = sliderStateText(pos, steps, suffix),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
