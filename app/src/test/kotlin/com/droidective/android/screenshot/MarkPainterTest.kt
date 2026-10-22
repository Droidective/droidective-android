package com.droidective.android.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidective.core.screenshot.Mark
import com.droidective.core.screenshot.Spot
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the painter puts on a picture, pixel by pixel: the same on screen and in what is saved. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkPainterTest {
    private val red = 0xFFFF0000

    private fun white(size: Int = 100): Bitmap =
        createBitmap(size, size).apply { eraseColor(Color.WHITE) }

    private fun painted(picture: Bitmap, vararg marks: Mark): Bitmap {
        val canvas = picture.copy(Bitmap.Config.ARGB_8888, true)
        MarkPainter(picture)
            .draw(Canvas(canvas), canvas.width.toFloat(), canvas.height.toFloat(), marks.toList())
        return canvas
    }

    @Test
    fun aBoxIsItsEdgeAndNothingInside() {
        val box = Mark.Box(Spot(0.2f, 0.2f), Spot(0.8f, 0.8f), red, width = 0.04f)
        val drawn = painted(white(), box)

        assertEquals(Color.RED, drawn.getPixel(20, 50))
        assertEquals(Color.WHITE, drawn.getPixel(50, 50))
    }

    @Test
    fun blackingOutPaintsTheAreaOver() {
        val hidden = Mark.Redact(Spot(0.5f, 0.5f), Spot(0.1f, 0.1f), Mark.Redact.Style.Solid)
        val drawn = painted(white(), hidden)

        assertEquals(Color.BLACK, drawn.getPixel(30, 30))
        assertEquals(Color.WHITE, drawn.getPixel(70, 70))
    }

    @Test
    fun pixelatingAveragesWhatWasThere() {
        // One-pixel stripes: readable as stripes until they are averaged into blocks.
        val stripes = white(200)
        for (x in 0 until 200 step 2) for (y in 0 until 200) stripes[x, y] = Color.BLACK
        val blocked = Mark.Redact(Spot(0f, 0f), Spot(0.5f, 0.5f), Mark.Redact.Style.Pixelate)
        val drawn = painted(stripes, blocked)

        val inside = drawn.getPixel(40, 40)
        assertTrue(
            Color.red(inside) in 60..200,
            "a stripe survived: ${Integer.toHexString(inside)}",
        )
        assertEquals(drawn.getPixel(40, 40), drawn.getPixel(41, 40), "a block is one colour")
        // Outside the area the stripes are as they were.
        assertEquals(Color.BLACK, drawn.getPixel(150, 150))
        assertEquals(Color.WHITE, drawn.getPixel(151, 150))
    }

    @Test
    fun aTapOfThePenIsADot() {
        val dot = Mark.Line(listOf(Spot(0.5f, 0.5f)), red, width = 0.1f)
        assertEquals(Color.RED, painted(white(), dot).getPixel(50, 50))
    }

    @Test
    fun marksAreDrawnInTheirOrder() {
        val hidden = Mark.Redact(Spot(0.1f, 0.1f), Spot(0.9f, 0.9f), Mark.Redact.Style.Solid)
        val over = Mark.Line(listOf(Spot(0.5f, 0.5f)), red, width = 0.1f)

        assertEquals(Color.RED, painted(white(), hidden, over).getPixel(50, 50))
        assertEquals(Color.BLACK, painted(white(), over, hidden).getPixel(50, 50))
    }
}
