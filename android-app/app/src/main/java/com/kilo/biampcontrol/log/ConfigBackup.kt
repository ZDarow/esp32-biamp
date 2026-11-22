/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2.
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
package com.kilo.biampcontrol.log

import android.content.Context
import com.kilo.biampcontrol.bt.DeviceState
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Бекап и восстановление конфигурации аудио-устройства.
 *
 * Сохраняет текущее [DeviceState] (громкость, баланс, эквалайзер, кроссовер,
 * задержки, канальные фильтры и т.д.) в JSON-файл во внешнем файловом
 * каталоге приложения. Восстановление отправляет соответствующие команды
 * на ESP32 через [CommandSender].
 *
 * Не требует WRITE_EXTERNAL_STORAGE: каталог приложения доступен без разрешений
 * на всех версиях Android.
 *
 * Формат файла: `biamp_config_YYYYMMDD_HHMMSS.json`
 */
class ConfigBackup(private val context: Context) {

    private val DIR_NAME = "biamp_configs"
    private val FILE_PREFIX = "biamp_config_"
    private val FILE_SUFFIX = ".json"

    private val configsDir: File = File(context.getExternalFilesDir(null), DIR_NAME)

    /** Путь к каталогу с бекапами. */
    val backupDirPath: String get() = configsDir.absolutePath

    /** Инициализирует каталог при первом доступе. */
    init {
        configsDir.mkdirs()
    }

    /** Список файлов бекапов, отсортированных по дате (новые первыми). */
    fun listBackups(): List<File> {
        return configsDir.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Сохраняет текущее состояние устройства в файл бекапа. */
    fun backup(state: DeviceState): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(configsDir, "$FILE_PREFIX$timestamp$FILE_SUFFIX")
        val json = serialize(state)
        FileWriter(file).use { it.write(json) }
        return file
    }

    /** Загружает DeviceState из файла бекапа. */
    fun restore(file: File): DeviceState? {
        return try {
            val json = file.readText()
            deserialize(json)
        } catch (e: Exception) {
            null
        }
    }

    /** Удаляет файл бекапа. */
    fun delete(file: File): Boolean = file.delete()

    // ── Сериализация DeviceState → JSON ──────────────────────────────

    private fun serialize(state: DeviceState): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"biamp_config_v1\",\n")
        sb.append("  \"vol0\": ${state.vol0},\n")
        sb.append("  \"vol1\": ${state.vol1},\n")
        sb.append("  \"bal\": ${state.bal},\n")
        sb.append("  \"fc\": ${state.fc},\n")
        sb.append("  \"hp\": ${state.hp},\n")
        sb.append("  \"subOn\": ${state.subOn},\n")
        sb.append("  \"tlf\": ${state.tlf},\n")
        sb.append("  \"thf\": ${state.thf},\n")
        sb.append("  \"eql\": ${state.eql},\n")
        sb.append("  \"eqm\": ${state.eqm},\n")
        sb.append("  \"eqh\": ${state.eqh},\n")
        sb.append("  \"swapped\": ${state.swapped},\n")
        sb.append("  \"dup\": ${state.dup},\n")
        sb.append("  \"muted\": [${state.muted.joinToString { if (it) "true" else "false" }}],\n")
        sb.append("  \"xoType\": ${state.xoType},\n")
        sb.append("  \"xoOn\": ${state.xoOn},\n")
        sb.append("  \"delays\": [${state.delays.joinToString(",")}],\n")
        sb.append("  \"chFilters\": [")
        sb.append(state.chFilters.joinToString(",") { "(hp=${it.first},lp=${it.second})" })
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun deserialize(json: String): DeviceState? {
        // Простой парсер для нашего формата: ищем "ключ": значение
        val vol0 = extractInt(json, "vol0") ?: 10
        val vol1 = extractInt(json, "vol1") ?: 10
        val bal = extractFloat(json, "bal") ?: 0f
        val fc = extractFloat(json, "fc") ?: 400f
        val hp = extractFloat(json, "hp") ?: 45f
        val subOn = extractBool(json, "subOn") ?: true
        val tlf = extractFloat(json, "tlf") ?: 0f
        val thf = extractFloat(json, "thf") ?: -1f
        val eql = extractFloat(json, "eql") ?: 0f
        val eqm = extractFloat(json, "eqm") ?: 0f
        val eqh = extractFloat(json, "eqh") ?: 0f
        val swapped = extractBool(json, "swapped") ?: false
        val dup = extractBool(json, "dup") ?: false
        val muted = extractBoolList(json, "muted") ?: listOf(false, false)
        val xoType = extractInt(json, "xoType") ?: 1
        val xoOn = extractBool(json, "xoOn") ?: true
        val delays = extractIntList(json, "delays") ?: listOf(0, 0, 0, 0)
        val chFilters = extractChFilters(json) ?: listOf(0 to 0, 0 to 0, 0 to 0, 0 to 0)

        return DeviceState(
            vol0 = vol0, vol1 = vol1, bal = bal, fc = fc, hp = hp, subOn = subOn,
            tlf = tlf, thf = thf, eql = eql, eqm = eqm, eqh = eqh,
            swapped = swapped, dup = dup, muted = muted,
            xoType = xoType, xoOn = xoOn, delays = delays, chFilters = chFilters
        )
    }

    private fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(-?\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractFloat(json: String, key: String): Float? {
        val pattern = "\"$key\"\\s*:\\s*(-?\\d+\\.?\\d*)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractBool(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toBoolean()
    }

    private fun extractBoolList(json: String, key: String): List<Boolean>? {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .split(",")
            .map { it.trim().toBoolean() }
    }

    private fun extractIntList(json: String, key: String): List<Int>? {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .split(",")
            .map { it.trim().toIntOrNull() ?: 0 }
    }

    private fun extractChFilters(json: String): List<Pair<Int, Int>>? {
        val pattern = "\"chFilters\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .split("\\),")
            .mapNotNull { part ->
                val hpMatch = """hp=(-?\d+)""".toRegex().find(part)
                val lpMatch = """lp=(-?\d+)""".toRegex().find(part)
                if (hpMatch != null && lpMatch != null) {
                    hpMatch.groupValues[1].toInt() to lpMatch.groupValues[1].toInt()
                } else null
            }
    }

    companion object {
        private const val TAG = "ConfigBackup"
    }
}
