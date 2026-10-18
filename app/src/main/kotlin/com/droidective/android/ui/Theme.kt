package com.droidective.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.droidective.core.AccessLevel

// Droidective's BrandAccent and the neutrals around it, matching the Mac app and the website.
private val light =
    lightColorScheme(
        primary = Color(0xFF468A10),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD7EDC4),
        onPrimaryContainer = Color(0xFF14290A),
        background = Color(0xFFF3F5F1),
        onBackground = Color(0xFF161A17),
        surface = Color(0xFFF3F5F1),
        onSurface = Color(0xFF161A17),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE6EAE3),
        onSurfaceVariant = Color(0xFF4B544D),
        outline = Color(0xFF8A938B),
        outlineVariant = Color(0xFFD3D9D0),
    )

private val dark =
    darkColorScheme(
        primary = Color(0xFF6ECC1F),
        onPrimary = Color(0xFF0A0C0B),
        primaryContainer = Color(0xFF274312),
        onPrimaryContainer = Color(0xFFCBEBB0),
        background = Color(0xFF0E110F),
        onBackground = Color(0xFFE6EAE4),
        surface = Color(0xFF0E110F),
        onSurface = Color(0xFFE6EAE4),
        surfaceContainer = Color(0xFF151916),
        surfaceVariant = Color(0xFF1B201C),
        onSurfaceVariant = Color(0xFFA6AFA7),
        outline = Color(0xFF6F786F),
        outlineVariant = Color(0xFF2A302B),
    )

/** The colour of each access level's chip, the same four hues the plan uses. */
@Immutable
data class LevelColors(val app: Color, val granted: Color, val shell: Color, val root: Color) {
    fun of(level: AccessLevel): Color =
        when (level) {
            AccessLevel.App -> app
            AccessLevel.Granted -> granted
            AccessLevel.Shell -> shell
            AccessLevel.Root -> root
        }
}

private val lightLevels =
    LevelColors(Color(0xFF4F5D68), Color(0xFF1F62A6), Color(0xFF9A5A06), Color(0xFFB0352C))
private val darkLevels =
    LevelColors(Color(0xFFA9B6C0), Color(0xFF7FB4EE), Color(0xFFF0B552), Color(0xFFF08077))

val LocalLevelColors = staticCompositionLocalOf { lightLevels }

@Composable
fun DroidectiveTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLevelColors provides if (isDark) darkLevels else lightLevels
    ) {
        MaterialTheme(colorScheme = if (isDark) dark else light, content = content)
    }
}
