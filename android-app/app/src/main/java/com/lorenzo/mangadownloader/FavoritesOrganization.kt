package com.lorenzo.mangadownloader

import java.math.BigDecimal

/**
 * Logica **pura** (niente Android/rete) per organizzare i preferiti: ordinamento e filtro
 * per stato di lettura. Speculare alla parte pura di [FavoriteUpdatesStore]. Le funzioni sono
 * top-level così sono testabili su JVM e riusabili sia dalla UI sia dal ViewModel.
 */
enum class FavoriteSort(val menuLabel: String) {
    DATE_ADDED("Aggiunta di recente"),
    TITLE_ASC("Titolo (A-Z)"),
    PUBLICATION_STATUS("Stato"),
    LAST_UPDATE("Ultimo capitolo"),
}

/**
 * Etichetta di lettura **automatica** di un preferito, derivata dalla libreria scaricata
 * (nessuna assegnazione manuale). È affidabile solo per i manga con capitoli scaricati/letti:
 * un preferito non scaricato risulta [TO_START].
 */
/** [shortLabel] è la variante compatta usata dal filtro segmented dei Preferiti. */
enum class FavoriteReadingState(val label: String, val shortLabel: String) {
    TO_START("Da iniziare", "Da iniziare"),
    IN_PROGRESS("In lettura", "In lettura"),
    COMPLETED("Completato", "Letti"),
}

/**
 * I primi [count] capitoli da leggere (numeri più bassi), indipendentemente dall'ordine in cui
 * la fonte li elenca. Usata dalla shortcut "Leggi" dei preferiti. Pura.
 */
fun firstChaptersForReading(chapters: List<ChapterEntry>, count: Int = 3): List<ChapterEntry> =
    chapters.sortedBy { it.numberValue }.take(count.coerceAtLeast(0))

/** Stato di lettura per una serie scaricata (o null = non in libreria → "Da iniziare"). Pura. */
fun favoriteReadingState(series: DownloadedSeries?): FavoriteReadingState {
    if (series == null) return FavoriteReadingState.TO_START
    if (series.isFullyRead()) return FavoriteReadingState.COMPLETED
    val started = series.readChapterCount() > 0 || series.chapters.any { it.hasReaderProgress() }
    return if (started) FavoriteReadingState.IN_PROGRESS else FavoriteReadingState.TO_START
}

/**
 * Mappa `seriesKey -> stato di lettura` per i preferiti, abbinando la libreria scaricata.
 *
 * L'abbinamento prova prima la fonte corrente del preferito e poi il titolo normalizzato:
 * senza il ripiego sul titolo, un preferito passato a un altro mirror perderebbe lo stato di
 * lettura di capitoli che sono ancora sul telefono, scaricati dalla fonte precedente. Pura.
 */
fun favoriteReadingStatesByKey(
    favorites: List<FavoriteManga>,
    library: List<DownloadedSeries>,
): Map<String, FavoriteReadingState> {
    val seriesByBinding = library
        .mapNotNull { series ->
            series.mangaUrl?.let { url -> MangaSourceCatalog.identityKey(series.sourceId, url) to series }
        }
        .toMap()
    val seriesByTitle = library
        .mapNotNull { series -> SeriesIdentity.keyForTitle(series.title)?.let { it to series } }
        .toMap()
    return favorites.associate { favorite ->
        val downloaded = seriesByBinding[favorite.identityKey()]
            ?: SeriesIdentity.keyForTitle(favorite.title)?.let { seriesByTitle[it] }
        favorite.canonicalKey() to favoriteReadingState(downloaded)
    }
}

private fun favoriteKey(favorite: FavoriteManga): String = favorite.canonicalKey()

private fun MangaPublicationStatus.sortRank(): Int = when (this) {
    MangaPublicationStatus.ONGOING -> 0
    MangaPublicationStatus.UNKNOWN -> 1
    MangaPublicationStatus.COMPLETED -> 2
    MangaPublicationStatus.DROPPED -> 3
}

/** Ordina i preferiti secondo [sort]. Tie-break per titolo così l'ordine è deterministico. */
fun sortFavorites(
    favorites: List<FavoriteManga>,
    sort: FavoriteSort,
    statusByKey: Map<String, MangaPublicationStatus>,
    seenByKey: Map<String, FavoriteSeenState>,
): List<FavoriteManga> = when (sort) {
    FavoriteSort.DATE_ADDED ->
        // addedAt desc; i legacy (0) finiscono in fondo mantenendo l'ordine d'inserimento (sort stabile).
        favorites.sortedByDescending { it.addedAt }
    FavoriteSort.TITLE_ASC ->
        favorites.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    FavoriteSort.PUBLICATION_STATUS ->
        favorites.sortedWith(
            compareBy<FavoriteManga> {
                (statusByKey[favoriteKey(it)] ?: MangaPublicationStatus.UNKNOWN).sortRank()
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    FavoriteSort.LAST_UPDATE ->
        favorites.sortedWith(
            compareByDescending<FavoriteManga> {
                seenByKey[favoriteKey(it)]?.latestChapterNumber?.toBigDecimalOrNull()
                    ?: BigDecimal(Long.MIN_VALUE)
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
}

/**
 * Filtra i preferiti per testo e stato di lettura. [readingState] null = tutti gli stati;
 * un preferito assente da [readingStateByKey] conta come [FavoriteReadingState.TO_START]
 * (stessa convenzione di [favoriteReadingState]).
 */
fun filterFavorites(
    favorites: List<FavoriteManga>,
    query: String,
    readingState: FavoriteReadingState? = null,
    readingStateByKey: Map<String, FavoriteReadingState> = emptyMap(),
): List<FavoriteManga> {
    val trimmed = query.trim()
    return favorites.filter { favorite ->
        val matchesText = trimmed.isBlank() || favorite.title.contains(trimmed, ignoreCase = true)
        val matchesReading = readingState == null ||
            (readingStateByKey[favoriteKey(favorite)] ?: FavoriteReadingState.TO_START) == readingState
        matchesText && matchesReading
    }
}
