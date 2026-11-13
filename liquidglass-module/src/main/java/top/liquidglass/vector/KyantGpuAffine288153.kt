package top.liquidglass.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/**
 * V288.153 opt-in A/B: source bitmap -> physical screen -> window -> Host local
 * -> Compose source canvas. Unlike V288.152, this preserves the full parent
 * affine matrix (scale/skew/rotation) instead of only replacing drawPosition.
 * Default OFF. No Bitmap allocation; failure returns false for legacy fallback.
 * This is an experimental geometry alternative, not a confirmed rebound fix.
 */
internal object KyantGpuAffine288153 {
    fun draw(canvas: Canvas, image: Bitmap, paint: Paint, host: View,
             composeWidth: Float, composeHeight: Float,
             screenWidth: Int, screenHeight: Int): Boolean {
        if (host.width <= 0 || host.height <= 0 || image.isRecycled ||
            composeWidth <= 0f || composeHeight <= 0f ||
            screenWidth <= 0 || screenHeight <= 0 ||
            image.width <= 0 || image.height <= 0) return false
        return try {
            val global = Matrix()
            val inverse = Matrix()
            host.transformMatrixToGlobal(global)
            if (!global.invert(inverse)) return false
            val screen = IntArray(2)
            val window = IntArray(2)
            host.rootView.getLocationOnScreen(screen)
            host.rootView.getLocationInWindow(window)
            val mapping = Matrix()
            mapping.setScale(screenWidth.toFloat() / image.width,
                screenHeight.toFloat() / image.height)
            mapping.postTranslate((window[0] - screen[0]).toFloat(),
                (window[1] - screen[1]).toFloat())
            mapping.postConcat(inverse)
            mapping.postScale(composeWidth / host.width, composeHeight / host.height)
            // Guard malformed matrices: a non-finite mapping must not blank glass.
            val values = FloatArray(9)
            mapping.getValues(values)
            if (!values.all { it.isFinite() }) return false
            canvas.drawBitmap(image, mapping, paint)
            true
        } catch (_: Throwable) { false }
    }
}
