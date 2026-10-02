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

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Очередь команд с троттлингом: «ползунковые» команды идут не чаще
 * 150 мс на префикс, причём новое значение ВЫТЕСНЯЕТ старое.
 * Остальные команды (транспорт, пресеты, тесты) — немедленно.
 *
 * Запись выполняется ОДНИМ циклом. Это не оптимизация, а требование: два
 * писателя в один сокет перемежали бы байты (`v0` + `bal:` → `v0:4bal:0`),
 * и прошивка разобрала бы это как команду другого вида. Поэтому очередь
 * не раздаётся писателям, а цикл ниже — единственный, кто держит
 * `outputStream`.
 */
class CommandSender(
    private val spp: SppTransport,
    private val scope: CoroutineScope,
    /**
     * Источник времени для окна троттлинга. Продлён параметром, а не берётся
     * из `System.currentTimeMillis()` внутри цикла, чтобы юнит-тест управлял
     * часами виртуального времени: иначе проверка 150-миллисекундного окна
     * зависела бы от скорости машины.
     */
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Диспетчер цикла отправки.
     *
     * Раньше цикл шёл на диспетчере вызывающего, то есть на главном потоке
     * UI: синхронные `write()`/`flush()` в сокет блокировали кадры и вели к
     * ANR. Диспетчер вынесен параметром, чтобы юнит-тест подставлял
     * тестовый и не зависел от реальных потоков.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Журнал потерь. Продлён параметром, чтобы юнит-тесты не упирались в
     * заглушку `android.util.Log` (в JVM-тестах она бросает «Stub!»).
     */
    private val logger: (String) -> Unit = { Log.w(TAG, it) }
) {

    /**
     * Префиксы, по которым команда проходит через троттлинг.
     *
     * У поканальных параметров ключ включает номер канала: степпер полосы
     * отправляет chhp:0:… и chhp:2:… одной пачкой, и при ключе «chhp:»
     * вторая команда вытеснила бы первую — левый канал молча остался бы
     * на старой частоте. Номер канала в префиксе сохраняет независимость
     * каналов при общем ограничении частоты.
     *
     * `tvol:` — громкость тест-сигнала, такой же ползунок: без троттлинга
     * он уходил в немедленную очередь и забивал SPP на каждом кадре
     * перетаскивания, вытесняя оттуда команды транспорта.
     */
    private val throttlePrefixes = listOf(
        "vol:", "v0:", "v1:", "bal:", "fc:", "hp:",
        "tlf:", "thf:", "eql:", "eqm:", "eqh:",
        "tvol:",
        "delay0:", "delay1:", "delay2:", "delay3:",
        "chhp:0:", "chhp:1:", "chhp:2:", "chhp:3:",
        "chlp:0:", "chlp:1:", "chlp:2:", "chlp:3:",
        "tf:"
    )

    /**
     * Ёмкость неотложной очереди.
     *
     * Раньше стоял `Channel.UNLIMITED`: при зависшем сокете (а он зависает
     * при потере связи) очередь росла без предела, пока процесс не умирал
     * по нехватке памяти. 256 команд — это больше, чем успевает отправить
     * SPP за секунду, поэтому нормальный режим предел не трогает, а зависший
     * конец получает явную потерю команд в журнал вместо текущей памяти.
     */
    private val immediate = Channel<String>(IMMEDIATE_CAPACITY)
    private val pending = LinkedHashMap<String, String>()
    private var lastBatchAt = 0L

    /** Сколько команд отброшено из-за переполнения очереди. */
    private val _dropped = MutableStateFlow(0)
    val dropped: StateFlow<Int> = _dropped

    fun send(cmd: String, force: Boolean = false) {
        val prefix = throttlePrefixes.firstOrNull { cmd.startsWith(it) }
        if (prefix == null || force) {
            if (immediate.trySend(cmd).isFailure) {
                _dropped.value += 1
                logger("Очередь команд переполнена ($IMMEDIATE_CAPACITY), команда отброшена: $cmd")
            }
        } else synchronized(pending) { pending[prefix] = cmd }
    }

    /**
     * Сброс очередей при смене сессии.
     *
     * Значения, накопленные до обрыва, относятся к ПРЕЖНЕМУ устройству: после
     * реконнекта они ушли бы на новый усилитель — и тот получил бы, например,
     * чужую громкость. Вызывается при CONNECTED и при DISCONNECTED.
     *
     * @return сколько команд было отброшено (для журнала).
     */
    fun clearQueues(): Int {
        var dropped = 0
        while (immediate.tryReceive().getOrNull() != null) dropped++
        synchronized(pending) {
            dropped += pending.size
            pending.clear()
        }
        lastBatchAt = now()
        if (dropped > 0) logger("Очереди очищены при смене сессии, отброшено команд: $dropped")
        return dropped
    }

    fun start(): Job = scope.launch(io) {
        lastBatchAt = now()
        while (isActive) {
            // 1) немедленные команды
            while (true) {
                val cmd = immediate.tryReceive().getOrNull() ?: break
                spp.sendLine(cmd)
            }
            // 2) накопленные «ползунковые» — пакетом раз в 150 мс
            val t = now()
            if (t - lastBatchAt >= THROTTLE_MS) {
                val batch = synchronized(pending) {
                    if (pending.isEmpty()) emptyList()
                    else { val l = pending.values.toList(); pending.clear(); l }
                }
                for (c in batch) spp.sendLine(c)
                if (batch.isNotEmpty()) lastBatchAt = t
            }
            delay(25)
        }
    }

    companion object {
        private const val TAG = "CommandSender"
        /** Окно троттлинга «ползунковых» команд. */
        const val THROTTLE_MS = 150L
        /** Предел неотложной очереди: проверяется юнит-тестом. */
        const val IMMEDIATE_CAPACITY = 256
    }
}
