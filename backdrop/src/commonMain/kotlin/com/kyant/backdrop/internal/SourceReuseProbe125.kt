package com.kyant.backdrop.internal

/** V288.125: log only at draw 1 and each 240th source node draw. */
object SourceReuseProbe125 {
    var enabled: Boolean = false
    fun report(line: String) { if (enabled) platformSourceReuseLog125(line) }
}

internal expect fun platformSourceReuseLog125(line: String)
