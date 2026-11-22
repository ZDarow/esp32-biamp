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
package com.kilo.biampcontrol.dsp

/**
 * Один фильтр из профиля AutoEQ (ParametricEQ.txt).
 *
 * [type] — "PK" (peaking), "LSC" (low shelf), "HSC" (high shelf).
 * [fc] — центральная частота, Гц.
 * [gain] — усиление/ослабление, дБ.
 * [q] — коэффициент качества.
 */
data class AutoEqFilter(
    val type: String,
    val fc: Float,
    val gain: Float,
    val q: Float
)

/**
 * Профиль AutoEQ: предусиление + список фильтров.
 *
 * [preamp] — предусиление, дБ (отрицательное = ослабление, защита от клипа).
 * [filters] — фильтры в порядке следования в файле.
 */
data class AutoEqProfile(
    val preamp: Float,
    val filters: List<AutoEqFilter>
)

/**
 * Парсер формата AutoEQ ParametricEQ.txt.
 *
 * Пример файла:
 *   Preapp: -6.8 dB
 *   Filter 1: ON PK Fc 105 Hz Gain 4.5 dB Q 0.70
 *   Filter 2: ON PK Fc 850 Hz Gain -2.3 dB Q 1.20
 *   Filter 3: ON LSC Fc 200 Hz Gain -1.5 dB Q 0.70
 *   Filter 4: ON HSC Fc 8000 Hz Gain 2.0 dB Q 0.70
 *
 * Возвращает null, если файл не распознан.
 */
object AutoEqImporter {

    private val PREAPP_REGEX = Regex("""Preapp:\s*(-?\d+(?:\.\d+)?)\s*dB""")
    private val FILTER_REGEX = Regex(
        """Filter\s+\d+:\s*ON\s+(PK|LSC|HSC)\s+Fc\s+(-?\d+(?:\.\d+)?)\s+Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s+dB\s+Q\s+(-?\d+(?:\.\d+)?)"""
    )

    fun parse(text: String): AutoEqProfile? {
        val preappMatch = PREAPP_REGEX.find(text)
        val preamp = preappMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        val filters = mutableListOf<AutoEqFilter>()
        text.lineSequence().forEach { line ->
            FILTER_REGEX.find(line)?.let { m ->
                filters.add(
                    AutoEqFilter(
                        type = m.groupValues[1],
                        fc = m.groupValues[2].toFloat(),
                        gain = m.groupValues[3].toFloat(),
                        q = m.groupValues[4].toFloat()
                    )
                )
            }
        }

        return if (filters.isEmpty()) null else AutoEqProfile(preamp, filters)
    }

    /**
     * Конвертирует профиль AutoEQ в список команд прошивки ESP32 BiAmp.
     *
     * Каждый фильтр映шится на команду:
     *   peq:C:Fc:Gain:Q:type
     * где C — индекс секции 0..7, type: 0=peak, 1=lowshelf, 2=highshelf.
     *
     * Первая команда — peq:preamp:N.
     *
     * Максимум 8 секций (ограничение прошивки). Если фильтров больше —
     * возвращаются первые 8.
     */
    fun toFirmwareCommands(profile: AutoEqProfile): List<String> {
        val commands = mutableListOf<String>()
        commands.add("peq:preamp:${profile.preamp}")

        val typeMap = mapOf(
            "PK" to 0,
            "LSC" to 1,
            "HSC" to 2
        )

        profile.filters.take(8).forEachIndexed { index, filter ->
            val typeCode = typeMap[filter.type] ?: 0
            commands.add("peq:$index:${filter.fc}:${filter.gain}:${filter.q}:$typeCode")
        }

        return commands
    }
}
