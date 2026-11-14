package top.liquidglass.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * V288.160 experimental B path. Uses the exact draw-phase source position
 * rather than a second read of the Host affine matrix. No extra Bitmap,
 * no retained mutable matrix, no asynchronous state read. It intentionally
 * does NOT claim to match the final HWUI composition transform.
 */
internal object KyantWorkspaceSource288160 {
    fun draw(canvas: Canvas, image: Bitmap, paint: Paint,
             sourceX: Float, sourceY: Float, width: Float, height: Float,
             previewWidth: Int, previewHeight: Int, screenWidth: Int, screenHeight: Int): Boolean {
        if (image.isRecycled || image.width <= 0 || image.height <= 0 ||
            width <= 0f || height <= 0f || previewWidth <= 0 || previewHeight <= 0 || screenWidth <= 0 || screenHeight <= 0 ||
            !sourceX.isFinite() || !sourceY.isFinite() ||
            !width.isFinite() || !height.isFinite()) return false
        return try {
            // The source bitmap covers the whole physical display. Compose
            // local geometry uses the same drawPosition consumed in this draw.
            val sx = width / previewWidth.toFloat()
            val sy = height / previewHeight.toFloat()
            val dst = RectF(-sourceX * sx, -sourceY * sy,
                (screenWidth - sourceX) * sx, (screenHeight - sourceY) * sy)
            canvas.drawBitmap(image, null, dst, paint)
            true
        } catch (_: Throwable) { false }
    }
}
