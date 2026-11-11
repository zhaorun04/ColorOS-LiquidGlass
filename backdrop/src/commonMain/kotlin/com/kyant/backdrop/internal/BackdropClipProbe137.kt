package com.kyant.backdrop.internal

/** V288.137: low-rate app-side layer topology accounting only.
 * This reports a Compose layer configuration, not an HWUI mask or GPU pass.
 */
object BackdropClipProbe137 {
    var enabled: Boolean = false
    fun log(message: String) {
        if (enabled) platformLifecycleLog122(message)
    }
}
