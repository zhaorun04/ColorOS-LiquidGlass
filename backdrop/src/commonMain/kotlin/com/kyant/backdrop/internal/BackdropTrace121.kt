package com.kyant.backdrop.internal

/** V288.121: tracing only. Disabled unless the Launcher explicitly opts in.
 * CPU Compose/GraphicsLayer recording spans do not measure GPU execution.
 */
object BackdropTrace121 {
    var enabled: Boolean = false
    fun begin(name: String) { if (enabled) platformTraceBegin121(name) }
    fun end() { if (enabled) platformTraceEnd121() }
}

internal expect fun platformTraceBegin121(name: String)
internal expect fun platformTraceEnd121()
