package com.kyant.backdrop.internal

/** V288.127: opt-in CPU-side layer operation topology counters.
 * Does not estimate GPU execution time, texture transfer, or frame presentation.
 * All accesses are expected from the Compose draw/UI thread; numbers are diagnostic.
 */
object BackdropPassProbe127 {
    var enabled = false
    var sourceRecords = 0L
    var drawNodeCalls = 0L
    var normalBackdropRecords = 0L
    var nestedBackdropRecords = 0L
    var backdropLayerDraws = 0L
    var exportedRecords = 0L
    var effectEnabledRecords = 0L
    var effectDisabledRecords = 0L
    var normalAreaPixels = 0L
    var nestedAreaPixels = 0L
    var exportedAreaPixels = 0L
    var insideExportRecord = false
    private var lastLogMs = 0L

    fun maybeReport() {
        if (!enabled) return
        // Avoid system clock and string construction on most draw callbacks.
        if ((normalBackdropRecords + nestedBackdropRecords) % 256L != 0L) return
        val now = platformElapsedTime122()
        if (now - lastLogMs < 3000L) return
        lastLogMs = now
        platformLifecycleLog122(
            "KYANT288127_PASS_WINDOW sourceRecords=$sourceRecords drawNodeCalls=$drawNodeCalls " +
                "normalRecords=$normalBackdropRecords nestedRecords=$nestedBackdropRecords " +
                "backdropDraws=$backdropLayerDraws exportedRecords=$exportedRecords " +
                "effectOnRecords=$effectEnabledRecords effectOffRecords=$effectDisabledRecords " +
                "normalAreaPx=$normalAreaPixels nestedAreaPx=$nestedAreaPixels " +
                "exportedAreaPx=$exportedAreaPixels " +
                "scope=cpu_record_draw_calls_and_nominal_pixel_area_not_gpu_cost"
        )
    }
}
