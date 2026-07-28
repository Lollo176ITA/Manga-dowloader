package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import java.util.Locale

data class ChapterEntry(
    val numberText: String,
    val numberValue: BigDecimal,
    val url: String,
    val slug: String,
    val volumeText: String? = null,
    val labelPrefix: String = "Capitolo",
    /**
     * Qualificatore per capitoli che condividono lo stesso numero **dentro la stessa fonte**
     * (es. Mangapill pubblica più gruppi di scanlation: "Chapter 1" e "Group 2 Chapter 1").
     * Senza di esso i due omonimi finirebbero sullo stesso `chapter_001.cbz` e uno
     * sovrascriverebbe l'altro. Vale `null` per il gruppo principale, così etichetta, nome
     * file e chiavi restano identici a prima e i download già su disco continuano a valere.
     */
    val variantTag: String? = null,
) {
    fun displayNumber(): String = numberValue.stripTrailingZeros().toPlainString()

    fun normalizedVariantTag(): String? = variantTag?.trim()?.takeIf(String::isNotBlank)

    fun displayShortLabel(): String {
        val base = "$labelPrefix ${displayNumber()}"
        return normalizedVariantTag()?.let { "$base ($it)" } ?: base
    }

    fun displayLabel(): String {
        val chapterLabel = displayShortLabel()
        return volumeText?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { "$it - $chapterLabel" }
            ?: chapterLabel
    }
}

data class DownloadPlan(
    val sourceId: String,
    val seriesTitle: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val outputDir: File,
    val chapters: List<ChapterEntry>,
    val totalChapterCount: Int,
    val startChapterLabel: String,
    val endChapterLabel: String,
)

data class MangaSearchResult(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
)

data class MangaDetails(
    val sourceId: String,
    val title: String,
    val coverUrl: String?,
    val mangaUrl: String,
    val chapters: List<ChapterEntry>,
    val description: String? = null,
    val status: MangaPublicationStatus = MangaPublicationStatus.UNKNOWN,
)

/**
 * Stato di pubblicazione di una serie, usato per le notifiche sui preferiti (non si
 * controllano i manga [COMPLETED]). [UNKNOWN] è il default e viene trattato come "in corso":
 * un parsing dello stato fallito non deve mai far perdere notifiche.
 */
enum class MangaPublicationStatus {
    ONGOING,
    COMPLETED,
    DROPPED,
    UNKNOWN,
}

/**
 * Mappa un testo di stato grezzo (IT/EN, da scraping) su [MangaPublicationStatus]. Best-effort.
 * Copre i valori reali delle fonti: MangaWorld (In corso/Finito/Droppato/In pausa), Mangapill
 * (Publishing/Finished/On Hiatus/Cancelled), VyManga (Ongoing/Completed), HastaTeam (campo JSON).
 * "In pausa"/hiatus è trattato come [ONGOING] (può riprendere). DROPPED va controllato per primo.
 */
fun mangaStatusFromText(raw: String?): MangaPublicationStatus {
    val text = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return MangaPublicationStatus.UNKNOWN
    return when {
        listOf("drop", "abbandon", "cancel", "discontinu").any { it in text } -> MangaPublicationStatus.DROPPED
        listOf("complet", "conclus", "finished", "finito", "termin", "ended", "fini")
            .any { it in text } -> MangaPublicationStatus.COMPLETED
        listOf("ongoing", "in corso", "publish", "in pubblicazione", "releasing", "serializ", "hiatus", "pausa", "in arrivo")
            .any { it in text } -> MangaPublicationStatus.ONGOING
        else -> MangaPublicationStatus.UNKNOWN
    }
}

/** Etichetta UI (IT) dello stato di pubblicazione, o `null` se sconosciuto (non si mostra nulla). */
fun MangaPublicationStatus.displayLabel(): String? = when (this) {
    MangaPublicationStatus.ONGOING -> "In corso"
    MangaPublicationStatus.COMPLETED -> "Terminato"
    MangaPublicationStatus.DROPPED -> "Abbandonato"
    MangaPublicationStatus.UNKNOWN -> null
}

fun readingUnitSingular(chapters: List<ChapterEntry>): String {
    return commonReadingPrefix(chapters)?.lowercase() ?: "elemento"
}

fun readingUnitPlural(chapters: List<ChapterEntry>): String {
    return when (commonReadingPrefix(chapters)?.lowercase()) {
        "volume" -> "volumi"
        "capitolo" -> "capitoli"
        else -> "elementi"
    }
}

private fun commonReadingPrefix(chapters: List<ChapterEntry>): String? {
    val prefixes = chapters
        .map { it.labelPrefix.trim() }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
    return prefixes.singleOrNull()
}

enum class DownloadResult {
    DOWNLOADED,
    SKIPPED_EXISTING,
}

enum class ThemeMode {
    AUTO,
    LIGHT,
    DARK,
}
