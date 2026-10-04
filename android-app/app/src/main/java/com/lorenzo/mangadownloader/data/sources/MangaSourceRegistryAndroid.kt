package com.lorenzo.mangadownloader.data.sources

import android.content.Context
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.platform.settings

/** Le fonti con preferenze, rete e libreria di sempre del dispositivo Android. */
fun MangaSourceRegistry(
    context: Context,
    libraryRepository: LibraryRepository = LibraryRepository(context),
): MangaSourceRegistry = MangaSourceRegistry(
    appSettings = context.settings(SettingsStore.PREFS_NAME),
    networkClient = MangaNetworkClient(SharedHttpClient.ktor(context)),
    libraryRepository = libraryRepository,
)
