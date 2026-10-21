package com.droidective.android.shelf

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidective.android.surface.Launch
import com.droidective.core.features.ToolShelf
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ToolShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun onTheIcon() =
        ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }

    @Test
    fun theIconOffersPinnedToolsThenRecentOnes() {
        val shelf =
            ToolShelf()
                .opened("logcat")
                .opened("apps")
                .opened("network-traffic")
                .opened("terminal")
                .opened("device-info")
                .pinning("screen-record", true)

        ToolShortcuts.publish(context, shelf)

        assertEquals(
            listOf("screen-record", "device-info", "terminal", "network-traffic").map {
                "tool-$it"
            },
            onTheIcon().map { it.id },
        )
    }

    @Test
    fun aShortcutOpensItsTool() {
        ToolShortcuts.publish(context, ToolShelf().opened("logcat"))

        val shortcut = onTheIcon().single()
        assertEquals("Logcat", shortcut.shortLabel)
        val intent = shortcut.intent
        assertEquals(Launch.DESTINATION_TOOL, intent.getStringExtra(Launch.EXTRA_DESTINATION))
        assertEquals("logcat", intent.getStringExtra(Launch.EXTRA_TOOL))
    }

    @Test
    fun aToolNoLongerInTheAppIsNotOffered() {
        ToolShortcuts.publish(context, ToolShelf(recent = listOf("gone", "apps")))

        assertEquals(listOf("tool-apps"), onTheIcon().map { it.id })
    }

    @Test
    fun anEmptyShelfLeavesTheIconBare() {
        ToolShortcuts.publish(context, ToolShelf().opened("logcat"))
        ToolShortcuts.publish(context, ToolShelf())

        assertEquals(emptyList(), onTheIcon())
    }
}
