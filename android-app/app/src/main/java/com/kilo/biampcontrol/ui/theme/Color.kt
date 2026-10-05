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

/**
 * Палитра приложения — по ролям, а не по именам оттенков.
 *
 * Прежние имена вида `Blue400` описывали оттенок, а не назначение, и потому
 * не отвечали на вопрос «какой именно цвет здесь должен быть»: акцент в
 * приложении — один, и он меняется вместе с брендом, а не вместе с
 * палитрой Material. Роли взяты из спецификации Material 3 и разложены по
 * трём уровням: акцент, поверхности (четыре ступени), текст (четыре
 * ступени). Значения тёмной темы — марка приложения; светлая тема
 * сохраняет тот же акцент, но затемнённый до читаемого на белом.
 */

// ── Акцент ────────────────────────────────────────────────────────────────
/** Основной акцент: заливка активной части шкалы, наполненные кнопки. */
val BrandAccent = Color(0xFF00D4FF)

/** Акцент при нажатии и как контейнер акцентных ролей в тёмной теме. */
val BrandAccentDim = Color(0xFF00A3C7)

/** Осветлённый акцент для текста поверх тёмной подложки. */
val BrandAccentSoft = Color(0xFF4DE8FF)

/**
 * Второй акцент — комплементарный оранжевый.
 *
 * Им размечены зона B в графе АЧХ, полоса «бас» будущего индикатора
 * уровней и предупреждения: он заметен на холодном фоне, где зелёный и
 * жёлтый от него не отличаются.
 */
val BrandAlt = Color(0xFFFF6B35)

/** Текст и иконки поверх акцентной заливки. */
val BrandOnAccent = Color(0xFF0A0A0F)

// ── Поверхности, тёмная тема ───────────────────────────────────────────────
val BrandBackground = Color(0xFF0A0A0F)
val BrandSurfaceLow = Color(0xFF12121A)
val BrandSurfaceMid = Color(0xFF1A1A25)
val BrandSurfaceHigh = Color(0xFF1E1E2A)
val BrandDivider = Color(0xFF2A2A35)
val BrandOutline = Color(0xFF3A3A45)

// ── Текст, тёмная тема ────────────────────────────────────────────────────
val BrandTextPrimary = Color(0xFFFFFFFF)
val BrandTextSecondary = Color(0xFFB8B8C8)
val BrandTextTertiary = Color(0xFF6B6B7B)

// ── Статусы ───────────────────────────────────────────────────────────────
val BrandStatusOk = Color(0xFF00E676)
val BrandStatusWarn = Color(0xFFFFAB40)
val BrandStatusError = Color(0xFFFF5252)

// ── Полосы индикатора уровней ─────────────────────────────────────────────
/**
 * Цвета трёх полос измерителя: бас, середина, верх.
 *
 * Заведены здесь вместе с остальной палитрой, а не в коде виджета, чтобы
 * график АЧХ и индикатор уровней читались как одна шкала. Разведены по
 * тону, а не по светлоте: при градациях серого бас и верх неразличимы.
 */
val BrandMeterBass = Color(0xFFFF6B35)
val BrandMeterMid = Color(0xFF00D4FF)
val BrandMeterTreble = Color(0xFF00E676)

// ── Светлая тема ──────────────────────────────────────────────────────────
val BrandLightBackground = Color(0xFFF5F7F9)
val BrandLightSurface = Color(0xFFFFFFFF)
val BrandLightSurfaceMid = Color(0xFFEFF3F6)
val BrandLightSurfaceHigh = Color(0xFFE7ECF0)
val BrandLightText = Color(0xFF101418)
val BrandLightTextSecondary = Color(0xFF3F4850)
val BrandLightOutline = Color(0xFFC3CCD4)

// Акцент для светлой темы: тот же циан, но затемнённый — в тёмной теме
// акцент светлее фона, в светлой темнее, и тот же оттенок на белом даёт
// контраст ниже порога WCAG 1.4.11.
val BrandLightAccent = Color(0xFF00658A)
val BrandLightAccentContainer = Color(0xFFB8ECFF)
val BrandLightAlt = Color(0xFF8B3A12)
val BrandLightAltContainer = Color(0xFFFFDBCA)
