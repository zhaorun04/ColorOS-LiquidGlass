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
    // V288.116.1: diagnostic-only counters; cache eligibility is unchanged.
    var highlightGeneric: Long = 0L
    var highlightRectangle: Long = 0L
    var highlightRounded: Long = 0L
    var highlightCold: Long = 0L
    var highlightKeyChanged: Long = 0L
    var highlightNodeAttached: Long = 0L
    var shadowGeneric: Long = 0L
    var shadowRectangle: Long = 0L
    var shadowRounded: Long = 0L
    var shadowCold: Long = 0L
    var shadowKeyChanged: Long = 0L
    var shadowNodeAttached: Long = 0L
    // V288.117: whitelist-eligible Outline.Generic candidates.
    var highlightKnownGeneric288117: Long = 0L
    var shadowKnownGeneric288117: Long = 0L
}
