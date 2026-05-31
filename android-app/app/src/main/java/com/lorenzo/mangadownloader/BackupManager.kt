package com.lorenzo.mangadownloader

import java.io.InputStream
import java.io.OutputStream

/**
 * Coordinatore Android-facing del backup (l'unico pezzo non puro). Costruito nel ViewModel dagli
 * store esistenti. Raccoglie i dati in un [MangaBackup] e, in ripristino, ri-persiste via gli
 * stessi store. L'IO sul file lo possiede il chiamante (MainActivity apre/chiude lo stream SAF):
 * qui si riceve uno stream già aperto e non lo si chiude.
 */
class BackupManager(
    private val favoritesStore: FavoritesStore,
    private val favoriteUpdatesStore: FavoriteUpdatesStore,
    private val favoriteDescriptionsStore: FavoriteDescriptionsStore,
    private val recentSearchesStore: RecentSearchesStore,
    private val settingsStore: SettingsStore,
    private val appVersionName: String,
) {

    fun buildBackup(nowMs: Long): MangaBackup = MangaBackup(
        schemaVersion = BACKUP_SCHEMA_VERSION,
        exportedAtMs = nowMs,
        appVersionName = appVersionName,
        favorites = favoritesStore.read().map { it.toBackupEntry() },
        favoriteUpdates = favoriteUpdatesStore.read(),
        favoriteDescriptions = favoriteDescriptionsStore.read(),
        recentSearches = recentSearchesStore.read(),
        settings = settingsStore.read().toBackup(),
    )

    /** Scrive il backup come JSON UTF-8 sullo stream fornito (aperto/chiuso dal chiamante). */
    fun export(output: OutputStream, nowMs: Long) {
        output.write(encodeBackup(buildBackup(nowMs)).toByteArray(Charsets.UTF_8))
    }

    /**
     * Legge e applica un backup dallo stream. Persiste su tutti gli store e restituisce i valori
     * per aggiornare lo stato della UI; `null` se il file non è un backup valido (gli store
     * restano intatti).
     */
    fun restore(input: InputStream, mode: BackupRestoreMode): BackupRestoreResult? {
        val raw = input.readBytes().toString(Charsets.UTF_8)
        val backup = decodeBackup(raw) ?: return null

        val currentFavorites = favoritesStore.read()
        val favorites: List<FavoriteManga>
        val recentSearches: List<String>
        val favoriteUpdates: Map<String, FavoriteSeenState>
        val favoriteDescriptions: Map<String, String>
        when (mode) {
            BackupRestoreMode.REPLACE -> {
                favorites = backup.favorites.mapNotNull { it.toFavoriteManga() }
                recentSearches = backup.recentSearches
                    .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                    .take(RecentSearchesStore.MAX_RECENT_SEARCHES)
                favoriteUpdates = backup.favoriteUpdates
                favoriteDescriptions = backup.favoriteDescriptions
            }
            BackupRestoreMode.MERGE -> {
                favorites = mergeFavorites(currentFavorites, backup.favorites)
                recentSearches = mergeRecentSearches(recentSearchesStore.read(), backup.recentSearches)
                favoriteUpdates = mergeFavoriteUpdates(favoriteUpdatesStore.read(), backup.favoriteUpdates)
                favoriteDescriptions = favoriteDescriptionsStore.read() + backup.favoriteDescriptions
            }
        }
        val settings = backup.settings.applyTo(settingsStore.read())

        favoritesStore.persist(favorites)
        recentSearchesStore.persist(recentSearches)
        favoriteUpdatesStore.write(favoriteUpdates)
        favoriteDescriptionsStore.write(favoriteDescriptions)
        settingsStore.persist(settings)

        return BackupRestoreResult(
            favorites = favorites,
            settings = settings,
            recentSearches = recentSearches,
            favoriteDescriptions = favoriteDescriptions,
            favoritesTotal = favorites.size,
            favoritesAdded = (favorites.size - currentFavorites.size).coerceAtLeast(0),
            mode = mode,
        )
    }
}

/** Esito di un ripristino: i valori già persistiti, da riflettere nello stato della UI. */
data class BackupRestoreResult(
    val favorites: List<FavoriteManga>,
    val settings: AppSettings,
    val recentSearches: List<String>,
    val favoriteDescriptions: Map<String, String>,
    val favoritesTotal: Int,
    val favoritesAdded: Int,
    val mode: BackupRestoreMode,
)
