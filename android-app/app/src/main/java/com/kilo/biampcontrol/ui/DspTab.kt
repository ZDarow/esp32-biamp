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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.BiAmpViewModel
import com.kilo.biampcontrol.R
import com.kilo.biampcontrol.bt.ConnState
import kotlin.math.roundToInt

@Composable
fun DspTab(vm: BiAmpViewModel) {
    val ds by vm.deviceState.collectAsState()
    val connected by vm.connState.collectAsState()
    val enabled = connected == ConnState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.dsp_title), style = MaterialTheme.typography.titleLarge)

        // ── 1. Кроссовер: общий срез НФ/ВЧ-веток ─────────────────
        Text(stringResource(R.string.xo_title), style = MaterialTheme.typography.titleMedium)
        Caption(stringResource(R.string.xo_caption))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.xo_switch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        if (ds.xoOn) R.string.xo_state_on else R.string.xo_state_off
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = ds.xoOn, onCheckedChange = { vm.setXoOn(it) }, enabled = enabled)
        }
        // Выключенный кроссовер оставляет значения Fc и типа в памяти
        // (их нечем менять, пока секции считаются как bypass), поэтому
        // элементы остаются видимыми — просто недоступными.
        val xoEnabled = enabled && ds.xoOn
        LabeledSlider("Fc", ds.fc, 200f..1000f, xoEnabled,
            stringResource(R.string.suffix_hz), 15) { vm.setFc(it.roundToInt()) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.subsonic_hpf), style = MaterialTheme.typography.bodyLarge,
                 modifier = Modifier.weight(1f))
            Switch(checked = ds.subOn, onCheckedChange = { vm.setSub(it) }, enabled = enabled)
        }
        if (ds.subOn) {
            LabeledSlider("HPF", ds.hp, 20f..80f, enabled,
                stringResource(R.string.suffix_hz), 11) { vm.setHp(it.roundToInt()) }
        }
        Text(stringResource(R.string.xo_type), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ds.xoType == 1, onClick = { vm.setXoType(1) },
                       label = { Text("Butterworth") }, enabled = xoEnabled,
                       modifier = Modifier.weight(1f))
            FilterChip(selected = ds.xoType == 2, onClick = { vm.setXoType(2) },
                       label = { Text("LR4") }, enabled = xoEnabled,
                       modifier = Modifier.weight(1f))
        }

        HorizontalDivider()

        // ── 2. Эквалайзер ────────────────────────────────────────
        Text(stringResource(R.string.eq_title), style = MaterialTheme.typography.titleMedium)
        EqRow(stringResource(R.string.eq_low), ds.eql, enabled, { vm.setEq(0, 0) }) { vm.setEq(0, it.roundToInt()) }
        EqRow(stringResource(R.string.eq_mid), ds.eqm, enabled, { vm.setEq(1, 0) }) { vm.setEq(1, it.roundToInt()) }
        EqRow(stringResource(R.string.eq_high), ds.eqh, enabled, { vm.setEq(2, 0) }) { vm.setEq(2, it.roundToInt()) }

        HorizontalDivider()

        // ── 3. Полосы СЧ/ВЧ: точка подстройки поверх кроссовера ──
        Text(stringResource(R.string.band_title), style = MaterialTheme.typography.titleMedium)
        Caption(stringResource(R.string.band_caption))
        FilterGraph(vm, ds, enabled)

        HorizontalDivider()

        // ── 5. Инверсия фазы ─────────────────────────────────────
        Text(stringResource(R.string.phase_inversion), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CHANNEL_NAME_RES.forEachIndexed { ch, res ->
                FilterChip(
                    selected = ds.inv.getOrElse(ch) { false },
                    onClick = { vm.toggleInv(ch) },
                    label = { Text(stringResource(res)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        OutlinedButton(onClick = { vm.invOff() }, enabled = enabled,
                       modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reset_inversion)) }

        Spacer(Modifier.height(8.dp))
    }
}

/** Пояснение под заголовком блока: чем этот блок отличается от соседнего. */
@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ── Полоса EQ: кнопка «0» в строке заголовка, слайдер отдельно ──
@Composable
private fun EqRow(label: String, value: Float, enabled: Boolean,
                  onReset: () -> Unit, onFinished: (Float) -> Unit) {
    var pos by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { pos = value }
    val stateText = "${pos.roundToInt()} дБ"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(stateText,
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onReset, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier
                .height(26.dp)
                .semanticsMerge(stringResource(R.string.eq_reset_cd, label), null)
        ) { Text("0", style = MaterialTheme.typography.bodySmall) }
    }
    // Контейнер height(28.dp) — компактная высота при зоне касания 48 dp
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        CompactSlider(
            value = pos, onValueChange = { pos = it },
            onValueChangeFinished = { onFinished(pos) },
            valueRange = -12f..12f, steps = 23, enabled = enabled,
            contentDescription = label,
            stateDescriptionText = stateText,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
