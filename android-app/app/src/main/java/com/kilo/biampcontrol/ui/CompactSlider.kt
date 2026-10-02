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

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Добавляет слайдеру accessibility-семантику: объединяет дочерние узлы,
 * задаёт имя (WCAG 2.5.3 «Label in Name») и текстовое состояние
 * (WCAG 4.1.2 «Name, Role, Value»). Пустые значения не добавляются,
 * чтобы не провоцировать замечания автоматических проверок.
 */
internal fun Modifier.semanticsMerge(name: String?, state: String?): Modifier =
    semantics(mergeDescendants = true) {
        if (!name.isNullOrEmpty()) contentDescription = name
        if (!state.isNullOrEmpty()) stateDescription = state
    }

/**
 * Компактный слайдер: трек 28 dp, зона касания расширена до 48 dp
 * (требование доступности WCAG 2.5.5 / Material: минимальная цель касания 48 dp)
 * за счёт `minimumInteractiveComponentSize()` — стандартного механизма Compose:
 * он центрирует компонент внутри невидимого 48-dp окна и **уменьшает** занятую
 * высоту до 28 dp, если родительский контейнер задан меньше (Row/Column с
 * height(28.dp)). В отличие от height+отрицательного padding этот способ
 * легален: Compose запрещает отрицательный padding.
 * Зазор между ползунком и краями/соседними элементами — 8 dp.
 * Тап по треку — установка значения, перетаскивание — плавное изменение.
 * Основан на стандартном Material3 Slider с уменьшенной высотой.
 *
 * Собственного состояния здесь нет намеренно: значение приходит сверху, и
 * раньше ползунок держал ещё одну копию в `mutableFloatStateOf` с
 * `LaunchedEffect` поверх копии вызывающего. Три зеркала одного числа в
 * цепочке `deviceState → state → pos` означали, что правка ползунка
 * доезжала до отправки через две асинхронные ступени, а `Slider` получал
 * значение, отстающее от того, что нарисовал. Теперь единственная точка
 * состояния — у вызывающего, а этот компонент её только отображает.
 */
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    contentDescription: String? = null,
    stateDescriptionText: String? = null,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier
            .padding(horizontal = 8.dp) // зазор между ползунком и краями/соседними элементами
            .minimumInteractiveComponentSize() // зона касания 48 dp при видимых 28 dp
            .semanticsMerge(contentDescription, stateDescriptionText),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}