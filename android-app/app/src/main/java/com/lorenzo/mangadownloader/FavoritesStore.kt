package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistenza dei preferiti su [SharedPreferences] (serializzazione tipizzata `@Serializable`,
 * formato su disco retrocompatibile col vecchio JSON costruito a mano). Estratta da
 * `MangaViewModel`.
 */
class FavoritesStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): List<FavoriteManga> {
        val raw = prefs.getString(KEY_FAVORITES_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            json.decodeFromString<List<FavoriteEntryJson>>(raw).mapNotNull { entry ->
                val title = entry.title.trim()
                val mangaUrl = entry.mangaUrl.trim()
                if (title.isBlank() || mangaUrl.isBlank()) {
                    null
                } else {
                    FavoriteManga(
                        sourceId = MangaSourceCatalog.resolveSourceId(entry.sourceId, mangaUrl),
                        title = title,
                        mangaUrl = mangaUrl,
                        coverUrl = entry.coverUrl,
                        addedAt = entry.addedAt,
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
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
            )
        }
        prefs.edit {
            putString(KEY_FAVORITES_JSON, json.encodeToString(payload))
        }
    }

    /** Forma su disco di un preferito; i campi combaciano col formato storico. */
    @Serializable
    private data class FavoriteEntryJson(
        val sourceId: String? = null,
        val title: String = "",
        val mangaUrl: String = "",
        val coverUrl: String? = null,
        val addedAt: Long = 0L,
    )

    private companion object {
        const val KEY_FAVORITES_JSON = "favorites_json"
    }
}
