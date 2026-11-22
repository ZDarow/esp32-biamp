/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2. Источник истины — прошивка ESP32.
 *
 * Интерфейс портирован с BDK-AUDIO-APP: карточки со скруглёнными углами,
 * секционные заголовки, пурпурный/циан акценты.
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
import com.kilo.biampcontrol.bt.Limits
import com.kilo.biampcontrol.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun DspTab(vm: BiAmpViewModel) {
    val ds by vm.deviceState.collectAsState()
    val connected by vm.connState.collectAsState()
    val enabled = connected == ConnState.CONNECTED
    val isDirty by vm.isDirty.collectAsState()

    val xoEnabled = enabled && ds.xoOn
    val subEnabled = enabled && ds.subOn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── 1. DSP Controls (BDK карточка) ──
        BdkCard {
            BdkSectionTitle(stringResource(R.string.xo_title))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.xo_switch), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                BdkSwitch(checked = ds.xoOn, onCheckedChange = { vm.setXoOn(it); vm.logUi("XO", "on=${it}") }, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.subsonic_hpf), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                BdkSwitch(checked = ds.subOn, onCheckedChange = { vm.setSub(it); vm.logUi("SUB", "on=${it}") }, enabled = enabled)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.swap_switch), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                BdkSwitch(checked = ds.swapped, onCheckedChange = { vm.setSwap(it); vm.logUi("SWAP", "on=${it}") }, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dup_switch), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                BdkSwitch(checked = ds.dup, onCheckedChange = { vm.setDup(it); vm.logUi("DUP", "on=${it}") }, enabled = enabled)
            }
            if (ds.dup) {
                Text(stringResource(R.string.dup_warning), style = MaterialTheme.typography.bodySmall, color = BrandStatusError)
            }
        }

        // ── 2. Кроссовер ──
        BdkCard {
            Column(Modifier.padding(0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.xo_type), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    FilterChip(selected = ds.xoType == 1, onClick = { vm.setXoType(1); vm.logUi("XO_TYPE", "BW12") }, label = { Text("BW12", style = MaterialTheme.typography.bodySmall) }, enabled = xoEnabled)
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = ds.xoType == 2, onClick = { vm.setXoType(2); vm.logUi("XO_TYPE", "LR24") }, label = { Text("LR24", style = MaterialTheme.typography.bodySmall) }, enabled = xoEnabled)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DialKnob(stringResource(R.string.xo_fc), ds.fc, 200f..1000f, 15, xoEnabled, Modifier.weight(1f), Limits.FC_DEFAULT) { vm.setFc(it.roundToInt()); vm.logUi("XO_FC", "${it.roundToInt()}") }
                    DialKnob(stringResource(R.string.subsonic_knob), ds.hp, 20f..80f, 11, subEnabled, Modifier.weight(1f), Limits.HP_DEFAULT) { vm.setHp(it.roundToInt()); vm.logUi("HPF", "${it.roundToInt()}") }
                }
            }
        }

        // ── 3. Эквалайзер (BDK карточка) ──
        BdkCard {
            BdkSectionTitle(stringResource(R.string.eq_title))
            EqRow(stringResource(R.string.eq_low), ds.eql, enabled, { vm.setEq(0, 0); vm.logUi("EQ", "low=0") }) { vm.setEq(0, it.roundToInt()); vm.logUi("EQ", "low=${it.roundToInt()}") }
            EqRow(stringResource(R.string.eq_mid), ds.eqm, enabled, { vm.setEq(1, 0); vm.logUi("EQ", "mid=0") }) { vm.setEq(1, it.roundToInt()); vm.logUi("EQ", "mid=${it.roundToInt()}") }
            EqRow(stringResource(R.string.eq_high), ds.eqh, enabled, { vm.setEq(2, 0); vm.logUi("EQ", "high=0") }) { vm.setEq(2, it.roundToInt()); vm.logUi("EQ", "high=${it.roundToInt()}") }
            EqCurve(eql = ds.eql, eqm = ds.eqm, eqh = ds.eqh, onEqChange = { band, gain -> vm.setEq(band, gain.roundToInt()); vm.logUi("EQ_CURVE", "band=$band gain=${gain.roundToInt()}") }, modifier = Modifier.fillMaxWidth().height(44.dp))
            // Link L/R toggle
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Link L/R", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                var linkEnabled by remember { mutableStateOf(false) }
                BdkSwitch(checked = linkEnabled, onCheckedChange = { linkEnabled = it }, enabled = enabled)
            }
            // EQ пресеты
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val presets = listOf("Flat", "Bass", "Treble", "V-Shape")
                presets.forEach { name ->
                    FilterChip(selected = false, onClick = { vm.applyEqPreset(name); vm.logUi("EQ_PRESET", name) }, label = { Text(name, style = MaterialTheme.typography.bodySmall) }, enabled = enabled, modifier = Modifier.weight(1f))
                }
            }
        }

        // ── 4. Фильтры каналов ──
        BdkCard {
            Column(Modifier.padding(0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.band_title), style = MaterialTheme.typography.bodyMedium)
                FilterGraph(vm, ds, enabled)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("SR: 44.1 кГц  16 бит  |  ${if (isDirty) "Modified" else "Saved"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EqRow(label: String, value: Float, enabled: Boolean, onReset: () -> Unit, onFinished: (Float) -> Unit) {
    val pos = remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { pos.floatValue = value }
    val stateText = "${pos.floatValue.roundToInt()} дБ"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(stateText, style = MaterialTheme.typography.bodyMedium, color = BrandAccent)
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = onReset, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) { Text("0", style = MaterialTheme.typography.bodySmall) }
    }
    Row(Modifier.fillMaxWidth().height(24.dp)) {
        CompactSlider(value = pos.floatValue, onValueChange = { pos.floatValue = it }, onValueChangeFinished = { onFinished(pos.floatValue) }, valueRange = Limits.EQ_MIN..Limits.EQ_MAX, steps = 23, enabled = enabled, contentDescription = label, stateDescriptionText = stateText, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DialKnob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, modifier: Modifier = Modifier, resetTo: Float, onFinished: (Float) -> Unit) {
    val stateText = "${value.roundToInt()}"
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Dial(value = value, valueRange = range, onValueChangeFinished = { onFinished(value) }, enabled = enabled, steps = steps, diameter = 52.dp, label = label, stateText = stateText, valueText = stateText, defaultValue = resetTo)
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline)
    }
}
