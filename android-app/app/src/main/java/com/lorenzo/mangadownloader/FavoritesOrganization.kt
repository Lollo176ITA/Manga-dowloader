package com.lorenzo.mangadownloader

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * Logica **pura** (niente Android/rete) per organizzare i preferiti: ordinamento, filtro per
 * categoria e conteggi. Speculare alla parte pura di [FavoriteUpdatesStore]. Le funzioni sono
 * top-level così sono testabili su JVM e riusabili sia dalla UI sia dal ViewModel.
 */
enum class FavoriteSort(val menuLabel: String) {
    DATE_ADDED("Aggiunta di recente"),
    TITLE_ASC("Titolo (A-Z)"),
    PUBLICATION_STATUS("Stato"),
    LAST_UPDATE("Ultimo capitolo"),
}

/** Una categoria/scaffale dei preferiti. `order` ne determina la posizione nella chip row. */
@Serializable
data class FavoriteCategory(
    val id: String = "",
    val name: String = "",
    val order: Int = 0,
)

/** ID sintetico del filtro "Senza cartella" (preferiti senza assegnazione). */
const val UNCATEGORIZED_CATEGORY_ID = "__uncategorized__"

/**
 * Etichetta di lettura **automatica** di un preferito, derivata dalla libreria scaricata
 * (nessuna assegnazione manuale). È affidabile solo per i manga con capitoli scaricati/letti:
 * un preferito non scaricato risulta [TO_START].
 */
enum class FavoriteReadingState(val label: String) {
    TO_START("Da iniziare"),
    IN_PROGRESS("In lettura"),
    COMPLETED("Completato"),
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

/** Mappa `identityKey -> stato di lettura` per i preferiti, abbinando per chiave la libreria. Pura. */
fun favoriteReadingStatesByKey(
    favorites: List<FavoriteManga>,
    library: List<DownloadedSeries>,
): Map<String, FavoriteReadingState> {
    val seriesByKey = library
        .mapNotNull { series ->
            series.mangaUrl?.let { url -> MangaSourceCatalog.identityKey(series.sourceId, url) to series }
        }
        .toMap()
    return favorites.associate { favorite ->
        val key = MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl)
        key to favoriteReadingState(seriesByKey[key])
    }
}

/** Categorie di default proposte al primo avvio (rinominabili / eliminabili dall'utente). */
object DefaultFavoriteCategories {
    const val ID_READING = "cat_reading"
    const val ID_TO_READ = "cat_toread"
    const val ID_COMPLETED = "cat_completed"

    val items: List<FavoriteCategory> = listOf(
        FavoriteCategory(ID_READING, "Sto leggendo", 0),
        FavoriteCategory(ID_TO_READ, "Da leggere", 1),
        FavoriteCategory(ID_COMPLETED, "Completati", 2),
    )
}

private fun favoriteKey(favorite: FavoriteManga): String =
    MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl)

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
 * Filtra i preferiti per testo e categoria. [categoryId] null = tutti;
 * [UNCATEGORIZED_CATEGORY_ID] = solo i non assegnati; altrimenti la categoria indicata.
 */
fun filterFavorites(
    favorites: List<FavoriteManga>,
    query: String,
    categoryId: String?,
    assignments: Map<String, String>,
): List<FavoriteManga> {
    val trimmed = query.trim()
    return favorites.filter { favorite ->
        val matchesText = trimmed.isBlank() || favorite.title.contains(trimmed, ignoreCase = true)
        val assigned = assignments[favoriteKey(favorite)]
        val matchesCategory = when (categoryId) {
            null -> true
            UNCATEGORIZED_CATEGORY_ID -> assigned == null
            else -> assigned == categoryId
        }
        matchesText && matchesCategory
    }
}

/**
 * Conteggi per chip: chiave `null` = "Tutti", [UNCATEGORIZED_CATEGORY_ID] = senza categoria,
 * altrimenti l'id categoria.
 */
fun categoryCounts(
    favorites: List<FavoriteManga>,
    assignments: Map<String, String>,
): Map<String?, Int> {
    val counts = linkedMapOf<String?, Int>(null to favorites.size)
    for (favorite in favorites) {
        val bucket = assignments[favoriteKey(favorite)] ?: UNCATEGORIZED_CATEGORY_ID
        counts[bucket] = (counts[bucket] ?: 0) + 1
    }
    return counts
}

/** Aggiunge una categoria (id stabile derivato dal nome, niente nomi duplicati). Pura. */
fun addCategory(categories: List<FavoriteCategory>, name: String): List<FavoriteCategory> {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return categories
    if (categories.any { it.name.equals(trimmed, ignoreCase = true) }) return categories
    val baseId = "cat_" + trimmed.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "x" }
    val existing = categories.mapTo(mutableSetOf()) { it.id }
    var id = baseId
    var suffix = 1
    while (id in existing) {
        id = "${baseId}_$suffix"
        suffix++
    }
    val order = (categories.maxOfOrNull { it.order } ?: -1) + 1
    return categories + FavoriteCategory(id = id, name = trimmed, order = order)
}

/** Rinomina una categoria (nessun effetto se il nome è vuoto). Pura. */
fun renameCategory(
    categories: List<FavoriteCategory>,
    id: String,
    newName: String,
): List<FavoriteCategory> {
    val trimmed = newName.trim()
    if (trimmed.isBlank()) return categories
    return categories.map { if (it.id == id) it.copy(name = trimmed) else it }
}

/** Rimuove una categoria. La pulizia delle assegnazioni orfane è a carico del chiamante. Pura. */
fun removeCategory(categories: List<FavoriteCategory>, id: String): List<FavoriteCategory> =
    categories.filterNot { it.id == id }
