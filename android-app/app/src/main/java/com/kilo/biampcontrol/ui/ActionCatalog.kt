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
package com.kilo.biampcontrol.ui

import androidx.annotation.StringRes
import com.kilo.biampcontrol.R

/**
 * Каталог действий вкладки «Сервис» и транспорта вкладки «Громкость».
 *
 * Зачем отдельный файл, если кнопки и так живут в композаблах. Команды
 * прошивки — это контракт, а не деталь вёрстки, и раньше они были
 * размазаны по телу композаблов: сетка тест-сигналов собиралась
 * литералом прямо в `ServiceTab`, транспорт — в `VolumeTab`. Проверить,
 * что команда существует в прошивке, по такому коду нельзя было, а
 * расхождение молча превращалось в кнопку, на которую прошивка отвечает
 * `ERROR: unknown test mode`.
 *
 * Теперь список команд — данные, а не код: его читает и рисует интерфейс,
 * и его же читает юнит-тест, который сверяет каждую команду с таблицей
 * раздела 4 `firmware/DOCUMENTATION.md`.
 *
 * Значения команд — вёдущие пробелы и регистр менять нельзя: это буквально
 * разбор строки в прошивке.
 */
object ActionCatalog {

    /**
     * Режимы тест-сигнала, документированные в разделе 4.8.
     *
     * `l` и `r` раньше не были в интерфейсе, хотя прошивка их выполняет
     * (режимы 2 и 3: левая зона 0,1 и правая зона 2,3). Проверка одной
     * зоны — обычный шаг при развёртке, и она делается в два нажатия
     * через `test:all`, поэтому режимы просто не были нужны. Теперь они
     * есть, и сетка стала 12 кнопок — 4×3, а не 10 — то есть ровно три
     * ряда без огрызка в последнем.
     */
    val TEST_MODES: List<TestModeAction> = listOf(
        TestModeAction("all", R.string.test_all),
        TestModeAction("l", R.string.test_left),
        TestModeAction("r", R.string.test_right),
        TestModeAction("woof", R.string.test_low),
        TestModeAction("tweet", R.string.test_high),
        TestModeAction("1", R.string.test_ch1),
        TestModeAction("2", R.string.test_ch2),
        TestModeAction("3", R.string.test_ch3),
        TestModeAction("4", R.string.test_ch4),
        TestModeAction("anti", R.string.test_anti),
        TestModeAction("sweep", R.string.test_sweep),
        TestModeAction("off", R.string.test_off, destructive = true)
    )

    /** Ширина сетки тест-сигнала в кнопках. */
    const val TEST_GRID_COLUMNS: Int = 4

    /**
     * Раскладывает действия по рядам сетки.
     *
     * Последний ряд короче остальных, поэтому композабл дополняет его
     * пустыми ячейками той же ширины — иначе кнопки последнего ряда
     * растянутся и станут шире кнопок первого. Сколько именно не хватает,
     * считает [gridPadding], чтобы правило ширины жило в одном месте.
     */
    fun <T> gridRows(actions: List<T>, perRow: Int): List<List<T>> {
        require(perRow > 0) { "perRow должен быть больше нуля, а не $perRow" }
        return actions.chunked(perRow)
    }

    /** Сколько пустых ячеек нужно последнему ряду до полного. */
    fun gridPadding(rowSize: Int, perRow: Int): Int {
        require(perRow > 0) { "perRow должен быть больше нуля, а не $perRow" }
        return (perRow - rowSize).coerceAtLeast(0)
    }

    /** Диагностические запросы без аргументов, раздел 4.7. */
    val DIAGNOSTICS: List<String> = listOf("stats", "help", "heap")

    /**
     * Команды транспорта, раздел 4.7.
     *
     * Иконка в модели не хранится: она нужна только для рисования, и её
     * выбор — дело [TransportButton]. Команда и подпись для TalkBack —
     * контракт, и они проверяются тестом.
     */
    enum class Transport(val command: String, @StringRes val contentDescription: Int) {
        PREV("prev", R.string.cd_prev),
        PLAY("play", R.string.cd_play),
        PAUSE("pause", R.string.cd_pause),
        NEXT("next", R.string.cd_next)
    }
}

/**
 * Кнопка тест-сигнала: команда `test:<mode>`, подпись и признак того,
 * что действие останавливает звук.
 */
data class TestModeAction(
    val mode: String,
    @StringRes val label: Int,
    val destructive: Boolean = false
) {
    /** Полная команда прошивки. */
    val command: String get() = "test:$mode"
}
