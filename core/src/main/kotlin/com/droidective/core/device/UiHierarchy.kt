package com.droidective.core.device

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand

/** A rectangle on the screen, in pixels, as `[left,top][right,bottom]`. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    /** No area on screen: a view scrolled out or clipped away reports bounds like this. */
    val isEmpty: Boolean
        get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

/**
 * One view on screen, as `uiautomator dump` describes it. [depth] is how deep it sits in the
 * hierarchy, [parent] the index of the node that holds it, or -1 for a window's root.
 */
data class UiNode(
    val depth: Int,
    val parent: Int,
    val className: String,
    val packageName: String,
    val resourceId: String,
    val text: String,
    val contentDescription: String,
    val bounds: Bounds?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val checked: Boolean?,
    val selected: Boolean,
    val password: Boolean,
) {
    /** What a person would call it: its text, its description, its id, or its class. */
    val label: String
        get() =
            text.ifBlank { null }
                ?: contentDescription.ifBlank { null }
                ?: resourceId.substringAfter(":id/").ifBlank { null }
                ?: className.substringAfterLast('.')
}

/**
 * The screen's view hierarchy through `uiautomator dump`: what UI tests select by, and what
 * TalkBack reads. Android lets one UI-automation client in at a time, so a running test, Appium or
 * another inspector makes the dump fail.
 */
object UiHierarchy {
    /** Prints the XML; uiautomator follows it with a line saying where it dumped to. */
    val dump: ShellCommand = ShellCommand.of("uiautomator", "dump", "/dev/stdout")

    private val tag = Regex("""<(/?)node\b([^>]*?)(/?)>""")
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")
    private val rect = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    /** Every node, depth first, in the order the dump lists them. Empty when there's no XML. */
    fun parse(output: String): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        val open = ArrayDeque<Int>()
        for (match in tag.findAll(output)) {
            val (closing, body, selfClosing) = match.destructured
            if (closing.isNotEmpty()) {
                open.removeLastOrNull()
                continue
            }
            val values =
                attribute.findAll(body).associate {
                    it.groupValues[1] to unescape(it.groupValues[2])
                }
            nodes += node(values, depth = open.size, parent = open.lastOrNull() ?: -1)
            if (selfClosing.isEmpty()) open.addLast(nodes.lastIndex)
        }
        return nodes
    }

    /** Why the dump failed, for the person: the usual reason is someone else holding it. */
    fun failure(exitCode: Int, output: String): String =
        when {
            exitCode == KILLED || "already registered" in output ->
                "Another UI-automation client holds the screen: a running test, Appium or " +
                    "agent-device. Stop it, then capture again."
            "null root node" in output || "could not get idle state" in output ->
                "The screen kept changing, so Android couldn't hold it still. Try again once " +
                    "it settles."
            else -> Parse.failure(output) ?: "uiautomator exited with $exitCode."
        }

    /** The deepest node whose bounds hold the point: what a tap there would land on. */
    fun at(nodes: List<UiNode>, x: Int, y: Int): Int? =
        nodes.indices
            .filter { nodes[it].bounds?.contains(x, y) == true }
            .maxWithOrNull(compareBy({ nodes[it].depth }, { it }))

    /**
     * Clickable views TalkBack has nothing to read for: no text or description on the view or on
     * anything inside it, since TalkBack reads a clickable view's children as its label.
     */
    fun unlabelled(nodes: List<UiNode>): List<Int> =
        nodes.indices.filter { index ->
            val node = nodes[index]
            (node.clickable || node.longClickable) &&
                node.bounds?.isEmpty != true &&
                subtree(nodes, index).none { readable(nodes[it]) }
        }

    /** [index] and every node below it: the run after it that sits deeper. */
    private fun subtree(nodes: List<UiNode>, index: Int): IntRange {
        val depth = nodes[index].depth
        var end = index + 1
        while (end < nodes.size && nodes[end].depth > depth) end++
        return index until end
    }

    private fun readable(node: UiNode) =
        node.text.isNotBlank() || node.contentDescription.isNotBlank()

    /**
     * Interactive views smaller than [minPx] either way: Android's guidance is 48 dp, so pass 48
     * times the screen's density. A view with no bounds isn't counted.
     */
    fun smallTargets(nodes: List<UiNode>, minPx: Int): List<Int> =
        nodes.indices.filter { index ->
            val node = nodes[index]
            val bounds = node.bounds
            (node.clickable || node.longClickable) &&
                bounds != null &&
                !bounds.isEmpty &&
                (bounds.width < minPx || bounds.height < minPx)
        }

    /**
     * Ways a UI test could find [node], best first, each as the line to paste. An id that more than
     * one view on screen shares, such as `android:id/title` on every row, would find them all, so
     * then the text comes first.
     */
    fun selectors(node: UiNode, screen: List<UiNode> = listOf(node)): List<Pair<String, String>> {
        val id = node.resourceId.substringAfter(":id/", "")
        val ofApp = node.resourceId.startsWith("${node.packageName}:id/")
        val text = node.text.takeIf { it.isNotBlank() }
        val description = node.contentDescription.takeIf { it.isNotBlank() }
        val unique =
            node.resourceId.isNotBlank() && screen.count { it.resourceId == node.resourceId } == 1
        val byId =
            listOfNotNull(
                node.resourceId
                    .takeIf { it.isNotBlank() }
                    ?.let { "UI Automator" to "By.res(${quoted(it)})" },
                id.takeIf { ofApp && it.isNotEmpty() }
                    ?.let { "Espresso" to "onView(withId(R.id.$it))" },
            )
        val byText =
            listOfNotNull(
                text?.let { "UI Automator" to "By.text(${quoted(it)})" },
                text?.let { "Compose" to "onNodeWithText(${quoted(it)})" },
                description?.let { "Compose" to "onNodeWithContentDescription(${quoted(it)})" },
            )
        val maestroId =
            id.takeIf { it.isNotEmpty() }?.let { "Maestro" to "tapOn:\n    id: ${quoted(it)}" }
        val maestroText = text?.let { "Maestro" to "tapOn: ${quoted(it)}" }
        return if (unique || text == null && description == null) {
            byId + byText + listOfNotNull(maestroId ?: maestroText)
        } else {
            byText + byId + listOfNotNull(maestroText ?: maestroId)
        }
    }

    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun node(values: Map<String, String>, depth: Int, parent: Int): UiNode {
        fun flag(name: String) = values[name] == "true"
        return UiNode(
            depth = depth,
            parent = parent,
            className = values["class"].orEmpty(),
            packageName = values["package"].orEmpty(),
            resourceId = values["resource-id"].orEmpty(),
            text = values["text"].orEmpty(),
            contentDescription = values["content-desc"].orEmpty(),
            bounds = values["bounds"]?.let(::bounds),
            clickable = flag("clickable"),
            longClickable = flag("long-clickable"),
            scrollable = flag("scrollable"),
            enabled = flag("enabled"),
            focused = flag("focused"),
            checked = if (flag("checkable")) flag("checked") else null,
            selected = flag("selected"),
            password = flag("password"),
        )
    }

    fun bounds(text: String): Bounds? =
        rect.find(text)?.destructured?.let { (left, top, right, bottom) ->
            Bounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        }

    private val entity = Regex("""&(amp|lt|gt|quot|apos|#(\d+)|#x([0-9a-fA-F]+));""")

    private fun unescape(value: String): String =
        entity.replace(value) { match ->
            val (name, decimal, hex) = match.destructured
            when {
                decimal.isNotEmpty() -> decimal.toInt().toChar().toString()
                hex.isNotEmpty() -> hex.toInt(HEX).toChar().toString()
                else -> ENTITIES.getValue(name)
            }
        }

    private val ENTITIES =
        mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
    private const val KILLED = 137
    private const val HEX = 16
}
