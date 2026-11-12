package top.liquidglass.vector

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * V288.150: records the FIRST OBSERVATION of a changed Compose state and the
 * state version seen by the current CPU draw callback. Not a write-site hook.
 * This distinction matters: the real publish instruction may have occurred
 * earlier than firstSeenNs. No changes to rendering, View, Compose or optics.
 *
 * Accessed only from the already opt-in provenance helper, not run(View).
 */
internal object KyantStateConsumption288150 {
    private data class Snapshot(
        val release: Long, val atNs: Long, val scroll: Int,
        val nativeX: Float, val stateX: Float, val preX: Float?,
        val revision: Long, val firstSeenNs: Long,
        val current: Int?, val next: Int?, val anchorScreenX: Int
    )
    private val last = ConcurrentHashMap<Int, Snapshot>()
    private val revisionByHost = ConcurrentHashMap<Int, Long>()

    @JvmStatic fun record(
        anchor: View, host: Int, release: Long, ageNs: Long,
        current: Int?, next: Int?, scroll: Int?,
        nativeX: Float, committedX: Float, preX: Float?, anchorScreenX: Int
    ) {
        try {
            if (Settings.Global.getInt(anchor.context.contentResolver,
                    "liquidglass_kyant_state_consume_probe_288150", 0) != 1) return
            if (ageNs !in 0L..150_000_000L || scroll == null ||
                !anchor.isAttachedToWindow || !anchor.isShown ||
                !nativeX.isFinite() || !committedX.isFinite()) return
            val now = SystemClock.elapsedRealtimeNanos()
            val old = last[host]
            val changed = old == null || abs(old.stateX - committedX) > 0.015625f
            val revision = if (changed) (revisionByHost[host] ?: 0L) + 1L
                else old!!.revision
            if (changed) revisionByHost[host] = revision
            val firstSeenNs = if (changed) now else old!!.firstSeenNs
            val related = old != null && old.release == release &&
                now >= old.atNs && now - old.atNs < 200_000_000L
            val ds = if (related) scroll - old!!.scroll else null
            val dn = if (related) nativeX - old!!.nativeX else null
            val dc = if (related) committedX - old!!.stateX else null
            val dp = if (related && old!!.preX != null && preX != null)
                preX - old.preX else null
            val stateDrift = if (ds != null && dc != null) ds + dc else null
            val nativeDrift = if (ds != null && dn != null) ds + dn else null
            val ageSinceFirstSeenMs = (now - firstSeenNs) / 1_000_000f
            val phase = when {
                current == null || next == null -> "PAGE_UNKNOWN"
                current != next -> "PAGE_TARGET_DIFF"
                related && (old!!.current != current || old.next != next) -> "PAGE_INDEX_CHANGED"
                else -> "NO_PAGE_TARGET_DIFF_OBSERVED"
            }
            val snap = Snapshot(release, now, scroll, nativeX, committedX,
                preX, revision, firstSeenNs, current, next, anchorScreenX)
            last[host] = snap
            // This is the state READ by the draw observer. Do not call it
            // an actual publishing timestamp or a SurfaceFlinger frame token.
            Log.i("KyantState288150", "KYANT288150_CONSUME host=$host release=$release " +
                "ageMs=${ageNs / 1_000_000f} phase=$phase " +
                "pageCurrent=${current ?: "NA"} pageNext=${next ?: "NA"} " +
                "scrollX=$scroll scrollDx=${ds ?: "NA"} " +
                "nativeX=$nativeX nativeDx=${dn ?: "NA"} " +
                "stateX=$committedX stateDx=${dc ?: "NA"} preX=${preX ?: "NA"} " +
                "preDx=${dp ?: "NA"} stateRevision=$revision stateChanged=$changed " +
                "observedStateAgeMs=$ageSinceFirstSeenMs " +
                "nativePlusScrollDx=${nativeDrift ?: "NA"} " +
                "statePlusScrollDx=${stateDrift ?: "NA"} " +
                "stateGapX=${nativeX - committedX} " +
                "anchorScreenX=$anchorScreenX hostAnchorOffsetX=${nativeX - anchorScreenX} " +
                "appliedCorrection=false actualWriteTimestamp=UNAVAILABLE " +
                "frameToken=UNAVAILABLE scope=cpu_draw_state_first_observed")
        } catch (_: Throwable) {
            // A diagnostic is never allowed to affect Kyant rendering.
        }
    }
}
