package com.kyant.backdrop.internal

/** V288.130 diagnostic only: record/layer identity and per-node source input deltas.
 * No access to HWUI native texture IDs; size/time correlation is not identity proof.
 * Log reporting happens on the same Compose draw path, and is deliberately sparse.
 */
object BackdropResourceProbe130 {
    var enabled: Boolean = false

    fun log(line: String) {
        if (enabled) platformLifecycleLog122(line)
    }
}
