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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Логгер в файл во внешнем файловом каталоге приложения
 * (`Android/data/com.kilo.biampcontrol/files/biamp.log`).
 *
 * Не требует WRITE_EXTERNAL_STORAGE: каталог приложения доступен без разрешений
 * на всех версиях Android (Scoped Storage не ограничивает getExternalFilesDir).
 *
 * Файл ротируется при превышении [MAX_SIZE_BYTES]: старый переименовывается в
 * `.1`, новый начинается с нуля.
 */
class FileLogger(private val context: Context) {

    private val FILE_NAME = "biamp.log"
    private val MAX_SIZE_BYTES = 256L * 1024L  // 256 КБ

    private val logFile: File = File(context.getExternalFilesDir(null), FILE_NAME)
    private val lock = Any()

    /** Путь к файлу лога (для шаринга/просмотра). */
    val filePath: String get() = logFile.absolutePath

    /** Существует ли файл лога. */
    val exists: Boolean get() = logFile.exists()

    /** Удалить файл лога. */
    fun delete() {
        synchronized(lock) {
            logFile.delete()
        }
    }

    /** Записать строку с временной меткой. */
    fun log(tag: String, message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())} [$tag] $message"
        synchronized(lock) {
            rotateIfNeeded()
            FileWriter(logFile, true).use { it.append(line + "\n") }
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() >= MAX_SIZE_BYTES) {
            val rotated = File(context.getExternalFilesDir(null), "${FILE_NAME}.1")
            rotated.delete()
            logFile.renameTo(rotated)
        }
    }

    companion object {
        private const val TAG = "FileLogger"
    }
}
