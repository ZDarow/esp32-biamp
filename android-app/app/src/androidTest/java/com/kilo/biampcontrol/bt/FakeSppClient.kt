/*
 * Заглушка соединения для instrumented-тестов.
 *
 * Усилитель не сопряжён с телефоном и сопрягать его ради прогона нельзя:
 * это состояние внешнего устройства, не зависящее от того, запущен ли у
 * кого-то Gradle. Поэтому [SppClient] подменяется заглушкой — проверяются
 * логика ViewModel и композаблов, а не Bluetooth. Заглушка отвечает блоком
 * status на команду `status`, как настоящий усилитель, поэтому тесты
 * проверяют ещё и порядок «запрос → ответ».
 *
 * Реальный Bluetooth-покров (`SppManager`, повторные попытки, чтение строк)
 * заглушкой не проверяется и тестируется вручную на живом усилителе.
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

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Соединение без Bluetooth: строки отдаёт тест, команды копятся в [sent].
 */
class FakeSppClient : SppClient {

    /**
     * Всё, что приложение отправило в SPP, в порядке отправки.
     *
     * Копирующий список: команды приходят из `viewModelScope` (главный поток),
     * а тест читает их из своего потока и из `Dispatchers.Default` — обычный
     * `MutableList` в такой паре может отдать читателю половину списка
     * и сломать тест.
     */
    val sent: MutableList<String> = CopyOnWriteArrayList()

    /**
     * Только команды mute.
     *
     * Отдельно от [sent], потому что ViewModel раз в три секунды опрашивает
     * усилитель командой `status`: в общем списке она неотличима от команды
     * под пользователем, а проверять нужно именно пользовательские.
     */
    val mutes: List<String> get() = sent.filter { it.startsWith("mute:") }

    /** Счётчик подключений: позволяет проверить автоподключение и его отсутствие. */
    var connectCalls = 0
        private set

    /** Счётчик явных отключений. */
    var disconnectCalls = 0
        private set

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    override val lines: SharedFlow<String> = _lines.asSharedFlow()

    override fun connect(dev: BluetoothDevice) {
        connectCalls++
        _state.value = ConnState.CONNECTING
    }

    override fun disconnect() {
        disconnectCalls++
        _state.value = ConnState.DISCONNECTED
    }

    override fun sendLine(cmd: String) {
        sent += cmd
    }

    /**
     * Ответ усилителя: переводит соединение в CONNECTED и отдаёт строки.
     *
     * Строки отдаются не сразу, а в ответ на команду `status`, как это делает
     * настоящий усилитель. Порядок важен: при CONNECTED ViewModel очищает буфер
     * строк, и если бы заглушка отдала блок раньше, значения попали бы в буфер
     * до очистки и пропали бы — тест падал бы в зависимости от порядка тестов.
     *
     * Ожидание подписчика на [lines] тоже обязательно: сбор строк ViewModel
     * запускается в `viewModelScope`, а тест идёт не в главном потоке, и без
     * паузы строки ушли бы в пустоту — попытка emit в SharedFlow без replay
     * при отсутствии подписчиков теряется молча.
     */
    suspend fun deviceAnswers(vararg lines: String) {
        val askedBefore = sent.count { it == "status" }
        _state.value = ConnState.CONNECTED
        _lines.subscriptionCount.first { it > 0 }
        // Ждём запрос status, отправленный после перехода в CONNECTED.
        withContext(Dispatchers.Default) {
            withTimeout(STATUS_REQUEST_TIMEOUT_MS) {
                while (sent.count { it == "status" } == askedBefore) delay(10)
            }
        }
        lines.forEach { _lines.emit(it) }
    }

    /** Обрыв связи: приложение должно увидеть DISCONNECTED. */
    fun linkLost() {
        _state.value = ConnState.DISCONNECTED
    }

    /** Забывает отправленные команды — удобно между шагами одного теста. */
    fun clearSent() {
        sent.clear()
    }

    private companion object {
        /** Сколько ждать запроса status, на который заглушка отвечает блоком. */
        const val STATUS_REQUEST_TIMEOUT_MS = 5_000L
    }
}