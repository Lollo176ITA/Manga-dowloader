package com.lorenzo.mangadownloader

/**
 * Generi curati per "Esplora per genere" (blocco Scopri): etichetta italiana mostrata in UI e
 * nome genre dell'API AniList (inglese, passato a `fetchMedia(genre = ...)`). Enum puro (niente
 * Compose) così lo stato UI e i test JVM non dipendono dalla grafica.
 */
enum class DiscoverGenre(val label: String, val apiGenre: String) {
    ACTION("Azione", "Action"),
    ADVENTURE("Avventura", "Adventure"),
    COMEDY("Commedia", "Comedy"),
    DRAMA("Drammatico", "Drama"),
    FANTASY("Fantasy", "Fantasy"),
    HORROR("Horror", "Horror"),
    MYSTERY("Mistero", "Mystery"),
    ROMANCE("Romance", "Romance"),
    SCI_FI("Sci-Fi", "Sci-Fi"),
    SLICE_OF_LIFE("Slice of Life", "Slice of Life"),
    SPORTS("Sport", "Sports"),
    SUPERNATURAL("Soprannaturale", "Supernatural"),
}
