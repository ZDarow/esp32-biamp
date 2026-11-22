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
package com.kilo.biampcontrol.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
 * BLE GATT-клиент: подключение к ESP32, обнаружение сервиса 0xF000,
 * запись команд в характеристику 0xF001, приём статуса через уведомления
 * от характеристики 0xF002. Автореконнект (5 попыток), построчное чтение.
 */
class SppManager(
    private val scope: CoroutineScope,
    private val context: Context
) : SppClient {

    companion object {
        private const val TAG = "SppManager"

        // UUID совпадают с прошивкой ESP32 (BLE GATT сервис 0xF000)
        val SERVICE_UUID = UUID.fromString("0000F000-0000-1000-8000-00805F9B34FB")
        val CHAR_CMD_UUID = UUID.fromString("0000F001-0000-1000-8000-00805F9B34FB")
        val CHAR_STATUS_UUID = UUID.fromString("0000F002-0000-1000-8000-00805F9B34FB")

        private val RETRY_DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

        /** Реальный предел ожидания connectGatt / обнаружения сервисов. */
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
    private var gatt: BluetoothGatt? = null
    private var charCmd: BluetoothGattCharacteristic? = null
    private var charStatus: BluetoothGattCharacteristic? = null
    private var targetDevice: BluetoothDevice? = null
    private var generation = 0
    private var job: Job? = null
    @Volatile private var wantConnection = false

    // Очередь записи: одна команда за раз, ждём подтверждения onCharacteristicWrite
    private val writeQueue = ArrayDeque<String>()
    private var isWriting = false

    // Deferred'ы для асинхронных операций GATT
    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var disconnectDeferred: CompletableDeferred<Unit>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                logEvent("GATT", "connected, status=$status")
                connectDeferred?.complete(true)
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                logEvent("GATT", "disconnected, status=$status")
                connectDeferred?.complete(false)
                disconnectDeferred?.complete(Unit)
                synchronized(lock) {
                    charCmd = null
                    charStatus = null
                    this@SppManager.gatt = null
                    isWriting = false
                    writeQueue.clear()
                }
                if (wantConnection) {
                    _state.value = ConnState.RECONNECTING
                    logEvent("CONN", "scheduling reconnect")
                    scope.launch { reconnectLoop() }
                } else {
                    _state.value = ConnState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logEvent("GATT", "service discovery failed, status=$status")
                return
            }
            val service = gatt.getService(SERVICE_UUID) ?: return
            synchronized(lock) {
                charCmd = service.getCharacteristic(CHAR_CMD_UUID)
                val cs = service.getCharacteristic(CHAR_STATUS_UUID)
                charStatus = cs
                if (cs != null) {
                    gatt.setCharacteristicNotification(cs, true)
                    val cccd = cs.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    if (cccd != null) {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                }
            }
            logEvent("GATT", "services discovered, service=0xF000, cmd=0xF001, status=0xF002")
            _state.value = ConnState.CONNECTED
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logEvent("GATT_WRITE", "command written OK")
            } else {
                logEvent("GATT_WRITE", "command write failed, status=$status")
            }
            synchronized(lock) { isWriting = false }
            processWriteQueue()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != CHAR_STATUS_UUID) return
            val data = characteristic.value ?: return
            val text = String(data, Charsets.UTF_8).trim()
            if (text.isNotEmpty()) {
                logEvent("GATT_READ", text)
                for (raw in text.split("\n")) {
                    val line = raw.trim()
                    if (line.isNotEmpty()) {
                        if (!_lines.tryEmit(line)) {
                            _droppedLines.value += 1
                        }
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Уведомления включены/выключены
        }
    }

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
        closeGatt()
        job?.cancel()
        job = null
        _state.value = ConnState.DISCONNECTED
    }

    private fun closeGatt() {
        synchronized(lock) {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            charCmd = null
            charStatus = null
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
        val c = synchronized(lock) { charCmd }
        val g = synchronized(lock) { gatt }
        if (c == null || g == null) {
            synchronized(lock) { isWriting = false }
            processWriteQueue()
            return
        }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.setValue((cmd + "\n").toByteArray(Charsets.UTF_8))
        if (!g.writeCharacteristic(c)) {
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
            servicesDeferred = CompletableDeferred<Boolean>()

            val gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

            val connOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectDeferred!!.await() }
            if (connOk != true || !isCurrent(gen) || !wantConnection) {
                gatt.close()
                synchronized(lock) { this.gatt = null }
                connectDeferred = null
                servicesDeferred = null
                if (!wantConnection || !isCurrent(gen)) break
                if (attempt >= RETRY_DELAYS.size) {
                    _state.value = ConnState.DISCONNECTED
                    break
                }
                delay(RETRY_DELAYS[attempt])
                attempt++
                continue
            }

            synchronized(lock) { this.gatt = gatt }

            val svcOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { servicesDeferred!!.await() }
            if (svcOk != true || !isCurrent(gen) || !wantConnection) {
                gatt.close()
                synchronized(lock) { this.gatt = null }
                connectDeferred = null
                servicesDeferred = null
                if (!wantConnection || !isCurrent(gen)) break
                if (attempt >= RETRY_DELAYS.size) {
                    _state.value = ConnState.DISCONNECTED
                    break
                }
                delay(RETRY_DELAYS[attempt])
                attempt++
                continue
            }

            connectDeferred = null
            servicesDeferred = null

            disconnectDeferred = CompletableDeferred<Unit>()
            try {
                disconnectDeferred!!.await()
            } catch (e: CancellationException) {
                throw e
            }

            disconnectDeferred = null
            closeGatt()

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

    private suspend fun reconnectLoop() {
        var attempt = 0
        val dev = targetDevice ?: return
        val gen = synchronized(lock) { generation }

        while (wantConnection && isCurrent(gen)) {
            if (attempt >= RETRY_DELAYS.size) {
                _state.value = ConnState.DISCONNECTED
                return
            }
            delay(RETRY_DELAYS[attempt])
            attempt++

            connectDeferred = CompletableDeferred<Boolean>()
            servicesDeferred = CompletableDeferred<Boolean>()

            val gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

            val connOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectDeferred!!.await() }
            if (connOk != true || !isCurrent(gen) || !wantConnection) {
                gatt.close()
                synchronized(lock) { this.gatt = null }
                connectDeferred = null
                servicesDeferred = null
                continue
            }

            synchronized(lock) { this.gatt = gatt }

            val svcOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { servicesDeferred!!.await() }
            if (svcOk != true || !isCurrent(gen) || !wantConnection) {
                gatt.close()
                synchronized(lock) { this.gatt = null }
                connectDeferred = null
                servicesDeferred = null
                continue
            }

            connectDeferred = null
            servicesDeferred = null

            _state.value = ConnState.CONNECTED

            disconnectDeferred = CompletableDeferred<Unit>()
            try {
                disconnectDeferred!!.await()
            } catch (e: CancellationException) {
                throw e
            }

            disconnectDeferred = null
            closeGatt()
        }
    }
}
