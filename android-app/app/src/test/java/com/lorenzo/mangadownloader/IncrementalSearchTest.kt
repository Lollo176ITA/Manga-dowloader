package com.lorenzo.mangadownloader

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il fan-out della ricerca: chi risponde pubblica subito, chi non risponde non blocca gli
 * altri. È la parte che il guasto di VyManga (522 dopo venti secondi) ha reso necessaria.
 *
 * Il budget nei test è di millisecondi, non i dodici secondi veri: quello che si verifica è
 * il comportamento al suo scadere, non il valore.
 */
class IncrementalSearchTest {

    /** Scope delle richieste "vere", separato dal fan-out come nell'app (là è il ViewModel). */
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        detached.cancel()
    }

    private fun result(sourceId: String) = MangaSearchResult(
        sourceId = sourceId,
        title = "Berserk",
        mangaUrl = "https://$sourceId.example/berserk",
        coverUrl = null,
    )

    @Test
    fun laFonteVeloceNonAspettaQuellaLenta() = runBlocking {
        val done = Collections.synchronizedList(mutableListOf<String>())
        val lenta = CompletableDeferred<List<MangaSearchResult>>()
        val veloceArrivata = CompletableDeferred<Unit>()

        val fanOut = launch(Dispatchers.Default) {
            searchSourcesIncrementally(
                sourceIds = listOf("lenta", "veloce"),
                detachedScope = detached,
                budgetMillis = 5_000L,
                search = { id -> if (id == "lenta") lenta.await() else listOf(result(id)) },
                onSourceDone = { id, _ ->
                    done += id
                    if (id == "veloce") veloceArrivata.complete(Unit)
                },
            )
        }

        // La veloce ha già consegnato mentre la lenta è ancora appesa: è tutto il punto.
        veloceArrivata.await()
        assertEquals(listOf("veloce"), done.toList())

        lenta.complete(listOf(result("lenta")))
        fanOut.join()
        assertEquals(listOf("veloce", "lenta"), done.toList())
    }

    @Test
    fun oltreIlBudgetLaFonteVienePersaEIlGiroFinisce() = runBlocking {
        val outcomes = Collections.synchronizedMap(mutableMapOf<String, Result<List<MangaSearchResult>>>())

        val elapsed = System.nanoTime()
        searchSourcesIncrementally(
            sourceIds = listOf("morta", "viva"),
            detachedScope = detached,
            budgetMillis = 100L,
            search = { id ->
                // "morta" è il sito che risponde molto dopo che non interessa più.
                if (id == "morta") delay(30_000L)
                listOf(result(id))
            },
            onSourceDone = { id, outcome -> outcomes[id] = outcome },
        )
        val elapsedMillis = (System.nanoTime() - elapsed) / 1_000_000

        assertEquals(listOf(result("viva")), outcomes["viva"]?.getOrNull())
        assertTrue(
            "la fonte fuori budget deve risultare fallita, non vuota",
            outcomes["morta"]?.exceptionOrNull() is SocketTimeoutException,
        )
        assertTrue(
            "il giro deve finire al budget, non quando la fonte si degna di rispondere " +
                "(impiegati ${elapsedMillis}ms)",
            elapsedMillis < 5_000,
        )
    }

    @Test
    fun ilFallimentoDiUnaFonteArrivaAlChiamanteSenzaFermareLeAltre() = runBlocking {
        val outcomes = Collections.synchronizedMap(mutableMapOf<String, Result<List<MangaSearchResult>>>())

        searchSourcesIncrementally(
            sourceIds = listOf("rotta", "viva"),
            detachedScope = detached,
            budgetMillis = 5_000L,
            search = { id ->
                if (id == "rotta") throw IOException("HTTP 522 su https://rotta.example/search")
                listOf(result(id))
            },
            onSourceDone = { id, outcome -> outcomes[id] = outcome },
        )

        assertEquals(listOf(result("viva")), outcomes["viva"]?.getOrNull())
        assertTrue(
            "il chiamante deve poter dire quale fonte ha fallito e perché",
            outcomes["rotta"]?.exceptionOrNull() is IOException,
        )
    }

    @Test
    fun ogniFonteRiferisceUnaVoltaSola() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())

        searchSourcesIncrementally(
            sourceIds = MangaSourceCatalog.descriptors.map { it.id },
            detachedScope = detached,
            budgetMillis = 5_000L,
            search = { emptyList() },
            onSourceDone = { id, _ -> calls += id },
        )

        assertEquals(MangaSourceCatalog.descriptors.size, calls.size)
        assertEquals("nessuna fonte deve pubblicare due volte", calls.toSet().size, calls.size)
    }
}
