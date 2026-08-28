package com.lorenzo.mangadownloader

import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il fan-out della ricerca aggregata, separato dal ViewModel perché è la parte che può
 * andare storta in modi che un test deve poter riprodurre: fonti che rispondono in ordine
 * sparso, fonti che non rispondono affatto, fonti che rispondono dopo che non interessa più.
 *
 * Due regole, entrambe nate dallo stesso guasto reale (l'origin di VyManga giù, con
 * Cloudflare che rispondeva 522 dopo venti secondi):
 *
 * 1. **Ognuno pubblica appena risponde.** Aspettare tutte le fonti prima di mostrare
 *    qualcosa tiene in ostaggio le sei veloci per colpa della settima.
 * 2. **Ognuno ha un budget.** Oltre [budgetMillis] la fonte è persa per questo giro.
 */
suspend fun searchSourcesIncrementally(
    sourceIds: List<String>,
    /**
     * Scope in cui vive la richiesta vera, **fuori** dalla gerarchia di questo fan-out.
     * Serve perché `searchManga` è bloccante: un `withTimeout` attorno a un blocco che non
     * si interrompe aspetterebbe comunque che finisca, e il budget non varrebbe niente. Così
     * invece la si molla davvero, e la chiamata orfana muore sul timeout di OkHttp.
     */
    detachedScope: CoroutineScope,
    budgetMillis: Long = SOURCE_SEARCH_BUDGET_MILLIS,
    search: suspend (sourceId: String) -> List<MangaSearchResult>,
    /** Chiamato una volta per fonte, nel contesto del chiamante, appena si sa com'è andata. */
    onSourceDone: (sourceId: String, outcome: Result<List<MangaSearchResult>>) -> Unit,
) {
    coroutineScope {
        sourceIds.forEach { sourceId ->
            launch {
                val call: Deferred<Result<List<MangaSearchResult>>> = detachedScope.async {
                    runCatching { search(sourceId) }
                }
                val outcome = withTimeoutOrNull(budgetMillis) { call.await() }
                    ?: run {
                        call.cancel()
                        Result.failure(
                            SocketTimeoutException(
                                "${MangaSourceCatalog.displayName(sourceId)} non ha risposto in tempo",
                            ),
                        )
                    }
                onSourceDone(sourceId, outcome)
            }
        }
    }
}
