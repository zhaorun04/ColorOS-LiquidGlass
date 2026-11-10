package com.kyant.backdrop.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import com.kyant.shapes.RoundedRectangle
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.blur
import com.kyant.backdrop.internal.StaticLayerRecordReuse288116
import kotlin.math.ceil

internal class ShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> Shadow?
) : ModifierNodeElement<ShadowNode>() {

    override fun create(): ShadowNode {
        return ShadowNode(shapeProvider, shadow)
    }

    override fun update(node: ShadowNode) {
        if (node.shapeProvider !== shapeProvider) {
            // V288.118: distinguish provider replacement from node attachment.
            if (StaticLayerRecordReuse288116.enabled) {
                StaticLayerRecordReuse288116.shadowProviderReplaced288118++
                if (node.hasRecordedCache288118()) {
                    StaticLayerRecordReuse288116.shadowProviderInvalidatedLive288118++
                }
            }
            // V288.119: do not discard a live layer just because a new
            // ShapeProvider wrapper was allocated. Only retain a known,
            // value-equal Kyant RoundedRectangle from the last *recorded* key.
            // Unknown/mutable paths still invalidate exactly as before.
            val retain = StaticLayerRecordReuse288116.enabled &&
                node.canRetainRecordedShape288119(shapeProvider.innerShape)
            if (retain) {
                StaticLayerRecordReuse288116.shadowProviderRetained288119++
            } else {
                node.invalidateRecorded288117()
                if (StaticLayerRecordReuse288116.enabled) {
                    StaticLayerRecordReuse288116.shadowProviderInvalidated288119++
                }
            }
        }
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

internal class ShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> Shadow?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null
    private data class RecordedShadowKey(
        val size: IntSize,
        val drawingSize: androidx.compose.ui.geometry.Size,
        val density: Float,
        val fontScale: Float,
        val direction: androidx.compose.ui.unit.LayoutDirection,
        val outline: Outline?,
        // V288.119: geometry value, not the provider wrapper identity.
        // Only known Kyant RoundedRectangle Generic outlines are eligible.
        val recordedShape: androidx.compose.ui.graphics.Shape,
        val shadow: Shadow
    )
    private var lastRecordedShadow: RecordedShadowKey? = null
    fun invalidateRecorded288117() { lastRecordedShadow = null }
    fun hasRecordedCache288118(): Boolean = lastRecordedShadow != null
    fun canRetainRecordedShape288119(candidate: androidx.compose.ui.graphics.Shape): Boolean {
        val recorded = lastRecordedShadow?.recordedShape ?: return false
        return recorded is RoundedRectangle && candidate is RoundedRectangle &&
            recorded == candidate
    }

    private val paint = Paint()

    override fun ContentDrawScope.draw() {
        val shadow = shadow() ?: return drawContent()

        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val radius = shadow.radius.toPx()
            val offsetX = shadow.offset.x.toPx()
            val offsetY = shadow.offset.y.toPx()
            val shadowSize = IntSize(
                ceil(size.width + radius * 4f + offsetX).toInt(),
                ceil(size.height + radius * 4f + offsetY).toInt()
            )
            val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)

            shadowLayer.alpha = shadow.alpha
            shadowLayer.blendMode = shadow.blendMode
            // Preserve live mask updates for mutable/custom paths and any
            // changed outline, density, shadow style, or target size.
            // V288.116.1: observe outline type without relaxing cache safety.
            if (StaticLayerRecordReuse288116.enabled) {
                when (outline) {
                    is Outline.Rectangle -> StaticLayerRecordReuse288116.shadowRectangle++
                    is Outline.Rounded -> StaticLayerRecordReuse288116.shadowRounded++
                    else -> StaticLayerRecordReuse288116.shadowGeneric++
                }
            }
            // Only the known Kyant RoundedRectangle is eligible among Generic
            // paths. Unrecognized custom/mutable paths always re-record.
            val knownGeneric288117 = outline is Outline.Generic &&
                shapeProvider.innerShape is RoundedRectangle
            if (StaticLayerRecordReuse288116.enabled && knownGeneric288117) {
                StaticLayerRecordReuse288116.shadowKnownGeneric288117++
            }
            val cacheable = outline is Outline.Rectangle ||
                outline is Outline.Rounded || knownGeneric288117
            val key = if (StaticLayerRecordReuse288116.enabled && cacheable) {
                RecordedShadowKey(shadowSize, size, density.density, density.fontScale,
                    layoutDirection,
                    if (knownGeneric288117) null else outline,
                    shapeProvider.innerShape, shadow)
            } else null
            if (StaticLayerRecordReuse288116.enabled && key != null) {
                if (lastRecordedShadow == null) {
                    StaticLayerRecordReuse288116.shadowCold++
                    if (shapeProvider.outlineRevision288117 > 1L) StaticLayerRecordReuse288116.shadowColdProviderRevisionGt1_288118++
                } else if (key != lastRecordedShadow) {
                    StaticLayerRecordReuse288116.shadowKeyChanged++
                }
            }
            if (key == null || key != lastRecordedShadow) {
                configurePaint(shadow)
                shadowLayer.record(shadowSize) {
                    translate(radius * 2f + offsetX, radius * 2f + offsetY) {
                        val canvas = drawContext.canvas
                        canvas.drawOutline(outline, paint)
                        canvas.translate(-offsetX, -offsetY)
                        canvas.drawOutline(outline, ShadowMaskPaint)
                        canvas.translate(offsetX, offsetY)
                    }
                }
                StaticLayerRecordReuse288116.shadowRecords++
                lastRecordedShadow = key
            } else {
                StaticLayerRecordReuse288116.shadowReuses++
            }

            translate(-radius * 2f, -radius * 2f) {
                drawLayer(shadowLayer)
            }
        }

        drawContent()
    }

    override fun onAttach() {
        if (StaticLayerRecordReuse288116.enabled) StaticLayerRecordReuse288116.shadowNodeAttached++
        val graphicsContext = requireGraphicsContext()
        shadowLayer =
            graphicsContext.createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
    }

    override fun onDetach() {
        if (StaticLayerRecordReuse288116.enabled) {
            StaticLayerRecordReuse288116.shadowNodeDetached288118++
            if (hasRecordedCache288118()) StaticLayerRecordReuse288116.shadowDetachedLive288118++
        }
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            shadowLayer = null
        }
        lastRecordedShadow = null
    }

    private fun DrawScope.configurePaint(shadow: Shadow) {
        paint.color = shadow.color
        paint.blur(shadow.radius.toPx())
    }
}

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
