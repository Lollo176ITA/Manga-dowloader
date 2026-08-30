package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Hero "Riprendi" della Home (stile mockup): card su `primaryContainer` con copertina, sopratitolo
 * RIPRENDI, serie + capitolo, barra di progresso lineare standard e "pagina X di Y".
 * Tap = riprende il reader all'ultima pagina. Riusa i dati già in [ResumeReadingItem], che
 * appiattisce le due provenienze possibili: un capitolo scaricato o una lettura in streaming.
 * Con [compact] (taglia S del blocco) la card si stringe: mini-cover, niente barra di progresso.
 */
@Composable
fun HomeResumeCard(
    item: ResumeReadingItem,
    onResume: (ResumeTarget) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onResume(item.target) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                model = item.coverModel,
                title = item.seriesTitle,
                modifier = Modifier
                    .size(
                        width = if (compact) 40.dp else 64.dp,
                        height = if (compact) 56.dp else 92.dp,
                    )
                    .clip(MaterialTheme.shapes.medium),
            )
            Column(modifier = Modifier.weight(1f)) {
                if (!compact) {
                    Text(
                        text = "RIPRENDI",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = item.seriesTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.chapterLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val idx = item.pageIndex
                val count = item.pageCount
                if (idx != null && count != null && count > 0) {
                    if (compact) {
                        Text(
                            text = "pagina ${idx + 1} di $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { ((idx + 1).toFloat() / count).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "pagina ${idx + 1} di $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = "Riprendi la lettura",
                modifier = Modifier.size(if (compact) 32.dp else 40.dp),
            )
        }
    }
}
