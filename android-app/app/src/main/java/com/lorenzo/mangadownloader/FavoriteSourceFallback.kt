package com.lorenzo.mangadownloader

import kotlinx.coroutines.CancellationException

/**
 * Scelta del mirror da cui leggere una serie preferita, con ripiego sulle alternative.
 *
 * Il preferito è la serie; la fonte è solo il posto da cui la si scarica in questo momento.
 * Quando quel posto è irraggiungibile (sito giù, dominio cambiato, capitolo non ancora
 * pubblicato lì) si prova il mirror successivo invece di lasciare il preferito muto.
 */

/** Mirror da provare per un preferito, in ordine: preferito → altri agganciati. Pura. */
fun favoriteSourceCandidates(
    favorite: FavoriteManga,
    link: SeriesLink?,
    disabledSourceIds: Set<String> = emptySet(),
    maxCandidates: Int = MAX_SOURCE_CANDIDATES,
): List<SeriesSourceBinding> {
    val current = SeriesSourceBinding(favorite.sourceId, favorite.mangaUrl, favorite.addedAt)
    val fromLink = link?.candidateBindings(maxCandidates = Int.MAX_VALUE).orEmpty()
    // Il binding corrente del preferito va comunque provato: un link può non conoscerlo
    // ancora (preferito aggiunto prima che la serie fosse agganciata altrove).
    val currentKey = MangaSourceCatalog.identityKey(current.sourceId, current.mangaUrl)
    val all = if (fromLink.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == currentKey }) {
        fromLink
    } else {
        listOf(current) + fromLink
    }
    // Le fonti spente in impostazioni non si interrogano — a meno che non resti nulla:
    // meglio un tentativo su una fonte spenta che un preferito senza alcun candidato.
    return all
        .filterNot { it.sourceId in disabledSourceIds }
        // Una fonte sola per tentativo: un link può contenere due URL dello stesso sito
        // (schema cambiato, pagina morta) e spenderci tutto il budget significherebbe non
        // provare mai le fonti *diverse*, che è lo scopo del fallback.
        .distinctBy { it.sourceId }
        .ifEmpty { listOf(current) }
        .take(maxCandidates.coerceAtLeast(1))
}

/** Il mirror che ha risposto e i dettagli che ha restituito. */
data class FavoriteFetchSuccess(
    val binding: SeriesSourceBinding,
    val details: MangaDetails,
)

/**
 * Prova i [candidates] in ordine e si ferma al **primo** che risponde: nel caso normale è una
 * sola richiesta di rete: le alternative costano solo quando la prima fallisce davvero.
 * Restituisce `null` se hanno fallito tutti.
 *
 * [fetch] è iniettato apposta: i test coprono l'ordine dei tentativi e la promozione senza
 * toccare la rete.
 */
suspend fun fetchFromFirstAvailable(
    candidates: List<SeriesSourceBinding>,
    fetch: suspend (SeriesSourceBinding) -> MangaDetails,
): FavoriteFetchSuccess? {
    for (candidate in candidates) {
        try {
            return FavoriteFetchSuccess(candidate, fetch(candidate))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Mirror non raggiungibile: si passa al prossimo.
        }
    }
    return null
}
