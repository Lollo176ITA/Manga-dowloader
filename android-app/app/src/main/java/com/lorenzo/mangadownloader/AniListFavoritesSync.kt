package com.lorenzo.mangadownloader

import android.content.SharedPreferences

/**
 * Sincronizzazione dei preferiti tra l'app e i **favourites** di AniList (che su AniList sono
 * una cosa a sé, distinta dalla lista di lettura usata dal tracking).
 *
 * La semantica è di **unione**: ciò che manca da una parte viene aggiunto all'altra e niente
 * viene mai rimosso. La scelta non è timidezza — è che le due sponde non sono simmetriche.
 * Un preferito dell'app esiste solo se una fonte reale ha quella serie, quindi l'assenza da un
 * lato non prova mai una volontà di rimozione: può essere un titolo che nessuna fonte espone,
 * un match non riuscito, o semplicemente un giro andato storto per la rete.
 *
 * Il punto delicato è distinguere "nuovo" da "rimosso apposta". Senza memoria, un preferito
 * cancellato in app tornerebbe dentro al giro successivo — è ancora tra i favourites — e
 * resusciterebbe per sempre. Per questo [AniListFavoritesSyncStore] ricorda gli id già
 * riconciliati: da lì in poi una sparizione da un lato è una scelta dell'utente e va rispettata.
 */

/**
 * Cosa fare in un giro di riconciliazione. Gli insiemi contengono id di media AniList: sono
 * l'unico ancoraggio comune alle due sponde (i preferiti dell'app li hanno nella chiave
 * canonica `anilist:<id>`, vedi [SeriesIdentity]).
 */
data class AniListFavoritesSyncPlan(
    /** Presenti tra i preferiti dell'app, mai riconciliati, assenti dai favourites AniList. */
    val toPushToAniList: List<Int>,
    /** Presenti tra i favourites AniList, mai riconciliati, assenti dai preferiti dell'app. */
    val toImportInApp: List<Int>,
)

/**
 * Quanti preferiti importare al massimo per giro. Ogni import costa una ricerca su tutte le
 * fonti attive: tenerlo basso evita che un primo collegamento con cento favourites blocchi
 * l'app (o martelli le fonti). Quello che avanza viene ripreso al giro dopo.
 */
const val MAX_ANILIST_FAVORITE_IMPORTS_PER_RUN = 5

/**
 * Quanti preferiti spingere su AniList al massimo per giro. Costa una mutation ciascuno, molto
 * meno di un import, quindi il tetto è più alto.
 */
const val MAX_ANILIST_FAVORITE_PUSHES_PER_RUN = 20

/**
 * Il piano di riconciliazione. Pura: tutta la logica di "nuovo vs rimosso apposta" sta qui,
 * verificabile senza rete né SharedPreferences.
 *
 * @param appMediaIds id AniList dei preferiti dell'app (solo quelli già agganciati ad AniList)
 * @param aniListMediaIds id dei favourites sull'account
 * @param alreadyReconciled id già visti da un giro precedente: una loro assenza da un lato è
 *   una rimozione voluta, non una novità da propagare
 * @param failedImports id che nessuna fonte espone: inutile ricercarli a ogni giro
 */
fun planAniListFavoritesSync(
    appMediaIds: Set<Int>,
    aniListMediaIds: Set<Int>,
    alreadyReconciled: Set<Int>,
    failedImports: Set<Int> = emptySet(),
): AniListFavoritesSyncPlan = AniListFavoritesSyncPlan(
    toPushToAniList = (appMediaIds - aniListMediaIds - alreadyReconciled)
        .sorted()
        .take(MAX_ANILIST_FAVORITE_PUSHES_PER_RUN),
    toImportInApp = (aniListMediaIds - appMediaIds - alreadyReconciled - failedImports)
        .sorted()
        .take(MAX_ANILIST_FAVORITE_IMPORTS_PER_RUN),
)

/**
 * Gli id da considerare riconciliati dopo un giro. Ci finiscono solo quelli su cui il giro ha
 * davvero concluso qualcosa: chi era già presente da entrambe le parti e chi è stato propagato
 * con successo. Un'operazione fallita (rete giù, fonte irraggiungibile) resta fuori e viene
 * ritentata, invece di essere archiviata come "fatto" senza esserlo.
 */
fun reconciledAniListFavoriteIds(
    alreadyReconciled: Set<Int>,
    appMediaIds: Set<Int>,
    aniListMediaIds: Set<Int>,
    succeeded: Set<Int>,
): Set<Int> = alreadyReconciled + (appMediaIds intersect aniListMediaIds) + succeeded

/**
 * Il risultato di ricerca che corrisponde con certezza a [media]: titolo normalizzato identico
 * a uno dei titoli o sinonimi noti ad AniList. Nessun fuzzy, per la stessa ragione di
 * [matchAniListCandidate]: importare la serie sbagliata tra i preferiti è peggio che non
 * importarla affatto.
 */
fun matchSourceResultForAniList(
    media: AniListManga,
    results: List<MangaSearchResult>,
): MangaSearchResult? {
    val wanted = media.allTitles()
        .map(SeriesIdentity::normalizeTitle)
        .filter(String::isNotBlank)
        .toSet()
    if (wanted.isEmpty()) return null
    return results.firstOrNull { SeriesIdentity.normalizeTitle(it.title) in wanted }
}

/**
 * I titoli con cui cercare [media] sulle fonti, al massimo due: l'inglese (quello che i siti
 * EN usano di solito) e il romaji. Il confronto poi avviene su tutti i titoli noti, quindi
 * aggiungere altre query renderebbe la ricerca più lenta senza allargare i match.
 */
fun aniListImportSearchQueries(media: AniListManga): List<String> =
    listOfNotNull(media.titleEnglish, media.titleRomaji)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

/**
 * Memoria della riconciliazione su [SharedPreferences], nello stile degli altri store.
 *
 * Non è una cache: senza questi due insiemi il sync non saprebbe distinguere un preferito
 * nuovo da uno che l'utente ha tolto, e li farebbe risorgere a ogni giro.
 */
class AniListFavoritesSyncStore(private val prefs: SharedPreferences) {

    /** Id già riconciliati almeno una volta tra le due sponde. */
    fun readReconciledIds(): Set<Int> =
        prefs.readJson<List<Int>>(KEY_RECONCILED, emptyList()).toSet()

    fun writeReconciledIds(ids: Set<Int>) {
        prefs.writeJson(KEY_RECONCILED, ids.sorted().takeLast(MAX_TRACKED_IDS))
    }

    /**
     * Favourites AniList che nessuna fonte attiva espone. Senza questo elenco verrebbero
     * ricercati su tutte le fonti a ogni giro, per sempre, senza mai riuscire.
     */
    fun readFailedImports(): Set<Int> =
        prefs.readJson<List<Int>>(KEY_FAILED_IMPORTS, emptyList()).toSet()

    fun writeFailedImports(ids: Set<Int>) {
        prefs.writeJson(KEY_FAILED_IMPORTS, ids.sorted().takeLast(MAX_TRACKED_IDS))
    }

    /** Dimentica tutto: il prossimo giro riparte come un primo collegamento. */
    fun clear() {
        writeReconciledIds(emptySet())
        writeFailedImports(emptySet())
    }

    private companion object {
        const val KEY_RECONCILED = "anilist_favorites_reconciled_ids"
        const val KEY_FAILED_IMPORTS = "anilist_favorites_failed_imports"

        // Tetto di guardia: gli insiemi crescono con i preferiti, non con il tempo.
        const val MAX_TRACKED_IDS = 2_000
    }
}
