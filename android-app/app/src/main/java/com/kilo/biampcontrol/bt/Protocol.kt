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

package com.kilo.biampcontrol.bt

/**
 * Диапазоны значений протокола — раздел 4 status-contract.md.
 *
 * Держим их в одном месте, потому что используют их три независимых места:
 * санитайзер [StatusParser], подписи слайдеров и проверка команд перед
 * отправкой. Расхождение между ними и есть тот самый «250 % в подписи при
 * ползунке до 100 %», который чинится одним списком.
 */
object Limits {
    const val VOL_MIN = 0f
    const val VOL_MAX = 100f
    const val BAL_MIN = -10f
    const val BAL_MAX = 10f
    const val FC_MIN = 200f
    const val FC_MAX = 1000f
    const val HP_MIN = 20f
    const val HP_MAX = 80f
    const val TRIM_MIN = -6f
    const val TRIM_MAX = 3f
    const val EQ_MIN = -12f
    const val EQ_MAX = 12f
    const val DELAY_MIN = 0
    const val DELAY_MAX = 220
    const val CH_FILTER_MIN = 20
    const val CH_FILTER_MAX = 20000
    const val TEST_VOL_MIN = 0
    const val TEST_VOL_MAX = 6
    const val TEST_MODE_MIN = 0
    const val TEST_MODE_MAX = 4
    const val XO_TYPE_MIN = 1
    const val XO_TYPE_MAX = 2

    const val FC_DEFAULT = 400f
    const val HP_DEFAULT = 45f
    const val CMD_MAX_CHARS = 62
    val CMD_ALLOWED = Regex("[A-Za-z0-9:_.-]")
}

object StatusTokens {
    const val ON = "ON"
    const val OFF = "OFF"
    const val ONE = "1"
    const val ZERO = "0"
    const val LR4 = "LR4"
    const val BUTTER = "Butter"
}

/** Состояние устройства, распарсенное из ответа `status`. */
data class DeviceState(
    val vol0: Int = 10,
    val vol1: Int = 10,
    val bal: Float = 0f,
    val fc: Float = 400f,
    val hp: Float = 45f,
    val subOn: Boolean = true,
    val tlf: Float = 0f,
    val thf: Float = -1f,
    val eql: Float = 0f,
    val eqm: Float = 0f,
    val eqh: Float = 0f,
// Прошивка v35.1 вместо инверсии фазы присылает перестановку Л/П и
    // дублирование выходов. Старые прошивки присылали "INV: 0100", этой
    // строки в контракте больше нет вовсе.
    val swapped: Boolean = false,                // "SWP:" — перестановка Л/П
    val dup: Boolean = false,                    // "DUP:" — каналы 2,3 не выводятся
    val muted: List<Boolean> = listOf(false, false), // "Mute: z0/z1" — зоны 0 и 1
    val btAudioOn: Boolean = false,
    val sppOn: Boolean = false,
    val testMode: Int = 0,
    val testVol: Int = 6,                       // громкость тест-сигнала, %
    val xoType: Int = 1,                       // 1 = Butterworth, 2 = LR4
    val xoOn: Boolean = true,                  // выключатель кроссовера (секции 1..4)
    val srcKhz: String = "44.1",                // частота источника из строки "Src:"
    val delays: List<Int> = listOf(0, 0, 0, 0), // сэмплы для delay0..delay3
    val chFilters: List<Pair<Int, Int>> = listOf(0 to 0, 0 to 0, 0 to 0, 0 to 0) // (hp, lp) для ch0..ch3
)

/**
 * Санитайзер команды перед отправкой в SPP.
 *
 * Прошивка исполняет присланную строку как команду, поэтому символ перевода
 * строки внутри неё отправлял бы сразу несколько команд. Здесь строка
 * приводится к алфавиту `[A-Za-z0-9:_.-]` и обрезается до [Limits.CMD_MAX_CHARS].
 */
object CommandSanitizer {
    /** Чистая команда для отправки либо `null`, если после отсечения нечего слать. */
    fun sanitize(raw: String): String? =
        raw.trim()
            .filter { Limits.CMD_ALLOWED.matches(it.toString()) }
            .take(Limits.CMD_MAX_CHARS)
            .ifEmpty { null }
}

/** Результат разбора: состояние плюс строки, которые парсер не узнал. */
data class ParseResult(
    val state: DeviceState?,
    /** Строки блока, не совпавшие ни с одним шаблоном `status`. */
    val unrecognized: List<String>
) {
    /** Сколько строк потеряно: контракт требует, чтобы это число было нулём. */
    val unrecognizedCount: Int get() = unrecognized.size
}

object StatusParser {
    private val reVol  = Regex("V0=(\\d+)%\\s+V1=(\\d+)%\\s+bal=(-?[\\d.]+)")
    private val reFc   = Regex("Fc=([\\d.]+)Hz\\s+hp=([\\d.]+)Hz\\s+sub=(${StatusTokens.ON}|${StatusTokens.OFF})")
    private val reTrim = Regex("TLF=(-?[\\d.]+)dB\\s+THF=(-?[\\d.]+)dB")
    private val reEq   = Regex("EQ:\\s*L=(-?[\\d.]+)\\s+M=(-?[\\d.]+)\\s+H=(-?[\\d.]+)")
    private val reMute = Regex("Mute:\\s*(${StatusTokens.ONE}|${StatusTokens.ZERO})/(${StatusTokens.ONE}|${StatusTokens.ZERO})")
    private val reSwp  = Regex("SWP:\\s*(${StatusTokens.ONE}|${StatusTokens.ZERO})")
    private val reDup  = Regex("DUP:\\s*(${StatusTokens.ONE}|${StatusTokens.ZERO})")
    private val reBt   = Regex("BT:\\s+(${StatusTokens.ON}|${StatusTokens.OFF})(?:\\s*\\|\\s*SPP:\\s+(${StatusTokens.ON}|${StatusTokens.OFF}))?")
    private val reTest = Regex("Test:\\s+(\\d+)(?:\\s+TVol=(\\d+)%)?")
    private val reSrc  = Regex("Src:\\s+([\\d.]+)\\s*kHz")
    private val reXo   = Regex("XO:\\s+(${StatusTokens.BUTTER}|${StatusTokens.LR4})(?:\\s+(${StatusTokens.ON}|${StatusTokens.OFF}))?")
    private val reChf  = Regex("CHF:\\s+(\\d+)/(\\d+)\\s+(\\d+)/(\\d+)\\s+(\\d+)/(\\d+)\\s+(\\d+)/(\\d+)")
    private val reDly  = Regex("Delay:\\s+(\\d+)/(\\d+)/(\\d+)/(\\d+)")

    /**
     * Шаблоны строк блока `status` — по ним же строка причисляется к блоку.
     *
     * Список задан явно, а не выводится из `object`: он одновременно служит
     * фильтром «это строка статуса, а не вывод `help`/`stats`/`heap`» и
     * источником строк для контрактного теста. Добавление новой строки в
     * контракт обязано добавлять её и сюда, иначе тест упадёт.
     */
    private val allLinePatterns = listOf(
        reVol, reFc, reTrim, reEq, reMute, reSwp, reDup, reBt, reTest, reSrc, reXo, reChf, reDly
    )

    /**
     * Признак последней строки блока `status`. Прошивка выдаёт блок через `say()`,
     * и строка `Delay:` замыкает его. По ней вызывающий код понимает, что блок
     * принят целиком, и может очистить буфер строк.
     */
    fun isBlockEnd(line: String): Boolean = reDly.containsMatchIn(line)

    /**
     * Признак первой строки блока `status`.
     *
     * Блок всегда начинается со строки громкости, и по ней буфер строк
     * очищается перед разбором. Раньше очистка шла по отправке команды
     * `status`, и periodic-опрос, попавший в середину уже идущего блока,
     * обрезал его: в буфере оставался хвост без начала, состояние собиралось
     * из половины полей, а часть значений оставалась прежней. Очистка по
     * факту прихода новой первой строки от такого обрыва не зависит.
     */
    fun isBlockStart(line: String): Boolean = reVol.containsMatchIn(line)

    /**
     * Кладёт строку в буфер блока, обнуляя его на начале нового блока.
     *
     * Политика буфера живёт здесь, а не в ViewModel, чтобы её можно было
     * проверить обычным тестом: ViewModel только накапливает строки и по
     * `Delay:` применяет разобранное состояние.
     *
     * @param maxLines сколько строк держать; лишние отбрасываются с начала.
     * @return `true`, если строка открыла новый блок, то есть буфер очищен.
     */
    fun appendToBlock(buffer: MutableList<String>, line: String, maxLines: Int): Boolean {
        if (!isStatusLine(line)) return false
        val started = isBlockStart(line)
        if (started) buffer.clear()
        buffer.add(line)
        while (buffer.size > maxLines) buffer.removeAt(0)
        return started
    }

    /** Строка принадлежит блоку `status` (любой из 13 строк контракта). */
    fun isStatusLine(line: String): Boolean = allLinePatterns.any { it.containsMatchIn(line) }

    /** Терпимый парсер: собирает состояние из любых подходящих строк буфера. */
    fun parse(lines: List<String>): DeviceState? = parse(lines, DeviceState())

    /**
     * Парсер, который достраивает результат поверх уже известного состояния.
     *
     * Блок `status` приходит построчно, поэтому между первой и последней
     * строкой в буфере лежит лишь часть полей. Если брать значения из
     * конструктора по умолчанию, каждое промежуточное обновление обнуляло бы
     * ещё не пришедшие поля: ползунки дёргались бы к дефолту и обратно,
     * а режимы кроссовера и сабсоника мигали. За основу поэтому берётся
     * [base] — текущее состояние UI, — и меняются только реально пришедшие строки.
     *
     * @return `null`, если в [lines] нет ни одной распознаваемой строки.
     */
    fun parse(lines: List<String>, base: DeviceState): DeviceState? =
        parseDetailed(lines, base).state

    /**
     * Разбор с отчётом: состояние и список строк, которые никто не узнал.
     *
     * Значение, которое не удалось разобрать числом, НЕ заменяется дефолтом:
     * остаётся предыдущее из [base] (раздел 6 контракта). Иначе потерянная
     * строка `V0=` тихо сбрасывала бы громкость в 10 % — и ползунок прыгал бы
     * назад на каждом потерянном пакете.
     */
    fun parseDetailed(lines: List<String>, base: DeviceState): ParseResult {
        var s = base
        var found = false
        val unknown = mutableListOf<String>()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            var recognized = false

            reVol.find(l)?.let { m ->
                s = s.copy(
                    vol0 = m.groupValues[1].toIntOrNull() ?: s.vol0,
                    vol1 = m.groupValues[2].toIntOrNull() ?: s.vol1,
                    bal = m.groupValues[3].toFloatOrNull() ?: s.bal
                ); found = true; recognized = true
            }
            reFc.find(l)?.let { m ->
                s = s.copy(
                    fc = m.groupValues[1].toFloatOrNull() ?: s.fc,
                    hp = m.groupValues[2].toFloatOrNull() ?: s.hp,
                    subOn = m.groupValues[3] == StatusTokens.ON
                ); found = true; recognized = true
            }
            reTrim.find(l)?.let { m ->
                s = s.copy(
                    tlf = m.groupValues[1].toFloatOrNull() ?: s.tlf,
                    thf = m.groupValues[2].toFloatOrNull() ?: s.thf
                ); found = true; recognized = true
            }
            reEq.find(l)?.let { m ->
                s = s.copy(
                    eql = m.groupValues[1].toFloatOrNull() ?: s.eql,
                    eqm = m.groupValues[2].toFloatOrNull() ?: s.eqm,
                    eqh = m.groupValues[3].toFloatOrNull() ?: s.eqh
                ); found = true; recognized = true
            }
 reMute.find(l)?.let { m ->
                s = s.copy(muted = listOf(m.groupValues[1] == StatusTokens.ONE, m.groupValues[2] == StatusTokens.ONE))
                found = true; recognized = true
            }
            reSwp.find(l)?.let { m ->
                s = s.copy(swapped = m.groupValues[1] == StatusTokens.ONE); found = true; recognized = true
            }
            reDup.find(l)?.let { m ->
                s = s.copy(dup = m.groupValues[1] == StatusTokens.ONE); found = true; recognized = true
            }
            reBt.find(l)?.let { m ->
                // У прошивки без поддержки `status` по SPP в строке есть только
                // "BT: ON". Отсутствие группы — это «не сообщено», а не
                // «SPP выключен»: иначе старый ответ гасил бы индикатор.
                val spp = m.groupValues.getOrNull(2)
                s = s.copy(
                    btAudioOn = m.groupValues[1] == StatusTokens.ON,
                    sppOn = if (spp.isNullOrEmpty()) s.sppOn else spp == StatusTokens.ON
                ); found = true; recognized = true
            }
            reTest.find(l)?.let { m ->
                s = s.copy(
                    testMode = m.groupValues[1].toIntOrNull() ?: s.testMode,
                    testVol = m.groupValues.getOrNull(2)?.toIntOrNull() ?: s.testVol
                ); found = true; recognized = true
            }
            reSrc.find(l)?.let { m ->
                s = s.copy(srcKhz = m.groupValues[1]); found = true; recognized = true
            }
            reXo.find(l)?.let { m ->
                s = s.copy(
                    xoType = if (m.groupValues[1] == StatusTokens.LR4) 2 else 1,
                    // Старая прошивка без хвоста " ON"/" OFF" ничего не сообщает
                    // о выключателе: там кроссовер всегда включён, поэтому
                    // отсутствие группы означает ON, а не потерю связи.
                    xoOn = m.groupValues.getOrNull(2) != StatusTokens.OFF
                ); found = true; recognized = true
            }
            reChf.find(l)?.let { m ->
                val pairs = listOf(
                    (m.groupValues[1].toIntOrNull() ?: s.chFilters[0].first) to
                        (m.groupValues[2].toIntOrNull() ?: s.chFilters[0].second),
                    (m.groupValues[3].toIntOrNull() ?: s.chFilters[1].first) to
                        (m.groupValues[4].toIntOrNull() ?: s.chFilters[1].second),
                    (m.groupValues[5].toIntOrNull() ?: s.chFilters[2].first) to
                        (m.groupValues[6].toIntOrNull() ?: s.chFilters[2].second),
                    (m.groupValues[7].toIntOrNull() ?: s.chFilters[3].first) to
                        (m.groupValues[8].toIntOrNull() ?: s.chFilters[3].second)
                )
                s = s.copy(chFilters = pairs); found = true; recognized = true
            }
            reDly.find(l)?.let { m ->
                val prev = s.delays
                val d = listOf(
                    m.groupValues[1].toIntOrNull() ?: prev[0],
                    m.groupValues[2].toIntOrNull() ?: prev[1],
                    m.groupValues[3].toIntOrNull() ?: prev[2],
                    m.groupValues[4].toIntOrNull() ?: prev[3]
                )
                s = s.copy(delays = d); found = true; recognized = true
            }
            if (!recognized) unknown += l
        }
        return ParseResult(if (found) s.sanitized() else null, unknown)
    }

    /**
     * Приведение состояния к диапазонам контракта (раздел 4).
     *
     * Устройство может сообщить значение вне диапазона: прошивка печатает
     * фактическое, а не ограниченное, и после ручной правки через UART или
     * после смены формата в блоке подпись слайдера показывала бы «250 %» при
     * ползунке, который физически не может уйти выше 100.
     */
    internal fun DeviceState.sanitized(): DeviceState = copy(
        vol0 = vol0.coerceIn(Limits.VOL_MIN.toInt(), Limits.VOL_MAX.toInt()),
        vol1 = vol1.coerceIn(Limits.VOL_MIN.toInt(), Limits.VOL_MAX.toInt()),
        bal = bal.coerceIn(Limits.BAL_MIN, Limits.BAL_MAX),
        fc = fc.coerceIn(Limits.FC_MIN, Limits.FC_MAX),
        hp = hp.coerceIn(Limits.HP_MIN, Limits.HP_MAX),
        tlf = tlf.coerceIn(Limits.TRIM_MIN, Limits.TRIM_MAX),
        thf = thf.coerceIn(Limits.TRIM_MIN, Limits.TRIM_MAX),
        eql = eql.coerceIn(Limits.EQ_MIN, Limits.EQ_MAX),
        eqm = eqm.coerceIn(Limits.EQ_MIN, Limits.EQ_MAX),
        eqh = eqh.coerceIn(Limits.EQ_MIN, Limits.EQ_MAX),
        testMode = testMode.coerceIn(Limits.TEST_MODE_MIN, Limits.TEST_MODE_MAX),
        testVol = testVol.coerceIn(Limits.TEST_VOL_MIN, Limits.TEST_VOL_MAX),
        xoType = xoType.coerceIn(Limits.XO_TYPE_MIN, Limits.XO_TYPE_MAX),
        delays = delays.map {
            it.coerceIn(Limits.DELAY_MIN, Limits.DELAY_MAX)
        },
        chFilters = chFilters.map { (hp, lp) -> sanitizeChFreq(hp) to sanitizeChFreq(lp) }
    )

    /**
     * Частота канального фильтра: 0 — «выключено» и остаётся нулём, всё
     * остальное зажимается в 20..20000 Гц. Прошивка включает секцию только
     * от 20 Гц, поэтому значение 1..19 не имеет физического смысла.
     */
    private fun sanitizeChFreq(hz: Int): Int = when {
        hz <= 0 -> 0
        else -> hz.coerceIn(Limits.CH_FILTER_MIN, Limits.CH_FILTER_MAX)
    }
}
