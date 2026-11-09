package com.kyant.backdrop.internal

/** V288.116: Launcher-controlled, default-off A/B for recording only static
 * highlight and shadow masks. Real-time backdrop/refraction stays unchanged.
 * Set once before the glass Hosts are composed, then restart Launcher.
 */
object StaticLayerRecordReuse288116 {
    var enabled: Boolean = false
    private var lastReportMs: Long = 0L
    fun shouldReport(nowMs: Long): Boolean {
        if (!enabled || nowMs - lastReportMs < 2000L) return false
        lastReportMs = nowMs
        return true
    }
    var highlightRecords: Long = 0L
    var highlightReuses: Long = 0L
    var shadowRecords: Long = 0L
    var shadowReuses: Long = 0L
}
