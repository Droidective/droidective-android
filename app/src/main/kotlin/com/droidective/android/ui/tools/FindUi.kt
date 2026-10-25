package com.droidective.android.ui.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.Symbols
import com.droidective.core.reactotron.ReactotronFind
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Find inside one detail: the word looked for, how many times each text holds it, which match is
 * current, and where each is, to scroll to. Texts report themselves through [FoundText], under ids
 * that sort in reading order, so next and previous go down the page.
 */
@Stable
internal class Finder(private val scroll: ScrollState, private val margin: Float) {
    var query by mutableStateOf("")
        private set

    var current by mutableIntStateOf(0)
        private set

    /** The scrolling content, which every match's place is measured from. */
    var content: LayoutCoordinates? = null

    private val counts = mutableStateMapOf<String, Int>()
    private val places = HashMap<String, Place>()
    private val order by derivedStateOf {
        counts.toSortedMap().flatMap { (id, count) -> List(count) { id to it } }
    }

    /** Where one text is, how it was laid out, and where its matches are in it. */
    private class Place(
        var coordinates: LayoutCoordinates? = null,
        var layout: TextLayoutResult? = null,
        var ranges: List<IntRange> = emptyList(),
    )

    val total: Int
        get() = order.size

    fun look(query: String) {
        this.query = query
        current = 0
    }

    fun isCurrent(id: String, index: Int): Boolean = order.getOrNull(current) == (id to index)

    fun counted(id: String, count: Int) {
        if (counts[id] != count) counts[id] = count
    }

    fun placed(id: String, coordinates: LayoutCoordinates) {
        places.getOrPut(id, ::Place).coordinates = coordinates
    }

    fun laidOut(id: String, layout: TextLayoutResult, ranges: List<IntRange>) {
        places.getOrPut(id, ::Place).apply {
            this.layout = layout
            this.ranges = ranges
        }
    }

    fun forget(id: String) {
        counts.remove(id)
        places.remove(id)
    }

    /** The next match, or with [by] -1 the one before, going round at either end. */
    suspend fun step(by: Int) {
        if (total == 0) return
        current = (current + by).mod(total)
        reveal()
    }

    /** Scrolls the current match into view, a little below the top of the detail. */
    suspend fun reveal() {
        val (id, index) = order.getOrNull(current) ?: return
        val place = places[id] ?: return
        val from = content?.takeIf { it.isAttached } ?: return
        val coordinates = place.coordinates?.takeIf { it.isAttached } ?: return
        val layout = place.layout ?: return
        val range = place.ranges.getOrNull(index) ?: return
        val line = layout.getLineTop(layout.getLineForOffset(range.first))
        val top = from.localPositionOf(coordinates, Offset.Zero).y + line
        scroll.animateScrollTo((top - margin).roundToInt().coerceAtLeast(0))
    }
}

/** The find of the detail around, while its find bar is open. */
internal val LocalFinder = compositionLocalOf<Finder?> { null }

/**
 * Text that a detail's find looks through: each match highlighted, the current one strongest. [id]
 * names it, and sorts in reading order among the detail's other found texts.
 */
@Composable
internal fun FoundText(id: String, text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val finder = LocalFinder.current
    if (finder == null) {
        Text(text, modifier, style = style)
        return
    }
    val ranges = remember(text, finder.query) { ReactotronFind.matches(text, finder.query) }
    DisposableEffect(finder, id) { onDispose { finder.forget(id) } }
    LaunchedEffect(finder, id, ranges.size) { finder.counted(id, ranges.size) }
    val match =
        SpanStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    val current =
        SpanStyle(
            background = MaterialTheme.colorScheme.primary,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    val shown = buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { index, range ->
            addStyle(
                if (finder.isCurrent(id, index)) current else match,
                range.first,
                range.last + 1,
            )
        }
    }
    Text(
        shown,
        modifier.onGloballyPositioned { finder.placed(id, it) },
        style = style,
        onTextLayout = { finder.laidOut(id, it, ranges) },
    )
}

/** The word to find, how many there are and which is current, next and previous, and close. */
@Composable
internal fun FindBar(finder: Finder, placeholder: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Once typing pauses, the first match comes into view.
    LaunchedEffect(finder.query) {
        delay(REVEAL_PAUSE_MILLIS)
        finder.reveal()
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = finder.query,
            onValueChange = finder::look,
            placeholder = { Text(placeholder) },
            suffix = {
                if (finder.query.isNotBlank()) {
                    Text(
                        if (finder.total == 0) "No matches"
                        else "${finder.current + 1} of ${finder.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { scope.launch { finder.step(1) } }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        FindButton("expand_less", "Previous match", enabled = finder.total > 0) {
            scope.launch { finder.step(-1) }
        }
        FindButton("expand_more", "Next match", enabled = finder.total > 0) {
            scope.launch { finder.step(1) }
        }
        FindButton("close", "Close find", enabled = true, onClick = onClose)
    }
}

@Composable
private fun FindButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(enabled = enabled, onClick = onClick) {
        Icon(painterResource(Symbols.byName.getValue(symbol).outlined), label)
    }
}

private const val REVEAL_PAUSE_MILLIS = 250L
