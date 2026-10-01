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
package com.kilo.biampcontrol.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Приёмник команд для [CommandSender].
 *
 * Вынесен в интерфейс, чтобы очередь и троттлинг проверялись юнит-тестами
 * без Bluetooth-стека: тесту нужен только список отправленных строк.
 */
interface SppTransport {
    /** Отправляет команду с переводом строки. Обрыв сокета переживает молча. */
    fun sendLine(cmd: String)
}

/**
 * RFCOMM/SPP-клиент: подключение, автореконнект (5 попыток), построчное чтение.
 */
class SppManager(private val scope: CoroutineScope) : SppTransport {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val RETRY_DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)
    }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val lines: SharedFlow<String> = _lines

    /**
     * Всё состояние соединения живёт под [lock], а [generation] — номер
     * поколения текущей попытки подключения.
     *
     * `Job.cancel()` не прерывает блокирующий `socket.connect()`, поэтому
     * отменённый цикл соединения может вернуться из connect уже с готовым
     * сокетом и объявить CONNECTED после того, как пользователь отключился.
     * Поколение отсекает такие циклы: устаревший закрывает свой сокет и не
     * трогает ни состояние, ни поля — иначе он затирал бы сокет новой сессии.
     */
    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var generation = 0
    private var job: Job? = null
    @Volatile private var wantConnection = false

    fun connect(dev: BluetoothDevice) {
        val gen = synchronized(lock) { ++generation }
        wantConnection = true
        job?.cancel()
        job = scope.launch { connectLoop(dev, gen) }
    }

    fun disconnect() {
        wantConnection = false
        // Сокет отрываем и закрываем ДО отмены задачи: разблокирует readLine,
        // который иначе остался бы висеть до прихода следующего пакета.
        val stale = synchronized(lock) { generation++; detachLocked() }
        closeQuietly(stale)
        job?.cancel()
        job = null
        _state.value = ConnState.DISCONNECTED
    }

    override fun sendLine(cmd: String) {
        val s = synchronized(lock) { socket } ?: return
        try {
            s.outputStream.apply {
                write((cmd + "\n").toByteArray())
                flush()
            }
        } catch (_: Exception) {
            // Сокет умер. Закрываем его прямо здесь: заблокированный readLine
            // в цикле чтения сорвётся с IOException, и сработает автореконнект.
            // Иначе UI слал бы команды в никуда, не получая ни ошибки, ни
            // реакции, а приложение продолжало бы показывать CONNECTED.
            detach(s)?.let(::closeQuietly)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectLoop(dev: BluetoothDevice, gen: Int) {
        var attempt = 0
        while (wantConnection && isCurrent(gen)) {
            _state.value = if (attempt == 0) ConnState.CONNECTING else ConnState.RECONNECTING
            var s: BluetoothSocket? = null
            try {
                s = withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) {
                        dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                    }
                } ?: throw IOException("connect timeout")
                // Пока шёл connect(), пользователь мог нажать «Отключить» или
                // выбрать другое устройство. Этот сокет уже не нужен: закрываем
                // молча, не показывая CONNECTED от устройства, от которого
                // только что отказались.
                if (!isCurrent(gen)) {
                    closeQuietly(s)
                    return
                }
                synchronized(lock) { socket = s }
                _state.value = ConnState.CONNECTED
                attempt = 0
                readLoop(s, gen)          // вернётся при обрыве
            } catch (_: Exception) {
            } finally {
                // Отпускаем сокет, только если он всё ещё наш: у нового
                // поколения к этому моменту может быть свой.
                s?.let { detach(it)?.let(::closeQuietly) }
            }
            if (!wantConnection || !isCurrent(gen)) break
            if (attempt >= RETRY_DELAYS.size) {
                _state.value = ConnState.DISCONNECTED
                break
            }
            delay(RETRY_DELAYS[attempt])
            attempt++
        }
        if (isCurrent(gen) && !wantConnection) _state.value = ConnState.DISCONNECTED
    }

    private suspend fun readLoop(s: BluetoothSocket, gen: Int) = withContext(Dispatchers.IO) {
        BufferedReader(InputStreamReader(s.inputStream)).use { r ->
            while (isActive && isCurrent(gen)) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) _lines.tryEmit(line.trim())
            }
        }
    }

    /** Поколение [gen] ещё актуально и имеет право трогать состояние. */
    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen == generation }

    /** Отрывает [expected] от полей, если он всё ещё текущий. */
    private fun detach(expected: BluetoothSocket?): BluetoothSocket? =
        synchronized(lock) { if (socket === expected) detachLocked() else null }

    private fun detachLocked(): BluetoothSocket? = socket.also { socket = null }

    private fun closeQuietly(s: BluetoothSocket?) {
        try { s?.close() } catch (_: Exception) {}
    }
}