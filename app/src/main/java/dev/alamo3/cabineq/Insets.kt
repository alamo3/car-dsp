package dev.alamo3.cabineq

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import kotlin.math.max

/**
 * Pads this view so its content stays clear of the status bar, navigation bar, display
 * cutout and on-screen keyboard. Needed because Android 15 (targetSdk 35) lays apps out
 * edge-to-edge. The view's existing padding is kept and the insets are added to it.
 *
 * With clipToPadding off, a scrolling view can still scroll its content under the bars.
 */
fun View.padForSystemBars() {
    if (Build.VERSION.SDK_INT < 30) return  // Android 10 isn't edge-to-edge; the system already does this
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    (this as? ViewGroup)?.clipToPadding = false
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + max(bars.bottom, ime.bottom))
        insets
    }
    requestApplyInsets()
}
