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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.BiAmpViewModel
import com.kilo.biampcontrol.R
import com.kilo.biampcontrol.bt.ConnState
import com.kilo.biampcontrol.bt.Limits
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
        Text(stringResource(R.string.service_title), style = MaterialTheme.typography.titleLarge)

        Text(stringResource(R.string.section_speaker_test), style = MaterialTheme.typography.titleMedium)
        val columns = ActionCatalog.TEST_GRID_COLUMNS
        ActionCatalog.gridRows(ActionCatalog.TEST_MODES, columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { action ->
                    ChoiceButton(
                        label = stringResource(action.label),
                        onClick = { vm.startTest(action.mode) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        destructive = action.destructive
                    )
                }
                // Пустые ячейки последнего ряда: без них оставшиеся кнопки
                // растянутся на всю ширину и станут шире кнопок верхних рядов.
                repeat(ActionCatalog.gridPadding(row.size, columns)) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        var tfText by remember { mutableStateOf("440") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tfText,
                onValueChange = { tfText = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.label_hz)) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(
                onClick = { tfText.toIntOrNull()?.let { vm.setTf(it) } },
                enabled = enabled
            ) { Text("TF") }
        }

        // Громкость тест-сигнала. Заводские 6 % — намеренно тихо, чтобы свип
        // и анти-фаза не били по ушам без присмотра, и потолок здесь не 100 %,
        // а 6 %: TEST_VOL_MAX в прошивке 0.06, а не 1.0 (контракт, раздел 4).
        LabeledSlider(
            label = stringResource(R.string.label_test_volume),
            value = ds.testVol.toFloat(),
            range = Limits.TEST_VOL_MIN.toFloat()..Limits.TEST_VOL_MAX.toFloat(),
            enabled = enabled,
            suffix = "%",
            steps = 5
        ) { vm.setTestVol(it.roundToInt()) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.section_diagnostics), style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (syncing) {
                    Text(
                        stringResource(R.string.syncing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text("V0=${ds.vol0}% V1=${ds.vol1}% bal=${ds.bal}")
                Text("Fc=${ds.fc.toInt()}Hz hp=${ds.hp.toInt()}Hz sub=${if (ds.subOn) "ON" else "OFF"}")
                Text("XO: ${if (ds.xoType == 2) "LR4" else "Butter"} ${if (ds.xoOn) "ON" else "OFF"}")
                Text("TLF=${ds.tlf}dB THF=${ds.thf}dB")
                Text("EQ: ${ds.eql}/${ds.eqm}/${ds.eqh}")
// Строки контракта, раздела 2: Mute, SWP, DUP. Инверсии фазы
                // в v35 нет — команда inv: удалена, поле INV: тоже.
                Text("Mute: ${ds.muted[0]}/${ds.muted[1]}")
                Text("SWP: ${if (ds.swapped) 1 else 0}")
                Text("DUP: ${if (ds.dup) 1 else 0}")
                Text("BT: ${if (ds.btAudioOn) "ON" else "OFF"} | SPP: ${if (ds.sppOn) "ON" else "OFF"}")
                Text("Test: ${ds.testMode} TVol=${ds.testVol}%")
                Text("Delay: ${ds.delays.joinToString("/")}")
                Text("CHF: ${ds.chFilters.joinToString(" ") { "${it.first}/${it.second}" }}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionCatalog.DIAGNOSTICS.forEach { command ->
                ChoiceButton(
                    label = command,
                    onClick = { vm.sendDiagnostic(command) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.section_danger), style = MaterialTheme.typography.titleMedium)
        DangerConfirmButton(
            label = stringResource(R.string.action_save),
            onConfirm = { vm.saveParams() },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        DangerConfirmButton(
            label = stringResource(R.string.action_reboot),
            onConfirm = { vm.reboot() },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        DangerConfirmButton(
            label = stringResource(R.string.action_factory),
            onConfirm = { vm.factoryReset() },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            destructive = true
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.section_docs), style = MaterialTheme.typography.titleMedium)
        ChoiceButton(
            label = stringResource(R.string.docs_button),
            onClick = { showDoc = true },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.section_log), style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)) {
            Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                log.takeLast(15).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showDoc) {
        DocSheet(onDismiss = { showDoc = false })
    }
}

/**
 * Документация в шторке, а не в окне.
 *
 * Окно для короткого подтверждения: «стереть всё? да/нет» читается в нём
 * целиком и без прокрутки. Документация — это несколько экранов текста,
 * и в окне она либо обрезалась по высоте, либо превращалась в мелкий
 * поток строк посреди пустого поля. Шторка занимает почти весь экран,
 * текст идёт от левого поля до нижнего, а «тащишь её вниз — закрыл»
 * работает без кнопки. Открывается сразу развёрнутой: документацию
 * открывают, чтобы её читать, а не чтобы на неё взглянуть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val lines by remember { mutableStateOf(loadDoc(ctx)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.docs_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close)
                    )
                }
            }
            Text(
                stringResource(R.string.docs_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(scroll)
            ) {
                lines.forEach { MdLine(it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
    val missing = ctx.getString(R.string.docs_missing)
    val raw = try {
        ctx.assets.open("BiAmpControl.md").use { it.readBytes().toString(StandardCharsets.UTF_8) }
    } catch (_: Exception) { return listOf(MdLine(missing, 0, false, false)) }
    return raw.lineSequence().map { line ->
        val trimmed = line.trimEnd()
        val t = trimmed.trimStart()
        val content = t.removePrefix("- ").removePrefix("* ")
        val level = when {
            t.startsWith("### ") -> 3
            t.startsWith("## ") -> 2
            t.startsWith("# ") -> 1
            else -> 0
        }
        // Все решётки заголовка снимаются: `removePrefix("#")` у `## Раздел`
        // оставлял лишнюю, и в шторке читалось «# Раздел».
        val text = content.trimStart('#').replace("**", "").trim()
        val bold = "**" in t
        val code = t.startsWith("`") && t.endsWith("`") && t.length > 2
        MdLine(text, level, bold, code)
    }.toList()
}


