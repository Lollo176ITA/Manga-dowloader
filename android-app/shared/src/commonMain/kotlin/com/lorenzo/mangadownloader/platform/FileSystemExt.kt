package com.lorenzo.mangadownloader.platform

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/*
 * Equivalenti okio delle operazioni di java.io.File usate dall'app, con la stessa semantica
 * "tollerante" (false/0/null invece di eccezioni), così il codice convertito si comporta come prima.
 */

/** Il file system del dispositivo (`FileSystem.SYSTEM`, che okio non espone in common). */
expect val systemFileSystem: FileSystem

fun FileSystem.isFile(path: Path): Boolean = metadataOrNull(path)?.isRegularFile == true

fun FileSystem.isDirectory(path: Path): Boolean = metadataOrNull(path)?.isDirectory == true

/** Dimensione in byte, 0 se il file non esiste (come `File.length()`). */
fun FileSystem.length(path: Path): Long = metadataOrNull(path)?.size ?: 0L

/** Ultima modifica in millisecondi, 0 se ignota (come `File.lastModified()`). */
fun FileSystem.lastModified(path: Path): Long = metadataOrNull(path)?.lastModifiedAtMillis ?: 0L

/** Contenuto della cartella, vuoto se non esiste o non è leggibile (come `listFiles().orEmpty()`). */
fun FileSystem.listOrEmpty(path: Path): List<Path> = listOrNull(path).orEmpty()

/** Rinomina atomica; `false` se non riesce (come `File.renameTo`). */
fun FileSystem.renameTo(source: Path, target: Path): Boolean = try {
    atomicMove(source, target)
    true
} catch (_: IOException) {
    false
}

fun FileSystem.readText(path: Path): String = read(path) { readUtf8() }

fun FileSystem.writeText(path: Path, text: String) {
    write(path) { writeUtf8(text) }
}

fun FileSystem.writeBytes(path: Path, bytes: ByteArray) {
    sink(path).buffer().use { it.write(bytes) }
}

/** Estensione del nome file senza punto, "" se assente (come `File.extension`). */
val Path.extension: String get() = name.substringAfterLast('.', "")

/** Nome senza estensione (come `File.nameWithoutExtension`). */
val Path.nameWithoutExtension: String get() = name.substringBeforeLast('.')

/** Percorso relativo con separatori '/' su ogni piattaforma (come `invariantSeparatorsPath`). */
fun Path.invariantRelativeTo(root: Path): String = relativeTo(root).segments.joinToString("/")

/** Spazio libero in byte sul volume che contiene [path]. */
expect fun availableBytes(path: Path): Long
