package top.liquidglass.vector

import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.WeakHashMap
import kotlin.math.abs

/** Diagnostic only. Reports raw positions separately from relative-motion residuals.
 * The Compose drawPosition and View origin are NOT assumed to share an origin.
 * This does not alter Canvas, bitmap, invalidation, or the Dock.
 */
internal object KyantCoordinateSpaceProbe288158 {
    private data class State(
        var count: Long = 0,
        var lastHostX: Float = Float.NaN, var lastHostY: Float = Float.NaN,
        var lastSourceX: Float = Float.NaN, var lastSourceY: Float = Float.NaN,
        var motionMismatch: Long = 0, var pathChanges: Long = 0,
        var lastPath: Int = -1, var lastLogMs: Long = 0,
        var maxStepResidualX: Float = 0f, var maxStepResidualY: Float = 0f
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
            val offsetX = screen[0] - window[0]
            val offsetY = screen[1] - window[1]
            val hostX = origin[0] + offsetX
            val hostY = origin[1] + offsetY
            val visible = Rect()
            val hasVisibleRect = host.getGlobalVisibleRect(visible)
            val state = states.getOrPut(host) { State() }
            state.count++
            var residualX = Float.NaN
            var residualY = Float.NaN
            if (state.lastHostX.isFinite() && state.lastSourceX.isFinite()) {
                residualX = (sourceX - state.lastSourceX) - (hostX - state.lastHostX)
                residualY = (sourceY - state.lastSourceY) - (hostY - state.lastHostY)
                if (abs(residualX) > 2f || abs(residualY) > 2f) state.motionMismatch++
                state.maxStepResidualX = maxOf(state.maxStepResidualX, abs(residualX))
                state.maxStepResidualY = maxOf(state.maxStepResidualY, abs(residualY))
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
                Log.i("KyantSpaceProbe288158", "KYANT288158_SPACE host=" +
                    System.identityHashCode(host) + " draws=" + state.count +
                    " hostScreenX=" + hostX + " hostScreenY=" + hostY +
                    " sourceDrawX=" + sourceX + " sourceDrawY=" + sourceY +
                    " rawOriginDx=" + (sourceX - hostX) + " rawOriginDy=" + (sourceY - hostY) +
                    " stepResidualX=" + residualX + " stepResidualY=" + residualY +
                    " motionMismatch=" + state.motionMismatch +
                    " maxStepResidualX=" + state.maxStepResidualX +
                    " maxStepResidualY=" + state.maxStepResidualY +
                    " visible=" + hasVisibleRect +
                    " visibleRect=" + visible.toShortString() +
                    " windowOffset=" + offsetX + "," + offsetY +
                    " path=" + path + " pathChanges=" + state.pathChanges +
                    " compose=" + composeW + "x" + composeH)
            }
        } catch (_: Throwable) { /* diagnostic must never break rendering */ }
    }
}
