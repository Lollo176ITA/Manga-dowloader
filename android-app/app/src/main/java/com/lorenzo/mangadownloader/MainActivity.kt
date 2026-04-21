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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val isDownloadActive = latestWork?.state == WorkInfo.State.RUNNING ||
        latestWork?.state == WorkInfo.State.ENQUEUED ||
        latestWork?.state == WorkInfo.State.BLOCKED
    val trimmedUrl = url.trim()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Manga Downloader") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Scarica capitoli da Mangapill",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "Incolla il primo chapter URL. L'app scarica da quel capitolo in poi e continua in background.",
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
                        minLines = 3,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                    ) {
                        Text(
                            text = "Output locale: $outputRoot",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                if (trimmedUrl.isEmpty()) {
                                    return@Button
                                }
                                prefs.edit().putString(KEY_LAST_URL, trimmedUrl).apply()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                DownloadWorker.enqueue(context, trimmedUrl)
                            },
                            enabled = trimmedUrl.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Avvia download")
                        }
                        FilledTonalButton(
                            onClick = {
                                workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
                            },
                            enabled = isDownloadActive,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Ferma download")
                        }
                    }
                }
            }

            StatusCard(workInfo = latestWork)
        }
    }
}

@Composable
private fun StatusCard(workInfo: WorkInfo?) {
    val progressMessage = workInfo?.progress?.getString(DownloadWorker.PROGRESS_MESSAGE)
    val done = workInfo?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
    val total = workInfo?.progress?.getInt(DownloadWorker.PROGRESS_TOTAL_CHAPTERS, -1) ?: -1
    val stateTitle = when (workInfo?.state) {
        WorkInfo.State.RUNNING -> "Download in corso"
        WorkInfo.State.ENQUEUED -> "In coda"
        WorkInfo.State.SUCCEEDED -> "Completato"
        WorkInfo.State.FAILED -> "Errore"
        WorkInfo.State.CANCELLED -> "Fermato"
        WorkInfo.State.BLOCKED -> "Bloccato"
        null -> "Nessun download attivo"
    }
    val progressCounts = if (done >= 0 && total > 0) "Capitoli completati: $done/$total" else null
    val fraction = if (done >= 0 && total > 0) done.toFloat() / total.toFloat() else null

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Stato download",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stateTitle,
                style = MaterialTheme.typography.titleMedium,
            )
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!progressMessage.isNullOrBlank()) {
                Text(
                    text = progressMessage,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (progressCounts != null) {
                Text(
                    text = progressCounts,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "Formato salvato: un file .cbz per capitolo. I capitoli gia presenti vengono saltati al riavvio.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val PREFS_NAME = "manga_downloader_prefs"
private const val KEY_LAST_URL = "last_url"
