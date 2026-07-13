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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onResult: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = rememberFeedbackAudioRecorder()
    var submitting by remember { mutableStateOf(false) }

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
    var elapsedSeconds by remember { mutableIntStateOf(0) }
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
            DropdownField(
                value = category?.label.orEmpty(),
                placeholder = "Scegli il tipo",
                options = ReportCategory.entries.map { it.label },
                onSelect = { label ->
                    val picked = ReportCategory.entries.first { it.label == label }
                    if (category != picked) {
                        category = picked
                        subtype = null
                    }
                },
            )
        }

        // 2) Sottotipo (dipende dalla categoria)
        category?.let { selectedCategory ->
            Column {
                FormLabel("Che tipo?")
                DropdownField(
                    value = subtype.orEmpty(),
                    placeholder = "Scegli…",
                    options = selectedCategory.subtypes(),
                    onSelect = { subtype = it },
                )
            }
        }

        // 3) Descrizione (compare dopo aver scelto il sottotipo)
        if (subtype != null) Column {
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

        // 4) Allegati (compaiono dopo la descrizione)
        if (canSubmit) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        // 5) Email facoltativa (dopo la descrizione)
        if (canSubmit) Column {
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

        // Invia: compare quando i campi obbligatori (tipo, sottotipo, descrizione) sono compilati.
        if (canSubmit) Button(
            onClick = {
                val selectedCategory = category ?: return@Button
                val selectedSubtype = subtype ?: return@Button
                if (recorder.isRecording) recorder.stop()
                val draft = FeedbackDraft(
                    category = selectedCategory,
                    subtype = selectedSubtype,
                    message = message,
                    contactEmail = contactEmail,
                    imageUris = images,
                    audioFile = recorder.recordedFile,
                )
                scope.launch {
                    submitting = true
                    val ok = FeedbackReporter.sendReport(context, draft)
                    submitting = false
                    if (ok) {
                        // Reset del form dopo l'invio: riaprendolo è pulito (prima la descrizione restava).
                        category = null
                        subtype = null
                        message = ""
                        contactEmail = ""
                        images = emptyList()
                        recorder.discard()
                    }
                    onResult(ok)
                }
            },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) {
                AppLoadingIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (submitting) "Invio…" else "Invia segnalazione")
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

/** Menù a tendina (select) read-only sullo stile del selettore server dell'app. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    value: String,
    placeholder: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
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
