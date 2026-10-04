package com.lorenzo.mangadownloader.data.sources

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.lorenzo.mangadownloader.domain.isAdultGenre
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Primo valore non-bianco (trimmato) tra quelli passati, o `null`. Usata dai parser
 * statici delle fonti (companion object), che non vedono i metodi d'istanza della base.
 */
fun firstNonBlankTrimmed(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isNotBlank()) {
            return trimmed
        }
    }
    return null
}

/**
 * Il riquadro ("card") di un risultato di ricerca: l'antenato più ampio di [anchor] che non
 * contiene link ad **altre** serie (secondo [seriesKeyOf]) né la cornice della pagina
 * (`nav`, `header`, `form`...). Serve a leggere i metadati della card, come i generi, senza
 * agganciarsi alle classi CSS del layout e senza sconfinare nella card accanto.
 */
fun searchResultCard(anchor: Element, seriesKeyOf: (Element) -> String?): Element {
    val own = seriesKeyOf(anchor)
    var card = anchor
    while (true) {
        val parent = card.parent() ?: break
        if (parent.tagName() in PAGE_ROOT_TAGS || parent.selectFirst(PAGE_CHROME_SELECTOR) != null) {
            break
        }
        val hasForeignSeries = parent.select("a[href]").any { link ->
            seriesKeyOf(link)?.let { it != own } == true
        }
        if (hasForeignSeries) {
            break
        }
        card = parent
    }
    return card
}

private val PAGE_ROOT_TAGS = setOf("html", "body")

/**
 * Un oggetto JSON di una fonte ha tra i [key] (array di `{ name, slug }` o di stringhe) un
 * genere per adulti? Tollera chiave assente, `null` o forme inattese: nel dubbio `false`.
 */
fun JsonObject.hasAdultGenre(key: String = "genres"): Boolean {
    val genres = this[key] as? JsonArray ?: return false
    return genres.any { genre ->
        val values = when (genre) {
            is JsonObject -> listOf("name", "slug").mapNotNull { (genre[it] as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(genre.contentOrNull)
            else -> emptyList()
        }
        values.any(::isAdultGenre)
    }
}
private const val PAGE_CHROME_SELECTOR = "nav, header, footer, form"

/**
 * Valore testuale associato a un'etichetta tipo "Stato"/"Status" in una pagina, cercando
 * sia il fratello successivo dell'etichetta sia il testo del genitore meno l'etichetta
 * (pattern "Etichetta: valore"). Tollerante alla struttura; `null` se nessuna combacia.
 * Usato dalle fonti per estrarre lo stato di pubblicazione in modo best-effort.
 */
fun statusTextNearLabel(document: Document, vararg labels: String): String? {
    for (label in labels) {
        // Etichetta = elemento il cui testo proprio, prima dei due punti, è esattamente la label
        // (cattura "Stato", "Stato:", "Stato: Valore").
        val element = document.getAllElements().firstOrNull { el ->
            el.ownText().trim().substringBefore(":").trim().equals(label, ignoreCase = true)
        } ?: continue

        // 1) Valore nello stesso elemento (anche dentro un figlio): "Status: <a>Completed</a>".
        if (element.ownText().contains(":")) {
            element.text().trim().substringAfter(":", "").trim()
                .takeIf(String::isNotBlank)?.let { return it }
        }
        // 2) Valore nei fratelli successivi, saltando i separatori (":", spazi). Es. VyManga:
        //    <span>Status</span><span>:</span><span>Ongoing</span>.
        var sibling = element.nextElementSibling()
        while (sibling != null) {
            sibling.text().trim().removePrefix(":").trim()
                .takeIf(String::isNotBlank)?.let { return it }
            sibling = sibling.nextElementSibling()
        }
        // 3) Valore nel testo del genitore, tolta l'etichetta.
        element.parent()?.text()?.trim()
            ?.substringAfter(element.text(), "")?.trim()?.removePrefix(":")?.trim()
            ?.takeIf(String::isNotBlank)?.let { return it }
    }
    return null
}

/**
 * Sinossi/descrizione di una pagina manga: prova prima i [selectors] specifici della fonte,
 * poi ricade sui meta OpenGraph/description (presenti su quasi tutti i siti). `null` se nulla.
 * Best-effort, usata dalle fonti HTML per riempire il pulsante info.
 */
fun parseDescription(document: Document, vararg selectors: String): String? {
    for (selector in selectors) {
        document.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return firstNonBlankTrimmed(
        document.selectFirst("""meta[property="og:description"]""")?.attr("content"),
        document.selectFirst("""meta[name="description"]""")?.attr("content"),
        document.selectFirst("""meta[name="twitter:description"]""")?.attr("content"),
    )
}
