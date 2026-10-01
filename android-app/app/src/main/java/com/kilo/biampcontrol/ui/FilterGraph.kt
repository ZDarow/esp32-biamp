/*
 * BiAmp Control — Android-приложение для усилителя ESP32 Bi-Amp.
 *
 * Графический редактор поканальных фильтров в духе Prology.
 * АЧХ считается теми же формулами RBJ, что и в прошивке
 * (calcSectionTo, ESP32_BiAmp.ino:190-228), поэтому график совпадает
 * с тем, что реально делает ESP32, а не приближённо рисует идеал.
 *
 * Прошивка не поддерживает отдельные команды крутизны, уровня и фазы
 * для канальных фильтров, поэтому редактируются только реальные поля:
 * частота ФВЧ (chhp) и частота ФНЧ (chlp). Q зафиксирован на 0.7071
 * (12 дБ/окт), уровень и фаза — только визуальная модель RBJ.
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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kilo.biampcontrol.BiAmpViewModel
import com.kilo.biampcontrol.R
import com.kilo.biampcontrol.bt.DeviceState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Частота дискретизации прошивки: #define SAMPLE_RATE 44100. */
const val FW_SAMPLE_RATE = 44100

/** Q, зафиксированный прошивкой для канальных фильтров (ESP32_BiAmp.ino:250-252). */
const val FW_CHANNEL_Q = 0.7071f

/**
 * Нижняя и верхняя границы оси частот графика.
 * Ось начинается с 10 Гц — так в спеке экрана, и 10 Гц нужно видеть слева,
 * чтобы был виден вход полосы из-под нижней границы.
 */
const val GRAPH_F_MIN = 10.0
const val GRAPH_F_MAX = 20000.0

/**
 * Порог включения канального фильтра, Гц.
 *
 * Прошивка в calcChannelFilters (ESP32_BiAmp.ino:250-252) строит секцию только
 * при f >= 20 Гц, иначе bypass; при разборе команды любое ненулевое значение
 * меньше 20 Гц приводится к 20 Гц (ESP32_BiAmp.ino:862). Поэтому 1..19 Гц —
 * это «выключено», и график обязан трактовать их так же, иначе он разойдётся
 * с тем, что реально считает ESP32.
 */
const val MIN_FILTER_HZ = 20

/**
 * Диапазон оси уровня, дБ. Верхняя граница 6 дБ — как в спеке экрана:
 * подписи оси идут 6, 0, -6, -12, -18, -24 с шагом 6 дБ.
 */
const val GRAPH_DB_MIN = -24.0
const val GRAPH_DB_MAX = 6.0

/**
 * Типы секций RBJ в нотации прошивки (calcSectionTo, ESP32_BiAmp.ino:198-224).
 * Для канальных фильтров используются только [SEC_LOWPASS], [SEC_HIGHPASS]
 * и [SEC_BYPASS]; остальные приведены полностью, чтобы порт совпадал с
 * прошивкой и не молча считал неверные коэффициенты.
 */
private const val SEC_LOWPASS = 0
private const val SEC_HIGHPASS = 1
private const val SEC_PEAK_EQ = 2
private const val SEC_LOWSHELF = 3
private const val SEC_HIGHSHELF = 4
private const val SEC_BYPASS = 5

/**
 * Нормированные коэффициенты одной секции RBJ.
 * Формулы повторяют calcSectionTo из прошивки, включая деление на a0.
 */
data class Biquad(
    val b0: Double, val b1: Double, val b2: Double,
    val a1: Double, val a2: Double
) {
    /** Единичный коэффициент: сигнал проходит без изменения. */
    val flat: Boolean get() = b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0

    /**
     * Квадрат модуля передаточной функции на частоте [f] Гц.
     * Возвращает отношение мощностей; для перевода в дБ берётся 10*log10.
     */
    fun powerRatio(f: Double): Double {
        if (flat) return 1.0
        val w = 2.0 * PI * f / FW_SAMPLE_RATE
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2.0 * w)
        val s2w = sin(2.0 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1.0 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        val den = dr * dr + di * di
        if (den < 1e-30) return 1e30
        return (nr * nr + ni * ni) / den
    }

    /** Фаза передаточной функции в градусах на частоте [f] Гц. */
    fun phaseDeg(f: Double): Double {
        if (flat) return 0.0
        val w = 2.0 * PI * f / FW_SAMPLE_RATE
        val numArg = atan2(-(b1 * sin(w) + b2 * sin(2.0 * w)),
            b0 + b1 * cos(w) + b2 * cos(2.0 * w))
        val denArg = atan2(-(a1 * sin(w) + a2 * sin(2.0 * w)),
            1.0 + a1 * cos(w) + a2 * cos(2.0 * w))
        return (numArg - denArg) * 180.0 / PI
    }
}

/**
 * Расчёт секции RBJ, идентичный прошивке.
 * [type]: 0 — ФНЧ, 1 — ФВЧ, 5 — bypass. [q] — добротность, [db] — Gain.
 */
fun rbjSection(type: Int, f: Double, q: Double = FW_CHANNEL_Q.toDouble(), db: Double = 0.0): Biquad {
    if (type == SEC_BYPASS) return Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
    val w0 = 2.0 * PI * f / FW_SAMPLE_RATE
    val cw = cos(w0)
    val sw = sin(w0)
    val alpha = sw / (2.0 * q)
    val b0: Double
    val b1: Double
    val b2: Double
    val a0: Double
    val a1: Double
    val a2: Double
    when (type) {
        SEC_LOWPASS -> {
            b0 = (1 - cw) * 0.5; b1 = 1 - cw; b2 = b0
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
        }
        SEC_HIGHPASS -> {
            b0 = (1 + cw) * 0.5; b1 = -(1 + cw); b2 = b0
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
        }
        SEC_PEAK_EQ -> {
            val A = 10.0.pow(db / 40.0)
            b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A
            a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A
        }
        SEC_LOWSHELF -> {
            val A = 10.0.pow(db / 20.0)
            val sqA = 2.0 * sqrt(A) * alpha
            b0 = A * ((A + 1) - (A - 1) * cw + sqA)
            b1 = 2.0 * A * ((A - 1) - (A + 1) * cw)
            b2 = A * ((A + 1) - (A - 1) * cw - sqA)
            a0 = (A + 1) + (A - 1) * cw + sqA
            a1 = -2.0 * ((A - 1) + (A + 1) * cw)
            a2 = (A + 1) + (A - 1) * cw - sqA
        }
        SEC_HIGHSHELF -> {
            val A = 10.0.pow(db / 20.0)
            val sqA = 2.0 * sqrt(A) * alpha
            b0 = A * ((A + 1) + (A - 1) * cw + sqA)
            b1 = -2.0 * A * ((A - 1) + (A + 1) * cw)
            b2 = A * ((A + 1) + (A - 1) * cw - sqA)
            a0 = (A + 1) - (A - 1) * cw + sqA
            a1 = 2.0 * ((A - 1) - (A + 1) * cw)
            a2 = (A + 1) - (A - 1) * cw - sqA
        }
        else -> throw IllegalArgumentException("Неизвестный тип секции RBJ: $type")
    }
    return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

/**
 * Поканальная АЧХ: ФВЧ [hpHz] и ФНЧ [lpHz] складываются каскадно,
 * как в calcChannelFilters (ESP32_BiAmp.ino:245-254), и умножаются на
 * уровень полосы [levelDb].
 *
 * [levelDb] — то самое плоское усиление, которое прошивка применяет к полосе
 * на выходе (computeTargets, ESP32_BiAmp.ino:343: `v * tlf` для низких каналов
 * и `v * thf` для высоких). Без него кривая стояла бы на 0 дБ при полке,
 * на которой усилитель реально играет на −1…−2 дБ, и график врал бы.
 */
class ChannelResponse(val hp: Biquad, val lp: Biquad, val levelDb: Double = 0.0) {
    /** Суммарный уровень в дБ на частоте [f] Гц. */
    fun db(f: Double): Double = levelDb + 10.0 * log10(
        (hp.powerRatio(f) * lp.powerRatio(f)).coerceAtLeast(1e-30)
    )

    /** Суммарная фаза в градусах на частоте [f] Гц. */
    fun phase(f: Double): Double = hp.phaseDeg(f) + lp.phaseDeg(f)
}

/** Собирает отклик канала из значений chhp/chlp и уровня полосы; 0 или <20 Гц — фильтр выключен. */
fun channelResponse(hpHz: Int, lpHz: Int, levelDb: Double = 0.0): ChannelResponse = ChannelResponse(
    hp = if (hpHz >= MIN_FILTER_HZ) rbjSection(SEC_HIGHPASS, hpHz.toDouble()) else rbjSection(SEC_BYPASS, 0.0),
    lp = if (lpHz >= MIN_FILTER_HZ) rbjSection(SEC_LOWPASS, lpHz.toDouble()) else rbjSection(SEC_BYPASS, 0.0),
    levelDb = levelDb
)

/** Расстояние [f] до [f0] на лог-оси, нормированное в 0..1. */
private fun logPos(f: Double, fMin: Double, fMax: Double): Float =
    ((log10(f.coerceIn(fMin, fMax)) - log10(fMin)) /
        (log10(fMax) - log10(fMin))).toFloat()

/** Частота, соответствующая доле [pos] оси 0..1. */
private fun logFreq(pos: Float, fMin: Double, fMax: Double): Double =
    10.0.pow(log10(fMin) + pos * (log10(fMax) - log10(fMin)))

/** Частоты сетки оси X: 10, 20, 50, 100, 200, 500, 1k, 2k, 5k, 10k, 20k. */
private val GRID_FREQS =
    doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)

/** Подписи уровня по оси Y: 6, 0, -6, -12, -18, -24. */
private val GRID_DB = doubleArrayOf(6.0, 0.0, -6.0, -12.0, -18.0, -24.0)

/** Подпись частоты для сетки: 1k, 2k, 5k… */
private fun freqLabel(f: Double): String = when {
    f >= 1000 -> "${(f / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}k"
    else -> f.toInt().toString()
}

/**
 * График АЧХ с сеткой, кривой и подписями осей.
 * Частота — лог-ось, уровень — дБ, как в Prology.
 */
@Composable
private fun ResponseGraph(resp: ChannelResponse, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val curve = MaterialTheme.colorScheme.primary
    val zeroLine = MaterialTheme.colorScheme.secondary

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun yOf(db: Double): Float =
            (h * ((GRAPH_DB_MAX - db.coerceIn(GRAPH_DB_MIN, GRAPH_DB_MAX)) /
                (GRAPH_DB_MAX - GRAPH_DB_MIN)).toFloat()).coerceIn(0f, h)

        drawDbGrid(grid, zeroLine, w, h, ::yOf)

        // Вертикальная сетка по частотам
        GRID_FREQS.forEach { f ->
            val x = logPos(f, GRAPH_F_MIN, GRAPH_F_MAX) * w
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }

        // Кривая АЧХ
        val path = Path()
        val steps = 400
        for (i in 0..steps) {
            val pos = i.toFloat() / steps
            val f = logFreq(pos, GRAPH_F_MIN, GRAPH_F_MAX)
            val y = yOf(resp.db(f))
            val x = pos * w
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, curve, style = Stroke(width = 3f))
    }
}

/**
 * Горизонтальная сетка: линии уровня по GRID_DB (6, 0, -6, -12, -18, -24)
 * и выделенная нулевая линия.
 *
 * Шаг 6 дБ и Q=0.7071 дают −3 дБ на срезе, поэтому спад 12 дБ/окт читается
 * глазом прямо по сетке. Координаты берутся из массива GRID_DB, а не
 * накапливаются прибавлением: иначе накопленная ошибка double уводила бы
 * подписи с линий сетки на последних делениях.
 */
private fun DrawScope.drawDbGrid(
    grid: Color,
    zero: Color,
    w: Float,
    h: Float,
    yOf: (Double) -> Float
) {
    GRID_DB.forEach { db ->
        val y = yOf(db)
        val isZero = abs(db) < 0.01
        drawLine(
            color = if (isZero) zero else grid,
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = if (isZero) 2f else 1f
        )
    }
}

/**
 * Идентификаторы имён четырёх каналов усилителя в порядке индексов ch_hp/ch_lp
 * (ESP32_BiAmp.ino:101: `Z1: LEFT_HF, LEFT_LF`, `Z2: RIGHT_HF, RIGHT_LF`).
 *
 * Единственный источник правды: список используют задержки и инверсия фазы.
 * Дублировать его опасно — рассинхрон по порядку сразу даст неверный канал
 * в командах chhp/chlp/delay/inv.
 *
 * Хранятся идентификаторы ресурсов, а не готовые строки: иначе подписи
 * каналов остались бы единственным непереводимым текстом в приложении.
 */
val CHANNEL_NAME_RES = listOf(
    R.string.channel_lf,
    R.string.channel_hf_l,
    R.string.channel_lf_r,
    R.string.channel_hf_r
)

/**
 * Полоса акустической системы: пара каналов стерео (левый и правый динамик
 * одной полосы) плюс её название для заголовка.
 *
 * Раскладка каналов в прошивке (ESP32_BiAmp.ino:101, DOCUMENTATION.md 2.3):
 * `Z1: LEFT_HF, LEFT_LF`, `Z2: RIGHT_HF, RIGHT_LF`, а индексы ch_hp/ch_lp
 * в приложении соответствуют порядку НЧ-Л, ВЧ-Л, НЧ-П, ВЧ-П. Поэтому полоса
 * СЧ — это каналы 0 и 2, полоса ВЧ — каналы 1 и 3.
 *
 * [chLeft] и [chRight] хранятся отдельно намеренно: экран показывает одну
 * полосу, а в прошивке значения левого и правого каналов могут различаться.
 * При расхождении степпер пишет в оба, иначе он бы молча затирал разстройку.
 *
 * [levelTrim] и [setLevel] привязывают полосу к её уровню в прошивке:
 * tlf достаётся низким каналам, thf — высоким (computeTargets,
 * ESP32_BiAmp.ino:343, `low = (c & 1) == 0`).
 */
data class Band(
    val titleRes: Int,
    val chLeft: Int,
    val chRight: Int,
    val levelTrim: (DeviceState) -> Float,
    val setLevel: (BiAmpViewModel, Float) -> Unit
) {
    /** Индексы обоих каналов полосы. */
    val channels: List<Int> get() = listOf(chLeft, chRight)
}

/**
 * Полосы системы. СЧ — среднечастотный динамик с ФВЧ и ФНЧ (плюс митбасс,
 * заниженный по басу тем же ФВЧ), ВЧ — высокочастотный динамик.
 * ФНЧ в полосе ВЧ нужен, чтобы приглушить верхний край на 20 кГц.
 */
val BANDS = listOf(
    Band(
        titleRes = R.string.band_mid, chLeft = 0, chRight = 2,
        levelTrim = { it.tlf },
        setLevel = { vm, v -> vm.setTlf(v, true) }
    ),
    Band(
        titleRes = R.string.band_high, chLeft = 1, chRight = 3,
        levelTrim = { it.thf },
        setLevel = { vm, v -> vm.setThf(v, true) }
    )
)

/**
 * Экран «Кроссовер»: график АЧХ полосы и степперы её частот.
 *
 * Ступени и значения подписаны как в спеке (ФВЧ ЧАСТОТА, ФНЧ ЧАСТОТА, УРОВЕНЬ,
 * ФАЗА). СПАД не выводится: прошивка держит Q=0.7071 для канальных фильтров
 * (ESP32_BiAmp.ino:250-252) и не имеет команд крутизны, поэтому такой степпер
 * был бы витриной, а не настройкой.
 */
/**
 * Локальное состояние степпера поверх значения, пришедшего с устройства.
 *
 * Устройство опрашивается раз в 3 с (POLL_INTERVAL_MS), а ответ на `tlf`/`thf`
 * приходит ещё позже. Если считать шаг от значения с устройства, то пять
 * быстрых нажатий «вниз» применяют одно и то же смещение пять раз и дают
 * один шаг вместо пяти. Поэтому позиция живёт здесь, а значение с устройства
 * лишь подтверждает её.
 *
 * [sent] — последнее отправленное значение. Пока ответ не пришёл, отклик
 * устройства игнорируется: иначе опрос вернул бы промежуточное значение и
 * откатил позицию, до которой пользователь уже дошёл. Значение извне
 * (пресет, ручная правка на устройстве) применяется, потому что тогда
 * [sent] пуст.
 */
private class Steppable<T>(remote: T) {
    var pos: T by mutableStateOf(remote)
        private set

    private var sent: T? by mutableStateOf(null)

    /** Значение с устройства: подтверждение нашей правки или внешнее изменение. */
    fun accept(remote: T) {
        if (sent == remote) sent = null
        else if (sent == null) pos = remote
    }

    /**
     * Переход на другую полосу: показываем её значения.
     *
     * [sent] здесь сбрасывается обязательно. Без сброса переключение полосы
     * было бы игнорировано: у полосы ВЧ [sent] ещё держит 3 от нажатий в полосе
     * СЧ, и [accept] решил бы, что 0 — это устаревший ответ, и оставил бы
     * показывать чужие 3 дБ.
     */
    fun switchTo(remote: T) {
        sent = null
        pos = remote
    }

    /** Пользователь нажал кнопку: двигаем позицию и запоминаем, что ушло. */
    fun set(v: T) {
        pos = v
        sent = v
    }
}

@Composable
fun FilterGraph(vm: BiAmpViewModel, ds: DeviceState, enabled: Boolean) {
    var bandIndex by remember { mutableIntStateOf(0) }
    val band = BANDS[bandIndex]

    val hp = ds.chFilters.getOrElse(band.chLeft) { 0 to 0 }.first
    val lp = ds.chFilters.getOrElse(band.chLeft) { 0 to 0 }.second
    val level = band.levelTrim(ds)

    // Степперы живут своей памятью, но при смене полосы показывают её значения.
    val hpStep = remember { Steppable(hp) }
    val lpStep = remember { Steppable(lp) }
    val levelStep = remember { Steppable(level) }
    LaunchedEffect(bandIndex) {
        hpStep.switchTo(hp)
        lpStep.switchTo(lp)
        levelStep.switchTo(level)
    }
    LaunchedEffect(hp) { hpStep.accept(hp) }
    LaunchedEffect(lp) { lpStep.accept(lp) }
    LaunchedEffect(level) { levelStep.accept(level) }

    val resp = remember(hpStep.pos, lpStep.pos, levelStep.pos) {
        channelResponse(hpStep.pos, lpStep.pos, levelStep.pos.toDouble())
    }

    // Расхождение левого и правого каналов полосы: сообщаем, что степпер
    // пишет в оба, иначе пользователь решит, что разстройки нет.
    val hpRight = ds.chFilters.getOrElse(band.chRight) { 0 to 0 }.first
    val lpRight = ds.chFilters.getOrElse(band.chRight) { 0 to 0 }.second
    val mismatch = hp != hpRight || lp != lpRight

    Column(modifier = Modifier.fillMaxWidth()) {
        val bandTitle = stringResource(band.titleRes)
        ResponseGraph(
            resp = resp,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semanticsMerge(
                    stringResource(
                        R.string.graph_cd, bandTitle,
                        hpFreqText(hpStep.pos), hpFreqText(lpStep.pos),
                        dbText(levelStep.pos)
                    ),
                    null
                )
        )

        AxisLabels()

        Spacer(Modifier.height(12.dp))

        Text(
            bandTitle,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        // Переключение полосы
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BANDS.forEachIndexed { i, b ->
                FilterChip(
                    selected = bandIndex == i,
                    onClick = { bandIndex = i },
                    label = { Text(stringResource(b.titleRes)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        StepperRow(stringResource(R.string.stepper_hp), hpStep.pos, band, enabled) { hz ->
            hpStep.set(hz)
            band.channels.forEach { vm.setChHp(it, hz) }
        }
        StepperRow(stringResource(R.string.stepper_lp), lpStep.pos, band, enabled) { hz ->
            lpStep.set(hz)
            band.channels.forEach { vm.setChLp(it, hz) }
        }
        StepperRow(
            label = stringResource(R.string.stepper_level),
            valueText = dbText(levelStep.pos),
            enabled = enabled,
            onPrev = {
                val v = levelSteps(levelStep.pos - 1f)
                levelStep.set(v)
                band.setLevel(vm, v)
            },
            onNext = {
                val v = levelSteps(levelStep.pos + 1f)
                levelStep.set(v)
                band.setLevel(vm, v)
            },
            onReset = {
                levelStep.set(0f)
                band.setLevel(vm, 0f)
            }
        )

        if (mismatch) {
            Text(
                stringResource(R.string.mismatch_notice, hp, lp, hpRight, lpRight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Фазы у полосы больше нет: инверсию знака убрали из прошивки в v35.
        // Настройку сдвига сторон делает перестановка Л/П в блоке DSP —
        // она общая для полосы, а не отдельная настройка каждой.

        Text(
            stringResource(R.string.slope_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.scope_note, bandTitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Границы уровня полосы: sanitizeParams в прошивке (ESP32_BiAmp.ino:729-730)
 * отбрасывает tlf/thf вне −6…+3 дБ, поэтому за пределы выходить бессмысленно.
 */
const val LEVEL_DB_MIN = -6f
const val LEVEL_DB_MAX = 3f

/**
 * Приводит [db] к ступени 1 дБ в диапазоне −6…+3.
 *
 * Ступень ровно 1 дБ, а не 0.5: разрядность ползунка с шагом 0.5 дБ на −6…+3
 * даёт 19 щелчков, у соседних значений −5.5 и −6 подпись в спеке не различима,
 * а счётчик «дБ на щелчок» для акустической настройки полезнее ровного шага.
 */
internal fun levelSteps(db: Float): Float =
    Math.round(db).toFloat().coerceIn(LEVEL_DB_MIN, LEVEL_DB_MAX)

/** Подпись уровня как в спеке: «0», «-2»; без дробной части. */
internal fun dbText(db: Float): String =
    if (abs(db - Math.round(db)) < 0.01f) Math.round(db).toInt().toString()
    else String.format(Locale.US, "%.1f", db)

/** Частота словами для accessibility: «выключен» вместо значения. */
@Composable
private fun hpFreqText(hz: Int): String =
    if (hz >= MIN_FILTER_HZ) stringResource(R.string.filter_hz, hz)
    else stringResource(R.string.filter_disabled)

/**
 * Ступени частоты фильтра: 1/3 октавы от 20 Гц до 20 кГц, плюс «выкл».
 * Шаг по кнопке — одна ступень; частота округляется целыми, как принимает
 * прошивка (chhp/chlp парсят float, но UI в Гц целых).
 */
internal val FILTER_STEPS = intArrayOf(
    20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
    1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
)

/** Ближайшая ступень к [hz]; 0 («выключено») остаётся нулём. */
internal fun nearestStep(hz: Int): Int {
    if (hz < MIN_FILTER_HZ) return 0
    return FILTER_STEPS.minByOrNull { kotlin.math.abs(it - hz) } ?: MIN_FILTER_HZ
}

/**
 * Формат частоты как в спеке: 80Гц, 4kГц, 5kГц.
 *
 * Ступени 1/3 октавы не круглые в kHz: 1250 → 1.25k, 3150 → 3.15k. Печатаем
 * до двух знаков и убираем хвостовые нули, иначе значение на экране не равно
 * тому, что уйдёт в прошивку, а соседние ступени (1600 и 2000) склеились бы
 * в одинаковую подпись при округлении до одного знака.
 *
 * Единицы измерения передаются параметрами, а не берутся из ресурсов:
 * функция остаётся чистой и проверяется юнит-тестами, а локализованные
 * подписи подставляет композабла.
 */
internal fun freqValueText(hz: Int, off: String, hzUnit: String, kHzUnit: String): String = when {
    hz < MIN_FILTER_HZ -> off
    hz >= 1000 -> {
        val s = String.format(Locale.US, "%.2f", hz / 1000.0)
            .trimEnd('0')
            .trimEnd('.')
        s + kHzUnit
    }
    else -> hz.toString() + hzUnit
}

/**
 * Строка параметра в стиле спеки: метка слева, кнопки «<» и «>» по краям
 * серого поля значения. Кнопки шагают по ступеням [FILTER_STEPS];
 * [onReset] вместо шага включается для нечисловых параметров (фаза).
 */
@Composable
private fun StepperRow(
    label: String,
    value: Int = 0,
    band: Band,
    enabled: Boolean,
    onStep: (Int) -> Unit
) = StepperRow(
    label = label,
    valueText = freqValueText(
        value,
        stringResource(R.string.filter_off),
        stringResource(R.string.suffix_hz).trimStart(),
        stringResource(R.string.filter_khz)
    ),
    enabled = enabled,
    onPrev = {
        val cur = nearestStep(value)
        val i = FILTER_STEPS.indexOf(cur)
        val next = if (i <= 0) 0 else FILTER_STEPS[i - 1]
        onStep(next)
    },
    onNext = {
        val cur = nearestStep(value)
        if (cur == 0) onStep(FILTER_STEPS[0]) else {
            val i = FILTER_STEPS.indexOf(cur)
            onStep(FILTER_STEPS[(i + 1).coerceAtMost(FILTER_STEPS.size - 1)])
        }
    },
    onReset = { onStep(0) }
)

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    enabled: Boolean,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (onPrev != null) {
            TextButton(
                onClick = onPrev,
                enabled = enabled,
                modifier = Modifier.semanticsMerge(stringResource(R.string.stepper_dec, label), null)
            ) { Text("<", style = MaterialTheme.typography.titleLarge) }
        }
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier
                .width(96.dp)
                .semanticsMerge(label, valueText)
        ) {
            Text(
                valueText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (onNext != null) {
            TextButton(
                onClick = onNext,
                enabled = enabled,
                modifier = Modifier.semanticsMerge(stringResource(R.string.stepper_inc, label), null)
            ) { Text(">", style = MaterialTheme.typography.titleLarge) }
        } else {
            // Для параметра без шага (фаза) правая кнопка меняет значение.
            TextButton(
                onClick = onReset,
                enabled = enabled,
                modifier = Modifier.semanticsMerge(stringResource(R.string.stepper_toggle, label), null)
            ) { Text("⇄", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/** Подписи осей: уровень слева, частота снизу — обе по фактическим линиям сетки. */
@Composable
private fun AxisLabels() {
    Row(modifier = Modifier.fillMaxWidth().height(18.dp)) {
        GRID_DB.forEach { db ->
            Text(
                db.toInt().toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        GRID_FREQS.forEach { f ->
            Text(
                freqLabel(f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}