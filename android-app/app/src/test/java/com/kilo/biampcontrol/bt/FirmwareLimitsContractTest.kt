/*
 * Контрактный тест: константы Limits из bt/Protocol.kt против
 * вызовов safeCmdVal(cmd, v, min, max) в прошивке ESP32_BiAmp.ino.
 *
 * Зачем он нужен. Лимиты UI и пределы прошивки живут в разных языках
 * (Kotlin vs C++), и расхождение не даёт ни ошибки компиляции, ни падения
 * теста — ползунок просто покажет значение, которого устройство не примет.
 * Это ровно тот класс дефекта, который уже случался: TRIM_MIN=-9 вместо -6,
 * DELAY_MAX=1024 вместо 220.
 *
 * Тест парсит безопасные вызовы safeCmdVal из .ino и сверяет их min/max
 * с Limits. Если кто-то изменит порог в прошивке, но не поправит Limits —
 * тест упадёт. Если изменит Limits, но не прошивку — тоже упадёт.
 * Оба изменения обязаны идти в одном коммите.
 */
package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File


class FirmwareLimitsContractTest {

    /**
     * Ожидаемые диапазоны прошивки, извлечённые из safeCmdVal в .ino.
     * Ключ — префикс команды (без двоеточия). Это «источник истины» из C++,
     * которую Limits должен повторять один в один.
     */
    private val firmwareRanges: Map<String, ClosedFloatingPointRange<Double>> by lazy {
        parseFirmwareRangesOrSkip()
    }

    // ── Тесты ───────────────────────────────────────────────────────────

    @Test
    fun `громкость совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("vol")
        assertEquals(0.0, r.start, 1e-9)
        assertEquals(100.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.VOL_MIN, Limits.VOL_MAX, r)
    }

    @Test
    fun `баланс совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("bal")
        assertEquals(-10.0, r.start, 1e-9)
        assertEquals(10.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.BAL_MIN, Limits.BAL_MAX, r)
    }

    @Test
    fun `частотный фильтр совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("fc")
        assertEquals(200.0, r.start, 1e-9)
        assertEquals(1000.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.FC_MIN, Limits.FC_MAX, r)
    }

    @Test
    fun `сабсоник совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("hp")
        assertEquals(20.0, r.start, 1e-9)
        assertEquals(80.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.HP_MIN, Limits.HP_MAX, r)
    }

    @Test
    fun `тримы совпадают с safeCmdVal прошивки`() {
        listOf("tlf", "thf").forEach { cmd ->
            val r = requireFirmwareRange(cmd)
            assertEquals("$cmd: нижняя граница", -6.0, r.start, 1e-9)
            assertEquals("$cmd: верхняя граница", 3.0, r.endInclusive, 1e-9)
        }
        assertRangeEquals(Limits.TRIM_MIN, Limits.TRIM_MAX, requireFirmwareRange("tlf"))
    }

    @Test
    fun `все три полосы EQ имеют одинаковый диапазон safeCmdVal`() {
        listOf("eql", "eqm", "eqh").forEach { cmd ->
            val r = requireFirmwareRange(cmd)
            assertEquals("$cmd: нижняя граница", -12.0, r.start, 1e-9)
            assertEquals("$cmd: верхняя граница", 12.0, r.endInclusive, 1e-9)
        }
        assertRangeEquals(Limits.EQ_MIN, Limits.EQ_MAX, requireFirmwareRange("eql"))
    }

    @Test
    fun `тип кроссовера совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("xotype")
        assertEquals(1.0, r.start, 1e-9)
        assertEquals(2.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.XO_TYPE_MIN, Limits.XO_TYPE_MAX, r)
    }

    @Test
    fun `задержка совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("delay")
        assertEquals(0.0, r.start, 1e-9)
        assertEquals(220.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.DELAY_MIN, Limits.DELAY_MAX, r)
    }

    @Test
    fun `громкость тест-сигнала совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("tvol")
        // В прошивке tvol хранится в сотах процента (0..600), в Limits — проценты (0..6).
        assertEquals(0.0, r.start, 1e-9)
        assertEquals(600.0, r.endInclusive, 1e-9)
        assertEquals(0, Limits.TEST_VOL_MIN)
        assertEquals(6, Limits.TEST_VOL_MAX)
        assertTrue("tvol в прошивке должен быть 0..600 (сотые процента), Limits — 0..6",
            r.endInclusive == 600.0 && Limits.TEST_VOL_MAX == 6)
    }

    @Test
    fun `частота тестового тона совпадает с safeCmdVal прошивки`() {
        val r = requireFirmwareRange("tf")
        assertEquals(20.0, r.start, 1e-9)
        assertEquals(20000.0, r.endInclusive, 1e-9)
        assertRangeEquals(Limits.CH_FILTER_MIN, Limits.CH_FILTER_MAX, r)
    }

    // ── Полнота покрытия ────────────────────────────────────────────────

    /**
     * Каждая числовая константа Limits, которая имеет аналог в прошивке,
     * обязана быть сверена с safeCmdVal. Новая константа без проверки —
     * расхождение источников истины.
     */
    @Test
    fun `каждая диапазонная константа Limits сверена с прошивкой`() {
        val numeric = Limits::class.java.declaredFields
            .filterNot { it.name.startsWith("$") }
            .filter { it.type == Float::class.javaPrimitiveType || it.type == Int::class.javaPrimitiveType }
            .filter { it.name !in SET_OF_NONRANGE_CONSTANTS }
            .map { it.name }
            .sorted()

        assertTrue("не нашлось числовых констант в Limits", numeric.isNotEmpty())

        val firmwareCommands = firmwareRanges.keys.toSet()
        val missing = numeric.filter { cmd ->
            limitsConstantToFirmwareCommand[cmd] !in firmwareCommands
        }
        assertTrue(
            "константы Limits без сверки с safeCmdVal прошивки: $missing",
            missing.isEmpty(),
        )
    }

    // ── Вспомогательное ─────────────────────────────────────────────────

    private fun requireFirmwareRange(command: String): ClosedFloatingPointRange<Double> =
        firmwareRanges[command]
            ?: throw AssertionError(
                "в прошивке нет вызова safeCmdVal для команды `$command`; " +
                    "константы Limits без аналога в прошивке — расхождение источников истины",
            )

    private fun assertRangeEquals(low: Number, high: Number, firmware: ClosedFloatingPointRange<Double>) {
        assertEquals("нижняя граница safeCmdVal", firmware.start, low.toDouble(), 1e-9)
        assertEquals("верхняя граница safeCmdVal", firmware.endInclusive, high.toDouble(), 1e-9)
    }

    /**
     * Парсит вызовы safeCmdVal из ESP32_BiAmp.ino.
     *
     * Ищет строки вида `safeCmdVal(cmd, v, min, max)` и ближайшую выше
     * проверку команды `cmdIs(cmd, "X:")` или `strncmp(cmd, "X:", N)`.
     */
    private fun parseFirmwareRangesOrSkip(): Map<String, ClosedFloatingPointRange<Double>> {
        val file = findFirmwareFile()
        assumeTrue(
            "нет файла прошивки `firmware/ESP32_BiAmp/ESP32_BiAmp.ino` — он в ветке направления firmware",
            file != null,
        )

        val text = file!!.readText(Charsets.UTF_8)
        val lines = text.lines()

        // MAX_DELAY_SAMPLES — потолок задержки в прошивке (220).
        val maxDelay = MAX_DELAY_REGEX.find(text)?.groupValues
            ?.getOrNull(1)?.toDoubleOrNull() ?: 220.0

        val ranges = mutableListOf<FirmwareRange>()
        val lineList = lines.toList()
        for (i in lineList.indices) {
            val line = lineList[i]

            // 1) Обычный safeCmdVal(cmd, v, min, max)
            val safeMatch = SAFE_CMD_VAL_REGEX.find(line)
            if (safeMatch != null) {
                val min = safeMatch.groupValues[1].toDouble()
                val max = safeMatch.groupValues[2].toDouble()
                val command = findCommandAbove(lineList, i)
                if (command.isNotEmpty()) {
                    ranges += FirmwareRange(command, min, max)
                }
                continue
            }

            // 2) delay: parseFloat(q + 1, fv, 0, MAX_DELAY_SAMPLES) — не safeCmdVal
            if (DELAY_PARSE_FLOAT_REGEX.containsMatchIn(line)) {
                val command = findCommandAbove(lineList, i)
                if (command == "delay") {
                    ranges += FirmwareRange("delay", 0.0, maxDelay)
                }
                continue
            }

            // 3) tvol: safeCmdVal(cmd, v, 0, (int)(TEST_VOL_MAX * 100.0f))
            val tvolMatch = TVOL_SAFE_CMD_REGEX.find(line)
            if (tvolMatch != null) {
                val command = findCommandAbove(lineList, i)
                if (command == "tvol") {
                    ranges += FirmwareRange("tvol", 0.0, 600.0)
                }
                continue
            }
        }

        assertTrue("в прошивке не разобрался ни один вызов safeCmdVal", ranges.isNotEmpty())
        return ranges.associateBy({ it.command }, { it.min..it.max })
    }

    /**
     * Ищет команду команды в строках над вызовом safeCmdVal.
     */
    private fun findCommandAbove(lines: List<String>, startIdx: Int): String {
        val searchFrom = startIdx.coerceAtLeast(1) - 1
        for (i in searchFrom downTo 0) {
            val l = lines[i]
            // cmdIs(cmd, "vol:")
            val cmdIs = CMD_IS_REGEX.find(l)
            if (cmdIs != null) return cmdIs.groupValues[1]
            // strncmp(cmd, "delay", 5) — задержка без двоеточия в шаблоне
            val strncmp = STRNCMP_REGEX.find(l)
            if (strncmp != null) return strncmp.groupValues[1]
        }
        return ""
    }

    private companion object {
        /**
         * Соответствие «константа Limits → команда прошивки (без двоеточия)».
         */
        val limitsConstantToFirmwareCommand = mapOf(
            "VOL_MIN" to "vol", "VOL_MAX" to "vol",
            "BAL_MIN" to "bal", "BAL_MAX" to "bal",
            "FC_MIN" to "fc", "FC_MAX" to "fc",
            "HP_MIN" to "hp", "HP_MAX" to "hp",
            "TRIM_MIN" to "tlf", "TRIM_MAX" to "tlf",
            "EQ_MIN" to "eql", "EQ_MAX" to "eql",
            "XO_TYPE_MIN" to "xotype", "XO_TYPE_MAX" to "xotype",
            "DELAY_MIN" to "delay", "DELAY_MAX" to "delay",
            "CH_FILTER_MIN" to "tf", "CH_FILTER_MAX" to "tf",
            "TEST_VOL_MIN" to "tvol", "TEST_VOL_MAX" to "tvol",
        )

        /** Константы, которые не имеют диапазона safeCmdVal в прошивке. */
        val SET_OF_NONRANGE_CONSTANTS = setOf("CMD_MAX_CHARS", "FC_DEFAULT", "HP_DEFAULT", "TEST_MODE_MIN", "TEST_MODE_MAX")
    }
}

/** Диапазон min..max, разобранный из прошивки. */
private data class FirmwareRange(
    val command: String,
    val min: Double,
    val max: Double,
)

/** safeCmdVal(cmd, v, min, max) — извлекает min и max. */
private val SAFE_CMD_VAL_REGEX = Regex("""safeCmdVal\(cmd,\s*v,\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\)""")

/** cmdIs(cmd, "X:") — извлекает команду X. */
private val CMD_IS_REGEX = Regex("""cmdIs\s*\(\s*cmd\s*,\s*"([A-Za-z0-9_]+):\s*"""")

/** strncmp(cmd, "X", N) — извлекает команду X (для delay без двоеточия). */
private val STRNCMP_REGEX = Regex("""strncmp\s*\(\s*cmd\s*,\s*"([A-Za-z0-9_]+)"\s*,\s*\d+\s*\)""")

/** #define MAX_DELAY_SAMPLES N — извлекает N. */
private val MAX_DELAY_REGEX = Regex("""#define\s+MAX_DELAY_SAMPLES\s+(\d+)""")

/** delay: parseFloat(q + 1, fv, 0, MAX_DELAY_SAMPLES) — маркер команды задержки. */
private val DELAY_PARSE_FLOAT_REGEX = Regex("""parseFloat\s*\(\s*q\s*\+\s*1\s*,\s*fv\s*,\s*0\s*,\s*MAX_DELAY_SAMPLES\s*\)""")

/** tvol: safeCmdVal(cmd, v, 0, (int)(TEST_VOL_MAX * 100.0f)) — маркер команды громкости теста. */
private val TVOL_SAFE_CMD_REGEX = Regex("""safeCmdVal\s*\(\s*cmd\s*,\s*v\s*,\s*0\s*,\s*\(int\)\(TEST_VOL_MAX\s*\*\s*100\.0f\)\s*\)""")

/** Ищет файл прошивки, поднимаясь вверх по дереву от каталога модуля. */
private fun findFirmwareFile(): File? {
    var dir: File = File(".").absoluteFile
    repeat(6) {
        val candidate = File(dir, "firmware/ESP32_BiAmp/ESP32_BiAmp.ino")
        if (candidate.exists()) return candidate
        val parent = dir.parentFile ?: return null
        dir = parent
    }
    return null
}
