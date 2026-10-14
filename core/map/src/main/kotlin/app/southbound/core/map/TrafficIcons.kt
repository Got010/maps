package app.southbound.core.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The map icons for 511NY traffic cameras and message signs, drawn in code
 * (no drawable resources, so nothing else needs a Context) at the screen's
 * density. Sizes are in dp.
 */
internal object TrafficIcons {
    /** Teal, apart from the plate readers' purple and the speed cameras' blue. */
    const val CAMERA_TEAL = "#00897B"
    const val CAMERA_OFFLINE_GREY = "#80868B"
    const val SIGN_AMBER = "#FFB300"

    private val density: Float get() = Resources.getSystem().displayMetrics.density

    /** A round badge with a white video camera, [fill] behind it. */
    fun camera(fill: String): Bitmap {
        val d = density
        val size = (24 * d).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 24f, size / 24f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(12f, 12f, 12f, paint)
        paint.color = Color.parseColor(fill)
        canvas.drawCircle(12f, 12f, 10.5f, paint)
        // The camera: a body and a lens wedge to its right, as Material's videocam.
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(5f, 8f, 14.5f, 16f), 1.5f, 1.5f, paint)
        val lens = Path().apply {
            moveTo(14.5f, 11f)
            lineTo(19f, 8.5f)
            lineTo(19f, 15.5f)
            lineTo(14.5f, 13f)
            close()
        }
        canvas.drawPath(lens, paint)
        return bitmap
    }

    /**
     * A plate reader's field of view: a [WEDGE_DEGREES] wedge pointing up (north) from the bitmap's centre, where the
     * camera's dot sits, so rotating the icon by the camera's direction aims it. Purple, see-through with a solid edge.
     */
    fun wedge(fill: String): Bitmap {
        val d = density
        val size = (WEDGE_DP * d).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val unit = size / WEDGE_DP
        canvas.scale(unit, unit)
        val c = WEDGE_DP / 2
        val r = c - 1f
        val half = WEDGE_DEGREES / 2
        val path = Path().apply {
            moveTo(c, c)
            arcTo(RectF(c - r, c - r, c + r, c + r), -90f - half, WEDGE_DEGREES.toFloat())
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(fill)
        paint.alpha = 0x66
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.alpha = 0xE6
        canvas.drawPath(path, paint)
        return bitmap
    }

    /** The wedge's bitmap is this many dp square; its point is in the middle. */
    private const val WEDGE_DP = 40f

    /** How wide [wedge] opens: a typical plate reader's view. */
    private const val WEDGE_DEGREES = 50

    /** A small black board with an amber frame and three amber lines of "text", like a roadside sign. */
    fun sign(): Bitmap {
        val d = density
        val w = (26 * d).toInt().coerceAtLeast(1)
        val h = (18 * d).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(w / 26f, h / 18f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(0f, 0f, 26f, 18f), 3f, 3f, paint)
        paint.color = Color.parseColor("#111111")
        canvas.drawRoundRect(RectF(1.2f, 1.2f, 24.8f, 16.8f), 2.2f, 2.2f, paint)
        paint.color = Color.parseColor(SIGN_AMBER)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        canvas.drawRoundRect(RectF(2.4f, 2.4f, 23.6f, 15.6f), 1.6f, 1.6f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(5f, 5f, 21f, 6.6f, paint)
        canvas.drawRect(5f, 8.2f, 18f, 9.8f, paint)
        canvas.drawRect(5f, 11.4f, 20f, 13f, paint)
        return bitmap
    }
}
