package com.lorenzo.mangadownloader

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Core **puro** (niente Android/rete/IO) del backup: schema versionato `@Serializable`,
 * codifica/decodifica tollerante e logica di merge/replace. Speculare alla parte pura di
 * [FavoriteUpdatesStore]. Tutto top-level così è testabile su JVM senza Robolectric.
 *
 * Cosa NON entra nel backup (v1): i **progressi di lettura** (posizione pagina, capitoli letti)
 * vivono in un altro file di prefs e in `series.json`, legati ai file `.cbz` scaricati che non
 * fanno parte del backup. Il campo è riservato per una versione futura senza rompere il formato.
 * Volutamente esclusi anche i **segreti** del parental control (PIN hash/salt) e lo stato del
 * tutorial: il restore non li tocca mai.
 */
const val BACKUP_SCHEMA_VERSION = 1

@Serializable
data class MangaBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAtMs: Long = 0L,
    val appVersionName: String = "",
    val favorites: List<FavoriteBackupEntry> = emptyList(),
    val favoriteUpdates: Map<String, FavoriteSeenState> = emptyMap(),
    val favoriteDescriptions: Map<String, String> = emptyMap(),
    val recentSearches: List<String> = emptyList(),
    val settings: SettingsBackup = SettingsBackup(),
)

/** Forma su disco di un preferito nel backup; combacia con `FavoritesStore`. */
@Serializable
data class FavoriteBackupEntry(
    val sourceId: String? = null,
    val title: String = "",
    val mangaUrl: String = "",
    val coverUrl: String? = null,
    val addedAt: Long = 0L,
)

/**
 * DTO serializzabile delle impostazioni: [AppSettings] non è `@Serializable` (è il modello vivo
 * della UI e referenzia enum), quindi si mappa qui esplicitamente. Gli enum sono salvati come
 * nomi (String) con fallback tollerante in lettura. Esclude i campi segreti/locali del device.
 */
@Serializable
data class SettingsBackup(
    val searchScope: String = SearchScope.ITA.name,
    val searchSourceId: String = MangaSourceIds.DEFAULT,
    val showIndividualSources: Boolean = false,
    val discoveryEnabled: Boolean = false,
    val autoDownloadEnabled: Boolean = false,
    val autoDownloadTriggerChapters: Int = 3,
    val autoDownloadBatchSize: Int = 3,
    val smartCleanupEnabled: Boolean = false,
    val smartCleanupKeepPreviousChapters: Int = 3,
    val streamingReaderEnabled: Boolean = false,
    val parentalControlEnabled: Boolean = false,
    val parentalBiometricEnabled: Boolean = false,
    val labsEnabled: Boolean = false,
    val downloadDevUpdates: Boolean = false,
    val highResImages: Boolean = false,
    val privacyBrightnessEnabled: Boolean = false,
    val readerBrightness: Float = 1f,
    val readingMode: String = ReadingMode.VERTICAL.name,
    val readerPageSpacingDp: Int = DEFAULT_READER_PAGE_SPACING_DP,
    val doubleTapZoomEnabled: Boolean = false,
    val keepScreenOnEnabled: Boolean = true,
    val allowLandscapeRotation: Boolean = false,
    val themeMode: String = ThemeMode.AUTO.name,
    val useDynamicColor: Boolean = false,
    val favoriteNewChapterNotificationsEnabled: Boolean = false,
    val favoriteSort: String = FavoriteSort.DATE_ADDED.name,
    val librarySort: String = LibrarySort.TITLE_ASC.name,
)

enum class BackupRestoreMode { MERGE, REPLACE }

private val backupJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeBackup(backup: MangaBackup): String = backupJson.encodeToString(backup)

/** Decodifica tollerante: JSON malformato o schema più recente del corrente → null. */
fun decodeBackup(raw: String): MangaBackup? {
    return try {
        val parsed = backupJson.decodeFromString<MangaBackup>(raw)
        if (parsed.schemaVersion > BACKUP_SCHEMA_VERSION) null else parsed
    } catch (_: Exception) {
        null
    }
}

fun AppSettings.toBackup(): SettingsBackup = SettingsBackup(
    searchScope = searchScope.name,
    searchSourceId = searchSourceId,
    showIndividualSources = showIndividualSources,
    discoveryEnabled = discoveryEnabled,
    autoDownloadEnabled = autoDownloadEnabled,
    autoDownloadTriggerChapters = autoDownloadTriggerChapters,
    autoDownloadBatchSize = autoDownloadBatchSize,
    smartCleanupEnabled = smartCleanupEnabled,
    smartCleanupKeepPreviousChapters = smartCleanupKeepPreviousChapters,
    streamingReaderEnabled = streamingReaderEnabled,
    parentalControlEnabled = parentalControlEnabled,
    parentalBiometricEnabled = parentalBiometricEnabled,
    labsEnabled = labsEnabled,
    downloadDevUpdates = downloadDevUpdates,
    highResImages = highResImages,
    privacyBrightnessEnabled = privacyBrightnessEnabled,
    readerBrightness = readerBrightness,
    readingMode = readingMode.name,
    readerPageSpacingDp = readerPageSpacingDp,
    doubleTapZoomEnabled = doubleTapZoomEnabled,
    keepScreenOnEnabled = keepScreenOnEnabled,
    allowLandscapeRotation = allowLandscapeRotation,
    themeMode = themeMode.name,
    useDynamicColor = useDynamicColor,
    favoriteNewChapterNotificationsEnabled = favoriteNewChapterNotificationsEnabled,
    favoriteSort = favoriteSort.name,
    librarySort = librarySort.name,
)

/**
 * Applica le impostazioni del backup su [current], riapplicando le coercizioni (così un file
 * manomesso non produce stato invalido) e tenendo i campi protetti dal device corrente:
 * PIN hash/salt, `parentalPinConfigured`, `tutorialCompleted` non vengono mai sovrascritti.
 * `parentalControlEnabled` viene riacceso solo se sul device c'è già un PIN configurato.
 */
fun SettingsBackup.applyTo(current: AppSettings): AppSettings = current.copy(
    searchScope = runCatching { SearchScope.valueOf(searchScope) }.getOrDefault(current.searchScope),
    searchSourceId = MangaSourceCatalog.resolveSourceId(searchSourceId),
    showIndividualSources = showIndividualSources,
    discoveryEnabled = discoveryEnabled,
    autoDownloadEnabled = autoDownloadEnabled,
    autoDownloadTriggerChapters = autoDownloadTriggerChapters.coerceAtLeast(1),
    autoDownloadBatchSize = autoDownloadBatchSize.coerceAtLeast(1),
    smartCleanupEnabled = smartCleanupEnabled,
    smartCleanupKeepPreviousChapters = smartCleanupKeepPreviousChapters.coerceAtLeast(0),
    streamingReaderEnabled = streamingReaderEnabled,
    parentalControlEnabled = parentalControlEnabled && current.parentalPinConfigured,
    parentalBiometricEnabled = parentalBiometricEnabled,
    labsEnabled = labsEnabled,
    downloadDevUpdates = downloadDevUpdates,
    highResImages = highResImages,
    privacyBrightnessEnabled = privacyBrightnessEnabled,
    readerBrightness = readerBrightness.coerceIn(0f, 1f),
    readingMode = runCatching { ReadingMode.valueOf(readingMode) }.getOrDefault(current.readingMode),
    readerPageSpacingDp = readerPageSpacingDp.coerceIn(0, MAX_READER_PAGE_SPACING_DP),
    doubleTapZoomEnabled = doubleTapZoomEnabled,
    keepScreenOnEnabled = keepScreenOnEnabled,
    allowLandscapeRotation = allowLandscapeRotation,
    themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(current.themeMode),
    useDynamicColor = useDynamicColor,
    favoriteNewChapterNotificationsEnabled = favoriteNewChapterNotificationsEnabled,
    favoriteSort = runCatching { FavoriteSort.valueOf(favoriteSort) }.getOrDefault(current.favoriteSort),
    librarySort = runCatching { LibrarySort.valueOf(librarySort) }.getOrDefault(current.librarySort),
)

fun FavoriteManga.toBackupEntry(): FavoriteBackupEntry =
    FavoriteBackupEntry(
        sourceId = sourceId,
        title = title,
        mangaUrl = mangaUrl,
        coverUrl = coverUrl,
        addedAt = addedAt,
    )

/** Converte una entry di backup in [FavoriteManga], risolvendo la fonte come fa `FavoritesStore`. */
fun FavoriteBackupEntry.toFavoriteManga(): FavoriteManga? {
    val cleanTitle = title.trim()
    val cleanUrl = mangaUrl.trim()
    if (cleanTitle.isBlank() || cleanUrl.isBlank()) return null
    return FavoriteManga(
        sourceId = MangaSourceCatalog.resolveSourceId(sourceId, cleanUrl),
        title = cleanTitle,
        mangaUrl = cleanUrl,
        coverUrl = coverUrl,
        addedAt = addedAt,
    )
}

/** MERGE dei preferiti: tiene i correnti in testa, aggiunge solo i nuovi (dedup per identityKey). */
fun mergeFavorites(
    current: List<FavoriteManga>,
    incoming: List<FavoriteBackupEntry>,
): List<FavoriteManga> {
    val merged = current.toMutableList()
    val keys = current.mapTo(mutableSetOf()) {
        MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl)
    }
    for (entry in incoming) {
        val favorite = entry.toFavoriteManga() ?: continue
        val key = MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl)
        if (keys.add(key)) {
            merged += favorite
        }
    }
    return merged
}

/** MERGE delle ricerche recenti: correnti in testa, aggiunge le nuove (dedup case-insensitive, cap). */
fun mergeRecentSearches(current: List<String>, incoming: List<String>): List<String> {
    val seen = current.mapTo(mutableSetOf()) { it.lowercase() }
    val merged = current.toMutableList()
    for (query in incoming) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) continue
        if (seen.add(trimmed.lowercase())) {
            merged += trimmed
        }
    }
    return merged.take(RecentSearchesStore.MAX_RECENT_SEARCHES)
}

/**
 * MERGE della baseline "ultimo visto" delle notifiche: per chiave tiene il numero capitolo più
 * alto, così reimportare un backup vecchio non fa regredire la baseline (niente notifiche spurie).
 */
fun mergeFavoriteUpdates(
    current: Map<String, FavoriteSeenState>,
    incoming: Map<String, FavoriteSeenState>,
): Map<String, FavoriteSeenState> {
    val merged = current.toMutableMap()
    for ((key, incomingState) in incoming) {
        val currentState = merged[key]
        merged[key] = if (currentState == null) {
            incomingState
        } else {
            val currentNumber = currentState.latestChapterNumber.toBigDecimalOrNull()
            val incomingNumber = incomingState.latestChapterNumber.toBigDecimalOrNull()
            if (incomingNumber != null && (currentNumber == null || incomingNumber > currentNumber)) {
                incomingState
            } else {
                currentState
            }
        }
    }
    return merged
}
