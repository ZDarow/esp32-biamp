/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth SPP: приложение открывает RFCOMM-сокет к
 * ESP32 ("ESP32 BiAmp Speaker") и обменивается текстовыми командами.
 * Формат команд и ответов — в firmware/DOCUMENTATION.md, раздел 4.
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

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kilo.biampcontrol.bt.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * @param createClient фабрика соединения. Продлена параметром со значением
 *   по умолчанию ради instrumented-тестов: без сопряжённого ESP32 реальный
 *   [SppManager] не может подключиться, а проверять нужно поведение ViewModel
 *   поверх заглушки. Приложение пользуется этим конструктором через
 *   `by viewModels()`: фабрика AndroidViewModel умеет только его.
 */
class BiAmpViewModel internal constructor(
    app: Application,
    createClient: (CoroutineScope) -> SppClient
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, { SppManager(it) })

    private val spp: SppClient = createClient(viewModelScope)
    private val sender = CommandSender(spp, viewModelScope)
    private val prefs = DevicePrefs(app)

    val connState = spp.state
    val deviceState = MutableStateFlow(DeviceState())
    val log = MutableStateFlow<List<String>>(emptyList())
    val devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val isSyncing = MutableStateFlow(false)
    private val mute0 = MutableStateFlow(false)
    private val mute1 = MutableStateFlow(false)

    private val lineBuffer = ArrayDeque<String>()

    @Volatile private var syncRequestedAt = 0L

    init {
        sender.start()
        viewModelScope.launch { spp.lines.collect(::onLine) }
        viewModelScope.launch {
            spp.state.collect { state ->
                if (state == ConnState.CONNECTED) {
                    mute0.value = false
                    mute1.value = false
                    // Ответ предыдущей сессии мог остаться в буфере: смешивать его
                    // с новым status нельзя, иначе UI покажет значения чужой сессии.
                    lineBuffer.clear()
                    deviceState.value = DeviceState()
                    requestStatusSync()
                } else if (state == ConnState.DISCONNECTED) {
                    isSyncing.value = false
                }
            }
        }
        viewModelScope.launch { statusPollLoop() }
        viewModelScope.launch { syncWatchdog() }
    }

    /** Ставит флаг синхронизации и отправляет `status`. Единственная точка входа. */
    private fun requestStatusSync() {
        isSyncing.value = true
        syncRequestedAt = System.currentTimeMillis()
        // Буфер очищается ДО отправки: недобранный хвост прошлого опроса
        // не должен смешаться с новым блоком, иначе в состояние попадут
        // значения двух разных моментов времени.
        lineBuffer.clear()
        sender.send("status", true)
    }

    // ── Устройства ──────────────────────────────────────────────
    /**
     * Обновляет список сопряжённых устройств.
     *
     * Вызывается из `LaunchedEffect` до того, как пользователь ответил на запрос
     * разрешений, поэтому `SecurityException` — штатная ветка, а не сбой:
     * список очищается, а `MainActivity` дёргает метод повторно из обработчика
     * результата запроса разрешений.
     */
    @SuppressLint("MissingPermission")
    fun refreshDevices(context: Context) {
        devices.value = try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun connect(dev: BluetoothDevice) {
        prefs.remember(dev)
        spp.connect(dev)
    }

    /**
     * Явное отключение по кнопке пользователя. Оно же выключает автоподключение:
     * иначе при следующем старте приложение снова подключилось бы само и
     * отключение выглядело бы неработающим. Включается обратно тумблером
     * в окне выбора устройства.
     */
    fun disconnect() {
        setAutoConnect(false)
        spp.disconnect()
    }

    /** Запомнено ли устройство (для показа в окне выбора). */
    val rememberedName: String? get() = prefs.lastName

    /**
     * Флаг автоподключения как Flow: окно выбора устройства перерисовывается
     * при переключении тумблера, а не только при пересоздании диалога.
     */
    private val _autoConnect = MutableStateFlow(prefs.autoConnect)
    val autoConnect: StateFlow<Boolean> = _autoConnect

    fun setAutoConnect(on: Boolean) {
        prefs.autoConnect = on
        _autoConnect.value = on
    }

    /** Забыть устройство: адрес, имя и автоподключение. */
    fun forgetDevice() {
        prefs.forget()
        _autoConnect.value = prefs.autoConnect
    }

    /**
     * Автоподключение к запомненному устройству при старте приложения.
     * Работает только если устройство по-прежнему сопряжено с телефоном;
     * без BLUETOOTH_CONNECT bondedDevices бросает SecurityException — тогда
     * просто ждём ручного выбора. Вызывать после [refreshDevices].
     */
    fun autoConnectIfSaved(context: Context) {
        if (!prefs.autoConnect) return
        if (spp.state.value != ConnState.DISCONNECTED) return
        val address = prefs.lastAddress ?: return
        val dev = try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bondedDevices?.firstOrNull { it.address == address }
        } catch (_: SecurityException) {
            null
        } ?: return
        spp.connect(dev)
    }

    // ── Команды (громкость/зоны) ────────────────────────────────
    fun setVolBoth(v: Int, force: Boolean = false) = sender.send("vol:$v", force)
    fun setVol0(v: Int, force: Boolean = false) = sender.send("v0:$v", force)
    fun setVol1(v: Int, force: Boolean = false) = sender.send("v1:$v", force)
    fun setBal(v: Int, force: Boolean = false) = sender.send("bal:$v", force)
    fun toggleMute(z: Int) {
        when (z) {
            0 -> {
                mute0.update { !it }
                sender.send("mute:0", true)
            }
            1 -> {
                mute1.update { !it }
                sender.send("mute:1", true)
            }
        }
    }
    val isMuted0 = mute0
    val isMuted1 = mute1

    // ── DSP ─────────────────────────────────────────────────────
    fun setFc(v: Int, force: Boolean = false) = sender.send("fc:$v", force)
    fun setHp(v: Int, force: Boolean = false) = sender.send("hp:$v", force)
    fun setSub(on: Boolean) = sender.send(if (on) "sub:1" else "sub:0", true)
    fun setTlf(v: Float, force: Boolean = false) = sender.send("tlf:${fmt(v)}", force)
    fun setThf(v: Float, force: Boolean = false) = sender.send("thf:${fmt(v)}", force)
    val eqPrefixes = listOf("eql", "eqm", "eqh")
    fun setEq(band: Int, v: Int, force: Boolean = false) =
        sender.send("${eqPrefixes[band]}:$v", force)
    fun toggleInv(ch: Int) = sender.send("inv:$ch", true)
    fun invOff() = sender.send("inv:off", true)
    fun preset(p: Int) = sender.send("preset:$p", true)

    // ── DSP v18: кроссовер, поканальные фильтры ───────────────────
    fun setXoType(type: Int) = sender.send("xotype:$type", true)
    // Выключатель общего кроссовера. Работает только с прошивкой v30+:
    // на старой команда xo: неизвестна и молча игнорируется, а статус
    // не содержит хвоста " ON"/" OFF", поэтому xoOn остаётся true.
    fun setXoOn(on: Boolean) = sender.send(if (on) "xo:1" else "xo:0", true)
    fun setChHp(ch: Int, freq: Int) = sender.send("chhp:$ch:$freq", false)
    fun setChLp(ch: Int, freq: Int) = sender.send("chlp:$ch:$freq", false)

    // ── Транспорт / тесты / сервис ──────────────────────────────
    fun transport(k: String) = sender.send(k, true)      // play/pause/next/prev
    fun startTest(mode: String) = sender.send("test:$mode", true)
    fun setTf(hz: Int) = sender.send("tf:$hz", true)
    fun saveParams() = sender.send("save", true)
    fun reboot() = sender.send("reboot", true)
    fun factoryReset() = sender.send("factory", true)

    fun setTestVol(v: Int) = sender.send("tvol:$v", false)

    fun sendDiagnostic(cmd: String) = sender.send(cmd, true)

    // ── Приём ───────────────────────────────────────────────────
    private fun onLine(line: String) {
        lineBuffer.addLast(line)
        if (lineBuffer.size > STATUS_LINES) lineBuffer.removeFirst()
        // Разбор идёт поверх текущего состояния, а не поверх значений по
        // умолчанию: блок status приходит одиннадцатью строками, и на первых
        // десяти в буфере ещё нет, например, строки Delay. Разбор с нуля
        // обнулял бы эти поля, и ползунки прыгали бы к дефолту и обратно.
        StatusParser.parse(lineBuffer, deviceState.value)?.let {
            deviceState.value = it
            // Полный блок status разобран — синхронизация завершена.
            if (isSyncing.value) isSyncing.value = false
            // Блок разобран целиком: очищаем буфер, чтобы следующий опрос
            // не смешался с предыдущим наполовину.
            if (StatusParser.isBlockEnd(line)) lineBuffer.clear()
        }
        log.value = (log.value + line).takeLast(LOG_LINES)
    }

    private suspend fun statusPollLoop() {
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            // Пока предыдущий status в обработке, новый не шлём: ответы наложились бы
            // и в буфер попали строки двух разных опросов.
            if (spp.state.value != ConnState.CONNECTED || isSyncing.value) continue
            requestStatusSync()
        }
    }

    /**
     * Снимает флаг синхронизации, если устройство не ответило на status.
     * Без этого опрос встал бы навсегда после первого потерянного ответа.
     */
    private suspend fun syncWatchdog() {
        while (currentCoroutineContext().isActive) {
            delay(SYNC_CHECK_MS)
            if (isSyncing.value && elapsedSinceSyncRequest() > SYNC_TIMEOUT_MS) {
                isSyncing.value = false
            }
        }
    }

    private fun elapsedSinceSyncRequest(): Long =
        System.currentTimeMillis() - syncRequestedAt

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private companion object {
        /** Столько ждём ответа на status, прежде чем снять флаг синхронизации. */
        const val SYNC_TIMEOUT_MS = 2_000L
        /** Период проверки сторожа. */
        const val SYNC_CHECK_MS = 500L
        /** Период опроса состояния устройства. */
        const val POLL_INTERVAL_MS = 3_000L
        /** Размер скользящего буфера строк для разбора status. */
        const val STATUS_LINES = 24
        /** Сколько последних строк SPP хранить в журнале. */
        const val LOG_LINES = 50
    }
}