package com.kyant.backdrop.internal

/** V288.129 diagnostic ONLY.
 * mode 0 = original RenderEffect; mode 1 = suppress Backdrop GraphicsLayer.renderEffect
 * while keeping reference wallpaper, source recording, geometry and effect scope unchanged.
 * The mode is read when the Launcher host is created and requires Launcher restart.
 * B mode intentionally changes glass appearance and MUST NOT be used as a quality-preserving fix.
 */
object BackdropEffectIsolation129 {
    var mode: Int = 0
    var drawCalls: Long = 0L
    var effectAssignments: Long = 0L
    var bypassAssignments: Long = 0L
    private var lastReportMs: Long = 0L

    fun onDraw() {
        if (mode !in 0..1) return
        drawCalls++
        val now = platformElapsedTime122()
        if (lastReportMs != 0L && now - lastReportMs < 3000L) return
        lastReportMs = now
        platformLifecycleLog122(
            "KYANT288129_WINDOW mode=$mode draws=$drawCalls effectAssignments=$effectAssignments " +
            "bypassAssignments=$bypassAssignments " +
            "scope=diagnostic_backdrop_rendereffect_assignment_ab_not_gpu_pass_count"
        )
    }
}
