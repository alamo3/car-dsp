package dev.alamo3.cabineq.music

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** Look of the music screens: a dark theme that also suits the car at night. Framework only. */
object Ui {
    val BG = Color.parseColor("#0F0F12")
    val SURFACE = Color.parseColor("#1C1C21")
    val SURFACE_2 = Color.parseColor("#2A2A31")
    val TEXT = Color.parseColor("#F5F5F7")
    val TEXT_2 = Color.parseColor("#A1A1AA")
    val ACCENT = Color.parseColor("#FA4F6A")
    val ERROR = Color.parseColor("#FF8A80")

    fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).roundToInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokeColor != null) setStroke(strokePx, strokeColor)
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Square cover with rounded corners (or a circle for artists); grey until the image loads. */
    fun cover(context: Context, sizePx: Int, radiusPx: Float, circle: Boolean = false) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = if (circle) oval(SURFACE_2) else rounded(SURFACE_2, radiusPx)
        clipToOutline = true   // clip the bitmap to the background's rounded shape
        minimumWidth = sizePx
        minimumHeight = sizePx
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false, lines: Int = 1) =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    /** Round icon button using a framework drawable tinted to [tint]. */
    fun iconButton(context: Context, icon: Int, sizePx: Int, tint: Int = TEXT, bg: Int? = null, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            background = if (bg != null) oval(bg) else null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = sizePx
            minimumHeight = sizePx
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    /** Pill button: filled accent, or outlined. */
    fun pill(context: Context, label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else TEXT)
        val r = dp(context, 24).toFloat()
        background = if (filled) rounded(ACCENT, r) else rounded(Color.TRANSPARENT, r, SURFACE_2, dp(context, 2))
        val h = dp(context, 12)
        setPadding(dp(context, 22), h, dp(context, 22), h)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Ripple-ish pressed feedback for clickable rows. */
    fun selectable(view: View) {
        val tv = TypedValue()
        if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            view.foreground = view.context.getDrawable(tv.resourceId)
        }
        view.isClickable = true
    }

    fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
