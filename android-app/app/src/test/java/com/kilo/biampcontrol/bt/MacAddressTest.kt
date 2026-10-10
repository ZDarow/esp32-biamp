/*
 * Проверка разбора MAC-адреса, введённого руками: bt/MacAddress.kt.
 *
 * Зачем тест. Адрес приходит с клавиатуры телефона, то есть с опечатками
 * и в разных форматах. `BluetoothAdapter.getRemoteDevice()` на неверном
 * адресе бросает IllegalArgumentException глубоко внутри фреймворка, и
 * если бы разбор шёл прямо в композабле, кнопка «Подключить» падала бы
 * вместо того, чтобы просто не включиться. Проверяем, что мусор отвергается,
 * а нормальные формы принимаются.
 */

package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAddressTest {

    @Test
    fun `разделители не влияют`() {
        val expected = "68:09:47:86:5C:38"
        assertEquals(expected, MacAddress.normalize("68:09:47:86:5C:38"))
        assertEquals(expected, MacAddress.normalize("68-09-47-86-5C-38"))
        assertEquals(expected, MacAddress.normalize("680947865C38"))
        assertEquals(expected, MacAddress.normalize("68 09 47 86 5C 38"))
    }

    @Test
    fun `регистр приводится к верхнему`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddress.normalize("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddress.normalize("Aa:bB:cC:dD:eE:fF"))
    }

    @Test
    fun `края ввода не считаются мусором`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddress.normalize("  AA:BB:CC:DD:EE:FF "))
        assertEquals("AA:BB:CC:DD:EE:FF", MacAddress.normalize("AA:BB:CC:DD:EE:FF\n"))
    }

    @Test
    fun `не хватает октета`() {
        assertNull(MacAddress.normalize("AA:BB:CC:DD:EE"))
        assertNull(MacAddress.normalize("AA:BB:CC:DD"))
    }

    @Test
    fun `лишний октет`() {
        assertNull(MacAddress.normalize("AA:BB:CC:DD:EE:FF:00"))
    }

    @Test
    fun `не hexadecimal символ`() {
        assertNull(MacAddress.normalize("AA:BB:CC:DD:EE:GG"))
        assertNull(MacAddress.normalize("ZZ:09:47:86:5C:38"))
    }

    @Test
    fun `пустой ввод`() {
        assertNull(MacAddress.normalize(""))
        assertNull(MacAddress.normalize("   "))
        assertNull(MacAddress.normalize("::::"))
    }

    @Test
    fun `проверка валидности совпадает с разбором`() {
        assertTrue(MacAddress.isValid("68:09:47:86:5C:38"))
        assertFalse(MacAddress.isValid("68:09:47:86:5C"))
    }

    @Test
    fun `октет длиннее двух символов не считается адресом`() {
        // Слепое склеивание дало бы 13 символов, а не адрес: лишние символы
        // внутри октета означают опечатку, и подключаться по такому адресу
        // бессмысленно — getRemoteDevice всё равно бросит исключение.
        assertNull(MacAddress.normalize("AAA:BB:CC:DD:EE:FF"))
    }
}