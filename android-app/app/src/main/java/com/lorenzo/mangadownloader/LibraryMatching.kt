package com.lorenzo.mangadownloader

/**
 * Logica di dominio (non UI) per associare i metadati di una serie/capitolo alla libreria
 * scaricata: matching per identità (sourceId+URL) con fallback sul titolo. Sta accanto a
 * [MangaSourceCatalog] ed è pura → testabile senza Android/Robolectric.
 */
object LibraryMatching {

    /**
     * Chiavi dei capitoli già scaricati per la serie [details]: l'id stabile del capitolo
     * più una chiave per-numero (`number:<label>`, con `@<variante>` in coda quando il
     * capitolo appartiene a un gruppo secondario), così il dettaglio può marcare come
     * "scaricato" anche capitoli con URL diverso ma stesso numero, senza però confondere
     * fra loro due omonimi della stessa fonte.
     */
    fun downloadedChapterKeys(
        details: MangaDetails,
        library: List<DownloadedSeries>,
        extraBindings: List<SeriesSourceBinding> = emptyList(),
    ): Set<String> {
        val matchingSeries = matchingDownloadedSeries(details, library, extraBindings) ?: return emptySet()
        return buildSet {
            matchingSeries.chapters.forEach { chapter ->
                add(chapter.chapterId)
                add(DownloadStorage.chapterNumberKey(chapter.numberText, chapter.variantTag))
            }
        }
    }

    /** Id dei capitoli letti per la serie [details] (sia da `readChapterIds` sia dai capitoli `isRead`). */
    fun downloadedReadChapterIds(
        details: MangaDetails,
        library: List<DownloadedSeries>,
        extraBindings: List<SeriesSourceBinding> = emptyList(),
    ): Set<String> {
        return matchingDownloadedSeries(details, library, extraBindings)?.let { series ->
            buildSet {
                addAll(series.readChapterIds)
                series.chapters
                    .filter { it.isRead }
                    .mapTo(this) { it.chapterId }
            }
        }.orEmpty()
    }

    /**
     * La copia scaricata di [chapter] per la serie [details], se c'è. Serve al dettaglio per
     * decidere *come* aprire un capitolo: con un file in libreria si legge quello, senza si
     * va in streaming. Il match segue le stesse due chiavi di [downloadedChapterKeys] (id
     * stabile, poi numero+variante), così un capitolo marcato "scaricato" nella lista è
     * sempre anche un capitolo che qui si riesce a risolvere.
     */
    fun downloadedChapterFor(
        details: MangaDetails,
        chapter: ChapterEntry,
        library: List<DownloadedSeries>,
        extraBindings: List<SeriesSourceBinding> = emptyList(),
    ): DownloadedChapter? {
        val series = matchingDownloadedSeries(details, library, extraBindings) ?: return null
        val stableId = DownloadStorage.stableChapterId(chapter)
        val numberKey = DownloadStorage.chapterNumberKey(chapter.displayNumber(), chapter.variantTag)
        return series.chapters.firstOrNull { it.chapterId == stableId }
            ?: series.chapters.firstOrNull { downloaded ->
                DownloadStorage.chapterNumberKey(downloaded.numberText, downloaded.variantTag) == numberKey
            }
    }

    /**
     * La serie scaricata che corrisponde a [details] per identità o titolo, se presente.
     * [extraBindings] sono i binding del SeriesLink (serie multi-fonte): una serie scaricata
     * da QUALUNQUE fonte collegata matcha, anche con titolo/URL diversi da quelli attivi.
     */
    fun matchingDownloadedSeries(
        details: MangaDetails,
        library: List<DownloadedSeries>,
        extraBindings: List<SeriesSourceBinding> = emptyList(),
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
            extraBindings.forEach { binding ->
                add(MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl))
            }
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
