package com.kyant.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightElement
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.recordLayer
import com.kyant.backdrop.internal.BackdropTrace121
import com.kyant.backdrop.internal.BackdropLifecycle122
import com.kyant.backdrop.internal.BackdropPassProbe127
import com.kyant.backdrop.internal.BackdropEffectProbe128
import com.kyant.backdrop.internal.BackdropEffectIsolation129
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.InnerShadowElement
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.ShadowElement

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront
        )
    }

    override fun update(node: DrawBackdropNode) {
        if (BackdropLifecycle122.enabled) BackdropLifecycle122.backgroundNodeUpdates++
        if (BackdropEffectProbe128.enabled) BackdropEffectProbe128.nodeUpdates++
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?
) : LayoutModifierNode, DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null
    private var lastRecordedLayerId122: Int = 0

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private var padding by mutableFloatStateOf(0f)

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding

        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        onDrawBackdrop {
            with(backdrop) {
                drawBackdrop(
                    density = effectScope,
                    coordinates = layoutCoordinates,
                    layerBlock = layerBlock
                )
            }
        }
        if (padding != 0f) {
            canvas.translate(-padding, -padding)
        }
    }

    private val drawBackdropLayer: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        if (layer != null) {
            val padding = padding

            if (BackdropLifecycle122.enabled) {
                BackdropLifecycle122.backgroundRecords++
                val currentId122 = layer.hashCode()
                if (lastRecordedLayerId122 != 0 && lastRecordedLayerId122 != currentId122)
                    BackdropLifecycle122.backgroundLayerReplaced++
                lastRecordedLayerId122 = currentId122
                BackdropLifecycle122.reportIfDue122("backdrop", currentId122,
                    size.width.toInt(), size.height.toInt(),
                    "padding=$padding coords=${layoutCoordinates != null}")
            }
            // V288.127: count record calls separately from graphics layer allocation.
            // Nested record calls can occur while building exportedBackdrop.
            if (BackdropPassProbe127.enabled) {
                val area127 = (size.width.toInt() + padding.toInt() * 2).coerceAtLeast(0).toLong() *
                    (size.height.toInt() + padding.toInt() * 2).coerceAtLeast(0).toLong()
                if (BackdropPassProbe127.insideExportRecord) {
                    BackdropPassProbe127.nestedBackdropRecords++
                    BackdropPassProbe127.nestedAreaPixels += area127
                } else {
                    BackdropPassProbe127.normalBackdropRecords++
                    BackdropPassProbe127.normalAreaPixels += area127
                }
                if (effectScope.renderEffect != null) BackdropPassProbe127.effectEnabledRecords++
                else BackdropPassProbe127.effectDisabledRecords++
                BackdropPassProbe127.maybeReport()
            }
            val trace121 = BackdropTrace121.enabled
            if (trace121) BackdropTrace121.begin("LG121_BACKDROP_LAYER_RECORD")
            try {
                recordLayer(
                    layer,
                    size = IntSize(
                        size.width.toInt() + padding.toInt() * 2,
                        size.height.toInt() + padding.toInt() * 2
                    ),
                    block = recordBackdropBlock
                )
            } finally {
                if (trace121) BackdropTrace121.end()
            }

            layer.topLeft =
                if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
                else IntOffset.Zero
            if (BackdropPassProbe127.enabled) BackdropPassProbe127.backdropLayerDraws++
            if (trace121) BackdropTrace121.begin("LG121_BACKDROP_LAYER_DRAW")
            try {
                drawLayer(layer)
            } finally {
                if (trace121) BackdropTrace121.end()
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
        }
    }

    override fun ContentDrawScope.draw() {
        if (BackdropPassProbe127.enabled) BackdropPassProbe127.drawNodeCalls++
        if (BackdropEffectProbe128.enabled) BackdropEffectProbe128.drawCalls++
        BackdropEffectIsolation129.onDraw()
        if (effectScope.update(this)) {
            if (BackdropEffectProbe128.enabled) BackdropEffectProbe128.drawRebuilt++
            updateEffects(fromDraw128 = true)
        }
        if (BackdropEffectProbe128.enabled) BackdropEffectProbe128.reportIfDue()

        onDrawBehind?.invoke(this)
        drawBackdropLayer()
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)

        exportedBackdrop?.graphicsLayer?.let { layer ->
            if (BackdropPassProbe127.enabled) {
                BackdropPassProbe127.exportedRecords++
                BackdropPassProbe127.exportedAreaPixels +=
                    size.width.toInt().coerceAtLeast(0).toLong() * size.height.toInt().coerceAtLeast(0).toLong()
            }
            val previousExport127 = BackdropPassProbe127.insideExportRecord
            if (BackdropPassProbe127.enabled) BackdropPassProbe127.insideExportRecord = true
            val trace121 = BackdropTrace121.enabled
            if (trace121) BackdropTrace121.begin("LG121_EXPORTED_LAYER_RECORD")
            try {
                recordLayer(layer) {
                    onDrawBehind?.invoke(this)
                    drawBackdropLayer()
                    onDrawSurface?.invoke(this)
                    onDrawFront?.invoke(this)
                }
            } finally {
                if (BackdropPassProbe127.enabled) BackdropPassProbe127.insideExportRecord = previousExport127
                if (trace121) BackdropTrace121.end()
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads { updateEffects(fromDraw128 = false) }
    }

    private fun updateEffects(fromDraw128: Boolean = false) {
        if (!isRenderEffectSupported()) return
        val trace128 = BackdropTrace121.enabled
        val probe128 = BackdropEffectProbe128.enabled
        val previous128 = if (probe128) effectScope.renderEffect else null
        if (probe128) {
            BackdropEffectProbe128.effectsRebuilt++
            if (fromDraw128) BackdropEffectProbe128.effectsFromDraw++
            else BackdropEffectProbe128.effectsFromObserve++
        }
        if (trace128) BackdropTrace121.begin("LG128_EFFECT_BUILD")
        try {
            effectScope.apply(effects)
        } finally {
            if (trace128) BackdropTrace121.end()
        }
        if (probe128) {
            val current128 = effectScope.renderEffect
            if (previous128 !== current128) BackdropEffectProbe128.effectReferenceChanged++
            else BackdropEffectProbe128.effectReferenceSame++
            if (current128 != null) BackdropEffectProbe128.effectPresent++
            else BackdropEffectProbe128.effectAbsent++
        }
        if (trace128) BackdropTrace121.begin("LG128_EFFECT_ASSIGN")
        try {
            // V288.129 A/B: original (0) or isolate Backdrop render effect (1).
            // Keep effectScope.apply(), padding and recording path unchanged.
            // DO NOT replace this diagnostic bypass with an unconditional optimization.
            val effect129 = if (BackdropEffectIsolation129.mode == 1) null else effectScope.renderEffect
            graphicsLayer?.renderEffect = effect129
            if (BackdropEffectIsolation129.mode == 1)
                BackdropEffectIsolation129.bypassAssignments++
            else BackdropEffectIsolation129.effectAssignments++
        } finally {
            if (trace128) BackdropTrace121.end()
        }
        if (probe128) BackdropEffectProbe128.assignCount++
        padding = effectScope.padding
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()
        if (BackdropLifecycle122.enabled) {
            BackdropLifecycle122.backgroundNodeAttaches++
            BackdropLifecycle122.backgroundLayerCreated++
        }

        observeEffects()
    }

    override fun onDetach() {
        if (BackdropLifecycle122.enabled) BackdropLifecycle122.backgroundNodeDetaches++
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            if (BackdropLifecycle122.enabled) BackdropLifecycle122.backgroundLayerReleased++
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }
        lastRecordedLayerId122 = 0

        effectScope.reset()
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
    }
}
