package com.droidective.core.screenshot

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkupTest {
    private val red = 0xFFFF0000
    private val arrow = Mark.Arrow(Spot(0.1f, 0.1f), Spot(0.5f, 0.5f), red, 0.01f)
    private val box = Mark.Box(Spot(0.2f, 0.2f), Spot(0.4f, 0.3f), red, 0.01f)

    @Test
    fun undoAndRedoStepThroughWhatWasDrawn() {
        val drawn = Markup().adding(arrow).adding(box)
        assertEquals(listOf(arrow, box), drawn.marks)

        val back = drawn.undo()
        assertEquals(listOf(arrow), back.marks)
        assertTrue(back.canRedo)
        assertEquals(listOf(arrow, box), back.redo().marks)
        assertEquals(emptyList(), back.undo().marks)
        assertFalse(back.undo().canUndo)
    }

    @Test
    fun somethingNewDrawnLeavesNothingToRedo() {
        val label = Mark.Label(Spot(0.5f, 0.9f), "here", red, 0.05f)
        val redrawn = Markup().adding(arrow).adding(box).undo().adding(label)

        assertEquals(listOf(arrow, label), redrawn.marks)
        assertFalse(redrawn.canRedo)
        assertEquals(redrawn, redrawn.redo())
    }

    @Test
    fun clearingIsOneStepBack() {
        val cleared = Markup().adding(arrow).adding(box).clear()

        assertEquals(emptyList(), cleared.marks)
        assertEquals(listOf(arrow, box), cleared.undo().marks)
        // Nothing on it, nothing to clear: no step of its own.
        assertEquals(cleared, cleared.clear())
    }

    @Test
    fun undoGoesBackOnlySoFar() {
        var markup = Markup()
        repeat(Markup.MAX_STATES + 20) { markup = markup.adding(box) }

        assertEquals(Markup.MAX_STATES, markup.states.size)
        repeat(Markup.MAX_STATES) { markup = markup.undo() }
        // A hundred states are ninety-nine steps back, to the oldest kept: twenty-one boxes.
        assertEquals(21, markup.marks.size)
        assertFalse(markup.canUndo)
    }

    @Test
    fun aMarkupSurvivesBeingPutAway() {
        val line = Mark.Line(listOf(Spot(0f, 0f), Spot(0.3f, 0.4f)), 0x80FFFF00, 0.03f, true)
        val redact = Mark.Redact(Spot(0.6f, 0.1f), Spot(0.9f, 0.2f), Mark.Redact.Style.Pixelate)
        val markup = Markup().adding(line).adding(redact).adding(arrow).undo()

        assertEquals(markup, Markup.decode(markup.encode()))
    }

    @Test
    fun aLineTakesOnlyPointsThatSaySomething() {
        val line = Mark.Line(listOf(Spot(0.5f, 0.5f)), red, 0.01f)
        assertEquals(1, line.adding(Spot(0.5001f, 0.5001f)).points.size)
        assertEquals(2, line.adding(Spot(0.51f, 0.5f)).points.size)
    }

    @Test
    fun anAreaIsInOrderAndOnThePicture() {
        assertEquals(Area(0.2f, 0.1f, 0.6f, 0.5f), Area.between(Spot(0.6f, 0.5f), Spot(0.2f, 0.1f)))
        assertEquals(Area(0f, 0f, 1f, 0.3f), Area.between(Spot(-0.2f, 0.3f), Spot(1.4f, -1f)))
        assertTrue(Area.between(Spot(0.3f, 0.3f), Spot(0.3f, 0.8f)).isEmpty)
    }

    @Test
    fun anArrowHeadIsTheSameShapeOnAnyPicture() {
        // A tall picture: in fractions the barbs look lopsided, in pixels they are even.
        val width = 1080f
        val height = 2400f
        val shaft = Mark.Arrow(Spot(0.2f, 0.2f), Spot(0.6f, 0.6f), red, 0.01f)
        val (left, right) = ArrowHead.barbs(shaft, width, height, length = 0.05f)

        fun pixels(spot: Spot) = (spot.x - shaft.to.x) * width to (spot.y - shaft.to.y) * height
        val (lx, ly) = pixels(left)
        val (rx, ry) = pixels(right)
        assertEquals(0.05f * width, hypot(lx, ly), 0.5f)
        assertEquals(0.05f * width, hypot(rx, ry), 0.5f)
        // Either side of the shaft, by the same angle.
        val back = atan2(-(0.4f * height), -(0.4f * width))
        val turn = { x: Float, y: Float -> abs(normalised(atan2(y, x) - back)) }
        assertEquals(turn(lx, ly), turn(rx, ry), 0.001f)
    }

    private fun normalised(angle: Float): Float {
        var value = angle
        while (value > Math.PI) value -= (2 * Math.PI).toFloat()
        while (value < -Math.PI) value += (2 * Math.PI).toFloat()
        return value
    }
}
