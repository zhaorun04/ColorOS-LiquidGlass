package top.liquidglass.vector

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.compose.ui.geometry.Offset
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max

/**
 * V288.152 controlled consumption-side A/B. Read-only with respect to Compose
 * State: only the returned optical source coordinate can differ. The baseline
 * is returned on every unverified, offscreen or incompatible condition.
 * The callback is CPU draw, NOT a SurfaceFlinger presentation token.
 */
internal object KyantReboundConsume288152 {
    private const val KEY = "liquidglass_kyant_rebound_consume_ab_288152"
    private const val TAG = "KyantConsume288152"
    private var lastSettingNs = 0L
    private var enabledCache = false
    private val lastLogNs = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private fun enabled(anchor: View, now: Long): Boolean {
        // Avoid a ContentResolver query for every draw. Settings changes are
        // reloaded after <=1s; the Launcher restart also resets this cache.
        // Default ON: the lg152_consume_ab.sh baseline still forces 0 for A/B.
        if (lastSettingNs == 0L || now - lastSettingNs >= 1_000_000_000L) {
            lastSettingNs = now
            enabledCache = try {
                Settings.Global.getInt(anchor.context.contentResolver, KEY, 1) == 1
            } catch (_: Throwable) { true }
        }
        return enabledCache
    }

    private fun page(view: View, name: String): Int? {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            try {
                val method: Method = cls.getDeclaredMethod(name)
                if (method.parameterTypes.isNotEmpty()) return null
                method.isAccessible = true
                return (method.invoke(view) as? Number)?.toInt()
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            } catch (_: Throwable) { return null }
        }
        return null
    }

    @JvmStatic fun choose(anchor: View, baseline: Offset,
                          nativePosition: () -> Offset, releaseNs: Long,
                          touchActive: Boolean = false): Offset {
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            // Same-page rebound lasts up to ~650ms after ACTION_UP; a 150ms
            // window missed the second half of the spring-back. While the
            // finger is still down there is no release yet, so the touch
            // phase is admitted directly (the same-page gate still applies).
            val inWindow = touchActive ||
                (releaseNs > 0L && now - releaseNs in 0L..650_000_000L)
            if (!enabled(anchor, now) || !inWindow ||
                !anchor.isAttachedToWindow || !anchor.isShown ||
                !baseline.x.isFinite() || !baseline.y.isFinite()) return baseline
            var p: View? = anchor.parent as? View
            var workspace: View? = null
            var depth = 0
            while (p != null && depth++ < 16) {
                if (p.javaClass.name.contains("Workspace", ignoreCase = true)) {
                    workspace = p
                    break
                }
                p = p.parent as? View
            }
            // No heuristic page guessing: unknown current/target -> baseline.
            val current = workspace?.let { page(it, "getCurrentPage") }
            val target = workspace?.let { page(it, "getNextPage") }
            if (current == null || target == null || current != target) return baseline
            val native = try { nativePosition() } catch (_: Throwable) { return baseline }
            if (!native.x.isFinite() || !native.y.isFinite()) return baseline
            val w = anchor.resources.displayMetrics.widthPixels.toFloat()
            val h = anchor.resources.displayMetrics.heightPixels.toFloat()
            if (native.x + anchor.width <= 0f || native.x >= w ||
                native.y + anchor.height <= 0f || native.y >= h) return baseline
            val dx = native.x - baseline.x
            val dy = native.y - baseline.y
            val gap = max(abs(dx), abs(dy))
            // Root fix: inside a same-page gesture (touch down or <=650ms after
            // release) there is NO legitimate difference between the committed
            // Compose state and the live native matrix -- any gap is pure
            // one-frame pipeline lag, largest exactly at spring-back onset
            // (rebound velocity x frame time = the observed 5..15px and beyond).
            // transformMatrixToGlobal() read in this draw callback is the SAME
            // transform that positions the FolderIcon in this frame, so using it
            // unconditionally is frame-exact; gap caps only re-admitted the
            // stale state on the worst frames. Log the gap for diagnostics.
            val host = System.identityHashCode(anchor)
            val prev = lastLogNs[host] ?: 0L
            if (gap >= 0.25f && now - prev >= 40_000_000L) {
                lastLogNs[host] = now
                Log.i(TAG, "KYANT288152_CONSUME host=$host page=$current " +
                    "ageMs=${(now-releaseNs)/1_000_000f} touch=$touchActive " +
                    "baselineX=${baseline.x} nativeX=${native.x} deltaX=$dx " +
                    "gapPx=$gap applied=true scope=cpu_source_draw_not_presented")
            }
            return native
        } catch (_: Throwable) { return baseline }
    }
}
