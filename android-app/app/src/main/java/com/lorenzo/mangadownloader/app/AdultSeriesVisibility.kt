package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.domain.isRegisteredAdult
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory

/*
 * Col filtro adulti attivo le serie del registro spariscono da preferiti, libreria, cronologia,
 * feed e widget. Si filtra solo ciò che arriva alle schermate: il ViewModel lavora sullo stato
 * completo e da lì riscrive i preferiti su disco, quindi filtrarlo li cancellerebbe. Pure.
 */

/**
 * Detto all'accensione del filtro: il registro copre ciò che è stato segnalato da una fonte, da
 * AniList o dal titolo, quindi una serie salvata in passato e sconosciuta ad AniList sfugge.
 */
const val ADULT_FILTER_ENABLED_NOTICE =
    "Filtro adulti attivo. Alcune serie già salvate potrebbero restare visibili se non sono su AniList"

/** Il registro da applicare: vuoto (non nasconde nulla) se il filtro è spento. */
fun MangaUiState.activeAdultKeys(): Set<String> =
    if (settings.hidesAdultContent()) adultSeriesKeys else emptySet()

fun List<FavoriteManga>.withoutRegisteredAdult(keys: Set<String>): List<FavoriteManga> =
    if (keys.isEmpty()) this else filterNot { isRegisteredAdult(keys, it.title, it.sourceId, it.mangaUrl, it.seriesKey) }

@JvmName("libraryWithoutRegisteredAdult")
fun List<DownloadedSeries>.withoutRegisteredAdult(keys: Set<String>): List<DownloadedSeries> =
    if (keys.isEmpty()) this else filterNot { isRegisteredAdult(keys, it.title, it.sourceId, it.mangaUrl) }

fun Map<String, ReadChapterMemory>.withoutRegisteredAdult(keys: Set<String>): Map<String, ReadChapterMemory> =
    if (keys.isEmpty()) {
        this
    } else {
        filterValues { record ->
            !isRegisteredAdult(
                keys,
                record.seriesTitle,
                record.sourceId.ifBlank { null },
                record.mangaUrl.ifBlank { null },
            )
        }
    }

@JvmName("updatesWithoutRegisteredAdult")
fun List<FavoriteUpdateEvent>.withoutRegisteredAdult(keys: Set<String>): List<FavoriteUpdateEvent> =
    if (keys.isEmpty()) this else filterNot { isRegisteredAdult(keys, it.title, it.sourceId, it.mangaUrl, it.seriesKey) }
