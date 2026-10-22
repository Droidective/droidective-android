package com.droidective.core.screenshot

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A place on the picture, as fractions of its width and height: the same mark on a phone-sized
 * preview and on the full-resolution picture it is saved onto.
 */
@Serializable data class Spot(val x: Float, val y: Float)

/**
 * A rectangle on the picture in the same fractions, its sides in order whichever way it was drawn.
 */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isEmpty: Boolean
        get() = right - left <= 0f || bottom - top <= 0f

    companion object {
        /** The area a drag from [from] to [to] covers, kept on the picture. */
        fun between(from: Spot, to: Spot): Area =
            Area(
                minOf(from.x, to.x).coerceIn(0f, 1f),
                minOf(from.y, to.y).coerceIn(0f, 1f),
                maxOf(from.x, to.x).coerceIn(0f, 1f),
                maxOf(from.y, to.y).coerceIn(0f, 1f),
            )
    }
}

/**
 * One thing drawn on a screenshot. Widths and sizes are fractions of the picture's width, so a line
 * keeps its weight when the picture is saved at its own size.
 */
@Serializable
sealed interface Mark {
    /** A freehand line; a highlight is drawn wide and see-through, under nothing in particular. */
    @Serializable
    @SerialName("line")
    data class Line(
        val points: List<Spot>,
        val color: Long,
        val width: Float,
        val highlight: Boolean = false,
    ) : Mark {
        /**
         * The line with [point] added, unless it is within [step] of the last one: a finger sends a
         * point for every frame, and most of them say nothing new.
         */
        fun adding(point: Spot, step: Float = MIN_STEP): Line {
            val last = points.lastOrNull()
            if (last != null && hypot(point.x - last.x, point.y - last.y) < step) return this
            return copy(points = points + point)
        }
    }

    @Serializable
    @SerialName("arrow")
    data class Arrow(val from: Spot, val to: Spot, val color: Long, val width: Float) : Mark

    @Serializable
    @SerialName("box")
    data class Box(val from: Spot, val to: Spot, val color: Long, val width: Float) : Mark

    @Serializable
    @SerialName("label")
    data class Label(val at: Spot, val text: String, val color: Long, val size: Float) : Mark

    /** A part of the picture hidden: painted over, or turned to blocks too coarse to read. */
    @Serializable
    @SerialName("redact")
    data class Redact(val from: Spot, val to: Spot, val style: Style) : Mark {
        enum class Style {
            Solid,
            Pixelate,
        }
    }

    companion object {
        /** How far a finger moves before a line takes another point, as a fraction of the width. */
        const val MIN_STEP = 0.002f
    }
}

/**
 * The marks on a screenshot, kept as every state they have been in, so any change — clearing
 * included — is one undo away. Every change gives a new history and leaves this one as it was.
 */
@Serializable
data class Markup(val states: List<List<Mark>> = listOf(emptyList()), val at: Int = 0) {
    val marks: List<Mark>
        get() = states[at]

    val canUndo: Boolean
        get() = at > 0

    val canRedo: Boolean
        get() = at < states.lastIndex

    /** [mark] added. What was taken back can't be put back after something new is drawn. */
    fun adding(mark: Mark): Markup = moving(marks + mark)

    /** Every mark off, as one step. */
    fun clear(): Markup = if (marks.isEmpty()) this else moving(emptyList())

    fun undo(): Markup = if (canUndo) copy(at = at - 1) else this

    fun redo(): Markup = if (canRedo) copy(at = at + 1) else this

    private fun moving(next: List<Mark>): Markup {
        val kept = states.take(at + 1) + listOf(next)
        // Oldest first out, so a long session can't grow without end.
        val trimmed = kept.takeLast(MAX_STATES)
        return Markup(trimmed, trimmed.lastIndex)
    }

    /** As text, for keeping across a rotation or the app being put away. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** How many steps back undo can go. */
        const val MAX_STATES = 100

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): Markup = json.decodeFromString(serializer(), text)
    }
}

/** The two short strokes an arrow ends in, worked out in real pixels so they aren't skewed. */
object ArrowHead {
    /**
     * Where the two strokes of [arrow]'s head end, on a picture [width] by [height] pixels: each
     * [length] long as a fraction of the width, at [ANGLE] radians either side of the shaft.
     */
    fun barbs(arrow: Mark.Arrow, width: Float, height: Float, length: Float): Pair<Spot, Spot> {
        val dx = (arrow.to.x - arrow.from.x) * width
        val dy = (arrow.to.y - arrow.from.y) * height
        val back = atan2(dy, dx) + Math.PI.toFloat()
        val reach = length * width
        fun barb(turn: Float): Spot {
            val angle = back + turn
            return Spot(
                arrow.to.x + reach * cos(angle) / width,
                arrow.to.y + reach * sin(angle) / height,
            )
        }
        return barb(ANGLE) to barb(-ANGLE)
    }

    private const val ANGLE = 0.5f
}
