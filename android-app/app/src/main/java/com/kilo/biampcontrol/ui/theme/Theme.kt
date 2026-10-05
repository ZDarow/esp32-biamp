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

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Тёмная схема — основная.
 *
 * Заданы все роли Material 3, а не пять: у Compose есть и `surfaceContainer*`
 * (четыре ступени, в них строятся карточки и неактивные треки), и
 * `surfaceVariant`, который устарел, но на который ещё ссылается часть
 * компонентов. Если роль не задать, Compose возьмёт значение из базовой
 * схемы — оно подобрано под обои телефона, а не под панель усилителя, и
 * карточки начнут отличаться по оттенку от фона, на котором лежат.
 */
private val DarkColors = darkColorScheme(
    primary = BrandAccent,
    onPrimary = BrandOnAccent,
    primaryContainer = BrandAccentDim,
    onPrimaryContainer = BrandOnAccent,
    inversePrimary = BrandAccentSoft,
    secondary = BrandAlt,
    onSecondary = BrandOnAccent,
    secondaryContainer = BrandLightAltContainer,
    onSecondaryContainer = BrandLightAlt,
    tertiary = BrandAccentSoft,
    onTertiary = BrandOnAccent,
    tertiaryContainer = BrandLightAccentContainer,
    onTertiaryContainer = BrandLightAccent,
    background = BrandBackground,
    onBackground = BrandTextPrimary,
    surface = BrandSurfaceLow,
    onSurface = BrandTextPrimary,
    surfaceVariant = BrandSurfaceHigh,
    onSurfaceVariant = BrandTextSecondary,
    surfaceDim = Color(0xFF08080C),
    surfaceBright = BrandSurfaceHigh,
    surfaceContainerLowest = Color(0xFF050508),
    surfaceContainerLow = Color(0xFF0F0F16),
    surfaceContainer = BrandSurfaceMid,
    surfaceContainerHigh = Color(0xFF1E1E2A),
    surfaceContainerHighest = BrandDivider,
    outline = BrandOutline,
    outlineVariant = BrandDivider,
    inverseSurface = BrandTextPrimary,
    inverseOnSurface = BrandBackground,
    error = BrandStatusError,
    onError = Color.White,
    errorContainer = Color(0xFF3A0E0E),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
)

/**
 * Светлая схема — тот же акцент, затемнённый.
 *
 * Значения акцента не переиспользуются из тёмной темы: в тёмной акцент
 * светлее фона, в светлой темнее, и один и тот же циан на белом даёт
 * контраст ниже порога WCAG 1.4.11 для мелкого текста.
 */
private val LightColors = lightColorScheme(
    primary = BrandLightAccent,
    onPrimary = Color.White,
    primaryContainer = BrandLightAccentContainer,
    onPrimaryContainer = BrandLightAccent,
    inversePrimary = BrandAccent,
    secondary = BrandLightAlt,
    onSecondary = Color.White,
    secondaryContainer = BrandLightAltContainer,
    onSecondaryContainer = BrandLightAlt,
    tertiary = BrandLightAccent,
    onTertiary = Color.White,
    tertiaryContainer = BrandLightAccentContainer,
    onTertiaryContainer = BrandLightAccent,
    background = BrandLightBackground,
    onBackground = BrandLightText,
    surface = BrandLightSurface,
    onSurface = BrandLightText,
    surfaceVariant = BrandLightSurfaceHigh,
    onSurfaceVariant = BrandLightTextSecondary,
    surfaceDim = Color(0xFFDCE2E7),
    surfaceBright = BrandLightSurface,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = BrandLightBackground,
    surfaceContainer = BrandLightSurfaceMid,
    surfaceContainerHigh = BrandLightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFDFE5EA),
    outline = BrandLightOutline,
    outlineVariant = Color(0xFFDCE2E7),
    inverseSurface = BrandLightText,
    inverseOnSurface = BrandLightBackground,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black
)

/**
 * Формы по шкале Material 3: 4 / 8 / 12 / 16 / 28 dp.
 *
 * Раньше `Shapes` не задавался вовсе, и все компоненты брали базовую шкалу
 * Material, у которой `extraLarge` = 28 dp, а `large` = 16 dp — на глаз
 * неотличимо от этих, но при явном задании видно, что компонент выбирает
 * форму, а не попадает в неё. Кнопки наследуют `full` отдельно: радиус
 * кнопок задаёт сама M3 через `ButtonDefaults`, и в шкале он не хранится.
 */
private val BiAmpShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Тема приложения.
 *
 * @param dynamicColor Material You (обои Android 12+). По умолчанию **выключено**:
 *   акцент панели усилителя — часть марки, и он не должен зависеть от обоев
 *   телефона; включить можно для пользователя, которому важнее единство с
 *   системой, чем узнаваемость.
 */
@Composable
fun BiAmpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = BiAmpShapes,
        content = content
    )
}
