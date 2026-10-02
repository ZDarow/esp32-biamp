/*
 * Instrumented-тесты ViewModel на настоящем Android-рантайме.
 *
 * Чего здесь нет и почему: реального ESP32. Телефон в тестовой среде с
 * усилителем не сопряжён, а сопряжение — состояние внешнего устройства,
 * которым нельзя манипулировать ради зелёного прогона. Соединение
 * подменено [FakeSppClient], поэтому проверяется то, ради чего тест и
 * нужен: ViewModel на реальных потоках, с реальными SharedPreferences
 * и реальным [kotlinx.coroutines] Main-диспетчером.
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

package com.kilo.biampcontrol

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kilo.biampcontrol.bt.ConnState
import com.kilo.biampcontrol.bt.DevicePrefs
import com.kilo.biampcontrol.bt.DeviceState
import com.kilo.biampcontrol.bt.FakeSppClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ждёт выполнения условия: потоки ViewModel живут отдельно от теста. */
private fun awaitTrue(timeoutMs: Long = 5_000, what: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (what()) return
        Thread.sleep(20)
    }
    throw AssertionError("Условие не выполнилось за $timeoutMs мс")
}

/**
 * Ждёт разбора всего блока status и возвращает состояние.
 *
 * Ожидания по одной строке мало: `V0=38%` приходит первой, а `Fc=350Hz` —
 * второй, и между ними разбор ещё не закончен. Поэтому ждём момента, когда
 * в журнале появились все строки блока: последняя из них уже разобрана, и
 * состояние можно читать целиком, а не на середине.
 */
private fun awaitStatusBlock(vm: BiAmpViewModel, lines: Int): DeviceState {
    awaitTrue(timeoutMs = 10_000) { vm.log.value.size == lines }
    return vm.deviceState.value
}

@RunWith(AndroidJUnit4::class)
class BiAmpViewModelTest {

    private lateinit var app: Application
    private lateinit var client: FakeSppClient
    private lateinit var vm: BiAmpViewModel

    /** Блок status прошивки v35.1 — 13 строк контракта, раздел 2. */
    private val statusBlock = listOf(
        "V0=38% V1=42% bal=-1.50",
        "Fc=350Hz hp=45Hz sub=ON",
        "XO: Butter OFF",
        "TLF=3.00dB THF=0.00dB",
        "EQ: L=2.00 M=0.00 H=1.00",
        "Mute: 0/0",
        "SWP: 1",
        "DUP: 0",
        "BT: ON | SPP: ON",
        "Src: 48.0 kHz",
        "Test: 2 TVol=9%",
        "CHF: 31/3150 0/0 31/3150 0/0",
        "Delay: 0/0/0/0"
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Настройки устройства общие для всего приложения: без сброса
        // тест видели бы адрес, оставшийся от прошлой установки.
        DevicePrefs(app).forget()
        client = FakeSppClient()
        vm = BiAmpViewModel(app) { client }
    }

    @After
    fun tearDown() {
        DevicePrefs(app).forget()
    }

    @Test
    fun приПодключенииЗапрашиваетсяStatusИСостояниеРазбирается() = runBlocking {
        client.deviceAnswers(*statusBlock.toTypedArray())

        assertEquals(ConnState.CONNECTED, vm.connState.value)
        // Отправка идёт через очередь CommandSender, а не в том же кадре,
        // где сменилось состояние, поэтому ждём асинхронной отправки.
        awaitTrue { client.sent.contains("status") }

        val ds = awaitStatusBlock(vm, statusBlock.size)
        assertEquals(38, ds.vol0)
        assertEquals(42, ds.vol1)
        assertEquals(-1.5f, ds.bal, 1e-4f)
        assertEquals(350f, ds.fc, 0.1f)
        assertEquals(3f, ds.tlf, 1e-4f)
        assertEquals("48.0", ds.srcKhz)
        assertEquals(2, ds.testMode)
        assertEquals(9, ds.testVol)
        assertFalse("XO: Butter OFF — кроссовер выключен", ds.xoOn)
        assertTrue("SWP: 1 — перестановка Л и П включена", ds.swapped)
        assertFalse("полный блок разобран — синхронизация снята", vm.isSyncing.value)
    }

    @Test
    fun переподключениеНеСмешиваетОтветыПрошлойСессии() = runBlocking {
        client.deviceAnswers(*statusBlock.toTypedArray())
        awaitStatusBlock(vm, statusBlock.size)

        // Ответ зависшего опроса: буфер предыдущей сессии уже очищен,
        // поэтому чужие значения не должны попасть в новое состояние.
        client.clearSent()
        client.linkLost()
        assertFalse(vm.isSyncing.value)

        client.deviceAnswers("V0=5% V1=6% bal=0.00")
        // Журнал не очищается между сессиями, поэтому ждём сами значения
        // нового блока, а не его длину.
        awaitTrue { vm.deviceState.value.vol0 == 5 && vm.deviceState.value.vol1 == 6 }
        val ds = vm.deviceState.value

        // Значения, которых в новом блоке не было, остались от первой сессии —
        // то есть буфер был очищен и разбор шёл поверх текущего состояния.
        assertEquals(6, vm.deviceState.value.vol1)
        assertEquals(350f, vm.deviceState.value.fc, 0.1f)
    }

    @Test
    fun блокStatusНеОставляетСтаруюСинхронизацию() = runBlocking {
        client.deviceAnswers(*statusBlock.toTypedArray())
        awaitStatusBlock(vm, statusBlock.size)

        // Сторож снимает флаг, если устройство не ответило на status:
        // иначе опрос встал бы навсегда после первого потерянного ответа.
        client.clearSent()
        awaitTrue(timeoutMs = 12_000) { client.sent.contains("status") }
        // Пока ответ не пришёл, флаг синхронизации стоит; сторож обязан
        // снять его по таймауту, иначе опрос встанет навсегда.
        awaitTrue(timeoutMs = 5_000) { !vm.isSyncing.value }
    }

    @Test
    fun muteОтправляетсяАбсолютноИНеОптимистиченДоСтатуса() = runBlocking {
        client.deviceAnswers(*statusBlock.toTypedArray())
        awaitStatusBlock(vm, statusBlock.size)
        client.clearSent()

        vm.toggleMute(0)
        // Фильтруем только mute: раз в 3 с ViewModel шлёт status, и без
        // фильтра тест принимал команду опроса за команду mute.
        // Команда абсолютная — контракт v35.1: mute:<зона>:<0|1>.
        awaitTrue { client.mutes == listOf("mute:0:1") }
        // Флаг переворачивает не нажатие, а ответ усилителя: до него
        // индикатор обязан показывать прочитанное состояние.
        assertFalse(vm.isMuted(0))

        // Повтор без нового блока статуса шлёт ту же цель, а не «переворот».
        vm.toggleMute(0)
        awaitTrue { client.mutes.size == 2 }
        assertEquals(listOf("mute:0:1", "mute:0:1"), client.mutes)
        assertFalse(vm.isMuted(0))
    }

    @Test
    fun disconnectВыключаетАвтоподключение() = runBlocking {
        client.deviceAnswers(*statusBlock.toTypedArray())
        awaitStatusBlock(vm, statusBlock.size)
        client.clearSent()

        vm.disconnect()

        assertEquals(1, client.disconnectCalls)
        assertFalse(
            "отключение кнопкой не должно оставлять автоподключение",
            vm.autoConnect.value
        )
    }

    @Test
    fun автоподключениеБезЗапомненногоУстройстваНеПроисходит() = runBlocking {
        vm.autoConnectIfSaved(app)
        Thread.sleep(300)

        assertEquals(0, client.connectCalls)
        assertEquals(ConnState.DISCONNECTED, vm.connState.value)
        assertEquals(null, vm.rememberedName)
    }

    @Test
    fun журналХранитПоследниеСтрокиОтвета() = runBlocking {
        client.deviceAnswers(*statusBlock.toTypedArray())
        awaitTrue { vm.log.value.size == statusBlock.size }

        assertEquals(statusBlock.last(), vm.log.value.last())
        assertEquals(statusBlock.size, vm.log.value.size)
    }
}