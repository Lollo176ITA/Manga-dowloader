package com.lorenzo.mangadownloader

import android.graphics.BitmapFactory
import java.io.File

/**
 * Pagine **doppie**: le facciate affiancate che nei volumi occupano due pagine e che le fonti
 * distribuiscono come un'unica immagine larga. Mostrata intera su un telefono in verticale
 * diventa una striscia schiacciata in cui ogni facciata sta in metà larghezza: leggibile solo
 * a forza di zoom.
 *
 * Qui vive tutta la logica non grafica: riconoscere una pagina doppia dalle sue proporzioni,
 * espandere l'elenco pagine sostituendo la doppia con le sue due metà, e tradurre gli indici
 * di progresso tra elenco espanso ed elenco originale. Pura e testabile: il ritaglio vero
 * avviene solo al momento di disegnare (vedi `SpreadHalfTransformation` nel reader).
 */

/** Come trattare una pagina doppia nel reader. */
enum class SpreadPageMode(val menuLabel: String, val shortLabel: String) {
    /** Comportamento storico: la pagina resta intera e viene rimpicciolita per starci. */
    FIT("Adatta allo schermo", "Adatta"),

    /** La pagina diventa due mezze pagine, nell'ordine di lettura giusto. */
    SPLIT("Dividi in due", "Dividi"),

    /** La pagina resta intera ma ruotata di 90°: si legge girando il telefono. */
    ROTATE("Ruota di lato", "Ruota"),
}

/** Quale metà di una pagina doppia rappresenta una pagina del reader. */
enum class PageHalf { LEFT, RIGHT }

/**
 * Verso in cui ruotare una pagina doppia in [SpreadPageMode.ROTATE]. Decide quale facciata
 * finisce in alto, cioè da quale si comincia: scorrendo verso il basso si legge dall'alto,
 * quindi il verso deve seguire l'ordine di lettura o le due facciate arrivano scambiate.
 */
enum class SpreadRotation(val degrees: Float) {
    /** Facciata sinistra in alto: l'ordine di lettura occidentale. */
    CLOCKWISE(90f),

    /** Facciata destra in alto: l'ordine di lettura dei manga. */
    COUNTER_CLOCKWISE(-90f),
}

/**
 * Rapporto larghezza/altezza oltre il quale una pagina è considerata doppia. Una facciata
 * singola sta intorno a 0,7 e una doppia intorno a 1,4: la soglia sta in mezzo, abbastanza
 * alta da non spezzare copertine o illustrazioni quasi quadrate.
 */
const val SPREAD_ASPECT_RATIO_THRESHOLD = 1.2f

fun isSpreadPage(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width.toFloat() / height.toFloat() >= SPREAD_ASPECT_RATIO_THRESHOLD

/** Dimensioni in pixel di un'immagine, lette senza decodificarla. */
data class PageBounds(val width: Int, val height: Int)

/**
 * L'elenco pagine pronto per il reader, più il filo che lo lega a quello originale.
 *
 * [originalIndexByPage] serve a un solo scopo, ma indispensabile: il progresso di lettura è
 * salvato come indice + numero di pagine, e dividere le doppie cambia entrambi. Senza la
 * mappa, spegnere o accendere la divisione farebbe riprendere il capitolo dalla pagina
 * sbagliata.
 */
data class ReaderPageExpansion(
    val pages: List<ReaderPage>,
    val originalIndexByPage: List<Int>,
    val originalCount: Int,
) {
    /** La prima pagina del reader che mostra la pagina originale indicata. */
    fun readerIndexForOriginalPage(originalIndex: Int): Int =
        originalIndexByPage.indexOf(originalIndex).takeIf { it >= 0 } ?: 0

    /**
     * L'indice da cui riprendere, interpretando un progresso salvato che può venire da una
     * modalità diversa da quella attuale. [savedCount] è il numero di pagine con cui era stato
     * salvato: se combacia con l'elenco attuale l'indice è già buono, se combacia con
     * l'originale va tradotto, altrimenti resta solo da tenerlo nei limiti.
     */
    fun restoredIndex(savedIndex: Int, savedCount: Int?): Int {
        val lastIndex = pages.lastIndex.coerceAtLeast(0)
        if (savedIndex <= 0) return 0
        return when (savedCount) {
            pages.size -> savedIndex
            originalCount -> readerIndexForOriginalPage(savedIndex)
            else -> savedIndex
        }.coerceIn(0, lastIndex)
    }
}

/** L'espansione neutra: nessuna pagina doppia divisa, indici uno a uno. */
fun unexpandedReaderPages(pages: List<ReaderPage>): ReaderPageExpansion = ReaderPageExpansion(
    pages = pages,
    originalIndexByPage = pages.indices.toList(),
    originalCount = pages.size,
)

/**
 * Sostituisce ogni pagina doppia con le sue due metà, in ordine di lettura.
 *
 * Solo le pagine locali vengono divise: di un'immagine ancora solo remota non si conoscono le
 * proporzioni senza scaricarla, e indovinare significherebbe spezzare pagine normali. In
 * streaming la divisione entra in gioco appena il capitolo finisce nella cache su disco, che
 * è anche il momento in cui l'elenco pagine viene ricostruito.
 *
 * @param boundsOf dimensioni della pagina, o `null` se illeggibili (la pagina resta intera)
 * @param rightFirst `true` per l'ordine di lettura dei manga: prima la metà destra
 */
fun expandSpreadPages(
    pages: List<ReaderPage>,
    rightFirst: Boolean,
    boundsOf: (ReaderPage.Local) -> PageBounds?,
): ReaderPageExpansion {
    val expanded = mutableListOf<ReaderPage>()
    val originalIndexes = mutableListOf<Int>()
    pages.forEachIndexed { originalIndex, page ->
        // I frammenti di una striscia webtoon già spezzata sono fasce larghe e basse: hanno le
        // proporzioni di una pagina doppia senza esserlo, e dividerli taglierebbe a metà il
        // flusso verticale invece di separare due facciate.
        val local = page.takeIf { it.persistedTallPageGroupKey() == null } as? ReaderPage.Local
        val bounds = local?.let(boundsOf)
        if (local == null || bounds == null || !isSpreadPage(bounds.width, bounds.height)) {
            expanded += page
            originalIndexes += originalIndex
            return@forEachIndexed
        }
        val halves = if (rightFirst) {
            listOf(PageHalf.RIGHT, PageHalf.LEFT)
        } else {
            listOf(PageHalf.LEFT, PageHalf.RIGHT)
        }
        halves.forEach { half ->
            expanded += local.copy(half = half)
            originalIndexes += originalIndex
        }
    }
    return ReaderPageExpansion(
        pages = expanded,
        originalIndexByPage = originalIndexes,
        originalCount = pages.size,
    )
}

/**
 * Dimensioni di un file immagine senza decodificarlo (`inJustDecodeBounds`): costa la lettura
 * dell'intestazione, non dei pixel. `null` se il file non c'è o non è un'immagine leggibile.
 * Da chiamare su un dispatcher I/O.
 */
fun readPageBounds(file: File): PageBounds? {
    if (!file.isFile || file.length() == 0L) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width > 0 && height > 0) PageBounds(width, height) else null
    } catch (_: Exception) {
        null
    }
}
