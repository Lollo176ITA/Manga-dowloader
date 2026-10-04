package com.lorenzo.mangadownloader.data.store

import com.russhwolf.settings.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@PublishedApi
internal val sharedPreferencesJson = Json { ignoreUnknownKeys = true }

inline fun <reified T> Settings.readJson(
    key: String,
    defaultValue: T,
): T {
    val raw = getString(key, null).orEmpty()
    if (raw.isBlank()) return defaultValue
    return try {
        sharedPreferencesJson.decodeFromString<T>(raw)
    } catch (_: Exception) {
        defaultValue
    }
}

inline fun <reified T> Settings.writeJson(key: String, value: T) {
    edit { putString(key, sharedPreferencesJson.encodeToString(value)) }
}
