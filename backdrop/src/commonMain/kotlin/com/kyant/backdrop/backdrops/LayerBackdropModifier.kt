package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.recordLayer
import com.kyant.backdrop.internal.BackdropTrace121
import com.kyant.backdrop.internal.BackdropLifecycle122

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        if (BackdropLifecycle122.enabled) BackdropLifecycle122.sourceNodeUpdates++
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        if (BackdropLifecycle122.enabled) {
            BackdropLifecycle122.sourceRecords++
            BackdropLifecycle122.reportIfDue122("source", backdrop.graphicsLayer.hashCode(),
                size.width.toInt(), size.height.toInt(), "")
        }
        drawContent()
        val trace121 = BackdropTrace121.enabled
        if (trace121) BackdropTrace121.begin("LG121_SOURCE_LAYER_RECORD")
        try {
            recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        } finally {
            if (trace121) BackdropTrace121.end()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onAttach() {
        if (BackdropLifecycle122.enabled) BackdropLifecycle122.sourceNodeAttaches++
    }

    override fun onDetach() {
        if (BackdropLifecycle122.enabled) BackdropLifecycle122.sourceNodeDetaches++
        backdrop.layerCoordinates = null
    }
}
