package top.liquidglass.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import android.view.View

/**
 * V288.155 experiment: geometry-validated matrix reuse.
 * Never assumes that elapsed time identifies an HWUI frame. Every consumption
 * re-reads the Host's global transform; cache is reused only when the full
 * matrix and relevant geometry are unchanged. No Bitmap copies or logging.
 * This does not guarantee synchronization with SurfaceFlinger composition.
 */
internal object KyantGpuVersionedSnapshot288155 {
    // One mapping per Host. Weak keys avoid retaining detached Launcher views.
    private class HostCache {
        var width = 0f
        var height = 0f
        var screenWidth = 0
        var screenHeight = 0
        var bitmapWidth = 0
        var bitmapHeight = 0
        var hostWidth = 0
        var hostHeight = 0
        val global = FloatArray(9)
        var offsetX = 0
        var offsetY = 0
        val mapping = Matrix()
        var valid = false
    }
    private val caches = java.util.WeakHashMap<View, HostCache>()

    fun draw(canvas: Canvas, image: Bitmap, paint: Paint, host: View,
             composeWidth: Float, composeHeight: Float,
             screenWidth: Int, screenHeight: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() ||
            host.width <= 0 || host.height <= 0 || image.isRecycled ||
            composeWidth <= 0f || composeHeight <= 0f ||
            screenWidth <= 0 || screenHeight <= 0 ||
            image.width <= 0 || image.height <= 0) return false
        return try {
            val global = Matrix()
            host.transformMatrixToGlobal(global)
            val values = FloatArray(9)
            global.getValues(values)
            if (!values.all { it.isFinite() }) return false
            val screen = IntArray(2)
            val window = IntArray(2)
            host.rootView.getLocationOnScreen(screen)
            host.rootView.getLocationInWindow(window)
            val offsetX = window[0] - screen[0]
            val offsetY = window[1] - screen[1]
            // V288.172: reject a torn geometry read. A Workspace rebound can
            // change a parent transform between reading the global matrix and
            // reading root/window coordinates. Never cache or draw a mapping
            // assembled from two different observed geometry states.
            val verifyGlobal = Matrix()
            host.transformMatrixToGlobal(verifyGlobal)
            val verifyValues = FloatArray(9)
            verifyGlobal.getValues(verifyValues)
            if (!verifyValues.all { it.isFinite() } ||
                values.indices.any { values[it] != verifyValues[it] }) return false
            val verifyScreen = IntArray(2)
            val verifyWindow = IntArray(2)
            host.rootView.getLocationOnScreen(verifyScreen)
            host.rootView.getLocationInWindow(verifyWindow)
            if (verifyWindow[0] - verifyScreen[0] != offsetX ||
                verifyWindow[1] - verifyScreen[1] != offsetY) return false
            val state = caches.getOrPut(host) { HostCache() }
            val same = state.valid &&
                state.width == composeWidth && state.height == composeHeight &&
                state.screenWidth == screenWidth && state.screenHeight == screenHeight &&
                state.bitmapWidth == image.width && state.bitmapHeight == image.height &&
                state.hostWidth == host.width && state.hostHeight == host.height &&
                state.offsetX == offsetX && state.offsetY == offsetY &&
                values.indices.all { values[it] == state.global[it] }
            if (!same) {
                val inverse = Matrix()
                if (!global.invert(inverse)) { state.valid = false; return false }
                val mapping = Matrix()
                mapping.setScale(screenWidth.toFloat() / image.width,
                    screenHeight.toFloat() / image.height)
                mapping.postTranslate(offsetX.toFloat(), offsetY.toFloat())
                mapping.postConcat(inverse)
                mapping.postScale(composeWidth / host.width, composeHeight / host.height)
                val mapped = FloatArray(9)
                mapping.getValues(mapped)
                if (!mapped.all { it.isFinite() }) { state.valid = false; return false }
                state.mapping.set(mapping)
                values.copyInto(state.global)
                state.offsetX = offsetX
                state.offsetY = offsetY
                state.width = composeWidth
                state.height = composeHeight
                state.screenWidth = screenWidth
                state.screenHeight = screenHeight
                state.bitmapWidth = image.width
                state.bitmapHeight = image.height
                state.hostWidth = host.width
                state.hostHeight = host.height
                state.valid = true
            }
            canvas.drawBitmap(image, state.mapping, paint)
            true
        } catch (_: Throwable) { caches.remove(host); false }
    }
}
