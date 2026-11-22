/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2. Источник истины — прошивка ESP32.
 *
 * Компоненты интерфейса в стиле BDK-AUDIO-APP: карточки со скруглёнными
 * углами, секционные заголовки, кнопки.
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
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.kilo.biampcontrol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.ui.theme.*

/**
 * Карточка в стиле BDK: тёмный фон, скруглённые углы, отступы 20 dp,
 * отступ снизу 16 dp.
 */
@Composable
fun BdkCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandSurfaceMid),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

/**
 * Заголовок секции в стиле BDK: маленький жирный текст, буква间距,
 * верхний регистр, цвет текста вторичный.
 */
@Composable
fun BdkSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = BrandTextTertiary,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

/**
 * Кнопка в стиле BDK: заполняет ширину, высота 48 dp, пурпурный фон,
 * скруглённые углы 14 dp.
 */
@Composable
fun BdkButton(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandAccent,
            contentColor = BrandOnAccent,
            disabledContainerColor = BrandOutline,
            disabledContentColor = BrandTextTertiary
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandOnAccent
        )
    }
}

/**
 * Кнопка вторичного стиля в стиле BDK: обводка пурпурным, текст пурпурный.
 */
@Composable
fun BdkButtonOutlined(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = BrandAccent,
            disabledContentColor = BrandTextTertiary
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandAccent
        )
    }
}

/**
 * Переключатель в стиле BDK: пурпурный трек.
 */
@Composable
fun BdkSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.height(32.dp),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            uncheckedThumbColor = Color.White,
            checkedTrackColor = BrandAccent,
            uncheckedTrackColor = BrandOutline
        )
    )
}

/**
 * Поле ввода текста в стиле BDK: тёмный фон, пурмурный акцент.
 */
@Composable
fun BdkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, style = MaterialTheme.typography.bodyMedium) },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        singleLine = true
    )
}

/**
 * Индикатор уровня в стиле BDK: горизонтальная полоса с подписью и значением.
 */
@Composable
fun BdkLevelMeter(
    label: String,
    value: Int,
    maxValue: Int = 120,
    color: Color = BrandMeterBass,
    modifier: Modifier = Modifier
) {
    val progress = (value.coerceAtLeast(0).coerceAtMost(maxValue) * 100 / maxValue).toFloat()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.width(56.dp)
        )
        LinearProgressIndicator(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            progress = progress / 100f,
            color = color,
            trackColor = BrandDivider
        )
        Text(
            text = "${value} dB",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTextSecondary,
            modifier = Modifier.width(48.dp)
        )
    }
}
