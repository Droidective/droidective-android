package com.droidective.android.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.droidective.android.droidective
import com.droidective.android.editor.LayoutEditor
import com.droidective.android.editor.Preview
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.DroidectiveTheme
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.previewStyle
import kotlinx.coroutines.launch

/**
 * Opens when a bar is added with configuration, reconfigured from its long-press menu, or tapped
 * once its layout was deleted. With layouts already made it asks which one the widget shows, so a
 * layout taken off the home screen can be put back from the launcher's own widget list too.
 */
class BarConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId =
            intent
                ?.extras
                ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Backing out still keeps the widget: it shows what it showed, or its starter.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val starter = WidgetReceiver.starterFor(this, widgetId)
        val current = ToggleBarWidget.ensureLayout(this, widgetId, starter)
        val others =
            droidective.layouts.all().filter { it.kind == LayoutKind.Bar && it.id != current?.id }
        // Nothing to choose between: straight to editing, as before.
        val first =
            if (others.isEmpty()) {
                (current ?: ToggleBarWidget.newStarter(this, widgetId, starter)).id
            } else {
                null
            }
        setContent {
            DroidectiveTheme {
                Surface(Modifier.safeDrawingPadding()) {
                    var editing by rememberSaveable { mutableStateOf(first) }
                    val id = editing
                    if (id != null) {
                        LayoutEditor(id, ::done)
                    } else {
                        Chooser(
                            current,
                            others,
                            onOwn = {
                                editing =
                                    (current ?: ToggleBarWidget.newStarter(this, widgetId, starter))
                                        .id
                            },
                            onPick = { layout ->
                                ToggleBarWidget.show(this, widgetId, layout.id)
                                done()
                            },
                        )
                    }
                }
            }
        }
    }

    private fun done() {
        lifecycleScope.launch {
            Surfaces.refresh(this@BarConfigureActivity)
            finish()
        }
    }

    companion object {
        /** Opens for [widgetId], such as from the widget itself once its layout was deleted. */
        fun intent(context: Context, widgetId: Int): Intent =
            Intent(context, BarConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // One per widget, so two widgets' taps aren't taken for the same one.
                .setData("droidective://widget/$widgetId".toUri())
    }
}

/**
 * What the widget shows: its own layout first (or, with that deleted, a new bar), then every other
 * home-screen layout made so far.
 */
@Composable
private fun Chooser(
    current: Layout?,
    others: List<Layout>,
    onOwn: () -> Unit,
    onPick: (Layout) -> Unit,
) {
    val context = LocalContext.current
    val placed = WidgetReceiver.placedIds(context)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("What this widget shows", style = MaterialTheme.typography.titleLarge)
        Option(
            current?.name ?: "A new bar",
            if (current == null) "Its layout was deleted. Start a new one." else "Edit it",
            current,
            onOwn,
        )
        Text(
            "LAYOUTS YOU MADE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        for (layout in others) {
            val shown = context.droidective.layouts.widgetsShowing(layout.id).any { it in placed }
            Option(
                layout.name,
                if (shown) "Also on the home screen: both show the same" else "Show it here",
                layout,
            ) {
                onPick(layout)
            }
        }
    }
}

@Composable
private fun Option(title: String, note: String, layout: Layout?, onClick: () -> Unit) {
    val context = LocalContext.current
    val cells by
        produceState(emptyList<Cell>(), layout) {
            value = layout?.let { Surfaces.cells(context, it) }.orEmpty()
        }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (layout != null) Preview(layout.previewStyle(), cells)
        }
    }
}
