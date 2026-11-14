package top.liquidglass.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * V288.169 safety fix: the legacy 16.67ms time bucket can span multiple
 * actual display frames. Route the opt-in 154 path through the validated,
 * per-Host 155 mapping instead. No false HWUI frame ownership is asserted.
 */
internal object KyantGpuFrameSnapshot288154 {
    fun draw(canvas: Canvas, image: Bitmap, paint: Paint, host: View,
             composeWidth: Float, composeHeight: Float,
             screenWidth: Int, screenHeight: Int): Boolean =
        KyantGpuVersionedSnapshot288155.draw(
            canvas, image, paint, host,
            composeWidth, composeHeight, screenWidth, screenHeight
        )
}
