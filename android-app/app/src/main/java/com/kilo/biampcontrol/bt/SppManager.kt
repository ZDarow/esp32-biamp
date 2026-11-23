/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth SPP (RFCOMM, Bluetooth Classic):
 * приложение подключается к ESP32 ("ESP32 BiAmp Speaker") через сокет
 * SPP UUID 00001101-0000-1000-8000-00805F9B34FB и обменивается текстовыми
 * командами. Формат команд и ответов — в protocol/status-contract.md,
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
package com.kilo.biampcontrol.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Приёмник команд для [CommandSender].
 */
interface SppTransport {
    /** Отправляет команду с переводом строки. Обрыв сокета переживает молча. */
    fun sendLine(cmd: String)
}

/**
 * Всё, что ViewModel знает о соединении с усилителем.
 */
interface SppClient : SppTransport {
    val state: StateFlow<ConnState>
    val lines: SharedFlow<String>
    val droppedLines: StateFlow<Long>
    val log: SharedFlow<String>
    fun connect(dev: BluetoothDevice)
    fun disconnect()
}

/**
 * SPP/RFCOMM-клиент: подключение к ESP32 через сокет Bluetooth Classic,
 * запись команд в выходной поток, приём строк через входящий поток.
 * Автореконнект (5 попыток), построчное чтение.
 */
class SppManager(
    private val scope: CoroutineScope,
    private val context: Context
) : SppClient {

    companion object {
        private const val TAG = "SppManager"

        // Стандартный UUID профиля SPP (Serial Port Profile), Bluetooth Classic.
        val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val RETRY_DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

        /** Реальный предел ожидания подключения сокета. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Предел длины строки ответа. */
        const val MAX_LINE_CHARS = 512
    }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val lines: SharedFlow<String> = _lines

    private val _droppedLines = MutableStateFlow(0L)
    override val droppedLines: StateFlow<Long> = _droppedLines

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 64, replay = 10)
    override val log: SharedFlow<String> = _log

    private fun logEvent(tag: String, msg: String) {
        val line = "[$tag] $msg"
        Log.d(TAG, line)
        if (!_log.tryEmit(line)) {
            _droppedLines.value += 1
        }
    }

    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var targetDevice: BluetoothDevice? = null
    private var generation = 0
    private var job: Job? = null
    @Volatile private var wantConnection = false

    // Очередь записи: одна команда за раз.
    private val writeQueue = ArrayDeque<String>()
    private var isWriting = false

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var disconnectDeferred: CompletableDeferred<Unit>? = null

    override fun connect(dev: BluetoothDevice) {
        val gen = synchronized(lock) { ++generation }
        wantConnection = true
        targetDevice = dev
        logEvent("CONN", "connect to ${dev.address} (${dev.name ?: "unknown"})")
        job?.cancel()
        job = scope.launch { connectLoop(dev, gen) }
    }

    override fun disconnect() {
        wantConnection = false
        logEvent("CONN", "disconnect requested")
        synchronized(lock) {
            generation++
            disconnectDeferred?.complete(Unit)
        }
        closeSocket()
        job?.cancel()
        job = null
        _state.value = ConnState.DISCONNECTED
    }

    private fun closeSocket() {
        synchronized(lock) {
            socket?.inputStream?.close()
            socket?.outputStream?.close()
            socket?.close()
            socket = null
            isWriting = false
            writeQueue.clear()
        }
    }

    override fun sendLine(cmd: String) {
        val line = CommandSanitizer.sanitize(cmd) ?: return
        logEvent("CMD>", line)
        synchronized(lock) {
            writeQueue.addLast(line)
        }
        processWriteQueue()
    }

    private fun processWriteQueue() {
        val cmd = synchronized(lock) {
            if (isWriting || writeQueue.isEmpty()) return
            isWriting = true
            writeQueue.removeFirst()
        }
        val s = synchronized(lock) { socket }
        if (s == null) {
            synchronized(lock) { isWriting = false }
            return
        }
        try {
            s.outputStream.write((cmd + "\n").toByteArray())
            s.outputStream.flush()
            logEvent("SPP_WRITE", "command written OK")
        } catch (e: Exception) {
            logEvent("SPP_WRITE", "command write failed: ${e.message}")
        } finally {
            synchronized(lock) { isWriting = false }
            processWriteQueue()
        }
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen == generation }

    @SuppressLint("MissingPermission")
    private suspend fun connectLoop(dev: BluetoothDevice, gen: Int) {
        var attempt = 0
        while (wantConnection && isCurrent(gen)) {
            _state.value = if (attempt == 0) ConnState.CONNECTING else ConnState.RECONNECTING

            connectDeferred = CompletableDeferred<Boolean>()

            val sock = try {
                dev.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: Exception) {
                logEvent("CONN", "createRfcommSocket failed: ${e.message}")
                null
            }

            if (sock == null) {
                synchronized(lock) { this.socket = null }
                connectDeferred = null
                if (!wantConnection || !isCurrent(gen)) break
                if (attempt >= RETRY_DELAYS.size) {
                    _state.value = ConnState.DISCONNECTED
                    break
                }
                delay(RETRY_DELAYS[attempt])
                attempt++
                continue
            }

            val connOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                try {
                    sock.connect()
                    true
                } catch (e: Exception) {
                    logEvent("CONN", "socket connect failed: ${e.message}")
                    false
                }
            }

            if (!(connOk ?: false) || !isCurrent(gen) || !wantConnection) {
                try { sock.close() } catch (_: Exception) {}
                synchronized(lock) { this.socket = null }
                connectDeferred = null
                if (!wantConnection || !isCurrent(gen)) break
                if (attempt >= RETRY_DELAYS.size) {
                    _state.value = ConnState.DISCONNECTED
                    break
                }
                delay(RETRY_DELAYS[attempt])
                attempt++
                continue
            }

            synchronized(lock) { this.socket = sock }

            connectDeferred = null
            _state.value = ConnState.CONNECTED

            // Запускаем чтение строк из сокета.
            val readJob = scope.launch(Dispatchers.IO) {
                readLines(sock)
            }

            disconnectDeferred = CompletableDeferred<Unit>()
            try {
                disconnectDeferred!!.await()
            } catch (e: CancellationException) {
                throw e
            }

            readJob.cancel()
            disconnectDeferred = null
            closeSocket()

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

    private fun readLines(sock: BluetoothSocket) {
        val input = sock.inputStream
        val reader = BufferedReader(InputStreamReader(input))
        try {
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    logEvent("SPP_READ", "stream closed")
                    break
                }
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (!_lines.tryEmit(trimmed)) {
                        _droppedLines.value += 1
                    }
                    logEvent("SPP_READ", trimmed)
                }
            }
        } catch (e: Exception) {
            logEvent("SPP_READ", "read error: ${e.message}")
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
    }
}
