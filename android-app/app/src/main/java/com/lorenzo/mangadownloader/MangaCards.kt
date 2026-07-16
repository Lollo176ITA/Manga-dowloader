package com.lorenzo.mangadownloader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le **card manga condivise** dell'app: un poster ([MangaPosterCard]) e una riga con
 * mini-copertina ([MangaRowCard]). Ogni schermata le compone via slot (badge, trailing)
 * invece di duplicare layout quasi uguali: una modifica qui arriva ovunque.
 *
 * Le dimensioni derivano dalla **densità globale** ([CardDensity], impostazione stile tema)
 * fornita via [LocalCardDensity] alla radice dell'app: niente parametri-taglia da infilare
 * in ogni call site.
 */
enum class CardDensity(val label: String) {
    COMPACT("Compatta"),
    NORMAL("Normale"),
    LARGE("Grande"),
}

/** Densità corrente delle card, fornita in MainActivity dalle impostazioni. */
val LocalCardDensity = compositionLocalOf { CardDensity.NORMAL }

/** Larghezza dei poster nei caroselli. */
val CardDensity.posterWidth: Dp
    get() = when (this) {
        CardDensity.COMPACT -> 92.dp
        CardDensity.NORMAL -> 110.dp
        CardDensity.LARGE -> 132.dp
    }

/** Larghezza delle card-riga nei caroselli orizzontali. */
val CardDensity.rowCardWidth: Dp
    get() = when (this) {
        CardDensity.COMPACT -> 172.dp
        CardDensity.NORMAL -> 200.dp
        CardDensity.LARGE -> 236.dp
    }

/** Lato della mini-copertina nelle card-riga (larghezza; altezza = 1.4x). */
private val CardDensity.rowCoverWidth: Dp
    get() = when (this) {
        CardDensity.COMPACT -> 36.dp
        CardDensity.NORMAL -> 40.dp
        CardDensity.LARGE -> 46.dp
    }

/**
 * Poster manga condiviso, nella variante più ricca (stile ResultCard): card con copertina 2:3,
 * badge sovrapponibili nei tre angoli (info, stella/azioni, fonte/conteggi...), titolo su due
 * righe con slot finale (es. icona stato lettura). [fillWidth] per le griglie (larghezza dal
 * parent); nei caroselli `false` → larghezza da [CardDensity.posterWidth]. [onClick] null =
 * non cliccabile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaPosterCard(
    coverModel: Any?,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    fillWidth: Boolean = true,
    cardStateDescription: String? = null,
    topStartBadge: (@Composable BoxScope.() -> Unit)? = null,
    topEndBadge: (@Composable BoxScope.() -> Unit)? = null,
    bottomStartBadge: (@Composable BoxScope.() -> Unit)? = null,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val density = LocalCardDensity.current
    val clickModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            onClick = onClick,
            onClickLabel = onClickLabel,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
        )
        onClick != null -> Modifier.clickable(onClick = onClick, onClickLabel = onClickLabel)
        else -> Modifier
    }
    Card(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(density.posterWidth))
            .then(clickModifier)
            .semantics(mergeDescendants = false) {
                cardStateDescription?.let { stateDescription = it }
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = appCardColors(),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    model = coverModel,
                    title = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.extraLarge),
                )
                topStartBadge?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        content = it,
                    )
                }
                topEndBadge?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        content = it,
                    )
                }
                bottomStartBadge?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                        content = it,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                titleTrailing?.invoke(this)
            }
        }
    }
}

/** Pallino "non visto/non letto" usato come trailing nelle card-riga. */
@Composable
fun UnseenDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/** Badge circolare con un conteggio (es. capitoli non letti), per gli angoli del poster. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$count",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .clearAndSetSemantics {},
    )
}

/**
 * Riga con mini-copertina: titolo + sottotitolo (+ [caption] colorata, es. il progresso) e
 * slot [leading]/[trailing] (posizione in classifica, pallino non letto...). Nei caroselli
 * passa [inCarousel]=true ([CardDensity.rowCardWidth]); nelle liste occupa tutta la larghezza.
 */
@Composable
fun MangaRowCard(
    coverModel: Any?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    caption: String? = null,
    captionColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    inCarousel: Boolean = false,
    cardStateDescription: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val density = LocalCardDensity.current
    Card(
        modifier = modifier
            .then(if (inCarousel) Modifier.width(density.rowCardWidth) else Modifier.fillMaxWidth())
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick, onClickLabel = onClickLabel)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                cardStateDescription?.let { stateDescription = it }
            },
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading?.invoke(this)
            CoverImage(
                model = coverModel,
                title = title,
                modifier = Modifier
                    .size(width = density.rowCoverWidth, height = density.rowCoverWidth * 1.4f)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                caption?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = captionColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}
