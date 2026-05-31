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
}

/**
 * Schermata in primo piano derivata dallo stato. L'ordine codifica lo "stack" implicito:
 * il reader sta sopra tutto, poi la gestione memoria (sopra le impostazioni), le impostazioni,
 * il dettaglio, la serie scaricata (solo nella tab Libreria) e infine le tab.
 */
fun MangaUiState.currentScreen(): Screen = when {
    readerChapter != null -> Screen.Reader
    showStorageManager -> Screen.StorageManager
    showSettings -> Screen.Settings
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
