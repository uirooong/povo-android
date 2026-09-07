package jp.povo.manager.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import jp.povo.manager.R

/**
 * Draws the remaining-data ring as a bitmap.
 *
 * Glance has no drawing primitives — it can only emit RemoteViews — so an arc
 * has to arrive as an image. Only the ring is drawn here; the readout that sits
 * in the hole is composed as real Glance text on top, so it stays crisp at any
 * launcher scale and picks up the widget's own theme instead of being baked
 * into pixels.
 */
object DonutRenderer {

    /** Rendered at a fixed size and scaled to fit; the ring has no fine detail. */
    private const val SIZE_PX = 512

    /** Stroke width as a fraction of the diameter, when the caller has no view. */
    const val DEFAULT_STROKE_FRACTION = 0.11f

    /** Straight up. Android's arc angles start at 3 o'clock. */
    private const val START_ANGLE = -90f

    /**
     * @param fraction how much of the ring to fill, 0..1. Values outside that
     *   range are clamped rather than producing a ring that wraps past itself.
     * @param strokeFraction ring thickness as a fraction of the diameter. Small
     *   rings pass a thinner value: the readout sits in the hole, and on a 1x1
     *   tile a proportionally thick stroke leaves too little room for it.
     */
    fun render(
        context: Context,
        fraction: Float,
        strokeFraction: Float = DEFAULT_STROKE_FRACTION,
    ): Bitmap {
        val bitmap = createBitmap(SIZE_PX, SIZE_PX)
        val canvas = Canvas(bitmap)

        val stroke = SIZE_PX * strokeFraction
        val inset = stroke / 2f
        val bounds = RectF(inset, inset, SIZE_PX - inset, SIZE_PX - inset)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

        // The track is a full circle rather than the unfilled remainder, so a
        // nearly-empty allowance still reads as a ring instead of a stray dash.
        paint.color = ContextCompat.getColor(context, R.color.widget_donut_track)
        canvas.drawArc(bounds, 0f, 360f, false, paint)

        val sweep = 360f * fraction.coerceIn(0f, 1f)
        if (sweep > 0f) {
            paint.color = ContextCompat.getColor(context, R.color.widget_donut_fill)
            canvas.drawArc(bounds, START_ANGLE, sweep, false, paint)
        }
        return bitmap
    }
}
