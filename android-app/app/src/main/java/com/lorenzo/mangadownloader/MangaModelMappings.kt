package com.lorenzo.mangadownloader

/** Conversioni condivise tra i modelli della ricerca, dei preferiti e dei dettagli. */

/** Chiave del **binding corrente** di un preferito (fonte + URL): non è la sua identità. */
fun FavoriteManga.identityKey(): String = MangaSourceCatalog.identityKey(sourceId, mangaUrl)

/**
 * Identità del preferito: la [FavoriteManga.seriesKey] persistita, o — per le istanze
 * costruite al volo e i preferiti salvati prima di questo schema — quella derivata dal
 * titolo, con le stesse regole di [SeriesLinksStore.seriesKeyFor].
 */
fun FavoriteManga.canonicalKey(): String = seriesKey.ifBlank {
    SeriesIdentity.keyForTitle(title) ?: MangaSourceCatalog.identityKey(sourceId, mangaUrl)
}

/**
 * Alias sotto cui riconoscere un preferito: la sua chiave canonica **più** la chiave-titolo.
 * Serve perché la stessa serie può presentarsi come `anilist:…` (preferito già promosso) o
 * come `title:…` (risultato di ricerca con AniList giù): senza alias la stella non si
 * accenderebbe e il toggle creerebbe un doppione.
 */
fun FavoriteManga.matchKeys(): Set<String> =
    setOfNotNull(canonicalKey(), SeriesIdentity.keyForTitle(title), identityKey())

/** `true` se l'insieme contiene almeno una delle chiavi indicate; i `null` sono ignorati. */
fun Set<String>.containsAny(vararg keys: String?): Boolean = keys.any { it != null && it in this }

fun MangaSearchResult.toFavoriteManga(addedAt: Long = 0L, seriesKey: String = ""): FavoriteManga =
    FavoriteManga(
        sourceId = sourceId,
        title = title,
        mangaUrl = mangaUrl,
        coverUrl = coverUrl,
        addedAt = addedAt,
        seriesKey = seriesKey,
    )

fun MangaDetails.toFavoriteManga(addedAt: Long = 0L, seriesKey: String = ""): FavoriteManga =
    FavoriteManga(
        sourceId = sourceId,
        title = title,
        mangaUrl = mangaUrl,
        coverUrl = coverUrl,
        addedAt = addedAt,
        seriesKey = seriesKey,
    )

/**
 * Preferito costruito da una serie scaricata (stella nella schermata serie): `null` senza
 * l'URL d'origine, che è l'identità del preferito. La cover resta `null` (su disco c'è un
 * File locale, non un URL): arriva al prossimo fetch dei dettagli come per gli altri.
 */
fun DownloadedSeries.toFavoriteManga(addedAt: Long = 0L, seriesKey: String = ""): FavoriteManga? {
    val url = mangaUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return FavoriteManga(
        sourceId = sourceId,
        title = title,
        mangaUrl = url,
        coverUrl = null,
        addedAt = addedAt,
        seriesKey = seriesKey,
    )
}

fun FavoriteManga.toSearchResult(): MangaSearchResult = MangaSearchResult(
    sourceId = sourceId,
    title = title,
    mangaUrl = mangaUrl,
    coverUrl = coverUrl,
)

fun MangaDetails.toSearchResult(): MangaSearchResult = MangaSearchResult(
    sourceId = sourceId,
    title = title,
    mangaUrl = mangaUrl,
    coverUrl = coverUrl,
)

fun FavoriteUpdateEvent.toSearchResult(): MangaSearchResult = MangaSearchResult(
    sourceId = sourceId,
    title = title,
    mangaUrl = mangaUrl,
    coverUrl = coverUrl,
)

/** Placeholder mostrato mentre vengono caricati capitoli e metadati completi. */
fun MangaSearchResult.toDetailsStub(): MangaDetails = MangaDetails(
    sourceId = sourceId,
    title = title,
    coverUrl = coverUrl,
    mangaUrl = mangaUrl,
    chapters = emptyList(),
)
