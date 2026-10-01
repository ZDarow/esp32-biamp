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

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Очередь команд с троттлингом: «ползунковые» команды идут не чаще
 * 150 мс на префикс, причём новое значение ВЫТЕСНЯЕТ старое.
 * Остальные команды (транспорт, пресеты, тесты) — немедленно.
 */
class CommandSender(private val spp: SppManager, private val scope: CoroutineScope) {

    /**
     * Префиксы, по которым команда проходит через троттлинг.
     *
     * У поканальных параметров ключ включает номер канала: степпер полосы
     * отправляет chhp:0:… и chhp:2:… одной пачкой, и при ключе «chhp:»
     * вторая команда вытеснила бы первую — левый канал молча остался бы
     * на старой частоте. Номер канала в префиксе сохраняет независимость
     * каналов при общем ограничении частоты.
     */
    private val throttlePrefixes = listOf(
        "vol:", "v0:", "v1:", "bal:", "fc:", "hp:",
        "tlf:", "thf:", "eql:", "eqm:", "eqh:",
        "delay0:", "delay1:", "delay2:", "delay3:",
        "chhp:0:", "chhp:1:", "chhp:2:", "chhp:3:",
        "chlp:0:", "chlp:1:", "chlp:2:", "chlp:3:",
        "tf:"
    )

    private val immediate = Channel<String>(Channel.UNLIMITED)
    private val pending = LinkedHashMap<String, String>()
    private var lastBatchAt = 0L

    fun send(cmd: String, force: Boolean = false) {
        val prefix = throttlePrefixes.firstOrNull { cmd.startsWith(it) }
        if (prefix == null || force) immediate.trySend(cmd)
        else synchronized(pending) { pending[prefix] = cmd }
    }

    fun start(): Job = scope.launch {
        lastBatchAt = System.currentTimeMillis()
        while (isActive) {
            // 1) немедленные команды
            while (true) {
                val cmd = immediate.tryReceive().getOrNull() ?: break
                spp.sendLine(cmd)
            }
            // 2) накопленные «ползунковые» — пакетом раз в 150 мс
            val now = System.currentTimeMillis()
            if (now - lastBatchAt >= 150) {
                val batch = synchronized(pending) {
                    if (pending.isEmpty()) emptyList()
                    else { val l = pending.values.toList(); pending.clear(); l }
                }
                for (c in batch) spp.sendLine(c)
                if (batch.isNotEmpty()) lastBatchAt = now
            }
            delay(25)
        }
    }
}