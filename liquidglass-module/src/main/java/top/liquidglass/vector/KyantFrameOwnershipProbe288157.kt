package top.liquidglass.vector

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.WeakHashMap
import kotlin.math.abs

/** Diagnostic only. Never changes the matrix, Canvas or invalidate scheduling. */
internal object KyantFrameOwnershipProbe288157 {
    private data class State(
        var count: Long = 0, var mismatch: Long = 0, var jumps: Long = 0,
        var pathChanges: Long = 0, var lastPath: Int = -1,
        var lastHostX: Float = Float.NaN, var lastHostY: Float = Float.NaN,
        var lastSourceX: Float = Float.NaN, var lastSourceY: Float = Float.NaN,
        var lastLogMs: Long = 0
    )
    private val states = WeakHashMap<View, State>()
    @Synchronized fun observe(host: View, sourceX: Float, sourceY: Float,
                               composeW: Float, composeH: Float, path: Int) {
        try {
            if (!host.isAttachedToWindow || !sourceX.isFinite() || !sourceY.isFinite()) return
            val m = Matrix()
            host.transformMatrixToGlobal(m)
            val origin = floatArrayOf(0f, 0f)
            m.mapPoints(origin)
            val root = host.rootView
            val screen = IntArray(2)
            val window = IntArray(2)
            root.getLocationOnScreen(screen)
            root.getLocationInWindow(window)
            // transformMatrixToGlobal returns window coordinates on Android.
            val hostX = origin[0] + screen[0] - window[0]
            val hostY = origin[1] + screen[1] - window[1]
            val state = states.getOrPut(host) { State() }
            state.count++
            val dx = sourceX - hostX
            val dy = sourceY - hostY
            if (abs(dx) > 2f || abs(dy) > 2f) state.mismatch++
            if (state.lastHostX.isFinite()) {
                val hostStepX = hostX - state.lastHostX
                val hostStepY = hostY - state.lastHostY
                val sourceStepX = sourceX - state.lastSourceX
                val sourceStepY = sourceY - state.lastSourceY
                if (abs(hostStepX - sourceStepX) > 2f || abs(hostStepY - sourceStepY) > 2f)
                    state.jumps++
            }
            if (state.lastPath >= 0 && state.lastPath != path) state.pathChanges++
            state.lastPath = path
            state.lastHostX = hostX
            state.lastHostY = hostY
            state.lastSourceX = sourceX
            state.lastSourceY = sourceY
            val now = SystemClock.uptimeMillis()
            if (now - state.lastLogMs >= 2000L) {
                state.lastLogMs = now
                Log.i("KyantFrameProbe288157", "KYANT288157_FRAME host=" +
                    System.identityHashCode(host) + " draws=" + state.count +
                    " hostX=" + hostX + " hostY=" + hostY +
                    " sourceX=" + sourceX + " sourceY=" + sourceY +
                    " deltaX=" + dx + " deltaY=" + dy +
                    " mismatch=" + state.mismatch + " stepMismatch=" + state.jumps +
                    " path=" + path + " pathChanges=" + state.pathChanges +
                    " compose=" + composeW + "x" + composeH)
            }
        } catch (_: Throwable) { /* probe must not affect rendering */ }
    }
}
