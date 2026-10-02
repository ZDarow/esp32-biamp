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
import com.kilo.biampcontrol.bt.Limits
import kotlin.math.roundToInt

@Composable
fun DspTab(vm: BiAmpViewModel) {
    val ds by vm.deviceState.collectAsState()
    val connected by vm.connState.collectAsState()
    val enabled = connected == ConnState.CONNECTED

    // Выключенные секции оставляют Fc и HPF в памяти (их нечем менять, пока
    // фильтры считаются как bypass), поэтому ручки остаются на месте и видимыми —
    // просто задизейблены. Иначе при выключении секции они бы исчезли, и
    // вторая ручка съезжала бы с места в горизонтальной линии.
    val xoEnabled = enabled && ds.xoOn
    val subEnabled = enabled && ds.subOn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.dsp_title), style = MaterialTheme.typography.titleLarge)

        // ── 1. Кроссовер и сабсоник: две крупные ручки в одну линию ──
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
        Text(stringResource(R.string.xo_type), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ds.xoType == 1, onClick = { vm.setXoType(1) },
                       label = { Text("Butterworth") }, enabled = xoEnabled,
                       modifier = Modifier.weight(1f))
            FilterChip(selected = ds.xoType == 2, onClick = { vm.setXoType(2) },
                       label = { Text("LR4") }, enabled = xoEnabled,
                       modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.subsonic_hpf), style = MaterialTheme.typography.bodyLarge,
                 modifier = Modifier.weight(1f))
            Switch(checked = ds.subOn, onCheckedChange = { vm.setSub(it) }, enabled = enabled)
        }

        // Обе частотные ручки стоят в одну горизонталь: их выставляют вместе,
        // слушая один и тот же фрагмент записи, и вертикальный список заставлял
        // прокручивать экран между ними. Подписи вынесены под ручки, иначе
        // подписи наезжали бы друг на друга на узком экране.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            DialKnob(
                label = stringResource(R.string.xo_fc),
                value = ds.fc,
                range = 200f..1000f,
                steps = 15,
                enabled = xoEnabled,
                modifier = Modifier.weight(1f)
            ) { vm.setFc(it.roundToInt()) }

            DialKnob(
                label = stringResource(R.string.subsonic_knob),
                value = ds.hp,
                range = 20f..80f,
                steps = 11,
                enabled = subEnabled,
                modifier = Modifier.weight(1f)
            ) { vm.setHp(it.roundToInt()) }
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

        // ── 5. Раскладка выходов: L/R и дублирование 2,3 ──────────
        // Предупреждение о DUP обязано быть на экране: при DUP: 1 каналы
        // 2 и 3 физически молчат, и без этой строки пользователь ищет
        // неисправность в усилителе, а не в настройке.
        if (ds.dup) {
            Text(
                stringResource(R.string.dup_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
Text(stringResource(R.string.swap_switch), style = MaterialTheme.typography.bodyLarge,
                 modifier = Modifier.weight(1f))
            Switch(checked = ds.swapped, onCheckedChange = { vm.setSwap(it) }, enabled = enabled)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.dup_switch), style = MaterialTheme.typography.bodyLarge,
                 modifier = Modifier.weight(1f))
            Switch(checked = ds.dup, onCheckedChange = { vm.setDup(it) }, enabled = enabled)
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Ручка с подписью и текущим значением для горизонтальной линии.
 *
 * Подпись и число стоят под крутилкой, а не рядом: на две ручки в ряд ширины
 * не хватает, и подпись сбоку выдавила бы вторую ручку за край экрана.
 * Число набрано покрупнее подписи — на него смотрят, выставляя частоту.
 */
@Composable
private fun DialKnob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFinished: (Float) -> Unit
) {
    val stateText = sliderStateText(value, steps, " Гц")
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Dial(
            value = value,
            valueRange = range,
            onValueChangeFinished = onFinished,
            enabled = enabled,
            steps = steps,
            diameter = 132.dp,
            label = label,
            stateText = stateText
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stateText,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
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
/**
 * Одна копия значения полосы: `pos`. Подтверждается значением с устройства
 * через [LaunchedEffect] и передаётся в [CompactSlider] как есть — у ползунка
 * своего состояния больше нет.
 */
@Composable
private fun EqRow(label: String, value: Float, enabled: Boolean,
                  onReset: () -> Unit, onFinished: (Float) -> Unit) {
    val pos = remember { mutableFloatStateOf(value) }
    // floatValue вместо value: mutableFloatStateOf хранит Float в примитиве,
    // и обращение через value упаковывало бы его в Float на каждом кадре
    // (lint AutoboxingStateValueProperty).
    LaunchedEffect(value) { pos.floatValue = value }
    val stateText = "${pos.floatValue.roundToInt()} дБ"
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
            value = pos.floatValue, onValueChange = { pos.floatValue = it },
            onValueChangeFinished = { onFinished(pos.floatValue) },
            valueRange = Limits.EQ_MIN..Limits.EQ_MAX, steps = 23, enabled = enabled,
            contentDescription = label,
            stateDescriptionText = stateText,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
