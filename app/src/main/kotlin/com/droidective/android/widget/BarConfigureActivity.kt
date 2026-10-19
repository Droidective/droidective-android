package com.droidective.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.droidective.android.editor.LayoutEditor
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.DroidectiveTheme
import kotlinx.coroutines.launch

/** Opens when a bar is added with configuration, or reconfigured from its long-press menu. */
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
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Backing out still keeps the widget: it starts with the starter bar.
        setResult(RESULT_OK, result)
        val layoutId =
            ToggleBarWidget.ensureLayout(
                    this,
                    widgetId,
                    WidgetReceiver.starterFor(this, widgetId),
                )
                .id
        setContent {
            DroidectiveTheme {
                Surface(Modifier.safeDrawingPadding()) {
                    LayoutEditor(layoutId) {
                        lifecycleScope.launch {
                            Surfaces.refresh(this@BarConfigureActivity)
                            finish()
                        }
                    }
                }
            }
        }
    }
}
