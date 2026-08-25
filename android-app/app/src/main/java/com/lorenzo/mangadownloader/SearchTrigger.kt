package com.lorenzo.mangadownloader

/**
 * Ciò che definisce una ricerca aggregata. È la chiave osservata dal ViewModel: finché non
 * cambia, la ricerca non viene rifatta (il flow la filtra con `distinctUntilChanged`, così una
 * raffica di modifiche collassa in un solo fetch).
 *
 * [requestId] esiste perché due richieste possono coincidere in tutto il resto e volere
 * comunque due ricerche distinte. Il caso reale: si tocca un consigliato, si atterra in Cerca,
 * si torna indietro e si ritocca **lo stesso** consigliato. La seconda volta i risultati
 * vengono svuotati e lo spinner riacceso, quindi il fetch *deve* ripartire — ma query, ambito
 * e fonti attive sono identici, quindi senza [requestId] il flow scartava il giro e lo spinner
 * restava acceso per sempre.
 */
internal data class SearchTrigger(
    val query: String,
    val scope: SearchScope,
    val disabledSourceIds: Set<String>,
    val requestId: Int,
)

/**
 * Il [SearchTrigger] corrispondente a questo stato. Estratta apposta dal ViewModel per poterla
 * verificare senza rete né looper.
 */
internal fun MangaUiState.searchTrigger(): SearchTrigger = SearchTrigger(
    query = query.trim(),
    scope = settings.searchScope,
    disabledSourceIds = settings.disabledSourceIds,
    requestId = searchRequestId,
)
