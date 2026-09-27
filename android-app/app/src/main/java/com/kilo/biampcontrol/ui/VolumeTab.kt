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
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun VolumeTab(vm: BiAmpViewModel) {
    val ds by vm.deviceState.collectAsState()
    val connected by vm.connState.collectAsState()
    val muted0 by vm.isMuted0.collectAsState()
    val muted1 by vm.isMuted1.collectAsState()
    val enabled = connected == com.kilo.biampcontrol.bt.ConnState.CONNECTED

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Громкость", style = MaterialTheme.typography.titleLarge)

        LabeledSlider(
            label = "Общая", value = ds.vol0.toFloat(), range = 0f..100f,
            enabled = enabled, suffix = "%"
        ) { vm.setVolBoth(it.roundToInt()) }

        // Левая зона
        LabeledSlider(
            label = "Левая (L)", value = ds.vol0.toFloat(), range = 0f..100f,
            enabled = enabled, suffix = "%"
        ) { vm.setVol0(it.roundToInt()) }

        // Правая зона
        LabeledSlider(
            label = "Правая (R)", value = ds.vol1.toFloat(), range = 0f..100f,
            enabled = enabled, suffix = "%"
        ) { vm.setVol1(it.roundToInt()) }

        // Баланс
        LabeledSlider(
            label = "Баланс", value = ds.bal, range = -10f..10f,
            enabled = enabled, suffix = "", steps = 19
        ) { vm.setBal(it.roundToInt()) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(
                selected = muted0, onClick = { vm.toggleMute(0) },
                label = { Text("Mute Л") }, enabled = enabled
            )
            FilterChip(
                selected = muted1, onClick = { vm.toggleMute(1) },
                label = { Text("Mute П") }, enabled = enabled
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("Транспорт", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.transport("prev") }, enabled = enabled) { Text("⏮") }
            OutlinedButton(onClick = { vm.transport("play") }, enabled = enabled) { Text("▶") }
            OutlinedButton(onClick = { vm.transport("pause") }, enabled = enabled) { Text("⏸") }
            OutlinedButton(onClick = { vm.transport("next") }, enabled = enabled) { Text("⏭") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("Пресеты", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presets = listOf("Flat", "Voice", "Night", "Party")
            presets.forEachIndexed { i, name ->
                OutlinedButton(
                    onClick = { vm.preset(i) }, enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(name, maxLines = 1) }
            }
        }
    }
}


