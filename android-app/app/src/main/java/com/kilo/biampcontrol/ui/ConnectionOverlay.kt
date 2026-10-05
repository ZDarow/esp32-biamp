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
package com.kilo.biampcontrol.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.R
import com.kilo.biampcontrol.bt.ConnState

/** Обычный размер пульсирующего кольца и период одного прохода волны. */
private const val RING_COUNT = 3
private const val RING_CYCLE_MS = 1_600
private val PULSE_SIZE = 72.dp

/**
 * Показывается ли оверлей соединения.
 *
 * Правило вынесено отдельно от отрисовки: это единственное место в
 * приложении, где «что пользователь видит» решается сравнением состояний,
 * и оно обязано проверяться без Android-рантайма.
 *
 * Окно выбора устройства важнее оверлея: если пользователь открыл список
 * во время автореконнекта, перекрывать его нельзя — он и есть ответ на
 * вопрос «подключается или нет».
 */
internal fun connectionOverlayVisible(state: ConnState, devicePickerOpen: Boolean): Boolean =
    !devicePickerOpen &&
        (state == ConnState.CONNECTING || state == ConnState.RECONNECTING)

/**
 * Оверлей «подключаемся».
 *
 * Раньше состояние соединения показывалось только цветком значка в верхней
 * панели, а сам экран оставался живым: все ползунки и кнопки выглядели
 * как обычно и просто не нажимались. Пользователь не понимал, сломано
 * приложение или выключен усилитель. Теперь поверх интерфейса появляется
 * карточка с пульсирующим кольцом и двумя действиями — отменить или
 * выбрать другое устройство, — и видно, что происходит и что можно
 * сделать.
 */
@Composable
fun ConnectionOverlay(
    state: ConnState,
    deviceName: String?,
    onCancel: () -> Unit,
    onPickDevice: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            // liveRegion: TalkBack читает смену состояния вслух, иначе он
            // объявит только «статус» и пропустит само сообщение.
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PulseRing()
                Text(
                    text = stringResource(
                        if (state == ConnState.RECONNECTING) R.string.conn_overlay_reconnect
                        else R.string.conn_overlay_connect
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                if (!deviceName.isNullOrEmpty()) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(
                        label = stringResource(R.string.conn_overlay_cancel),
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                    ChoiceButton(
                        label = stringResource(R.string.conn_overlay_pick),
                        onClick = onPickDevice,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Пульсирующее кольцо вместо стандартного индикатора.
 *
 * Три кольца со сдвигом фазы: пока идёт соединение, они расходятся и
 * гаснут волной, и глаз видит движение. Обычный `CircularProgressIndicator`
 * на старом стоке рисует тонкое дуговое колесо, которое легко принять за
 * зависшую анимацию. Кольца рисуются одним `Canvas` без анимаций
 * Material — иначе ради трёх дуг пришлось бы тянуть целую тему.
 *
 * Цвет берётся из роли `primary`, поэтому оверлей одинаков в светлой и
 * тёмной теме и никогда не остаётся чёрным на чёрном.
 */
@Composable
private fun PulseRing() {
    val transition = rememberInfiniteTransition(label = "conn")
    val rings = List(RING_COUNT) { index ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = RING_CYCLE_MS
                    0f at 0
                    1f at RING_CYCLE_MS
                },
                initialStartOffset = StartOffset(index * (RING_CYCLE_MS / RING_COUNT)),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring$index"
        )
    }

    val base = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(PULSE_SIZE)) {
        val maxRadius = size.minDimension / 2f
        rings.forEach { phase ->
            val t = phase.value
            val alpha = ((1f - t) * 0.55f).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEach
            drawCircle(
                color = base.copy(alpha = alpha),
                radius = maxRadius * (0.30f + 0.70f * t),
                style = Stroke(width = maxRadius * 0.12f)
            )
        }
    }
}