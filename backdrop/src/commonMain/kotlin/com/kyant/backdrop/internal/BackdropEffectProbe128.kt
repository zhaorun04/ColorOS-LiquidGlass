package com.kyant.backdrop.internal

/** V288.128: record RenderEffect object/update activity, NOT GPU pass count.
 * Opt-in only. Counters are process-wide, diagnostic and cumulative.
 */
object BackdropEffectProbe128 {
    var enabled: Boolean = false
    var nodeUpdates: Long = 0L
    var effectsRebuilt: Long = 0L
    var effectsFromDraw: Long = 0L
    var effectsFromObserve: Long = 0L
    var effectReferenceChanged: Long = 0L
    var effectReferenceSame: Long = 0L
    var effectPresent: Long = 0L
    var effectAbsent: Long = 0L
    var assignCount: Long = 0L
    var drawCalls: Long = 0L
    var drawRebuilt: Long = 0L
    private var lastLogMs: Long = 0L

    fun reportIfDue() {
        if (!enabled) return
        val now = platformElapsedTime122()
        if (lastLogMs != 0L && now - lastLogMs < 3000L) return
        lastLogMs = now
        platformLifecycleLog122(
            "KYANT288128_EFFECT_WINDOW nodeUpdates=$nodeUpdates drawCalls=$drawCalls " +
            "drawRebuilt=$drawRebuilt effectsRebuilt=$effectsRebuilt " +
            "fromDraw=$effectsFromDraw fromObserve=$effectsFromObserve " +
            "refChanged=$effectReferenceChanged refSame=$effectReferenceSame " +
            "effectPresent=$effectPresent effectAbsent=$effectAbsent " +
            "assignCount=$assignCount " +
            "scope=effect_object_and_cpu_property_assignment_not_gpu_pass_count"
        )
    }
}
