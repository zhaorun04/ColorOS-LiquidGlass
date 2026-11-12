package top.liquidglass.vector

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * V288.149: read-only Host/anchor geometry and consecutive position-delta audit.
 * Called from the already isolated V288.148 provenance recorder, never from run(View).
 * Observations do not change shader, Host bounds, Compose state or scroll position.
 */
internal object KyantGeometryTiming288149 {
    private data class Sample(
        val release: Long, val timeNs: Long, val scroll: Int,
        val nativeX: Float, val committedX: Float,
        val anchorScreenX: Int, val offsetX: Float,
        val current: Int?, val next: Int?
    )
    private val last = ConcurrentHashMap<Int, Sample>()
    private val logged = ConcurrentHashMap<Int, Long>()

    @JvmStatic fun record(
        anchor: View, host: Int, release: Long, ageNs: Long,
        current: Int?, next: Int?, scroll: Int?,
        nativeX: Float, committedX: Float, preX: Float?,
        anchorScreenX: Int
    ) {
        try {
            if (Settings.Global.getInt(anchor.context.contentResolver,
                    "liquidglass_kyant_geometry_timing_288149", 0) != 1) return
            if (ageNs !in 0L..150_000_000L || scroll == null ||
                !nativeX.isFinite() || !committedX.isFinite() ||
                !anchor.isAttachedToWindow || !anchor.isShown) return
            val now = SystemClock.elapsedRealtimeNanos()
            val offsetX = nativeX - anchorScreenX
            val previous = last[host]
            val sample = Sample(release, now, scroll, nativeX, committedX,
                anchorScreenX, offsetX, current, next)
            last[host] = sample
            // Only compare points from the SAME release: never mix page transitions.
            val related = previous != null && previous.release == release &&
                now >= previous.timeNs && now - previous.timeNs < 200_000_000L
            val ds = if (related) scroll - previous!!.scroll else null
            val dn = if (related) nativeX - previous!!.nativeX else null
            val dc = if (related) committedX - previous!!.committedX else null
            val da = if (related) anchorScreenX - previous!!.anchorScreenX else null
            val driftNative = if (ds != null && dn != null) dn + ds else null
            val driftCompose = if (ds != null && dc != null) dc + ds else null
            val offsetChange = if (related) offsetX - previous!!.offsetX else null
            val pageSame = current != null && next != null && current == next
            val phase = when {
                current == null || next == null -> "PAGE_UNKNOWN"
                !pageSame -> "PAGE_TARGET_DIFF"
                related && (previous!!.current != current || previous!!.next != next) -> "PAGE_INDEX_CHANGED"
                else -> "NO_PAGE_TARGET_DIFF_OBSERVED"
            }
            // The 51px result is measured, not presumed to equal padding or bounds.left.
            // Compare candidate geometry, then inspect actual local draw bounds in source.
            val prevLog = logged[host]
            if (prevLog != null && now - prevLog in 0L..39_999_999L) return
            logged[host] = now
            Log.i("KyantGeometry288149", "KYANT288149_GEOMETRY host=$host release=$release " +
                "ageMs=${ageNs / 1_000_000f} phase=$phase pageCurrent=${current ?: "NA"} " +
                "pageNext=${next ?: "NA"} scrollX=$scroll scrollDx=${ds ?: "NA"} " +
                "anchorScreenX=$anchorScreenX anchorX=${anchor.x} anchorLeft=${anchor.left} " +
                "anchorWidth=${anchor.width} anchorPaddingLeft=${anchor.paddingLeft} " +
                "anchorScaleX=${anchor.scaleX} anchorTx=${anchor.translationX} " +
                "nativeX=$nativeX committedX=$committedX preX=${preX ?: Float.NaN} " +
                "hostAnchorOffsetX=$offsetX offsetChange=${offsetChange ?: "NA"} " +
                "nativeDx=${dn ?: "NA"} committedDx=${dc ?: "NA"} anchorDx=${da ?: "NA"} " +
                "nativePlusScrollDx=${driftNative ?: "NA"} " +
                "composePlusScrollDx=${driftCompose ?: "NA"} " +
                "stateGapX=${nativeX - committedX} " +
                "appliedCorrection=false frameToken=UNAVAILABLE scope=read_only_cpu_draw")
        } catch (_: Throwable) {
            // Diagnostic failure must never affect folder rendering.
        }
    }
}
