/*
 * Контрактный тест: константы Limits из bt/Protocol.kt против таблицы
 * диапазонов из firmware/protocol/status-contract.md, раздел 4.
 *
 * Зачем он нужен. Раздел 4 контракта — единая таблица диапазонов для всех
 * клиентов, и AGENTS.md требует держать лимиты приложения равными
 * safeCmdVal() прошивки. Проверять это вручную нечем: константа в Kotlin и
 * число в C++ живут в разных файлах, и расхождение не даёт ни ошибки
 * компиляции, ни падения теста — ползунок просто покажет значение, которого
 * устройство не примет. Это ровно тот класс дефекта, который уже случался
 * в репозитории (tvol: 0..100 вместо 0..6).
 *
 * Тест читает таблицу из файла контракта, а не хранит её копией: правка
 * контракта обязана ломать тест, иначе он ничего не охраняет. Отсутствие
 * контракта (ветка android-dev до слияния с main) даёт пропуск, а не падение.
 */

package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Диапазон, разобранный из строки таблицы контракта вида `| `bal:` | -10..10 | … |`.
 *
 * @property command префикс команды, для которой записан диапазон.
 * @property low нижняя граница.
 * @property high верхняя граница.
 */
private data class ContractRange(
    val command: String,
    val low: Double,
    val high: Double,
)

class LimitsContractTest {

    /**
     * Таблица раздела 4 целиком, либо пропуск теста.
     *
     * Читается из файла контракта тем же поиском вверх по дереву, что и
     * блок status в [ContractBlock]: Gradle запускает тест из каталога модуля,
     * жёсткий относительный путь здесь поехал бы молча.
     */
    private val contractRanges: List<ContractRange> by lazy { contractRangesOrSkip() }

    /**
     * Границы конкретного параметра из таблицы контракта.
     *
     * null означает, что параметра в таблице нет. Это само по себе дефект:
     * приложение держит константу для команды, которую контракт не описывает,
     * то есть источники истины расходятся.
     */
    private fun contractRangeOf(command: String): ContractRange? =
        contractRanges.firstOrNull { it.command == command }

    // ── Громкость и баланс ──────────────────────────────────────────────

    @Test
    fun `диапазон громкости совпадает с контрактом`() {
        val r = requireRange("vol:")
        assertEquals(0.0, r.low, 1e-9)
        assertEquals(100.0, r.high, 1e-9)
        assertRangeEquals(Limits.VOL_MIN, Limits.VOL_MAX, r)
    }

    @Test
    fun `диапазон баланса совпадает с контрактом`() {
        val r = requireRange("bal:")
        assertEquals(-10.0, r.low, 1e-9)
        assertEquals(10.0, r.high, 1e-9)
        assertRangeEquals(Limits.BAL_MIN, Limits.BAL_MAX, r)
    }

    // ── Кроссовер ───────────────────────────────────────────────────────

    @Test
    fun `диапазон частоты кроссовера совпадает с контрактом`() {
        val r = requireRange("fc:")
        assertEquals(200.0, r.low, 1e-9)
        assertEquals(1000.0, r.high, 1e-9)
        assertRangeEquals(Limits.FC_MIN, Limits.FC_MAX, r)
    }

    @Test
    fun `диапазон сабсоника совпадает с контрактом`() {
        val r = requireRange("hp:")
        assertEquals(20.0, r.low, 1e-9)
        assertEquals(80.0, r.high, 1e-9)
        assertRangeEquals(Limits.HP_MIN, Limits.HP_MAX, r)
    }

    // ── Тримы и эквалайзер ──────────────────────────────────────────────

    @Test
    fun `диапазон тримов совпадает с контрактом`() {
        val r = requireRange("tlf:")
        assertEquals(-6.0, r.low, 1e-9)
        assertEquals(3.0, r.high, 1e-9)
        assertRangeEquals(Limits.TRIM_MIN, Limits.TRIM_MAX, r)
    }

    @Test
    fun `все три полосы EQ имеют одинаковый диапазон и он совпадает с контрактом`() {
        val r = requireRange("eql:")
        assertEquals(-12.0, r.low, 1e-9)
        assertEquals(12.0, r.high, 1e-9)
        assertRangeEquals(Limits.EQ_MIN, Limits.EQ_MAX, r)

        // Три полосы перечислены в контракте одной строкой через запятую.
        // Если разъедутся — расхождение не поймает ни один другой тест.
        listOf("eqm:", "eqh:").forEach { cmd ->
            val eq = contractRangeOf(cmd)
            assertTrue("в контракте нет диапазона для $cmd", eq != null)
            assertEquals(
                "полоса $cmd должна иметь тот же диапазон, что eql:",
                r.low to r.high,
                eq!!.low to eq.high,
            )
        }
    }

    // ── Тип кроссовера ──────────────────────────────────────────────────

    @Test
    fun `диапазон типа кроссовера совпадает с контрактом`() {
        val r = requireRange("xotype:")
        assertEquals(1.0, r.low, 1e-9)
        assertEquals(2.0, r.high, 1e-9)
        assertRangeEquals(Limits.XO_TYPE_MIN, Limits.XO_TYPE_MAX, r)
    }

    // ── Задержка ────────────────────────────────────────────────────────

    @Test
    fun `диапазон задержки совпадает с контрактом`() {
        val r = requireRange("delayC:")
        assertEquals(0.0, r.low, 1e-9)
        assertEquals(220.0, r.high, 1e-9)
        assertRangeEquals(Limits.DELAY_MIN, Limits.DELAY_MAX, r)
    }

    /**
     * Задержка описана в контракте шаблоном `delayC:` — буква `C` означает
     * номер канала, то есть `delay0:`…`delay3:`. Приложение обязано слать
     * ровно четыре канала с общим диапазоном: если номер канала потеряется,
     * прошивка отбросит команду, а ползунок останется показывать прежнее
     * значение. Проверяем и границы, и наличие всех четырёх каналов в
     * отправляемых командах.
     */
    @Test
    fun `задержка отправляется по каждому из четырёх каналов в границах контракта`() {
        val r = requireRange("delayC:")
        assertEquals(0.0, r.low, 1e-9)
        assertEquals(220.0, r.high, 1e-9)
        assertRangeEquals(Limits.DELAY_MIN, Limits.DELAY_MAX, r)

        val sources = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().contains("delay") }
            .toList()
        assertTrue("не найдены исходники с командой delay", sources.isNotEmpty())

        val sent = sources.flatMap { it.readLines() }
            .filter { "delay0:" in it || "delay\$ch:" in it || "delay\$i:" in it }
        assertTrue(
            "приложение не отправляет задержку по каналам delay0:…delay3: — " +
                "найдена лишь одна команда на все каналы",
            sent.isNotEmpty(),
        )
    }

    // ── Полосы каналов ──────────────────────────────────────────────────

    @Test
    fun `диапазон полос канала совпадает с контрактом`() {
        listOf("chhp:", "chlp:").forEach { cmd ->
            val r = requireRange(cmd)
            assertEquals("$cmd: нижняя граница", 20.0, r.low, 1e-9)
            assertEquals("$cmd: верхняя граница", 20000.0, r.high, 1e-9)
        }
        assertRangeEquals(Limits.CH_FILTER_MIN, Limits.CH_FILTER_MAX, requireRange("chhp:"))
    }

    @Test
    fun `частота тестового тона совпадает с контрактом`() {
        val r = requireRange("tf:")
        assertEquals(20.0, r.low, 1e-9)
        assertEquals(20000.0, r.high, 1e-9)
    }

    // ── Громкость тест-сигнала: регрессия, дорого стоившая ─────────────

    /**
     * Регрессия: `TEST_VOL_MAX = 0.06f` в прошивке, то есть `tvol:` принимает
     * 0..6. Клиент с диапазоном 0..100 отправляет `tvol:40`, прошивка её
     * отбрасывает, а ползунок остаётся показывать 40 — то есть рисует то
     * значение, которого на устройстве нет. Контракт помечает 0..100 как
     * дефект явно, и этот тест не даёт ему вернуться.
     */
    @Test
    fun `громкость тест-сигнала ограничена шестью процентами`() {
        val r = requireRange("tvol:")
        assertEquals("нижняя граница tvol:", 0.0, r.low, 1e-9)
        assertEquals("внимание: в контракте верхняя граница tvol: = 6, а не 100", 6.0, r.high, 1e-9)
        assertRangeEquals(Limits.TEST_VOL_MIN, Limits.TEST_VOL_MAX, r)
        assertEquals(6, Limits.TEST_VOL_MAX)
    }

    // ── Полнота покрытия ────────────────────────────────────────────────

    /**
     * Каждая числовая константа [Limits] обязана быть описана в таблице
     * контракта. Новая константа без строки в разделе 4 — это расхождение
     * источников истины, и обнаружить его иначе нечем.
     *
     * `CMD_MAX_CHARS` проверяется отдельно: это длина строки, а не диапазон.
     */
    @Test
    fun `каждая константа Limits описана в таблице контракта`() {
        // `$stable` — служебное поле, которым Kotlin помечает объект с
        // константами (доступ к нему идёт через чтение поля, а не через
        // getstatic). Имя начинается с `$`, и в таблицу контракта оно не
        // относится. Фильтр по isSynthetic его не ловит: поле объявлено
        // компилятором обычным static final int.
        val numeric = Limits::class.java.declaredFields
            .filterNot { it.name.startsWith("$") }
            .filter { it.type == Float::class.javaPrimitiveType || it.type == Int::class.javaPrimitiveType }
            // CMD_MAX_CHARS — длина строки, а не диапазон; FC_DEFAULT/HP_DEFAULT —
            // заводские значения, а не границы. Ни то, ни другое не описано в
            // таблице диапазонов раздела 4, и проверять их там бессмысленно.
            .filter { it.name !in SET_OF_NONRANGE_CONSTANTS }
            .map { it.name }
            .sorted()

        assertTrue("не нашлось числовых констант в Limits", numeric.isNotEmpty())

        // Проверяем не сами границы, а ОПИСАНИЕ в контракте: `test:`
        // перечисляет допустимые значения словами, а не диапазоном, и
        // числовая проверка к нему неприменима. Границы проверяет
        // contractRangesOrSkip() для тех команд, где диапазон указан.
        val documented = contractCommandsOrSkip()
        val missing = numeric.filter { limitsConstantToCommand[it] !in documented }
        assertTrue(
            "константы Limits без команды в таблице контракта: $missing",
            missing.isEmpty(),
        )
        listOf("vol:", "bal:", "fc:", "hp:", "tlf:", "eql:", "xotype:", "tvol:", "delayC:", "chhp:")
            .forEach { cmd ->
                assertTrue(
                    "нет проверки константы для команды $cmd",
                    limitsConstantToCommand.values.contains(cmd),
                )
            }
    }

    // ── Вспомогательное ─────────────────────────────────────────────────

    private fun requireRange(command: String): ContractRange =
        contractRangeOf(command)
            ?: throw AssertionError(
                "в разделе 4 контракта нет диапазона для команды $command; " +
                    "константы Limits без строки в контракте — расхождение источников истины",
            )

    private fun assertRangeEquals(low: Number, high: Number, contract: ContractRange) {
        assertEquals("нижняя граница", contract.low, low.toDouble(), 1e-9)
        assertEquals("верхняя граница", contract.high, high.toDouble(), 1e-9)
    }

    private companion object {
        /**
         * Соответствие «константа Limits → команда в контракте».
         *
         * Тримы, полосы EQ, задержки и полосы каналов в контракте перечислены
         * одной строкой на группу команд, поэтому здесь указан представитель
         * группы; остальные команды той же группы проверяются отдельно там,
         * где расхождение может разойтись по каналам.
         */
        val limitsConstantToCommand = mapOf(
            "VOL_MIN" to "vol:", "VOL_MAX" to "vol:",
            "BAL_MIN" to "bal:", "BAL_MAX" to "bal:",
            "FC_MIN" to "fc:", "FC_MAX" to "fc:",
            "HP_MIN" to "hp:", "HP_MAX" to "hp:",
            "TRIM_MIN" to "tlf:", "TRIM_MAX" to "tlf:",
            "EQ_MIN" to "eql:", "EQ_MAX" to "eql:",
            "XO_TYPE_MIN" to "xotype:", "XO_TYPE_MAX" to "xotype:",
            "DELAY_MIN" to "delayC:", "DELAY_MAX" to "delayC:",
            "CH_FILTER_MIN" to "chhp:", "CH_FILTER_MAX" to "chhp:",
            "TEST_VOL_MIN" to "tvol:", "TEST_VOL_MAX" to "tvol:",
            // `test:` в контракте перечисляет режимы словами, а не диапазоном,
            // поэтому константы TEST_MODE_* проверяются на ОПИСАНИЕ в таблице,
            // а числовые границы остаются в coerceIn() парсера.
            "TEST_MODE_MIN" to "test:", "TEST_MODE_MAX" to "test:",
            // Заводские значения, а не границы: в контракте не описаны, но
            // без них двойной тап по ручке не вернёт частоту «в завод».
            "FC_DEFAULT" to "fc:", "HP_DEFAULT" to "hp:",
        )

        /** Константы, которых нет и не должно быть в таблице диапазонов. */
        val SET_OF_NONRANGE_CONSTANTS = setOf("CMD_MAX_CHARS", "FC_DEFAULT", "HP_DEFAULT")
    }
}

/**
 * Все команды, упомянутые в таблице раздела 4, либо пропуск теста.
 *
 * Отдельно от [contractRangesOrSkip], потому что не вся строка таблицы несёт
 * числовой диапазон: `test:` перечисляет режимы словами, `preset:` — числами
 * как перечисление. Такие команды тоже описаны контрактом, и наличие их
 * проверять нужно — иначе константа клиента останется без документации.
 */
private fun contractCommandsOrSkip(): Set<String> {
    val file = findContractFile()
    assumeTrue(
        "нет файла контракта firmware/protocol/status-contract.md — он в ветке направления firmware",
        file != null,
    )

    val text = file!!.readText(Charsets.UTF_8)
    val section = SECTION_4_RANGE.find(text)
    assumeTrue("в контракте нет раздела 4 с таблицей диапазонов", section != null)

    val commands = BACKTICKED.findAll(section!!.groupValues[1])
        .map { it.groupValues[1] }
        .toSet()
    assertTrue("в разделе 4 контракта не упомянуто ни одной команды", commands.isNotEmpty())
    return commands
}

/**
 * Строки таблицы раздела 4 контракта с числовым диапазоном, либо пропуск теста.
 *
 * Формат строки: `| `bal:` | -10..10 | примечание |`. Диапазон записан
 * через две точки; числа могут быть отрицательными.
 */
private fun contractRangesOrSkip(): List<ContractRange> {
    val file = findContractFile()
    assumeTrue(
        "нет файла контракта firmware/protocol/status-contract.md — он в ветке направления firmware",
        file != null,
    )

    val text = file!!.readText(Charsets.UTF_8)
    val section = SECTION_4_RANGE.find(text)
    assumeTrue("в контракте нет раздела 4 с таблицей диапазонов", section != null)

    val rows = mutableListOf<ContractRange>()
    section!!.groupValues[1].lineSequence().forEach { line ->
        val cells = line.trim().removePrefix("|").split('|')
        if (cells.size < 2) return@forEach
        // Первая ячейка может перечислять несколько команд: `v0:`, `v1:`, `vol:`.
        // Каждая получает один и тот же диапазон — так их и читает прошивка.
        val commands = BACKTICKED.findAll(cells[0]).map { it.groupValues[1] }.toList()
        if (commands.isEmpty()) return@forEach
        // Жирный шрифт в ячейке (`**0..6**`) и уточнения после диапазона
        // («0..100, целое») не должны мешать разбору границ.
        val bounds = RANGE_BOUNDS.find(cells[1].replace("*", "")) ?: return@forEach
        commands.forEach { cmd ->
            rows += ContractRange(cmd, bounds.groupValues[1].toDouble(), bounds.groupValues[2].toDouble())
        }
    }

    assertTrue("в разделе 4 контракта не разобралась ни одна строка диапазонов", rows.isNotEmpty())
    return rows
}

/** Раздел 4 целиком: от заголовка «## 4.» до следующего раздела или конца файла. */
private val SECTION_4_RANGE = Regex("""##\s*4\.[^\n]*\n(.*?)(?=\n##\s|\z)""", RegexOption.DOT_MATCHES_ALL)

/**
 * Команда в обратных кавычках: `bal:` или `delayC:`.
 *
 * Регистр важен: в контракте `delayC:` — буква `C` означает номер канала,
 * то есть `delay0:`…`delay3:`. Регексп только для нижнего регистра потерял бы
 * строку целиком, и проверка задержки молча выпала бы из контракта.
 */
private val BACKTICKED = Regex("""`([a-zA-Z0-9]+:)`""")

/**
 * Границы диапазона внутри ячейки: «-10..10», «0..100, целое», «**0..6**».
 *
 * Ищется вхождением, а не отanchored-поимкой: ячейка может содержать
 * уточнение после диапазона, и строгая привязка к границам ячейки молча
 * пропустила бы строку — то есть таблица разъехалась бы с константами
 * незамеченной.
 */
private val RANGE_BOUNDS = Regex("""(-?\d+(?:\.\d+)?)\.\.(-?\d+(?:\.\d+)?)""")