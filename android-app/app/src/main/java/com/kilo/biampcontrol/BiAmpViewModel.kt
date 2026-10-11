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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class BiAmpViewModel internal constructor(
    app: Application,
    createClient: (CoroutineScope) -> SppClient
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, { SppManager(it) })

    private val spp: SppClient = createClient(viewModelScope)
    private val sender = CommandSender(spp, viewModelScope, io = Dispatchers.IO)
    private val prefs = DevicePrefs(app)
    private val statusSync = StatusSyncManager(sender, spp, viewModelScope)

    val connState = spp.state
    val deviceState = statusSync.deviceState
    val log = MutableStateFlow<List<String>>(emptyList())
    val devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val isSyncing = statusSync.isSyncing

    init {
        sender.start()
        viewModelScope.launch { spp.lines.collect(::onLine) }
        viewModelScope.launch {
            spp.state.collect { state ->
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

    // ── DSP ─────────────────────────────────────────────────────
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

    // ── Приём ───────────────────────────────────────────────────
    private fun onLine(line: String) {
        statusSync.onLine(line)
        log.value = (log.value + line).takeLast(LOG_LINES)
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

    private fun note(msg: String) {
        log.value = (log.value + msg).takeLast(LOG_LINES)
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private companion object {
        const val LOG_LINES = 50
    }
}
