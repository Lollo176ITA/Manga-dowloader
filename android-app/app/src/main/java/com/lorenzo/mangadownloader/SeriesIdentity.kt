package com.lorenzo.mangadownloader

import java.text.Normalizer
import java.util.Locale

/**
 * Identità canonica di una serie, indipendente dalla fonte: `anilist:<mediaId>` quando la
 * serie è agganciata ad AniList, altrimenti `title:<titolo-normalizzato>`. Sta sopra
 * all'identityKey per-fonte di [MangaSourceCatalog] (che resta per capitoli/URL) e permette
 * a preferiti, tracking e progressi di sopravvivere al cambio fonte.
 */
object SeriesIdentity {
    const val ANILIST_PREFIX = "anilist:"
    const val TITLE_PREFIX = "title:"

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Normalizzazione conservativa per il confronto titoli tra fonti/lingue: minuscole,
     * accenti rimossi (NFKD), tutto ciò che non è lettera/cifra diventa spazio, spazi
     * collassati. Applicata a entrambi i lati di ogni confronto, mai mostrata in UI.
     */
    fun normalizeTitle(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
        return decomposed
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    fun keyForAniList(mediaId: Int): String = "$ANILIST_PREFIX$mediaId"

    fun keyForTitle(title: String): String? {
        val normalized = normalizeTitle(title).takeIf(String::isNotBlank) ?: return null
        return "$TITLE_PREFIX$normalized"
    }

    fun aniListIdFromKey(seriesKey: String): Int? {
        return seriesKey.removePrefix(ANILIST_PREFIX)
            .takeIf { it != seriesKey }
            ?.toIntOrNull()
    }
}
