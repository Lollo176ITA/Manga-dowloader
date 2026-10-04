package com.lorenzo.mangadownloader.data.network

/*
 * Composizione degli URL delle richieste, multipiattaforma. Riproduce la codifica di OkHttp
 * (`HttpUrl.Builder.addPathSegment` / `addQueryParameter`) usata prima della migrazione: i siti
 * ricevono esattamente le stesse richieste.
 */

private const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
private const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * [base] (schema, host ed eventuale percorso, senza query) più [pathSegments] e [query], codificati
 * come farebbe OkHttp.
 */
fun buildHttpUrl(
    base: String,
    pathSegments: List<String> = emptyList(),
    query: List<Pair<String, String>> = emptyList(),
): String {
    val schemeEnd = base.indexOf("://")
    require(schemeEnd > 0) { "URL non assoluto: $base" }
    val authorityStart = schemeEnd + 3
    val pathStart = base.indexOf('/', authorityStart).let { if (it < 0) base.length else it }
    val origin = base.substring(0, pathStart)
    val basePath = base.substring(pathStart).ifEmpty { "/" }

    val segments = basePath.removePrefix("/").split('/').toMutableList()
    for (segment in pathSegments) {
        if (segment == "." || segment == "..") continue
        val encoded = canonicalize(segment, PATH_SEGMENT_ENCODE_SET, plusIsSpace = false)
        if (segments.last().isEmpty()) segments[segments.lastIndex] = encoded else segments += encoded
    }
    return buildString {
        append(origin)
        append('/')
        append(segments.joinToString("/"))
        if (query.isNotEmpty()) {
            append('?')
            append(
                query.joinToString("&") { (name, value) ->
                    canonicalize(name, QUERY_COMPONENT_ENCODE_SET, plusIsSpace = true) + "=" +
                        canonicalize(value, QUERY_COMPONENT_ENCODE_SET, plusIsSpace = true)
                },
            )
        }
    }
}

private fun canonicalize(input: String, encodeSet: String, plusIsSpace: Boolean): String = buildString {
    var i = 0
    while (i < input.length) {
        val high = input[i]
        val codePointLength = if (high.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) 2 else 1
        val code = high.code
        val mustEncode = when {
            codePointLength == 2 -> true
            high == '+' && plusIsSpace -> true
            code < 0x20 || code == 0x7f || code >= 0x80 -> true
            high in encodeSet -> true
            high == '%' -> true
            else -> false
        }
        if (mustEncode) {
            for (byte in input.substring(i, i + codePointLength).encodeToByteArray()) {
                val b = byte.toInt() and 0xFF
                append('%')
                append(HEX_DIGITS[b shr 4])
                append(HEX_DIGITS[b and 0xF])
            }
        } else {
            append(high)
        }
        i += codePointLength
    }
}

/**
 * Risolve [reference] rispetto a [base] con lo stesso algoritmo di `java.net.URI.resolve` (RFC 2396),
 * tranne un caso: con un [base] senza percorso ("https://host") Java concatena senza "/" e produce
 * un URL rotto ("https://hostx"); qui il percorso vuoto vale "/".
 */
fun resolveUrl(base: String, reference: String): String {
    val child = parseReference(reference)
    val parent = parseReference(base)
    if (child.scheme == null && child.authority == null && child.path.isEmpty() &&
        child.fragment != null && child.query == null
    ) {
        if (parent.fragment != null && parent.fragment == child.fragment) return base
        return format(parent.scheme, parent.authority, parent.path, parent.query, child.fragment)
    }
    if (child.scheme != null) return reference
    if (child.authority != null) {
        return format(parent.scheme, child.authority, child.path, child.query, child.fragment)
    }
    val path = if (child.path.startsWith("/")) {
        child.path
    } else {
        val basePath = parent.path.ifEmpty { if (parent.authority != null) "/" else "" }
        val directoryEnd = basePath.lastIndexOf('/')
        val merged = when {
            child.path.isEmpty() -> if (directoryEnd >= 0) basePath.substring(0, directoryEnd + 1) else ""
            directoryEnd >= 0 -> basePath.substring(0, directoryEnd + 1) + child.path
            else -> child.path
        }
        removeDotSegments(merged)
    }
    return format(parent.scheme, parent.authority, path, child.query, child.fragment)
}

private fun format(scheme: String?, authority: String?, path: String, query: String?, fragment: String?): String =
    buildString {
        scheme?.let { append(it).append(':') }
        authority?.let { append("//").append(it) }
        append(path)
        query?.let { append('?').append(it) }
        fragment?.let { append('#').append(it) }
    }

private class UrlParts(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
)

private fun parseReference(url: String): UrlParts {
    var rest = url
    val fragment = rest.substringAfter('#', missingDelimiterValue = "").takeIf { '#' in rest }
    rest = rest.substringBefore('#')
    val query = rest.substringAfter('?', missingDelimiterValue = "").takeIf { '?' in rest }
    rest = rest.substringBefore('?')
    val schemeMatch = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(rest)
    val scheme = schemeMatch?.groupValues?.get(1)
    if (schemeMatch != null) rest = rest.substring(schemeMatch.value.length)
    var authority: String? = null
    if (rest.startsWith("//")) {
        val end = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
        authority = rest.substring(2, end)
        rest = rest.substring(end)
    }
    return UrlParts(scheme, authority, rest, query, fragment)
}

private fun removeDotSegments(path: String): String {
    val output = mutableListOf<String>()
    val segments = path.split('/')
    for ((index, segment) in segments.withIndex()) {
        when (segment) {
            "." -> if (index == segments.lastIndex) output += ""
            ".." -> {
                if (output.size > 1 || (output.size == 1 && output[0].isNotEmpty())) output.removeAt(output.lastIndex)
                if (index == segments.lastIndex) output += ""
            }
            else -> output += segment
        }
    }
    return output.joinToString("/").let { if (path.startsWith("/") && !it.startsWith("/")) "/$it" else it }
}
