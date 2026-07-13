package com.lorenzo.mangadownloader

/**
 * Blocchi CONTENUTO della Home, riordinabili e nascondibili dall'utente. Saluto, card
 * onboarding ed empty state sono "chrome" e NON fanno parte di questo enum.
 */
enum class HomeBlock { RESUME, FAVORITE_UPDATES, RECENT_FAVORITES, DISCOVER, STATS, HISTORY, TO_FINISH }

/** Ordine consigliato dal brief. È anche l'ordine con cui i blocchi nuovi vengono aggiunti in coda. */
val DEFAULT_HOME_BLOCK_ORDER: List<HomeBlock> =
    listOf(
        HomeBlock.RESUME,
        HomeBlock.FAVORITE_UPDATES,
        HomeBlock.RECENT_FAVORITES,
        HomeBlock.DISCOVER,
        HomeBlock.STATS,
        HomeBlock.HISTORY,
        HomeBlock.TO_FINISH,
    )

/**
 * Riconcilia una lista persistita: rimuove duplicati e APPENDE i blocchi non presenti
 * (nell'ordine di default), così un utente esistente vede i blocchi introdotti in futuro.
 * I nomi ignoti vanno già scartati a monte (decode enum tollerante).
 */
fun reconcileHomeBlocks(stored: List<HomeBlock>): List<HomeBlock> {
    val known = stored.distinct()
    val missing = DEFAULT_HOME_BLOCK_ORDER.filter { it !in known }
    return known + missing
}

/**
 * Sposta [block] di una posizione su/giù rispetto ai soli blocchi VISIBILI: scambia con il vicino
 * visibile, lasciando al loro posto nell'ordine completo i blocchi nascosti dalla vista (es. Scopri
 * sotto controllo parentale). Con [isHiddenFromView] sempre `false` è un semplice scambio tra
 * adiacenti. No-op se [block] è al bordo (tra i visibili) o assente. Puro.
 */
fun moveHomeBlockInOrder(
    order: List<HomeBlock>,
    block: HomeBlock,
    up: Boolean,
    isHiddenFromView: (HomeBlock) -> Boolean,
): List<HomeBlock> {
    val visible = order.filterNot(isHiddenFromView)
    val vi = visible.indexOf(block)
    if (vi < 0) return order
    val target = if (up) vi - 1 else vi + 1
    if (target !in visible.indices) return order
    val neighbor = visible[target]
    val i = order.indexOf(block)
    val j = order.indexOf(neighbor)
    return order.toMutableList().apply {
        this[i] = neighbor
        this[j] = block
    }
}

/** Saluto leggero in base all'ora (0-23). 5-12 mattina, 13-18 pomeriggio, altrimenti sera. */
fun homeGreeting(hourOfDay: Int): String = when (hourOfDay) {
    in 5..12 -> "Buongiorno"
    in 13..18 -> "Buon pomeriggio"
    else -> "Buonasera"
}
