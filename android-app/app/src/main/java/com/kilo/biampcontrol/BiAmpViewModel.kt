/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2.
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
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kilo.biampcontrol.bt.*
import com.kilo.biampcontrol.dsp.AutoEqImporter
import com.kilo.biampcontrol.dsp.AutoEqProfile
import com.kilo.biampcontrol.log.ConfigBackup
import com.kilo.biampcontrol.log.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BiAmpViewModel internal constructor(
    app: Application,
    createClient: (CoroutineScope, Context) -> SppClient
) : AndroidViewModel(app) {

    private val appContext: Context = app

    constructor(app: Application) : this(app, { scope, ctx -> SppManager(scope, ctx) })

    private val spp: SppClient = createClient(viewModelScope, appContext)
    private val sender = CommandSender(spp, viewModelScope, io = Dispatchers.IO, logCmd = { cmd ->
        fileLogger.log("CMD>", cmd)
    })
    private val prefs = DevicePrefs(app)
    private val statusSync = StatusSyncManager(sender, spp, viewModelScope)

    val connState = spp.state
    val deviceState = statusSync.deviceState
    val log = MutableStateFlow<List<String>>(emptyList())
    val devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val isSyncing = statusSync.isSyncing

    /** Логгер в файл (Android/data/com.kilo.biampcontrol/files/biamp.log). */
    val fileLogger: FileLogger

    /** Бекап/восстановление конфигурации аудио-устройства. */
    val configBackup: ConfigBackup

    init {
        fileLogger = FileLogger(getApplication())
        configBackup = ConfigBackup(getApplication())
        sender.start()
        viewModelScope.launch { spp.lines.collect(::onLine) }
        viewModelScope.launch { spp.log.collect(::onGattLog) }
        viewModelScope.launch {
            spp.state.collect { state ->
                fileLogger.log("CONN", state.toString())
                if (state == ConnState.CONNECTED) {
                    statusSync.requestStatusSync()
                }
            }
        }
        viewModelScope.launch { reportLostLines() }
        viewModelScope.launch { reportLostCommands() }
    }

    override fun onCleared() {
        spp.disconnect()
        sender.clearQueues()
        statusSync.clear()
        super.onCleared()
    }

    // ── Устройства ──────────────────────────────────────────────
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

    fun disconnect() {
        setAutoConnect(false)
        spp.disconnect()
    }

    val rememberedName: String? get() = prefs.lastName

    /** Имя устройства для локального отображения (не отправляется на ESP32). */
    var deviceName: String
        get() = prefs.lastName ?: ""
        set(value) = prefs.setLastName(value)

    private val _autoConnect = MutableStateFlow(prefs.autoConnect)
    val autoConnect: StateFlow<Boolean> = _autoConnect

    fun setAutoConnect(on: Boolean) {
        prefs.autoConnect = on
        _autoConnect.value = on
    }

    fun forgetDevice() {
        setAutoConnect(false)
        spp.disconnect()
        prefs.forget()
        _autoConnect.value = prefs.autoConnect
    }

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
        if (z != 0 && z != 1) return
        val target = if (isMuted(z)) 0 else 1
        sender.send("mute:$z:$target", true)
    }

    fun isMuted(z: Int): Boolean = deviceState.value.muted.getOrElse(z) { false }

    // ── Dirty indicator ─────────────────────────────────────────
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    fun markDirty() { _isDirty.value = true }
    fun markSaved() { _isDirty.value = false }

    // ── Спектр-анализатор ───────────────────────────────────────
    private val _spectrum = MutableStateFlow(FloatArray(16))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    // ── Link groups ─────────────────────────────────────────────
    private val _linkLR = MutableStateFlow(false)
    val linkLR: StateFlow<Boolean> = _linkLR.asStateFlow()

    fun setLinkLR(on: Boolean) { _linkLR.value = on }

    fun setChFilterLinked(ch: Int, hp: Int, lp: Int) {
        setChHp(ch, hp); setChLp(ch, lp)
        if (_linkLR.value) {
            val mirror = if (ch < 2) ch + 2 else ch - 2
            setChHp(mirror, hp); setChLp(mirror, lp)
        }
    }

    fun setDelayLinked(ch: Int, samples: Int) {
        sender.send("delay$ch:$samples", true)
        if (_linkLR.value) {
            val mirror = if (ch < 2) ch + 2 else ch - 2
            sender.send("delay$mirror:$samples", true)
        }
    }

    // ── EQ пресеты ──────────────────────────────────────────────
    fun applyEqPreset(name: String) {
        when (name) {
            "Flat" -> { setEq(0, 0); setEq(1, 0); setEq(2, 0) }
            "Bass" -> { setEq(0, 6); setEq(1, 0); setEq(2, 0) }
            "Treble" -> { setEq(0, 0); setEq(1, 0); setEq(2, 6) }
            "V-Shape" -> { setEq(0, 4); setEq(1, -2); setEq(2, 4) }
        }
        markDirty()
    }
    fun setFc(v: Int, force: Boolean = false) = sender.send("fc:$v", force)
    fun setHp(v: Int, force: Boolean = false) = sender.send("hp:$v", force)
    fun setSub(on: Boolean) = sender.send(if (on) "sub:1" else "sub:0", true)
    fun setTlf(v: Float, force: Boolean = false) = sender.send("tlf:${fmt(v)}", force)
    fun setThf(v: Float, force: Boolean = false) = sender.send("thf:${fmt(v)}", force)
    val eqPrefixes = listOf("eql", "eqm", "eqh")
    fun setEq(band: Int, v: Int, force: Boolean = false) {
        val prefix = eqPrefixes.getOrNull(band) ?: return
        sender.send("$prefix:$v", force)
    }

    fun setSwap(on: Boolean) = sender.send("swap:${if (on) 1 else 0}", true)

    fun setDup(on: Boolean) = sender.send("dup:${if (on) 1 else 0}", true)

    fun preset(p: Int) = sender.send("preset:$p", true)

    fun setXoType(type: Int) = sender.send("xotype:$type", true)
    fun setXoOn(on: Boolean) = sender.send(if (on) "xo:1" else "xo:0", true)
    fun setChHp(ch: Int, freq: Int) = sender.send("chhp:$ch:$freq", false)
    fun setChLp(ch: Int, freq: Int) = sender.send("chlp:$ch:$freq", false)

    fun setLrSwap(on: Boolean) = setSwap(on)

    // ── Транспорт / тесты / сервис ──────────────────────────────
    fun transport(k: String) = sender.send(k, true)
    fun startTest(mode: String) = sender.send("test:$mode", true)
    fun setTf(hz: Int) = sender.send("tf:$hz", true)
    fun saveParams() = sender.send("save", true)
    fun reboot() = sender.send("reboot", true)
    fun factoryReset() = sender.send("factory", true)

    fun setTestVol(v: Int) = sender.send("tvol:${v.coerceIn(Limits.TEST_VOL_MIN, Limits.TEST_VOL_MAX)}", false)

    fun sendDiagnostic(cmd: String) = sender.send(cmd, true)

    // ── Бекап и восстановление конфигурации ──────────────────────────

    /** Сохраняет текущее состояние устройства в JSON-файл бекапа. */
    fun backupConfig(): File? {
        return try {
            val file = configBackup.backup(deviceState.value)
            fileLogger.log("BACKUP", "Конфигурация сохранена: ${file.name}")
            file
        } catch (e: Exception) {
            fileLogger.log("BACKUP", "Ошибка сохранения: ${e.message}")
            null
        }
    }

    /** Возвращает список файлов бекапов (новые первыми). */
    fun listBackups(): List<File> = configBackup.listBackups()

    /** Удаляет файл бекапа. */
    fun deleteBackup(file: File): Boolean {
        val result = configBackup.delete(file)
        if (result) fileLogger.log("BACKUP", "Бекап удалён: ${file.name}")
        return result
    }

    /**
     * Восстанавливает конфигурацию из файла бекапа: загружает DeviceState и
     * отправляет соответствующие команды на ESP32 для применения настроек.
     *
     * Команды отправляются без троттлинга (force = true), так как это разовая
     * операция восстановления, а не перетаскивание ползунка.
     */
    fun restoreConfig(file: File): Boolean {
        val state = configBackup.restore(file) ?: return false
        val ds = deviceState.value

        // Применяем настройки, которые отличаются от текущих
        if (state.vol0 != ds.vol0) sender.send("v0:${state.vol0}", true)
        if (state.vol1 != ds.vol1) sender.send("v1:${state.vol1}", true)
        if (state.bal != ds.bal) sender.send("bal:${state.bal.toInt()}", true)
        if (state.fc != ds.fc) sender.send("fc:${state.fc.toInt()}", true)
        if (state.hp != ds.hp) sender.send("hp:${state.hp.toInt()}", true)
        if (state.subOn != ds.subOn) sender.send("sub:${if (state.subOn) 1 else 0}", true)
        if (state.tlf != ds.tlf) sender.send("tlf:${state.tlf}", true)
        if (state.thf != ds.thf) sender.send("thf:${state.thf}", true)
        if (state.eql != ds.eql) sender.send("eql:${state.eql.toInt()}", true)
        if (state.eqm != ds.eqm) sender.send("eqm:${state.eqm.toInt()}", true)
        if (state.eqh != ds.eqh) sender.send("eqh:${state.eqh.toInt()}", true)
        if (state.swapped != ds.swapped) sender.send("swap:${if (state.swapped) 1 else 0}", true)
        if (state.dup != ds.dup) sender.send("dup:${if (state.dup) 1 else 0}", true)
        if (state.xoOn != ds.xoOn) sender.send("xo:${if (state.xoOn) 1 else 0}", true)
        if (state.xoType != ds.xoType) sender.send("xotype:${state.xoType}", true)

        // Канальные фильтры и задержки
        for (i in state.delays.indices) {
            if (i < ds.delays.size && state.delays[i] != ds.delays[i]) {
                sender.send("delay$i:${state.delays[i]}", true)
            }
        }
        for (i in state.chFilters.indices) {
            if (i < ds.chFilters.size) {
                val (hp, lp) = state.chFilters[i]
                val (curHp, curLp) = ds.chFilters[i]
                if (hp != curHp) sender.send("chhp:$i:$hp", true)
                if (lp != curLp) sender.send("chlp:$i:$lp", true)
            }
        }

        // Мute по зонам
        for (i in state.muted.indices) {
            if (i < ds.muted.size && state.muted[i] != ds.muted[i]) {
                sender.send("mute:$i:${if (state.muted[i]) 1 else 0}", true)
            }
        }

        fileLogger.log("RESTORE", "Конфигурация восстановлена из: ${file.name}")
        return true
    }

    /**
     * Применяет профиль AutoEQ на ESP32: отправляет peq:preamp и peq:C:Fc:Gain:Q:type
     * для каждой секции. Команды без троттлинга (разовая операция).
     *
     * Возвращает true, если профиль применён (хотя бы одна команда отправлена).
     */
    fun applyAutoEqProfile(profile: AutoEqProfile): Boolean {
        val commands = AutoEqImporter.toFirmwareCommands(profile)
        if (commands.isEmpty()) return false

        // Первая команда — предусиление
        sender.send(commands.first(), true)
        // Остальные — секции PEQ
        commands.drop(1).forEach { cmd ->
            sender.send(cmd, true)
        }

        fileLogger.log("AUTOEQ", "Профиль применён: preamp=${profile.preamp} дБ, секций=${profile.filters.size}")
        return true
    }

    /**
     * Парсит текст профиля AutoEQ и применяет его на ESP32.
     * Возвращает true, если профиль распознан и применён.
     */
    fun applyAutoEqProfileFromText(text: String): Boolean {
        val profile = AutoEqImporter.parse(text) ?: return false
        return applyAutoEqProfile(profile)
    }

    /**
     * Читает файл профиля AutoEQ по URI и применяет его на ESP32.
     * Вызывается из MainActivity после выбора файла через SAF.
     */
    fun applyAutoEqFile(uri: Uri): Boolean {
        val resolver: ContentResolver = appContext.contentResolver
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val text = input.bufferedReader().readText()
                val result = applyAutoEqProfileFromText(text)
                if (result) {
                    fileLogger.log("AUTOEQ", "Профиль применён из файла: $uri")
                }
                result
            } ?: false
        } catch (e: Exception) {
            fileLogger.log("AUTOEQ", "Ошибка чтения файла: ${e.message}")
            false
        }
    }

    // ── Приём ───────────────────────────────────────────────────
    private fun onLine(line: String) {
        if (line.startsWith("spec:")) {
            val parts = line.removePrefix("spec:").split(",")
            if (parts.size == 16) {
                _spectrum.value = FloatArray(16) { i -> parts[i].toFloatOrNull() ?: -80f }
            }
            return
        }
        statusSync.onLine(line)
        log.value = (log.value + ts("ESP32", line)).takeLast(LOG_LINES)
        fileLogger.log("ESP32", line)
    }

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

    private fun onGattLog(line: String) {
        log.value = (log.value + ts("GATT", line)).takeLast(LOG_LINES)
        fileLogger.log("GATT", line)
    }

    private fun note(msg: String) {
        val line = ts("UI", msg)
        log.value = (log.value + line).takeLast(LOG_LINES)
        fileLogger.log("UI", msg)
    }

    /** Логирует действие пользователя в интерфейсе. */
    fun logUi(tag: String, msg: String) {
        note("[$tag] $msg")
    }

    private fun ts(tag: String, msg: String): String {
        return "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} [$tag] $msg"
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private companion object {
        const val LOG_LINES = 50
    }
}
