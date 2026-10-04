package com.lorenzo.mangadownloader.ui.reader

/** Come trattare una pagina doppia nel reader. */
enum class SpreadPageMode(val menuLabel: String, val shortLabel: String) {
    /** Comportamento storico: la pagina resta intera e viene rimpicciolita per starci. */
    FIT("Adatta allo schermo", "Adatta"),

    /** La pagina diventa due mezze pagine, nell'ordine di lettura giusto. */
    SPLIT("Dividi in due", "Dividi"),

    /** La pagina resta intera ma ruotata di 90°: si legge girando il telefono. */
    ROTATE("Ruota di lato", "Ruota"),
}
