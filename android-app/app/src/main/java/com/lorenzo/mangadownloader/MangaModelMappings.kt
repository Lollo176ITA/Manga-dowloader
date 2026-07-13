package com.lorenzo.mangadownloader

/** Conversioni condivise tra i modelli della ricerca, dei preferiti e dei dettagli. */
fun FavoriteManga.identityKey(): String = MangaSourceCatalog.identityKey(sourceId, mangaUrl)

fun MangaSearchResult.toFavoriteManga(addedAt: Long = 0L): FavoriteManga = FavoriteManga(
    sourceId = sourceId,
    title = title,
    mangaUrl = mangaUrl,
    coverUrl = coverUrl,
    addedAt = addedAt,
)

fun MangaDetails.toFavoriteManga(addedAt: Long = 0L): FavoriteManga = FavoriteManga(
    sourceId = sourceId,
    title = title,
    mangaUrl = mangaUrl,
    coverUrl = coverUrl,
    addedAt = addedAt,
)

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
