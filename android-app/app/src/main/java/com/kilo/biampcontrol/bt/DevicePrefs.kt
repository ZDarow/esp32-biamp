/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Память устройства: адрес и имя последнего успешно выбранного ESP32
 * плюс флаг автоподключения. Хранится в приватных SharedPreferences,
 * наружу не отдаётся и не попадает в логи.
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
package com.kilo.biampcontrol.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * Хранилище «последнего устройства».
 *
 * По умолчанию автоподключение включено: как только пользователь один раз
 * выбрал усилитель, приложение подключается к нему само при каждом старте.
 * Явное отключение пользователем ([autoConnect] = false) отключает эту
 * поведенку до явного включения обратно.
 */
class DevicePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** MAC-адрес запомненного устройства; null, если устройство ещё не выбиралось. */
    val lastAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)

    /** Имя запомненного устройства — только для показа в интерфейсе. */
    val lastName: String?
        get() = prefs.getString(KEY_NAME, null)

    /** Подключаться к запомненному устройству при старте приложения. */
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /**
     * Запоминает устройство при подключении. Имя читается безопасно:
     *  без разрешения BLUETOOTH_CONNECT Android бросает SecurityException.
     *
     * Флаг автоподключения не перезаписывается: пользователь мог его осознанно
     *  отключить, и ручной выбор устройства не должно молча включать его обратно.
     *  Значение по умолчанию (true) ставится только когда флага ещё не было —
     *  то есть при самом первом подключении или после [forget].
     */
    @SuppressLint("MissingPermission")
    fun remember(dev: BluetoothDevice) {
        val name = try { dev.name } catch (_: SecurityException) { null }
        val hadAutoFlag = prefs.contains(KEY_AUTO)
        prefs.edit()
            .putString(KEY_ADDRESS, dev.address)
            .putString(KEY_NAME, name ?: dev.address)
            .apply()
        if (!hadAutoFlag) autoConnect = true
    }

    /** Полностью забывает устройство: адрес, имя и флаг автоподключения. */
    fun forget() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "biamp_device"
        private const val KEY_ADDRESS = "last_address"
        private const val KEY_NAME = "last_name"
        private const val KEY_AUTO = "auto_connect"
    }
}