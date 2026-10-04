package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.platform.currentTimeMillis
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import okio.BufferedSink
import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/** CRC-32 (polinomio IEEE 802.3), lo stesso di `java.util.zip.CRC32` e del formato ZIP. */
class Crc32 {
    private var crc = 0xFFFFFFFF.toInt()

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    val value: Long get() = (crc.inv().toLong()) and 0xFFFFFFFFL

    companion object {
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun of(bytes: ByteArray): Long = Crc32().apply { update(bytes) }.value
    }
}

/**
 * Archivi .cbz dei capitoli: scrittura in ZIP **STORED** (le immagini sono già compresse) e
 * estrazione delle pagine per il reader.
 *
 * In scrittura l'header locale di ogni entry viene completato a posteriori (CRC e dimensioni)
 * tramite accesso casuale al file: niente doppia lettura delle pagine e niente data descriptor,
 * che `java.util.zip.ZipInputStream` e diversi lettori esterni non accettano con STORED.
 * In lettura si accettano anche gli archivi delle versioni precedenti (Deflate livello 0).
 */
object ChapterArchive {

    /**
     * Pagine già nominate in modo ordinabile: si estraggono col loro nome, così ordine e split delle
     * pagine alte restano quelli scelti al download. Le altre vengono rinumerate in ordine d'archivio.
     */
    private val orderedPageName = Regex("""^\d+(?:__part_\d{4,})?\.[A-Za-z0-9]+$""", RegexOption.IGNORE_CASE)

    private const val MAX_ENTRY_SIZE = 0xFFFFFFFFL
    private const val COPY_BUFFER = 64 * 1024

    /** Scrive [files] (in quest'ordine, col loro nome) nell'archivio [target], sovrascrivendolo. */
    fun write(fileSystem: FileSystem, target: Path, files: List<Path>) {
        fileSystem.delete(target, mustExist = false)
        val (dosTime, dosDate) = dosDateTime(currentTimeMillis())
        fileSystem.openReadWrite(target, mustCreate = true).use { handle ->
            handle.sink().buffer().use { sink -> writeEntries(fileSystem, handle, sink, files, dosTime, dosDate) }
        }
    }

    private fun writeEntries(
        fileSystem: FileSystem,
        handle: FileHandle,
        sink: BufferedSink,
        files: List<Path>,
        dosTime: Int,
        dosDate: Int,
    ) {
        val central = Buffer()
        var offset = 0L
        for (file in files) {
            val name = file.name.encodeToByteArray()
            val headerOffset = offset
            sink.writeLocalHeader(name, dosTime, dosDate, crc = 0L, size = 0L)
            offset += 30 + name.size
            val crc = Crc32()
            var size = 0L
            val chunk = ByteArray(COPY_BUFFER)
            fileSystem.source(file).buffer().use { source ->
                while (true) {
                    val read = source.read(chunk)
                    if (read == -1) break
                    crc.update(chunk, 0, read)
                    sink.write(chunk, 0, read)
                    size += read
                }
            }
            if (size >= MAX_ENTRY_SIZE) throw IOException("Pagina troppo grande per il CBZ: ${file.name}")
            offset += size
            sink.flush()
            val patch = Buffer().writeIntLe(crc.value.toInt()).writeIntLe(size.toInt()).writeIntLe(size.toInt())
            handle.write(headerOffset + 14, patch, patch.size)
            central.writeCentralHeader(name, dosTime, dosDate, crc.value, size, headerOffset)
        }
        val centralSize = central.size
        val entries = files.size
        sink.writeAll(central)
        sink.writeIntLe(0x06054b50)
        sink.writeShortLe(0)
        sink.writeShortLe(0)
        sink.writeShortLe(entries)
        sink.writeShortLe(entries)
        sink.writeIntLe(centralSize.toInt())
        sink.writeIntLe(offset.toInt())
        sink.writeShortLe(0)
        sink.flush()
    }

    /**
     * Estrae le pagine di [archive] in [outputDir] (creata qui) e le restituisce in ordine d'archivio.
     * Un archivio corrotto o vuoto lancia [IOException] senza lasciare file in [outputDir].
     */
    fun extractPages(fileSystem: FileSystem, archive: Path, outputDir: Path): List<Path> {
        val extracted = mutableListOf<Path>()
        try {
            val zip = fileSystem.openZip(archive)
            fileSystem.createDirectories(outputDir)
            val usedNames = mutableSetOf<String>()
            var index = 1
            for (name in entryNamesInArchiveOrder(fileSystem, archive)) {
                if (name.endsWith('/')) continue
                val entry = ZIP_ROOT / name
                val entryName = name.substringAfterLast('/').substringAfterLast('\\')
                val extension = entryName.substringAfterLast('.', "jpg").lowercase().ifBlank { "jpg" }
                val outputName = if (orderedPageName.matches(entryName)) {
                    if (!usedNames.add(entryName.lowercase())) {
                        throw IOException("Nome pagina duplicato nel CBZ: $entryName")
                    }
                    entryName
                } else {
                    val prefix = index.toString().padStart(3, '0')
                    var suffix = 0
                    var candidate: String
                    do {
                        candidate = "$prefix${if (suffix == 0) "" else "_$suffix"}.$extension"
                        suffix += 1
                    } while (!usedNames.add(candidate.lowercase()))
                    candidate
                }
                val output = outputDir / outputName
                zip.source(entry).use { source ->
                    fileSystem.sink(output).buffer().use { it.writeAll(source) }
                }
                extracted += output
                index += 1
            }
        } catch (e: IOException) {
            fileSystem.deleteRecursively(outputDir, mustExist = false)
            throw IOException("Capitolo scaricato corrotto o illeggibile", e)
        }
        if (extracted.isEmpty()) {
            fileSystem.deleteRecursively(outputDir, mustExist = false)
            throw IOException("Nessuna pagina trovata nel capitolo scaricato")
        }
        return extracted
    }

    private val ZIP_ROOT = "/".toPath()

    /**
     * Nomi delle entry nell'ordine della central directory, cioè l'ordine in cui sono state scritte
     * (lo stesso che vedeva `ZipInputStream`); il file system zip di okio le elencherebbe per nome.
     */
    private fun entryNamesInArchiveOrder(fileSystem: FileSystem, archive: Path): List<String> =
        fileSystem.openReadOnly(archive).use { handle ->
            val size = handle.size()
            val tailSize = minOf(size, 22L + 65_535L)
            val tail = Buffer().also { handle.read(size - tailSize, it, tailSize) }.readByteArray()
            var eocd = tail.size - 22
            while (eocd >= 0 && !tail.isEndOfCentralDirectoryAt(eocd)) eocd--
            if (eocd < 0) throw IOException("Fine della central directory non trovata")
            val end = Buffer().write(tail, eocd, 22)
            end.skip(10)
            val count = end.readShortLe().toInt() and 0xFFFF
            val centralSize = end.readIntLe().toLong() and 0xFFFFFFFFL
            val centralOffset = end.readIntLe().toLong() and 0xFFFFFFFFL
            val central = Buffer().also { handle.read(centralOffset, it, centralSize) }
            List(count) {
                if (central.readIntLe() != 0x02014b50) throw IOException("Central directory non valida")
                central.skip(24)
                val nameLength = central.readShortLe().toInt() and 0xFFFF
                val extraLength = central.readShortLe().toInt() and 0xFFFF
                val commentLength = central.readShortLe().toInt() and 0xFFFF
                central.skip(12)
                val name = central.readUtf8(nameLength.toLong())
                central.skip((extraLength + commentLength).toLong())
                name
            }
        }

    private fun ByteArray.isEndOfCentralDirectoryAt(index: Int): Boolean =
        this[index] == 0x50.toByte() && this[index + 1] == 0x4b.toByte() &&
            this[index + 2] == 0x05.toByte() && this[index + 3] == 0x06.toByte()

    private fun BufferedSink.writeLocalHeader(name: ByteArray, time: Int, date: Int, crc: Long, size: Long) {
        writeIntLe(0x04034b50)
        writeShortLe(10)
        writeShortLe(UTF8_FLAG)
        writeShortLe(0)
        writeShortLe(time)
        writeShortLe(date)
        writeIntLe(crc.toInt())
        writeIntLe(size.toInt())
        writeIntLe(size.toInt())
        writeShortLe(name.size)
        writeShortLe(0)
        write(name)
    }

    private fun Buffer.writeCentralHeader(name: ByteArray, time: Int, date: Int, crc: Long, size: Long, offset: Long) {
        writeIntLe(0x02014b50)
        writeShortLe(20)
        writeShortLe(10)
        writeShortLe(UTF8_FLAG)
        writeShortLe(0)
        writeShortLe(time)
        writeShortLe(date)
        writeIntLe(crc.toInt())
        writeIntLe(size.toInt())
        writeIntLe(size.toInt())
        writeShortLe(name.size)
        writeShortLe(0)
        writeShortLe(0)
        writeShortLe(0)
        writeShortLe(0)
        writeIntLe(0)
        writeIntLe(offset.toInt())
        write(name)
    }

    /** Bit 11: nomi delle entry in UTF-8. */
    private const val UTF8_FLAG = 0x0800

    private fun dosDateTime(epochMillis: Long): Pair<Int, Int> {
        val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        val year = local.year.coerceIn(1980, 2107)
        val date = ((year - 1980) shl 9) or (local.month.ordinal + 1 shl 5) or local.day
        val time = (local.hour shl 11) or (local.minute shl 5) or (local.second / 2)
        return time to date
    }
}
