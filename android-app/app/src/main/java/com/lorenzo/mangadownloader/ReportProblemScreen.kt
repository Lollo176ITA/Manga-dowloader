package com.lorenzo.mangadownloader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val MAX_FEEDBACK_IMAGES = 5

/**
 * Schermata "Segnala un problema": form guidato (tipo → sottotipo → descrizione → allegati →
 * email facoltativa) che produce una [FeedbackDraft]. L'invio vero (email verso la board Trello)
 * lo fa il chiamante via [onSubmit], così lo snackbar e la chiusura restano in MainActivity.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportProblemScreen(
    padding: PaddingValues,
    onSubmit: (FeedbackDraft) -> Unit,
) {
    val context = LocalContext.current
    val recorder = rememberFeedbackAudioRecorder()

    var category by remember { mutableStateOf<ReportCategory?>(null) }
    var subtype by remember { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var contactEmail by rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_FEEDBACK_IMAGES),
    ) { picked ->
        if (picked.isNotEmpty()) {
            images = (images + picked).distinct().take(MAX_FEEDBACK_IMAGES)
        }
    }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) recorder.start() }

    // Cronometro della registrazione in corso; dopo lo stop trattiene la durata finale.
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(recorder.isRecording) {
        if (recorder.isRecording) {
            elapsedSeconds = 0
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    val canSubmit = category != null && subtype != null && message.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Raccontaci cosa non va o cosa vorresti. Più dettagli dai, prima possiamo aiutarti.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!FeedbackReporter.isConfigured()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = "Le segnalazioni non sono ancora configurate in questa build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // 1) Tipo
        Column {
            FormLabel("Di cosa si tratta?")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReportCategory.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = category == option,
                        onClick = {
                            if (category != option) {
                                category = option
                                subtype = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ReportCategory.entries.size,
                        ),
                        label = { Text(option.label) },
                    )
                }
            }
        }

        // 2) Sottotipo (dipende dalla categoria)
        category?.let { selectedCategory ->
            Column {
                FormLabel("Che tipo?")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selectedCategory.subtypes().forEach { option ->
                        FilterChip(
                            selected = subtype == option,
                            onClick = { subtype = option },
                            label = { Text(option) },
                        )
                    }
                }
            }
        }

        // 3) Descrizione
        Column {
            FormLabel("Descrizione")
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = { Text("Descrivi il problema o l'idea. Per i bug: cosa facevi e cosa è successo.") },
                shape = MaterialTheme.shapes.large,
            )
        }

        // 4) Allegati
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormLabel("Allegati (facoltativi)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = images.size < MAX_FEEDBACK_IMAGES,
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Immagini")
                }
            }

            if (images.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    images.forEach { uri ->
                        AttachmentThumbnail(
                            uri = uri,
                            onRemove = { images = images - uri },
                        )
                    }
                }
            }

            VoiceRecorderRow(
                isRecording = recorder.isRecording,
                hasRecording = recorder.recordedFile != null,
                elapsedSeconds = elapsedSeconds,
                onStart = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) recorder.start() else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = recorder::stop,
                onDiscard = recorder::discard,
            )
        }

        // 5) Email facoltativa
        Column {
            FormLabel("La tua email (facoltativa)")
            OutlinedTextField(
                value = contactEmail,
                onValueChange = { contactEmail = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("email@esempio.com") },
                supportingText = { Text("Lasciala solo se vuoi essere ricontattato.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = MaterialTheme.shapes.large,
            )
        }

        Button(
            onClick = {
                val selectedCategory = category ?: return@Button
                val selectedSubtype = subtype ?: return@Button
                if (recorder.isRecording) recorder.stop()
                onSubmit(
                    FeedbackDraft(
                        category = selectedCategory,
                        subtype = selectedSubtype,
                        message = message,
                        contactEmail = contactEmail,
                        imageUris = images,
                        audioUri = recorder.uri(),
                    ),
                )
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Invia segnalazione")
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun AttachmentThumbnail(
    uri: Uri,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.size(76.dp)) {
        AsyncImage(
            model = uri,
            contentDescription = "Immagine allegata",
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
        )
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
        ) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Rimuovi immagine",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceRecorderRow(
    isRecording: Boolean,
    hasRecording: Boolean,
    elapsedSeconds: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    val durationLabel = "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    when {
        isRecording -> Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Registrazione… $durationLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Ferma registrazione",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        hasRecording -> Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Messaggio vocale ($durationLabel)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDiscard) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Elimina messaggio vocale",
                    )
                }
            }
        }

        else -> OutlinedButton(onClick = onStart) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Registra messaggio vocale")
        }
    }
}
