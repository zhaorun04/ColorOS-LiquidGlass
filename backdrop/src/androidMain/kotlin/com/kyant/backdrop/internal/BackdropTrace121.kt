package com.kyant.backdrop.internal

internal actual fun platformTraceBegin121(name: String) {
    android.os.Trace.beginSection(name)
}

internal actual fun platformTraceEnd121() {
    android.os.Trace.endSection()
}
