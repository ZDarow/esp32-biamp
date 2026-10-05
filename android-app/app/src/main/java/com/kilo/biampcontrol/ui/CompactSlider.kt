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

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

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
 * Высота ручки ползунка: 28 dp при видимой ширине 4 dp.
 *
 * Раньше ползунок рисовался дефолтом — кругом 20 dp, — и выглядел как
 * переключатель, а не как фейдер. Узкая вертикальная ручка со скруглёнными
 * торцами читается как ход резистора и даёт ту же площадь захвата пальцем,
 * что и круг, при меньшем визуальном весе.
 */
private val ThumbSize = DpSize(4.dp, 28.dp)

/**
 * Компактный слайдер: трек 16 dp, ручка 4×28 dp, зона касания 48 dp.
 *
 * Зона касания расширена до 48 dp (WCAG 2.5.5 / Material: минимальная цель
 * касания 48 dp) за счёт `minimumInteractiveComponentSize()` — стандартного
 * механизма Compose: он центрирует компонент внутри невидимого 48-dp окна и
 * **уменьшает** занятую высоту до 28 dp, если родительский контейнер задан
 * меньше (`Row` с `height(28.dp)`). В отличие от `height` с отрицательным
 * отступом этот способ легален: Compose запрещает отрицательный padding.
 * Зазор между ползунком и краями/соседними элементами — 8 dp.
 * Тап по треку — установка значения, перетаскивание — плавное изменение.
 *
 * Используется перегрузка `Slider(value = …, thumb = …, track = …)`: в
 * Compose Material 3 1.3.2 (единственная версия, которую приносит BOM
 * 2025.09.00) устаревшей помечена перегрузка без слотов `thumb`/`track`, а
 * stateful-вариант `Slider(state = …)` появится только в 1.4.0 вместе с
 * `rememberSliderState`. Эта перегрузка помечена `@ExperimentalMaterial3Api`,
 * поэтому ниже стоит `@OptIn`; снимать его имеет смысл вместе с переходом
 * на 1.4.0. Слоты нужны ради собственной формы ручки: дефолтная — круг 20 dp,
 * и ползунок выглядит переключателем, а не фейдером.
 *
 * Источник истины — вызывающий: значение приходит сверху и уходит вниз
 * прежним порядком, а `thumb`/`track` только рисуют. Поэтому ответ усилителя,
 * подтвердивший правку, всегда побеждает локальное положение, и ползунок не
 * залипает на неотправленном значении.
 *
 * Цвета взяты из ролей темы: неактивный трек — `surfaceContainerHighest`,
 * а не устаревшая `surfaceVariant`, значение которой Compose подставляет из
 * базовой схемы и потому оно не совпадает с соседними карточками.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                enabled = enabled,
                thumbSize = ThumbSize
            )
        },
        modifier = modifier
            .padding(horizontal = 8.dp) // зазор между ползунком и краями/соседними элементами
            .minimumInteractiveComponentSize() // зона касания 48 dp при видимых 28 dp
            .semanticsMerge(contentDescription, stateDescriptionText)
    )
}
