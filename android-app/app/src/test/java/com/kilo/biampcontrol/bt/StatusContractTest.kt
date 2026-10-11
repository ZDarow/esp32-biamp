/*
 * Контрактный тест: дословный блок `status` из
 * protocol/status-contract.md, раздел 2.
 *
 * Тест обязателен по разделу 5 контракта. Он ломается в двух случаях:
 *  1) прошивка добавила строку в блок `status`, а парсер её не знает —
 *     тогда строка попадёт в unrecognized и тест упадёт;
 *  2) парсер перестал разбирать поле, которое раньше разбирал.
 *
 * Второй случай ловится сверкой полей, первый — счётчиком
 * нераспознанных строк [ParseResult.unrecognizedCount].
 */

package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StatusContractTest {

    /**
     * Блок `status` прошивки v35.1 — читается ДОСЛОВНО из
     * protocol/status-contract.md, раздел 2, функцией
     * [contractBlockOrSkip]. Порядок строк фиксирован, последняя — `Delay:`.
     *
     * Раньше блок хранился здесь цитатой. Цитата была верной, но её
     * соответствие контракту никто не проверял: правка контракта оставляла
     * тест зелёным. Теперь источник один, и Android не может разойтись с
     * прошивкой незаметно.
     */
    private val contractBlock by lazy { contractBlockOrSkip() }

    /** Блок содержит ровно 13 строк — столько же, сколько в контракте. */
    @Test
    fun `блок контракта это ровно 13 строк`() {
        assertEquals(13, contractBlock.size)
    }

    @Test
    fun `ни одна строка блока контракта не осталась нераспознанной`() {
        val r = StatusParser.parseDetailed(contractBlock, DeviceState())

        assertEquals(
            "Парсер не узнал строки блока status: ${r.unrecognized}",
            0,
            r.unrecognizedCount
        )
        assertNotNull(r.state)
    }

    /** Все 13 полей контракта, по одной проверке на строку. */
    @Test
    fun `распознаны все поля блока status`() {
        val s = StatusParser.parse(contractBlock)!!

        // 1. V0 / V1 / bal
        assertEquals(40, s.vol0)
        assertEquals(40, s.vol1)
        assertEquals(0f, s.bal, 1e-4f)
        // 2. Fc / hp / sub
        assertEquals(400f, s.fc, 0.1f)
        assertEquals(45f, s.hp, 0.1f)
        assertTrue(s.subOn)
        // 3. XO: тип и выключатель
        assertEquals(1, s.xoType)
        assertTrue(s.xoOn)
        // 4. TLF / THF
        assertEquals(0f, s.tlf, 1e-4f)
        assertEquals(-1f, s.thf, 1e-4f)
        // 5. EQ
        assertEquals(0f, s.eql, 1e-4f)
        assertEquals(0f, s.eqm, 1e-4f)
        assertEquals(0f, s.eqh, 1e-4f)
        // 6. Mute
        assertEquals(listOf(false, false), s.muted)
        // 7. SWP
        assertFalse(s.swapped)
        // 8. DUP
        assertFalse(s.dup)
        // 9. BT / SPP
        assertTrue(s.btAudioOn)
        assertFalse(s.sppOn)
        // 10. Src
        assertEquals("44.1", s.srcKhz)
        // 11. Test / TVol
        assertEquals(0, s.testMode)
        assertEquals(4, s.testVol)
        // 12. CHF
        assertEquals(listOf(0 to 0, 0 to 0, 0 to 0, 0 to 0), s.chFilters)
        // 13. Delay — конец блока
        assertEquals(listOf(0, 0, 0, 0), s.delays)
        assertTrue(StatusParser.isBlockEnd(contractBlock.last()))
    }

    /**
     * Регрессия защиты от протокольного дрейфа: строка `INV:` удалена из
     * контракта в v35, поэтому парсер обязан считать её чужой, а поле
     * `inv` в [DeviceState] — отсутствовать вовсе.
     */
    @Test
    fun `INV удалён из контракта и не разбирается`() {
        val r = StatusParser.parseDetailed(listOf("INV: 0100"), DeviceState())
        assertEquals(1, r.unrecognizedCount)
        assertTrue(StatusParser.parse(listOf("INV: 0100")) == null)

        val fields = DeviceState::class.java.declaredFields.map { it.name }
        assertFalse("в DeviceState не должно быть поля inv: $fields", fields.contains("inv"))
    }

    /**
     * Билдеров команды `inv:` в коде быть не должно: прошивка v35 её не
     * знает, то есть клиент отправляет команду в пустоту и рисует выдуманные
     * значения. Проверяется исходниками модуля — иначе любой новый
     * `sender.send("inv:0")` снова проскочит мимо компилятора.
     */
    @Test
    fun `в исходниках нет билдеров команды inv`() {
        val offenders = sourceFiles()
            .flatMap { f -> f.readLines().mapIndexed { i, l -> f.name to (i + 1 to l) } }
            .filter { (_, l) -> Regex("""["']inv[:'"]""").containsMatchIn(l.second) }
            .map { (name, l) -> "$name:${l.first}: ${l.second.trim()}" }

        assertTrue(
            "Команда inv: удалена контрактом, но найдена в коде:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** Исходники main-модуля: каталог задаётся рабочим каталогом Gradle. */
    private fun sourceFiles(): List<File> {
        val root = File("src/main/java")
        assertTrue("не найден каталог исходников $root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
