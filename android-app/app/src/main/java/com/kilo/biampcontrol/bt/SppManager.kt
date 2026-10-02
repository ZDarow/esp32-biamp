/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth SPP: приложение открывает RFCOMM-сокет к
 * ESP32 ("ESP32 BiAmp Speaker") и обменивается текстовыми командами.
 * Формат команд и ответов — в firmware/protocol/status-contract.md, раздел 2.
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
import android.util.Log
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
 * Всё, что ViewModel знает о соединении с усилителем.
 *
 * Вынесено в интерфейс, чтобы instrumented-тесты подставляли заглушку:
 * реальный [SppManager] без сопряжённого ESP32 не может ни подключиться,
 * ни прочитать строку, а проверять надо именно поведение ViewModel —
 * разбор `status`, сброс буфера при переподключении, снятие флага
 * синхронизации. Тесты на [SppTransport] уже есть в unit-варианте.
 */
interface SppClient : SppTransport {
    val state: StateFlow<ConnState>
    val lines: SharedFlow<String>

    /**
     * Сколько строк SPP потеряно из-за переполнения буфера [lines].
     *
     * Часть контракта соединения, а не деталь реализации: ViewModel читает
     * счётчик через [SppClient], чтобы показать потерю строк в журнале,
     * поэтому заглушка в instrumented-тестах обязана его предоставить.
     */
    val droppedLines: StateFlow<Long>

    fun connect(dev: BluetoothDevice)
    fun disconnect()
}

/**
 * RFCOMM/SPP-клиент: подключение, автореконнект (5 попыток), построчное чтение.
 */
class SppManager(private val scope: CoroutineScope) : SppClient {

    companion object {
        private const val TAG = "SppManager"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val RETRY_DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

        /** Реальный предел ожидания `socket.connect()`. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /**
         * Предел длины строки ответа.
         *
         * Строка `status` — это несколько десятков символов. Всё, что длиннее,
         * либо мусор, либо залипший поток без переводов строк; без предела он
         * копился бы в `StringBuilder` до нехватки памяти.
         */
        const val MAX_LINE_CHARS = 512
    }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val lines: SharedFlow<String> = _lines

    /**
     * Сколько строк SPP потеряно из-за переполнения буфера [_lines].
     *
     * `tryEmit` при заполненном буфере возвращает `false`, и раньше этот
     * результат просто игнорировался: терялась строка `Delay:` — то есть
     * блок `status` не замыкался, состояние «залипало» на старом, а ползунки
     * не возвращались к реальным значениям. Счётчик показывается в журнале.
     */
    private val _droppedLines = MutableStateFlow(0L)
    override val droppedLines: StateFlow<Long> = _droppedLines

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

    override fun connect(dev: BluetoothDevice) {
        val gen = synchronized(lock) { ++generation }
        wantConnection = true
        job?.cancel()
        job = scope.launch { connectLoop(dev, gen) }
    }

    override fun disconnect() {
        wantConnection = false
        // Сокет отрываем и закрываем ДО отмены задачи: разблокирует readLine,
        // который иначе остался бы висеть до прихода следующего пакета.
        val stale = synchronized(lock) { generation++; detachLocked() }
        closeQuietly(stale)
        job?.cancel()
        job = null
        _state.value = ConnState.DISCONNECTED
    }

    /**
     * Отправка команды: санитайзер, затем запись в сокет.
     *
     * Строка очищается от символов вне `[A-Za-z0-9:_.-]` и обрезается до
     * [Limits.CMD_MAX_CHARS]. Прошивка исполняет присланную строку как команду,
     * поэтому встроенный перевод строки отправил бы сразу несколько команд.
     */
    override fun sendLine(cmd: String) {
        val line = CommandSanitizer.sanitize(cmd) ?: return
        val s = synchronized(lock) { socket } ?: return
        try {
            s.outputStream.apply {
                write((line + "\n").toByteArray(Charsets.UTF_8))
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
                s = openSocket(dev, gen)
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
            } catch (e: CancellationException) {
                // Отмена — это не «ошибка соединения». Пробрасываем её, иначе
                // цикл автореконнекта продолжил бы работу после disconnect()
                // и вернул приложение в CONNECTED без спроса пользователя.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Попытка соединения не удалась: ${e.message}")
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

    /**
     * Попытка открыть сокет с РЕАЛЬНЫМ пределом ожидания.
     *
     * `withTimeoutOrNull { socket.connect() }` не работает: `connect()` —
     * блокирующий нативный вызов, и отмена корутины не может прервать его.
     * Хуже того, отмена такого `withContext` не возвращает управление до
     * выхода из нативного вызова, то есть «таймаут» просто сдвигал проблему,
     * а сокет успевал утечь. Здесь вызов уходит в отдельную задачу, а
     * ожидание идёт по [CompletableDeferred] — это обычное отменяемое
     * приостановление.
     *
     * По истечении предела сокет закрывается прямо из этой функции, что
     * разблокирует нативный `connect()`, а сама задача, увидев
     * [ConnectAttempt.abandoned], закрывает его ещё раз и не отдаёт его
     * никому. Сокет не теряется ни в одном из исходов.
     */
    @SuppressLint("MissingPermission")
    private suspend fun openSocket(dev: BluetoothDevice, gen: Int): BluetoothSocket {
        val attempt = ConnectAttempt()
        val ready = CompletableDeferred<BluetoothSocket?>()
        scope.launch(Dispatchers.IO) {
            val s = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            attempt.sock = s
            if (attempt.abandoned) {
                closeQuietly(s)
                ready.complete(null)
                return@launch
            }
            var ok = false
            try {
                s.connect()
                ok = true
            } catch (e: Exception) {
                Log.w(TAG, "connect() не удался: ${e.message}")
            }
            if (!ok || attempt.abandoned) {
                // Пользователь успел нажать «Отключить» или предел ожидания
                // истёк: сокет закрываем здесь и наружу не отдаём.
                closeQuietly(s)
                ready.complete(null)
            } else if (!isCurrent(gen) || !wantConnection) {
                closeQuietly(s)
                ready.complete(null)
            } else {
                ready.complete(s)
            }
        }
        val s = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (s == null) {
            attempt.abandoned = true
            // Закрытие здесь — единственный способ прервать зависший connect().
            closeQuietly(attempt.sock)
            throw IOException("connect timeout ${CONNECT_TIMEOUT_MS} мс")
        }
        return s
    }

    /** Состояние одной попытки соединения, разделяемое с задачей `connect()`. */
    private class ConnectAttempt {
        @Volatile var sock: BluetoothSocket? = null
        @Volatile var abandoned = false
    }

    private suspend fun readLoop(s: BluetoothSocket, gen: Int) = withContext(Dispatchers.IO) {
        BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).use { r ->
            while (isActive && isCurrent(gen)) {
                val (t, skipped) = readLineLimited(r) ?: break
                if (skipped > 0) {
                    Log.w(TAG, "Строка длиннее $MAX_LINE_CHARS симв. обрезана, отброшено символов: $skipped")
                }
                if (t.isEmpty()) continue
                if (!_lines.tryEmit(t)) {
                    // Буфер переполнен: строка потеряна. Молчать об этом
                    // нельзя — потеря `Delay:` или `V0=` ломает разбор
                    // статуса и оставляет «залипшие» значения на экране.
                    _droppedLines.value += 1
                    Log.w(TAG, "Буфер SPP переполнен, строка отброшена: $t")
                }
            }
        }
    }

    /**
     * Чтение строки с пределом длины [MAX_LINE_CHARS].
     *
     * `BufferedReader.readLine()` не ограничен: поток без переводов строк
     * заставил бы его расти до нехватки памяти. Здесь строка обрезается, а
     * лишнее дочитывается до конца строки: иначе хвост обрезанной строки
     * был бы прочитан как отдельная «новая» строка.
     *
     * @return пара «строка без CR» и «сколько символов отброшено», либо
     *   `null`, если поток закончился ровно на границе строк.
     */
    private fun readLineLimited(r: BufferedReader): Pair<String, Int>? {
        val sb = StringBuilder()
        var skipped = 0
        while (true) {
            val c = r.read()
            if (c == -1) return if (sb.isEmpty() && skipped == 0) null else sb.toString() to skipped
            if (c == '\n'.code) return sb.toString() to skipped
            if (sb.length < MAX_LINE_CHARS) {
                if (c != '\r'.code) sb.append(c.toChar())
            } else {
                skipped++
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
