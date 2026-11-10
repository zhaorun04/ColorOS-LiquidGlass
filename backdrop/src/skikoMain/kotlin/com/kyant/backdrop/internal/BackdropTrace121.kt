package com.kyant.backdrop.internal

// Desktop / JS / WASM / iOS do not have android.os.Trace.
internal actual fun platformTraceBegin121(name: String) {}
internal actual fun platformTraceEnd121() {}
