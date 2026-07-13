package com.lorenzo.mangadownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val TallPageNormalizationMinHeightPx = 4096
internal const val TallPageNormalizationChunkHeightPx = 2048

internal data class TallPageNormalizationResult(
    val files: List<File>,
    val originalWidth: Int,
    val originalHeight: Int,
    val wasSplit: Boolean,
)

/**
 * Converte una pagina troppo alta in file lossless ordinati senza mai decodificare
 * l'intera immagine in memoria. Il chiamante deve eseguire [normalize] su un dispatcher I/O.
 *
 * Le pagine sotto [TallPageNormalizationMinHeightPx] non vengono copiate o ricodificate:
 * [TallPageNormalizationResult.files] contiene direttamente [source]. Per le pagine alte,
 * invece, ogni fascia viene prima completata in una directory di staging e poi promossa
 * nel target; se qualcosa fallisce, i nuovi file vengono rimossi e quelli preesistenti
 * vengono ripristinati.
 */
internal object TallPageNormalizer {
    // Download e cache restano paralleli; serializziamo soltanto il tratto che tiene
    // un bitmap ARGB in memoria, evitando quattro region decoder pesanti simultanei.
    @Synchronized
    fun normalize(
        source: File,
        outputDirectory: File,
        outputBaseName: String,
        minHeightPx: Int = TallPageNormalizationMinHeightPx,
        chunkHeightPx: Int = TallPageNormalizationChunkHeightPx,
    ): TallPageNormalizationResult {
        require(minHeightPx > 0) { "minHeightPx must be positive" }
        require(chunkHeightPx > 0) { "chunkHeightPx must be positive" }
        require(isSafeBaseName(outputBaseName)) {
            "outputBaseName must be a non-empty file name, not a path"
        }
        if (!source.isFile) throw IOException("Image does not exist: ${source.absolutePath}")

        val bounds = readBounds(source)
        if (bounds.height < minHeightPx) {
            return TallPageNormalizationResult(
                files = listOf(source),
                originalWidth = bounds.width,
                originalHeight = bounds.height,
                wasSplit = false,
            )
        }

        ensureOutputDirectory(outputDirectory)
        val encoding = losslessEncoding()
        val ranges = tallPageNormalizationRanges(bounds.height, chunkHeightPx)
        val names = ranges.indices.map { index ->
            tallPageNormalizationPartFileName(
                outputBaseName = outputBaseName,
                partIndex = index,
                partCount = ranges.size,
                extension = encoding.extension,
            )
        }
        val stagingDirectory = createWorkingDirectory(outputDirectory, outputBaseName, "staging")
        val backupDirectory = try {
            createWorkingDirectory(outputDirectory, outputBaseName, "backup")
        } catch (failure: Exception) {
            stagingDirectory.deleteRecursively()
            throw failure
        }

        try {
            decodeParts(
                input = source,
                width = bounds.width,
                ranges = ranges,
                stagingDirectory = stagingDirectory,
                names = names,
                encoding = encoding,
            )
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
            stagingDirectory.deleteRecursively()
            // Se un ripristino eccezionalmente fallisse, non cancelliamo l'unica
            // copia rimasta dei vecchi frammenti.
            if (backupDirectory.listFiles().isNullOrEmpty()) backupDirectory.delete()
        }
    }
}

internal fun tallPageNormalizationRanges(
    imageHeight: Int,
    chunkHeight: Int = TallPageNormalizationChunkHeightPx,
): List<IntRange> {
    if (imageHeight <= 0 || chunkHeight <= 0) return emptyList()
    return (0 until imageHeight step chunkHeight).map { top ->
        top until minOf(top + chunkHeight, imageHeight)
    }
}

internal fun tallPageNormalizationPartFileName(
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

private data class ImageBounds(val width: Int, val height: Int)

private data class LosslessEncoding(
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
)

private fun readBounds(input: File): ImageBounds {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(input.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        throw IOException("Unsupported or corrupt image: ${input.absolutePath}")
    }
    return ImageBounds(options.outWidth, options.outHeight)
}

private fun ensureOutputDirectory(directory: File) {
    if (directory.isDirectory) return
    if (!directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Cannot create output directory: ${directory.absolutePath}")
    }
}

private fun isSafeBaseName(value: String): Boolean =
    value.isNotBlank() && value != "." && value != ".." && File(value).name == value

private fun losslessEncoding(): LosslessEncoding =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        LosslessEncoding("webp", Bitmap.CompressFormat.WEBP_LOSSLESS)
    } else {
        // WEBP_LOSSLESS non esiste sulle API 26-29 supportate dall'app.
        LosslessEncoding("png", Bitmap.CompressFormat.PNG)
    }

private fun createWorkingDirectory(parent: File, baseName: String, purpose: String): File {
    repeat(10) {
        val candidate = File(parent, ".$baseName-$purpose-${UUID.randomUUID()}")
        if (candidate.mkdir()) return candidate
    }
    throw IOException("Cannot create a temporary $purpose directory in ${parent.absolutePath}")
}

private fun decodeParts(
    input: File,
    width: Int,
    ranges: List<IntRange>,
    stagingDirectory: File,
    names: List<String>,
    encoding: LosslessEncoding,
) {
    FileInputStream(input).use { inputStream ->
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(inputStream.fd, false)
        try {
            ranges.forEachIndexed { index, rows ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                val bitmap = decoder.decodeRegion(
                    Rect(0, rows.first, width, rows.last + 1),
                    options,
                ) ?: throw IOException("Cannot decode image part ${index + 1} of ${ranges.size}")
                try {
                    writeBitmap(bitmap, File(stagingDirectory, names[index]), encoding.compressFormat)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            decoder.recycle()
        }
    }
}

private fun writeBitmap(
    bitmap: Bitmap,
    destination: File,
    format: Bitmap.CompressFormat,
) {
    FileOutputStream(destination).use { output ->
        if (!bitmap.compress(format, 100, output)) {
            throw IOException("Cannot encode ${destination.name}")
        }
        output.flush()
        output.fd.sync()
    }
}

private fun promoteParts(
    outputDirectory: File,
    outputBaseName: String,
    stagingDirectory: File,
    backupDirectory: File,
    names: List<String>,
): List<File> {
    val previousParts = outputDirectory.listFiles().orEmpty()
        .filter { it.isFile && isNormalizedPart(it.name, outputBaseName) }
    val promoted = mutableListOf<File>()
    try {
        previousParts.forEach { previous ->
            moveAtomically(previous, File(backupDirectory, previous.name))
        }
        names.forEach { name ->
            val destination = File(outputDirectory, name)
            moveAtomically(File(stagingDirectory, name), destination)
            promoted += destination
        }
        backupDirectory.deleteRecursively()
        return promoted
    } catch (failure: Exception) {
        promoted.forEach { it.delete() }
        backupDirectory.listFiles().orEmpty().forEach { backup ->
            try {
                moveAtomically(backup, File(outputDirectory, backup.name))
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
        }
        throw IOException("Cannot publish normalized parts for $outputBaseName", failure)
    }
}

private fun isNormalizedPart(fileName: String, outputBaseName: String): Boolean {
    val prefix = "${outputBaseName}__part_"
    return fileName.startsWith(prefix) &&
        (fileName.endsWith(".png", ignoreCase = true) ||
            fileName.endsWith(".webp", ignoreCase = true))
}

private fun moveAtomically(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath())
    }
}
