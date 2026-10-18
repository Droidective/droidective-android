package com.droidective.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.widget.AppIcons
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellIcon

/** A cell as the app draws it: in folders, previews and the editor. */
@Composable
fun CellButton(
    cell: Cell,
    onColor: Color,
    offColor: Color,
    showLabel: Boolean,
    onClick: (() -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier.clip(RoundedCornerShape(12.dp))
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            CellIconView(cell, if (cell.active) onColor else offColor, Modifier.size(28.dp))
            if (cell.degraded) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(LocalLevelColors.current.shell)
                )
            }
        }
        if (showLabel) {
            Text(
                cell.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun CellIconView(cell: Cell, tint: Color, modifier: Modifier) {
    val context = LocalContext.current
    when (val icon = cell.icon) {
        is CellIcon.Symbol -> {
            val symbol = Symbols.byName[icon.name] ?: Symbols.byName.getValue("toggle_on")
            Icon(
                painterResource(if (cell.active) symbol.filled else symbol.outlined),
                contentDescription = cell.label,
                tint = tint,
                modifier = modifier,
            )
        }
        is CellIcon.AppIcon -> {
            val bitmap = remember(icon.packageName) { AppIcons.bitmap(context, icon.packageName) }
            Image(bitmap.asImageBitmap(), contentDescription = cell.label, modifier = modifier)
        }
        is CellIcon.File -> {
            val bitmap = remember(icon.path) { AppIcons.file(context, icon.path) }
            Image(bitmap.asImageBitmap(), contentDescription = cell.label, modifier = modifier)
        }
    }
}
