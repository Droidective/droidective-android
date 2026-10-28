package com.droidective.core.layout

import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.ShellProvider
import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTest {
    private val dir: File = createTempDirectory("layouts").toFile()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    private fun bar(vararg items: Item) = Layout("home", LayoutKind.Bar, "Home", items.toList())

    private fun toggle(key: String, id: String = "wifi") = Item.Toggle(key, id)

    @Test
    fun addsUpToTheKindsLimitAndRefusesDuplicatesAndNestedFolders() {
        val full = Layout("n", LayoutKind.Notification, "Shade", (1..6).map { toggle("t$it") })
        assertIs<LayoutEdits.Result.Refused>(LayoutEdits.add(full, toggle("t7"), now = 1))
        assertIs<LayoutEdits.Result.Refused>(
            LayoutEdits.add(bar(toggle("a")), toggle("a"), now = 1)
        )
        val folder = Layout("f", LayoutKind.Folder, "More")
        assertIs<LayoutEdits.Result.Refused>(
            LayoutEdits.add(folder, Item.Folder("x", "f2"), now = 1)
        )
        val added = LayoutEdits.add(bar(), toggle("a"), now = 42)
        assertEquals(42, (added as LayoutEdits.Result.Done).layout.editedAt)
    }

    @Test
    fun movesLikeADragAndClampsPastTheEnds() {
        val layout = bar(toggle("a"), toggle("b"), toggle("c"))
        assertEquals(
            listOf("b", "c", "a"),
            LayoutEdits.move(layout, 0, 2, now = 1).items.map { it.key },
        )
        assertEquals(
            listOf("c", "a", "b"),
            LayoutEdits.move(layout, 2, -5, now = 1).items.map { it.key },
        )
        assertEquals(layout, LayoutEdits.move(layout, 9, 0, now = 1))
    }

    @Test
    fun theFileFormatIsStable() {
        // Layout files outlive app versions. If this changes, old files must still load.
        val layout =
            Layout(
                id = "home",
                kind = LayoutKind.Bar,
                name = "Home",
                items =
                    listOf(
                        Item.Toggle("a", "wifi"),
                        Item.App("b", "com.example", "com.example.Main", "Example"),
                        Item.Shortcut("c", "Chat", "intent:#Intent;end", "icons/c.png"),
                        Item.Folder("d", "more"),
                        Item.DeepLink("e", "Onboarding", "myapp://onboarding"),
                        Item.SettingsScreen("f", "Wi-Fi", "android.settings.WIFI_SETTINGS"),
                    ),
                editedAt = 7,
            )
        val json = JsonFileStore.json.encodeToString(Layout.serializer(), layout)
        assertTrue("\"type\": \"toggle\"" in json && "\"kind\": \"bar\"" in json, json)
        assertEquals(layout, JsonFileStore.json.decodeFromString(Layout.serializer(), json))
    }

    @Test
    fun aCorruptFileIsSetAsideNotOverwritten() {
        val file = File(dir, "x.json").apply { writeText("{ not json") }
        val store = JsonFileStore(file, Layout.serializer()) { bar() }
        assertEquals(bar(), store.load())
        assertTrue(File(dir, "x.json.corrupt").readText() == "{ not json")
    }

    @Test
    fun repositoryKeepsLayoutsAndWidgetBindings() {
        val repo = LayoutRepository(dir)
        repo.save(bar(toggle("a")).copy(editedAt = 2))
        repo.save(Layout("work", LayoutKind.Bar, "Work", editedAt = 5))
        repo.bind(17, "home")
        assertEquals(listOf("work", "home"), repo.all().map { it.id })
        assertEquals("home", repo.layoutFor(17)?.id)
        assertEquals(listOf(17), repo.widgetsShowing("home"))
        repo.unbind(17)
        assertNull(repo.layoutFor(17))
        assertEquals("home", repo.get("home")?.id, "unbinding a widget keeps its layout")
        repo.bind(18, "home")
        repo.delete("home")
        assertNull(repo.layoutFor(18))
        assertTrue(repo.isBound(18), "the widget can say its layout was deleted")
        assertTrue(!repo.isBound(17))
    }

    @Test
    fun aLayoutPlacedFromTheAppGoesToTheNextNewWidgetOnce() {
        val repo = LayoutRepository(dir)
        repo.placing("work", now = 1_000)
        assertEquals("work", repo.takePlacing(now = 5_000))
        assertNull(repo.takePlacing(now = 6_000), "only once")
        repo.placing("work", now = 1_000)
        assertNull(
            repo.takePlacing(now = 1_000 + LayoutRepository.PLACING_MILLIS + 1),
            "a placing the launcher never finished doesn't catch a widget added later",
        )
    }

    @Test
    fun widgetsRestoredUnderNewIdsKeepTheirLayouts() {
        val repo = LayoutRepository(dir)
        repo.save(bar(toggle("a")))
        repo.bind(17, "home")
        repo.bind(18, "home")
        repo.restored(oldIds = listOf(17, 18), newIds = listOf(41, 42))
        assertEquals("home", repo.layoutFor(41)?.id)
        assertEquals(listOf(41, 42), repo.widgetsShowing("home").sorted())
        assertTrue(!repo.isBound(17))
    }

    @Test
    fun deletingACommandTakesItsCellsOutOfEveryLayout() {
        val repo = LayoutRepository(dir)
        repo.save(bar(Item.Command("a", "cmd-1"), toggle("b")))
        repo.save(
            Layout("folder-x", LayoutKind.Folder, "Tools", listOf(Item.Command("c", "cmd-1")))
        )
        repo.save(Layout("other", LayoutKind.Bar, "Other", listOf(Item.Command("d", "cmd-2"))))
        repo.dropCommand("cmd-1", now = 5)
        assertEquals(listOf("b"), repo.get("home")?.items?.map { it.key })
        assertEquals(5, repo.get("home")?.editedAt)
        assertEquals(emptyList(), repo.get("folder-x")?.items)
        assertEquals(0, repo.get("other")?.editedAt, "a layout without the command is untouched")
    }

    @Test
    fun layoutIdsCantEscapeTheirFolder() {
        val repo = LayoutRepository(dir)
        assertFailsWith<IllegalArgumentException> { repo.get("../secrets") }
        assertFailsWith<IllegalArgumentException> { repo.save(Layout("A.B", LayoutKind.Bar, "x")) }
    }

    @Test
    fun aShellToggleWithoutShellOpensItsStandIn() {
        val cell = Cells.plan(toggle("a", "wifi"), "on", Access.Nothing, folderName = { null })!!
        assertEquals(Tap.Open(Target.SystemScreen("android.settings.panel.action.WIFI")), cell.tap)
        assertTrue(cell.degraded)
        assertTrue(cell.active)
    }

    @Test
    fun aShellToggleWithNoStandInOpensSetup() {
        val cell =
            Cells.plan(toggle("a", "layout-bounds"), "off", Access.Nothing, folderName = { null })!!
        assertEquals(Tap.Open(Target.Setup(AccessLevel.Shell)), cell.tap)
    }

    @Test
    fun withShellTheToggleRunsInPlace() {
        val access = Access(granted = false, shell = ShellProvider.Shizuku)
        val cell = Cells.plan(toggle("a", "wifi"), "off", access, folderName = { null })!!
        assertEquals(Tap.RunToggle("wifi"), cell.tap)
        assertEquals(false, cell.active)
        assertEquals(false, cell.degraded)
    }

    @Test
    fun screenTogglesOpenTheirScreenAndGoneThingsDrawNothing() {
        assertEquals(
            Tap.Open(Target.ToggleScreen("brightness-slider")),
            Cells.plan(
                    toggle("a", "brightness-slider"),
                    null,
                    Access.Nothing,
                    folderName = { null },
                )
                ?.tap,
        )
        assertNull(
            Cells.plan(toggle("a", "removed-toggle"), null, Access.Nothing, folderName = { null })
        )
        assertNull(
            Cells.plan(toggle("a", "hotspot"), null, Access.Nothing, folderName = { null }),
            "pending",
        )
        assertNull(
            Cells.plan(Item.Folder("f", "gone"), null, Access.Nothing, folderName = { null })
        )
    }
}
