package com.lorenzo.mangadownloader

/**
 * Logica di dominio (non UI) per associare i metadati di una serie/capitolo alla libreria
 * scaricata: matching per identità (sourceId+URL) con fallback sul titolo. Sta accanto a
 * [MangaSourceCatalog] ed è pura → testabile senza Android/Robolectric.
 */
object LibraryMatching {

    /**
     * Chiavi dei capitoli già scaricati per la serie [details]: l'id stabile del capitolo
     * più una chiave per-numero (`number:<label>`), così il dettaglio può marcare come
     * "scaricato" anche capitoli con URL diverso ma stesso numero.
     */
    fun downloadedChapterKeys(
        details: MangaDetails,
        library: List<DownloadedSeries>,
    ): Set<String> {
        val matchingSeries = matchingDownloadedSeries(details, library) ?: return emptySet()
        return buildSet {
            matchingSeries.chapters.forEach { chapter ->
                add(chapter.chapterId)
                add("number:${DownloadStorage.normalizedChapterLabel(chapter.numberText)}")
            }
        }
    }

    /** Id dei capitoli letti per la serie [details] (sia da `readChapterIds` sia dai capitoli `isRead`). */
    fun downloadedReadChapterIds(
        details: MangaDetails,
        library: List<DownloadedSeries>,
    ): Set<String> {
        return matchingDownloadedSeries(details, library)?.let { series ->
            buildSet {
                addAll(series.readChapterIds)
                series.chapters
                    .filter { it.isRead }
                    .mapTo(this) { it.chapterId }
            }
        }.orEmpty()
    }

    /** La serie scaricata che corrisponde a [details] per identità o titolo, se presente. */
    fun matchingDownloadedSeries(
        details: MangaDetails,
        library: List<DownloadedSeries>,
    ): DownloadedSeries? {
        val detailsKey = MangaSourceCatalog.identityKey(details.sourceId, details.mangaUrl)
        val detailsTitleKey = MangaSourceCatalog.identityKeyOrNull(
            sourceId = details.sourceId,
            mangaUrl = null,
            title = details.title,
        )
        val detailsKeys = buildSet {
            add(detailsKey)
            detailsTitleKey?.let(::add)
        }
        return library.firstOrNull { series ->
            val seriesKey = MangaSourceCatalog.identityKeyOrNull(
                sourceId = series.sourceId,
                mangaUrl = series.mangaUrl,
                title = series.title,
            )
            val seriesTitleKey = MangaSourceCatalog.identityKeyOrNull(
                sourceId = series.sourceId,
                mangaUrl = null,
                title = series.title,
            )
            seriesKey in detailsKeys || seriesTitleKey in detailsKeys
        }
    }

    /**
     * Serie della libreria che corrisponde al [sample] del tutorial; se il sample è assente
     * o non trova corrispondenza, ripiega sulla prima serie disponibile.
     */
    fun tutorialSampleSeries(
        sample: TutorialSample?,
        library: List<DownloadedSeries>,
    ): DownloadedSeries? {
        sample ?: return library.firstOrNull()
        val sampleKey = MangaSourceCatalog.identityKey(sample.sourceId, sample.mangaUrl)
        val sampleTitleKey = MangaSourceCatalog.identityKeyOrNull(
            sourceId = sample.sourceId,
            mangaUrl = null,
            title = sample.title,
        )
        return library.firstOrNull { series ->
            val seriesKey = MangaSourceCatalog.identityKeyOrNull(
                sourceId = series.sourceId,
                mangaUrl = series.mangaUrl,
                title = series.title,
            )
            val seriesTitleKey = MangaSourceCatalog.identityKeyOrNull(
                sourceId = series.sourceId,
                mangaUrl = null,
                title = series.title,
            )
            seriesKey == sampleKey || seriesTitleKey == sampleTitleKey
        } ?: library.firstOrNull()
    }
}
