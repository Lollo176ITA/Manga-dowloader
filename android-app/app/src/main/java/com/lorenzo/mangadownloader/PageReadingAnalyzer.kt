package com.lorenzo.mangadownloader

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PageReadingAnalyzer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    fun cachedTimeMs(file: File): Long? = cache[file.absolutePath]

    fun prefetch(files: List<File>, startIndex: Int, count: Int = PREFETCH_WINDOW) {
        if (startIndex >= files.size) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val end = minOf(files.size, startIndex + count)
            for (i in startIndex until end) {
                if (!isActive) break
                val f = files[i]
                if (cache.containsKey(f.absolutePath)) continue
                analyze(f)
            }
        }
    }

    suspend fun analyze(file: File): Long {
        cache[file.absolutePath]?.let { return it }
        val ms = withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                val result = recognizer.process(image).await()
                computeReadingTimeMs(result)
            } catch (t: Throwable) {
                FALLBACK_TIME_MS
            }
        }
        cache[file.absolutePath] = ms
        return ms
    }

    fun close() {
        prefetchJob?.cancel()
        scope.cancel()
        runCatching { recognizer.close() }
        cache.clear()
    }

    private fun computeReadingTimeMs(text: Text): Long {
        val wordCount = text.textBlocks.sumOf { block ->
            block.lines.sumOf { it.elements.size }
        }
        val perWordMs = 60_000L / WORDS_PER_MINUTE
        val raw = BASE_MS + wordCount * perWordMs
        return raw.coerceIn(MIN_MS, MAX_MS)
    }

    companion object {
        private const val WORDS_PER_MINUTE = 250L
        private const val BASE_MS = 5_000L
        private const val MIN_MS = 3_000L
        private const val MAX_MS = 60_000L
        private const val FALLBACK_TIME_MS = 8_000L
        private const val PREFETCH_WINDOW = 4
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resume(value) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
}
