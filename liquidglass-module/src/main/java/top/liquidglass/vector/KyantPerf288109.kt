package top.liquidglass.vector

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * V288.109 — opt-in CPU callback accounting across ALL folder Hosts.
 * Only measured when the per-Host Global setting is enabled on Launcher startup.
 * No Choreographer/surface frame equivalence is claimed; overlapping stages must
 * not be added together. No Bitmap copies or Compose state writes are performed.
 */
internal object KyantPerf288109 {
    const val PREDRAW = 0
    const val BRIDGE = 1
    const val NATIVE_DRAW = 2
    const val COMPOSE_SOURCE = 3
    private const val STAGES = 4
    private const val WINDOW_NS = 2_000_000_000L
    private val samples = AtomicLongArray(STAGES)
    private val durations = AtomicLongArray(STAGES)
    private val peaks = AtomicLongArray(STAGES)
    private val bridgePixels = AtomicLong(0L)
    private val skipped = AtomicLong(0L)
    private val notifications = AtomicLong(0L)
    // Host membership is updated only at attach/dispose, not on every frame.
    // Each value stores [usesNativeBitmapBridge, containerPixelArea].
    private val active = ConcurrentHashMap<Int, Pair<Boolean, Long>>()
    private val reportedNs = AtomicLong(0L)

    fun register(id: Int, bridge: Boolean, pixels: Long) {
        active[id] = Pair(bridge, pixels)
        Log.i("KyantPerf288109", "KYANT288109_HOST_ATTACH host=$id bridge=$bridge pixels=$pixels active=${active.size}")
    }

    fun unregister(id: Int) {
        active.remove(id)
        Log.i("KyantPerf288109", "KYANT288109_HOST_DETACH host=$id active=${active.size}")
    }

    fun record(stage: Int, ns: Long, pixels: Long = 0L) {
        if (stage !in 0 until STAGES) return
        if (ns < 0L) return
        samples.incrementAndGet(stage)
        durations.addAndGet(stage, ns)
        var old = peaks.get(stage)
        while (ns > old && !peaks.compareAndSet(stage, old, ns)) old = peaks.get(stage)
        if (stage == BRIDGE) bridgePixels.addAndGet(pixels)
    }

    fun skipBridge() { skipped.incrementAndGet() }
    fun notifyCompose() { notifications.incrementAndGet() }

    fun flushIfDue() {
        val now = SystemClock.elapsedRealtimeNanos()
        val previous = reportedNs.get()
        if (previous != 0L && now - previous < WINDOW_NS) return
        if (!reportedNs.compareAndSet(previous, now)) return
        // One line / 2 seconds globally, not per Host. Swap windows atomically;
        // concurrent updates that straddle the swap can move to the next window.
        val c = LongArray(STAGES) { samples.getAndSet(it, 0L) }
        val n = LongArray(STAGES) { durations.getAndSet(it, 0L) }
        val peak = LongArray(STAGES) { peaks.getAndSet(it, 0L) }
        val hosts = active.values.toList()
        val bridgeHosts = hosts.count { it.first }
        val totalArea = hosts.sumOf { it.second }
        fun row(i: Int): String {
            val avg = if (c[i] > 0L) n[i] / c[i] / 1_000f else 0f
            return "n=${c[i]},sumMs=${n[i]/1_000_000f},avgUs=$avg,maxMs=${peak[i]/1_000_000f}"
        }
        val elapsedMs = if (previous > 0L) (now - previous)/1_000_000L else 0L
        Log.i("KyantPerf288109", "KYANT288109_PERF windowMs=$elapsedMs " +
            "activeHosts=${hosts.size} bridgeHosts=$bridgeHosts totalHostPixels=$totalArea " +
            "predraw={${row(PREDRAW)}} bridge={${row(BRIDGE)}} " +
            "nativeDraw={${row(NATIVE_DRAW)}} composeSource={${row(COMPOSE_SOURCE)}} " +
            "bridgePixels=${bridgePixels.getAndSet(0L)} " +
            "bridgeSkipped=${skipped.getAndSet(0L)} " +
            "composeNotifies=${notifications.getAndSet(0L)} " +
            "note=callbackCpuOnly_notGpuFrame")
    }
}
