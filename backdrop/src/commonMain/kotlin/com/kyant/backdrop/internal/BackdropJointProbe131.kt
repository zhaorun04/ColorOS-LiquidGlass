package com.kyant.backdrop.internal

/** V288.131: diagnostic-only, host gated; no render mutations or GPU metrics. */
object BackdropJointProbe131 {
    var enabled: Boolean = false
    var sourceRecords: Long = 0L
    var backdropRecords: Long = 0L
    var lastReportNs: Long = 0L

    fun reportIfDue() {
        if (!enabled) return
        val now = platformElapsedTime122()
        if (lastReportNs != 0L && now - lastReportNs < 2_000L) return
        lastReportNs = now
        platformLifecycleLog122("KYANT288131_LAYER tMs=$now " +
            "sourceRecords=$sourceRecords backdropRecords=$backdropRecords " +
            "scope=cpu_graphicslayer_record_calls_not_gpu_pass_or_presented_frame")
    }
}
