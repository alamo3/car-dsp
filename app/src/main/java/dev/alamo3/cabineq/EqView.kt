package dev.alamo3.cabineq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Graphic equalizer: one vertical fader per band, with a smooth response curve drawn
 * through the knobs. Touch anywhere in a band's column and drag up/down. The band is
 * locked for the whole drag, so you don't need to hit the knob exactly.
 *
 * A second, dashed curve shows the gains actually being applied right now (your EQ plus
 * speed compensation), so you can watch speed compensation working.
 */
class EqView(context: Context) : View(context) {

    var onBandChanged: ((band: Int, db: Float) -> Unit)? = null

    private val gains = FloatArray(Bands.COUNT)
    private var applied: FloatArray? = null
    private var activeBand = -1

    private val maxDb = Bands.MAX_BAND_DB
    private val d = resources.displayMetrics.density

    private val accent = themeColor(android.R.attr.colorAccent, Color.rgb(0x3D, 0x8B, 0xFF))
    private val textColor = themeColor(android.R.attr.textColorPrimary, Color.WHITE)
    private val liveColor = Color.rgb(0xFF, 0x98, 0x00)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; alpha = 40; strokeWidth = 1f * d
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; alpha = 90; strokeWidth = 1.5f * d
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; alpha = 60; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 3f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; alpha = 45; style = Paint.Style.FILL
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = liveColor; style = Paint.Style.STROKE; strokeWidth = 2f * d
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; alpha = 170; textSize = 11f * d; textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; textSize = 11f * d; textAlign = Paint.Align.CENTER
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; alpha = 130; textSize = 10f * d; textAlign = Paint.Align.RIGHT
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val livePath = Path()

    // Plot area
    private val padLeft = 30f * d
    private val padRight = 6f * d
    private val padTop = 26f * d
    private val padBottom = 24f * d

    fun setGains(values: FloatArray) {
        values.copyInto(gains)
        invalidate()
    }

    fun setApplied(values: FloatArray?) {
        val old = applied
        if (values == null && old == null) return
        if (values != null && old != null && values.indices.all { abs(values[it] - old[it]) < 0.05f }) return
        applied = values?.copyOf()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize((300 * d).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private val plotTop get() = padTop
    private val plotBottom get() = height - padBottom
    private val plotLeft get() = padLeft
    private val plotRight get() = width - padRight
    private val colWidth get() = (plotRight - plotLeft) / Bands.COUNT

    private fun colX(i: Int) = plotLeft + (i + 0.5f) * colWidth
    private fun yFor(db: Float) = plotTop + (maxDb - db) / (2 * maxDb) * (plotBottom - plotTop)
    private fun dbFor(y: Float): Float {
        val raw = maxDb - (y - plotTop) / (plotBottom - plotTop) * (2 * maxDb)
        return ((raw * 2f).roundToInt() / 2f).coerceIn(-maxDb, maxDb)  // snap to 0.5 dB
    }

    override fun onDraw(canvas: Canvas) {
        // Horizontal grid + dB axis
        var db = -maxDb
        while (db <= maxDb + 0.01f) {
            val y = yFor(db)
            canvas.drawLine(plotLeft, y, plotRight, y, if (db == 0f) zeroPaint else gridPaint)
            val label = if (db > 0) "+${db.toInt()}" else db.toInt().toString()
            canvas.drawText(label, plotLeft - 6f * d, y + 3.5f * d, axisPaint)
            db += 6f
        }

        // Fader tracks
        for (i in 0 until Bands.COUNT) {
            val x = colX(i)
            canvas.drawLine(x, plotTop, x, plotBottom, trackPaint)
        }

        // Response curve (and the area between it and 0 dB)
        buildCurve(curvePath, gains)
        fillPath.set(curvePath)
        fillPath.lineTo(plotRight, yFor(0f))
        fillPath.lineTo(plotLeft, yFor(0f))
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)

        // Live (applied) curve
        applied?.let {
            buildCurve(livePath, it)
            canvas.drawPath(livePath, livePaint)
        }

        // Knobs, values, frequency labels
        for (i in 0 until Bands.COUNT) {
            val x = colX(i)
            val y = yFor(gains[i])
            val r = if (i == activeBand) 13f * d else 9f * d
            canvas.drawCircle(x, y, r, knobPaint)
            canvas.drawCircle(x, y, r, knobRingPaint)

            valuePaint.isFakeBoldText = i == activeBand
            canvas.drawText(formatDb(gains[i]), x, padTop - 9f * d, valuePaint)
            canvas.drawText(shortFreq(i), x, height - 7f * d, labelPaint)
        }
    }

    /** Catmull-Rom spline through the band points, extended flat to both edges. */
    private fun buildCurve(path: Path, values: FloatArray) {
        path.reset()
        val n = Bands.COUNT
        fun px(i: Int) = colX(i.coerceIn(0, n - 1))
        fun py(i: Int) = yFor(values[i.coerceIn(0, n - 1)].coerceIn(-maxDb, maxDb))
        path.moveTo(plotLeft, py(0))
        path.lineTo(px(0), py(0))
        for (i in 0 until n - 1) {
            val c1x = px(i) + (px(i + 1) - px(i - 1)) / 6f
            val c1y = py(i) + (py(i + 1) - py(i - 1)) / 6f
            val c2x = px(i + 1) - (px(i + 2) - px(i)) / 6f
            val c2y = py(i + 1) - (py(i + 2) - py(i)) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, px(i + 1), py(i + 1))
        }
        path.lineTo(plotRight, py(n - 1))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < plotTop - 20f * d || event.y > height.toFloat()) return false
                activeBand = ((event.x - plotLeft) / colWidth).toInt().coerceIn(0, Bands.COUNT - 1)
                parent?.requestDisallowInterceptTouchEvent(true)  // keep the ScrollView from stealing the drag
                updateActive(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeBand >= 0) updateActive(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                endDrag()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endDrag()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun updateActive(y: Float) {
        val v = dbFor(y)
        if (v != gains[activeBand]) {
            gains[activeBand] = v
            onBandChanged?.invoke(activeBand, v)
        }
        invalidate()
    }

    private fun endDrag() {
        activeBand = -1
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun formatDb(v: Float): String = when {
        v == 0f -> "0"
        v % 1f == 0f -> String.format(Locale.US, "%+d", v.toInt())
        else -> String.format(Locale.US, "%+.1f", v)
    }

    private fun shortFreq(i: Int): String {
        val f = Bands.CENTERS[i]
        return if (f >= 1000f) "${(f / 1000f).toInt()}k" else f.toInt().toString()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
}
