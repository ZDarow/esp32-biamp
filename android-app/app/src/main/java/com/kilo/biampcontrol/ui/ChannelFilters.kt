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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.BiAmpViewModel
import com.kilo.biampcontrol.bt.DeviceState
import kotlin.math.roundToInt

@Composable
fun ChannelFilters(vm: BiAmpViewModel, ds: DeviceState, enabled: Boolean) {
    val names = listOf("НЧ-Л", "ВЧ-Л", "НЧ-П", "ВЧ-П")
    Text("Поканальные фильтры", style = MaterialTheme.typography.titleMedium)
    names.forEachIndexed { ch, name ->
        Text(name, style = MaterialTheme.typography.bodyLarge,
             modifier = Modifier.padding(top = 6.dp))
        val hp = ds.chFilters.getOrElse(ch) { 0 to 0 }.first
        val lp = ds.chFilters.getOrElse(ch) { 0 to 0 }.second
        ChFilterRow("HPF", name, hp, enabled) { vm.setChHp(ch, it.roundToInt()) }
        ChFilterRow("LPF", name, lp, enabled) { vm.setChLp(ch, it.roundToInt()) }
    }
}

@Composable
private fun ChFilterRow(label: String, channelName: String, valueHz: Int, enabled: Boolean,
                        onFinished: (Float) -> Unit) {
    val on = valueHz > 0
    var pos by remember(label) { mutableFloatStateOf(if (valueHz > 0) valueHz.toFloat() else 200f) }
    LaunchedEffect(valueHz) { if (valueHz > 0) pos = valueHz.toFloat() }
    val stateText = "$label $channelName, ${if (on) "$valueHz Гц" else "выключен"}"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${valueHz} Гц", style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = on, enabled = enabled,
            onCheckedChange = { isOn -> onFinished(if (isOn) pos else 0f) },
            modifier = Modifier.semanticsMerge("$label $channelName", null)
        )
    }
    // Контейнер height(28.dp) — компактная высота при зоне касания 48 dp
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        CompactSlider(
            value = if (on) pos else 20f,
            onValueChange = { pos = it },
            onValueChangeFinished = { if (on) onFinished(pos) },
            valueRange = 20f..20000f, steps = 0,
            enabled = enabled && on,
            contentDescription = "$label $channelName",
            stateDescriptionText = stateText,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
