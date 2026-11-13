package top.liquidglass.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import android.view.Choreographer
import android.view.View

/**
 * V288.154 opt-in frame-owned transform snapshot. The first consumer of a
 * Choreographer frame captures the full affine mapping; subsequent consumers
 * within the same frame reuse it. No bitmap copies, no per-frame logcat.
 * A mode remains V288.153 affine. B mode is an experiment, not a proven fix.
 */
internal object KyantGpuFrameSnapshot288154 {
    private var lastHost: View? = null
    private var lastFrame: Long = Long.MIN_VALUE
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0
    private var lastBitmapWidth = 0
    private var lastBitmapHeight = 0
    private val cached = Matrix()

    fun draw(canvas: Canvas, image: Bitmap, paint: Paint, host: View,
             composeWidth: Float, composeHeight: Float,
             screenWidth: Int, screenHeight: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() ||
            host.width <= 0 || host.height <= 0 || image.isRecycled ||
            composeWidth <= 0f || composeHeight <= 0f ||
            screenWidth <= 0 || screenHeight <= 0 ||
            image.width <= 0 || image.height <= 0) return false
        return try {
            val frame = try { Choreographer.getInstance().frameTimeNanos }
                        catch (_: Throwable) { Long.MIN_VALUE }
            val reusable = frame != Long.MIN_VALUE && frame == lastFrame &&
                lastHost === host && lastWidth == composeWidth &&
                lastHeight == composeHeight && lastScreenWidth == screenWidth &&
                lastScreenHeight == screenHeight && lastBitmapWidth == image.width &&
                lastBitmapHeight == image.height
            if (!reusable) {
                val global = Matrix()
                val inverse = Matrix()
                host.transformMatrixToGlobal(global)
                if (!global.invert(inverse)) return false
                val screen = IntArray(2)
                val window = IntArray(2)
                val root = host.rootView
                root.getLocationOnScreen(screen)
                root.getLocationInWindow(window)
                val mapping = Matrix()
                mapping.setScale(screenWidth.toFloat() / image.width,
                    screenHeight.toFloat() / image.height)
                mapping.postTranslate((window[0] - screen[0]).toFloat(),
                    (window[1] - screen[1]).toFloat())
                mapping.postConcat(inverse)
                mapping.postScale(composeWidth / host.width, composeHeight / host.height)
                val values = FloatArray(9)
                mapping.getValues(values)
                if (!values.all { it.isFinite() }) return false
                cached.set(mapping)
                lastHost = host
                lastFrame = frame
                lastWidth = composeWidth
                lastHeight = composeHeight
                lastScreenWidth = screenWidth
                lastScreenHeight = screenHeight
                lastBitmapWidth = image.width
                lastBitmapHeight = image.height
            }
            canvas.drawBitmap(image, cached, paint)
            true
        } catch (_: Throwable) { false }
    }
}
