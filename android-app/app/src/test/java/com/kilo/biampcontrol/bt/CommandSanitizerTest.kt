/*
 * Тесты санитайзера команды.
 *
 * Прошивка исполняет присланную строку как команду, поэтому символ перевода
 * строки внутри неё отправил бы сразу несколько команд, а символ пробела —
 * разорвал бы команду на части. Здесь проверяется, что в сокет уходит
 * только допустимый алфавит и длина меньше 63 байт (контракт, раздел 4).
 */

package com.kilo.biampcontrol.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CommandSanitizerTest {

    @Test
    fun `обычные команды проходят без изменений`() {
        for (c in listOf(
            "status", "vol:40", "v0:0", "bal:-3", "fc:200", "hp:20",
            "eql:12", "tlf:-6.0", "chhp:0:31", "delay0:220",
            "mute:0:1", "swap:1", "dup:0", "test:anti", "preset:3",
            "tvol:6", "xotype:2", "save", "reboot", "factory"
        )) {
            assertEquals(c, CommandSanitizer.sanitize(c))
        }
    }

    /**
     * Смысл проверки не в обрезке хвоста, а в том, что перевод строки никогда
     * не доходит до сокета: команда, дописанная после него, сливается в
     * одно нераспознаваемое имя, а не превращается во вторую команду.
     */
    @Test
    fun `перевод строки не доходит до SPP и команд не множит`() {
        val out = CommandSanitizer.sanitize("status\nreboot")!!
        assertEquals("statusreboot", out)
        assertFalse("в команде не должно быть перевода строки", out.contains('\n') || out.contains('\r'))

        val crlf = CommandSanitizer.sanitize("v0:40\r\nfactory")!!
        assertFalse(crlf.contains('\n') || crlf.contains('\r'))
        assertEquals("v0:40factory", crlf)
    }

    @Test
    fun `пробелы и прочее вырезаются`() {
        assertEquals("status", CommandSanitizer.sanitize("sta tus"))
        assertEquals("v0:40", CommandSanitizer.sanitize("v0: 40"))
        assertEquals("bal:-3", CommandSanitizer.sanitize("bal:= -3"))
    }

    @Test
    fun `длинная команда обрезается до предела прошивки`() {
        val long = "chhp:0:" + "9".repeat(200)
        val out = CommandSanitizer.sanitize(long)!!
        assertEquals(Limits.CMD_MAX_CHARS, out.length)
        assertEquals("9", out.takeLast(1))
    }

    @Test
    fun `пустая команда не отправляется`() {
        assertNull(CommandSanitizer.sanitize(""))
        assertNull(CommandSanitizer.sanitize("   "))
        assertNull(CommandSanitizer.sanitize("\n\r;"))
    }
}
