/*
 * Проверка каталога команд интерфейса: ui/ActionCatalog.kt.
 *
 * Зачем тест. Команды в каталоге — это контракт с прошивкой, а не деталь
 * вёрстки: кнопка `test:anti` отправляет ровно `test:anti`, и если в
 * прошивке режим переименуют или уберут, кнопка не падает — она молча
 * получает `ERROR: unknown test mode`, и это видно только глазами на
 * акустике. Тест читает таблицы команд из firmware/DOCUMENTATION.md
 * (разделы 4.7 и 4.8) и требует, чтобы каждая команда каталога там была.
 *
 * Файл документации берётся тем же способом, что и контракт статуса в
 * bt/ContractBlock.kt: поиск вверх от рабочего каталога и пропуск теста,
 * если файла нет (на ветке android-dev его может не быть).
 */

package com.kilo.biampcontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ActionCatalogTest {

    /**
     * Команды в таблицах документации лежат в обратных кавычках, но там же
     * лежат и значения (`TEST_VOL_MAX = 6%`), поэтому берём только те
     * кодовые прогоны, которые начинаются со строчной буквы и идут до
     * двоеточия без пробелов — то есть потенциальные команды.
     */
    private fun documentedCommands(): Set<String> {
        val file = findFirmwareDoc() ?: run {
            assumeTrue("нет firmware/DOCUMENTATION.md — он в ветке направления firmware", false)
            return emptySet()
        }
        val text = file.readText(Charsets.UTF_8)
        val section = text.substringAfter("## 4. ", "")
            .substringBefore("## 5. ")
        val spans = Regex("`([a-z][a-z0-9]*(?::[a-z0-9]+)?)`")
            .findAll(section)
            .map { it.groupValues[1] }
            .toMutableSet()
        spans += expandRanges(section)
        return spans
    }

    /**
     * Разворачивает диапазоны вида `test:1` … `test:4` в отдельные команды.
     *
     * Документация записывает диапазон одной строкой, а интерфейсу нужны
     * все четыре кнопки. Без разворачивания тест ругался бы не на
     * интерфейс, а на оформление документации. Раскрываются только
     * диапазоны с одинаковым именем команды и числовыми границами —
     * всё остальное остаётся как есть.
     */
    private fun expandRanges(section: String): Set<String> {
        val range = Regex("""`([a-z][a-z0-9]*):(\d+)`\s*…\s*`([a-z][a-z0-9]*):(\d+)`""")
        val out = mutableSetOf<String>()
        range.findAll(section).forEach { m ->
            val name = m.groupValues[1]
            if (m.groupValues[1] == m.groupValues[3]) {
                val from = m.groupValues[2].toInt()
                val to = m.groupValues[4].toInt()
                if (from <= to) (from..to).forEach { out += "$name:$it" }
            }
        }
        return out
    }

    private fun findFirmwareDoc(): File? {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val probe = File(dir, "firmware/DOCUMENTATION.md")
            if (probe.isFile) return probe
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `режимы теста совпадают с разделом 4_8 документации`() {
        val documented = documentedCommands()
        val missing = ActionCatalog.TEST_MODES
            .map { it.command }
            .filterNot { it in documented }
        assertTrue("команды теста не описаны в прошивке: $missing", missing.isEmpty())
    }

    @Test
    fun `транспорт совпадает с разделом 4_7 документации`() {
        val documented = documentedCommands()
        val missing = ActionCatalog.Transport.entries
            .map { it.command }
            .filterNot { it in documented }
        assertTrue("команды транспорта не описаны в прошивке: $missing", missing.isEmpty())
    }

    @Test
    fun `диагностика совпадает с разделом 4_7 документации`() {
        val documented = documentedCommands()
        val missing = ActionCatalog.DIAGNOSTICS.filterNot { it in documented }
        assertTrue("команды диагностики не описаны в прошивке: $missing", missing.isEmpty())
    }

    @Test
    fun `режим теста строится как test с двоеточием`() {
        assertEquals("test:all", TestModeAction("all", 0).command)
        assertEquals("test:off", TestModeAction("off", 0).command)
    }

    @Test
    fun `режимы теста не повторяются`() {
        val modes = ActionCatalog.TEST_MODES.map { it.mode }
        assertEquals("режим теста продублирован: $modes", modes.size, modes.toSet().size)
    }

    @Test
    fun `остановка теста помечена разрушительной, остальные нет`() {
        val stop = ActionCatalog.TEST_MODES.filter { it.destructive }
        assertEquals(
            "разрушительным должен быть ровно один режим — выключение",
            listOf("off"),
            stop.map { it.mode }
        )
    }

    @Test
    fun `сетка теста ложится в три полных ряда`() {
        val rows = ActionCatalog.gridRows(ActionCatalog.TEST_MODES, ActionCatalog.TEST_GRID_COLUMNS)
        assertEquals(3, rows.size)
        rows.forEach { row ->
            assertEquals(ActionCatalog.TEST_GRID_COLUMNS, row.size)
        }
    }

    @Test
    fun `пустые ячейки дополняют только неполный ряд`() {
        assertEquals(0, ActionCatalog.gridPadding(4, 4))
        assertEquals(1, ActionCatalog.gridPadding(3, 4))
        assertEquals(0, ActionCatalog.gridPadding(5, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `нулевая ширина сетки отвергается`() {
        ActionCatalog.gridRows(ActionCatalog.TEST_MODES, 0)
    }

    @Test
    fun `транспорт покрывает четыре команды AVRCP`() {
        assertEquals(
            listOf("prev", "play", "pause", "next"),
            ActionCatalog.Transport.entries.map { it.command }
        )
    }
}
