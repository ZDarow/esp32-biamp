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

/**
 * Разбор MAC-адреса, введённого руками.
 *
 * Зачем это нужно. Список сопряжённых устройств Android показывает только
 * то, что сопряжено с этим телефоном; усилитель, который раньше был
 * сопряжен с другим телефоном или сброшен кнопкой на корпусе, в списке не
 * появится, и приложение не сможет к нему подключиться вообще. Ручной
 * ввод адреса — единственный путь в этой ситуации, и он есть в диалогах
 * Bluetooth, но в приложении его не было.
 *
 * Разбор вынесен в отдельный объект без Android: `BluetoothAdapter.getRemoteDevice`
 * на мусорных входных данных бросает `IllegalArgumentException`, и если
 * отлавливать его в композабле, ошибка от пользователя зависит. Здесь
 * невалидный вход даёт `null`, а композабл просто не включает кнопку.
 *
 * Принимается и `AA:BB:CC:DD:EE:FF`, и `AA-BB-CC-DD-EE-FF`, и
 * `aabbccddeeff`, и ввод с пробелами: набирать адрес с телефонной
 * клавиатуры через двоеточия неудобно, а опечатка в разделителе не должна
 * была бы стоить попытки подключения.
 */
object MacAddress {

    private val HEX_CHARS = ('0'..'9') + ('a'..'f') + ('A'..'F')
    private const val HEX_DIGITS = 12
    private const val OCTET_CHARS = 2

    /**
     * Возвращает адрес в канонической форме `AA:BB:CC:DD:EE:FF` либо `null`.
     *
     * `null` означает «ввод не похож на адрес»: не hex, не 6 октетов,
     * лишние символы. Всё это отсекается до вызова Android API, потому
     * что адрес неверного формата — исключение глубоко внутри
     * `getRemoteDevice`, и перехватить его там нечем.
     *
     * Две формы, а не одна: с разделителями — когда каждый октет должен
     * быть ровно двумя символами, и слитная — когда 12 hex-символов подряд.
     * Форма «с разделителями, но октет длиннее двух символов» отвергается:
     * это опечатка, а не другой формат, и `getRemoteDevice` по такому
     * адресу всё равно бросил бы исключение. Наивное «выбросить все
     * разделители и проверить длину» эту опечатку приняло бы.
     */
    fun normalize(input: String): String? {
        val parts = input.trim().split(':', '-', ' ', '\t').filter { it.isNotEmpty() }
        if (parts.size == 1) {
            val flat = parts[0]
            if (flat.length != HEX_DIGITS || !flat.all { it in HEX_CHARS }) return null
            return flat.chunked(OCTET_CHARS).joinToString(":") { it.uppercase() }
        }
        if (parts.size != OCTETS) return null
        if (parts.any { it.length != OCTET_CHARS || !it.all { c -> c in HEX_CHARS } }) return null
        return parts.joinToString(":") { it.uppercase() }
    }

    /** Правильно ли набирается адрес — для включения кнопки «Подключить». */
    fun isValid(input: String): Boolean = normalize(input) != null

    private const val OCTETS = 6
}