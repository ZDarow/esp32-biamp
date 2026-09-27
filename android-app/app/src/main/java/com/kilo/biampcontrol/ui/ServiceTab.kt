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

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.BiAmpViewModel
import com.kilo.biampcontrol.bt.ConnState
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

@Composable
fun ServiceTab(vm: BiAmpViewModel) {
    val ds by vm.deviceState.collectAsState()
    val connected by vm.connState.collectAsState()
    val log by vm.log.collectAsState()
    val syncing by vm.isSyncing.collectAsState()
    val enabled = connected == ConnState.CONNECTED
    val scrollState = rememberScrollState()
    var showDoc by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Сервис", style = MaterialTheme.typography.titleLarge)

        Text("Тест динамиков", style = MaterialTheme.typography.titleMedium)
        val tests = listOf(
            "all" to "Все", "woof" to "НЧ", "tweet" to "ВЧ",
            "1" to "ch1", "2" to "ch2", "3" to "ch3", "4" to "ch4",
            "anti" to "Анти", "sweep" to "Свип", "off" to "СТОП"
        )
        tests.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (cmd, label) ->
                    val isStop = cmd == "off"
                    Button(
                        onClick = { vm.startTest(cmd) },
                        enabled = enabled,
                        colors = if (isStop) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) { Text(label, maxLines = 1) }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        var tfText by remember { mutableStateOf("440") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tfText,
                onValueChange = { tfText = it.filter { c -> c.isDigit() } },
                label = { Text("Гц") },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(
                onClick = { tfText.toIntOrNull()?.let { vm.setTf(it) } },
                enabled = enabled
            ) { Text("TF") }
        }

        // Громкость тест-сигнала. Заводская 6 % — намеренно тихо, чтобы свип
        // и анти-фаза не били по ушам без присмотра.
        LabeledSlider(
            label = "Громкость теста",
            value = ds.testVol.toFloat(),
            range = 0f..100f,
            enabled = enabled,
            suffix = "%",
            steps = 0
        ) { vm.setTestVol(it.roundToInt()) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Диагностика", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (syncing) {
                    Text(
                        "Синхронизация с устройством…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text("V0=${ds.vol0}% V1=${ds.vol1}% bal=${ds.bal}")
                Text("Fc=${ds.fc.toInt()}Hz hp=${ds.hp.toInt()}Hz sub=${if (ds.subOn) "ON" else "OFF"}")
                Text("XO: ${if (ds.xoType == 2) "LR4" else "Butter"}")
                Text("TLF=${ds.tlf}dB THF=${ds.thf}dB")
                Text("EQ: ${ds.eql}/${ds.eqm}/${ds.eqh}")
                Text("INV: ${ds.inv.map { if (it) 1 else 0 }.joinToString("")}")
                Text("BT: ${if (ds.btAudioOn) "ON" else "OFF"} | SPP: ${if (ds.sppOn) "ON" else "OFF"}")
                Text("Test: ${ds.testMode} TVol=${ds.testVol}%")
                Text("Delay: ${ds.delays.joinToString("/")}")
                Text("CHF: ${ds.chFilters.joinToString(" ") { "${it.first}/${it.second}" }}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.sendDiagnostic("stats") }, enabled = enabled) { Text("Stats") }
            OutlinedButton(onClick = { vm.sendDiagnostic("help") }, enabled = enabled) { Text("Help") }
            OutlinedButton(onClick = { vm.sendDiagnostic("heap") }, enabled = enabled) { Text("Heap") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Опасные действия", style = MaterialTheme.typography.titleMedium)
        DangerousButton("Сохранить (save)", enabled) { vm.saveParams() }
        DangerousButton("Перезагрузка (reboot)", enabled) { vm.reboot() }
        DangerousButton("Заводской сброс (factory)", enabled, isDestructive = true) { vm.factoryReset() }

HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Документация", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { showDoc = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("BiAmp Control — руководство (v15)") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Лог (последние строки)", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)) {
            Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                log.takeLast(15).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showDoc) {
        DocDialog(onDismiss = { showDoc = false })
    }
}

@Composable
private fun DocDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val lines by remember { mutableStateOf(loadDoc(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BiAmp Control — документация") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(scroll)) {
                lines.forEach { MdLine(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = null
    )
}

private data class MdLine(val text: String, val level: Int, val bold: Boolean, val code: Boolean)

@Composable
private fun MdLine(item: MdLine) {
    when {
        item.code -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        item.level > 0 -> Text(
            item.text,
            style = if (item.level == 1) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )
        item.bold -> Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        item.text.startsWith("- ") || item.text.startsWith("* ") -> Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium
        )
        item.text.trim().isEmpty() -> Spacer(Modifier.height(4.dp))
        else -> Text(item.text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun loadDoc(ctx: Context): List<MdLine> {
    val raw = try {
        ctx.assets.open("BiAmpControl.md").use { it.readBytes().toString(StandardCharsets.UTF_8) }
    } catch (_: Exception) { return listOf(MdLine("Документация не найдена в assets", 0, false, false)) }
    return raw.lineSequence().map { line ->
        val trimmed = line.trimEnd()
        val t = trimmed.trimStart()
        val content = t.removePrefix("- ").removePrefix("* ")
        val level = when {
            t.startsWith("# ") -> 1
            t.startsWith("## ") -> 2
            t.startsWith("### ") -> 3
            else -> 0
        }
        val text = content.removePrefix("#").trim()
        val bold = "**" in t
        val code = t.startsWith("`") && t.endsWith("`") && t.length > 2
MdLine(text, level, bold, code)
    }.toList()
}

@Composable
private fun DangerousButton(
    label: String,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onConfirm: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showDialog = true },
        enabled = enabled,
        colors = if (isDestructive) ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ) else ButtonDefaults.outlinedButtonColors(),
        modifier = Modifier.fillMaxWidth()
    ) { Text(label) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Подтверждение") },
            text = { Text("Выполнить «$label»?") },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(); showDialog = false },
                    colors = if (isDestructive) ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ) else ButtonDefaults.textButtonColors()
                ) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена") }
            }
        )
    }
}

