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
import java.io.OutputStream
import java.util.UUID

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * RFCOMM/SPP-клиент: подключение, автореконнект (5 попыток), построчное чтение.
 */
class SppManager(private val scope: CoroutineScope) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val RETRY_DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)
    }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val lines: SharedFlow<String> = _lines

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var job: Job? = null
    @Volatile private var wantConnection = false

    fun connect(dev: BluetoothDevice) {
        wantConnection = true
        job?.cancel()
        job = scope.launch { connectLoop(dev) }
    }

    fun disconnect() {
        wantConnection = false
        job?.cancel()
        closeQuietly()
        _state.value = ConnState.DISCONNECTED
    }

    fun sendLine(cmd: String) {
        try {
            output?.let {
                it.write((cmd + "\n").toByteArray())
                it.flush()
            }
        } catch (_: Exception) { /* сокет умер — обнаружит цикл чтения */ }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectLoop(dev: BluetoothDevice) {
        var attempt = 0
        while (wantConnection) {
            _state.value = if (attempt == 0) ConnState.CONNECTING else ConnState.RECONNECTING
            try {
                val s = withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) {
                        dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                    }
                } ?: throw IOException("connect timeout")
                socket = s
                output = s.outputStream
                _state.value = ConnState.CONNECTED
                attempt = 0
                readLoop(s)          // вернётся при обрыве
            } catch (_: Exception) { }
            closeQuietly()
            if (!wantConnection) break
            if (attempt >= RETRY_DELAYS.size) {
                _state.value = ConnState.DISCONNECTED
                break
            }
            delay(RETRY_DELAYS[attempt])
            attempt++
        }
        if (!wantConnection) _state.value = ConnState.DISCONNECTED
    }

    private suspend fun readLoop(s: BluetoothSocket) = withContext(Dispatchers.IO) {
        BufferedReader(InputStreamReader(s.inputStream)).use { r ->
            while (isActive) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) _lines.tryEmit(line.trim())
            }
        }
    }

    private fun closeQuietly() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
    }
}