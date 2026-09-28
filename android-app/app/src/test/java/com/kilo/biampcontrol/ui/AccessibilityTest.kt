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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Автоматические проверки доступности (Фаза 1, пункт 1.5 UI-PLAN).
 * `tryPerformAccessibilityChecks()` ловит: пустые contentDescription,
 * малые цели касания (<48 dp), низкий контраст, неверный порядок обхода.
 * Тест падает при любом замечании — это критерий «0 замечаний» из UI-PLAN.
 * Запускается как unit-тест через Robolectric. sdk зафиксирован на 34, потому что
 * Robolectric 4.14 не поддерживает SDK 35 (Compose-тест падает с
 * `Build.FINGERPRINT is null`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun labeledSliderHasNoAccessibilityIssues() {
        rule.setContent {
            Column {
                LabeledSlider(
                    label = "Общая", value = 50f, range = 0f..100f,
                    enabled = true, suffix = "%"
                ) {}
            }
        }
        rule.waitForIdle()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun dspRowsHaveNoAccessibilityIssues() {
        rule.setContent {
            Column { DspRowsForTest() }
        }
        rule.waitForIdle()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun channelFilterRowHasNoAccessibilityIssues() {
        rule.setContent {
            Column { ChannelFilterRowForTest() }
        }
        rule.waitForIdle()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun transportButtonsHaveNoAccessibilityIssues() {
        rule.setContent {
            Row {
                OutlinedButton(onClick = {},
                               modifier = Modifier.semanticsMerge("Воспроизвести", null)) { Text("▶") }
                OutlinedButton(onClick = {},
                               modifier = Modifier.semanticsMerge("Пауза", null)) { Text("⏸") }
            }
        }
        rule.waitForIdle()
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}

// ── Вспомогательные обёртки ────────────────────────────────────────────────
// EqRow/DelayRow/ChFilterRow приватны в своих файлах; здесь воспроизводится их
// структура один-в-один (те же компактные кнопки/переключатели и семантика),
// чтобы проверки доступности покрывали именно проблемные элементы.

@Composable
private fun DspRowsForTest() {
    // EqRow: строка заголовка с компактной кнопкой сброса «0» + слайдер
    val eqPos = remember { mutableFloatStateOf(0f) }
    Row {
        Text("Low 120Hz", modifier = Modifier.weight(1f))
        Text("${eqPos.floatValue.roundToInt()} дБ")
        OutlinedButton(onClick = {},
                       modifier = Modifier.height(26.dp).semanticsMerge("Low 120Hz, сброс в 0 дБ", null)
        ) { Text("0") }
    }
    Row(Modifier.height(28.dp)) {
        CompactSlider(value = eqPos.floatValue, onValueChange = { eqPos.floatValue = it },
                      valueRange = -12f..12f, steps = 23,
                      contentDescription = "Low 120Hz", stateDescriptionText = "0 дБ")
    }
}

@Composable
private fun ChannelFilterRowForTest() {
    // ChFilterRow: переключатель фильтра + слайдер частоты
    val on = remember { mutableStateOf(true) }
    val pos = remember { mutableFloatStateOf(200f) }
    Row {
        Text("HPF", modifier = Modifier.weight(1f))
        Text("${pos.floatValue.roundToInt()} Гц")
        Switch(checked = on.value, onCheckedChange = { on.value = it },
               modifier = Modifier.semanticsMerge("HPF НЧ-Л", null))
    }
    Row(Modifier.height(28.dp)) {
        CompactSlider(value = pos.floatValue, onValueChange = { pos.floatValue = it },
                      valueRange = 20f..20000f, enabled = on.value,
                      contentDescription = "HPF НЧ-Л", stateDescriptionText = "HPF НЧ-Л, 200 Гц")
    }
}
