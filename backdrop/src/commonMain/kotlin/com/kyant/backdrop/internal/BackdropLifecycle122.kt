package com.kyant.backdrop.internal

/** V288.122: low-volume diagnostics; no draw invalidation or resource mutation. */
object BackdropLifecycle122 {
    var enabled: Boolean = false
    var sourceRecords: Long = 0
    var sourceNodeUpdates: Long = 0
    var sourceNodeAttaches: Long = 0
    var sourceNodeDetaches: Long = 0
    var backgroundRecords: Long = 0
    var backgroundNodeUpdates: Long = 0
    var backgroundNodeAttaches: Long = 0
    var backgroundNodeDetaches: Long = 0
    var backgroundLayerCreated: Long = 0
    var backgroundLayerReleased: Long = 0
    var backgroundLayerReplaced: Long = 0
    private var lastLogAt: Long = 0L

    /** Android supplies an elapsed-time gate and emits the log outside the draw loop. */
    fun reportIfDue122(kind: String, layerId: Int, width: Int, height: Int, extra: String) {
        if (!enabled) return
        val now = platformElapsedTime122()
        if (now - lastLogAt < 3000L) return
        lastLogAt = now
        platformLifecycleLog122(
            "KYANT288122_LAYER_WINDOW kind=$kind layerId=$layerId size=${width}x${height} " +
                "sourceRecords=$sourceRecords sourceUpdates=$sourceNodeUpdates " +
                "sourceAttach=$sourceNodeAttaches sourceDetach=$sourceNodeDetaches " +
                "backdropRecords=$backgroundRecords backdropUpdates=$backgroundNodeUpdates " +
                "backdropAttach=$backgroundNodeAttaches backdropDetach=$backgroundNodeDetaches " +
                "backdropCreated=$backgroundLayerCreated backdropReleased=$backgroundLayerReleased " +
                "backdropReplaced=$backgroundLayerReplaced $extra"
        )
    }
}

internal expect fun platformElapsedTime122(): Long
internal expect fun platformLifecycleLog122(line: String)
