package com.lorenzo.mangadownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                MangaDownloaderApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaDownloaderApp(viewModel: MangaViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE_WORK_NAME)
        .observeAsState(emptyList())
    val latestWork = workInfos.maxByOrNull { it.id.toString() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (!msg.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.dismissError()
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onStartDownload: (String, String) -> Unit = { mangaUrl, chapterLabel ->
        try {
            DownloadWorker.enqueue(context, mangaUrl, chapterLabel)
            scope.launch {
                snackbarHostState.showSnackbar("Download avviato dal capitolo $chapterLabel")
            }
        } catch (exc: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar(exc.message ?: "Impossibile avviare il download")
            }
        }
    }

    val isDownloadActive = latestWork?.state == WorkInfo.State.RUNNING ||
        latestWork?.state == WorkInfo.State.ENQUEUED ||
        latestWork?.state == WorkInfo.State.BLOCKED

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Manga Downloader") },
                navigationIcon = {
                    if (state.selected != null) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Indietro",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val selected = state.selected
        if (selected == null) {
            SearchScreen(
                state = state,
                padding = innerPadding,
                onQueryChange = viewModel::onQueryChange,
                onSelect = viewModel::selectManga,
                status = latestWork,
                isDownloadActive = isDownloadActive,
                onStopDownload = { workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME) },
            )
        } else {
            DetailScreen(
                details = selected,
                isLoading = state.isLoadingDetails,
                padding = innerPadding,
                onStart = onStartDownload,
                status = latestWork,
                isDownloadActive = isDownloadActive,
                onStopDownload = { workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (MangaSearchResult) -> Unit,
    status: WorkInfo?,
    isDownloadActive: Boolean,
    onStopDownload: () -> Unit,
) {
    val trimmed = state.query.trim()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("Cerca manga") },
            singleLine = true,
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Pulisci")
                    }
                }
            },
            shape = MaterialTheme.shapes.large,
        )

        DownloadStatusStrip(
            status = status,
            isActive = isDownloadActive,
            onStop = onStopDownload,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isSearching -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                    )
                }
                state.results.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.results, key = { it.mangaUrl }) { result ->
                            ResultCard(result = result, onClick = { onSelect(result) })
                        }
                    }
                }
                trimmed.isNotEmpty() && trimmed.length < 3 -> {
                    Text(
                        text = "Digita almeno 3 caratteri",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                trimmed.length >= 3 && !state.isSearching -> {
                    Text(
                        text = "Nessun risultato",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: MangaSearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            AsyncImage(
                model = result.coverUrl,
                contentDescription = result.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = result.title,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailScreen(
    details: MangaDetails,
    isLoading: Boolean,
    padding: PaddingValues,
    onStart: (mangaUrl: String, chapterLabel: String) -> Unit,
    status: WorkInfo?,
    isDownloadActive: Boolean,
    onStopDownload: () -> Unit,
) {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = details.coverUrl,
                contentDescription = details.title,
                modifier = Modifier
                    .width(96.dp)
                    .height(144.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = details.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DownloadStatusStrip(
            status = status,
            isActive = isDownloadActive,
            onStop = onStopDownload,
        )

        if (isLoading && details.chapters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(details.chapters.reversed(), key = { it.url }) { chapter ->
                    ChapterRow(chapter = chapter, onClick = { pending = chapter.displayNumber() })
                }
            }
        }
    }

    pending?.let { label ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Capitolo $label") },
            text = { Text("Scarica da questo capitolo in poi?") },
            confirmButton = {
                TextButton(onClick = {
                    onStart(details.mangaUrl, label)
                    pending = null
                }) { Text("Avvia") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = "Capitolo ${chapter.displayNumber()}",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DownloadStatusStrip(
    status: WorkInfo?,
    isActive: Boolean,
    onStop: () -> Unit,
) {
    val progressMessage = status?.progress?.getString(DownloadWorker.PROGRESS_MESSAGE)
    val done = status?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
    val total = status?.progress?.getInt(DownloadWorker.PROGRESS_TOTAL_CHAPTERS, -1) ?: -1
    val showRecent = status?.state == WorkInfo.State.SUCCEEDED ||
        status?.state == WorkInfo.State.FAILED ||
        status?.state == WorkInfo.State.CANCELLED

    if (!isActive && !showRecent) {
        return
    }

    val fraction = if (done >= 0 && total > 0) done.toFloat() / total.toFloat() else null
    val title = when (status?.state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            progressMessage ?: "Download in corso"
        WorkInfo.State.SUCCEEDED -> "Completato"
        WorkInfo.State.FAILED -> progressMessage ?: "Errore"
        WorkInfo.State.CANCELLED -> "Fermato"
        else -> progressMessage ?: ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Ferma download",
                        )
                    }
                }
            }
            if (fraction != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$done / $total",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (isActive) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
