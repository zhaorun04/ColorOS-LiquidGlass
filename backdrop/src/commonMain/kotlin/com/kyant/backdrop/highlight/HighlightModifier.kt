package com.kyant.backdrop.highlight

import androidx.compose.ui.Modifier
import com.kyant.shapes.RoundedRectangle
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.backdrop.RuntimeShaderCacheImpl
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.blur
import com.kyant.backdrop.internal.clipOutline
import com.kyant.backdrop.internal.setRuntimeShader
import com.kyant.backdrop.internal.StaticLayerRecordReuse288116
import com.kyant.backdrop.internal.BackdropTrace121
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.ceil

internal class HighlightElement(
    val shapeProvider: ShapeProvider,
    val highlight: () -> Highlight?
) : ModifierNodeElement<HighlightNode>() {

    override fun create(): HighlightNode {
        return HighlightNode(shapeProvider, highlight)
    }

    override fun update(node: HighlightNode) {
        if (node.shapeProvider !== shapeProvider) {
            // V288.118: distinguish provider replacement from node attachment.
            if (StaticLayerRecordReuse288116.enabled) {
                StaticLayerRecordReuse288116.highlightProviderReplaced288118++
                if (node.hasRecordedCache288118()) {
                    StaticLayerRecordReuse288116.highlightProviderInvalidatedLive288118++
                }
            }
            // V288.119: do not discard a live layer just because a new
            // ShapeProvider wrapper was allocated. Only retain a known,
            // value-equal Kyant RoundedRectangle from the last *recorded* key.
            // Unknown/mutable paths still invalidate exactly as before.
            val retain = StaticLayerRecordReuse288116.enabled &&
                node.canRetainRecordedShape288119(shapeProvider.innerShape)
            if (retain) {
                StaticLayerRecordReuse288116.highlightProviderRetained288119++
            } else {
                node.invalidateRecorded288117()
                if (StaticLayerRecordReuse288116.enabled) {
                    StaticLayerRecordReuse288116.highlightProviderInvalidated288119++
                }
            }
        }
        node.shapeProvider = shapeProvider
        node.highlight = highlight
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "highlight"
        properties["shapeProvider"] = shapeProvider
        properties["highlight"] = highlight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HighlightElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (highlight != other.highlight) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + highlight.hashCode()
        return result
    }
}

internal class HighlightNode(
    var shapeProvider: ShapeProvider,
    var highlight: () -> Highlight?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var highlightLayer: GraphicsLayer? = null
    private data class RecordedHighlightKey(
        val size: IntSize,
        val drawingSize: androidx.compose.ui.geometry.Size,
        val density: Float,
        val fontScale: Float,
        val direction: androidx.compose.ui.unit.LayoutDirection,
        val outline: Outline?,
        // V288.119: geometry value, not the provider wrapper identity.
        // Only known Kyant RoundedRectangle Generic outlines are eligible.
        val recordedShape: androidx.compose.ui.graphics.Shape,
        val highlight: Highlight
    )
    private var lastRecordedHighlight: RecordedHighlightKey? = null
    fun invalidateRecorded288117() { lastRecordedHighlight = null }
    fun hasRecordedCache288118(): Boolean = lastRecordedHighlight != null
    fun canRetainRecordedShape288119(candidate: androidx.compose.ui.graphics.Shape): Boolean {
        val recorded = lastRecordedHighlight?.recordedShape ?: return false
        return recorded is RoundedRectangle && candidate is RoundedRectangle &&
            recorded == candidate
    }

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    private var prevStyle: HighlightStyle? = null

    override fun ContentDrawScope.draw() {
        val highlight = highlight()
        if (highlight == null || highlight.width.value <= 0f) {
            return drawContent()
        }

        drawContent()

        val highlightLayer = highlightLayer
        if (highlightLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val safeSize =
                IntSize(
                    ceil(size.width).toInt() + 2,
                    ceil(size.height).toInt() + 2
                )

            val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)
            val clipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            highlightLayer.alpha = highlight.alpha
            highlightLayer.blendMode = highlight.style.blendMode
            // Rectangles and rounded rectangles have value-stable geometry.
            // Generic paths may be mutated in place, so always re-record them.
            // V288.116.1: observe outline type without relaxing cache safety.
            if (StaticLayerRecordReuse288116.enabled) {
                when (outline) {
                    is Outline.Rectangle -> StaticLayerRecordReuse288116.highlightRectangle++
                    is Outline.Rounded -> StaticLayerRecordReuse288116.highlightRounded++
                    else -> StaticLayerRecordReuse288116.highlightGeneric++
                }
            }
            // The production Launcher shape is a value-stable Kyant RoundedRectangle
            // that generates Outline.Generic. Other Generic paths remain excluded.
            // The provider caches the outline and increments its revision when
            // shape, size, direction or density causes an outline rebuild.
            val knownGeneric288117 = outline is Outline.Generic &&
                shapeProvider.innerShape is RoundedRectangle
            if (StaticLayerRecordReuse288116.enabled && knownGeneric288117) {
                StaticLayerRecordReuse288116.highlightKnownGeneric288117++
            }
            val cacheable = outline is Outline.Rectangle ||
                outline is Outline.Rounded || knownGeneric288117
            val key = if (StaticLayerRecordReuse288116.enabled && cacheable) {
                RecordedHighlightKey(safeSize, size, density.density, density.fontScale,
                    layoutDirection,
                    if (knownGeneric288117) null else outline,
                    shapeProvider.innerShape, highlight)
            } else null
            if (StaticLayerRecordReuse288116.enabled && key != null) {
                if (lastRecordedHighlight == null) {
                    StaticLayerRecordReuse288116.highlightCold++
                    if (shapeProvider.outlineRevision288117 > 1L) StaticLayerRecordReuse288116.highlightColdProviderRevisionGt1_288118++
                } else if (key != lastRecordedHighlight) {
                    StaticLayerRecordReuse288116.highlightKeyChanged++
                }
            }
            if (key == null || key != lastRecordedHighlight) {
                configurePaint(highlight)
                val trace121 = BackdropTrace121.enabled
                if (trace121) BackdropTrace121.begin("LG121_HIGHLIGHT_RECORD")
                try {
                    highlightLayer.record(safeSize) {
                        translate(1f, 1f) {
                            val canvas = drawContext.canvas
                            canvas.save()
                            canvas.clipOutline(outline, clipPath)
                            canvas.drawOutline(outline, paint)
                            canvas.restore()
                        }
                    }
                } finally {
                    if (trace121) BackdropTrace121.end()
                }
                StaticLayerRecordReuse288116.highlightRecords++
                lastRecordedHighlight = key
            } else {
                StaticLayerRecordReuse288116.highlightReuses++
            }

            val traceDraw121 = BackdropTrace121.enabled
            if (traceDraw121) BackdropTrace121.begin("LG121_HIGHLIGHT_DRAW")
            try {
                translate(-1f, -1f) {
                    drawLayer(highlightLayer)
                }
            } finally {
                if (traceDraw121) BackdropTrace121.end()
            }
        }
    }

    override fun onAttach() {
        if (StaticLayerRecordReuse288116.enabled) StaticLayerRecordReuse288116.highlightNodeAttached++
        val graphicsContext = requireGraphicsContext()
        highlightLayer = graphicsContext.createGraphicsLayer()
    }

    override fun onDetach() {
        if (StaticLayerRecordReuse288116.enabled) {
            StaticLayerRecordReuse288116.highlightNodeDetached288118++
            if (hasRecordedCache288118()) StaticLayerRecordReuse288116.highlightDetachedLive288118++
        }
        val graphicsContext = requireGraphicsContext()
        highlightLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            highlightLayer = null
        }
        clipPath = null
        runtimeShaderCache.clear()
        prevStyle = null
        lastRecordedHighlight = null
    }

    private fun DrawScope.configurePaint(highlight: Highlight) {
        paint.color = highlight.style.color
        paint.strokeWidth = ceil(highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)) * 2f
        paint.blur(highlight.blurRadius.toPx())
        if (isRuntimeShaderSupported()) {
            val shader =
                with(highlight.style) {
                    createShader(
                        shape = shapeProvider.shape,
                        runtimeShaderCache = runtimeShaderCache
                    )
                }
            paint.setRuntimeShader(shader)
        }
    }
}
