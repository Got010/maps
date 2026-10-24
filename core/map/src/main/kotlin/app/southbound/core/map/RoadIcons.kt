package app.southbound.core.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The map badges for Road pack spots, drawn in code (no drawable resources or asset files): a white glyph
 * ([RoadGlyphs]) on a round badge in the category's colour ([RoadColors]), with a thin white ring so it reads on the
 * dark map and apart from the Wi-Fi badges. Sizes are in dp.
 */
internal object RoadIcons {
    private val density: Float get() = Resources.getSystem().displayMetrics.density

    /** The badge of [category] (a pack category code), [SIZE_DP] square. */
    fun badge(category: String): Bitmap {
        val size = (SIZE_DP * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / SIZE_DP, size / SIZE_DP)
        val tint = Color.parseColor(RoadColors.hex(category))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        val c = SIZE_DP / 2
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, c, paint)
        paint.color = tint
        canvas.drawCircle(c, c, c - RING_DP, paint)
        // The glyph's grid, scaled to fit inside the ring and centred.
        val scale = GLYPH_DP / RoadGlyphs.GRID
        val offset = (SIZE_DP - GLYPH_DP) / 2
        canvas.translate(offset, offset)
        canvas.scale(scale, scale)
        for (shape in RoadGlyphs.of(category)) draw(canvas, shape, if (shape.hole) tint else Color.WHITE)
        return bitmap
    }

    private fun draw(canvas: Canvas, shape: RoadGlyphs.Shape, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        when (shape) {
            is RoadGlyphs.Circle -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(shape.cx, shape.cy, shape.r, paint)
            }
            is RoadGlyphs.Rect -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(shape.left, shape.top, shape.right, shape.bottom), shape.radius, shape.radius, paint)
            }
            is RoadGlyphs.Polygon -> {
                paint.style = Paint.Style.FILL
                canvas.drawPath(path(shape.xy, close = true), paint)
            }
            is RoadGlyphs.Line -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = shape.width
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path(shape.xy, close = false), paint)
            }
        }
    }

    private fun path(xy: List<Float>, close: Boolean): Path = Path().apply {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) {
            lineTo(xy[i], xy[i + 1])
            i += 2
        }
        if (close) close()
    }

    /** The white ring round the coloured disc. */
    private const val RING_DP = 1.5f

    /** The glyph's 24 by 24 grid is drawn this many dp square. */
    private const val GLYPH_DP = 17f

    /** Every badge is this many dp square. */
    const val SIZE_DP = 26f
}
