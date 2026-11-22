/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Управление идёт по Bluetooth LE GATT: приложение подключается к
 * ESP32 ("ESP32 BiAmp Speaker"), открывает сервис 0xF000 и обменивается
 * текстовыми командами через характеристики 0xF001 (команды) и 0xF002
 * (статус). Формат команд и ответов — в protocol/status-contract.md,
 * раздел 2. Источник истины — прошивка ESP32.
 *
 * Интерфейс портирован с BDK-AUDIO-APP: тёмная тема, пурпурный/циан
 * акценты, карточки со скруглёнными углами, секционные заголовки.
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
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.kilo.biampcontrol.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра приложения — по ролям, а не по именам оттенков.
 *
 * Цвета взяты из BDK-AUDIO-APP (тёмная тема с пурпурным акцентом #8B5CF6
 * и циановым вторым акцентом #06B6D4). Роли взяты из спецификации
 * Material 3 и разложены по трём уровням: акцент, поверхности (четыре
 * ступени), текст (четыре ступени). Значения тёмной темы — марка
 * приложения; светлая тема сохраняет тот же акцент, но затемнённый до
 * читаемого на белом.
 */

// ── Акцент ────────────────────────────────────────────────────────────────
/** Основной акцент: пурпурный, заливка активной части шкалы, кнопки. */
val BrandAccent = Color(0xFF8B5CF6)

/** Акцент при нажатии и как контейнер акцентных ролей в тёмной теме. */
val BrandAccentDim = Color(0xFF7C3AED)

/** Осветлённый акцент для текста поверх тёмной подложки. */
val BrandAccentSoft = Color(0xFFA78BFA)

/**
 * Второй акцент — циан, комплементарный пурпурному.
 *
 * Им размечены зона B в графе АЧХ, полоса «бас» будущего индикатора
 * уровней и предупреждения: он заметен на тёмном фоне.
 */
val BrandAlt = Color(0xFF06B6D4)

/** Текст и иконки поверх акцентной заливки. */
val BrandOnAccent = Color(0xFF0A0A0F)

// ── Поверхности, тёмная тема ───────────────────────────────────────────────
val BrandBackground = Color(0xFF121212)
val BrandSurfaceLow = Color(0xFF1E1E1E)
val BrandSurfaceMid = Color(0xFF252525)
val BrandSurfaceHigh = Color(0xFF2D2D2D)
val BrandDivider = Color(0xFF3D3D3D)
val BrandOutline = Color(0xFF3D3D3D)

// ── Текст, тёмная тема ────────────────────────────────────────────────────
val BrandTextPrimary = Color(0xFFFFFFFF)
val BrandTextSecondary = Color(0xFFB3B3B3)
val BrandTextTertiary = Color(0xFF666666)

// ── Статусы ───────────────────────────────────────────────────────────────
val BrandStatusOk = Color(0xFF22C55E)
val BrandStatusWarn = Color(0xFFFBBF24)
val BrandStatusError = Color(0xFFEF4444)

// ── Полосы индикатора уровней ─────────────────────────────────────────────
/**
 * Цвета трёх полос измерителя: бас, середина, верх.
 *
 * Заведены здесь вместе с остальной палитрой, а не в коде виджета, чтобы
 * график АЧХ и индикатор уровней читались как одна шкала. Разведены по
 * тону, а не по светлоте: при градациях серого бас и верх неразличимы.
 */
val BrandMeterBass = Color(0xFF8B5CF6)
val BrandMeterMid = Color(0xFF06B6D4)
val BrandMeterTreble = Color(0xFF22C55E)

// ── Светлая тема ──────────────────────────────────────────────────────────
val BrandLightBackground = Color(0xFFF5F7F9)
val BrandLightSurface = Color(0xFFFFFFFF)
val BrandLightSurfaceMid = Color(0xFFEFF3F6)
val BrandLightSurfaceHigh = Color(0xFFE7ECF0)
val BrandLightText = Color(0xFF101418)
val BrandLightTextSecondary = Color(0xFF3F4850)
val BrandLightOutline = Color(0xFFC3CCD4)

// Акцент для светлой темы: тот же пурпур, но затемнённый — в тёмной теме
// акцент светлее фона, в светлой темнее, и тот же оттенок на белом даёт
// контраст ниже порога WCAG 1.4.11.
val BrandLightAccent = Color(0xFF6D28D9)
val BrandLightAccentContainer = Color(0xFFE9D5FF)
val BrandLightAlt = Color(0xFF0891B2)
val BrandLightAltContainer = Color(0xFFCCFEFC)
