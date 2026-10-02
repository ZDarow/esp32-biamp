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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.BiAmpViewModel
import com.kilo.biampcontrol.R
import com.kilo.biampcontrol.bt.Limits
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun VolumeTab(vm: BiAmpViewModel) {
    val ds by vm.deviceState.collectAsState()
    val connected by vm.connState.collectAsState()
    val enabled = connected == com.kilo.biampcontrol.bt.ConnState.CONNECTED
    // Mute берётся из строки `Mute: z0/z1` блока status, а не из локального
    // флага: локальный флаг врал между нажатием и ответом усилителя и
    // оставался врёным навсегда, если ответ терялся.
    val muted0 = ds.muted.getOrElse(0) { false }
    val muted1 = ds.muted.getOrElse(1) { false }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
Text(stringResource(R.string.volume_title), style = MaterialTheme.typography.titleLarge)

        LabeledSlider(
            label = stringResource(R.string.volume_both), value = ds.vol0.toFloat(),
            range = Limits.VOL_MIN..Limits.VOL_MAX,
            enabled = enabled, suffix = "%"
        ) { vm.setVolBoth(it.roundToInt()) }

        // Левая зона
        LabeledSlider(
            label = stringResource(R.string.volume_left), value = ds.vol0.toFloat(),
            range = Limits.VOL_MIN..Limits.VOL_MAX,
            enabled = enabled, suffix = "%"
        ) { vm.setVol0(it.roundToInt()) }

        // Правая зона
        LabeledSlider(
            label = stringResource(R.string.volume_right), value = ds.vol1.toFloat(),
            range = Limits.VOL_MIN..Limits.VOL_MAX,
            enabled = enabled, suffix = "%"
        ) { vm.setVol1(it.roundToInt()) }

        // Баланс
        LabeledSlider(
            label = stringResource(R.string.volume_balance), value = ds.bal,
            range = Limits.BAL_MIN..Limits.BAL_MAX,
            enabled = enabled, suffix = "", steps = 19
        ) { vm.setBal(it.roundToInt()) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(
                selected = muted0, onClick = { vm.toggleMute(0) },
                label = { Text(stringResource(R.string.mute_left)) }, enabled = enabled
            )
            FilterChip(
                selected = muted1, onClick = { vm.toggleMute(1) },
                label = { Text(stringResource(R.string.mute_right)) }, enabled = enabled
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_transport), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.transport("prev") }, enabled = enabled,
                           modifier = Modifier.semanticsMerge(stringResource(R.string.cd_prev), null)) { Text("⏮") }
            OutlinedButton(onClick = { vm.transport("play") }, enabled = enabled,
                           modifier = Modifier.semanticsMerge(stringResource(R.string.cd_play), null)) { Text("▶") }
            OutlinedButton(onClick = { vm.transport("pause") }, enabled = enabled,
                           modifier = Modifier.semanticsMerge(stringResource(R.string.cd_pause), null)) { Text("⏸") }
            OutlinedButton(onClick = { vm.transport("next") }, enabled = enabled,
                           modifier = Modifier.semanticsMerge(stringResource(R.string.cd_next), null)) { Text("⏭") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_presets), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presets = listOf(
                stringResource(R.string.preset_flat),
                stringResource(R.string.preset_voice),
                stringResource(R.string.preset_night),
                stringResource(R.string.preset_party)
            )
            presets.forEachIndexed { i, name ->
                OutlinedButton(
                    onClick = { vm.preset(i) }, enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(name, maxLines = 1) }
            }
        }
    }
}


