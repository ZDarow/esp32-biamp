package com.kilo.biampcontrol.bt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StatusSyncManager(
    private val sender: CommandSender,
    private val spp: SppClient,
    private val scope: CoroutineScope,
) {
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val statusBuffer = ArrayDeque<String>()
    @Volatile private var syncRequestedAt = 0L
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null

    init {
        scope.launch { spp.state.collect { state ->
            if (state == ConnState.CONNECTED) {
                statusBuffer.clear()
                _deviceState.value = DeviceState()
                sender.clearQueues()
                requestStatusSync()
            }
        } }
        startPolling()
        startWatchdog()
    }

    fun onLine(line: String) {
        val blockStarted = StatusParser.appendToBlock(statusBuffer, line, STATUS_LINES)
        if (blockStarted || StatusParser.isStatusLine(line)) {
            val r = StatusParser.parseDetailed(statusBuffer, _deviceState.value)
            r.state?.let {
                _deviceState.value = it
                if (_isSyncing.value) _isSyncing.value = false
                if (StatusParser.isBlockEnd(line)) statusBuffer.clear()
            }
        }
    }

    fun requestStatusSync() {
        _isSyncing.value = true
        syncRequestedAt = System.currentTimeMillis()
        sender.send("status", true)
    }

    fun clear() {
        statusBuffer.clear()
        _deviceState.value = DeviceState()
        _isSyncing.value = false
        pollJob?.cancel()
        watchdogJob?.cancel()
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (spp.state.value != ConnState.CONNECTED || _isSyncing.value) continue
                requestStatusSync()
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(SYNC_CHECK_MS)
                if (_isSyncing.value && System.currentTimeMillis() - syncRequestedAt > SYNC_TIMEOUT_MS) {
                    _isSyncing.value = false
                }
            }
        }
    }

    private companion object {
        const val SYNC_TIMEOUT_MS = 2_000L
        const val SYNC_CHECK_MS = 500L
        const val POLL_INTERVAL_MS = 3_000L
        const val STATUS_LINES = 24
    }
}
