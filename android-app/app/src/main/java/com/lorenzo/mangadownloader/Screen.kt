package com.lorenzo.mangadownloader

/**
 * Schermata attualmente in primo piano. Prima la navigazione era implicita nell'ordine di
 * un `when` con booleani/nullable sparsi (`showSettings`, `selected`, `readerChapter`, …)
 * replicato in 4 punti (rendering, BackHandler, canHandleBack, showPager).
 *
 * Qui la gerarchia è codificata **una volta sola** in [currentScreen]: i campi di stato
 * restano l'unica fonte di verità (niente stack parallelo da tenere in sync), ma la
 * decisione di *quale* schermata mostrare e di *cosa fa il back* diventa un tipo sigillato
 * esaustivo e testabile. [Screen.Tabs] è la root (il pager Cerca/Preferiti/Libreria).
 */
sealed interface Screen {
    data object Tabs : Screen
    data object Detail : Screen
    data object DownloadedSeries : Screen
    data object Reader : Screen
    data object Settings : Screen
    data object StorageManager : Screen
    data object Backup : Screen
    data object Changelog : Screen
    data object Updates : Screen
}

/**
 * Schermata in primo piano derivata dallo stato. L'ordine codifica lo "stack" implicito:
 * il reader sta sopra tutto, poi la gestione memoria (sopra le impostazioni), le impostazioni,
 * il dettaglio, la serie scaricata (solo nella tab Libreria) e infine le tab.
 */
fun MangaUiState.currentScreen(): Screen = when {
    readerChapter != null -> Screen.Reader
    showStorageManager -> Screen.StorageManager
    showBackup -> Screen.Backup
    showChangelog -> Screen.Changelog
    showSettings -> Screen.Settings
    showUpdates -> Screen.Updates
    selected != null -> Screen.Detail
    currentTab == AppTab.LIBRARY && selectedDownloadedSeries != null -> Screen.DownloadedSeries
    else -> Screen.Tabs
}

/** C'è una schermata da chiudere col tasto "indietro"? (cioè non siamo già sulle tab). */
fun MangaUiState.canHandleBack(): Boolean = currentScreen() != Screen.Tabs

/**
 * Tab effettivamente mostrate nella bottom bar / pager, nell'ordine. La tab [AppTab.DISCOVERY]
 * (vetrina AniList) compare solo se attivata dal flag nelle impostazioni — di default è nascosta.
 * Il resto delle tab è sempre presente.
 */
fun MangaUiState.visibleTabs(): List<AppTab> =
    AppTab.entries.filter { it != AppTab.DISCOVERY || settings.discoveryEnabled }

/** Indice della tab tra quelle visibili (per il pager), o 0 se non visibile. */
fun MangaUiState.tabPageIndex(tab: AppTab): Int =
    visibleTabs().indexOf(tab).coerceAtLeast(0)

/**
 * Chiave per il [androidx.compose.runtime.saveable.SaveableStateHolder] che avvolge la
 * schermata in primo piano: identifica *quale* stato salvabile (in primis la posizione di
 * scroll) ripristinare quando si esce e si rientra in una schermata. Cambiare schermata
 * smonta del tutto la precedente dalla composizione — senza questa chiave lo scroll si
 * azzererebbe a ogni andata e ritorno (es. Impostazioni → Gestisci memoria → indietro).
 *
 * [Screen.Detail] e [Screen.DownloadedSeries] sono qualificate dal contenuto aperto (manga /
 * serie) così due elementi diversi non si scambiano la posizione di scroll; per il resto basta
 * il tipo di schermata. Il reader resta a chiave stabile: la sua posizione la ripristina già
 * il ViewModel via `initialPageIndex`, e una chiave per-capitolo romperebbe la transizione.
 */
fun MangaUiState.saveableScreenKey(): String = when (currentScreen()) {
    Screen.Detail -> "Detail:${selected?.mangaUrl.orEmpty()}"
    Screen.DownloadedSeries -> "DownloadedSeries:${selectedDownloadedSeries?.directory?.absolutePath.orEmpty()}"
    else -> currentScreen().toString()
}
