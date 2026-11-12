package top.liquidglass.vector

import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.ui.geometry.Offset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * V288.147 experimental, strictly read-only CPU callback-stage audit.
 * No changes to Lens270.run(View), optical pipeline, Host lifecycle or position state.
 * Needs both frameCorrelation288145=1 and stageAudit288147=1; disabled by default.
 * Callback samples do NOT prove actual SurfaceFlinger presentation order.
 */
internal object KyantStageAudit288147 {
    private data class Entry(
        val seq: Long, val atNs: Long, val pre: Offset?,
        val committed: Offset, val native: Offset, val preAgeNs: Long
    )
    private val active = ConcurrentHashMap<Int, Entry>()

    @JvmStatic fun begin(anchor: View, host: Int, release: Long,
                         pre: Offset?, committed: Offset, native: Offset,
                         preAgeNs: Long) {
        try {
            if (Settings.Global.getInt(anchor.context.contentResolver,
                    "liquidglass_kyant_stage_audit_288147", 0) != 1) return
            if (!anchor.isAttachedToWindow || !anchor.isShown ||
                anchor.width <= 0 || anchor.height <= 0) return
            if (!native.x.isFinite() || !native.y.isFinite()) return
            active[host] = Entry(release, SystemClock.elapsedRealtimeNanos(),
                pre, committed, native, preAgeNs)
        } catch (_: Throwable) { active.remove(host) }
    }

    @JvmStatic fun hasPending(host: Int): Boolean = active.containsKey(host)
    @JvmStatic fun cancel(host: Int) { active.remove(host) }

    @JvmStatic fun finish(host: Int, exit: Offset) {
        val s = active.remove(host) ?: return
        try {
            if (!exit.x.isFinite() || !exit.y.isFinite()) return
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - s.atNs
            if (elapsedNs !in 0L..100_000_000L) return
            val drawDx = exit.x - s.native.x
            val drawDy = exit.y - s.native.y
            val stateGap = max(abs(s.committed.x - s.native.x), abs(s.committed.y - s.native.y))
            val preGap = if (s.pre == null) Float.NaN else
                max(abs(s.pre.x - s.native.x), abs(s.pre.y - s.native.y))
            Log.i("KyantStage288147", "KYANT288147_STAGE host=$host release=${s.seq} " +
                "preX=${s.pre?.x} committedX=${s.committed.x} " +
                "drawEntryNativeX=${s.native.x} drawExitNativeX=${exit.x} " +
                "drawDeltaX=$drawDx drawDeltaY=$drawDy " +
                "stateGapPx=$stateGap preGapPx=$preGap " +
                "preAgeMs=${s.preAgeNs / 1000000f} " +
                "drawDurationMs=${elapsedNs / 1000000f} " +
                "appliedCorrection=false frameToken=UNAVAILABLE scope=cpu_draw_callback")
        } catch (_: Throwable) { /* Diagnostics may never break glass rendering. */ }
    }
}
