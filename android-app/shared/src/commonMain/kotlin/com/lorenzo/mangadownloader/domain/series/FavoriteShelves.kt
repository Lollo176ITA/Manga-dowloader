package com.lorenzo.mangadownloader.domain.series

import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.model.canonicalKey
import com.lorenzo.mangadownloader.data.model.matchKeys
import kotlinx.serialization.Serializable

/**
 * Scaffali dei preferiti scelti dall'utente ("Da rileggere", "Per le vacanze"…). Si affiancano
 * agli stati di lettura automatici ([FavoriteReadingState]), che restano il filtro principale:
 * uno scaffale è solo un'etichetta, e un preferito può stare su più scaffali o su nessuno.
 */
@Serializable
data class FavoriteShelf(
    val id: String,
    val name: String,
)

/**
 * Scaffali e appartenenze. Le appartenenze sono indicizzate per chiave del preferito, ma si
 * leggono su **tutti** i suoi alias ([matchKeys]): quando il worker promuove un preferito da
 * `title:` ad `anilist:` la chiave cambia, e l'alias sul titolo continua a ritrovarlo senza
 * dover migrare questo store. Le chiavi di preferiti rimossi restano: la rimozione si annulla
 * dallo snackbar, e un preferito ripristinato ritrova i suoi scaffali. Tutto puro.
 */
@Serializable
data class FavoriteShelves(
    val shelves: List<FavoriteShelf> = emptyList(),
    val assignments: Map<String, Set<String>> = emptyMap(),
) {
    fun shelf(id: String): FavoriteShelf? = shelves.firstOrNull { it.id == id }

    /** Gli scaffali del preferito, nell'ordine degli scaffali. */
    fun shelfIdsOf(favorite: FavoriteManga): Set<String> {
        val assigned = favorite.matchKeys().flatMapTo(mutableSetOf()) { assignments[it].orEmpty() }
        return shelves.map(FavoriteShelf::id).filterTo(linkedSetOf()) { it in assigned }
    }

    /** `null` se il nome è vuoto o già usato (senza distinguere maiuscole). */
    fun withNewShelf(name: String, id: String): FavoriteShelves? {
        val cleaned = cleanShelfName(name) ?: return null
        if (hasShelfNamed(cleaned)) return null
        return copy(shelves = shelves + FavoriteShelf(id = id, name = cleaned))
    }

    /** `null` se lo scaffale non esiste o il nome non è valido. Rinominare in sé stesso va bene. */
    fun withRenamedShelf(id: String, name: String): FavoriteShelves? {
        if (shelf(id) == null) return null
        val cleaned = cleanShelfName(name) ?: return null
        if (hasShelfNamed(cleaned, except = id)) return null
        return copy(shelves = shelves.map { if (it.id == id) it.copy(name = cleaned) else it })
    }

    fun withoutShelf(id: String): FavoriteShelves = copy(
        shelves = shelves.filterNot { it.id == id },
        assignments = assignments
            .mapValues { (_, ids) -> ids - id }
            .filterValues(Set<String>::isNotEmpty),
    )

    /**
     * Mette il preferito esattamente sugli scaffali [shelfIds] (quelli inesistenti si
     * ignorano). Le voci sugli alias si consolidano sulla chiave canonica.
     */
    fun withShelvesFor(favorite: FavoriteManga, shelfIds: Set<String>): FavoriteShelves {
        val known = shelves.map(FavoriteShelf::id).toSet()
        val valid = shelfIds.filterTo(linkedSetOf()) { it in known }
        val updated = assignments - favorite.matchKeys()
        return copy(
            assignments = if (valid.isEmpty()) updated else updated + (favorite.canonicalKey() to valid),
        )
    }

    /** Quanti dei [favorites] stanno su ogni scaffale. */
    fun countsIn(favorites: List<FavoriteManga>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        favorites.forEach { favorite ->
            shelfIdsOf(favorite).forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
        }
        return counts
    }

    private fun hasShelfNamed(name: String, except: String? = null): Boolean =
        shelves.any { it.id != except && it.name.equals(name, ignoreCase = true) }
}

const val MAX_SHELF_NAME_LENGTH = 30

/** Nome ripulito (spazi compressi, lunghezza massima) o `null` se vuoto. */
fun cleanShelfName(raw: String): String? =
    raw.trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_SHELF_NAME_LENGTH)
        .trim()
        .takeIf(String::isNotBlank)

/** I preferiti sullo scaffale [shelfId]; `null` = nessun filtro. */
fun filterFavoritesByShelf(
    favorites: List<FavoriteManga>,
    shelfId: String?,
    shelves: FavoriteShelves,
): List<FavoriteManga> {
    if (shelfId == null) return favorites
    return favorites.filter { shelfId in shelves.shelfIdsOf(it) }
}

/**
 * Unione di due insiemi di scaffali (ripristino di un backup in modalità "unisci"). Gli
 * scaffali con lo stesso nome sono lo stesso scaffale: le appartenenze di [incoming] passano
 * sull'id già presente in [current].
 */
fun mergeFavoriteShelves(current: FavoriteShelves, incoming: FavoriteShelves): FavoriteShelves {
    val shelves = current.shelves.toMutableList()
    val idMapping = mutableMapOf<String, String>()
    incoming.shelves.forEach { shelf ->
        val existing = shelves.firstOrNull { it.name.equals(shelf.name, ignoreCase = true) }
        if (existing != null) {
            idMapping[shelf.id] = existing.id
        } else {
            val id = if (shelves.any { it.id == shelf.id }) "${shelf.id}-backup" else shelf.id
            shelves += shelf.copy(id = id)
            idMapping[shelf.id] = id
        }
    }
    val assignments = current.assignments.mapValues { it.value.toMutableSet() }.toMutableMap()
    incoming.assignments.forEach { (key, ids) ->
        val mapped = ids.mapNotNull(idMapping::get)
        if (mapped.isNotEmpty()) assignments.getOrPut(key) { mutableSetOf() } += mapped
    }
    return FavoriteShelves(shelves = shelves, assignments = assignments)
}
