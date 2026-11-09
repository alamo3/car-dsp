package dev.alamo3.cabineq.cabin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Draws a measured frequency response: log frequency 20 Hz – 20 kHz, ±24 dB, with the band we
 * trust the phone's microphone over (100 Hz – 8 kHz) picked out and the rest dimmed.
 */
class ResponseView(context: Context) : View(context) {

    private var curve: List<CabinMeasure.Point> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9AA6.toInt()
        textSize = 24f
    }
    private val dbRange = 24f

    fun set(points: List<CabinMeasure.Point>) {
        curve = points
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.45f).toInt().coerceAtLeast(dp(140)))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun xFor(hz: Double): Float {
        val left = dp(44).toFloat()
        val right = width - dp(10).toFloat()
        val t = (log10(hz) - log10(20.0)) / (log10(20000.0) - log10(20.0))
        return left + ((right - left) * t).toFloat()
    }
    private fun yFor(db: Double): Float {
        val top = dp(10).toFloat()
        val bottom = height - dp(28).toFloat()
        val t = ((db.coerceIn(-dbRange.toDouble(), dbRange.toDouble()) + dbRange) / (2 * dbRange))
        return bottom - ((bottom - top) * t).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val top = dp(10).toFloat()
        val bottom = height - dp(28).toFloat()

        // Untrusted band, dimmed.
        paint.style = Paint.Style.FILL
        paint.color = 0x22000000
        canvas.drawRect(0f, top, xFor(CabinMeasure.TRUSTED_LOW_HZ), bottom, paint)
        canvas.drawRect(xFor(CabinMeasure.TRUSTED_HIGH_HZ), top, width.toFloat(), bottom, paint)

        // Grid: 0 dB line and octave marks.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x33FFFFFF
        canvas.drawLine(dp(44).toFloat(), yFor(0.0), width - dp(10).toFloat(), yFor(0.0), paint)
        for (hz in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
            val x = xFor(hz)
            canvas.drawLine(x, bottom - dp(4), x, bottom, paint)
            canvas.drawText(
                if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString(),
                x - dp(8), height - dp(6).toFloat(), text.apply { textSize = dp(9).toFloat() },
            )
        }
        for (db in listOf(-18, -12, -6, 6, 12, 18)) {
            canvas.drawText("${if (db > 0) "+" else ""}$db", dp(2).toFloat(), yFor(db.toDouble()) + dp(3), text.apply { textSize = dp(9).toFloat() })
        }

        if (curve.isEmpty()) {
            text.textSize = dp(12).toFloat()
            canvas.drawText("No measurement yet", dp(60).toFloat(), (top + bottom) / 2, text)
            return
        }

        // The curve itself.
        paint.color = 0xFFFF4D6D.toInt()
        paint.strokeWidth = dp(2).toFloat()
        path.reset()
        curve.forEachIndexed { i, p ->
            val x = xFor(p.hz)
            val y = yFor(p.db)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)

        // Values at the extremes, so the numbers are visible next to the picture.
        text.textSize = dp(10).toFloat()
        text.color = 0xFFFFFFFF.toInt()
        val worst = curve.maxByOrNull { abs(it.db) }
        if (worst != null) {
            canvas.drawText(
                "%.0f Hz %+.1f dB".format(java.util.Locale.US, worst.hz, worst.db),
                dp(48).toFloat(), top + dp(12), text,
            )
        }
    }

    @Suppress("unused")
    private fun dbAt(hz: Double): Double? =
        curve.minByOrNull { abs(log10(it.hz) - log10(hz)) }?.db?.takeIf { 2.0.pow(0.0) > 0 }
}
