/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth SPP: приложение открывает RFCOMM-сокет к
 * ESP32 ("ESP32 BiAmp Speaker") и обменивается текстовыми командами.
 * Формат команд и ответов — в protocol/status-contract.md, раздел 2.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    // Цикл отправки живёт на Dispatchers.IO: синхронный write()/flush()
    // на главном потоке — это прямой путь к ANR.
    private val sender = CommandSender(spp, viewModelScope, io = Dispatchers.IO)
    private val prefs = DevicePrefs(app)

    val connState = spp.state
    val deviceState = MutableStateFlow(DeviceState())
    val log = MutableStateFlow<List<String>>(emptyList())
    val devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val isSyncing = MutableStateFlow(false)

    /**
     * Буфер строк блока `status`.
     *
     * Живёт отдельно от журнала: раньше обе строки шли в один `lineBuffer`, и
     * многострочный вывод `help`/`stats`/`heap` (он идёт с `force = true` и
     * печатается один раз, но на 40+ строк) вытеснял из буфера начало блока
     * `status`. Парсер терял `V0=`, а вместе с ним громкость и баланс, и
     * значения на экране «залипали» до следующего полного блока.
     */
    private val statusBuffer = ArrayDeque<String>()

    @Volatile private var syncRequestedAt = 0L

    init {
        sender.start()
        viewModelScope.launch { spp.lines.collect(::onLine) }
        viewModelScope.launch {
            spp.state.collect { state ->
                if (state == ConnState.CONNECTED) {
                    // Ответ предыдущей сессии мог остаться в буфере: смешивать его
                    // с новым status нельзя, иначе UI покажет значения чужой сессии.
                    statusBuffer.clear()
                    deviceState.value = DeviceState()
                    // Команды, накопленные до обрыва, относятся к прежнему
                    // устройству: на новом усилителе они выставили бы чужую
                    // громкость. Очереди очищаются на обеих границах сессии.
                    sender.clearQueues()
                    requestStatusSync()
                } else if (state == ConnState.DISCONNECTED) {
                    isSyncing.value = false
                    sender.clearQueues()
                }
            }
        }
        viewModelScope.launch { reportLostLines() }
        viewModelScope.launch { reportLostCommands() }
        viewModelScope.launch { statusPollLoop() }
        viewModelScope.launch { syncWatchdog() }
    }

    /**
     * Разрыв соединения при уничтожении ViewModel.
     *
     * Без этого `viewModelScope` отменяет циклы, а сокет остаётся открытым:
     * устройство думает, что клиент на связи, и следующая попытка
     * подключения упирается в занятый канал RFCOMM.
     */
    override fun onCleared() {
        spp.disconnect()
        sender.clearQueues()
        super.onCleared()
    }

    /** Ставит флаг синхронизации и отправляет `status`. Единственная точка входа. */
    private fun requestStatusSync() {
        isSyncing.value = true
        syncRequestedAt = System.currentTimeMillis()
        // Буфер НЕ очищается здесь: он чистится по первой строке пришедшего
        // блока ([StatusParser.isBlockStart]). Очистка здесь обрезала блок,
        // который уже идёт от усилителя, если запрос пришёл в его середину:
        // в буфере оставался хвост без начала, и часть полей состояния
        // оставалась прежней. Остаток прошлой сессии очищается при CONNECTED
        // и после разбора полного блока.
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
     * Подключение по введённому вручную адресу.
     *
     * Единственный способ добраться до усилителя, который не сопряжён с
     * этим телефоном: Android показывает в списке только сопряжённые
     * устройства, а прошивка не пишет MAC в NVS и не восстанавливает его
     * после сброса — то есть после «заводского сброса» телефон о нём
     * забывает. Адрес приходится вводить руками.
     *
     * Невалидный адрес и отсутствие адаптера дают `false`: вызывающий
     * покажет сообщение сам, ViewModel не занимается диалогами.
     */
    @SuppressLint("MissingPermission")
    fun connectByAddress(context: Context, input: String): Boolean {
        val mac = MacAddress.normalize(input) ?: return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter ?: return false
        val dev = try {
            adapter.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        connect(dev)
        return true
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

    /**
     * Забыть устройство: адрес, имя и автоподключение.
     *
     * Разрыв соединения обязателен: забытый адрес остаётся в памяти усилителя
     * как активная сессия, и приложение продолжило бы слать команды устройству,
     * о котором пользователь забыл, до ручного «Откл.».
     */
    fun forgetDevice() {
        setAutoConnect(false)
        spp.disconnect()
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

    /**
     * Mute как абсолютная установка.
     *
     * Прошивка v35.1 различает `mute:<z>` (переключатель, аргумент игнорируется)
     * и `mute:<z>:<0|1>` (установка). Старый клиентский `toggleMute` слал
     * `mute:0` и одновременно переворачивал ЛОКАЛЬНЫЙ флаг, а состояние
     * обновлялось только следующим блоком `status` — между нажатием и ответом
     * индикатор врал, а при потерянном ответе оставался врёным навсегда.
     * Теперь цель вычисляется из прочитанного `Mute: z0/z1` и подтверждается
     * только реальным статусом: [deviceState] — единственный источник истины.
     */
    fun toggleMute(z: Int) {
        if (z != 0 && z != 1) return
        val target = if (isMuted(z)) 0 else 1
        sender.send("mute:$z:$target", true)
    }

    /** Прочитанное состояние зоны [z]; неизвестная зона — не заглушена. */
    fun isMuted(z: Int): Boolean = deviceState.value.muted.getOrElse(z) { false }

    // ── DSP ─────────────────────────────────────────────────────
    fun setFc(v: Int, force: Boolean = false) = sender.send("fc:$v", force)
    fun setHp(v: Int, force: Boolean = false) = sender.send("hp:$v", force)
    fun setSub(on: Boolean) = sender.send(if (on) "sub:1" else "sub:0", true)
    fun setTlf(v: Float, force: Boolean = false) = sender.send("tlf:${fmt(v)}", force)
    fun setThf(v: Float, force: Boolean = false) = sender.send("thf:${fmt(v)}", force)
    val eqPrefixes = listOf("eql", "eqm", "eqh")
/** Полоса вне 0..2 игнорируется: `getOrNull`, а не исключение из List. */
    fun setEq(band: Int, v: Int, force: Boolean = false) {
        val prefix = eqPrefixes.getOrNull(band) ?: return
        sender.send("$prefix:$v", force)
    }

    /** Перестановка Л/П (строка `SWP:` контракта). */
    fun setSwap(on: Boolean) = sender.send("swap:${if (on) 1 else 0}", true)

    /**
     * Дублирование выхода на каналы 2,3 (строка `DUP:` контракта).
     *
     * При `DUP: 1` каналы 2 и 3 физически молчат: предупреждение обязано быть
     * на экране, иначе пользователь ищет неисправность там, где она не в
     * усилителе.
     */
    fun setDup(on: Boolean) = sender.send("dup:${if (on) 1 else 0}", true)
    fun preset(p: Int) = sender.send("preset:$p", true)

    // ── DSP v18: кроссовер, поканальные фильтры ───────────────────
    fun setXoType(type: Int) = sender.send("xotype:$type", true)
    // Выключатель общего кроссовера. Работает только с прошивкой v30+:
    // на старой команда xo: неизвестна и молча игнорируется, а статус
    // не содержит хвоста " ON"/" OFF", поэтому xoOn остаётся true.
    fun setXoOn(on: Boolean) = sender.send(if (on) "xo:1" else "xo:0", true)
    fun setChHp(ch: Int, freq: Int) = sender.send("chhp:$ch:$freq", false)
    fun setChLp(ch: Int, freq: Int) = sender.send("chlp:$ch:$freq", false)

    // ── Прошивка v35: перестановка выходов Л/П вместо инверсии фазы ──
    /** Синоним [setSwap]: UI перестановки Л/П называет команду так же. */
    fun setLrSwap(on: Boolean) = setSwap(on)

    // ── Транспорт / тесты / сервис ──────────────────────────────
    fun transport(k: String) = sender.send(k, true)      // play/pause/next/prev
    fun startTest(mode: String) = sender.send("test:$mode", true)
    fun setTf(hz: Int) = sender.send("tf:$hz", true)
    fun saveParams() = sender.send("save", true)
    fun reboot() = sender.send("reboot", true)
    fun factoryReset() = sender.send("factory", true)

    fun setTestVol(v: Int) = sender.send("tvol:${v.coerceIn(Limits.TEST_VOL_MIN, Limits.TEST_VOL_MAX)}", false)

    fun sendDiagnostic(cmd: String) = sender.send(cmd, true)

    // ── Приём ───────────────────────────────────────────────────
    private fun onLine(line: String) {
        // Политика буфера (обнуление на начале блока, отбрасывание лишнего)
        // живёт в парсере: здесь только накопление и разбор.
        val blockStarted = StatusParser.appendToBlock(statusBuffer, line, STATUS_LINES)
        if (blockStarted || StatusParser.isStatusLine(line)) {
            // Разбор идёт поверх текущего состояния, а не поверх значений по
            // умолчанию: блок status приходит 13 строками, и на первых
            // двенадцати в буфере ещё нет, например, строки Delay. Разбор с
            // нуля обнулял бы эти поля, и ползунки прыгали бы к дефолту и обратно.
            val r = StatusParser.parseDetailed(statusBuffer, deviceState.value)
            r.state?.let {
                deviceState.value = it
                // Полный блок status разобран — синхронизация завершена.
                if (isSyncing.value) isSyncing.value = false
                // Блок разобран целиком: очищаем буфер, чтобы следующий опрос
                // не смешался с предыдущим наполовину.
                if (StatusParser.isBlockEnd(line)) statusBuffer.clear()
            }
        }
        log.value = (log.value + line).takeLast(LOG_LINES)
    }

    /**
     * Потери на транспорте — в журнал.
     *
     * Строка SPP, отброшенная переполненным буфером, и команда, отброшенная
     * переполненной очередью, не возвращаются: приложение обязано сказать об
     * этом пользователю, иначе «залипшие» значения выглядят как поломка
     * усилителя.
     */
    private suspend fun reportLostLines() {
        var lastSpp = 0L
        var lastCmd = 0
        spp.droppedLines.collect { n ->
            if (n > lastSpp) {
                note("! потеряно строк от усилителя: ${n - lastSpp} (всего $n)")
                lastSpp = n
            }
        }
    }

    private suspend fun reportLostCommands() {
        var last = 0
        sender.dropped.collect { n ->
            if (n > last) {
                note("! потеряно команд усилителю: ${n - last} (всего $n)")
                last = n
            }
        }
    }

    /** Служебная строка журнала — с восклицательным знаком, чтобы её искали. */
    private fun note(msg: String) {
        log.value = (log.value + msg).takeLast(LOG_LINES)
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
