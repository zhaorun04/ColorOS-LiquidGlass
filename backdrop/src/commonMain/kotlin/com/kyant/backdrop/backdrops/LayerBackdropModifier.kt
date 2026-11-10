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
import com.kyant.backdrop.internal.BackdropPassProbe127
import com.kyant.backdrop.internal.BackdropResourceProbe130

/** V288.126 diagnostic-only projection of the source visual key.
 *  No reference to Android APIs, no bypass of source record checks.
 */
interface SourceKeyDetails126 {
    val sourceX126: Float
    val sourceY126: Float
    val bitmapId126: Int
    val bitmapGeneration126: Int
    val geometryStamp126: Int
}

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
            node.clearSourceRecord125()
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

    private var lastSourceRecordKey125: Any? = null
    private var lastSourceLayer125: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    private var lastSourceWidth125 = -1
    private var lastSourceHeight125 = -1
    private var lastSourceDensity125 = Float.NaN
    private var lastSourceFontScale125 = Float.NaN
    private var lastSourceLayout125: Any? = null
    private var sourceRecords125 = 0L
    private var sourceReused125 = 0L
    private var sourceFallback125 = 0L
    private var sourceInvalidated125 = 0L
    private var sourceKeyEligible125 = 0L
    private var sourceDraws125 = 0L
    // V288.126 diagnostic totals, only sampled when source experiment is enabled.
    private var positionChanged126 = 0L
    private var bitmapChanged126 = 0L
    private var geometryChanged126 = 0L
    private var layerChanged126 = 0L
    private var scopeChanged126 = 0L
    private var equalKeyMiss126 = 0L
    private var otherKeyChanged126 = 0L
    private var noPrevious126 = 0L
    private var unavailableKey126 = 0L
    private var knownKey126 = 0L
    // V288.130 source-path per-node counters; no changes to record/reuse behavior.
    private var inputReports130 = 0L
    private var positionDelta130 = 0L
    private var stablePosition130 = 0L
    private var bitmapDelta130 = 0L
    private var missingDetails130 = 0L
    private var previousX130 = Float.NaN
    private var previousY130 = Float.NaN
    private var previousBitmapId130 = 0
    private var previousBitmapGen130 = 0
    private var hasPrevious130 = false
    private var lastReport130 = 0L


    fun clearSourceRecord125() {
        lastSourceRecordKey125 = null
        lastSourceLayer125 = null
        lastSourceWidth125 = -1
        lastSourceHeight125 = -1
        sourceInvalidated125++
    }

    override fun ContentDrawScope.draw() {
        if (BackdropPassProbe127.enabled) BackdropPassProbe127.sourceRecords++
        if (BackdropLifecycle122.enabled) {
            BackdropLifecycle122.sourceRecords++
            BackdropLifecycle122.reportIfDue122("source", backdrop.graphicsLayer.hashCode(),
                size.width.toInt(), size.height.toInt(), "")
        }
        drawContent()
        // V288.125: never skip drawContent(). Reuse only the already recorded
        // SOURCE GraphicsLayer, after validating an explicit immutable visual key.
        // If key provider is unavailable or throws, fail open to normal recording.
        val key125 = try { backdrop.sourceRecordKey125?.invoke() } catch (_: Throwable) { null }
        val layerId125 = backdrop.graphicsLayer.hashCode()
        val w125 = size.width.toInt()
        val h125 = size.height.toInt()
        val density125 = density
        val font125 = fontScale
        val layout125 = layoutDirection
        val probe130 = BackdropResourceProbe130.enabled
        if (probe130) {
            inputReports130++
            val info130 = key125 as? SourceKeyDetails126
            if (info130 == null) missingDetails130++
            else {
                if (hasPrevious130) {
                    if (info130.sourceX126 != previousX130 || info130.sourceY126 != previousY130)
                        positionDelta130++
                    else stablePosition130++
                    if (info130.bitmapId126 != previousBitmapId130 ||
                        info130.bitmapGeneration126 != previousBitmapGen130) bitmapDelta130++
                }
                previousX130 = info130.sourceX126
                previousY130 = info130.sourceY126
                previousBitmapId130 = info130.bitmapId126
                previousBitmapGen130 = info130.bitmapGeneration126
                hasPrevious130 = true
            }
            val now130 = com.kyant.backdrop.internal.platformElapsedTime122()
            if (lastReport130 == 0L || now130 - lastReport130 >= 3000L) {
                lastReport130 = now130
                BackdropResourceProbe130.log(
                    "KYANT288130_SOURCE layerId=$layerId125 size=${w125}x${h125} " +
                    "draws=$inputReports130 recorded=$sourceRecords125 reused=$sourceReused125 " +
                    "posChanged=$positionDelta130 posStable=$stablePosition130 " +
                    "bitmapChanged=$bitmapDelta130 unavailable=$missingDetails130 " +
                    "bitmapId=$previousBitmapId130 generation=$previousBitmapGen130 " +
                    "x=$previousX130 y=$previousY130 " +
                    "scope=app_graphicslayer_source_record_not_hwui_texture_id")
            }
        }
        sourceDraws125++
        if (key125 != null) sourceKeyEligible125++ else sourceFallback125++
        val reuse125 = key125 != null && lastSourceRecordKey125 != null &&
            key125 == lastSourceRecordKey125 && lastSourceLayer125 === backdrop.graphicsLayer &&
            lastSourceWidth125 == w125 && lastSourceHeight125 == h125 &&
            lastSourceDensity125 == density125 && lastSourceFontScale125 == font125 &&
            lastSourceLayout125 == layout125
        if (reuse125) {
            sourceReused125++
        } else {
            // Record each mismatch independently; multiple reasons can coexist.
            // The reason counters should NOT be summed as mutually exclusive misses.
            val current126 = key125 as? SourceKeyDetails126
            val previous126 = lastSourceRecordKey125 as? SourceKeyDetails126
            if (key125 == null) unavailableKey126++
            if (lastSourceRecordKey125 == null) noPrevious126++
            if (current126 != null && previous126 != null) {
                knownKey126++
                if (current126.sourceX126 != previous126.sourceX126 ||
                    current126.sourceY126 != previous126.sourceY126) positionChanged126++
                if (current126.bitmapId126 != previous126.bitmapId126 ||
                    current126.bitmapGeneration126 != previous126.bitmapGeneration126) bitmapChanged126++
                if (current126.geometryStamp126 != previous126.geometryStamp126) geometryChanged126++
                if (key125 != lastSourceRecordKey125 &&
                    current126.sourceX126 == previous126.sourceX126 &&
                    current126.sourceY126 == previous126.sourceY126 &&
                    current126.bitmapId126 == previous126.bitmapId126 &&
                    current126.bitmapGeneration126 == previous126.bitmapGeneration126 &&
                    current126.geometryStamp126 == previous126.geometryStamp126) otherKeyChanged126++
            }
            if (lastSourceLayer125 != null && lastSourceLayer125 !== backdrop.graphicsLayer) layerChanged126++
            if (lastSourceRecordKey125 != null && (
                lastSourceWidth125 != w125 || lastSourceHeight125 != h125 ||
                lastSourceDensity125 != density125 || lastSourceFontScale125 != font125 ||
                lastSourceLayout125 != layout125)) scopeChanged126++
            if (key125 != null && lastSourceRecordKey125 != null && key125 == lastSourceRecordKey125)
                equalKeyMiss126++
            if (lastSourceRecordKey125 != null) sourceInvalidated125++
            // Do not publish a new cache key until recording finishes successfully.
            lastSourceRecordKey125 = null
            val trace130 = probe130 && BackdropTrace121.enabled
            if (trace130) BackdropTrace121.begin("LG130_SRC_${w125}x${h125}_L${layerId125}")
            val trace121 = BackdropTrace121.enabled
            if (trace121) BackdropTrace121.begin("LG121_SOURCE_LAYER_RECORD")
            try {
                val trace131 = com.kyant.backdrop.internal.BackdropJointProbe131.enabled && trace121
                if (trace131) BackdropTrace121.begin("LG131_SOURCE_RECORD_${w125}x${h125}")
                try {
                    recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
                } finally {
                    if (trace131) BackdropTrace121.end()
                }
                if (com.kyant.backdrop.internal.BackdropJointProbe131.enabled) {
                    com.kyant.backdrop.internal.BackdropJointProbe131.sourceRecords++
                    com.kyant.backdrop.internal.BackdropJointProbe131.reportIfDue()
                }
                sourceRecords125++
                if (key125 != null) {
                    lastSourceRecordKey125 = key125
                    lastSourceLayer125 = backdrop.graphicsLayer
                    lastSourceWidth125 = w125
                    lastSourceHeight125 = h125
                    lastSourceDensity125 = density125
                    lastSourceFontScale125 = font125
                    lastSourceLayout125 = layout125
                }
            } finally {
                if (trace121) BackdropTrace121.end()
                if (trace130) BackdropTrace121.end()
            }
        }
        if (sourceDraws125 == 1L || sourceDraws125 % 240L == 0L) {
            com.kyant.backdrop.internal.SourceReuseProbe125.report(
                "KYANT288125_SOURCE_CACHE layerId=$layerId125 draws=$sourceDraws125 " +
                "recorded=$sourceRecords125 reused=$sourceReused125 " +
                "fallback=$sourceFallback125 invalidated=$sourceInvalidated125 " +
                "eligible=$sourceKeyEligible125 keyEligible=${key125 != null} " +
                "size=${w125}x${h125} scope=source_layer_only")
            com.kyant.backdrop.internal.SourceReuseProbe125.report(
                "KYANT288126_SOURCE_MISS layerId=$layerId125 draws=$sourceDraws125 " +
                "positionKeyChanged=$positionChanged126 bitmapKeyChanged=$bitmapChanged126 " +
                "geometryKeyChanged=$geometryChanged126 layerChanged=$layerChanged126 " +
                "scopeChanged=$scopeChanged126 keyEqualButMiss=$equalKeyMiss126 " +
                "otherKeyChanged=$otherKeyChanged126 noPrevious=$noPrevious126 " +
                "unavailableKey=$unavailableKey126 knownKeyCompared=$knownKey126 " +
                "recorded=$sourceRecords125 reused=$sourceReused125 " +
                "scope=source_key_classification_only")
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
        clearSourceRecord125()
        if (BackdropLifecycle122.enabled) BackdropLifecycle122.sourceNodeDetaches++
        backdrop.layerCoordinates = null
    }
}
