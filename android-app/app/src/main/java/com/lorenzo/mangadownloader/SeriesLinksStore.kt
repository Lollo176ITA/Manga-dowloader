package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/** Un aggancio serie→fonte: dove trovare questa serie su un certo server. */
data class SeriesSourceBinding(
    val sourceId: String,
    val mangaUrl: String,
    val addedAt: Long = 0L,
)

/**
 * Il legame persistito tra una serie canonica ([SeriesIdentity]) e le fonti su cui è stata
 * trovata. [preferredSourceId] è l'ultima fonte scelta nel selettore della scheda.
 */
data class SeriesLink(
    val seriesKey: String,
    val aniListId: Int?,
    val canonicalTitle: String,
    val coverUrl: String?,
    val sources: List<SeriesSourceBinding>,
    val preferredSourceId: String? = null,
)

/** Fonte iniziale all'apertura della scheda: preferita → coerente con lo scope → prima. */
fun SeriesLink.initialBinding(scope: SearchScope): SeriesSourceBinding {
    preferredSourceId?.let { preferred ->
        sources.firstOrNull { it.sourceId == preferred }?.let { return it }
    }
    scope.language?.let { language ->
        sources.firstOrNull { MangaSourceCatalog.languageOf(it.sourceId) == language }?.let { return it }
    }
    return sources.first()
}

/**
 * Persistenza dei [SeriesLink] su [SharedPreferences] (pattern degli altri store). Le
 * scritture avvengono solo su azioni esplicite (tap su card raggruppata, cambio fonte,
 * aggancio/scollegamento manuale), mai durante la digitazione in ricerca.
 */
class SeriesLinksStore(private val prefs: SharedPreferences) {

    fun readAll(): Map<String, SeriesLink> {
        return prefs.readJson<Map<String, LinkJson>>(KEY_SERIES_LINKS, emptyMap())
            .filterValues { it.sources.isNotEmpty() && it.seriesKey.isNotBlank() }
            .mapValues { (_, entry) ->
                SeriesLink(
                    seriesKey = entry.seriesKey,
                    aniListId = entry.aniListId,
                    canonicalTitle = entry.canonicalTitle,
                    coverUrl = entry.coverUrl,
                    sources = entry.sources.map { SeriesSourceBinding(it.sourceId, it.mangaUrl, it.addedAt) },
                    preferredSourceId = entry.preferredSourceId,
                )
            }
    }

    fun linkFor(seriesKey: String): SeriesLink? = readAll()[seriesKey]

    /** Il link che contiene il binding (sourceId, url), confrontando l'URL normalizzato. */
    fun linkForBinding(sourceId: String, mangaUrl: String): SeriesLink? {
        val targetKey = MangaSourceCatalog.identityKey(sourceId, mangaUrl)
        return readAll().values.firstOrNull { link ->
            link.sources.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == targetKey }
        }
    }

    /**
     * Chiave serie per una tripla (fonte, url, titolo): la chiave del link esistente se il
     * binding è già noto, altrimenti la chiave-titolo; ultima spiaggia l'identityKey legacy.
     */
    fun seriesKeyFor(sourceId: String, mangaUrl: String, title: String): String {
        linkForBinding(sourceId, mangaUrl)?.let { return it.seriesKey }
        return SeriesIdentity.keyForTitle(title)
            ?: MangaSourceCatalog.identityKey(sourceId, mangaUrl)
    }

    fun upsert(link: SeriesLink) {
        persistAll(readAll() + (link.seriesKey to link))
    }

    /**
     * Crea/aggiorna il link a partire da una card raggruppata tappata: unione dei binding
     * (dedup per identityKey), preferred conservato. Se il gruppo ha un aniListId e i suoi
     * binding vivevano sotto una chiave `title:`, quel link viene PROMOSSO (ri-chiavato).
     */
    fun mergeFromGroup(group: GroupedSearchResult, now: Long): SeriesLink {
        val all = readAll().toMutableMap()
        val existing = all[group.seriesKey]
            ?: group.results.firstNotNullOfOrNull { result ->
                // Promozione title: → anilist:: il vecchio link viene assorbito.
                all.values.firstOrNull { candidate ->
                    candidate.seriesKey.startsWith(SeriesIdentity.TITLE_PREFIX) &&
                        candidate.sources.any {
                            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) ==
                                MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
                        }
                }
            }?.also { all.remove(it.seriesKey) }

        val mergedSources = LinkedHashMap<String, SeriesSourceBinding>()
        existing?.sources?.forEach {
            mergedSources[MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl)] = it
        }
        group.results.forEach { result ->
            val key = MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
            mergedSources.putIfAbsent(key, SeriesSourceBinding(result.sourceId, result.mangaUrl, now))
        }

        val merged = SeriesLink(
            seriesKey = group.seriesKey,
            aniListId = group.aniListId ?: existing?.aniListId,
            canonicalTitle = group.title,
            coverUrl = group.coverUrl ?: existing?.coverUrl,
            sources = mergedSources.values.toList(),
            preferredSourceId = existing?.preferredSourceId,
        )
        all[group.seriesKey] = merged
        persistAll(all)
        return merged
    }

    /** Promuove un link `title:` ad `anilist:` (aggancio tracking arrivato dopo). */
    fun promoteToAniList(titleKey: String, aniListId: Int): SeriesLink? {
        val all = readAll().toMutableMap()
        val link = all.remove(titleKey) ?: return null
        val promoted = link.copy(
            seriesKey = SeriesIdentity.keyForAniList(aniListId),
            aniListId = aniListId,
        )
        all[promoted.seriesKey] = promoted
        persistAll(all)
        return promoted
    }

    fun setPreferredSource(seriesKey: String, sourceId: String) {
        val link = linkFor(seriesKey) ?: return
        upsert(link.copy(preferredSourceId = sourceId))
    }

    fun addBinding(seriesKey: String, binding: SeriesSourceBinding) {
        val link = linkFor(seriesKey) ?: return
        val key = MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl)
        if (link.sources.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == key }) return
        upsert(link.copy(sources = link.sources + binding))
    }

    fun removeBinding(seriesKey: String, sourceId: String) {
        val link = linkFor(seriesKey) ?: return
        val remaining = link.sources.filterNot { it.sourceId == sourceId }
        if (remaining.isEmpty() || remaining.size == link.sources.size) return
        upsert(link.copy(sources = remaining))
    }

    private fun persistAll(links: Map<String, SeriesLink>) {
        val payload = links.mapValues { (_, link) ->
            LinkJson(
                seriesKey = link.seriesKey,
                aniListId = link.aniListId,
                canonicalTitle = link.canonicalTitle,
                coverUrl = link.coverUrl,
                sources = link.sources.map { BindingJson(it.sourceId, it.mangaUrl, it.addedAt) },
                preferredSourceId = link.preferredSourceId,
            )
        }
        prefs.writeJson(KEY_SERIES_LINKS, payload)
    }

    /** Forma su disco di un binding serie→fonte. */
    @Serializable
    private data class BindingJson(
        val sourceId: String = "",
        val mangaUrl: String = "",
        val addedAt: Long = 0L,
    )

    /** Forma su disco di un legame serie→fonti. */
    @Serializable
    private data class LinkJson(
        val seriesKey: String = "",
        val aniListId: Int? = null,
        val canonicalTitle: String = "",
        val coverUrl: String? = null,
        val sources: List<BindingJson> = emptyList(),
        val preferredSourceId: String? = null,
    )

    private companion object {
        const val KEY_SERIES_LINKS = "series_links_json"
    }
}
