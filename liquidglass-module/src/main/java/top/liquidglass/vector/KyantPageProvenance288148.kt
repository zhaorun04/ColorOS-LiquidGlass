package top.liquidglass.vector

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * V288.148: opt-in, read-only coordinate provenance and page-transition evidence.
 * This is a CPU callback observation, NOT a presented frame or definitive page transition.
 * Never sets a View transform, Compose position, or Kyant sampling coordinate.
 */
internal object KyantPageProvenance288148 {
    private data class Track(val release: Long, val startPage: Int?, val startScroll: Int,
                             val atNs: Long)
    private val tracks = ConcurrentHashMap<Int, Track>()
    private val lastNs = ConcurrentHashMap<Int, Long>()
    private const val LOG_TAG = "KyantPage288148"

    private fun pageMethod(workspace: View, methodName: String): Int? {
        var cls: Class<*>? = workspace.javaClass
        while (cls != null) {
            try {
                val method: Method = cls.getDeclaredMethod(methodName)
                if (method.parameterTypes.isNotEmpty()) return null
                method.isAccessible = true
                val value = method.invoke(workspace)
                return (value as? Number)?.toInt()
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    @JvmStatic fun record(anchor: View, host: Int, release: Long, ageNs: Long,
                          committedX: Float, preX: Float?, nativeX: Float,
                          screenWidth: Int) {
        try {
            if (Settings.Global.getInt(anchor.context.contentResolver,
                    "liquidglass_kyant_page_provenance_288148", 0) != 1) return
            if (ageNs !in 0L..150_000_000L || !anchor.isAttachedToWindow || !anchor.isShown) return
            if (!nativeX.isFinite() || !committedX.isFinite() ||
                nativeX + anchor.width <= 0f || nativeX >= screenWidth.toFloat()) return
            val now = SystemClock.elapsedRealtimeNanos()
            val prev = lastNs[host]
            if (prev != null && now - prev in 0L..39_999_999L) return
            lastNs[host] = now
            var ancestor: View? = anchor.parent as? View
            var workspace: View? = null
            var depth = 0
            while (ancestor != null && depth++ < 16) {
                if (ancestor.javaClass.name.contains("Workspace", ignoreCase = true)) {
                    workspace = ancestor
                    break
                }
                ancestor = ancestor.parent as? View
            }
            val current = workspace?.let { pageMethod(it, "getCurrentPage") }
            val next = workspace?.let { pageMethod(it, "getNextPage") }
            val scroll = workspace?.scrollX
            val first = tracks[host]
            val record = if (first == null || first.release != release) {
                Track(release, current, scroll ?: Int.MIN_VALUE, now).also { tracks[host] = it }
            } else first
            val deltaScroll = if (scroll != null && record.startScroll != Int.MIN_VALUE)
                scroll - record.startScroll else null
            val pageChange = current != null && record.startPage != null && current != record.startPage
            val targetDiff = current != null && next != null && current != next
            val phase = when {
                workspace == null -> "WORKSPACE_UNKNOWN"
                pageChange -> "PAGE_CHANGE_OBSERVED"
                targetDiff -> "PAGE_TARGET_DIFF"
                current == null -> "PAGE_INDEX_UNKNOWN"
                else -> "NO_PAGE_CHANGE_OBSERVED"
            }
            val xy = IntArray(2)
            anchor.getLocationOnScreen(xy)
            val wsX = IntArray(2)
            workspace?.getLocationOnScreen(wsX)
            val gap = nativeX - committedX
            val preGap = if (preX == null) Float.NaN else nativeX - preX
            Log.i(LOG_TAG, "KYANT288148_PAGE host=$host release=$release ageMs=${ageNs / 1000000f} " +
                "phase=$phase pageCurrent=${current ?: "NA"} pageNext=${next ?: "NA"} " +
                "pageStart=${record.startPage ?: "NA"} workspaceFound=${workspace != null} " +
                "workspaceScrollX=${scroll ?: "NA"} scrollDelta=${deltaScroll ?: "NA"} " +
                "workspaceScreenX=${if (workspace != null) wsX[0].toString() else "NA"} " +
                "workspaceTx=${workspace?.translationX ?: Float.NaN} " +
                "anchorScreenX=${xy[0]} anchorX=${anchor.x} anchorTx=${anchor.translationX} " +
                "nativeX=$nativeX committedX=$committedX preX=${preX ?: Float.NaN} " +
                "nativeStateDeltaX=$gap nativePreDeltaX=$preGap " +
                "appliedCorrection=false frameToken=UNAVAILABLE scope=read_only_cpu_draw")
        } catch (_: Throwable) {
            // Diagnostics must never interfere with the optical pipeline.
        }
    }
}
