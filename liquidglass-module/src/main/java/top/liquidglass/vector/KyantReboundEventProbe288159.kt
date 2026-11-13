package top.liquidglass.vector

import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.WeakHashMap
import kotlin.math.abs

/** Diagnostic only: bounded per-host ring; no Bitmap, Canvas or Shader mutation. */
internal object KyantReboundEventProbe288159 {
    private data class Sample(val t: Long, val hx: Float, val hy: Float,
        val sx: Float, val sy: Float, val residual: Float, val path: Int, val visible: Boolean)
    private class State {
        val ring = arrayOfNulls<Sample>(12)
        var cursor = 0
        var count = 0
        var last: Sample? = null
        var lastEventMs = 0L
        var events = 0L
    }
    private val states = WeakHashMap<View, State>()
    @Synchronized fun observe(host: View, sourceX: Float, sourceY: Float, path: Int) {
        try {
            if (!host.isAttachedToWindow || !sourceX.isFinite() || !sourceY.isFinite()) return
            val matrix = Matrix()
            host.transformMatrixToGlobal(matrix)
            val point = floatArrayOf(0f, 0f)
            matrix.mapPoints(point)
            val root = host.rootView
            val screen = IntArray(2)
            val window = IntArray(2)
            root.getLocationOnScreen(screen)
            root.getLocationInWindow(window)
            val hx = point[0] + screen[0] - window[0]
            val hy = point[1] + screen[1] - window[1]
            val rect = Rect()
            val visible = host.getGlobalVisibleRect(rect) && !rect.isEmpty()
            val now = SystemClock.uptimeMillis()
            val state = states.getOrPut(host) { State() }
            val previous = state.last
            val residual = if (previous != null) maxOf(
                abs((sourceX - previous.sx) - (hx - previous.hx)),
                abs((sourceY - previous.sy) - (hy - previous.hy))) else 0f
            val sample = Sample(now, hx, hy, sourceX, sourceY, residual, path, visible)
            val pathChanged = previous != null && previous.path != path
            val anomalous = visible && previous?.visible == true &&
                (residual > 2f || pathChanged)
            // Output the preceding 12 samples only for a visible-to-visible anomaly.
            // Limit to one burst per host per 1.5 s to avoid logcat and UI-thread flooding.
            if (anomalous && now - state.lastEventMs >= 1500L) {
                state.lastEventMs = now
                state.events++
                Log.i("KyantEventProbe288159", "KYANT288159_EVENT host=" +
                    System.identityHashCode(host) + " event=" + state.events +
                    " residual=" + residual + " pathChanged=" + pathChanged +
                    " current=" + format(sample) + " historyCount=" + state.count)
                for (i in 0 until state.count) {
                    val index = (state.cursor - state.count + i + state.ring.size) % state.ring.size
                    val history = state.ring[index] ?: continue
                    Log.i("KyantEventProbe288159", "KYANT288159_BEFORE host=" +
                        System.identityHashCode(host) + " index=" + i + " " + format(history))
                }
            }
            state.ring[state.cursor] = sample
            state.cursor = (state.cursor + 1) % state.ring.size
            state.count = minOf(state.count + 1, state.ring.size)
            state.last = sample
        } catch (_: Throwable) { /* diagnostic must not affect rendering */ }
    }
    private fun format(s: Sample): String =
        "t=" + s.t + " hx=" + s.hx + " hy=" + s.hy +
        " sx=" + s.sx + " sy=" + s.sy + " residual=" + s.residual +
        " path=" + s.path + " visible=" + s.visible
}
