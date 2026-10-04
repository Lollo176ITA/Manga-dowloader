package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.ChapterNumber
import com.lorenzo.mangadownloader.data.model.toChapterNumberOrNull
import com.lorenzo.mangadownloader.platform.availableBytes
import com.lorenzo.mangadownloader.platform.invariantRelativeTo
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Path

object DownloadStorage {
    const val LIBRARY_FOLDER_NAME = AppPaths.LIBRARY_FOLDER_NAME
    const val SERIES_METADATA_FILE_NAME = "series.json"

    private val chapterFileRegex = Regex("""^chapter_(.+)\.cbz$""", RegexOption.IGNORE_CASE)
    private val numericRegex = Regex("""\d+(?:\.\d+)?""")

    /**
     * Separa numero e variante nel nome file (`chapter_001__group_2.cbz`). Doppio underscore
     * perché [safeFilename] collassa le sequenze di caratteri non ammessi in un solo `_`,
     * quindi non può produrlo da sé e resta un delimitatore affidabile.
     */
    private const val VARIANT_SEPARATOR = "__"

    /**
     * Soglia minima di spazio libero richiesta prima di scaricare un capitolo.
     * Margine prudente: una richiesta scrive le pagine in una cartella temporanea e
     * poi lo zip finale, quindi serve spazio per entrambi durante la finalizzazione.
     */
    const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024

    /** Policy pura: c'è abbastanza spazio libero? (testabile senza toccare il filesystem) */
    fun hasEnoughFreeSpace(
        availableBytes: Long,
        requiredBytes: Long = MIN_FREE_SPACE_BYTES,
    ): Boolean = availableBytes >= requiredBytes

    /**
     * Spazio libero (byte) sul volume che contiene [dir]. **Fail-open**: se la misura
     * non è possibile restituisce `Long.MAX_VALUE`, così un guasto della misura non
     * blocca i download (al massimo si ricade nel vecchio comportamento su `IOException`).
     */
    fun freeSpaceBytes(dir: Path): Long {
        return try {
            availableBytes(dir)
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun safeFilename(input: String): String {
        return input.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").trim('_').ifBlank { "manga" }
    }

    fun buildChapterFileName(chapter: ChapterEntry): String {
        val label = normalizedChapterLabel(chapter.numberText)
        val padded = if (label.all(Char::isDigit)) label.padStart(3, '0') else label
        // Il gruppo principale non ha variante → nome file invariato rispetto alle versioni
        // precedenti, così i .cbz già scaricati restano associati al loro capitolo.
        val variant = chapter.normalizedVariantTag()
            ?.let { "$VARIANT_SEPARATOR${safeFilename(it)}" }
            .orEmpty()
        return "chapter_${safeFilename(padded)}$variant.cbz"
    }

    fun normalizedChapterLabel(raw: String): String {
        return raw.toChapterNumberOrNull()?.stripTrailingZeros()?.toPlainString() ?: raw.trim()
    }

    /**
     * Chiave "per numero" usata per marcare come scaricato/letto lo stesso capitolo arrivato
     * da un URL diverso (tipicamente un'altra fonte). La variante entra nella chiave, altrimenti
     * due capitoli omonimi della stessa fonte si marcherebbero a vicenda.
     */
    fun chapterNumberKey(numberLabel: String, variantTag: String?): String {
        val normalizedVariant = variantTag?.trim()?.takeIf(String::isNotBlank)
        val suffix = normalizedVariant?.let { "@$it" }.orEmpty()
        return "number:${normalizedChapterLabel(numberLabel)}$suffix"
    }

    fun parseChapterLabelFromFileName(fileName: String): String? {
        val raw = chapterFileRegex.matchEntire(fileName)?.groupValues?.getOrNull(1) ?: return null
        return normalizedChapterLabel(raw.substringBefore(VARIANT_SEPARATOR))
    }

    /** La variante codificata nel nome file, se presente (`chapter_001__group_1.cbz`). */
    fun parseChapterVariantFromFileName(fileName: String): String? {
        val raw = chapterFileRegex.matchEntire(fileName)?.groupValues?.getOrNull(1) ?: return null
        if (!raw.contains(VARIANT_SEPARATOR)) return null
        return raw.substringAfter(VARIANT_SEPARATOR).trim().takeIf(String::isNotBlank)
    }

    fun parseChapterValueOrNull(raw: String): ChapterNumber? {
        return numericRegex.find(raw)?.value?.toChapterNumberOrNull()
    }

    /** Estensione immagine (minuscola, solo alfanumerici) dall'URL; `jpg` se assente. */
    fun imageExtension(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('.', "jpg")
        val cleaned = raw.lowercase().filter { it.isLetterOrDigit() }
        return if (cleaned.isBlank()) "jpg" else cleaned
    }

    fun stableChapterId(
        numberText: String,
        url: String?,
        slug: String?,
    ): String {
        val normalizedUrl = url?.trim()?.takeIf(String::isNotBlank)
        if (normalizedUrl != null) {
            return "url:$normalizedUrl"
        }
        val normalizedSlug = slug?.trim()?.takeIf(String::isNotBlank)
        if (normalizedSlug != null) {
            return "slug:$normalizedSlug"
        }
        return "number:${normalizedChapterLabel(numberText)}"
    }

    fun stableChapterId(chapter: ChapterEntry): String {
        return stableChapterId(
            numberText = chapter.displayNumber(),
            url = chapter.url,
            slug = chapter.slug,
        )
    }

    fun relativePath(root: Path, file: Path): String {
        return file.invariantRelativeTo(root)
    }

    fun readerCacheDirectoryName(relativePath: String): String = relativePath.encodeUtf8().sha256().hex()

    fun chapterComparator(): Comparator<DownloadedChapter> {
        return compareBy<DownloadedChapter>(
            { it.numberValue == null },
            { it.numberValue ?: ChapterNumber.ZERO },
            { it.numberText.lowercase() },
        )
    }
}
