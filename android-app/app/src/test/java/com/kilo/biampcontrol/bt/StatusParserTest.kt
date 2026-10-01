/*
 * Тесты парсера ответа `status`.
 *
 * Блок status — единственный канал, через который приложение узнаёт состояние
 * усилителя, поэтому парсер проверяется на реальном формате строк прошивки,
 * а не на синтетике. Отдельно покрыта совместимость со старой прошивкой:
 * строка `XO:` до v30 не содержала хвоста ` ON`/` OFF`.
 */

package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {

    /** Блок status прошивки v35, как он приходит с усилителя. */
    private val blockV30 = listOf(
        "V0=11% V1=11% bal=0.00",
        "Fc=350Hz hp=45Hz sub=ON",
        "XO: Butter OFF",
        "TLF=0.00dB THF=0.00dB",
        "EQ: L=2.00 M=0.00 H=1.00",
        "SWP: 0",
        "BT: ON | SPP: ON",
        "Src: 44.1 kHz",
        "Test: 0 TVol=6%",
        "CHF: 31/3150 0/0 31/3150 0/0",
        "Delay: 0/0/0/0"
    )

    @Test
    fun `парсит полный блок v30`() {
        val s = StatusParser.parse(blockV30)!!

        assertEquals(11, s.vol0)
        assertEquals(11, s.vol1)
        assertEquals(0f, s.bal, 1e-4f)
        assertEquals(350f, s.fc, 0.1f)
        assertEquals(45f, s.hp, 0.1f)
        assertTrue(s.subOn)
        assertEquals(2f, s.eql, 1e-4f)
        assertEquals(0f, s.eqm, 1e-4f)
        assertEquals(1f, s.eqh, 1e-4f)
        assertFalse(s.lrSwap)
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
        val s = StatusParser.parse(blockV30)!!
        assertFalse(s.xoOn)
        assertEquals(1, s.xoType)
    }

    @Test
    fun `включённый кроссовер читается как ON`() {
        val s = StatusParser.parse(blockV30.map { if (it.startsWith("XO:")) "XO: Butter ON" else it })!!
        assertTrue(s.xoOn)
    }

    @Test
    fun `тип LR4 читается вместе с состоянием`() {
        val s = StatusParser.parse(blockV30.map { if (it.startsWith("XO:")) "XO: LR4 OFF" else it })!!
        assertEquals(2, s.xoType)
        assertFalse(s.xoOn)
    }

    /**
     * Прошивка до v30 отдавала строку без хвоста. Там кроссовер всегда был
     * включён, поэтому отсутствие группы — это ON, а не потеря связи.
     */
    @Test
    fun `старая прошивка без хвоста считается включённой`() {
        val old = blockV30.map { if (it.startsWith("XO:")) "XO: Butter" else it }
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
            blockV30.map { if (it.startsWith("Fc=")) "Fc=350Hz hp=45Hz sub=OFF" else it }
        )!!
        assertFalse(s.subOn)
    }

    @Test
    fun `отрицательный баланс и дробный Fc не теряются`() {
        val s = StatusParser.parse(
            blockV30.map {
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
            blockV30.map { if (it.startsWith("Test:")) "Test: 11 TVol=30%" else it }
        )!!
        assertEquals(11, s.testMode)
        assertEquals(30, s.testVol)
    }

    @Test
    fun `перестановка Л и П читается`() {
        val s = StatusParser.parse(
            blockV30.map { if (it.startsWith("SWP:")) "SWP: 1" else it }
        )!!
        assertTrue(s.lrSwap)
    }

    @Test
    fun `старая строка INV больше не влияет на состояние`() {
        // Прошивка v34 присылала "INV: 1010". Приложение v35 такой строки
        // не знает и обязано оставить перестановку выключенной, а не
        // истолковать её как swap.
        val s = StatusParser.parse(
            blockV30.map { if (it.startsWith("SWP:")) "INV: 1010" else it }
        )!!
        assertFalse(s.lrSwap)
    }

    /**
     * Блок status приходит одиннадцатью строками, поэтому на первых десяти
     * ещё нет, например, `Delay`. Разбор поверх значений по умолчанию обнулял
     * бы эти поля — ползунки прыгали бы к дефолту и обратно.
     */
    @Test
    fun `непришедшие поля не сбрасываются на дефолт`() {
        val base = DeviceState(vol0 = 38, vol1 = 38, fc = 350f, delays = listOf(7, 8, 9, 10))
        val partial = blockV30.dropLast(1)   // без строки Delay

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
}
