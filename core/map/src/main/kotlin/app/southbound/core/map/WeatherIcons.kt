package app.southbound.core.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Weather icons drawn in code, the same on the map and on cards: sun or moon, clouds, rain, drizzle,
 * snow, sleet, thunder and fog. [sky] is the name of core/weather's Sky ("RAIN", "PARTLY_CLOUDY").
 * Each glyph is drawn in a 24-unit box.
 */
object WeatherIcons {
    private const val SUN = "#F9AB00"
    private const val MOON = "#C9B458"
    private const val CLOUD = "#B0B5BA"
    private const val CLOUD_DARK = "#7A8087"
    private const val RAIN = "#1A73E8"
    private const val SNOW = "#5FA8E8"
    private const val BOLT = "#F9AB00"
    private const val FOG = "#9AA0A6"

    private val density: Float get() = Resources.getSystem().displayMetrics.density

    /** The icon alone, [sizeDp] square, transparent around it. */
    fun glyph(sky: String, night: Boolean, sizeDp: Int = 24): Bitmap {
        val size = (sizeDp * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 24f, size / 24f)
        draw(canvas, sky, night)
        return bitmap
    }

    /**
     * The map's badge: a white pill with the icon and the temperature ("57°"), or the icon alone when
     * the station sent no temperature.
     */
    fun badge(sky: String, night: Boolean, tempF: Int?): Bitmap {
        val d = density
        val text = tempF?.let { "$it°" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#202124")
            textSize = 13f * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val h = 26f * d
        val icon = 22f * d
        val textW = text?.let { textPaint.measureText(it) } ?: 0f
        val w = 3f * d + icon + (if (text != null) 2f * d + textW + 7f * d else 3f * d)
        val bitmap = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#DADCE0")
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(d, d, w - d, h - d), h / 2 - d, h / 2 - d, paint)
        canvas.save()
        canvas.translate(3f * d, (h - icon) / 2)
        canvas.scale(icon / 24f, icon / 24f)
        draw(canvas, sky, night)
        canvas.restore()
        if (text != null) {
            val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, 3f * d + icon + 2f * d, baseline, textPaint)
        }
        return bitmap
    }

    private fun draw(c: Canvas, sky: String, night: Boolean) {
        when (sky) {
            "CLEAR" -> body(c, 12f, 12f, 6.5f, night)
            "PARTLY_CLOUDY" -> {
                body(c, 8.5f, 8.5f, 5f, night)
                cloud(c, 13.5f, 15f, 1f, CLOUD)
            }
            "MOSTLY_CLOUDY" -> {
                body(c, 7.5f, 7f, 4f, night)
                cloud(c, 12.5f, 14f, 1.15f, CLOUD)
            }
            "OVERCAST" -> {
                cloud(c, 9f, 10f, 0.9f, CLOUD)
                cloud(c, 13.5f, 14.5f, 1.1f, CLOUD_DARK)
            }
            "FOG" -> {
                cloud(c, 12f, 9f, 0.95f, CLOUD)
                line(c, FOG, 2.2f, 4f, 15.5f, 20f, 15.5f)
                line(c, FOG, 2.2f, 6f, 19f, 18f, 19f)
                line(c, FOG, 2.2f, 4f, 22.5f, 20f, 22.5f)
            }
            "RAIN" -> {
                cloud(c, 12f, 9f, 1f, CLOUD_DARK)
                for (x in floatArrayOf(7f, 11.5f, 16f)) line(c, RAIN, 1.8f, x + 1.5f, 15f, x - 0.5f, 21.5f)
            }
            "DRIZZLE" -> {
                cloud(c, 12f, 9f, 1f, CLOUD)
                for ((x, y) in listOf(8f to 16.5f, 12f to 18.5f, 16f to 16.5f, 10f to 21.5f, 14f to 21.5f)) dot(c, RAIN, x, y, 1.1f)
            }
            "SNOW" -> {
                cloud(c, 12f, 9f, 1f, CLOUD)
                for ((x, y) in listOf(7.5f to 17f, 12f to 19.5f, 16.5f to 17f, 9.5f to 22f, 14.5f to 22f)) flake(c, x, y)
            }
            "SLEET" -> {
                cloud(c, 12f, 9f, 1f, CLOUD_DARK)
                line(c, RAIN, 1.8f, 9f, 15f, 7.5f, 21f)
                flake(c, 12.5f, 18.5f)
                line(c, RAIN, 1.8f, 17.5f, 15f, 16f, 21f)
            }
            "THUNDER" -> {
                cloud(c, 12f, 9f, 1f, CLOUD_DARK)
                val bolt = Path().apply {
                    moveTo(13f, 13.5f)
                    lineTo(9f, 19f)
                    lineTo(12f, 19f)
                    lineTo(10.5f, 23.5f)
                    lineTo(15.5f, 17f)
                    lineTo(12.5f, 17f)
                    lineTo(14f, 13.5f)
                    close()
                }
                c.drawPath(bolt, fill(BOLT))
            }
            else -> cloud(c, 12f, 12f, 1f, CLOUD)
        }
    }

    /** The sun with rays, or a crescent moon at night. */
    private fun body(c: Canvas, x: Float, y: Float, r: Float, night: Boolean) {
        if (night) {
            val moon = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
            val bite = Path().apply { addCircle(x + r * 0.55f, y - r * 0.4f, r * 0.85f, Path.Direction.CW) }
            moon.op(bite, Path.Op.DIFFERENCE)
            c.drawPath(moon, fill(MOON))
            return
        }
        c.drawCircle(x, y, r * 0.62f, fill(SUN))
        val rays = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(SUN)
            strokeWidth = r * 0.22f
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 8) {
            val a = i * PI / 4
            c.drawLine(
                x + (r * 0.85f * cos(a)).toFloat(), y + (r * 0.85f * sin(a)).toFloat(),
                x + (r * 1.15f * cos(a)).toFloat(), y + (r * 1.15f * sin(a)).toFloat(), rays,
            )
        }
    }

    /** A cloud centred on [x], [y], about 14 units wide at [scale] 1. */
    private fun cloud(c: Canvas, x: Float, y: Float, scale: Float, color: String) {
        val p = fill(color)
        val s = scale
        c.drawCircle(x - 3.5f * s, y + 0.8f * s, 3.2f * s, p)
        c.drawCircle(x + 0.3f * s, y - 1.2f * s, 4.4f * s, p)
        c.drawCircle(x + 4f * s, y + 1f * s, 3f * s, p)
        c.drawRoundRect(RectF(x - 6.7f * s, y + 0.5f * s, x + 7f * s, y + 4f * s), 1.8f * s, 1.8f * s, p)
    }

    private fun line(c: Canvas, color: String, width: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color)
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(x0, y0, x1, y1, p)
    }

    private fun dot(c: Canvas, color: String, x: Float, y: Float, r: Float) = c.drawCircle(x, y, r, fill(color))

    private fun flake(c: Canvas, x: Float, y: Float) {
        for (i in 0 until 3) {
            val a = i * PI / 3
            line(c, SNOW, 1.1f, x - (1.8 * cos(a)).toFloat(), y - (1.8 * sin(a)).toFloat(), x + (1.8 * cos(a)).toFloat(), y + (1.8 * sin(a)).toFloat())
        }
    }

    private fun fill(color: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.parseColor(color)
        style = Paint.Style.FILL
    }
}
