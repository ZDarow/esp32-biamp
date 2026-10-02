/*
 * Тесты очереди команд: троттлинг «ползунков», вытеснение старого значения,
 * сброс очередей при смене сессии и предел ёмкости.
 *
 * Проверяется чистая логика через [SppTransport]: Bluetooth не нужен, тест
 * работает на виртуальном времени kotlinx-coroutines-test, поэтому паузы
 * в 150 мс не превращают прогон в спящий, а источник времени [CommandSender]
 * подставляется из планировщика. Диспетчер цикла тоже подставляется:
 * по умолчанию это Dispatchers.IO, а в тесте — тестовый, иначе гонка была бы
 * с реальными потоками.
 */

package com.kilo.biampcontrol.bt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Транспорт-заглушка: только запоминает отправленные строки. */
private class FakeTransport : SppTransport {
    val sent = mutableListOf<String>()
    override fun sendLine(cmd: String) { sent += cmd }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommandSenderTest {

    /** Запущенный отправитель на виртуальных часах планировщика. */
    private fun TestScope.startSender(transport: FakeTransport): CommandSender =
        CommandSender(
            transport,
            backgroundScope,
            { testScheduler.currentTime },
            UnconfinedTestDispatcher(testScheduler),
            {}                       // журнал: android.util.Log в JVM-тестах бросает «Stub!»
        ).also { it.start() }

    @Test
    fun `немедленная команда уходит сразу`() = runTest {
        val transport = FakeTransport()
        startSender(transport).send("preset:1", force = true)

        advanceTimeBy(50)
        assertEquals(listOf("preset:1"), transport.sent)
    }

    @Test
    fun `ползунок вытесняется последним значением`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        sender.send("vol:10")
        sender.send("vol:20")
        sender.send("vol:30")

        advanceTimeBy(200)
        assertEquals(listOf("vol:30"), transport.sent)
    }

    @Test
    fun `громкость тест-сигнала троттлится а не идёт в немедленную очередь`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        // Без троттлинга tvol уходил бы в immediate и забивал бы очередь
        // на каждом кадре перетаскивания ползунка.
        sender.send("tvol:5")
        sender.send("tvol:9")

        // До истечения 150 мс в SPP не должно уйти ничего.
        advanceTimeBy(50)
        assertEquals(emptyList<String>(), transport.sent)

        advanceTimeBy(200)
        assertEquals(listOf("tvol:9"), transport.sent)
    }

    @Test
    fun `каналы полосы не вытесняют друг друга`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        sender.send("chhp:0:31")
        sender.send("chhp:2:31")
        sender.send("chhp:0:40")

        advanceTimeBy(200)
        assertEquals(setOf("chhp:0:40", "chhp:2:31"), transport.sent.toSet())
    }

    @Test
    fun `немедленная команда не задерживается ожиданием окна троттлинга`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        sender.send("vol:15")
        sender.send("play", force = true)

        // Транспорт встаёт первым, ползунок уходит следом в своём окне.
        advanceTimeBy(200)
        assertEquals(listOf("play", "vol:15"), transport.sent)
    }

    @Test
    fun `вытеснение не теряет последнее значение при пустом окне`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        sender.send("fc:350")
        advanceTimeBy(200)
        sender.send("fc:500")
        advanceTimeBy(200)

        assertEquals(listOf("fc:350", "fc:500"), transport.sent)
    }

    // ── Смена сессии ──────────────────────────────────────────────

    /**
     * Значения, оставшиеся в очереди до обрыва, относятся к ПРЕЖНЕМУ
     * усилителю. После реконнекта они ушли бы на новое устройство и выставили
     * бы ему чужую громкость.
     */
    @Test
    fun `накопленные команды не уходят на новое устройство`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        sender.send("vol:15")          // ждёт своего окна
        sender.send("status", force = true)   // мгновенная

        advanceTimeBy(200)
        assertEquals(setOf("vol:15", "status"), transport.sent.toSet())

        sender.send("vol:90")
        sender.send("play", force = true)
        val dropped = sender.clearQueues()
        assertEquals("должны быть отброшены и ползунок, и мгновенная", 2, dropped)

        advanceTimeBy(200)
        assertEquals(
            "на новое устройство ушло то, что осталось в очереди до сброса",
            setOf("vol:15", "status"),
            transport.sent.toSet()
        )
    }

    @Test
    fun `очередь ограничена и переполнение считается а не молчит`() = runTest {
        val transport = FakeTransport()
        val sender = startSender(transport)
        // Цикл отправки не запускаем: команды копятся в переполненной очереди
        // имитируют зависший сокет.
        repeat(CommandSender.IMMEDIATE_CAPACITY + 5) { sender.send("play", force = true) }

        assertEquals(5, sender.dropped.value)
        assertTrue(sender.dropped.value > 0)
    }
}
