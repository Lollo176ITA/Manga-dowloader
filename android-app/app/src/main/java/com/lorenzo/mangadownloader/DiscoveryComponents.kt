package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Componenti riusabili della vetrina AniList "Scopri". Vivono qui (non in una schermata-tab, che
 * non esiste più) perché il blocco Scopri della Home li compone: un carosello orizzontale di
 * poster ([discoverySection] + [DiscoveryCard]) e il dialog con la trama ([AniListInfoDialog]).
 * Il tap su un poster fa il "ponte" verso la ricerca aggregata (vedi [MangaViewModel.onPickAniListManga]).
 */

/** Sezione a carosello: header + [LazyRow] di [DiscoveryCard]. Non renderizza nulla se [items] è vuota. */
internal fun androidx.compose.foundation.lazy.LazyListScope.discoverySection(
    title: String,
    items: List<AniListManga>,
    onPick: (AniListManga) -> Unit,
    onShowInfo: (AniListManga) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item(key = "row-$title") {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { manga ->
                DiscoveryCard(
                    manga = manga,
                    onClick = { onPick(manga) },
                    onShowInfo = { onShowInfo(manga) },
                )
            }
        }
    }
}

@Composable
internal fun DiscoveryCard(
    manga: AniListManga,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    onClick: () -> Unit,
    onShowInfo: () -> Unit,
) {
    // Sottile wrapper sul poster condiviso [MangaPosterCard]: badge info in alto a sinistra e
    // valutazione in alto a destra. `fillWidth=false` → larghezza da carosello; le griglie
    // passano `fillWidth=true`. Gli slot già posizionano e "paddano" i badge.
    MangaPosterCard(
        coverModel = manga.coverUrl,
        title = manga.displayTitle(),
        modifier = modifier,
        onClick = onClick,
        onClickLabel = "Apri",
        fillWidth = fillWidth,
        topStartBadge = { InfoBadge(onClick = onShowInfo) },
        topEndBadge = manga.averageScore?.let { score ->
            { ScoreBadge(score = score) }
        },
    )
}

@Composable
private fun ScoreBadge(
    score: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = "Valutazione $score percento" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ThumbUp,
            contentDescription = null,
            tint = FavoriteYellow,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = "$score%",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun AniListInfoDialog(
    manga: AniListManga,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(manga.displayTitle()) },
        text = {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                manga.status.displayLabel()?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (manga.genres.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(manga.genres, key = { it }) { genre ->
                            AssistChip(onClick = {}, label = { Text(genre) })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = manga.description?.takeIf { it.isNotBlank() } ?: "Trama non disponibile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        },
    )
}
