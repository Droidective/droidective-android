package com.droidective.android.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import com.droidective.core.screenshot.Area
import com.droidective.core.screenshot.ArrowHead
import com.droidective.core.screenshot.Mark
import com.droidective.platform.SharedStorage
import java.io.File
import java.io.IOException

/**
 * Draws marks onto a canvas: the editor's view of [picture], and the picture itself at its own
 * size, with the same strokes, so what is seen is what is saved. Marks are in fractions of the
 * picture, so each is drawn against the canvas it is given.
 */
class MarkPainter(private val picture: Bitmap) {
    private val pixelated = HashMap<Mark.Redact, Bitmap>()
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blocks = Paint().apply { isFilterBitmap = false }

    /**
     * Every mark in [marks], in order, on a [width] by [height] canvas, and then [sketch], the one
     * a finger is still drawing.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, marks: List<Mark>, sketch: Mark? = null) {
        marks.forEach { draw(canvas, width, height, it) }
        // Marks taken back don't keep their blocks.
        pixelated.keys.retainAll(marks.filterIsInstance<Mark.Redact>().toSet())
        when {
            sketch == null -> Unit
            // Averaged once it is let go: working it out for every move of a finger is slow.
            sketch is Mark.Redact && sketch.style == Mark.Redact.Style.Pixelate -> {
                fill.color = SKETCH
                canvas.drawRect(rect(Area.between(sketch.from, sketch.to), width, height), fill)
            }
            else -> draw(canvas, width, height, sketch)
        }
    }

    private fun draw(canvas: Canvas, width: Float, height: Float, mark: Mark) {
        when (mark) {
            is Mark.Line -> line(canvas, width, height, mark)
            is Mark.Arrow -> arrow(canvas, width, height, mark)
            is Mark.Box -> {
                stroke.set(mark.color, mark.width * width)
                canvas.drawRect(rect(Area.between(mark.from, mark.to), width, height), stroke)
            }
            is Mark.Label -> label(canvas, width, height, mark)
            is Mark.Redact -> redact(canvas, width, height, mark)
        }
    }

    private fun line(canvas: Canvas, width: Float, height: Float, mark: Mark.Line) {
        val first = mark.points.firstOrNull() ?: return
        val path = Path().apply { moveTo(first.x * width, first.y * height) }
        // One point is a dot: a round cap on a line that goes nowhere.
        if (mark.points.size == 1) path.lineTo(first.x * width, first.y * height)
        mark.points.drop(1).forEach { path.lineTo(it.x * width, it.y * height) }
        stroke.set(mark.color, mark.width * width)
        canvas.drawPath(path, stroke)
    }

    private fun arrow(canvas: Canvas, width: Float, height: Float, mark: Mark.Arrow) {
        stroke.set(mark.color, mark.width * width)
        val (left, right) = ArrowHead.barbs(mark, width, height, length = mark.width * HEAD)
        val tip = mark.to
        canvas.drawLine(
            mark.from.x * width,
            mark.from.y * height,
            tip.x * width,
            tip.y * height,
            stroke,
        )
        canvas.drawLine(tip.x * width, tip.y * height, left.x * width, left.y * height, stroke)
        canvas.drawLine(tip.x * width, tip.y * height, right.x * width, right.y * height, stroke)
    }

    /** Text with a dark edge, so it reads on a light screen and a dark one alike. */
    private fun label(canvas: Canvas, width: Float, height: Float, mark: Mark.Label) {
        val size = mark.size * width
        stroke.set(OUTLINE, size * EDGE)
        stroke.textSize = size
        fill.color = mark.color.toInt()
        fill.textSize = size
        val x = mark.at.x * width
        val y = mark.at.y * height
        canvas.drawText(mark.text, x, y, stroke)
        canvas.drawText(mark.text, x, y, fill)
    }

    private fun redact(canvas: Canvas, width: Float, height: Float, mark: Mark.Redact) {
        val area = Area.between(mark.from, mark.to)
        if (area.isEmpty) return
        val target = rect(area, width, height)
        when (mark.style) {
            Mark.Redact.Style.Solid -> {
                fill.color = Color.BLACK
                canvas.drawRect(target, fill)
            }
            Mark.Redact.Style.Pixelate -> {
                val coarse = pixelated.getOrPut(mark) { coarse(area) }
                canvas.drawBitmap(coarse, null, target, blocks)
            }
        }
    }

    /**
     * The part of the picture [area] covers as blocks [BLOCK] of the picture's width across, each
     * the average of every pixel in it. Scaling down would only sample a few, and what it sampled
     * could still be read.
     */
    private fun coarse(area: Area): Bitmap {
        val left = (area.left * picture.width).toInt().coerceIn(0, picture.width - 1)
        val top = (area.top * picture.height).toInt().coerceIn(0, picture.height - 1)
        val wide = ((area.right * picture.width).toInt() - left).coerceIn(1, picture.width - left)
        val tall = ((area.bottom * picture.height).toInt() - top).coerceIn(1, picture.height - top)
        val pixels = IntArray(wide * tall)
        picture.getPixels(pixels, 0, wide, left, top, wide, tall)
        val block = (picture.width * BLOCK).toInt().coerceAtLeast(1)
        val across = (wide + block - 1) / block
        val down = (tall + block - 1) / block
        val averaged = IntArray(across * down)
        for (row in 0 until down) {
            for (column in 0 until across) {
                averaged[row * across + column] =
                    average(pixels, wide, column * block, row * block, block, tall)
            }
        }
        return Bitmap.createBitmap(averaged, across, down, Bitmap.Config.ARGB_8888)
    }

    /** The mean colour of the [size]-square block at [x], [y] in [pixels], [wide] across. */
    private fun average(pixels: IntArray, wide: Int, x: Int, y: Int, size: Int, tall: Int): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (row in y until minOf(y + size, tall)) {
            for (column in x until minOf(x + size, wide)) {
                val pixel = pixels[row * wide + column]
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun rect(area: Area, width: Float, height: Float) =
        RectF(area.left * width, area.top * height, area.right * width, area.bottom * height)

    private fun Paint.set(color: Long, strokeWidth: Float) {
        this.color = color.toInt()
        this.strokeWidth = strokeWidth
    }

    private companion object {
        /** How long an arrow's head is, in widths of its line. */
        const val HEAD = 4f

        /** A label's dark edge, as a part of its size. */
        const val EDGE = 0.16f

        /** How wide one block of a pixelated area is, as a part of the picture's width. */
        const val BLOCK = 0.025f

        const val OUTLINE = 0xCC000000
        const val SKETCH = 0x99808080.toInt()
    }
}

/** A marked-up screenshot, saved and shared. */
object MarkupExport {
    /**
     * [picture] with [marks] drawn on it at its own size, saved in Pictures/Droidective.
     *
     * @return null when there's no shared storage or MediaStore refused the entry.
     * @throws IOException when writing the picture fails.
     */
    suspend fun save(context: Context, picture: Bitmap, marks: List<Mark>): Uri? {
        val folder = SharedStorage.handover(context) ?: return null
        val file = File(folder, "Screenshot_${System.currentTimeMillis()}_Droidective.png")
        val marked = picture.copy(Bitmap.Config.ARGB_8888, true)
        try {
            MarkPainter(picture)
                .draw(Canvas(marked), marked.width.toFloat(), marked.height.toFloat(), marks)
            file.outputStream().use { out ->
                if (!marked.compress(Bitmap.CompressFormat.PNG, FULL, out)) {
                    throw IOException("The picture couldn't be written as a PNG")
                }
            }
        } finally {
            marked.recycle()
        }
        return SharedStorage.publish(context, file, PNG, SharedStorage.Place.Pictures)
    }

    /** The share sheet for a saved one. */
    fun share(uri: Uri): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(PNG)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share the screenshot",
        )

    private const val PNG = "image/png"
    private const val FULL = 100
}
