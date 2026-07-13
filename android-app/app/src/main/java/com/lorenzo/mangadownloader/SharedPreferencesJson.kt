package com.lorenzo.mangadownloader

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
        defaultValue
    }
}

internal inline fun <reified T> SharedPreferences.writeJson(key: String, value: T) {
    edit { putString(key, sharedPreferencesJson.encodeToString(value)) }
}
