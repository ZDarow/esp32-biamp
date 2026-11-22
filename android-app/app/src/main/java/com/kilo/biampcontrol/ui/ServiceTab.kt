/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2. Источник истины — прошивка ESP32.
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

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
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
import java.io.File
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
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showAutoEqDialog by remember { mutableStateOf(false) }

    val autoEqFileLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { vm.applyAutoEqFile(it) }
        showAutoEqDialog = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.service_title), style = MaterialTheme.typography.titleLarge)

        // ── Настройки устройства (BDK карточка) ──
        BdkCard {
            BdkSectionTitle(stringResource(R.string.section_device_settings))
            BdkTextField(
                value = vm.deviceName,
                onValueChange = { vm.deviceName = it },
                label = stringResource(R.string.label_device_name),
                enabled = true,
                modifier = Modifier.fillMaxWidth()
            )
            BdkButton(
                onClick = { /* имя сохраняется локально при изменении поля */ },
                label = stringResource(R.string.btn_apply_name),
                enabled = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(stringResource(R.string.section_speaker_test), style = MaterialTheme.typography.titleMedium)
        val columns = ActionCatalog.TEST_GRID_COLUMNS
        ActionCatalog.gridRows(ActionCatalog.TEST_MODES, columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { action ->
                    ChoiceButton(
                        label = stringResource(action.label),
                        onClick = { vm.startTest(action.mode); vm.logUi("TEST", action.mode) },
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BdkTextField(
                value = tfText,
                onValueChange = { tfText = it.filter { c -> c.isDigit() } },
                label = stringResource(R.string.label_hz),
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { tfText.toIntOrNull()?.let { vm.setTf(it); vm.logUi("TF", "$it Hz") } },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
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
        ) { vm.setTestVol(it.roundToInt()); vm.logUi("TVOL", "${it.roundToInt()}%") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_diagnostics), style = MaterialTheme.typography.titleMedium)
        BdkCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (syncing) {
                    Text(
                        stringResource(R.string.syncing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text("V0=${ds.vol0}% V1=${ds.vol1}% bal=${ds.bal}", style = MaterialTheme.typography.bodySmall)
                Text("Fc=${ds.fc.toInt()}Hz hp=${ds.hp.toInt()}Hz sub=${if (ds.subOn) "ON" else "OFF"}", style = MaterialTheme.typography.bodySmall)
                Text("XO: ${if (ds.xoType == 2) "LR24" else "BW12"} ${if (ds.xoOn) "ON" else "OFF"}", style = MaterialTheme.typography.bodySmall)
                Text("TLF=${ds.tlf}dB THF=${ds.thf}dB", style = MaterialTheme.typography.bodySmall)
                Text("EQ: ${ds.eql}/${ds.eqm}/${ds.eqh}", style = MaterialTheme.typography.bodySmall)
// Строки контракта, раздела 2: Mute, SWP, DUP. Инверсии фазы
                // в v35 нет — команда inv: удалена, поле INV: тоже.
                Text("Mute: ${ds.muted[0]}/${ds.muted[1]}", style = MaterialTheme.typography.bodySmall)
                Text("SWP: ${if (ds.swapped) 1 else 0}", style = MaterialTheme.typography.bodySmall)
                Text("DUP: ${if (ds.dup) 1 else 0}", style = MaterialTheme.typography.bodySmall)
                Text("BT: ${if (ds.btAudioOn) "ON" else "OFF"} | SPP: ${if (ds.sppOn) "ON" else "OFF"}", style = MaterialTheme.typography.bodySmall)
                Text("Test: ${ds.testMode} TVol=${ds.testVol}%", style = MaterialTheme.typography.bodySmall)
                Text("Delay: ${ds.delays.joinToString("/")}", style = MaterialTheme.typography.bodySmall)
                Text("CHF: ${ds.chFilters.joinToString(" ") { "${it.first}/${it.second}" }}", style = MaterialTheme.typography.bodySmall)
                if (vm.fileLogger.exists) {
                    Text("Лог: ${vm.fileLogger.filePath}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionCatalog.DIAGNOSTICS.forEach { command ->
                ChoiceButton(
                    label = command,
                    onClick = { vm.sendDiagnostic(command); vm.logUi("DIAG", command) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_backup), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceButton(
                label = stringResource(R.string.action_backup),
                onClick = { vm.backupConfig(); vm.logUi("BACKUP", "save") },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
            ChoiceButton(
                label = stringResource(R.string.action_restore),
                onClick = { showRestoreDialog = true; vm.logUi("BACKUP", "restore dialog") },
                modifier = Modifier.weight(1f),
                enabled = enabled && vm.listBackups().isNotEmpty()
            )
        }
        val backups = vm.listBackups()
        if (backups.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Бекапы:", style = MaterialTheme.typography.bodySmall)
                backups.forEach { backupFile ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(backupFile.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        ChoiceButton(
                            label = stringResource(R.string.action_restore_short),
                            onClick = { vm.restoreConfig(backupFile); vm.logUi("BACKUP", "restore ${backupFile.name}") },
                            modifier = Modifier.weight(0f),
                            enabled = enabled
                        )
                        ChoiceButton(
                            label = stringResource(R.string.action_delete),
                            onClick = { vm.deleteBackup(backupFile); vm.logUi("BACKUP", "delete ${backupFile.name}") },
                            modifier = Modifier.weight(0f),
                            enabled = enabled
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_autoeq), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceButton(
                label = stringResource(R.string.action_autoeq_import),
                onClick = { showAutoEqDialog = true; autoEqFileLauncher.launch(arrayOf("*/*")); vm.logUi("AUTOEQ", "import dialog") },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_danger), style = MaterialTheme.typography.titleMedium)
        DangerConfirmButton(
            label = stringResource(R.string.action_save),
            onConfirm = { vm.saveParams(); vm.logUi("SAVE", "config") },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        DangerConfirmButton(
            label = stringResource(R.string.action_reboot),
            onConfirm = { vm.reboot(); vm.logUi("REBOOT", "device") },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        DangerConfirmButton(
            label = stringResource(R.string.action_factory),
            onConfirm = { vm.factoryReset(); vm.logUi("FACTORY", "reset") },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            destructive = true
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_docs), style = MaterialTheme.typography.titleMedium)
        ChoiceButton(
            label = stringResource(R.string.docs_button),
            onClick = { showDoc = true },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(stringResource(R.string.section_log), style = MaterialTheme.typography.titleMedium)
        BdkCard(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp)) {
            Column(modifier = Modifier.padding(6.dp).verticalScroll(rememberScrollState())) {
                log.takeLast(15).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showDoc) {
        DocSheet(onDismiss = { showDoc = false })
    }

    if (showRestoreDialog) {
        RestoreDialog(
            onDismiss = { showRestoreDialog = false },
            onRestore = { vm.restoreConfig(it) },
            backups = vm.listBackups(),
            enabled = enabled
        )
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(scroll)
            ) {
                lines.forEach { MdLine(it) }
            }
            Spacer(Modifier.height(12.dp))
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

@Composable
private fun RestoreDialog(
    onDismiss: () -> Unit,
    onRestore: (File) -> Unit,
    backups: List<File>,
    enabled: Boolean
) {
    var selectedBackup by remember { mutableStateOf<File?>(null) }

    val backupList = backups.joinToString(separator = "\n") { it.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_restore_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dialog_restore_text))
                Spacer(modifier = Modifier.height(8.dp))
                backups.forEach { backupFile ->
                    TextButton(
                        onClick = { selectedBackup = backupFile },
                        enabled = enabled
                    ) {
                        Text(backupFile.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selectedBackup != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val sb = selectedBackup
                    Text("Выбрано: ${sb?.name ?: ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sb = selectedBackup
                    if (sb != null) { onRestore(sb); onDismiss() }
                },
                enabled = selectedBackup != null && enabled
            ) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AutoEqDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_autoeq_title)) },
        text = { Text(stringResource(R.string.dialog_autoeq_text)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}


