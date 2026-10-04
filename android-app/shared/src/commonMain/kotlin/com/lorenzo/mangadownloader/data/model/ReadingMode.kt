package com.lorenzo.mangadownloader.data.model

import com.lorenzo.mangadownloader.ui.reader.SpreadRotation

/**
 * Come vengono sfogliate le pagine nel reader.
 * - [VERTICAL]: scroll verticale continuo (webtoon), modalità storica.
 * - [PAGED]: una pagina per volta, si sfoglia da sinistra a destra (occidentale).
 * - [PAGED_RTL]: come [PAGED] ma da destra a sinistra, il senso di lettura dei manga.
 */
enum class ReadingMode(val menuLabel: String, val shortLabel: String) {
    VERTICAL("Scroll verticale", "Verticale"),
    PAGED("A pagine", "Pagine"),
    PAGED_RTL("A pagine (da destra)", "Manga");

    /** Vero per entrambe le modalità a pagine (occidentale e manga). */
    val isPaged: Boolean get() = this == PAGED || this == PAGED_RTL

    /** Vero solo per la modalità manga: lo swipe e l'ordine pagine vanno da destra a sinistra. */
    val isRightToLeft: Boolean get() = this == PAGED_RTL

    /**
     * In quale ordine mostrare le due metà di una pagina doppia divisa: `true` per l'ordine
     * di lettura dei manga, prima la metà destra.
     *
     * Vale ovunque tranne che in [PAGED], l'unica modalità in cui l'utente ha dichiarato di
     * leggere da sinistra. Anche nello scroll verticale, quindi: le pagine doppie arrivano
     * dai volumi manga, mentre le strisce webtoon — l'altro contenuto tipico di quella
     * modalità — non ne producono mai.
     */
    val splitsSpreadRightFirst: Boolean get() = this != PAGED

    /**
     * Verso in cui ruotare una pagina doppia lasciata intera (vedi [SpreadRotation]). Stessa
     * regola di [splitsSpreadRightFirst]: la facciata da leggere per prima deve finire in
     * alto, così scorrendo verso il basso l'ordine resta quello giusto.
     */
    val spreadRotation: SpreadRotation
        get() = if (splitsSpreadRightFirst) {
            SpreadRotation.COUNTER_CLOCKWISE
        } else {
            SpreadRotation.CLOCKWISE
        }
}
