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
    private var lastHost: View? = null
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0
    private var lastBitmapWidth = 0
    private var lastBitmapHeight = 0
    private var lastHostWidth = 0
    private var lastHostHeight = 0
    private val previousGlobal = FloatArray(9)
    private val previousWindowOffset = IntArray(2)
    private val cachedMapping = Matrix()
    private var valid = false

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
            val same = valid && lastHost === host &&
                lastWidth == composeWidth && lastHeight == composeHeight &&
                lastScreenWidth == screenWidth && lastScreenHeight == screenHeight &&
                lastBitmapWidth == image.width && lastBitmapHeight == image.height &&
                lastHostWidth == host.width && lastHostHeight == host.height &&
                previousWindowOffset[0] == offsetX && previousWindowOffset[1] == offsetY &&
                values.indices.all { values[it] == previousGlobal[it] }
            if (!same) {
                val inverse = Matrix()
                if (!global.invert(inverse)) { valid = false; return false }
                val mapping = Matrix()
                mapping.setScale(screenWidth.toFloat() / image.width,
                    screenHeight.toFloat() / image.height)
                mapping.postTranslate(offsetX.toFloat(), offsetY.toFloat())
                mapping.postConcat(inverse)
                mapping.postScale(composeWidth / host.width, composeHeight / host.height)
                val mapped = FloatArray(9)
                mapping.getValues(mapped)
                if (!mapped.all { it.isFinite() }) { valid = false; return false }
                cachedMapping.set(mapping)
                values.copyInto(previousGlobal)
                previousWindowOffset[0] = offsetX
                previousWindowOffset[1] = offsetY
                lastHost = host
                lastWidth = composeWidth
                lastHeight = composeHeight
                lastScreenWidth = screenWidth
                lastScreenHeight = screenHeight
                lastBitmapWidth = image.width
                lastBitmapHeight = image.height
                lastHostWidth = host.width
                lastHostHeight = host.height
                valid = true
            }
            canvas.drawBitmap(image, cachedMapping, paint)
            true
        } catch (_: Throwable) { valid = false; false }
    }
}
