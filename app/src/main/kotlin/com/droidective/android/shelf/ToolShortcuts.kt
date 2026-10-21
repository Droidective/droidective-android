package com.droidective.android.shelf

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.surface.Launch
import com.droidective.android.ui.Symbols
import com.droidective.core.features.FeatureDef
import com.droidective.core.features.Features
import com.droidective.core.features.ToolShelf

/**
 * Tools as launcher shortcuts: the ones long-pressing the app's icon offers, and the ones pinned to
 * the home screen.
 */
object ToolShortcuts {
    /** What launchers show from a long press; more than this is cut, or hidden. */
    const val ON_THE_ICON = 4

    fun idOf(toolId: String) = "$PREFIX$toolId"

    /** Puts the shelf's tools on the app's icon: pinned first, then the most recent. */
    fun publish(context: Context, shelf: ToolShelf) {
        val shortcuts =
            shelf.shortcuts(ON_THE_ICON).mapNotNull(Features::byId).mapIndexed { rank, feature ->
                shortcut(context, feature, rank)
            }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /** Whether the launcher takes shortcuts pinned to the home screen. */
    fun canPin(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the launcher to put [feature] on the home screen. The launcher asks the person first.
     *
     * @return false when the launcher won't take one.
     */
    fun pinToHome(context: Context, feature: FeatureDef): Boolean =
        canPin(context) &&
            ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, feature, 0), null)

    fun shortcut(context: Context, feature: FeatureDef, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, idOf(feature.id))
            .setShortLabel(feature.title.take(SHORT_LABEL))
            .setLongLabel(feature.title)
            .setIcon(icon(context, feature.icon))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                    .putExtra(Launch.EXTRA_TOOL, feature.id)
            )
            .setRank(rank)
            .build()

    /**
     * The tool's symbol on the app icon's own background. The symbols are white, for widgets and
     * tiles; alone on a light launcher they would not show at all.
     */
    private fun icon(context: Context, symbol: String): IconCompat {
        val density = context.resources.displayMetrics.density
        val size = (ADAPTIVE_DP * density).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher_background)?.run {
            setBounds(0, 0, size, size)
            draw(canvas)
        }
        val glyph = (GLYPH_DP * density).toInt()
        val inset = (size - glyph) / 2
        val drawable = Symbols.byName[symbol]?.outlined ?: R.drawable.sym_build
        ContextCompat.getDrawable(context, drawable)?.run {
            setBounds(inset, inset, inset + glyph, inset + glyph)
            draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    private const val PREFIX = "tool-"
    private const val SHORT_LABEL = 25

    /** An adaptive icon's full size; launchers mask it down to the middle 72. */
    private const val ADAPTIVE_DP = 108
    private const val GLYPH_DP = 44
}
