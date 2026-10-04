package com.lorenzo.mangadownloader.data.store

import com.russhwolf.settings.Settings

internal actual fun platformGetStringSet(settings: Settings, key: String): Set<String>? = null

internal actual fun platformPutStringSet(settings: Settings, key: String, value: Set<String>): Boolean = false
