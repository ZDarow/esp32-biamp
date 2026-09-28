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
package com.kilo.biampcontrol.ui.theme

import androidx.compose.ui.graphics.Color

// Палитра приложения (запасной вариант, когда Material You недоступен).
val Blue400 = Color(0xFF4FC3F7)
val Cyan300 = Color(0xFF80DEEA)
val Green200 = Color(0xFFA5D6A7)
val DarkBackground = Color(0xFF101418)
val DarkSurface = Color(0xFF1A2026)
val LightBackground = Color(0xFFF7F9FB)
val LightSurface = Color(0xFFFFFFFF)
val BlueGrey800 = Color(0xFF1E3A4F)
