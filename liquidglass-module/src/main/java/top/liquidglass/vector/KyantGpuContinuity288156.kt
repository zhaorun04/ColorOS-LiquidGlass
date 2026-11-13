package top.liquidglass.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import android.util.Log
import android.view.View

/**
 * V288.156 experimental continuity guard. Does not smooth real Workspace movement.
 * Only reuses the previous mapping for subpixel matrix noise when the Host's
 * on-screen origin is unchanged. One owner, no Bitmap copy, bounded diagnostics.
 */
internal object KyantGpuContinuity288156 {
    private var lastHost: View? = null
    private var lastBitmap: Bitmap? = null
    private val lastMapping = Matrix()
    private val lastGlobal = FloatArray(9)
    private val lastOrigin = FloatArray(2)
    private var lastW = 0f
    private var lastH = 0f
    private var lastScreenW = 0
    private var lastScreenH = 0
    private var lastHostW = 0
    private var lastHostH = 0
    private var lastOffsetX = 0
    private var lastOffsetY = 0
    private var ready = false
    private var drawCount = 0L
    private var tinyReuse = 0L
    private var largeChange = 0L
    private var failed = 0L

    fun draw(canvas: Canvas, image: Bitmap, paint: Paint, host: View,
             composeWidth: Float, composeHeight: Float,
             screenWidth: Int, screenHeight: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || image.isRecycled ||
            host.width <= 0 || host.height <= 0 || composeWidth <= 0f || composeHeight <= 0f ||
            screenWidth <= 0 || screenHeight <= 0 || image.width <= 0 || image.height <= 0) return false
        return try {
            val global = Matrix()
            host.transformMatrixToGlobal(global)
            val g = FloatArray(9)
            global.getValues(g)
            if (!g.all { it.isFinite() }) return false
            val p = floatArrayOf(0f, 0f)
            global.mapPoints(p)
            val root = host.rootView
            val screen = IntArray(2)
            val window = IntArray(2)
            root.getLocationOnScreen(screen)
            root.getLocationInWindow(window)
            val offsetX = window[0] - screen[0]
            val offsetY = window[1] - screen[1]
            val compatible = ready && lastHost === host && lastBitmap === image &&
                lastW == composeWidth && lastH == composeHeight &&
                lastScreenW == screenWidth && lastScreenH == screenHeight &&
                lastHostW == host.width && lastHostH == host.height &&
                lastOffsetX == offsetX && lastOffsetY == offsetY
            var maxDelta = 0f
            if (compatible) {
                for (i in g.indices) {
                    val d = kotlin.math.abs(g[i] - lastGlobal[i])
                    if (d > maxDelta) maxDelta = d
                }
            }
            // Avoid freezing real motion: only reuse when origin has not moved
            // and affine coefficients differ by floating-point noise alone.
            val sameOrigin = compatible && kotlin.math.abs(p[0] - lastOrigin[0]) <= 0.001f &&
                kotlin.math.abs(p[1] - lastOrigin[1]) <= 0.001f
            val reuse = compatible && sameOrigin && maxDelta <= 0.0001f
            if (!reuse) {
                val inverse = Matrix()
                if (!global.invert(inverse)) { ready = false; failed++; return false }
                val mapping = Matrix()
                mapping.setScale(screenWidth.toFloat() / image.width,
                    screenHeight.toFloat() / image.height)
                mapping.postTranslate(offsetX.toFloat(), offsetY.toFloat())
                mapping.postConcat(inverse)
                mapping.postScale(composeWidth / host.width, composeHeight / host.height)
                val mapped = FloatArray(9)
                mapping.getValues(mapped)
                if (!mapped.all { it.isFinite() }) { ready = false; failed++; return false }
                lastMapping.set(mapping)
                g.copyInto(lastGlobal)
                lastOrigin[0] = p[0]
                lastOrigin[1] = p[1]
                lastHost = host
                lastBitmap = image
                lastW = composeWidth
                lastH = composeHeight
                lastScreenW = screenWidth
                lastScreenH = screenHeight
                lastHostW = host.width
                lastHostH = host.height
                lastOffsetX = offsetX
                lastOffsetY = offsetY
                ready = true
                if (compatible && maxDelta > 8f) largeChange++
            } else tinyReuse++
            canvas.drawBitmap(image, lastMapping, paint)
            drawCount++
            if (drawCount % 600L == 0L) {
                Log.i("KyantContinuity288156", "KYANT288156_CONTINUITY draws=$drawCount " +
                    "tinyReuse=$tinyReuse largeChange=$largeChange failures=$failed")
            }
            true
        } catch (_: Throwable) { ready = false; failed++; false }
    }
}
