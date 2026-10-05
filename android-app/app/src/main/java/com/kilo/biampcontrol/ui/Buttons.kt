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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.R

/**
 * Тоновая кнопка выбора — базовая кнопка приложения.
 *
 * Раньше на вкладках лежали голые `Button` и `OutlinedButton` с разной
 * высотой, разными отступами и разной шириной: тест-сигнал был
 * `Button`, пресеты — `OutlinedButton`, диагностика — `OutlinedButton` в
 * один ряд. Рядом друг с другом они читались как элементы из разных
 * приложений, хотя означали одно и то же — «нажми, чтобы применить
 * настройку». Тоновая подложка `secondaryContainer` и высота контейнера
 * `ButtonDefaults.ButtonContainerHeight` (40 dp) с зоной касания 48 dp
 * дают всем кнопкам один ритм.
 *
 * Подпись обрезается многоточием, а не переносится: в сетке тест-сигнала
 * ширина ячейки считается от числа колонок, и перенос сделал бы кнопки
 * разной высоты.
 *
 * Ширину задаёт вызывающий: в ряду — `Modifier.weight(1f)`, во всю
 * ширину — `Modifier.fillMaxWidth()`.
 */
@Composable
fun ChoiceButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = if (destructive) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Кнопка транспорта: иконка плюс имя для TalkBack.
 *
 * Иконки вместо символов Unicode — не украшение. Раньше на кнопках
 * стояли глифы `⏮ ▶ ⏸ ⏭`, и это шрифтовая подстановка: на части
 * устройств emoji-шрифт отсутствует, и кнопка показывалась квадратом,
 * а на части — цветным эмодзи, то есть кнопка меняла цвет сама, мимо
 * темы. Иконки Material рисуются всегда, одним цветом темы и в одном
 * стиле с остальным интерфейсом.
 *
 * `play` выделен тонкой заливкой (`emphasized`): он главное действие
 * панели, остальные три — переключение трека и пауза, и одинаковый вес
 * у всех четырёх заставил бы искать нужную.
 *
 * Имя кнопки передаётся подписью (WCAG 2.5.3), состояние — полем
 * [semanticsMerge] у вызывающего, если оно есть.
 */
@Composable
fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false
) {
    if (emphasized) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.semanticsMerge(contentDescription, null),
            enabled = enabled
        ) { Icon(icon, contentDescription = null) }
    } else {
        OutlinedIconButton(
            onClick = onClick,
            modifier = modifier.semanticsMerge(contentDescription, null),
            enabled = enabled
        ) { Icon(icon, contentDescription = null) }
    }
}

/**
 * Кнопка необратимого действия: сначала окно подтверждения, потом команда.
 *
 * Подтверждение нужно ровно двум действиям — `reboot` и `factory`.
 * Прошивка по Bluetooth и без него требует суффикс `:ok`, но это защита
 * от чужой команды, а не от промаха по кнопке: палец, который промахнулся
 * на соседний элемент, `:ok` не поставит.
 *
 * Окно, а не тост и не «нажать дважды»: действие необратимо, и подтверж-
 * дение должно быть прочитано целиком — «стереть всё настройки? да/нет».
 * Короткий текст помещается в окно без прокрутки, в отличие от
 * документации, поэтому она живёт в шторке (`DocSheet`).
 *
 * [destructive] красит подтверждение в цвет ошибки: `factory` стирает
 * настройки NVS, `reboot` — просто перезагружает, и пугать его нечем.
 */
@Composable
fun DangerConfirmButton(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showDialog = true },
        modifier = modifier,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.outlinedButtonColors()
        }
    ) { Text(label) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_question, label)) },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(); showDialog = false },
                    colors = if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    }
                ) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
