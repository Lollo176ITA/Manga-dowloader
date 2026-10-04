package com.lorenzo.mangadownloader.ui.library

/**
 * Criterio di ordinamento della Libreria, persistito nelle impostazioni (come il sort dei
 * Preferiti). Prima la lista era solo alfabetica, hardcoded.
 */
enum class LibrarySort(val label: String) {
    LAST_READ("Recenti"),
    TITLE_ASC("A-Z"),
    UNREAD_FIRST("Da leggere"),
}
