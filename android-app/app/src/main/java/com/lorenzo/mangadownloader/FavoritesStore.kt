package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/**
 * Persistenza dei preferiti su [SharedPreferences] (serializzazione tipizzata `@Serializable`,
 * formato su disco retrocompatibile col vecchio JSON costruito a mano). Estratta da
 * `MangaViewModel`.
 */
class FavoritesStore(private val prefs: SharedPreferences) {

    fun read(): List<FavoriteManga> {
        return prefs.readJson<List<FavoriteEntryJson>>(KEY_FAVORITES_JSON, emptyList())
            .mapNotNull { entry ->
                val title = entry.title.trim()
                val mangaUrl = entry.mangaUrl.trim()
                if (title.isBlank() || mangaUrl.isBlank()) {
                    null
                } else {
                    val sourceId = MangaSourceCatalog.resolveSourceId(entry.sourceId, mangaUrl)
                    FavoriteManga(
                        sourceId = sourceId,
                        title = title,
                        mangaUrl = mangaUrl,
                        coverUrl = entry.coverUrl,
                        addedAt = entry.addedAt,
                        // Preferiti salvati prima dello schema per-serie: chiave derivata dal
                        // titolo, la stessa che avrebbero avuto da `seriesKeyFor` senza link.
                        seriesKey = entry.seriesKey?.trim()?.takeIf(String::isNotBlank)
                            ?: SeriesIdentity.keyForTitle(title)
                            ?: MangaSourceCatalog.identityKey(sourceId, mangaUrl),
                    )
                }
            }
    }

    fun persist(favorites: List<FavoriteManga>) {
        val payload = favorites.map {
            FavoriteEntryJson(
                sourceId = it.sourceId,
                title = it.title,
                mangaUrl = it.mangaUrl,
                coverUrl = it.coverUrl,
                addedAt = it.addedAt,
                seriesKey = it.canonicalKey(),
            )
        }
        prefs.writeJson(KEY_FAVORITES_JSON, payload)
    }

    /**
     * Forma su disco di un preferito; i campi combaciano col formato storico. [seriesKey] è
     * additivo con default null: una versione precedente dell'app lo ignora e continua a
     * leggere il resto.
     */
    @Serializable
    private data class FavoriteEntryJson(
        val sourceId: String? = null,
        val title: String = "",
        val mangaUrl: String = "",
        val coverUrl: String? = null,
        val addedAt: Long = 0L,
        val seriesKey: String? = null,
    )

    private companion object {
        const val KEY_FAVORITES_JSON = "favorites_json"
    }
}
