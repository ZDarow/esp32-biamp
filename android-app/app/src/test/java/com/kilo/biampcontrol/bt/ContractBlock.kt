/*
 * Загрузчик контракта: читает блок `status` из
 * firmware/protocol/status-contract.md, раздел 2.
 *
 * Зачем файл отдельно от теста. Блок контракта раньше хранился цитатой в
 * самом StatusContractTest. Цитата была дословной, но проверка этого не
 * утверждала: правка контракта оставляла тест зелёным, и Android-парсер
 * разъезжался с прошивкой молча — ровно тот класс дефекта, ради которого
 * контрактный тест и написан. Клиент для ПК читает файл напрямую; теперь так
 * же делает Android.
 *
 * Файл принадлежит направлению прошивки, поэтому на ветке android-dev до
 * слияния с main его может не быть. Тогда тесты пропускаются (см.
 * [contractBlockOrSkip]), а не падают на чужом отсутствующем файле.
 */

package com.kilo.biampcontrol.bt

import org.junit.Assume.assumeTrue
import java.io.File

/** Путь контракта относительно корня репозитория. */
private const val CONTRACT_RELATIVE = "firmware/protocol/status-contract.md"

/** Заголовок раздела 2 — «## 2.». */
private const val SECTION_2 = "## 2."

/**
 * Блок `status` в тройных кавычках внутри раздела 2.
 *
 * Именно ``` , а не отступы: контракт — markdown, и блок статуса единственный
 * огороженный fenced-блок в разделе 2.
 */
private val BLOCK_RE = Regex("""##\s*2\.[^\n]*\n+```\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)

/**
 * Ищет файл контракта, поднимаясь от рабочего каталога вверх.
 *
 * Путь вверх, а не жёсткий `../../`: Gradle запускает тест из каталога
 * модуля, но при смене компоновки или запуске из IDE относительный путь
 * поедет молча, и тест превратится в «файла нет» вместо «контракт другой».
 * Поиск вверх переживает любую вложенность, пока корень репозитория
 * достижим.
 */
fun findContractFile(): File? {
    var dir: File? = File(".").absoluteFile.normalize()
    while (dir != null) {
        val probe = File(dir, CONTRACT_RELATIVE)
        if (probe.isFile) return probe
        dir = dir.parentFile
    }
    return null
}

/**
 * Блок `status` ДОСЛОВНО из контракта, либо пропуск теста.
 *
 * Возвращать пустой список нельзя: тест «нет нераспознанных строк» на пустом
 * блоке прошёл бы, а тест «13 строк» упал бы — то есть поломался бы не тот
 * тест, который указывает на причину. Поэтому отсутствие контракта
 * сообщается пропуском.
 */
fun contractBlockOrSkip(): List<String> {
    val file = findContractFile()
    assumeTrue(
        "нет файла контракта $CONTRACT_RELATIVE — он в ветке направления firmware",
        file != null,
    )
    val text = file!!.readText(Charsets.UTF_8)
    assertTrue("в контракте нет раздела 2", text.contains(SECTION_2))
    val match = BLOCK_RE.find(text)
    assertTrue("в разделе 2 контракта нет блока status в тройных кавычках", match != null)
    return match!!.groupValues[1]
        .lines()
        .filter { it.isNotBlank() }
        .map { it.trim() }
}

private fun assertTrue(message: String, condition: Boolean) {
    if (!condition) throw AssertionError(message)
}
