package com.droidective.android.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.Symbols

/**
 * A detail's frame: its title and actions over a scrolling column. With [onBack] it has a back
 * arrow, and system back goes back too; without, it sits beside the list it was opened from. With
 * [find], the placeholder of its find bar, a search button opens a find for the text inside it.
 */
@Composable
internal fun DetailFrame(
    title: String,
    onBack: (() -> Unit)?,
    find: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val margin = with(LocalDensity.current) { FIND_MARGIN.toPx() }
    val finder = remember(scroll, margin) { Finder(scroll, margin) }
    var finding by rememberSaveable { mutableStateOf(false) }
    fun closeFind() {
        finding = false
        finder.look("")
    }
    if (onBack != null) BackHandler(onBack = onBack)
    // After the detail's own: back closes the find first.
    BackHandler(enabled = finding, onBack = ::closeFind)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = if (onBack == null) 16.dp else 0.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(Symbols.byName.getValue("arrow_back").outlined), "Back")
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            if (find != null && !finding) {
                IconButton(onClick = { finding = true }) {
                    Icon(painterResource(Symbols.byName.getValue("search").outlined), find)
                }
            }
            actions()
        }
        if (find != null && finding) FindBar(finder, find, onClose = ::closeFind)
        CompositionLocalProvider(LocalFinder provides finder.takeIf { finding }) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scroll)
                    .onGloballyPositioned { finder.content = it }
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Room above a match a find scrolls to, so the lines before it give it context. */
private val FIND_MARGIN = 96.dp
