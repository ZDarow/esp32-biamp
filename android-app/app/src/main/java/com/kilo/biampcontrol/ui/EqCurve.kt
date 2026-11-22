package com.kilo.biampcontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.dsp.BiquadFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Частоты EQ-полос в прошивке (ESP32_BiAmp.ino:341-343). */
private const val EQ_LOW_HZ = 120.0
private const val EQ_MID_HZ = 1000.0
private const val EQ_HIGH_HZ = 6000.0

/** Q для EQ-полос (ESP32_BiAmp.ino:341-343). */
private const val EQ_LOW_Q = 0.7071
private const val EQ_MID_Q = 1.0
private const val EQ_HIGH_Q = 0.7071

/**
 * Кривая АЧХ 3-полосного эквалайзера с перетаскиваемыми полосами.
 * Drag по Y меняет gain. Частоты фиксированы (120/1000/6000 Гц).
 */
@Composable
fun EqCurve(
    eql: Float,
    eqm: Float,
    eqh: Float,
    onEqChange: (Int, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val lowGain = remember { mutableFloatStateOf(eql) }
    val midGain = remember { mutableFloatStateOf(eqm) }
    val highGain = remember { mutableFloatStateOf(eqh) }

    LaunchedEffect(eql) { lowGain.floatValue = eql }
    LaunchedEffect(eqm) { midGain.floatValue = eqm }
    LaunchedEffect(eqh) { highGain.floatValue = eqh }

    val low = rememberBiquad(EQ_LOW_HZ, lowGain.floatValue.toDouble(), BiquadFilter.FilterType.LOWSHELF, EQ_LOW_Q)
    val mid = rememberBiquad(EQ_MID_HZ, midGain.floatValue.toDouble(), BiquadFilter.FilterType.PEAK_EQ, EQ_MID_Q)
    val high = rememberBiquad(EQ_HIGH_HZ, highGain.floatValue.toDouble(), BiquadFilter.FilterType.HIGHSHELF, EQ_HIGH_Q)

    val grid = MaterialTheme.colorScheme.outlineVariant
    val curve = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val pos = change.position
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val dbMin = -12.0
                val dbMax = 12.0
                val fMin = 20.0
                val fMax = 20000.0

                fun logPos(f: Double): Float =
                    ((log10(f.coerceIn(fMin, fMax)) - log10(fMin)) / (log10(fMax) - log10(fMin))).toFloat()

                val lowX = logPos(EQ_LOW_HZ) * w
                val midX = logPos(EQ_MID_HZ) * w
                val highX = logPos(EQ_HIGH_HZ) * w

                val db = (dbMax - (pos.y / h) * (dbMax - dbMin)).coerceIn(dbMin, dbMax).toFloat()

                // Определяем ближайшую полосу
                val distLow = abs(pos.x - lowX)
                val distMid = abs(pos.x - midX)
                val distHigh = abs(pos.x - highX)
                val minDist = minOf(distLow, distMid, distHigh)

                when {
                    minDist == distLow && distLow < 40f -> {
                        lowGain.floatValue = db
                        onEqChange(0, db)
                    }
                    minDist == distMid && distMid < 40f -> {
                        midGain.floatValue = db
                        onEqChange(1, db)
                    }
                    minDist == distHigh && distHigh < 40f -> {
                        highGain.floatValue = db
                        onEqChange(2, db)
                    }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val fMin = 20.0
        val fMax = 20000.0
        val dbMin = -12.0
        val dbMax = 12.0

        fun yOf(db: Double): Float =
            (h * ((dbMax - db.coerceIn(dbMin, dbMax)) / (dbMax - dbMin))).toFloat().coerceIn(0f, h)

        fun logPos(f: Double): Float =
            ((log10(f.coerceIn(fMin, fMax)) - log10(fMin)) / (log10(fMax) - log10(fMin))).toFloat()

        // Сетка
        drawLine(grid, Offset(0f, yOf(0.0)), Offset(w, yOf(0.0)), strokeWidth = 1f)
        for (db in listOf(-12.0, -6.0, 6.0, 12.0)) {
            drawLine(grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = 0.5f)
        }
        for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)) {
            val x = logPos(f) * w
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 0.5f)
        }

        // Кривая АЧХ
        val path = Path()
        val steps = 200
        for (i in 0..steps) {
            val pos = i.toFloat() / steps
            val f = 10.0.pow(log10(fMin) + pos * (log10(fMax) - log10(fMin)))
            val pr = low.powerRatio(f) * mid.powerRatio(f) * high.powerRatio(f)
            val db = 10.0 * log10(pr.coerceAtLeast(1e-30))
            val y = yOf(db)
            val x = pos * w
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, curve, style = Stroke(width = 2f))

        // Точки полос
        for ((freq, gain) in listOf(EQ_LOW_HZ to lowGain.floatValue, EQ_MID_HZ to midGain.floatValue, EQ_HIGH_HZ to highGain.floatValue)) {
            val x = logPos(freq) * w
            val y = yOf(gain.toDouble())
            drawCircle(color = knobColor, radius = 6f, center = Offset(x, y))
            drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
        }
    }
}

@Composable
private fun rememberBiquad(freq: Double, gainDb: Double, type: BiquadFilter.FilterType, q: Double): BiquadFilter {
    return remember(freq, gainDb, type, q) {
        BiquadFilter(freq.toFloat(), gainDb.toFloat(), type, q = q)
    }
}