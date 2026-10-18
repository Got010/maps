package app.southbound.core.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The map badges for free Wi-Fi spots, drawn in code (no drawable resources or asset files): a white Wi-Fi fan on
 * a round badge in the tier's colour ([WifiColors]). A spot that is free for customers only has a ring round the
 * badge. Sizes are in dp.
 */
internal object WifiIcons {
    private val density: Float get() = Resources.getSystem().displayMetrics.density

    /** The badge of [tier] (1 to 4), ringed when [ringed]. All are [SIZE_DP] square. */
    fun badge(tier: Int, ringed: Boolean): Bitmap {
        val size = (SIZE_DP * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / SIZE_DP, size / SIZE_DP)
        val tint = Color.parseColor(WifiColors.hex(tier))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        val c = SIZE_DP / 2
        if (ringed) {
            // A thin ring in the tier's colour, a white gap, then the badge.
            paint.color = tint
            canvas.drawCircle(c, c, 14f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(c, c, 12.6f, paint)
            paint.color = tint
            canvas.drawCircle(c, c, 11.2f, paint)
        } else {
            paint.color = Color.WHITE
            canvas.drawCircle(c, c, 13f, paint)
            paint.color = tint
            canvas.drawCircle(c, c, 11.5f, paint)
        }
        drawFan(canvas)
        return bitmap
    }

    /** The Wi-Fi symbol: a dot and three arcs opening upwards, white, centred in the badge. */
    private fun drawFan(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        val cx = SIZE_DP / 2
        val cy = 18f
        canvas.drawCircle(cx, cy, 1.7f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.9f
        paint.strokeCap = Paint.Cap.ROUND
        for (r in floatArrayOf(4.2f, 7.0f, 9.8f)) {
            val arc = Path().apply { arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -135f, 90f) }
            canvas.drawPath(arc, paint)
        }
    }

    /** Every badge is this many dp square, ringed or not. */
    const val SIZE_DP = 28f
}
