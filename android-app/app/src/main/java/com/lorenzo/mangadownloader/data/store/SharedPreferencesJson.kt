package com.lorenzo.mangadownloader.data.store

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@PublishedApi
internal val sharedPreferencesJson = Json { ignoreUnknownKeys = true }

internal inline fun <reified T> SharedPreferences.readJson(
    key: String,
    defaultValue: T,
): T {
    val raw = getString(key, null).orEmpty()
    if (raw.isBlank()) return defaultValue
    return try {
        sharedPreferencesJson.decodeFromString<T>(raw)
    } catch (_: Exception) {
        preserveUnreadableJson(key, raw)
        defaultValue
    }
}

/**
 * Mette da parte un valore che non si riesce più a leggere, prima che il default restituito al
 * suo posto venga salvato sopra. Il caso tipico non è il disco rotto ma un modello cambiato in
 * modo incompatibile: senza copia, la prima scrittura cancellerebbe (per esempio) tutti i
 * preferiti. La copia resta finché non la sovrascrive un altro valore illeggibile della stessa
 * chiave.
 */
internal fun SharedPreferences.preserveUnreadableJson(key: String, raw: String) {
    val backupKey = key + UNREADABLE_JSON_SUFFIX
    if (getString(backupKey, null) != raw) {
        edit { putString(backupKey, raw) }
    }
}

/** Chiavi di cui esiste una copia illeggibile: solo i nomi, mai il contenuto. */
fun SharedPreferences.unreadableJsonKeys(): List<String> =
    all.keys.filter { it.endsWith(UNREADABLE_JSON_SUFFIX) }
        .map { it.removeSuffix(UNREADABLE_JSON_SUFFIX) }
        .sorted()

internal const val UNREADABLE_JSON_SUFFIX = ".illeggibile"

internal inline fun <reified T> SharedPreferences.writeJson(key: String, value: T) {
    edit { putString(key, sharedPreferencesJson.encodeToString(value)) }
}
