/*
 * Instrumented-тесты композаблов на настоящем устройстве.
 *
 * Что здесь проверяется и что юнит-тест поймать не может: реальная
 * композиция, реальные ноды семантики, реальный ввод. Именно на этом
 * ломались вещи, которые в unit-тесте выглядели рабочими: элемент,
 * который нарисован, но не находится по подписи; кнопка, которая есть,
 * но задизейблена без связи; диалог, который не открылся.
 *
 * Активность — пустая ComponentActivity из ui-test-manifest, а не
 * MainActivity: у MainActivity есть запрос разрешений Bluetooth, и
 * системный диалог поверх теста сделал бы результаты нестабильными.
 *
 * Список сопряжённых устройств эти тесты не читают: композ получает
 * готовый [BiAmpViewModel] с подменённым соединением, а Bluetooth-разрешений
 * у пустой активности нет.
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
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kilo.biampcontrol.bt.ConnState
import com.kilo.biampcontrol.bt.DevicePrefs
import com.kilo.biampcontrol.bt.FakeSppClient
import com.kilo.biampcontrol.ui.theme.BiAmpTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var client: FakeSppClient
    private lateinit var vm: BiAmpViewModel

    private val statusBlock = listOf(
        "V0=38% V1=42% bal=-1.50",
        "Fc=350Hz hp=45Hz sub=ON",
        "XO: Butter ON",
        "TLF=3.00dB THF=0.00dB",
        "EQ: L=2.00 M=0.00 H=1.00",
        "SWP: 1",
        "BT: ON | SPP: ON",
        "Src: 48.0 kHz",
        "Test: 0 TVol=9%",
        "CHF: 31/3150 0/0 31/3150 0/0",
        "Delay: 0/0/0/0"
    )

    /** Строка из ресурсов: язык устройства в тестах не задаём. */
    private fun str(id: Int): String = app.getString(id)

    /**
     * Единый значок подключения.
     *
     * Ищем по `conn_toggle_cd`, а не по тексту: у иконки внутри contentDescription
     * пустой (иначе TalkBack прочёл бы имя дважды), поэтому единственная
     * устойчивая подпись узла — та, что задана самому IconButton.
     */
    private fun connButton(): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription(str(R.string.conn_toggle_cd))

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DevicePrefs(app).forget()
        client = FakeSppClient()
        vm = BiAmpViewModel(app) { client }
        compose.setContent {
            BiAmpTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(vm)
                }
            }
        }
    }

    @After
    fun tearDown() {
        DevicePrefs(app).forget()
    }

    /**
     * Дожидается разбора блока status и пересборки композиции.
     *
     * Ждём по журналу, а не по первой строке: `V0=38%` разбирается первым, а
     * `Fc=`, `Test:` и прочее — позже, и между ними композабл видел бы
     * состояние наполовину разобранным.
     */
    private fun connected() {
        compose.waitUntil(10_000) { vm.log.value.size == statusBlock.size }
        compose.waitForIdle()
    }

    @Test
    fun безСвязиПолзункиИКнопкиЗадизейблены() {
        // Подпись вкладки «Громкость» совпадает с заголовком самой вкладки,
        // поэтому узел ищем по подписи ползунка, а не по тексту заголовка.
        compose.onNodeWithContentDescription(str(R.string.volume_both)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.volume_left)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.mute_left)).assertIsNotEnabled()
        // Значок подключения — единственная кнопка состояния, и по её
        // подписи мы находим её и без связи: надписи «Подкл.» больше нет.
        connButton().assertIsDisplayed()
        connButton().assert(hasStateDescription(str(R.string.conn_state_off)))
    }

    @Test
    fun приПодключенииПолзункиСтановитсяДоступными() {
        runBlocking { client.deviceAnswers(*statusBlock.toTypedArray()) }
        connected()

        compose.onNodeWithContentDescription(str(R.string.volume_left)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.mute_left)).assertIsEnabled()
    }

    @Test
    fun тапПоMuteОтправляетКомандуИМеняетСостояние() {
        runBlocking { client.deviceAnswers(*statusBlock.toTypedArray()) }
        connected()
        client.clearSent()

        compose.onNodeWithText(str(R.string.mute_left)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("mute:0"), client.mutes)
        assertEquals(true, vm.isMuted0.value)
    }

    @Test
    fun кнопкаПодключенияСообщаетСостояниеИОтключаетПриСвязи() {
        connButton().assert(hasStateDescription(str(R.string.conn_state_off)))

        runBlocking { client.deviceAnswers(*statusBlock.toTypedArray()) }
        compose.waitUntil(10_000) { vm.connState.value == ConnState.CONNECTED }
        compose.waitForIdle()

        // Один и тот же узел остаётся на месте и меняет только состояние —
        // ради этого кнопка и делалась единой.
        connButton().assert(hasStateDescription(str(R.string.conn_state_on)))

        connButton().performClick()
        compose.waitForIdle()
        assertEquals(ConnState.DISCONNECTED, vm.connState.value)
    }

    @Test
    fun вкладкаСервисПоказываетРазобранныйДиагностическийБлок() {
        runBlocking { client.deviceAnswers(*statusBlock.toTypedArray()) }
        connected()

        compose.onNodeWithText(str(R.string.tab_service)).performClick()
        compose.waitForIdle()

        // Строки собираются в композабле из полей deviceState: если разбор
        // блока не отработал, здесь был бы дефолт «10». Совпадений может быть
        // два — та же строка попадает в лог SPP, поэтому берём первый узел.
        compose.onAllNodesWithText("V0=38% V1=42% bal=-1.5")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Test: 0 TVol=9%")[0].assertIsDisplayed()
    }

    @Test
    fun окноДокументацииОткрывается() {
        compose.onNodeWithText(str(R.string.tab_service)).performClick()
        compose.waitForIdle()

        // Кнопка документации внизу длинного скролла: без прокрутки узел
        // найден, но не виден, и тап ушёл бы мимо.
        compose.onNodeWithText(str(R.string.docs_button)).performScrollTo().performClick()
        compose.waitForIdle()

        // Заголовок окна — верх диалога; текст внутри длинный и прокручиваемый.
        compose.onNodeWithText(str(R.string.docs_dialog_title)).assertIsDisplayed()
    }

    @Test
    fun крутилкаFcОтправляетЗначениеПоОтпусканию() {
        runBlocking { client.deviceAnswers(*statusBlock.toTypedArray()) }
        connected()
        compose.onNodeWithText(str(R.string.tab_dsp)).performClick()
        compose.waitForIdle()

        val dial = compose.onNodeWithContentDescription(str(R.string.xo_fc))
        dial.performScrollTo().assertIsDisplayed()
        client.clearSent()

        // Тап по дуге у её начала (верх шкалы): значение уходит на усилитель
        // одним шагом, без дожимовки — иначе по Bluetooth идёт поток `fc:`.
        dial.performTouchInput { click(Offset(centerX.toFloat(), height * 0.12f)) }
        compose.waitForIdle()

        val fc = client.sent.filter { it.startsWith("fc:") }
        assertEquals("Тап по ручке должен дать ровно одну команду fc", 1, fc.size)
    }

    @Test
    fun крутилкаПоказываетТекущееЗначение() {
        runBlocking { client.deviceAnswers(*statusBlock.toTypedArray()) }
        connected()
        compose.onNodeWithText(str(R.string.tab_dsp)).performClick()
        compose.waitForIdle()

        // 350 Гц из блока status: ручка обязана показывать то, что ответил
        // усилитель, а не локальное значение, оставшееся от прошлой сессии.
        val hz = "350" + str(R.string.suffix_hz).trim()
        // Прокрутка и проверка состояния разнесены: performScrollTo() над узлом,
        // найденным по связке двух признаков, не находит прокручиваемого
        // предка — он ищет по цепочке scroll-семантики от самого узла.
        compose.onNodeWithContentDescription(str(R.string.xo_fc))
            .performScrollTo()
            .assert(hasStateDescription(hz))
            .assertIsDisplayed()
    }

    @Test
    fun окноВыбораУстройстваОткрываетсяИЗакрывается() {
        connButton().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.device_picker_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.close)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.device_picker_title)).doesNotExist()
    }
}

/**
 * Отсутствие узла проверяем через [SemanticsNodeInteraction.fetchSemanticsNode],
 * бросающего исключение вместо возврата пустого узла: у Compose на разных
 * версиях метод с другим именем то есть, то нет.
 */
private fun SemanticsNodeInteraction.doesNotExist() {
    val found = runCatching { fetchSemanticsNode("Должен отсутствовать") }.isSuccess
    check(!found) { "Узел не должен существовать, но найден" }
}