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

/** Состояние устройства, распарсенное из ответа `status`. */
data class DeviceState(
    val vol0: Int = 10,
    val vol1: Int = 10,
    val bal: Float = 0f,
    val fc: Float = 400f,
    val hp: Float = 45f,
    val subOn: Boolean = true,
    val tlf: Float = 0f,
    val thf: Float = -1f,
    val eql: Float = 0f,
    val eqm: Float = 0f,
    val eqh: Float = 0f,
    val inv: List<Boolean> = listOf(false, false, false, false),
val btAudioOn: Boolean = false,
    val sppOn: Boolean = false,
    val testMode: Int = 0,
    val testVol: Int = 6,                       // громкость тест-сигнала, %
    val xoType: Int = 1,                       // 1 = Butterworth, 2 = LR4
    val srcKhz: String = "44.1",                // частота источника из строки "Src:"
    val delays: List<Int> = listOf(0, 0, 0, 0), // сэмплы для delay0..delay3
    val chFilters: List<Pair<Int, Int>> = listOf(0 to 0, 0 to 0, 0 to 0, 0 to 0) // (hp, lp) для ch0..ch3
)

object StatusParser {
    private val reVol  = Regex("V0=(\\d+)%\\s+V1=(\\d+)%\\s+bal=(-?[\\d.]+)")
    private val reFc   = Regex("Fc=([\\d.]+)Hz\\s+hp=([\\d.]+)Hz\\s+sub=(ON|OFF)")
    private val reTrim = Regex("TLF=(-?[\\d.]+)dB\\s+THF=(-?[\\d.]+)dB")
    private val reEq   = Regex("EQ:\\s*L=(-?[\\d.]+)\\s+M=(-?[\\d.]+)\\s+H=(-?[\\d.]+)")
    private val reInv  = Regex("INV:\\s+([01]{4})")
    // ИСПРАВЛЕНО: парсит "BT: ON | SPP: ON" или "BT: ON"
    private val reBt   = Regex("BT:\\s+(ON|OFF)(?:\\s*\\|\\s*SPP:\\s+(ON|OFF))?")
    // "Test: 0 TVol=6%" — v29 добавил громкость тест-сигнала в ту же строку
    private val reTest = Regex("Test:\\s+(\\d+)(?:\\s+TVol=(\\d+)%)?")
    // "Src: 48 kHz" — частота, согласованная по переговорам A2DP
    private val reSrc  = Regex("Src:\\s+([\\d.]+)\\s*kHz")
    // v18: XO: Butter | XO: LR4
    private val reXo   = Regex("XO:\\s+(Butter|LR4)")
    // v18: CHF: 0/0 0/0 0/0 0/0  (hp0/lp0 hp1/lp1 hp2/lp2 hp3/lp3)
    private val reChf  = Regex("CHF:\\s+(\\d+)/(\\d+)\\s+(\\d+)/(\\d+)\\s+(\\d+)/(\\d+)\\s+(\\d+)/(\\d+)")
    // v18: Delay: 0/0/0/0  — последняя строка блока status в прошивке
    private val reDly  = Regex("Delay:\\s+(\\d+)/(\\d+)/(\\d+)/(\\d+)")

    /**
     * Признак последней строки блока `status`. Прошивка выдаёт блок через `say()`,
     * и строка `Delay:` замыкает его. По ней вызывающий код понимает, что блок
     * принят целиком, и может очистить буфер строк.
     */
    fun isBlockEnd(line: String): Boolean = reDly.containsMatchIn(line)

    /** Терпимый парсер: собирает состояние из любых подходящих строк буфера. */
    fun parse(lines: List<String>): DeviceState? {
        var s = DeviceState()
        var found = false
        for (l in lines) {
            reVol.find(l)?.let { m ->
                s = s.copy(
                    vol0 = m.groupValues[1].toIntOrNull() ?: 10,
                    vol1 = m.groupValues[2].toIntOrNull() ?: 10,
                    bal = m.groupValues[3].toFloatOrNull() ?: 0f
                ); found = true
            }
            reFc.find(l)?.let { m ->
                s = s.copy(
                    fc = m.groupValues[1].toFloatOrNull() ?: 400f,
                    hp = m.groupValues[2].toFloatOrNull() ?: 45f,
                    subOn = m.groupValues[3] == "ON"
                ); found = true
            }
            reTrim.find(l)?.let { m ->
                s = s.copy(
                    tlf = m.groupValues[1].toFloatOrNull() ?: 0f,
                    thf = m.groupValues[2].toFloatOrNull() ?: -1f
                ); found = true
            }
            reEq.find(l)?.let { m ->
                s = s.copy(
                    eql = m.groupValues[1].toFloatOrNull() ?: 0f,
                    eqm = m.groupValues[2].toFloatOrNull() ?: 0f,
                    eqh = m.groupValues[3].toFloatOrNull() ?: 0f
                ); found = true
            }
            reInv.find(l)?.let { m ->
                s = s.copy(inv = m.groupValues[1].map { it == '1' }); found = true
            }
            reBt.find(l)?.let { m ->
                s = s.copy(
                    btAudioOn = m.groupValues[1] == "ON",
                    sppOn = if (m.groupValues.size > 2) m.groupValues[2] == "ON" else false
                ); found = true
            }
            reTest.find(l)?.let { m ->
                s = s.copy(
                    testMode = m.groupValues[1].toIntOrNull() ?: 0,
                    testVol = m.groupValues.getOrNull(2)?.toIntOrNull() ?: s.testVol
                ); found = true
            }
            reSrc.find(l)?.let { m ->
                s = s.copy(srcKhz = m.groupValues[1]); found = true
            }
            reXo.find(l)?.let { m ->
                s = s.copy(xoType = if (m.groupValues[1] == "LR4") 2 else 1); found = true
            }
            reChf.find(l)?.let { m ->
                val pairs = listOf(
                    (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0),
                    (m.groupValues[3].toIntOrNull() ?: 0) to (m.groupValues[4].toIntOrNull() ?: 0),
                    (m.groupValues[5].toIntOrNull() ?: 0) to (m.groupValues[6].toIntOrNull() ?: 0),
                    (m.groupValues[7].toIntOrNull() ?: 0) to (m.groupValues[8].toIntOrNull() ?: 0)
                )
                s = s.copy(chFilters = pairs); found = true
            }
            reDly.find(l)?.let { m ->
                val d = listOf(
                    m.groupValues[1].toIntOrNull() ?: 0,
                    m.groupValues[2].toIntOrNull() ?: 0,
                    m.groupValues[3].toIntOrNull() ?: 0,
                    m.groupValues[4].toIntOrNull() ?: 0
                )
                s = s.copy(delays = d); found = true
            }
        }
        return if (found) s else null
    }
}

