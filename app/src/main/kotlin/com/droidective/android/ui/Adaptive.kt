package com.droidective.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How many columns of rows the list around this composable lays out: 1 on a phone. */
val LocalColumns = compositionLocalOf { 1 }

/** The narrowest a row gets before a list drops a column: two fit a tablet held upright. */
val RowMinWidth = 360.dp

/**
 * Hands [content] how many [minWidth]-wide columns this space fits, and provides it as
 * [LocalColumns]. The space, not the window, decides, so a pane beside a rail or in split screen
 * gets what fits it.
 */
@Composable
fun AdaptiveColumns(
    modifier: Modifier = Modifier,
    minWidth: Dp = RowMinWidth,
    content: @Composable (columns: Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val columns = (maxWidth / minWidth).toInt().coerceAtLeast(1)
        CompositionLocalProvider(LocalColumns provides columns) { content(columns) }
    }
}

/**
 * [items] as a list on a phone, and in rows of [columns] where there's room, every row as tall as
 * its tallest item so the cards line up. [content] gets the modifier that sizes its item.
 */
fun <T> LazyListScope.adaptiveItems(
    items: List<T>,
    columns: Int,
    key: (T) -> Any,
    content: @Composable RowScope.(item: T, modifier: Modifier) -> Unit,
) {
    if (columns <= 1) {
        items(items, key = key) { item -> Row { content(item, Modifier.fillMaxWidth()) } }
        return
    }
    items(items.chunked(columns), key = { row -> "row-${key(row.first())}" }) { row ->
        Row(
            Modifier.fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { item -> content(item, Modifier.weight(1f).fillMaxHeight()) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** [items] in rows of [columns] outside a lazy list, such as in a scrolling column of cards. */
@Composable
fun <T> AdaptiveRows(
    items: List<T>,
    columns: Int,
    content: @Composable RowScope.(item: T, modifier: Modifier) -> Unit,
) {
    items.chunked(columns.coerceAtLeast(1)).forEach { row ->
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { item -> content(item, Modifier.weight(1f).fillMaxHeight()) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A row's background: the screen's own in a list, a card where rows sit side by side. */
@Composable
fun rowContainerColor(): Color =
    if (LocalColumns.current > 1) MaterialTheme.colorScheme.surfaceContainerLow
    else MaterialTheme.colorScheme.surface
