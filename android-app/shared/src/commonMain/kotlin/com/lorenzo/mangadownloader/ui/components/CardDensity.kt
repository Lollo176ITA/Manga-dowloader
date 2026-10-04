package com.lorenzo.mangadownloader.ui.components

/**
 * Le **card manga condivise** dell'app: un poster ([MangaPosterCard]) e una riga con
 * mini-copertina ([MangaRowCard]). Ogni schermata le compone via slot (badge, trailing)
 * invece di duplicare layout quasi uguali: una modifica qui arriva ovunque.
 *
 * Le dimensioni derivano dalla **densità globale** ([CardDensity], impostazione stile tema)
 * fornita via [LocalCardDensity] alla radice dell'app: niente parametri-taglia da infilare
 * in ogni call site.
 */
enum class CardDensity(val label: String) {
    COMPACT("Compatta"),
    NORMAL("Normale"),
    LARGE("Grande"),
}
