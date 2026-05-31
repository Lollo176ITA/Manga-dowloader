package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistenza (stesso pattern JSON-on-SharedPreferences di [FavoriteUpdatesStore]) delle
 * categorie dei preferiti e della mappa di assegnazione `identityKey -> categoryId`.
 * Tollerante: JSON illeggibile → default/vuoto, mai crash.
 */
class FavoriteCategoriesStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Categorie definite; al primo avvio (chiave assente) restituisce i default senza persistere. */
    fun readCategories(): List<FavoriteCategory> {
        val raw = prefs.getString(KEY_CATEGORIES_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return DefaultFavoriteCategories.items
        }
        return try {
            json.decodeFromString<List<FavoriteCategory>>(raw)
        } catch (_: Exception) {
            DefaultFavoriteCategories.items
        }
    }

    fun writeCategories(categories: List<FavoriteCategory>) {
        prefs.edit()
            .putString(KEY_CATEGORIES_JSON, json.encodeToString(categories))
            .apply()
    }

    fun readAssignments(): Map<String, String> {
        val raw = prefs.getString(KEY_ASSIGNMENTS_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun writeAssignments(assignments: Map<String, String>) {
        prefs.edit()
            .putString(KEY_ASSIGNMENTS_JSON, json.encodeToString(assignments))
            .apply()
    }

    private companion object {
        const val KEY_CATEGORIES_JSON = "favorite_categories_json"
        const val KEY_ASSIGNMENTS_JSON = "favorite_category_assignments_json"
    }
}
