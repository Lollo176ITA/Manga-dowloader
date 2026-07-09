package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Intestazione di sezione della Home: titolo (heading per lo screen reader) + azione trailing opzionale. */
@Composable
fun HomeSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (trailingActionLabel != null && onTrailingAction != null) {
            TextButton(onClick = onTrailingAction) { Text(trailingActionLabel) }
        }
    }
}

/** Sezione Home: intestazione + contenuto in colonna. */
@Composable
fun HomeSection(
    title: String,
    modifier: Modifier = Modifier,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HomeSectionTitle(
            title = title,
            trailingActionLabel = trailingActionLabel,
            onTrailingAction = onTrailingAction,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Carosello orizzontale standard della Home (contentPadding 16dp, spaziatura 12dp). */
@Composable
fun <T> HomeCarousel(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items) { itemContent(it) }
    }
}
