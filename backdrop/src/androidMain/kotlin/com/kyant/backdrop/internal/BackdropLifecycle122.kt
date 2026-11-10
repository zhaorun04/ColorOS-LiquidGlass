package com.kyant.backdrop.internal

internal actual fun platformElapsedTime122(): Long = android.os.SystemClock.elapsedRealtime()
internal actual fun platformLifecycleLog122(line: String) {
    android.util.Log.i("KyantLayer288122", line)
}
