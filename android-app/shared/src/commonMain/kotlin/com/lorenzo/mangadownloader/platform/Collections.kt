package com.lorenzo.mangadownloader.platform

/** Come `MutableMap.putIfAbsent` della JVM: scrive solo se la chiave manca, restituisce il valore precedente. */
fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V): V? {
    val existing = get(key)
    if (existing == null) put(key, value)
    return existing
}
