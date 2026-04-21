package com.lorenzo.mangadownloader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                MangaDownloaderScreen()
            }
        }
    }
}

@Composable
private fun MangaDownloaderScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE_WORK_NAME)
        .observeAsState(emptyList())
    val latestWork = workInfos.maxByOrNull { it.id.toString() }

    var url by rememberSaveable {
        mutableStateOf(prefs.getString(KEY_LAST_URL, "") ?: "")
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // Download still works, but the foreground notification may be hidden on newer Android versions.
        }
    }

    val outputRoot = remember {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("MangaDownloader")
            ?.absolutePath
            ?: "App external downloads folder unavailable"
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Manga Downloader",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Incolla il primo chapter URL di Mangapill. L'app scarica da quel capitolo in poi e continua in background.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Primo chapter URL") },
                supportingText = {
                    Text("Esempio: https://mangapill.com/chapters/11-10001000/20-seiki-shounen-chapter-1")
                },
                singleLine = false,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val trimmed = url.trim()
                        if (trimmed.isEmpty()) {
                            return@Button
                        }
                        prefs.edit().putString(KEY_LAST_URL, trimmed).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        DownloadWorker.enqueue(context, trimmed)
                    },
                ) {
                    Text("Avvia download")
                }

                TextButton(
                    onClick = {
                        workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
                    },
                ) {
                    Text("Ferma")
                }
            }

            StatusCard(
                workInfo = latestWork,
                outputRoot = outputRoot,
            )
        }
    }
}

@Composable
private fun StatusCard(workInfo: WorkInfo?, outputRoot: String) {
    val progressMessage = workInfo?.progress?.getString(DownloadWorker.PROGRESS_MESSAGE)
    val progressCounts = buildString {
        val done = workInfo?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
        val total = workInfo?.progress?.getInt(DownloadWorker.PROGRESS_TOTAL_CHAPTERS, -1) ?: -1
        if (done >= 0 && total > 0) {
            append("Capitoli completati: $done/$total")
        }
    }
    val stateLine = when (workInfo?.state) {
        WorkInfo.State.RUNNING -> "Stato: download in corso"
        WorkInfo.State.ENQUEUED -> "Stato: in coda"
        WorkInfo.State.SUCCEEDED -> "Stato: completato"
        WorkInfo.State.FAILED -> "Stato: errore"
        WorkInfo.State.CANCELLED -> "Stato: fermato"
        WorkInfo.State.BLOCKED -> "Stato: bloccato"
        null -> "Stato: nessun download attivo"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stateLine, style = MaterialTheme.typography.titleMedium)
        if (!progressMessage.isNullOrBlank()) {
            Text(text = progressMessage, style = MaterialTheme.typography.bodyMedium)
        }
        if (progressCounts.isNotEmpty()) {
            Text(text = progressCounts, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "Output: $outputRoot",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Formato salvato: un file .cbz per capitolo. I capitoli già presenti vengono saltati al riavvio.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val PREFS_NAME = "manga_downloader_prefs"
private const val KEY_LAST_URL = "last_url"
