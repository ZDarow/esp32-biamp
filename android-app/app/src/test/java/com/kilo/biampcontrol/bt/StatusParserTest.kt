/*
 * Тесты парсера ответа `status`.
 *
 * Блок status — единственный канал, через который приложение узнаёт состояние
 * усилителя, поэтому парсер проверяется на реальном формате строк прошивки,
 * а не на синтетике. Отдельно покрыта совместимость со старой прошивкой:
 * строка `XO:` до v30 не содержала хвоста ` ON`/` OFF`, а строк Mute/SWP/DUP
 * в ней не было вовсе.
 *
 * Дословный блок из firmware/protocol/status-contract.md проверяет отдельный
 * контрактный тест StatusContractTest.
 */

package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {

/** Блок status прошивки v35.1 — 13 строк контракта, раздел 2. */
    private val blockV35 = listOf(
        "V0=11% V1=11% bal=0.00",
        "Fc=350Hz hp=45Hz sub=ON",
        "XO: Butter OFF",
        "TLF=0.00dB THF=0.00dB",
        "EQ: L=2.00 M=0.00 H=1.00",
"Mute: 0/1",
        "SWP: 1",
        "DUP: 0",
        "BT: ON | SPP: ON",
        "Src: 44.1 kHz",
        "Test: 0 TVol=6%",
        "CHF: 31/3150 0/0 31/3150 0/0",
        "Delay: 0/0/0/0"
    )

    @Test
    fun `парсит полный блок v35`() {
        val s = StatusParser.parse(blockV35)!!

        assertEquals(11, s.vol0)
        assertEquals(11, s.vol1)
        assertEquals(0f, s.bal, 1e-4f)
        assertEquals(350f, s.fc, 0.1f)
        assertEquals(45f, s.hp, 0.1f)
        assertTrue(s.subOn)
        assertEquals(2f, s.eql, 1e-4f)
        assertEquals(0f, s.eqm, 1e-4f)
        assertEquals(1f, s.eqh, 1e-4f)
assertEquals(listOf(false, true), s.muted)
        assertTrue(s.swapped)
        assertFalse(s.dup)
        assertTrue(s.btAudioOn)
        assertTrue(s.sppOn)
        assertEquals("44.1", s.srcKhz)
        assertEquals(0, s.testMode)
        assertEquals(6, s.testVol)
        assertEquals(listOf(31 to 3150, 0 to 0, 31 to 3150, 0 to 0), s.chFilters)
        assertEquals(listOf(0, 0, 0, 0), s.delays)
    }

    @Test
    fun `выключенный кроссовер читается как OFF`() {
        val s = StatusParser.parse(blockV35)!!
        assertFalse(s.xoOn)
        assertEquals(1, s.xoType)
    }

    @Test
    fun `включённый кроссовер читается как ON`() {
        val s = StatusParser.parse(blockV35.map { if (it.startsWith("XO:")) "XO: Butter ON" else it })!!
        assertTrue(s.xoOn)
    }

    @Test
    fun `тип LR4 читается вместе с состоянием`() {
        val s = StatusParser.parse(blockV35.map { if (it.startsWith("XO:")) "XO: LR4 OFF" else it })!!
        assertEquals(2, s.xoType)
        assertFalse(s.xoOn)
    }

    /**
     * Прошивка до v30 отдавала строку без хвоста. Там кроссовер всегда был
     * включён, поэтому отсутствие группы — это ON, а не потеря связи.
     */
    @Test
    fun `старая прошивка без хвоста считается включённой`() {
        val old = blockV35.map { if (it.startsWith("XO:")) "XO: Butter" else it }
        val s = StatusParser.parse(old)!!
        assertTrue(s.xoOn)
        assertEquals(1, s.xoType)
    }

    @Test
    fun `последняя строка блока опознаётся по Delay`() {
        assertTrue(StatusParser.isBlockEnd("Delay: 0/0/0/0"))
        assertTrue(StatusParser.isBlockEnd("Delay: 120/0/12/0"))
        assertFalse(StatusParser.isBlockEnd("CHF: 31/3150 0/0 31/3150 0/0"))
        assertFalse(StatusParser.isBlockEnd("V0=11% V1=11% bal=0.00"))
    }

    @Test
    fun `мусор не разбирается в состояние`() {
        assertNull(StatusParser.parse(listOf("", "OK", ">", "garbage 123")))
    }

    @Test
    fun `пустой блок не разбирается в состояние`() {
        assertNull(StatusParser.parse(emptyList()))
    }

    @Test
    fun `выключенный сабсоник читается`() {
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("Fc=")) "Fc=350Hz hp=45Hz sub=OFF" else it }
        )!!
        assertFalse(s.subOn)
    }

    @Test
    fun `отрицательный баланс и дробный Fc не теряются`() {
        val s = StatusParser.parse(
            blockV35.map {
                when {
                    it.startsWith("V0=") -> "V0=40% V1=60% bal=-1.50"
                    it.startsWith("Fc=") -> "Fc=500Hz hp=60Hz sub=ON"
                    else -> it
                }
            }
        )!!
        assertEquals(40, s.vol0)
        assertEquals(60, s.vol1)
        assertEquals(-1.5f, s.bal, 1e-4f)
        assertEquals(500f, s.fc, 0.1f)
        assertEquals(60f, s.hp, 0.1f)
    }

    @Test
    fun `тестовая частота и громкость читаются вместе`() {
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("Test:")) "Test: 3 TVol=2%" else it }
        )!!
        assertEquals(3, s.testMode)
        assertEquals(2, s.testVol)
    }

    // ── Mute / SWP / DUP (контракт, раздел 2, строки 6..8) ──────────

    @Test
    fun `обе зоны mute читаются раздельно`() {
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("Mute:")) "Mute: 1/0" else it }
        )!!
        assertEquals(listOf(true, false), s.muted)
    }

    @Test
    fun `старая строка INV больше не влияет на состояние`() {
        // Прошивка v34 присылала "INV: 1010". Приложение v35.1 такой строки
        // не знает и обязано оставить перестановку выключенной, а не
        // истолковать её как swap.
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("SWP:")) "INV: 1010" else it }
        )!!
        assertFalse(s.swapped)
    }

    @Test
    fun `сброс обеих зон mute читается как две единицы`() {
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("Mute:")) "Mute: 1/1" else it }
        )!!
        assertEquals(listOf(true, true), s.muted)
    }

    @Test
    fun `перестановка каналов читается`() {
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("SWP:")) "SWP: 0" else it }
        )!!
        assertFalse(s.swapped)
    }

    @Test
    fun `дублирование каналов читается и это заметный флаг`() {
        val s = StatusParser.parse(
            blockV35.map { if (it.startsWith("DUP:")) "DUP: 1" else it }
        )!!
        assertTrue("DUP: 1 означает, что каналы 2 и 3 молчат", s.dup)
    }

    /** Инверсии фазы в v35 нет: команда inv: и строка INV: удалены. */
    @Test
    fun `строка INV больше не распознаётся и состояние не меняет`() {
        val base = DeviceState(vol0 = 38)
        val r = StatusParser.parseDetailed(listOf("INV: 1010"), base)

        assertNull("строка INV обязана остаться нераспознанной", r.state)
        assertEquals(listOf("INV: 1010"), r.unrecognized)
        assertFalse("INV не должен считаться строкой статуса", StatusParser.isStatusLine("INV: 1010"))
    }

    // ── Санитайзер диапазонов (контракт, раздел 4) ──────────────────

    @Test
    fun `значения вне диапазона зажимаются а не показываются как есть`() {
        val s = StatusParser.parse(
            listOf(
                "V0=250% V1=4% bal=99.00",
                "Fc=50Hz hp=800Hz sub=ON",
                "TLF=9.00dB THF=-40.00dB",
                "EQ: L=30.00 M=-30.00 H=12.00",
                "Test: 9 TVol=100%",
                "CHF: 5/40000 0/0 0/0 0/0",
                "Delay: 999/0/0/0"
            )
        )!!
        assertEquals(100, s.vol0)
        assertEquals(4, s.vol1)
        assertEquals(10f, s.bal, 1e-4f)
        assertEquals(200f, s.fc, 1e-4f)
        assertEquals(80f, s.hp, 1e-4f)
        assertEquals(3f, s.tlf, 1e-4f)
        assertEquals(-6f, s.thf, 1e-4f)
        assertEquals(12f, s.eql, 1e-4f)
        assertEquals(-12f, s.eqm, 1e-4f)
        assertEquals(4, s.testMode)
        assertEquals(6, s.testVol)
        assertEquals(20 to 20000, s.chFilters[0])
        assertEquals(listOf(220, 0, 0, 0), s.delays)
    }

    /** Ноль на канальном фильтре — это «выключено», а не 20 Гц. */
    @Test
    fun `нулевой канальный фильтр остаётся нулём`() {
        val s = StatusParser.parse(listOf("CHF: 0/0 0/0 0/0 0/0"))!!
        assertEquals(listOf(0 to 0, 0 to 0, 0 to 0, 0 to 0), s.chFilters)
    }

    // ── Защита от молчаливых дефолтов (контракт, раздел 6) ─────────

    /**
     * Блок status приходит тринадцатью строками, поэтому на первых
     * двенадцати ещё нет, например, `Delay`. Разбор поверх значений по
     * умолчанию обнулял бы эти поля — ползунки прыгали бы к дефолту и обратно.
     */
    @Test
    fun `непришедшие поля не сбрасываются на дефолт`() {
        val base = DeviceState(vol0 = 38, vol1 = 38, fc = 350f, delays = listOf(7, 8, 9, 10))
        val partial = blockV35.dropLast(1)   // без строки Delay

        val s = StatusParser.parse(partial, base)!!

        // Пришедшие строки обновляют состояние как обычно.
        assertEquals(11, s.vol0)
        assertEquals(11, s.vol1)
        assertEquals(0f, s.bal, 1e-4f)
        assertEquals(350f, s.fc, 0.1f)
        assertEquals(listOf(31 to 3150, 0 to 0, 31 to 3150, 0 to 0), s.chFilters)
        // А строка Delay ещё не пришла — её значение обязано уцелеть,
        // иначе ползунки задержек прыгали бы к нулю на каждом обновлении.
        assertEquals(listOf(7, 8, 9, 10), s.delays)
    }

    /**
     * Битое значение не должно подставлять дефолт: раньше `vol0 = ... ?: 10`
     * тихо возвращал громкость в 10 % при каждом потерянном пакете. Теперь
     * поле сохраняет предыдущее значение, а строки, которые разобрать не
     * удалось, попадают в счётчик [ParseResult.unrecognizedCount].
     */
    @Test
    fun `битое значение не заменяется дефолтом`() {
        val base = DeviceState(testVol = 3, vol0 = 38)
        val s = StatusParser.parseDetailed(listOf("Test: 0 TVol=%"), base).state!!

        assertEquals("громкость теста не должна прыгать к дефолту", 3, s.testVol)
        assertEquals(38, s.vol0)
    }

    @Test
    fun `нераспознанные строки считаются а не заменяются дефолтом`() {
        val r = StatusParser.parseDetailed(
            listOf("V0=x% V1=11% bal=0.00", "INV: 0100"),
            DeviceState(vol0 = 38)
        )
        assertNull("из одних битых строк состояние не собирается", r.state)
        assertEquals(2, r.unrecognizedCount)
    }

    @Test
    fun `слияние не восстанавливает состояние из пустого буфера`() {
        assertNull(StatusParser.parse(emptyList(), DeviceState(vol0 = 38)))
    }

    @Test
    fun `слияние не принимает мусор за состояние`() {
        assertNull(StatusParser.parse(listOf("OK", ">"), DeviceState(vol0 = 38)))
    }

    /** Прошивка без `SPP:` в строке не должна гасить признак SPP. */
    @Test
    fun `старая строка BT не гасит признак SPP`() {
        val base = DeviceState(sppOn = true)
        val s = StatusParser.parse(listOf("BT: ON"), base)!!
        assertTrue(s.btAudioOn)
        assertTrue(s.sppOn)
    }

    @Test
    fun `строка BT без SPP обрывает признак SPP если прошивка его сообщает`() {
        val base = DeviceState(sppOn = true)
        val s = StatusParser.parse(listOf("BT: ON | SPP: OFF"), base)!!
        assertFalse(s.sppOn)
    }

    @Test
    fun `каждая строка блока опознаётся как строка статуса`() {
        for (l in blockV35) {
            assertTrue("строка не опознана как status: $l", StatusParser.isStatusLine(l))
        }
    }

    @Test
    fun `вывод диагностики не путается со строкой статуса`() {
        // help/stats/heap печатают произвольный текст; он обязан уходить в
        // журнал, а не вытеснять блок status (это и была причина залипания).
        for (l in listOf("OK", "Stats: heap 21456", "> help", "avail 30000")) {
            assertFalse("строка принята за status: $l", StatusParser.isStatusLine(l))
        }
    }
}
