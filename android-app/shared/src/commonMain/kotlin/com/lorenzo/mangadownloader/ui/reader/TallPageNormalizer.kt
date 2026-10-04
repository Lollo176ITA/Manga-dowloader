package com.lorenzo.mangadownloader.ui.reader

import com.lorenzo.mangadownloader.platform.ImageOps
import com.lorenzo.mangadownloader.platform.ImageSize
import com.lorenzo.mangadownloader.platform.isDirectory
import com.lorenzo.mangadownloader.platform.isFile
import com.lorenzo.mangadownloader.platform.listOrEmpty
import com.lorenzo.mangadownloader.platform.systemFileSystem
import kotlin.random.Random
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.IOException
import okio.Path

const val TallPageNormalizationMinHeightPx = 4096
const val TallPageNormalizationChunkHeightPx = 2048

/**
 * Tetto di sanità (64 fasce da 2048 px): i formati reali restano molto sotto
 * (il JPEG si ferma a 65.535 px), solo un PNG assurdo o malevolo lo supera, e
 * ricodificarlo in lossless moltiplicherebbe i file e lo spazio su disco.
 */
const val TallPageNormalizationMaxHeightPx = 131_072

data class TallPageNormalizationResult(
    val files: List<Path>,
    val originalWidth: Int,
    val originalHeight: Int,
    val wasSplit: Boolean,
)

/**
 * Converte una pagina troppo alta in file lossless ordinati senza mai decodificare
 * l'intera immagine in memoria. Il chiamante deve eseguire [normalize] su un dispatcher I/O.
 *
 * Le pagine sotto [TallPageNormalizationMinHeightPx] — e quelle oltre
 * [TallPageNormalizationMaxHeightPx] — non vengono copiate o ricodificate:
 * [TallPageNormalizationResult.files] contiene direttamente la sorgente. Per le pagine alte,
 * invece, ogni fascia viene prima completata in una directory di staging e poi promossa
 * nel target; se qualcosa fallisce, i nuovi file vengono rimossi e quelli preesistenti
 * vengono ripristinati. Decodifica e codifica sono di [imageOps], la piattaforma.
 */
class TallPageNormalizer(
    private val imageOps: ImageOps,
    private val fileSystem: FileSystem = systemFileSystem,
) {
    fun normalize(
        source: Path,
        outputDirectory: Path,
        outputBaseName: String,
        minHeightPx: Int = TallPageNormalizationMinHeightPx,
        chunkHeightPx: Int = TallPageNormalizationChunkHeightPx,
    ): TallPageNormalizationResult {
        require(minHeightPx > 0) { "minHeightPx must be positive" }
        require(chunkHeightPx > 0) { "chunkHeightPx must be positive" }
        require(isSafeBaseName(outputBaseName)) {
            "outputBaseName must be a non-empty file name, not a path"
        }
        if (!fileSystem.isFile(source)) throw IOException("Image does not exist: $source")

        val bounds = imageOps.readSize(source)
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?: throw IOException("Unsupported or corrupt image: $source")
        if (bounds.height < minHeightPx || bounds.height > TallPageNormalizationMaxHeightPx) {
            return TallPageNormalizationResult(
                files = listOf(source),
                originalWidth = bounds.width,
                originalHeight = bounds.height,
                wasSplit = false,
            )
        }

        return normalizeTallPage(
            source = source,
            outputDirectory = outputDirectory,
            outputBaseName = outputBaseName,
            bounds = bounds,
            chunkHeightPx = chunkHeightPx,
        )
    }

    private fun normalizeTallPage(
        source: Path,
        outputDirectory: Path,
        outputBaseName: String,
        bounds: ImageSize,
        chunkHeightPx: Int,
    ): TallPageNormalizationResult {
        if (!fileSystem.isFile(source)) throw IOException("Image does not exist: $source")
        fileSystem.createDirectories(outputDirectory)
        val ranges = tallPageNormalizationRanges(bounds.height, chunkHeightPx)
        val names = ranges.indices.map { index ->
            tallPageNormalizationPartFileName(
                outputBaseName = outputBaseName,
                partIndex = index,
                partCount = ranges.size,
                extension = imageOps.losslessExtension,
            )
        }
        val stagingDirectory = createWorkingDirectory(outputDirectory, outputBaseName, "staging")
        val backupDirectory = try {
            createWorkingDirectory(outputDirectory, outputBaseName, "backup")
        } catch (failure: Exception) {
            fileSystem.deleteRecursively(stagingDirectory, mustExist = false)
            throw failure
        }

        try {
            // Le sole pagine alte condividono questo gate: il controllo delle dimensioni delle pagine
            // normali resta parallelo, mentre non teniamo più bitmap pesanti contemporaneamente.
            synchronized(tallPageLock) {
                if (!fileSystem.isFile(source)) throw IOException("Image does not exist: $source")
                imageOps.writeStrips(
                    source = source,
                    width = bounds.width,
                    rows = ranges,
                    destinations = names.map { stagingDirectory / it },
                )
            }
            val files = promoteParts(
                outputDirectory = outputDirectory,
                outputBaseName = outputBaseName,
                stagingDirectory = stagingDirectory,
                backupDirectory = backupDirectory,
                names = names,
            )
            return TallPageNormalizationResult(
                files = files,
                originalWidth = bounds.width,
                originalHeight = bounds.height,
                wasSplit = true,
            )
        } finally {
            fileSystem.deleteRecursively(stagingDirectory, mustExist = false)
            // Se un ripristino eccezionalmente fallisse, non cancelliamo l'unica
            // copia rimasta dei vecchi frammenti.
            if (fileSystem.listOrEmpty(backupDirectory).isEmpty()) {
                fileSystem.delete(backupDirectory, mustExist = false)
            }
        }
    }

    private fun createWorkingDirectory(parent: Path, baseName: String, purpose: String): Path {
        repeat(10) {
            val candidate = parent / ".$baseName-$purpose-${Random.nextLong().toULong().toString(16)}"
            if (!fileSystem.exists(candidate)) {
                fileSystem.createDirectory(candidate, mustCreate = true)
                return candidate
            }
        }
        throw IOException("Cannot create a temporary $purpose directory in $parent")
    }

    private fun promoteParts(
        outputDirectory: Path,
        outputBaseName: String,
        stagingDirectory: Path,
        backupDirectory: Path,
        names: List<String>,
    ): List<Path> {
        val previousParts = fileSystem.listOrEmpty(outputDirectory)
            .filter { fileSystem.isFile(it) && isNormalizedPart(it.name, outputBaseName) }
        val promoted = mutableListOf<Path>()
        try {
            previousParts.forEach { previous ->
                fileSystem.atomicMove(previous, backupDirectory / previous.name)
            }
            names.forEach { name ->
                val destination = outputDirectory / name
                fileSystem.atomicMove(stagingDirectory / name, destination)
                promoted += destination
            }
            fileSystem.deleteRecursively(backupDirectory, mustExist = false)
            return promoted
        } catch (failure: Exception) {
            promoted.forEach { fileSystem.delete(it, mustExist = false) }
            fileSystem.listOrEmpty(backupDirectory).forEach { backup ->
                try {
                    fileSystem.atomicMove(backup, outputDirectory / backup.name)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw IOException("Cannot publish normalized parts for $outputBaseName", failure)
        }
    }

    private companion object {
        val tallPageLock = SynchronizedObject()
    }
}

fun tallPageNormalizationRanges(
    imageHeight: Int,
    chunkHeight: Int = TallPageNormalizationChunkHeightPx,
): List<IntRange> {
    if (imageHeight <= 0 || chunkHeight <= 0) return emptyList()
    val ranges = (0 until imageHeight step chunkHeight).map { top ->
        top until minOf(top + chunkHeight, imageHeight)
    }
    // Una coda sotto 1/8 del blocco sarebbe un file-scheggia da pochi px: la fondiamo
    // nell'ultima fascia piena, che resta comunque ben sotto i limiti texture.
    if (ranges.size < 2 || ranges.last().count() >= chunkHeight / 8) return ranges
    return ranges.dropLast(2) + listOf(ranges[ranges.size - 2].first..ranges.last().last)
}

fun tallPageNormalizationPartFileName(
    outputBaseName: String,
    partIndex: Int,
    partCount: Int,
    extension: String,
): String {
    require(partIndex >= 0 && partIndex < partCount) { "partIndex must identify an existing part" }
    require(partCount > 0) { "partCount must be positive" }
    val digits = maxOf(4, partCount.toString().length)
    return "${outputBaseName}__part_${(partIndex + 1).toString().padStart(digits, '0')}.$extension"
}

private fun isSafeBaseName(value: String): Boolean =
    value.isNotBlank() && value != "." && value != ".." && '/' !in value && '\\' !in value

private fun isNormalizedPart(fileName: String, outputBaseName: String): Boolean {
    val prefix = "${outputBaseName}__part_"
    return fileName.startsWith(prefix) &&
        (fileName.endsWith(".png", ignoreCase = true) ||
            fileName.endsWith(".webp", ignoreCase = true))
}
