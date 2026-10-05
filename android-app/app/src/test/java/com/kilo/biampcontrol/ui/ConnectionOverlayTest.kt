/*
 * Проверка правила показа оверлея подключения:
 * ui/ConnectionOverlay.kt, функция connectionOverlayVisible.
 *
 * Зачем тест. Оверлей — единственное место в интерфейсе, где решение
 * «показывать или нет» принимается сравнением состояний соединения с
 * состоянием окна выбора устройства. Ошибка здесь стоит пользователю
 * либо перекрытый список устройств (он не может ничего выбрать), либо
 * пустой экран с надписью «подключаемся», когда подключения нет вовсе.
 * Обе ошибки видны только на устройстве, поэтому правило проверяется здесь.
 */

package com.kilo.biampcontrol.ui

import com.kilo.biampcontrol.bt.ConnState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOverlayTest {

    @Test
    fun `оверлей виден при первом подключении`() {
        assertTrue(connectionOverlayVisible(ConnState.CONNECTING, devicePickerOpen = false))
    }

    @Test
    fun `оверлей виден при автореконнекте`() {
        assertTrue(connectionOverlayVisible(ConnState.RECONNECTING, devicePickerOpen = false))
    }

    @Test
    fun `после подключения оверлей исчезает`() {
        assertFalse(connectionOverlayVisible(ConnState.CONNECTED, devicePickerOpen = false))
    }

    @Test
    fun `при отключении оверлей не показывается`() {
        // Иначе приложение открывалось бы с оверлеем и требовало нажать
        // «Отмена» перед первым подключением.
        assertFalse(connectionOverlayVisible(ConnState.DISCONNECTED, devicePickerOpen = false))
    }

    @Test
    fun `открытое окно выбора важнее оверлея`() {
        // Пользователь сам открыл список во время автореконнекта: перекрывать
        // его оверлеем нельзя, иначе нажать устройство невозможно.
        assertFalse(connectionOverlayVisible(ConnState.RECONNECTING, devicePickerOpen = true))
        assertFalse(connectionOverlayVisible(ConnState.CONNECTING, devicePickerOpen = true))
    }
}