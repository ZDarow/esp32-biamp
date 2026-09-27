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

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import com.kilo.biampcontrol.bt.ConnState
import com.kilo.biampcontrol.ui.DspTab
import com.kilo.biampcontrol.ui.ServiceTab
import com.kilo.biampcontrol.ui.VolumeTab
import com.kilo.biampcontrol.ui.theme.BiAmpTheme

class MainActivity : ComponentActivity() {

    private val btPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Пока разрешение не выдано, refreshDevices() возвращает пустой список.
            // Как только его дали, список надо перечитать — иначе окно выбора
            // устройства останется пустым до перезапуска приложения.
            if (granted.values.any { it }) {
                val vm: BiAmpViewModel by viewModels()
                vm.refreshDevices(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            BiAmpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: BiAmpViewModel by viewModels()
                    MainScreen(vm)
                }
            }
        }
    }

    private fun ensurePermissions() {
        val missing = btPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: BiAmpViewModel) {
    val connState by vm.connState.collectAsState()
    val devices by vm.devices.collectAsState()
    val ctx = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDevicePicker by remember { mutableStateOf(false) }

    val tabs = listOf(
        TabItem("Громкость", Icons.Default.GraphicEq),
        TabItem("DSP", Icons.Default.Settings),
        TabItem("Сервис", Icons.Default.Build)
    )

    LaunchedEffect(Unit) { vm.refreshDevices(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BiAmp Control") },
                actions = {
                    val color = when (connState) {
                        ConnState.CONNECTED -> Color(0xFF4CAF50)
                        ConnState.CONNECTING, ConnState.RECONNECTING -> Color(0xFFFFC107)
                        ConnState.DISCONNECTED -> Color(0xFFF44336)
                    }
                    val label = when (connState) {
                        ConnState.CONNECTED -> "ON"
                        ConnState.CONNECTING -> "..."
                        ConnState.RECONNECTING -> "RE"
                        ConnState.DISCONNECTED -> "OFF"
                    }
                    Surface(
                        color = color,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (connState == ConnState.CONNECTED || connState == ConnState.RECONNECTING) {
                        TextButton(onClick = { vm.disconnect() }) {
                            Text("Откл.", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = {
                            vm.refreshDevices(ctx)
                            showDevicePicker = true
                        }) { Text("Подкл.") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> VolumeTab(vm)
                1 -> DspTab(vm)
                2 -> ServiceTab(vm)
            }
        }
    }

    if (showDevicePicker) {
        AlertDialog(
            onDismissRequest = { showDevicePicker = false },
            title = { Text("Выберите устройство") },
            text = {
                Column {
                    if (devices.isEmpty()) {
                        Text("Нет сопряжённых устройств.\nСначала спарьте телефон с ESP32 BiAmp Speaker в настройках Bluetooth.")
                    }
                    devices.forEach { dev ->
                        TextButton(
                            onClick = {
                                vm.connect(dev)
                                showDevicePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(dev.name ?: dev.address)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDevicePicker = false }) { Text("Закрыть") }
            }
        )
    }
}

