package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.ChapterNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Golden test: fissa l'output di numeri capitolo, etichette, nomi file e id stabili prodotti
 * dalla versione con java.math.BigDecimal. Questi valori finiscono su disco e nelle preferenze
 * (nomi .cbz, chiavi letto/scaricato): qualsiasi differenza rompe i dati degli utenti esistenti.
 */
class ChapterNumberGoldenTest {

    private data class Row(
        val raw: String,
        val parsed: String?,
        val label: String,
        val display: String?,
        val file: String?,
        val id: String?,
    )

    private val rows = listOf(
        Row("1", parsed = "1", label = "1", display = "1", file = "chapter_001.cbz", id = "number:1"),
        Row("01", parsed = "1", label = "1", display = "1", file = "chapter_001.cbz", id = "number:1"),
        Row("010", parsed = "10", label = "10", display = "10", file = "chapter_010.cbz", id = "number:10"),
        Row("10.50", parsed = "10.50", label = "10.5", display = "10.5", file = "chapter_10.5.cbz", id = "number:10.5"),
        Row("10.5", parsed = "10.5", label = "10.5", display = "10.5", file = "chapter_10.5.cbz", id = "number:10.5"),
        Row("1.0", parsed = "1.0", label = "1", display = "1", file = "chapter_001.cbz", id = "number:1"),
        Row("0", parsed = "0", label = "0", display = "0", file = "chapter_000.cbz", id = "number:0"),
        Row("0.0", parsed = "0.0", label = "0", display = "0", file = "chapter_000.cbz", id = "number:0"),
        Row("00", parsed = "0", label = "0", display = "0", file = "chapter_000.cbz", id = "number:0"),
        Row("100", parsed = "100", label = "100", display = "100", file = "chapter_100.cbz", id = "number:100"),
        Row("1000", parsed = "1000", label = "1000", display = "1000", file = "chapter_1000.cbz", id = "number:1000"),
        Row("12.345", parsed = "12.345", label = "12.345", display = "12.345", file = "chapter_12.345.cbz", id = "number:12.345"),
        Row("007.10", parsed = "7.10", label = "7.1", display = "7.1", file = "chapter_7.1.cbz", id = "number:7.1"),
        Row("Extra", parsed = null, label = "Extra", display = null, file = null, id = null),
        Row("", parsed = null, label = "", display = null, file = null, id = null),
        Row(" 3 ", parsed = "3", label = "3", display = "3", file = "chapter_003.cbz", id = "number:3"),
        Row("1e3", parsed = "1", label = "1000", display = "1", file = "chapter_1000.cbz", id = "number:1"),
        Row("1E-2", parsed = "1", label = "0.01", display = "1", file = "chapter_0.01.cbz", id = "number:1"),
        Row("2.", parsed = "2", label = "2", display = "2", file = "chapter_002.cbz", id = "number:2"),
        Row(".5", parsed = "5", label = "0.5", display = "5", file = "chapter_0.5.cbz", id = "number:5"),
        Row("-1", parsed = "1", label = "-1", display = "1", file = "chapter_-1.cbz", id = "number:1"),
        Row("+2", parsed = "2", label = "2", display = "2", file = "chapter_002.cbz", id = "number:2"),
        Row("-0", parsed = "0", label = "0", display = "0", file = "chapter_000.cbz", id = "number:0"),
        Row("Chapter 5.5", parsed = "5.5", label = "Chapter 5.5", display = "5.5", file = "chapter_Chapter_5.5.cbz", id = "number:5.5"),
        Row("5-6", parsed = "5", label = "5-6", display = "5", file = "chapter_5-6.cbz", id = "number:5"),
        Row("99999999999999999999.5", parsed = "99999999999999999999.5", label = "99999999999999999999.5", display = "99999999999999999999.5", file = "chapter_99999999999999999999.5.cbz", id = "number:99999999999999999999.5"),
        Row("3.14159265358979323846", parsed = "3.14159265358979323846", label = "3.14159265358979323846", display = "3.14159265358979323846", file = "chapter_3.14159265358979323846.cbz", id = "number:3.14159265358979323846"),
        Row("0.000", parsed = "0.000", label = "0", display = "0", file = "chapter_000.cbz", id = "number:0"),
        Row("120.0", parsed = "120.0", label = "120", display = "120", file = "chapter_120.cbz", id = "number:120"),
        Row("1,5", parsed = "1", label = "1,5", display = "1", file = "chapter_1_5.cbz", id = "number:1"),
        Row("NaN", parsed = null, label = "NaN", display = null, file = null, id = null),
        Row("Infinity", parsed = null, label = "Infinity", display = null, file = null, id = null),
        Row("0x10", parsed = "0", label = "0x10", display = "0", file = "chapter_0x10.cbz", id = "number:0"),
        Row("1.5f", parsed = "1.5", label = "1.5f", display = "1.5", file = "chapter_1.5f.cbz", id = "number:1.5"),
        Row("1.5d", parsed = "1.5", label = "1.5d", display = "1.5", file = "chapter_1.5d.cbz", id = "number:1.5"),
        Row("12a", parsed = "12", label = "12a", display = "12", file = "chapter_12a.cbz", id = "number:12"),
    )

    @Test
    fun parsingLabelsFileNamesAndIdsMatchTheBigDecimalVersion() {
        for (row in rows) {
            val parsed = DownloadStorage.parseChapterValueOrNull(row.raw)
            assertEquals(row.parsed, parsed?.toPlainString(), "parsed of '${row.raw}'")
            assertEquals(row.label, DownloadStorage.normalizedChapterLabel(row.raw), "label of '${row.raw}'")
            val chapter = parsed?.let { ChapterEntry(row.raw, it, url = "", slug = "") }
            assertEquals(row.display, chapter?.displayNumber(), "display of '${row.raw}'")
            assertEquals(row.file, chapter?.let(DownloadStorage::buildChapterFileName), "file of '${row.raw}'")
            assertEquals(row.id, chapter?.let { DownloadStorage.stableChapterId(it) }, "id of '${row.raw}'")
        }
    }

    @Test
    fun sortingIsNumericAndStable() {
        val texts = listOf("10", "9.5", "9.50", "1e1", "2", "0.5", "100")
        val sorted = texts
            .map { ChapterEntry(it, DownloadStorage.parseChapterValueOrNull(it)!!, url = it, slug = "") }
            .sortedBy { it.numberValue }
            .map { it.numberText }
        assertEquals(listOf("0.5", "1e1", "2", "9.5", "9.50", "10", "100"), sorted)
    }

    @Test
    fun equalityKeepsTheScaleLikeBigDecimal() {
        val a = DownloadStorage.parseChapterValueOrNull("9.5")!!
        val b = DownloadStorage.parseChapterValueOrNull("9.50")!!
        assertFalse(a == b)
        assertEquals(0, a.compareTo(b))
        assertEquals(a, b.stripTrailingZeros())
    }
}
