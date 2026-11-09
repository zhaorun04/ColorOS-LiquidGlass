package top.liquidglass.vector

import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Offset
import android.util.TypedValue
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.blur
import com.kyant.shapes.RoundedRectangle
import com.kyant.backdrop.highlight.Highlight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.roundToInt
import android.os.SystemClock
import android.app.Activity
import android.content.ContextWrapper
import android.view.MotionEvent
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.atomic.AtomicBoolean
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** V269.1: isolated Compose composition/host-draw/disposal verification; no Launcher glass replacement. */
object KyantLauncherLens270 {
    private val reboundLayerGuard28818 = AtomicBoolean(false)
    // V288.90: read-only correlation. These samples describe UI/Compose draw
    // callbacks, NOT GPU presentation or SurfaceFlinger frame timelines.
    private data class ReboundSample28890(
        val serial: Long, val atNs: Long, val pos: Offset,
        val releaseSeq: Long
    )
    private data class BridgeSample28890(
        val gen: Long, val atNs: Long, val pos: Offset,
        val referenceIdentity: Int
    )
    // V288.91: an immutable bitmap and its source coordinates travel together.
    // The producer never repaints a published bitmap; stale generations may be
    // retained briefly by the Compose renderer without pixel/position tearing.
    private data class BridgeFrame28891(
        val bitmap: Bitmap, val position: Offset, val generation: Long,
        val sampledAtNs: Long, val referenceIdentity: Int,
        val downSerial28894: Long, val releaseSerial28894: Long
    )
    // V288.101: immutable bitmap AND sampled position are published as one object.
    // A/B is explicitly opt-in, with age and gesture gates on the consumer.
    // It cannot prove the SurfaceFlinger presentation frame.
    private data class VerifiedBridgeFrame288101(
        val bitmap: Bitmap, val pos: Offset, val gen: Long,
        val atNs: Long, val referenceId: Int,
        val down: Long, val release: Long, val phase: String
    )
    // V288.102: lightweight, non-observable trace snapshots. These are CALLBACK
    // timestamps, not GPU presentation fences or actual measured display FPS.
    private data class TimingPoint288102(
        val atNs: Long, val gen: Long, val pos: Offset,
        val down: Long, val release: Long, val durationNs: Long,
        val workspaceScrollX: Int?
    )
    private class TimingTrace288102 {
        val bridge = java.util.concurrent.atomic.AtomicReference<TimingPoint288102?>(null)
        val predraw = java.util.concurrent.atomic.AtomicReference<TimingPoint288102?>(null)
    }
    // V288.92: observational metadata only. Not a GPU presentation timestamp and
    // not the OEM wallpaper Surface transform. Coordinates are screen/source pairs.
    private data class BridgeCoordinate28892(
        val generation: Long, val atNs: Long, val position: Offset,
        val referenceIdentity: Int, val referenceWidth: Int, val referenceHeight: Int,
        val screenWidth: Int, val screenHeight: Int, val scaleX: Float, val scaleY: Float,
        val sourceMatrixTx: Float, val sourceMatrixTy: Float, val path: String
    )
    // V288.100: one immutable UI-thread PreDraw checkpoint. Not a Choreographer
    // frame ID or a SurfaceFlinger presentation fence: generation/age comparisons
    // expose which callback changed first without claiming GPU frame equality.
    private data class PredrawCheckpoint288100(
        val serial: Long, val atNs: Long, val pos: Offset,
        val down: Long, val release: Long, val bridgeGen: Long,
        val wsScrollX: Int?, val wsX: Float?, val wsTx: Float?
    )
    // V288.93: aggregate only diagnostic observations from the same release and
    // 50ms time bucket. This is NOT a verified screen-space or GPU-frame offset.
    private data class HostGap28893(val host: Int, val x: Float, val atNs: Long)
    private val commonGaps28893 = ConcurrentHashMap<String, MutableMap<Int, HostGap28893>>()
    private fun observeCommonGap28893(seq: Long, host: Int, elapsedNs: Long,
                                      x: Float, enabled: Boolean) {
        if (!enabled || !x.isFinite()) return
        val bucket = elapsedNs / 50_000_000L
        val key = "$seq:$bucket"
        val group = commonGaps28893.getOrPut(key) { ConcurrentHashMap<Int, HostGap28893>() }
        group[host] = HostGap28893(host, x, elapsedNs)
        if (group.size == 3) {
            val values = group.values.toList()
            val min = values.minOf { it.x }
            val max = values.maxOf { it.x }
            val timeSpanMs = (values.maxOf { it.atNs } - values.minOf { it.atNs }) / 1_000_000f
            XposedBridge.log("KYANT28893_COMMON seq=$seq bucket=$bucket hosts=${values.map { it.host }} " +
                "minGap=$min maxGap=$max spread=${max - min} spanMs=$timeSpanMs " +
                "sameGap=${max-min <= 1.5f}")
        }
        // Limit retained aggregation to current and prior release time buckets.
        if (commonGaps28893.size > 80) {
            commonGaps28893.keys.removeAll { it != key && !it.startsWith("$seq:") }
        }
    }
    // V288.21: isolated trace of release BEFORE and AFTER actual OEM touch handling.
    // The rendering pipeline remains identical to V288.19 mode 2.
    private const val RELEASE_TRACE_28821_KEY = "liquidglass_kyant_release_trace_28821"
    private const val FRAME_SYNC_AB_28822_KEY = "liquidglass_kyant_frame_sync_ab_28822"
    private val releaseSeq28820 = AtomicLong(0L)
    private val releaseNs28820 = AtomicLong(0L)
    @Volatile private var releaseDx28820 = 0f
    @Volatile private var releaseAction28820 = "NONE"
    @Volatile private var touchDownX28820 = Float.NaN
    @Volatile private var touchDownY28820 = Float.NaN
    @Volatile private var lastTouchEventKey28820 = ""
    @Volatile private var lastTouchDownKey28821 = ""
    @Volatile private var gestureDownSerial28893 = 0L
    // V288.94: fail closed when a new gesture begins. Only mode 1 reads this.
    @Volatile private var touchActive28894 = false
    // V288.96 diagnostic clock: monotonic time of latest ACTION_DOWN.
    @Volatile private var touchDownNs28896 = 0L
    // The down epoch associated with the latest accepted release, not the raw release counter.
    @Volatile private var releaseDownSerial28899 = -1L
    @Volatile private var releaseBeforeVia28821 = ""
    @Volatile private var releaseAfterKey28821 = ""
    @Volatile private var activeTouchRoot28820: WeakReference<View>? = null
    @Volatile private var activeTouchWorkspace28820: WeakReference<View>? = null
    private val hookedMethods28820 = ConcurrentHashMap.newKeySet<Method>()
    private class ReleaseHostProbe28821(
        val weakAnchor: WeakReference<View>,
        val snapshot: () -> String
    )
    private val liveHostProbes28821 = ConcurrentHashMap<Int, ReleaseHostProbe28821>()
    @Volatile private var selectedHostIds28821: Set<Int> = emptySet()
    private fun selectVisibleHosts28821(): Set<Int> {
        // Re-select on EVERY release; never freeze the first three attached hosts.
        val all = liveHostProbes28821.entries.mapNotNull { (id, probe) ->
            val anchor = probe.weakAnchor.get() ?: return@mapNotNull null
            try {
                if (!anchor.isAttachedToWindow || !anchor.isShown) return@mapNotNull null
                val r = Rect()
                if (!anchor.getGlobalVisibleRect(r) || r.isEmpty) return@mapNotNull null
                Pair(id, r.width().toLong() * r.height().toLong())
            } catch (_: Throwable) { null }
        }
        return all.sortedByDescending { it.second }.take(3).map { it.first }.toSet()
    }
    private fun releaseSnapshot28821(stage: String, serial: Long, via: String) {
        val workspace = activeTouchWorkspace28820?.get()
        XposedBridge.log("KYANT28821_RELEASE_$stage seq=$serial via=$via " +
            "uptimeNs=${SystemClock.elapsedRealtimeNanos()} dx=$releaseDx28820 " +
            "workspaceX=${workspace?.x} workspaceTx=${workspace?.translationX} " +
            "workspaceScrollX=${workspace?.scrollX} workspaceLayer=${workspace?.layerType} " +
            "selected=${selectedHostIds28821.joinToString()}")
        for (id in selectedHostIds28821) {
            val probe = liveHostProbes28821[id] ?: continue
            val state = try { probe.snapshot() } catch (t: Throwable) { "probeError=${t.javaClass.simpleName}" }
            XposedBridge.log("KYANT28821_RELEASE_HOST seq=$serial phase=$stage host=$id $state")
        }
    }
    private fun observeTouchEvent28821(event: MotionEvent, method: String, phase: String) {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL) return
        val eventKey = "${event.downTime}:${event.eventTime}:$action"
        if (action == MotionEvent.ACTION_DOWN) {
            if (phase == "BEFORE" && eventKey != lastTouchDownKey28821) {
                lastTouchDownKey28821 = eventKey
                gestureDownSerial28893++
                touchActive28894 = true
                touchDownNs28896 = SystemClock.elapsedRealtimeNanos()
                XposedBridge.log("KYANT28893_TOUCH_DOWN downSerial=$gestureDownSerial28893 " +
                    "priorRelease=${releaseSeq28820.get()} eventMs=${event.eventTime} " +
                    "rawX=${event.rawX} rawY=${event.rawY} via=$method")
                touchDownX28820 = event.rawX
                touchDownY28820 = event.rawY
            }
            return
        }
        // A short/cancelled gesture also ends the active touch epoch.
        if (phase == "BEFORE") touchActive28894 = false
        val dx = if (touchDownX28820.isFinite()) event.rawX - touchDownX28820 else Float.NaN
        val dy = if (touchDownY28820.isFinite()) event.rawY - touchDownY28820 else Float.NaN
        if (!dx.isFinite() || kotlin.math.abs(dx) < 12f) return
        if (phase == "BEFORE") {
            if (eventKey == lastTouchEventKey28820) return
            lastTouchEventKey28820 = eventKey
            releaseBeforeVia28821 = method
            releaseDx28820 = dx
            releaseAction28820 = if (action == MotionEvent.ACTION_UP) "UP" else "CANCEL"
            selectedHostIds28821 = selectVisibleHosts28821()
            releaseNs28820.set(SystemClock.elapsedRealtimeNanos())
            releaseDownSerial28899 = gestureDownSerial28893
            val serial = releaseSeq28820.incrementAndGet()
            XposedBridge.log("KYANT28893_TOUCH_END seq=$serial downSerial=$gestureDownSerial28893 " +
                "action=$releaseAction28820 dx=$dx dy=$dy eventMs=${event.eventTime} via=$method")
            XposedBridge.log("KYANT28821_TOUCH_RELEASE seq=$serial action=$releaseAction28820 " +
                "dx=$dx dy=$dy rawX=${event.rawX} rawY=${event.rawY} eventMs=${event.eventTime} via=$method")
            releaseSnapshot28821("BEFORE", serial, method)
        } else if (eventKey == lastTouchEventKey28820 &&
            eventKey != releaseAfterKey28821 && method == releaseBeforeVia28821) {
            // The matching after callback belongs to the SAME outermost method.
            // Nested workspace hooks must not consume this event's AFTER marker.
            releaseAfterKey28821 = eventKey
            releaseSnapshot28821("AFTER", releaseSeq28820.get(), method)
        }
    }
    private fun installTouchHooks28820(anchor: View) {
        val root = anchor.rootView
        activeTouchRoot28820 = WeakReference(root)
        var workspace: View? = anchor
        var depth = 0
        while (workspace != null && depth++ < 15 &&
            !workspace.javaClass.name.contains("Workspace", ignoreCase = true)) {
            workspace = workspace.parent as? View
        }
        if (workspace != null) activeTouchWorkspace28820 = WeakReference(workspace)
        var installed = 0
        // Hook the OEM implementation, not android.view.ViewGroup globally.
        if (workspace != null) {
            for (name in arrayOf("dispatchTouchEvent", "onTouchEvent", "onInterceptTouchEvent")) {
                var klass: Class<*>? = workspace.javaClass
                while (klass != null && !klass.name.startsWith("android.")) {
                    for (method in klass.declaredMethods) {
                        if (method.name != name || method.parameterTypes.size != 1 ||
                            method.parameterTypes[0] != MotionEvent::class.java) continue
                        if (hookedMethods28820.add(method)) {
                            try {
                                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        if (param.thisObject !== activeTouchWorkspace28820?.get()) return
                                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                                        try { observeTouchEvent28821(event, name, "BEFORE") } catch (t: Throwable) { XposedBridge.log("KYANT28821_TOUCH_ERROR before:${t.javaClass.simpleName}") }
                                    }
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        if (param.thisObject !== activeTouchWorkspace28820?.get()) return
                                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                                        try { observeTouchEvent28821(event, name, "AFTER") } catch (t: Throwable) { XposedBridge.log("KYANT28821_TOUCH_ERROR after:${t.javaClass.simpleName}") }
                                    }
                                })
                                XposedBridge.log("KYANT28821_HOOK_OK method=${klass.name}.$name")
                            } catch (t: Throwable) {
                                hookedMethods28820.remove(method)
                                XposedBridge.log("KYANT28821_HOOK_FAILED method=${klass.name}.$name error=${t.javaClass.simpleName}")
                            }
                        }
                        installed++
                    }
                    klass = klass.superclass
                }
            }
        }
        // Fallback: Activity-level release event, scoped by the same root window.
        var context = anchor.context
        var activity: Activity? = null
        for (i in 0 until 10) {
            if (context is Activity) { activity = context as Activity; break }
            val wrapped = context as? ContextWrapper ?: break
            context = wrapped.baseContext
        }
        val act = activity
        if (act != null) {
            val method = runCatching {
                act.javaClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)
            }.getOrNull()
            if (method != null && hookedMethods28820.add(method)) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val current = param.thisObject as? Activity ?: return
                            if (current.window?.decorView?.rootView !== activeTouchRoot28820?.get()) return
                            val event = param.args.firstOrNull() as? MotionEvent ?: return
                            try { observeTouchEvent28821(event, "activity", "BEFORE") } catch (t: Throwable) { XposedBridge.log("KYANT28821_TOUCH_ERROR activityBefore:${t.javaClass.simpleName}") }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val current = param.thisObject as? Activity ?: return
                            if (current.window?.decorView?.rootView !== activeTouchRoot28820?.get()) return
                            val event = param.args.firstOrNull() as? MotionEvent ?: return
                            try { observeTouchEvent28821(event, "activity", "AFTER") } catch (t: Throwable) { XposedBridge.log("KYANT28821_TOUCH_ERROR activityAfter:${t.javaClass.simpleName}") }
                        }
                    })
                    XposedBridge.log("KYANT28821_HOOK_OK method=${method.declaringClass.name}.dispatchTouchEvent")
                } catch (t: Throwable) {
                    hookedMethods28820.remove(method)
                    XposedBridge.log("KYANT28821_HOOK_FAILED method=activity error=${t.javaClass.simpleName}")
                }
            }
        }
        XposedBridge.log("KYANT28821_HOOK_STATUS workspace=${workspace?.javaClass?.simpleName ?: "absent"} " +
            "workspaceMethods=$installed activity=${act?.javaClass?.simpleName ?: "absent"}")
    }
    // Opt-in diagnostic: 1 allows only one active optical host, 0 retains all hosts.
    private const val SINGLE_HOST_KEY = "liquidglass_kyant_single_host_282"
    private const val SAMPLE_MODE_KEY = "liquidglass_kyant_sampling_mode_283"
    private const val RENDER_AB_KEY = "liquidglass_kyant_render_ab_2883"
    private const val MOTION_AB_KEY = "liquidglass_kyant_motion_ab_2884"
    private const val LEGACY_SYNC_AB_KEY = "liquidglass_kyant_legacy_sync_ab_2886"
    private const val NATIVE_COMPARISON_KEY = "liquidglass_kyant_native_compare_2887"
    private const val NATIVE_BRIDGE_KEY = "liquidglass_kyant_native_bridge_2888"
    private const val RIGHT_START_TRACE_KEY = "liquidglass_kyant_right_start_trace_28813"
    private const val MATRIX_TRACE_28814_KEY = "liquidglass_kyant_matrix_trace_28814"
    private const val FRAME_TRACE_28815_KEY = "liquidglass_kyant_frame_trace_28815"
    private const val REDRAW_AB_28816_KEY = "liquidglass_kyant_right_redraw_28816"
    private const val LAYER_TRACE_28817_KEY = "liquidglass_kyant_layer_trace_28817"
    private const val REBOUND_AB_28818_KEY = "liquidglass_kyant_rebound_layer_ab_28818"
    // -1 preserves the legacy modes; 0..3 isolate the wallpaper display pipeline.
    private const val PIPELINE_AB_28819_KEY = "liquidglass_kyant_pipeline_ab_28819"
    private const val NATIVE_LAYER_KEY = "liquidglass_kyant_native_layer_28812"
    private const val DISPLAY_ISOLATION_KEY = "liquidglass_kyant_display_isolation_28811"
    private const val BRIDGE_SYNC_KEY = "liquidglass_kyant_bridge_sync_2889"
    private val activeHostCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastSamplingCommitNs = java.util.concurrent.atomic.AtomicLong(0L)

    private const val KEY = "liquidglass_kyant_persistent_probe_2884"

    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        private var started = false
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun start() {
            controller.performAttach()
            controller.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            started = true
        }
        fun stop() {
            if (started) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                started = false
            }
            store.clear()
        }
    }

    @JvmStatic fun run(anchor: View) {
        if (!anchor.isAttachedToWindow || KyantLauncherLensProbe270.isActiveFor273(anchor)) return
        val reference = KyantLauncherFrameBridge266.peekReferenceFrame270()
        if (reference == null) {
            XposedBridge.log("KYANT2885_REFERENCE_NOT_READY one_shot_not_consumed")
            return
        }
        // V288.32: a folder consumes the same live reference publication as Dock.
        // Updating this state changes the pixels without rebuilding the optics host.
        val referenceState28832 = androidx.compose.runtime.mutableStateOf(reference)
        val root = anchor.rootView as? ViewGroup ?: return
        // Place the Compose host as the FIRST child of FolderIcon, before native icon children.
        // This preserves the original draw ordering without a DecorView overlay.
        val folder = anchor as? ViewGroup ?: run {
            XposedBridge.log("KYANT2885_ABORT anchor_not_viewgroup")
            return
        }
        // Persistent experiment is explicitly opt-in; never disarm the switch on attach.
        // V288.88: resolve the engine before installing the FolderIcon host.
        // Native V230 mode is independent of the Kyant Compose UI switch.
        val selectedEngine28888 = KyantLauncherLensProbe270.selectedEngine28888(anchor.context)
        if (selectedEngine28888 < 0) return
        if (selectedEngine28888 == 0) KyantOptics28827.ensure(anchor.context)
        XposedBridge.log("KYANT28888_ENGINE_SELECTED host=${System.identityHashCode(anchor)} " +
            "engine=${when (selectedEngine28888) { 1 -> "V230_NATIVE_AGSL"; 2 -> "NATIVE_REFERENCE"; else -> "KYANT_COMPOSE" }} " +
            "kyantSwitch=${try { Settings.Global.getInt(anchor.context.contentResolver, KEY, 0) } catch (_: Throwable) { -1 }} " +
            "v230UiEnabled=${MaterialConfig230.get().enabled} safeFolder=true")
        // V288.86 P0 containment. Default ON, including after upgrades from
        // 288.77..85 with stale Settings.Global experiments still enabled.
        // A DecorView child is above the OEM icon/Recents hierarchy and MUST NOT
        // be used for the normal lens. Attach the full Kyant host as index 0 of
        // FolderIcon, which inherits the OEM page/Recents visibility and z-order.
        // 0 is a deliberately explicit legacy-debug opt-out, not a UI control.
        val folderContainment28886 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_folder_containment_28886", 1) != 0
        } catch (_: Throwable) { true }
        XposedBridge.log("KYANT28886_CONTAINMENT host=${System.identityHashCode(anchor)} " +
            "enabled=$folderContainment28886 parent=FOLDER_CHILD_INDEX_0 " +
            "rootOverlayAllowed=${!folderContainment28886}")
        // V288.64: opt-in native parent versus Compose source draw lifecycle probe.
        // Counters are in memory. No per-frame logs or state writes from draw.
        // V288.66: opt-in use the native matrix of the actual Compose source
        // View, not the FolderIcon anchor. This changes the sampling origin,
        // never the Shader, bitmap generation, or redraw cadence.
        // V288.67: diagnostics only. Measure the actual wallpaper UV inputs in
        // the Compose source draw and the gaps between visible motion draws.
        // Disabled by default, never changes the shader, reference or invalidation.
        // V288.68: isolate only the optical lens displacement. This does not
        // change source bitmap, sampling origin, blur, highlight or host geometry.
        val zeroLensAb28868 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_zero_lens_ab_28868", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        val continuousMotionProbe28868 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_continuous_motion_probe_28868", 0) == 1
        } catch (_: Throwable) { false }
        if (zeroLensAb28868 == 1 || continuousMotionProbe28868) XposedBridge.log(
            "KYANT28868_HOST host=${System.identityHashCode(anchor)} zeroLens=$zeroLensAb28868 " +
            "motionProbe=$continuousMotionProbe28868")
        // A pair of (last observed moving PreDraw nanoTime, active run start nanoTime).
        // Run starts are reset by stationary or long-gapped PreDraw observations.
        val continuousRun28868 = java.util.concurrent.atomic.AtomicReference(Pair(0L, 0L))
        var lastMovingPredrawNs28868 = 0L
        var currentRunStartNs28868 = 0L
        var continuousBreaks28868 = 0L
        var maxMovingPredrawGapNs28868 = 0L
        var continuousWindowNs28868 = SystemClock.elapsedRealtimeNanos()
        val uvProbe28867 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_uv_motion_probe_28867", 0) == 1
        } catch (_: Throwable) { false }
        val sourceMatrixAb28866 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_source_matrix_ab_28866", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        if (uvProbe28867) XposedBridge.log(
            "KYANT28867_UV_HOST host=${System.identityHashCode(anchor)} enabled=1 " +
            "matrixMode=$sourceMatrixAb28866")
        val sourceMatrixStats28866 = FloatArray(3) // max dx, max dy, sum absolute delta
        val sourceMatrixCounts28866 = LongArray(3) // samples, invalid, over 1px
        var sourceMatrixWindowNs28866 = SystemClock.elapsedRealtimeNanos()
        XposedBridge.log("KYANT28866_HOST host=${System.identityHashCode(anchor)} " +
            "mode=$sourceMatrixAb28866 origin=${if (sourceMatrixAb28866 == 1) "COMPOSE_VIEW_MATRIX" else "ANCHOR_MATRIX"}")
        val lifecycleProbe28864 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_lifecycle_probe_28864", 0) == 1
        } catch (_: Throwable) { false }
        // V288.65: experimental redraw request on genuine native motion only.
        // Default OFF. No bitmap decoding, publication or shader changes.
        val nativeDrawSync28865 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_native_draw_sync_ab_28865", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        val drawProbe28865 = lifecycleProbe28864 || nativeDrawSync28865 == 1
        val treeDrawSerial28865 = AtomicLong(0L)
        var treeDrawListener28865: android.view.ViewTreeObserver.OnDrawListener? = null
        var drawRequestWindow28865 = SystemClock.elapsedRealtimeNanos()
        var drawRequests28865 = 0L
        var drawSourceAtStart28865 = 0L
        var drawTreeAtStart28865 = 0L
        var drawMaxGap28865 = 0f
        var drawLastRequestedX28865 = Float.NaN
        var drawLastRequestedY28865 = Float.NaN
        val sourceDrawSerial28864 = AtomicLong(0L)
        val parentDrawSerial28864 = AtomicLong(0L)
        val parentWithoutSource28864 = AtomicLong(0L)
        val sourceDrawPosition28864 = java.util.concurrent.atomic.AtomicReference<Offset?>(null)
        var probeWindow28864 = SystemClock.elapsedRealtimeNanos()
        var probePreDraw28864 = 0L
        var probeMoving28864 = 0L
        var probeMovingWithoutSource28864 = 0L
        var probeMaxSourceGap28864 = 0f
        var probeLastX28864 = Float.NaN
        var probeLastY28864 = Float.NaN
        var probeLastSourceSerial28864 = -1L
        var probeLastParentSerial28864 = 0L
        var probeLastSourceTotal28864 = 0L
        var probeLastNoSource28864 = 0L
        if (drawProbe28865) XposedBridge.log(
            "KYANT28865_HOST host=${System.identityHashCode(anchor)} mode=$nativeDrawSync28865 " +
            "lifecycle=$lifecycleProbe28864")
        val pipelineMode28819 = try {
            Settings.Global.getInt(anchor.context.contentResolver, PIPELINE_AB_28819_KEY, -1).coerceIn(-1, 3)
        } catch (_: Throwable) { -1 }
        if (lifecycleProbe28864) XposedBridge.log(
            "KYANT28864_LIFECYCLE_HOST host=${System.identityHashCode(anchor)} enabled=true pipeline=$pipelineMode28819")
        // V288.69 renderer ownership A/B. Read at host creation, never switched
        // during draw; the source bitmap and optics are not reconfigured globally.
        // 0=unchanged Compose source+Kyant lens;
        // 1=native View produces a wallpaper bitmap, Compose consumes it with lens;
        // 2=actual native View displays wallpaper above Compose (no Kyant lens).
        // Mode 2 is a DIAGNOSTIC control, NOT a retained optical design.
        // V288.70: native-carrier settle-phase A/B; 0 preserves v288.69.1,
        // 1 samples from the actual drawing View's current floating matrix.
        // This reads a position, it does not alter reference pixels or Kyant lens.
        // V288.71: observation only. Both native carriers report settle-stage
        // source continuity, pixel-grid transitions and reference generation.
        // No bitmap decoding, readback, cropping changes, or extra invalidation.
        val settleProbe28871 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_settle_probe_28871", 0) == 1
        } catch (_: Throwable) { false }
        val nativeSettleAb28870 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_native_settle_ab_28870", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        // V288.89: fusion experiment is opt-in and never changes engine ownership.
        // 1: native V230-coordinate BitmapShader produces Kyant's backdrop pixels;
        // 2: native V230 AGSL uses the existing smooth edge-refraction profile.
        val requestedFusion28889 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_fusion_ab_28889", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        val fusionMode28889 = when (requestedFusion28889) {
            1 -> if (selectedEngine28888 == 0) 1 else 0
            2 -> if (selectedEngine28888 == 1) 2 else 0
            else -> 0
        }
        val fusionTrace28889 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_fusion_trace_28889", 0) == 1
        } catch (_: Throwable) { false }
        val nativeCarrierAb28869 = if (fusionMode28889 == 1) 1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_native_carrier_ab_28869", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        if (requestedFusion28889 != 0) XposedBridge.log(
            "KYANT28889_FUSION_HOST host=${System.identityHashCode(anchor)} " +
            "requested=$requestedFusion28889 effective=$fusionMode28889 " +
            "selectedEngine=$selectedEngine28888 carrier=$nativeCarrierAb28869 " +
            "safeFolder=$folderContainment28886")
        // V288.87: 0=unchanged Kyant, 1=actual UnifiedGlass230 AGSL,
        // 2=same native wallpaper sampling without optical Shader.
        // Deliberately independent of historical root experiments, always
        // mounted as FolderIcon's index-0 child with V288.86 containment.
        // This value MUST match the entry gate and the live PreDraw gate.
        // Do not re-select another engine after the host has been created.
        val v230NativeMode28887 = if (folderContainment28886) selectedEngine28888 else 0
        val v230NativeTrace28887 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_v230_trace_28887", 0) == 1
        } catch (_: Throwable) { false }
        if (v230NativeMode28887 != 0) {
            val m230 = MaterialConfig230.get()
            XposedBridge.log("KYANT28887_NATIVE_HOST host=${System.identityHashCode(anchor)} " +
                "mode=$v230NativeMode28887 folderContained=$folderContainment28886 " +
                "opticalStrength=${m230.strength} thickness=${m230.thickness} " +
                "environment=${m230.environment} materialUiEnabled=${m230.enabled} " +
                "source=FRAME_BRIDGE_266")
        }
        // V288.74: 0 = baseline, 1 = small-delta bridge pixel-space correction.
        // Only native carrier 1 consumes this switch; native carrier 2 stays unchanged.
        val distanceAb28874 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_distance_ab_28874", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        val distanceTrace28874 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_distance_trace_28874", 0) == 1
        } catch (_: Throwable) { false }
        // V288.75: 0 = legacy mutable single bridge, 1 = two-buffer handoff.
        // Applies ONLY to native carrier 1 and does not alter direct native mode 2.
        val bridgeBufferAb28875 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_bridge_buffer_ab_28875", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        val distanceTrace28875 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_page_distance_trace_28875", 0) == 1
        } catch (_: Throwable) { false }
        val bufferExperiment28875 = nativeCarrierAb28869 == 1 && bridgeBufferAb28875 == 1
        // V288.76: 0=original translation-only bitmap sampling;
        // 1=use the inverse FULL affine global View transform to map wallpaper
        // into local pixels; 2=move the direct-native control into the root
        // overlay, outside Workspace motion (requires native carrier 2).
        // The overlay is a diagnostic and intentionally covers the glass/icon.
        val transformAb28876 = if (folderContainment28886) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_transform_ab_28876", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        val transformTrace28876 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_transform_trace_28876", 0) == 1
        } catch (_: Throwable) { false }
        // V288.77.1: do NOT attach full Kyant to ViewGroupOverlay: the host
        // traversed/drew, but the Compose/Backdrop glass was not visible on-device.
        // Attach as an ordinary direct child of Launcher's root ViewGroup instead.
        // This retains a regular View hierarchy/window traversal while avoiding
        // Workspace's inherited page transforms. Diagnostic only: top-level child
        // can obscure icons, so disable for normal use.
        val fullOverlayAb28877 = if (folderContainment28886) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_full_overlay_ab_28877", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        val fullOverlayTrace28877 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_full_overlay_trace_28877", 0) == 1
        } catch (_: Throwable) { false }
        // V288.78: SAME root FrameLayout, only the displayed renderer changes.
        // 0=legacy V288.77.1 behaviour; 1=root native-only; 2=root full Kyant.
        val rootCompareAb28878 = if (folderContainment28886) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_root_compare_ab_28878", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        val rootIconPassthrough28878 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_root_icons_28878", 1) == 1
        } catch (_: Throwable) { true }
        val rootCompareTrace28878 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_root_compare_trace_28878", 0) == 1
        } catch (_: Throwable) { false }
        // V288.79: independent 5-stage *actual* rendering pipeline A/B.
        // -1=V288.78 unchanged, 0=native Canvas, 1=Compose source only,
        // 2=Compose source with layerBackdrop capture modifier but no consumer,
        // 3=Backdrop consumer blur/highlight without lens(), 4=full optical lens.
        // Stages 1-4 share the exact same root FrameLayout and bridge Bitmap.
        val rootPipeline28879 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_root_pipeline_28879", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val rootPipelineTrace28879 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_root_pipeline_trace_28879", 0) == 1
        } catch (_: Throwable) { false }
        // V288.80: isolate the FIRST unstable boundary: native Canvas versus
        // Compose Bitmap. All five modes use the same root child FrameLayout.
        // -1 leaves the complete V288.79 behavior untouched.
        val bitmapPipeline28880 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_bitmap_pipeline_28880", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val bitmapTrace28880 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_bitmap_trace_28880", 0) == 1
        } catch (_: Throwable) { false }
        // V288.81: isolate ComposeView hosting from Compose draw nodes and
        // graphicsLayer composition. All modes use one root FrameLayout.
        // -1 preserves the complete V288.80 implementation.
        val nodePipeline28881 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_node_pipeline_28881", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val nodeTrace28881 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_node_trace_28881", 0) == 1
        } catch (_: Throwable) { false }
        // V288.82: same AndroidView painter, four position sources. The stable
        // root-native control is stage 0, -1 keeps the previous production path.
        val positionPipeline28882 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_position_pipeline_28882", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val positionTrace28882 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_position_trace_28882", 0) == 1
        } catch (_: Throwable) { false }
        // V288.83: separate host hierarchy experiment, not another position A/B.
        // 0 root-native, 1 Compose/AndroidView, 2 native sibling with Compose
        // hidden, 3 native sibling with transparent Compose node, 4 native
        // sibling beneath the genuine Kyant optical pipeline (integration probe).
        val siblingPipeline28883 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_sibling_pipeline_28883", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val siblingTrace28883 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_sibling_trace_28883", 0) == 1
        } catch (_: Throwable) { false }
        // V288.84: test only root-child drawing order and transient root-layer
        // visibility. These settings override older A/B tests when enabled.
        // 0 stable native-over-Compose; 1 native-under / Compose invisible;
        // 2 native-over / Compose empty visible; 3 native-under / Compose GONE;
        // 4 native-under / complete Kyant. No new shader or bitmap paths.
        val orderPipeline28884 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_order_pipeline_28884", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val orderTrace28884 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_order_trace_28884", 0) == 1
        } catch (_: Throwable) { false }
        // V288.85: fixed-Z experiment. All five stages create the exact same
        // root native-under-Compose hierarchy; only Compose alpha, visibility,
        // and optical composition change. Older probes remain disabled by 85.
        val statePipeline28885 = if (folderContainment28886) -1 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_state_pipeline_28885", -1).coerceIn(-1, 4)
        } catch (_: Throwable) { -1 }
        val stateTrace28885 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_state_trace_28885", 0) == 1
        } catch (_: Throwable) { false }
        val stateActive28885 = statePipeline28885 >= 0 &&
            nativeCarrierAb28869 == 1 && transformAb28876 == 0
        val siblingNative28883 = siblingPipeline28883 in 2..4 &&
            nativeCarrierAb28869 == 1 && transformAb28876 == 0
        val orderNative28884 = (stateActive28885 || orderPipeline28884 in 1..4) &&
            nativeCarrierAb28869 == 1 && transformAb28876 == 0
        val rootDirect28878 = (if (stateActive28885) false
            else if (orderPipeline28884 >= 0) orderPipeline28884 == 0
            else if (siblingPipeline28883 >= 0) siblingPipeline28883 == 0
            else if (positionPipeline28882 >= 0) positionPipeline28882 == 0
            else if (nodePipeline28881 >= 0) nodePipeline28881 == 0
            else if (bitmapPipeline28880 >= 0) bitmapPipeline28880 in 0..1
            else if (rootPipeline28879 >= 0) rootPipeline28879 == 0
            else rootCompareAb28878 == 1) && nativeCarrierAb28869 == 1 &&
            transformAb28876 == 0
        val rootFullKyant28878 = (if (stateActive28885) true
            else if (orderPipeline28884 >= 0) orderPipeline28884 in 1..4
            else if (siblingPipeline28883 >= 0) siblingPipeline28883 in 1..4
            else if (positionPipeline28882 >= 0) positionPipeline28882 in 1..4
            else if (nodePipeline28881 >= 0) nodePipeline28881 in 1..4
            else if (bitmapPipeline28880 >= 0) bitmapPipeline28880 in 2..4
            else if (rootPipeline28879 >= 0) rootPipeline28879 in 1..4
            else rootCompareAb28878 == 2) && nativeCarrierAb28869 == 1 &&
            transformAb28876 == 0
        val fullOverlayRequested28877 = !folderContainment28886 &&
            (fullOverlayAb28877 == 1 || rootDirect28878 ||
            rootFullKyant28878) && nativeCarrierAb28869 == 1 && transformAb28876 == 0
        val fullOverlayParent28877 = if (fullOverlayRequested28877) root as? ViewGroup else null
        val fullOverlayActive28877 = fullOverlayRequested28877 && fullOverlayParent28877 != null
        if (fullOverlayTrace28877 || fullOverlayAb28877 != 0) XposedBridge.log(
            "KYANT288771_HOST host=${System.identityHashCode(anchor)} " +
            "enabled=$fullOverlayActive28877 carrier=$nativeCarrierAb28869 " +
            "transform76=$transformAb28876 lens=COMPOSE_NATIVE_BITMAP mount=ROOT_CHILD")
        val affineSampling28876 = transformAb28876 == 1 && nativeCarrierAb28869 in 1..2
        val overlayRequested28876 = !folderContainment28886 &&
            transformAb28876 == 2 && nativeCarrierAb28869 == 2
        val overlayParent28876 = if (overlayRequested28876) root as? ViewGroup else null
        val overlayActive28876 = overlayRequested28876 && overlayParent28876 != null
        if (transformTrace28876 || transformAb28876 != 0) XposedBridge.log(
            "KYANT28876_HOST host=${System.identityHashCode(anchor)} " +
            "carrier=$nativeCarrierAb28869 mode=$transformAb28876 " +
            "affine=$affineSampling28876 overlay=$overlayActive28876")

        val frameSync28822 = if (pipelineMode28819 == 2) try {
            Settings.Global.getInt(anchor.context.contentResolver, FRAME_SYNC_AB_28822_KEY, 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 } else 0
        if (pipelineMode28819 == 2) XposedBridge.log("KYANT28822_FRAME_SYNC host=${System.identityHashCode(anchor)} mode=$frameSync28822 pipeline=$pipelineMode28819")
        val releaseTrace28820 = try {
            Settings.Global.getInt(anchor.context.contentResolver, RELEASE_TRACE_28821_KEY, 0) == 1
        } catch (_: Throwable) { false }
        if (releaseTrace28820) {
            installTouchHooks28820(anchor)
            XposedBridge.log("KYANT28821_TRACE_ARMED host=${System.identityHashCode(anchor)} pipeline=$pipelineMode28819")
        }
        val samplingMode = if (pipelineMode28819 >= 0) 1 else try {
            Settings.Global.getInt(anchor.context.contentResolver, SAMPLE_MODE_KEY, 1).coerceIn(0, 1)
        } catch (_: Throwable) { 1 }
        // 0: original optics + original bitmap sampling; 1: cropped bitmap source,
        // 2: original bitmap sampling with isolated offscreen compositing layer.
        // Read once on Host creation; switching modes requires Launcher restart.
        val renderAbMode = if (pipelineMode28819 >= 0) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver, RENDER_AB_KEY, 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        // V288.62: isolate the Kyant wallpaper source transform and Compose layer.
        // This opt-in A/B only selects EXISTING render paths and leaves lens optics intact.
        // 0 = existing configuration; 1 = rectangular source-crop sampling;
        // 2 = isolated offscreen optical layer; 3 = both.
        val layerSampleAb28862 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_layer_sample_ab_28862", 0).coerceIn(0, 3)
        } catch (_: Throwable) { 0 }
        val layerSampleEffective28862 = if (pipelineMode28819 == -1) layerSampleAb28862 else 0
        val effectiveSamplingMode28862 = if (layerSampleEffective28862 == 1 || layerSampleEffective28862 == 3) 0 else samplingMode
        val effectiveRenderMode28862 = if (layerSampleEffective28862 == 2 || layerSampleEffective28862 == 3) 2 else renderAbMode
        // Motion diagnostic is independent of the earlier render A/B switch.
        // 0 = baseline, 1 = freeze wallpaper source at its first draw,
        // 2 = live wallpaper source without lens refraction.
        val motionAbMode = if (pipelineMode28819 >= 0) 2 else try {
            Settings.Global.getInt(anchor.context.contentResolver, MOTION_AB_KEY, 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        val bridgeSyncMode = if (pipelineMode28819 >= 0) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver, BRIDGE_SYNC_KEY, 1).coerceIn(0, 2)
        } catch (_: Throwable) { 1 }
        XposedBridge.log("KYANT28810_BRIDGE_SYNC mode=$bridgeSyncMode")
        // 0=legacy switches, 1=native Canvas full overlay, 2=Compose Bitmap without
        // Kyant optics, 3=Compose Bitmap with full Kyant optics.
        val displayIsolationMode = if (pipelineMode28819 >= 0) {
            if (pipelineMode28819 == 0) 1 else 2
        } else try {
            Settings.Global.getInt(anchor.context.contentResolver, DISPLAY_ISOLATION_KEY, 3).coerceIn(0, 3)
        } catch (_: Throwable) { 3 }
        XposedBridge.log("KYANT28811_DISPLAY_ISOLATION mode=$displayIsolationMode")
        // 0=legacy isolation, 1=native above Compose, 2=native behind Compose
        // with 0-alpha source experiment, 3=native behind Compose with 2% source.
        val nativeLayerMode = if (pipelineMode28819 >= 0) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver, NATIVE_LAYER_KEY, 0).coerceIn(0, 3)
        } catch (_: Throwable) { 0 }
        XposedBridge.log("KYANT28812_NATIVE_LAYER mode=$nativeLayerMode isolation=$displayIsolationMode")
        val rightStartTraceEnabled = if (pipelineMode28819 >= 0) false else try {
            Settings.Global.getInt(anchor.context.contentResolver, RIGHT_START_TRACE_KEY, 1) == 1
        } catch (_: Throwable) { true }
        XposedBridge.log("KYANT28813_TRACE_ENABLED enabled=$rightStartTraceEnabled host=${System.identityHashCode(anchor)} nativeLayer=$nativeLayerMode")
        var onsetPrevX = Float.NaN
        var onsetPrevY = Float.NaN
        var onsetPrevHostX = Float.NaN
        var onsetPrevScrollX = Int.MIN_VALUE
        var onsetPrevNs = 0L
        var onsetQuietFrames = 0
        var onsetDirection = 0
        var onsetFramesRemaining = 0
        var onsetSerial = 0
        var onsetLogCount = 0
        var onsetNativeDrawCount = 0
        val matrixTrace28814 = if (pipelineMode28819 >= 0) false else try {
            Settings.Global.getInt(anchor.context.contentResolver, MATRIX_TRACE_28814_KEY, 1) == 1
        } catch (_: Throwable) { true }
        XposedBridge.log("KYANT28814_MATRIX_TRACE enabled=$matrixTrace28814 host=${System.identityHashCode(anchor)}")
        var trace28814PrevSample = Float.NaN
        var trace28814PrevHost = Float.NaN
        var trace28814PrevAnchor = Float.NaN
        var trace28814PredrawSerial = 0L
        var trace28814DrawSerial = 0L
        var trace28814DrawRemaining = 0
        var trace28814LastDirection = 0
        var trace28814Events = 0
        val trace28814AnchorMatrix = Matrix()
        val trace28814HostMatrix = Matrix()
        val trace28814AnchorPoint = FloatArray(2)
        val trace28814HostPoint = FloatArray(2)
        val trace28814RootScreen = IntArray(2)
        val trace28814RootWindow = IntArray(2)
        // V288.15: diagnostics only. A UI-thread draw callback does not prove GPU presentation.
        val frameTrace28815 = if (pipelineMode28819 >= 0) false else try {
            Settings.Global.getInt(anchor.context.contentResolver, FRAME_TRACE_28815_KEY, 0) == 1
        } catch (_: Throwable) { false }
        val redrawMode28816 = if (pipelineMode28819 >= 0) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver, REDRAW_AB_28816_KEY, 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        var redraw28816Requests = 0
        var redraw28816Draws = 0
        var redraw28816Pending = false
        var redraw28816PendingNs = 0L
        var redraw28816PendingX = Float.NaN
        val layerTrace28817 = if (pipelineMode28819 >= 0) false else try { Settings.Global.getInt(anchor.context.contentResolver, LAYER_TRACE_28817_KEY, 0) == 1 } catch (_: Throwable) { false }
        var layerTrace28817Count = 0
        var layerTrace28817Remaining = 0
        var layerTrace28817Direction = 0
        val reboundMode28818 = if (pipelineMode28819 >= 0) 0 else try { Settings.Global.getInt(anchor.context.contentResolver, REBOUND_AB_28818_KEY, 0).coerceIn(0, 1) } catch (_: Throwable) { 0 }
        var rebound28818LastX = Float.NaN
        var rebound28818RightDrag = false
        var rebound28818LastPositiveNs = 0L
        var rebound28818Count = 0
        var rebound28818Target: View? = null
        var rebound28818OldLayer = View.LAYER_TYPE_NONE
        var rebound28818Restore: Runnable? = null
        val trace28815Host = System.identityHashCode(anchor)
        XposedBridge.log("KYANT28818_AB host=$trace28815Host mode=$reboundMode28818")
        if (layerTrace28817) XposedBridge.log("KYANT28817_TRACE_ENABLED host=$trace28815Host nativeLayer=$nativeLayerMode")
        XposedBridge.log("KYANT28816_REDRAW_AB host=$trace28815Host mode=$redrawMode28816 nativeLayer=$nativeLayerMode")

        var trace28815PreSerial = 0L
        var trace28815DrawSerial = 0L
        var trace28815LastPreNs = 0L
        var trace28815LastDrawNs = 0L
        var trace28815LastPreX = Float.NaN
        var trace28815LastDrawX = Float.NaN
        var trace28815LastPreSerialAtDraw = 0L
        var trace28815Quiet = 0
        var trace28815Remaining = 0
        var trace28815Direction = 0
        var trace28815Events = 0
        if (frameTrace28815) XposedBridge.log(
            "KYANT28815_TRACE_ENABLED host=$trace28815Host native=${nativeLayerMode in 1..3} sourceId=${System.identityHashCode(reference)}")
        val onsetSourceId = System.identityHashCode(reference)
        val nativeBridgeMode = if (v230NativeMode28887 != 0) 0
            else if (nativeCarrierAb28869 != 0) {
            // Both A/B paths sample via the native Canvas; mode 2 also draws
            // directly to the native View instead of waiting for Compose.
            if (nativeCarrierAb28869 == 1) 1 else 0
        } else if (pipelineMode28819 >= 0) {
            if (pipelineMode28819 <= 1) 0 else 1
        } else if (nativeLayerMode in 1..3) 0 else if (displayIsolationMode in 1..3) {
            if (displayIsolationMode == 1) 0 else 1
        } else try {
            Settings.Global.getInt(anchor.context.contentResolver, NATIVE_BRIDGE_KEY, 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        XposedBridge.log("KYANT2888_NATIVE_BRIDGE mode=$nativeBridgeMode (0=baseline 1=native_bitmap_bridge)")
        if (settleProbe28871) XposedBridge.log(
            "KYANT28871_HOST host=${System.identityHashCode(anchor)} carrier=$nativeCarrierAb28869 " +
            "settleMode=$nativeSettleAb28870 enabled=true")
        XposedBridge.log("KYANT28870_SETTLE_HOST host=${System.identityHashCode(anchor)} " +
            "carrier=$nativeCarrierAb28869 settleMode=$nativeSettleAb28870")
        XposedBridge.log("KYANT28869_RENDER_PATH host=${System.identityHashCode(anchor)} " +
            "ab=$nativeCarrierAb28869 producer=${when(nativeCarrierAb28869) { 1 -> "NATIVE_BITMAP_BRIDGE"; 2 -> "NATIVE_VIEW_DIRECT"; else -> "COMPOSE_DIRECT" }} " +
            "consumer=${if(nativeCarrierAb28869 == 2) "NATIVE_VIEW_ONLY_NO_LENS" else "COMPOSE_KYANT_LENS"} " +
            "pipeline=$pipelineMode28819 displayIsolation=$displayIsolationMode nativeLayer=$nativeLayerMode " +
            "nativeBridge=$nativeBridgeMode")
        val nativeCompareMode = if (pipelineMode28819 >= 0) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver, NATIVE_COMPARISON_KEY, 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        XposedBridge.log("KYANT2887_NATIVE_COMPARE mode=$nativeCompareMode (0=full_kyant 1=left_native_right_kyant)")
        val legacySyncAbMode = if (pipelineMode28819 >= 0) 1 else try {
            Settings.Global.getInt(anchor.context.contentResolver, LEGACY_SYNC_AB_KEY, 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        if (pipelineMode28819 >= 0) XposedBridge.log(
            "KYANT28819_PIPELINE host=${System.identityHashCode(anchor)} mode=$pipelineMode28819 " +
            "native=$nativeLayerMode isolation=$displayIsolationMode bridge=$nativeBridgeMode " +
            "optics=false bridgeSync=$bridgeSyncMode sourceId=${System.identityHashCode(reference)}")
        // V288.61: opt-in diagnostics; never alter geometry or shader output.
        // V288.63 A/B: 0 = unchanged baseline, 1 = commit native matrix on
        // every visibly changed PreDraw, independent of OEM moved/geometry gates.
        // This deliberately changes only invalidation scheduling, not the shader,
        // optics, reference bitmap, coordinate transform, or wallpaper publication.
        val nativeFrameCommit28863 = if (pipelineMode28819 >= 0) 0 else try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_native_frame_commit_ab_28863", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        XposedBridge.log("KYANT28863_HOST host=$trace28815Host commitAb=$nativeFrameCommit28863 " +
            "bridge=$nativeBridgeMode pipeline=$pipelineMode28819")
        val motionCompare28861 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_motion_compare_28861", 0) == 1
        } catch (_: Throwable) { false }
        if (motionCompare28861) XposedBridge.log(
            "KYANT28861_HOST_PROBE mode=$legacySyncAbMode host=$trace28815Host " +
            "reference=${reference.width}x${reference.height} " +
            "pipeline=$pipelineMode28819 source=$samplingMode")
        XposedBridge.log("KYANT2886_LEGACY_SYNC_AB mode=$legacySyncAbMode (0=predraw_state 1=draw_time_matrix)")
        XposedBridge.log("KYANT2885_MOTION_AB mode=$motionAbMode (0=baseline 1=freeze_source 2=no_refraction)")
        XposedBridge.log("KYANT2885_RENDER_AB mode=" + renderAbMode +
            " (0=baseline 1=cropped_bitmap 2=offscreen_compositing)")
        XposedBridge.log("KYANT2885_SAMPLE_MODE mode=$samplingMode (0=cropped_source 1=whole_bitmap_transform) referenceIdentity=${System.identityHashCode(reference)}")
        // V288.1: mode 1 was confirmed to freeze wallpaper sampling on device.
        // Always use the verified PreDraw commit path; no Compose state mutation
        // inside FrameLayout.dispatchDraw.
        XposedBridge.log("KYANT2885_FRAME_SYNC disabled_bad_draw_commit=true predraw_only=true")
        val singleHostMode = try {
            Settings.Global.getInt(anchor.context.contentResolver, SINGLE_HOST_KEY, 0) == 1
        } catch (_: Throwable) { false }
        // Controlled A/B test; do not silently change normal multi-folder behavior.
        if (singleHostMode && activeHostCount.get() > 0) {
            XposedBridge.log("KYANT2885_SINGLE_HOST_SKIP active=" + activeHostCount.get())
            return
        }
        XposedBridge.log("KYANT2885_PERSISTENT_ARMED key=" + KEY)
        val startedAt = SystemClock.uptimeMillis()
        val displayWidth = root.width.coerceAtLeast(1)
        val displayHeight = root.height.coerceAtLeast(1)
        val initialBounds = FolderGlassGeometry272.resolve(anchor)
        var lastBounds = android.graphics.Rect(initialBounds)
        val nativeRadius = HookEntry.kyantFolderGlassRadius273(anchor)
        XposedBridge.log("KYANT2885_NATIVE_GEOMETRY rect=$initialBounds radiusPx=$nativeRadius")
        val previewWidth = initialBounds.width().coerceAtLeast(1)
        val previewHeight = initialBounds.height().coerceAtLeast(1)
        val anchorXY = IntArray(2)
        anchor.getLocationOnScreen(anchorXY)
        // Place the diagnostic preview at the upper-left of the Launcher root.
        // The bitmap sample coordinates are aligned to the root's screen position.
        
        val compositionSeen = AtomicBoolean(false)
        val drawSeen = AtomicBoolean(false)
        // Single state write per frame prevents separate X/Y snapshot invalidations.
        val samplePosition = androidx.compose.runtime.mutableStateOf(
            Offset(0f, 0f))
        // Last observed global matrix and its timestamp, captured by PreDraw.
        // Atomic immutable snapshot avoids mismatched X/Y values.
        val predrawMatrixSnapshot = java.util.concurrent.atomic.AtomicReference(
            Pair(Offset(Float.NaN, Float.NaN), 0L))
        val sampleWidth = androidx.compose.runtime.mutableIntStateOf(previewWidth)
        val sampleHeight = androidx.compose.runtime.mutableIntStateOf(previewHeight)
        // Initial sample uses the full transform, not the early integer view location.
        val sampleRadiusPx = androidx.compose.runtime.mutableFloatStateOf(
            nativeRadius.coerceIn(0f, minOf(previewWidth, previewHeight) * 0.49f))
        XposedBridge.log("KYANT2885_SHAPE size=${previewWidth}x${previewHeight} radiusPx=${sampleRadiusPx.floatValue} radiusDp=${sampleRadiusPx.floatValue / anchor.resources.displayMetrics.density} source=legacy")
        var followListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
        var detachListener: View.OnAttachStateChangeListener? = null
        var layoutListener: View.OnLayoutChangeListener? = null
        // Reuse the global cached switch value; no Settings provider IPC per rendered frame.
        // Large square folders expose missed source position updates much more
        // visibly than small or strip-shaped previews.  Keep the original
        // shader, but avoid repeated OEM geometry reflection and multiple
        // global-matrix reads inside the same pre-draw frame.
        val largeSquare28829 = previewWidth >= displayWidth * .30f &&
            previewHeight >= previewWidth * .72f && previewHeight <= previewWidth * 1.4f &&
            (try { Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_large_square_opt_28829", 1) != 0 }
             catch (_: Throwable) { true })
        // V288.30: large squares expose pixel stepping at the changing
        // cropped-source Rect boundaries. Keep a stable full reference Bitmap
        // and apply a floating-point destination transform instead (A/B).
        // No extra Bitmap copy, no downsampling, no delayed frame.
        val largeContinuousSource28830 = largeSquare28829 && try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_large_square_source_28830", 1) == 1
        } catch (_: Throwable) { true }
        // V288.62.1: defer effective source selection until the original source
        // switch has been computed; otherwise Kotlin sees an unresolved reference.
        val effectiveLargeContinuous28862 = if (layerSampleEffective28862 == 1 || layerSampleEffective28862 == 3) false else largeContinuousSource28830
        XposedBridge.log("KYANT28862_AB_HOST mode=$layerSampleAb28862 applied=$layerSampleEffective28862 " +
            "sampling=$effectiveSamplingMode28862 continuous=$effectiveLargeContinuous28862 render=$effectiveRenderMode28862 " +
            "originalSampling=$samplingMode originalRender=$renderAbMode pipeline=$pipelineMode28819 " +
            "host=${System.identityHashCode(anchor)}")
        if (largeSquare28829) XposedBridge.log(
            "KYANT28830_LARGE_SOURCE mode=${if (largeContinuousSource28830) 1 else 0} " +
            "size=${previewWidth}x${previewHeight} reference=${reference.width}x${reference.height}")
        var largeGeometryDirty28829 = false
        if (largeSquare28829) XposedBridge.log("KYANT28829_LARGE_FOLDER_OPTIMIZED size=${previewWidth}x${previewHeight} screenW=$displayWidth")
        var frameChecks = 0L
        var geometryChanges = 0L
        var hostLayoutCorrections = 0L
        var dragDiagnosticCount = 0L
        var lastHostCorrectionMs = 0L
        // Diagnostics: track local host position independently from inherited Workspace motion.
        // Never modify Compose state or layout in a draw callback.
        var lastWorkspaceScrollX = Int.MIN_VALUE
        var lastWorkspaceTranslationX = Float.NaN
        var lastHostScreenX = Float.NaN
        var lastAnchorScreenX = Float.NaN
        var lastPositionTraceMs = 0L
        var positionChanges = 0L
        var maxPredrawUs = 0L
        var totalPredrawUs = 0L
        var offscreenChecks = 0L
        var sync28863WindowNs = 0L
        var sync28863Checks = 0L
        var sync28863Commits = 0L
        var sync28863NativeChanged = 0L
        var sync28863MaxLag = 0f
        var sync28863MaxStep = 0f
        var sync28863LastX = Float.NaN
        var skippedPositionUpdates = 0L
        var stableLayoutFrames = 0
        var lastDrawNs = 0L
        var drawIntervals = 0L
        var drawGapOver16ms = 0L
        var drawGapOver33ms = 0L
        var maxDrawGapMs = 0L
        var lastCommitNs = 0L
        var sampleCommitCount = 0L
        var commitToDrawTotalMs = 0L
        var commitToDrawSamples = 0L
        var activeCountRegistered = false
        val locationScratch = IntArray(2)
        val matrixScratch = Matrix()
        val matrixPoint = FloatArray(2)
        val rootScreen = IntArray(2)
        val rootWindow = IntArray(2)
        fun glassScreenPosition(b: Rect): Offset {
            // Include fractional Workspace translations rather than truncating to integer pixels.
            // transformMatrixToGlobal is window-local; convert to physical screen coordinates.
            matrixScratch.reset()
            anchor.transformMatrixToGlobal(matrixScratch)
            matrixPoint[0] = b.left.toFloat(); matrixPoint[1] = b.top.toFloat()
            matrixScratch.mapPoints(matrixPoint)
            root.getLocationOnScreen(rootScreen)
            root.getLocationInWindow(rootWindow)
            return Offset(matrixPoint[0] + rootScreen[0] - rootWindow[0],
                matrixPoint[1] + rootScreen[1] - rootWindow[1])
        }
        // V288.70: both native background carriers can use the SAME coordinate
        // source as the View actually drawing pixels. Reusable objects; no
        // allocation and no reference-bitmap regeneration on a normal draw.
        val settleMatrix28870 = Matrix()
        val settlePoint28870 = FloatArray(2)
        val settleScreen28870 = IntArray(2)
        val settleWindow28870 = IntArray(2)
        var settleWindowNs28870 = SystemClock.elapsedRealtimeNanos()
        var settleCount28870 = 0L
        var settleSubpixel28870 = 0L
        var settleReverse28870 = 0L
        var settleMaxGap28870 = 0f
        var settlePrevX28870 = Float.NaN
        var settlePrevDx28870 = 0f
        fun nativeCarrierDrawPosition28870(carrier: View, baseline: Offset, path: String): Offset {
            if (nativeSettleAb28870 != 1 || !carrier.isAttachedToWindow) return baseline
            val result = try {
                settleMatrix28870.reset()
                carrier.transformMatrixToGlobal(settleMatrix28870)
                settlePoint28870[0] = 0f
                settlePoint28870[1] = 0f
                settleMatrix28870.mapPoints(settlePoint28870)
                root.getLocationOnScreen(settleScreen28870)
                root.getLocationInWindow(settleWindow28870)
                Offset(settlePoint28870[0] + settleScreen28870[0] - settleWindow28870[0],
                    settlePoint28870[1] + settleScreen28870[1] - settleWindow28870[1])
            } catch (_: Throwable) { baseline }
            if (!result.x.isFinite() || !result.y.isFinite()) return baseline
            settleCount28870++
            val gap = maxOf(kotlin.math.abs(result.x - baseline.x), kotlin.math.abs(result.y - baseline.y))
            settleMaxGap28870 = maxOf(settleMaxGap28870, gap)
            if (settlePrevX28870.isFinite()) {
                val dx = result.x - settlePrevX28870
                if (kotlin.math.abs(dx) in 0.0001f..1.0f) settleSubpixel28870++
                if (dx * settlePrevDx28870 < -0.25f) settleReverse28870++
                settlePrevDx28870 = dx
            }
            settlePrevX28870 = result.x
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - settleWindowNs28870 >= 1_000_000_000L) {
                XposedBridge.log("KYANT28870_SETTLE_MATRIX host=${System.identityHashCode(anchor)} " +
                    "path=$path mode=$nativeSettleAb28870 samples=$settleCount28870 " +
                    "subpixelMoves=$settleSubpixel28870 reversals=$settleReverse28870 " +
                    "maxOriginGapPx=$settleMaxGap28870 viewX=${result.x} baseX=${baseline.x} " +
                    "fractionX=${result.x-kotlin.math.floor(result.x)}")
                settleCount28870=0L; settleSubpixel28870=0L; settleReverse28870=0L
                settleMaxGap28870=0f; settleWindowNs28870=now
            }
            return result
        }
        // Initialize only after the local coordinate helper is declared.
        samplePosition.value = glassScreenPosition(initialBounds)
        predrawMatrixSnapshot.set(Pair(samplePosition.value, SystemClock.elapsedRealtimeNanos()))
        var lastSample = Offset(Float.NaN, Float.NaN)
        // V288.24: the native sampler consumes the same pre-draw coordinate snapshot.
        // This is a local View/Canvas-only change; no mutable Bitmap bridge is involved.
        // V288.26: compare pre-draw snapshot with the native View's actual
        // transformed origin. Mode 1 uses the same View that consumes Canvas.
        // Mode 0 is the unchanged V288.25 control; mode 2 re-reads anchor.
        val nativeCoordinateMode28826 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_native_coordinate_ab_28826", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        XposedBridge.log("KYANT28826_COORD_MODE host=$trace28815Host mode=$nativeCoordinateMode28826")
        var nativePredrawPosition28824 = samplePosition.value
        var nativePredrawSerial28824 = 0L
        var firstPositionSettled = false
        var startupSyncAttempts = 0
        var startupLastPosition = Offset(Float.NaN, Float.NaN)
        var startupStableTicks = 0
        val startupBeginMs = SystemClock.uptimeMillis()
        // V286: startup position is continuously tracked; no fixed multi-second visibility gate.
        var startupGateOpen = true
        // V281: synchronize visible wallpaper sampling on each pre-draw pass.
        // V279 22ms throttle caused the optical texture to lag behind Workspace.
        val owner = Owner()
        // ComposeView is final in the Compose UI version used by this project.
        // Observe the enclosing FrameLayout instead of subclassing ComposeView.
        var disposed = false
        lateinit var releaseTracePulse28820: (String, Offset?, String) -> Unit
        // Monotonic diagnostics only; no Compose state mutation inside draw.
        var state85PendingX = Float.NaN
        var state85PendingNs = 0L
        var state85CommitX = Float.NaN
        var state85LogNs = 0L
        var state85LogCount = 0
        val frame = object : FrameLayout(anchor.context) {
            override fun dispatchDraw(canvas: Canvas) {
                val sourceBefore28864 = if (lifecycleProbe28864) sourceDrawSerial28864.get() else 0L
                // Never mutate Compose state during View.dispatchDraw: the
                // V288 experiment stalled the source snapshot on real devices.
                super.dispatchDraw(canvas)
                if (stateActive28885 && stateTrace28885 && state85PendingX.isFinite() &&
                    state85LogCount < 90) {
                    val drawNs85 = SystemClock.elapsedRealtimeNanos()
                    if (drawNs85 - state85LogNs >= 120_000_000L) {
                        state85LogNs = drawNs85
                        state85LogCount++
                        XposedBridge.log("KYANT28885_DRAW host=$trace28815Host mode=$statePipeline28885 " +
                            "targetX=$state85PendingX committedX=$state85CommitX " +
                            "frameTx=$translationX frameX=$x ageMs=${(drawNs85-state85PendingNs)/1_000_000f} " +
                            "childCount=$childCount hardware=${canvas.isHardwareAccelerated}")
                    }
                }
                if (lifecycleProbe28864) {
                    parentDrawSerial28864.incrementAndGet()
                    if (sourceDrawSerial28864.get() == sourceBefore28864)
                        parentWithoutSource28864.incrementAndGet()
                }
                val nowNs = SystemClock.elapsedRealtimeNanos()
                if (releaseTrace28820) releaseTracePulse28820("HOST_DRAW", glassScreenPosition(lastBounds),
                    "frameVisible=$visibility frameLayer=$layerType childCount=$childCount")
                if (lastDrawNs != 0L) {
                    val gapMs = (nowNs - lastDrawNs) / 1_000_000L
                    drawIntervals++
                    if (gapMs > 16L) drawGapOver16ms++
                    if (gapMs > 33L) drawGapOver33ms++
                    if (gapMs > maxDrawGapMs) maxDrawGapMs = gapMs
                    if (drawIntervals % 180L == 0L) {
                        XposedBridge.log("KYANT2885_DRAW_CADENCE host=" + System.identityHashCode(anchor) +
                            " intervals=" + drawIntervals + " over16ms=" + drawGapOver16ms +
                            " over33ms=" + drawGapOver33ms + " maxGapMs=" + maxDrawGapMs +
                            " sampleCommits=" + sampleCommitCount + " activeHosts=" + activeHostCount.get())
                    }
                }
                lastDrawNs = nowNs
                if (lastCommitNs > 0L && nowNs >= lastCommitNs) {
                    commitToDrawTotalMs += (nowNs - lastCommitNs) / 1_000_000L
                    commitToDrawSamples++
                    lastCommitNs = 0L
                }
                if (drawSeen.compareAndSet(false, true)) {
                    XposedBridge.log("KYANT2885_FIRST_DRAW elapsedMs=" +
                        (SystemClock.uptimeMillis() - startedAt) +
                        " compositionSeen=" + compositionSeen.get() +
                        " width=" + width + " height=" + height +
                        " hardware=" + canvas.isHardwareAccelerated +
                        " source=host_frame_dispatchDraw")
                }
            }
        }.apply {
            isClickable = false
            isFocusable = false
            // Do not show an uncomposed placeholder; reveal as soon as geometry and composition agree.
            visibility = View.INVISIBLE
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }
        val compose = ComposeView(anchor.context).apply {
            isClickable = false
            isFocusable = false
            // Mode 1 of V288.78 uses exactly the SAME root host, but native wallpaper
            // covers Kyant. The optical composition is kept alive for A/B parity.
            if (v230NativeMode28887 != 0 || pipelineMode28819 == 0 || pipelineMode28819 == 3 ||
                nativeCarrierAb28869 == 2 || rootDirect28878 ||
                siblingPipeline28883 == 2 || orderPipeline28884 == 1 ||
                orderPipeline28884 == 3) alpha = 0f
            if (orderPipeline28884 == 3) visibility = View.GONE
            if (stateActive28885 && v230NativeMode28887 == 0) {
                alpha = if (statePipeline28885 == 1 || statePipeline28885 == 4) 1f else 0f
                visibility = when (statePipeline28885) {
                    2 -> View.INVISIBLE
                    3 -> View.GONE
                    else -> View.VISIBLE
                }
            }
        }
        // The ComposeView is the actual native child carrying the Kyant source.
        // A/B 1 gets its current transformed origin directly from that child,
        // including fractional Workspace motion and host-local placement.
        val composeSourceMatrix28866 = Matrix()
        val composeSourcePoint28866 = FloatArray(2)
        val composeRootScreen28866 = IntArray(2)
        val composeRootWindow28866 = IntArray(2)
        fun composeViewScreenPosition28866(): Offset {
            composeSourceMatrix28866.reset()
            compose.transformMatrixToGlobal(composeSourceMatrix28866)
            composeSourcePoint28866[0] = 0f
            composeSourcePoint28866[1] = 0f
            composeSourceMatrix28866.mapPoints(composeSourcePoint28866)
            root.getLocationOnScreen(composeRootScreen28866)
            root.getLocationInWindow(composeRootWindow28866)
            return Offset(
                composeSourcePoint28866[0] + composeRootScreen28866[0] - composeRootWindow28866[0],
                composeSourcePoint28866[1] + composeRootScreen28866[1] - composeRootWindow28866[1])
        }
        // V288.8 experimental bridge: native View Canvas draws wallpaper into a
        // mutable Bitmap; Compose uses that Bitmap as its Kyant backdrop source.
        // This tests input compatibility; it does NOT guarantee same-frame sync.
        val bridgeBitmap = if (nativeBridgeMode == 1) Bitmap.createBitmap(
            previewWidth.coerceAtLeast(1), previewHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888) else null
        // In experimental carrier mode 1, the producer paints the *inactive*
        // Bitmap. The UI thread publishes that Bitmap plus its position as one
        // generation, then rotates the front/back references. The old front is
        // reused at the next native draw; no GPU completion fence is promised.
        val bridgeExtraBitmap28875 = if (bufferExperiment28875 && bridgeBitmap != null)
            Bitmap.createBitmap(bridgeBitmap.width, bridgeBitmap.height, Bitmap.Config.ARGB_8888)
            else null
        val bridgeFront28875 = androidx.compose.runtime.mutableStateOf<Bitmap?>(bridgeBitmap)
        var bridgeBack28875: Bitmap? = bridgeExtraBitmap28875
        val bridgeFrontGen28875 = androidx.compose.runtime.mutableLongStateOf(0L)
        val bridgeFrontPosition28875 = androidx.compose.runtime.mutableStateOf<Offset?>(null)
        // V288.80 stage 4: immutable-by-convention per-publication Bitmap.
        // Copies are never mutated once published; short diagnostic ONLY.
        val immutableBridge28880 = androidx.compose.runtime.mutableStateOf<Bitmap?>(null)
        val immutableGen28880 = androidx.compose.runtime.mutableLongStateOf(0L)
        val bridgeVersion = androidx.compose.runtime.mutableIntStateOf(0)
        val bridgePublishedPosition = java.util.concurrent.atomic.AtomicReference<Offset?>(null)
        // Strict opt-in, read at host creation; mode 0 leaves V288.90 unchanged.
        // Mode 1 only snapshots for 650 ms after a right-swipe release.
        val reboundFrameAb28891 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_rebound_frame_ab_28891", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        // Disabled by default. Set before restarting Launcher to activate.
        val gestureIsolation28894 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_gesture_isolation_ab_28894", 0).coerceIn(0, 1)
        } catch (_: Throwable) { 0 }
        // V288.95 A/B: 0 legacy; 1 direct reference while touch is active;
        // 2 direct reference during touch and first 120ms of right-release.
        // Only the ordinary screen-aligned RECT bridge is eligible.
        val continuityAb28895 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_source_continuity_ab_28895", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        val coordinateTrace28892 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_coordinate_trace_28892", 0) == 1
        } catch (_: Throwable) { false }
        val timelineTrace28893 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_timeline_trace_28893", 0) == 1
        } catch (_: Throwable) { false }
        // V288.96 read-only both-directions / first-motion sampling; default OFF.
        // V288.107: legacy trace-only switches retired; no per-frame diagnostics.
        val dualMotionTrace28896 = false
        val finalSampleTrace28897 = false
        val sampleSyncAb28898 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_sample_sync_ab_28898", 0).coerceIn(0, 2)
        } catch (_: Throwable) { 0 }
        if (sampleSyncAb28898 > 0) XposedBridge.log("KYANT28898_HOST host=$trace28815Host " +
            "mode=$sampleSyncAb28898 carrier=$nativeCarrierAb28869 pipeline=$pipelineMode28819 " +
            "bitmap=$bitmapPipeline28880 node=$nodePipeline28881")
        // V288.99: opt-in, read-only gesture/visibility-scoped diagnostics.
        // This setting has no effect on the original drawBitmap path.
        val visibleGestureTrace28899 = false
        val snapshotTrace288100 = false
        // V288.102 SAFETY: retire 288101's main-thread Bitmap.copy() A/B path.
        // Force-disabled even if old global settings still contain mode 1 or 2.
        val bridgePairAb288101 = 0
        val bridgePairTrace288101 = false
        // V288.103: read-only Workspace-scroll provenance. This flag intentionally
        // uses the existing zero-copy producer/PreDraw probes; no sampling changes.
        // Retired costly V288.102-104 provenance logs and their workspace probes.
        val scrollTraceEnabled288103 = false
        val timingTraceEnabled288102 = false
        val coordinateBasisTrace288104 = false
        val timings288102 = TimingTrace288102()
        // 288101 main-thread Bitmap.copy experiment remains permanently disabled above.
        val bridgePairFrame288101 = androidx.compose.runtime.mutableStateOf<VerifiedBridgeFrame288101?>(null)
        var lastPairCopyNs288101 = 0L
        var pairErrors288101 = 0
        val bridgeCoordinate28892 = java.util.concurrent.atomic.AtomicReference<BridgeCoordinate28892?>(null)
        if (timelineTrace28893) XposedBridge.log("KYANT28893_HOST host=$trace28815Host " +
            "timeline=1 coordinateTrace=$coordinateTrace28892 carrier=$nativeCarrierAb28869 " +
            "pipeline=$pipelineMode28819")
        if (coordinateTrace28892) XposedBridge.log(
            "KYANT28892_HOST host=$trace28815Host trace=1 carrier=$nativeCarrierAb28869 " +
            "pipeline=$pipelineMode28819 fusion=$fusionMode28889 " +
            "display=${displayWidth}x${displayHeight} reference=${reference.width}x${reference.height}")
        val reboundFrame28891 = androidx.compose.runtime.mutableStateOf<BridgeFrame28891?>(null)
        if (gestureIsolation28894 == 1) XposedBridge.log(
            "KYANT28894_HOST host=$trace28815Host isolation=1 frame=$reboundFrameAb28891 " +
            "carrier=$nativeCarrierAb28869 pipeline=$pipelineMode28819")
        if (continuityAb28895 > 0) XposedBridge.log(
            "KYANT28895_HOST host=$trace28815Host mode=$continuityAb28895 " +
            "carrier=$nativeCarrierAb28869 pipeline=$pipelineMode28819 " +
            "fusion=$fusionMode28889 affine=$affineSampling28876")
        var reboundCopyFailures28891 = 0
        if (reboundFrameAb28891 != 0) XposedBridge.log(
            "KYANT28891_HOST host=$trace28815Host ab=$reboundFrameAb28891 " +
            "carrier=$nativeCarrierAb28869 mode=$pipelineMode28819")
        val bridgeDrawCount = java.util.concurrent.atomic.AtomicLong(0L)
        // Read the trace switch only at host creation. Disabled by default.
        val reboundTrace28890 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_rebound_trace_28890", 0) == 1
        } catch (_: Throwable) { false }
        val predrawSample28890 = java.util.concurrent.atomic.AtomicReference<ReboundSample28890?>(null)
        val bridgeSample28890 = java.util.concurrent.atomic.AtomicReference<BridgeSample28890?>(null)
        var predrawSerial28890 = 0L
        var previousPredrawX28890 = Float.NaN
        var previousPredrawDx28890 = 0f
        var lastReversalNs28890 = 0L
        var lastPredrawLogNs28890 = 0L
        var predrawLogBudget28890 = 0
        if (reboundTrace28890) XposedBridge.log(
            "KYANT28890_HOST host=$trace28815Host engine=$selectedEngine28888 " +
            "carrier=$nativeCarrierAb28869 fusion=$fusionMode28889 " +
            "folderContained=$folderContainment28886 trace=true")
        val bridgeMismatchObserved = java.util.concurrent.atomic.AtomicLong(0L)
        // Native View and Compose draw run on the Launcher UI thread. This single
        // reusable Bitmap is updated atomically relative to those draw callbacks.
        // A pending notification is coalesced to avoid unbounded post() backlog.
        val bridgeNotifyPending = java.util.concurrent.atomic.AtomicBoolean(false)
        var traceBudget28821 = 0
        var traceSeenSeq28821 = 0L
        fun releaseTrace28820(stage: String, pos: Offset?, details: String = "") {
            if (!releaseTrace28820 || trace28815Host !in selectedHostIds28821) return
            val seq = releaseSeq28820.get()
            if (seq == 0L) return
            val ageNs = SystemClock.elapsedRealtimeNanos() - releaseNs28820.get()
            if (ageNs < 0L || ageNs > 260_000_000L) return
            // Reset budget for EVERY release, so later right-swipes are traced too.
            if (traceSeenSeq28821 != seq) {
                traceSeenSeq28821 = seq
                traceBudget28821 = 0
            }
            if (traceBudget28821 >= 160) return
            traceBudget28821++
            val nowVersion = bridgeVersion.intValue
            val published = bridgePublishedPosition.get()
            // Pixel fingerprints only on actual native sampling / Compose drawing,
            // not every PreDraw; diagnostic overhead must not create extra jank.
            val fingerprint = if (stage == "NATIVE_SAMPLE" || stage == "COMPOSE_DRAW") try {
                bridgeBitmap?.let { b ->
                    val a = b.getPixel(b.width / 2, b.height / 2)
                    val c = b.getPixel(b.width / 4, b.height / 4)
                    val d = b.getPixel((b.width * 3) / 4, (b.height * 3) / 4)
                    Integer.toHexString(a xor (c shl 1) xor (d ushr 1))
                } ?: "null"
            } catch (_: Throwable) { "error" } else "skip"
            XposedBridge.log("KYANT28821_$stage host=$trace28815Host seq=$seq " +
                "afterReleaseMs=${ageNs / 1_000_000L} ageNs=$ageNs " +
                "dx=$releaseDx28820 action=$releaseAction28820 " +
                "x=${pos?.x} y=${pos?.y} publishedX=${published?.x} publishedY=${published?.y} " +
                "deltaX=${if (pos != null && published != null) pos.x - published.x else Float.NaN} " +
                "version=$nowVersion publishes=${bridgeDrawCount.get()} bitmapSig=$fingerprint $details")
        }
        releaseTracePulse28820 = { stage, pos, details -> releaseTrace28820(stage, pos, details) }
        // V288.72: actual native source is already float RectF + FILTER_BITMAP_FLAG.
        // Diagnostic only. Restrict "settling" to a contiguous, visible, live
        // movement run; an idle redraw after seconds MUST NOT count as a frame gap.
        // V288.73: application-side PreDraw / native draw correlation.
        // Diagnostic only: no image re-sampling, View invalidation or layer change.
        val frameTrace28873 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_frame_trace_28873", 0) == 1
        } catch (_: Throwable) { false }
        var tracePreSerial28873 = 0L
        var tracePreNs28873 = 0L
        var tracePrePosition28873 = Offset(Float.NaN, Float.NaN)
        var traceDrawSerial28873 = 0L
        var traceLastDrawNs28873 = 0L
        var traceLastDrawX28873 = Float.NaN
        var traceLastDrawDx28873 = 0f
        var traceRecords28873 = 0
        val traceMatrix28873 = Matrix()
        val tracePoint28873 = FloatArray(2)
        val traceRootOnScreen28873 = IntArray(2)
        val traceRootInWindow28873 = IntArray(2)
        fun traceGlobalOrigin28873(view: View): Float {
            traceMatrix28873.reset()
            view.transformMatrixToGlobal(traceMatrix28873)
            tracePoint28873[0] = 0f
            tracePoint28873[1] = 0f
            traceMatrix28873.mapPoints(tracePoint28873)
            root.getLocationOnScreen(traceRootOnScreen28873)
            root.getLocationInWindow(traceRootInWindow28873)
            return tracePoint28873[0] + traceRootOnScreen28873[0] - traceRootInWindow28873[0]
        }
        fun traceNativeFrame28873(path: String, carrier: View, position: Offset, generation: Long) {
            if (!frameTrace28873 || disposed || traceRecords28873 >= 180) return
            val now = SystemClock.elapsedRealtimeNanos()
            traceDrawSerial28873++
            val dx = if (traceLastDrawX28873.isFinite())
                position.x - traceLastDrawX28873 else 0f
            val drawGapMs = if (traceLastDrawNs28873 > 0L)
                (now - traceLastDrawNs28873) / 1_000_000f else -1f
            val preAgeMs = if (tracePreNs28873 > 0L)
                (now - tracePreNs28873) / 1_000_000f else -1f
            val preDx = if (tracePrePosition28873.x.isFinite())
                position.x - tracePrePosition28873.x else Float.NaN
            val nearRest = traceLastDrawX28873.isFinite() &&
                kotlin.math.abs(dx) <= 3f && kotlin.math.abs(dx) >= 0.001f
            // This is a displacement heuristic, not a claim about OEM touch state.
            val phase = if (nearRest) "SMALL_STEP"
                else if (kotlin.math.abs(dx) > 3f) "MOVING" else "STILL_OR_FIRST"
            val logThis = (nearRest && traceDrawSerial28873 % 2L == 0L) ||
                (traceDrawSerial28873 % 8L == 0L) ||
                (dx * traceLastDrawDx28873 < -0.25f)
            traceLastDrawNs28873 = now
            traceLastDrawX28873 = position.x
            traceLastDrawDx28873 = dx
            if (!logThis) return
            try {
                val carrierX = traceGlobalOrigin28873(carrier)
                val hostX = traceGlobalOrigin28873(frame)
                // Find the nearest OEM workspace ancestor without relying on class names alone.
                var p: View? = anchor.parent as? View
                var workspace: View? = null
                var depth = 0
                while (p != null && depth++ < 18) {
                    if (p.javaClass.name.contains("Workspace", ignoreCase = true)) {
                        workspace = p; break
                    }
                    p = p.parent as? View
                }
                val scrollX = workspace?.scrollX ?: Int.MIN_VALUE
                val workspaceTx = workspace?.translationX ?: Float.NaN
                val parentTx = (anchor.parent as? View)?.translationX ?: Float.NaN
                val hostResidual = position.x - hostX
                val carrierResidual = position.x - carrierX
                XposedBridge.log("KYANT28873_NATIVE_FRAME host=${System.identityHashCode(anchor)} " +
                    "path=$path draw=$traceDrawSerial28873 predraw=$tracePreSerial28873 " +
                    "phase=$phase tsNs=$now preTsNs=$tracePreNs28873 " +
                    "preAgeMs=$preAgeMs drawGapMs=$drawGapMs " +
                    "sampleX=${position.x} preX=${tracePrePosition28873.x} " +
                    "samplePreDelta=$preDx dx=$dx " +
                    "carrierX=$carrierX hostX=$hostX " +
                    "sampleCarrierDelta=$carrierResidual sampleHostDelta=$hostResidual " +
                    "workspaceScrollX=$scrollX workspaceTx=$workspaceTx parentTx=$parentTx " +
                    "bridgeGen=$generation")
                traceRecords28873++
            } catch (t: Throwable) {
                XposedBridge.log("KYANT28873_TRACE_ERROR host=${System.identityHashCode(anchor)} " +
                    "error=${t.javaClass.simpleName}")
                traceRecords28873++
            }
        }
        if (frameTrace28873) XposedBridge.log(
            "KYANT28873_HOST host=${System.identityHashCode(anchor)} carrier=$nativeCarrierAb28869 " +
            "mode=$nativeSettleAb28870 diagnosticOnly=true maxRecords=180")
        // V288.74: distance is measured relative to this host's first verified
        // stationary position, NOT inferred from gesture pressure or velocity.
        // This provisional home can become invalid after an actual page change;
        // logs explicitly label its provenance. No sampling behaviour is changed.
        var distanceHomeX28874 = Float.NaN
        var distancePrevPreX28874 = Float.NaN
        var distanceStillFrames28874 = 0
        var distanceMaxExcursion28874 = 0f
        var distanceLastPreDx28874 = 0f
        var distancePreCount28874 = 0L
        var distanceNativeCount28874 = 0L
        fun distancePredraw28874(pos: Offset) {
            if (!distanceTrace28874 || nativeCarrierAb28869 == 0) return
            val dx = if (distancePrevPreX28874.isFinite()) pos.x - distancePrevPreX28874 else 0f
            distancePrevPreX28874 = pos.x
            distancePreCount28874++
            distanceStillFrames28874 = if (kotlin.math.abs(dx) < 0.035f)
                (distanceStillFrames28874 + 1).coerceAtMost(100) else 0
            if (!distanceHomeX28874.isFinite() && distanceStillFrames28874 >= 8) {
                distanceHomeX28874 = pos.x
                XposedBridge.log("KYANT28874_HOME host=${System.identityHashCode(anchor)} " +
                    "homeX=$distanceHomeX28874 source=first_stable_predraw provisional=true")
            }
            if (distanceHomeX28874.isFinite()) {
                val residual = kotlin.math.abs(pos.x - distanceHomeX28874)
                distanceMaxExcursion28874 = maxOf(distanceMaxExcursion28874, residual)
                val reversed = dx * distanceLastPreDx28874 < -0.08f
                if (distancePreCount28874 % 24L == 0L || reversed) {
                    val band = when {
                        distanceMaxExcursion28874 < 50f -> "SHORT_LT50"
                        distanceMaxExcursion28874 < 180f -> "MID_50_180"
                        else -> "LONG_GE180"
                    }
                    XposedBridge.log("KYANT28874_DISTANCE host=${System.identityHashCode(anchor)} " +
                        "pre=$distancePreCount28874 homeX=$distanceHomeX28874 " +
                        "x=${pos.x} dx=$dx residualPx=$residual " +
                        "maxExcursionPx=$distanceMaxExcursion28874 band=$band " +
                        "directionChanged=$reversed source=provisional_home")
                }
            }
            if (kotlin.math.abs(dx) > 0.01f) distanceLastPreDx28874 = dx
        }
        fun distanceNative28874(path: String, pos: Offset, gen: Long) {
            if (!distanceTrace28874 || nativeCarrierAb28869 == 0) return
            distanceNativeCount28874++
            if (distanceNativeCount28874 % 24L != 0L || !distanceHomeX28874.isFinite()) return
            val residual = kotlin.math.abs(pos.x - distanceHomeX28874)
            XposedBridge.log("KYANT28874_NATIVE_DISTANCE host=${System.identityHashCode(anchor)} " +
                "path=$path gen=$gen homeX=$distanceHomeX28874 " +
                "sampleX=${pos.x} residualPx=$residual maxExcursionPx=$distanceMaxExcursion28874")
        }
        if (distanceTrace28874 || distanceAb28874 != 0) XposedBridge.log(
            "KYANT28874_HOST host=${System.identityHashCode(anchor)} " +
            "carrier=$nativeCarrierAb28869 ab=$distanceAb28874 trace=$distanceTrace28874 " +
            "home=first_stable_predraw maxPixelShift=8px")
        // V288.75 distance probe. A one-page scroll can shift EVERY host by
        // exactly root.width; that is not a 1217px drag-return excursion.
        // This measures shortest page-equivalent displacement from the initial
        // stationary anchor, with a fresh episode for each rest-to-motion run.
        var d75Home = Float.NaN
        var d75Last = Float.NaN
        var d75Still = 0
        var d75Peak = 0f
        var d75Episode = 0L
        var d75WasMoving = false
        var d75Frame = 0L
        val d75PageWidth = displayWidth.toFloat().coerceAtLeast(1f)
        fun traceDistance28875(pos: Offset) {
            if (!distanceTrace28875 || nativeCarrierAb28869 == 0) return
            d75Frame++
            val dx = if (d75Last.isFinite()) pos.x - d75Last else 0f
            d75Last = pos.x
            val stable = kotlin.math.abs(dx) < 0.035f
            d75Still = if (stable) (d75Still + 1).coerceAtMost(100) else 0
            if (!d75Home.isFinite() && d75Still >= 8) {
                d75Home = pos.x
                XposedBridge.log("KYANT28875_PAGE_HOME host=${System.identityHashCode(anchor)} " +
                    "homeX=$d75Home pageWidth=$d75PageWidth method=stationary_mod_page")
            }
            if (!d75Home.isFinite()) return
            val absoluteDelta = pos.x - d75Home
            val pageShift = kotlin.math.round(absoluteDelta / d75PageWidth).toInt()
            val localDelta = absoluteDelta - pageShift * d75PageWidth
            val localDistance = kotlin.math.abs(localDelta)
            val moving = kotlin.math.abs(dx) >= .035f
            if (moving && !d75WasMoving) {
                d75Episode++
                d75Peak = 0f
            }
            if (moving) d75Peak = maxOf(d75Peak, localDistance)
            if ((d75Frame % 24L == 0L && (moving || d75Still < 12)) ||
                (!moving && d75WasMoving && d75Still == 1)) {
                val band = when {
                    d75Peak < 50f -> "SHORT_LT50"
                    d75Peak < 180f -> "MID_50_180"
                    else -> "LONG_GE180"
                }
                XposedBridge.log("KYANT28875_PAGE_DISTANCE host=${System.identityHashCode(anchor)} " +
                    "episode=$d75Episode pageShift=$pageShift pageWidth=$d75PageWidth " +
                    "homeX=$d75Home x=${pos.x} dx=$dx " +
                    "localDeltaPx=$localDelta localDistancePx=$localDistance " +
                    "peakPx=$d75Peak band=$band still=$d75Still " +
                    "source=page_wrapped_provisional_home")
            }
            d75WasMoving = moving
        }
        if (distanceTrace28875 || bufferExperiment28875) XposedBridge.log(
            "KYANT28875_HOST host=${System.identityHashCode(anchor)} " +
            "carrier=$nativeCarrierAb28869 bufferAb=$bridgeBufferAb28875 " +
            "distanceTrace=$distanceTrace28875 pageWidth=$d75PageWidth " +
            "twoBuffers=${bridgeExtraBitmap28875 != null}")
        val settleProbe28872 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_settle_probe_28872", 0) == 1
        } catch (_: Throwable) { false }
        var settle72WindowNs = SystemClock.elapsedRealtimeNanos()
        var settle72PreviousNs = 0L
        var settle72PreviousX = Float.NaN
        var settle72PreviousY = Float.NaN
        var settle72PreviousRefId = 0
        var settle72Samples = 0
        var settle72Contiguous = 0
        var settle72TinySteps = 0
        var settle72PixelCrossings = 0
        var settle72Over16 = 0
        var settle72Over33 = 0
        var settle72RunBreaks = 0
        var settle72ReferenceSwitches = 0
        var settle72MaxContinuousGapMs = 0f
        var settle72MaxPositionStep = 0f
        var settle72MaxTargetDrift = 0f
        var settle72MaxPreDrawGap = 0f
        fun recordNativeSettle28872(path: String, pos: Offset, reference: Bitmap, generation: Long,
                                      sampledWidth: Int, sampledHeight: Int) {
            if (!settleProbe28872) return
            val now = SystemClock.elapsedRealtimeNanos()
            val onScreen = pos.x + previewWidth > 0f && pos.x < displayWidth &&
                pos.y + previewHeight > 0f && pos.y < displayHeight &&
                frame.visibility == View.VISIBLE && frame.isShown
            if (onScreen) {
                settle72Samples++
                val refId = System.identityHashCode(reference)
                if (settle72PreviousRefId != 0 && settle72PreviousRefId != refId)
                    settle72ReferenceSwitches++
                settle72PreviousRefId = refId
                val sx = reference.width.toFloat() /
                    android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
                val sy = reference.height.toFloat() /
                    android.content.res.Resources.getSystem().displayMetrics.heightPixels.coerceAtLeast(1)
                // Both producer paths use drawBitmap(reference, null, RectF, FILTER_BITMAP_FLAG).
                // Verify actual destination mapping (float arithmetic) matches screen origin.
                if (sampledWidth > 0 && sampledHeight > 0) {
                    val x = pos.x * sx
                    val y = pos.y * sy
                    val sw = sampledWidth * sx
                    val sh = sampledHeight * sy
                    if (sw > 0f && sh > 0f) {
                        val destLeft = -x * sampledWidth / sw
                        val destTop = -y * sampledHeight / sh
                        val effectiveX = -destLeft * sw / sampledWidth / sx
                        val effectiveY = -destTop * sh / sampledHeight / sy
                        settle72MaxTargetDrift = maxOf(settle72MaxTargetDrift,
                            maxOf(kotlin.math.abs(effectiveX - pos.x),
                                  kotlin.math.abs(effectiveY - pos.y)))
                    }
                }
                val pd = predrawMatrixSnapshot.get()
                if (pd.second > 0L && now - pd.second in 0L..16_000_000L)
                    settle72MaxPreDrawGap = maxOf(settle72MaxPreDrawGap,
                        maxOf(kotlin.math.abs(pd.first.x - pos.x),
                              kotlin.math.abs(pd.first.y - pos.y)))
                if (settle72PreviousNs > 0L && settle72PreviousX.isFinite()) {
                    val gapMs = (now - settle72PreviousNs) / 1_000_000f
                    val dx = pos.x - settle72PreviousX
                    val dy = pos.y - settle72PreviousY
                    val step = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    // 8..25ms qualified draw cadence; 25..50ms still counts but is
                    // not considered a tiny-step steady run. >50ms breaks the run.
                    if (gapMs in 0.5f..50f && step > 0.001f && step < 150f) {
                        settle72Contiguous++
                        settle72MaxContinuousGapMs = maxOf(settle72MaxContinuousGapMs, gapMs)
                        settle72MaxPositionStep = maxOf(settle72MaxPositionStep, step)
                        if (gapMs > 16.67f) settle72Over16++
                        if (gapMs > 33.3f) settle72Over33++
                        if (step <= 3f) {
                            settle72TinySteps++
                            if (kotlin.math.floor(pos.x) != kotlin.math.floor(settle72PreviousX) ||
                                kotlin.math.floor(pos.y) != kotlin.math.floor(settle72PreviousY))
                                settle72PixelCrossings++
                        }
                    } else if (gapMs > 50f) settle72RunBreaks++
                }
                settle72PreviousNs = now
                settle72PreviousX = pos.x
                settle72PreviousY = pos.y
            } else {
                settle72PreviousNs = 0L
                settle72PreviousX = Float.NaN
                settle72PreviousY = Float.NaN
                settle72PreviousRefId = 0
            }
            if (now - settle72WindowNs >= 1_000_000_000L) {
                if (settle72Samples > 0) XposedBridge.log(
                    "KYANT28872_SETTLE_SAMPLING host=${System.identityHashCode(anchor)} " +
                    "path=$path draws=$settle72Samples continuous=$settle72Contiguous " +
                    "tinySteps=$settle72TinySteps pixelCrossings=$settle72PixelCrossings " +
                    "over16=$settle72Over16 over33=$settle72Over33 runBreaks=$settle72RunBreaks " +
                    "maxContinuousGapMs=$settle72MaxContinuousGapMs " +
                    "maxPositionStepPx=$settle72MaxPositionStep " +
                    "maxTargetDriftPx=$settle72MaxTargetDrift " +
                    "maxPredrawGapPx=$settle72MaxPreDrawGap " +
                    "referenceSwitches=$settle72ReferenceSwitches " +
                    "filter=true floatDst=true gen=$generation x=${pos.x}")
                settle72WindowNs = now
                settle72Samples=0;settle72Contiguous=0;settle72TinySteps=0
                settle72PixelCrossings=0;settle72Over16=0;settle72Over33=0
                settle72RunBreaks=0;settle72ReferenceSwitches=0
                settle72MaxContinuousGapMs=0f;settle72MaxPositionStep=0f
                settle72MaxTargetDrift=0f;settle72MaxPreDrawGap=0f
            }
        }
        if (settleProbe28872) XposedBridge.log(
            "KYANT28872_SETTLE_HOST host=${System.identityHashCode(anchor)} " +
            "carrier=$nativeCarrierAb28869 filter=true floatDst=true " +
            "reference=${referenceState28832.value.width}x${referenceState28832.value.height}")
        val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Draw a wallpaper source in local bitmap/View pixels from the exact
        // current source-to-screen matrix. No integer rounding of its origin.
        // This differs from the legacy translation-only rectangle if Launcher
        // applies a scale, skew or rotation to Workspace or its descendants.
        val affineGlobal28876 = Matrix()
        val affineInverse28876 = Matrix()
        val affineSource28876 = Matrix()
        val affineScreen28876 = IntArray(2)
        val affineWindow28876 = IntArray(2)
        val affineValues28876 = FloatArray(9)
        var affineDraws28876 = 0L
        var affineFailed28876 = 0L
        fun drawAffineWallpaper28876(target: Canvas, carrier: View, image: Bitmap, path: String): Boolean {
            try {
                affineGlobal28876.reset()
                carrier.transformMatrixToGlobal(affineGlobal28876)
                if (!affineGlobal28876.invert(affineInverse28876)) {
                    affineFailed28876++
                    return false
                }
                root.getLocationOnScreen(affineScreen28876)
                root.getLocationInWindow(affineWindow28876)
                val screenW = android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
                val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels.coerceAtLeast(1)
                // source Bitmap -> physical screen -> window -> carrier local.
                // transformMatrixToGlobal() returns window coordinates.
                affineSource28876.reset()
                affineSource28876.setScale(screenW.toFloat() / image.width.coerceAtLeast(1),
                    screenH.toFloat() / image.height.coerceAtLeast(1))
                affineSource28876.postTranslate(
                    -(affineScreen28876[0] - affineWindow28876[0]).toFloat(),
                    -(affineScreen28876[1] - affineWindow28876[1]).toFloat())
                affineSource28876.postConcat(affineInverse28876)
                val checkpoint = target.save()
                target.drawBitmap(image, affineSource28876, bridgePaint)
                target.restoreToCount(checkpoint)
                affineDraws28876++
                if (transformTrace28876 && affineDraws28876 % 48L == 1L) {
                    affineGlobal28876.getValues(affineValues28876)
                    XposedBridge.log("KYANT28876_AFFINE host=${System.identityHashCode(anchor)} " +
                        "path=$path draws=$affineDraws28876 " +
                        "scaleX=${affineValues28876[Matrix.MSCALE_X]} " +
                        "skewX=${affineValues28876[Matrix.MSKEW_X]} " +
                        "scaleY=${affineValues28876[Matrix.MSCALE_Y]} " +
                        "skewY=${affineValues28876[Matrix.MSKEW_Y]} " +
                        "tx=${affineValues28876[Matrix.MTRANS_X]} " +
                        "failures=$affineFailed28876")
                }
                return true
            } catch (t: Throwable) {
                affineFailed28876++
                if (affineFailed28876 < 5L) XposedBridge.log(
                    "KYANT28876_AFFINE_ERROR path=$path error=${t.javaClass.simpleName}")
                return false
            }
        }

        val bridgeDst = RectF()
        // Shared V230 sampling convention: source bitmap -> viewport pixels,
        // then subtract the FolderIcon's current screen origin. This is the
        // native BitmapShader route, not a Compose image transform.
        val fusionSourceMatrix28889 = Matrix()
        val fusionPaint28889 = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var fusionReference28889: Bitmap? = null
        var fusionShader28889: android.graphics.BitmapShader? = null
        val bridgeCanvas = bridgeBitmap?.let { Canvas(it) }
        val bridgeExtraCanvas28875 = bridgeExtraBitmap28875?.let { Canvas(it) }
        var bridgeLastReference28875: Bitmap? = null
        // V288.108: skip a redundant CPU bitmap resample only when both the
        // source identity and the full sampling position are unchanged.
        // Opt-out: settings put global liquidglass_kyant_bridge_dedupe_288108 0
        // V288.109: opt-in whole-path CPU tracing. No rendering, scheduling, or
        // wallpaper-source changes are performed by this switch.
        // Logcat tag KyantPerf288109, available through `logcat -b all`.
        val perfProfile288109 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_perf_profile_288109", 0) == 1
        } catch (_: Throwable) { false }
        // V288.110: opt-in direct GPU-backed reference draw. This bypasses
        // CPU Canvas(bridgeBitmap) sampling only when the normal native-carrier
        // -> Compose Kyant backdrop path is active. Source/geometry/optics
        // remain unchanged. Switch requires Launcher restart; default OFF.
        // This is an experimental GPU draw, NOT a zero-copy guarantee.
        // V288.111: always report the parsed setting and every effective gate.
        // V288.110 only logged when requested=true; this hid setup failures.
        val rawGpuReference288111 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_gpu_reference_ab_288110", -999)
        } catch (t: Throwable) {
            android.util.Log.w("KyantGpu288111", "KYANT288111_SETTING_ERROR " +
                t.javaClass.simpleName + " " + (t.message ?: ""))
            -998
        }
        val requestedGpuReference288110 = rawGpuReference288111 == 1
        val gpuReference288110 = requestedGpuReference288110 &&
            bridgeBitmap != null && nativeBridgeMode == 1 &&
            nativeCarrierAb28869 == 1 && fusionMode28889 == 0 &&
            !bufferExperiment28875 && !affineSampling28876 &&
            bitmapPipeline28880 < 0 && nodePipeline28881 < 0 &&
            bridgePairAb288101 == 0 && reboundFrameAb28891 != 1 &&
            frameSync28822 == 0 && v230NativeMode28887 == 0 &&
            nativeLayerMode !in 2..3 && displayIsolationMode != 2
        // Emit once for EACH Host regardless of the requested switch value.
        // This separates stale Host initialization from experimental gate failures.
        val gpuBlockers288111 = ArrayList<String>(14)
        if (rawGpuReference288111 != 1) gpuBlockers288111.add("setting=$rawGpuReference288111")
        if (bridgeBitmap == null) gpuBlockers288111.add("noBridgeBitmap")
        if (nativeBridgeMode != 1) gpuBlockers288111.add("nativeBridgeMode=$nativeBridgeMode")
        if (nativeCarrierAb28869 != 1) gpuBlockers288111.add("carrier=$nativeCarrierAb28869")
        if (fusionMode28889 != 0) gpuBlockers288111.add("fusion=$fusionMode28889")
        if (bufferExperiment28875) gpuBlockers288111.add("bufferAB")
        if (affineSampling28876) gpuBlockers288111.add("affine")
        if (bitmapPipeline28880 >= 0) gpuBlockers288111.add("bitmapPipeline=$bitmapPipeline28880")
        if (nodePipeline28881 >= 0) gpuBlockers288111.add("nodePipeline=$nodePipeline28881")
        if (bridgePairAb288101 != 0) gpuBlockers288111.add("bridgePair=$bridgePairAb288101")
        if (reboundFrameAb28891 == 1) gpuBlockers288111.add("reboundFrame")
        if (frameSync28822 != 0) gpuBlockers288111.add("frameSync=$frameSync28822")
        if (v230NativeMode28887 != 0) gpuBlockers288111.add("v230=$v230NativeMode28887")
        if (nativeLayerMode in 2..3) gpuBlockers288111.add("nativeLayer=$nativeLayerMode")
        if (displayIsolationMode == 2) gpuBlockers288111.add("displayIsolation=2")
        android.util.Log.i("KyantGpu288111", "KYANT288111_HOST host=$trace28815Host " +
            "raw=$rawGpuReference288111 requested=$requestedGpuReference288110 " +
            "effective=$gpuReference288110 blockers=${gpuBlockers288111.joinToString(",").ifEmpty { "none" }} " +
            "reference=${reference.width}x${reference.height} " +
            "carrier=$nativeCarrierAb28869 pipeline=$pipelineMode28819")
        val bridgeDedupe288108 = try {
            Settings.Global.getInt(anchor.context.contentResolver,
                "liquidglass_kyant_bridge_dedupe_288108", 1) == 1
        } catch (_: Throwable) { true }
        var lastBridgeSampleSource288108: Bitmap? = null
        var lastBridgeSampleTarget288108: Bitmap? = null
        var lastBridgeSamplePosition288108 = Offset(Float.NaN, Float.NaN)
        var skippedBridgeSamples288108 = 0L
        // V288.107: per-host aggregate only. No allocations/logging on ordinary draws.
        var perfSamples107 = 0L
        var perfTotalNs107 = 0L
        var perfMaxNs107 = 0L
        fun sampleBridgeAt(pos: Offset, carrier28876: View? = null) {
            val bitmap = if (bufferExperiment28875) bridgeBack28875 else bridgeBitmap
            if (bitmap == null) return
            val target = if (bufferExperiment28875) {
                if (bitmap === bridgeExtraBitmap28875) bridgeExtraCanvas28875 else bridgeCanvas
            } else bridgeCanvas
            if (target == null) return
            val activeReference = referenceState28832.value
            if (activeReference.isRecycled) return
            // Do not suppress diagnostic/alternate pipelines: they may require
            // per-draw publications even when the wallpaper origin is unchanged.
            val dedupeSafe288108 = bridgeDedupe288108 && !bufferExperiment28875 &&
                bitmapPipeline28880 != 4 && bridgePairAb288101 == 0 &&
                reboundFrameAb28891 != 1 && !coordinateTrace28892 &&
                !timelineTrace28893 && !snapshotTrace288100 && !reboundTrace28890
            if (dedupeSafe288108 && lastBridgeSampleSource288108 === activeReference &&
                lastBridgeSampleTarget288108 === bitmap &&
                lastBridgeSamplePosition288108 == pos) {
                skippedBridgeSamples288108++
                if (perfProfile288109) KyantPerf288109.skipBridge()
                return
            }
            val perfStart288109 = if (perfProfile288109)
                SystemClock.elapsedRealtimeNanos() else 0L
            val perfStart107 = SystemClock.elapsedRealtimeNanos()
            val sw = android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
            val sh = android.content.res.Resources.getSystem().displayMetrics.heightPixels.coerceAtLeast(1)
            val sx = activeReference.width.toFloat() / sw
            val sy = activeReference.height.toFloat() / sh
            val cropW = bitmap.width * sx
            val cropH = bitmap.height * sy
            val x = pos.x * sx
            val y = pos.y * sy
            bridgeDst.set(-x * bitmap.width / cropW.coerceAtLeast(.001f),
                -y * bitmap.height / cropH.coerceAtLeast(.001f),
                (activeReference.width - x) * bitmap.width / cropW.coerceAtLeast(.001f),
                (activeReference.height - y) * bitmap.height / cropH.coerceAtLeast(.001f))
            target.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            var affineUsed28892 = false
            if (fusionMode28889 == 1) {
                if (fusionReference28889 !== activeReference || fusionShader28889 == null) {
                    fusionReference28889 = activeReference
                    fusionShader28889 = android.graphics.BitmapShader(activeReference,
                        android.graphics.Shader.TileMode.DECAL,
                        android.graphics.Shader.TileMode.DECAL)
                }
                // Identical bitmap->native-local transform to the V230
                // AGSL wallpaper input used in the same FolderIcon geometry.
                fusionSourceMatrix28889.setValues(floatArrayOf(
                    sw.toFloat() / activeReference.width, 0f, -pos.x,
                    0f, sh.toFloat() / activeReference.height, -pos.y,
                    0f, 0f, 1f))
                fusionShader28889!!.setLocalMatrix(fusionSourceMatrix28889)
                fusionPaint28889.shader = fusionShader28889
                target.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                    fusionPaint28889)
                fusionPaint28889.shader = null
            } else if (!(affineSampling28876 && carrier28876 != null &&
                    drawAffineWallpaper28876(target, carrier28876, activeReference, "BRIDGE").also { affineUsed28892 = it })) {
                target.drawBitmap(activeReference, null, bridgeDst, bridgePaint)
            }
            val gen = bridgeDrawCount.incrementAndGet()
            if (coordinateTrace28892 || timelineTrace28893 || snapshotTrace288100) bridgeCoordinate28892.set(BridgeCoordinate28892(
                gen, SystemClock.elapsedRealtimeNanos(), pos,
                System.identityHashCode(activeReference), activeReference.width, activeReference.height,
                sw, sh, sx, sy, -pos.x, -pos.y,
                if (fusionMode28889 == 1) "shader" else if (affineUsed28892) "affine" else "rect"
            ))
            if (reboundTrace28890 || timelineTrace28893) bridgeSample28890.set(BridgeSample28890(
                gen, SystemClock.elapsedRealtimeNanos(), pos,
                System.identityHashCode(activeReference)))
            if (fusionMode28889 == 1 && fusionTrace28889 && (gen <= 2L || gen % 180L == 0L))
                XposedBridge.log("KYANT28889_V230_SOURCE_TO_KYANT host=$trace28815Host " +
                    "generation=$gen x=${pos.x} y=${pos.y} " +
                    "source=${System.identityHashCode(activeReference)} " +
                    "bridge=${System.identityHashCode(bitmap)} hardware=false")
            if (bufferExperiment28875) {
                val oldFront = bridgeFront28875.value
                // All three updates happen synchronously on Launcher UI thread.
                // The consumer uses the snapshot state-selected front Bitmap.
                bridgeFrontPosition28875.value = pos
                bridgeFrontGen28875.longValue = gen
                bridgeFront28875.value = bitmap
                bridgeBack28875 = oldFront
                if (gen % 48L == 0L) XposedBridge.log(
                    "KYANT28875_BUFFER_PUBLISH gen=$gen front=${System.identityHashCode(bitmap)} " +
                    "back=${System.identityHashCode(oldFront)} x=${pos.x} " +
                    "referenceChanged=${bridgeLastReference28875 !== activeReference}")
            }
            bridgeLastReference28875 = activeReference
            if (bitmapPipeline28880 == 4) {
                // Explicitly isolate mutable source updates from Compose reads.
                // This is deliberately expensive and must never be the default.
                try {
                    val immutableCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    immutableGen28880.longValue = gen
                    immutableBridge28880.value = immutableCopy
                    if (bitmapTrace28880 && gen % 90L == 1L) XposedBridge.log(
                        "KYANT28880_SNAPSHOT_PUBLISH host=$trace28815Host gen=$gen " +
                        "bitmapId=${System.identityHashCode(immutableCopy)} x=${pos.x}")
                } catch (t: Throwable) {
                    if (bitmapTrace28880 && gen % 90L == 1L) XposedBridge.log(
                        "KYANT28880_SNAPSHOT_ERROR ${t.javaClass.simpleName}")
                }
            }
            // V288.101: producer-side immutable pixel/position pairing. The old
            // shared bridge bitmap remains the default and is never modified here.
            if (bridgePairAb288101 > 0 && nativeCarrierAb28869 == 1 &&
                sampleSyncAb28898 == 0 && !disposed) {
                val pairNow101 = SystemClock.elapsedRealtimeNanos()
                val pairDown101 = gestureDownSerial28893
                val pairRelease101 = releaseSeq28820.get()
                val pairStart101 = bridgePairAb288101 == 2 && touchActive28894 &&
                    pairDown101 > 0L && pairNow101 - touchDownNs28896 in 0L..150_000_000L
                val pairRebound101 = !touchActive28894 && pairDown101 > 0L &&
                    releaseDownSerial28899 == pairDown101 &&
                    releaseAction28820 == "UP" &&
                    pairNow101 - releaseNs28820.get() in 0L..650_000_000L
                if ((pairStart101 || pairRebound101) &&
                    bitmap.width.toLong() * bitmap.height.toLong() <= 2_000_000L &&
                    pairNow101 - lastPairCopyNs288101 >= 65_000_000L) {
                    // Copy is performed only after target.draw... completed.
                    // A single immutable publication contains all metadata.
                    try {
                        val frozen101 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        if (gestureDownSerial28893 == pairDown101 &&
                            releaseSeq28820.get() == pairRelease101 &&
                            (if (pairStart101) touchActive28894 else !touchActive28894)) {
                            val frame101 = VerifiedBridgeFrame288101(
                                frozen101, pos, gen, SystemClock.elapsedRealtimeNanos(),
                                System.identityHashCode(activeReference), pairDown101,
                                pairRelease101, if (pairStart101) "TOUCH_START" else "REBOUND")
                            bridgePairFrame288101.value = frame101
                            lastPairCopyNs288101 = pairNow101
                            if (bridgePairTrace288101) XposedBridge.log(
                                "KYANT288101_PAIR_PUBLISH host=$trace28815Host " +
                                "gen=$gen phase=${frame101.phase} x=${pos.x} y=${pos.y} " +
                                "down=$pairDown101 release=$pairRelease101 " +
                                "bitmapId=${System.identityHashCode(frozen101)}")
                        }
                    } catch (t101: Throwable) {
                        pairErrors288101++
                        if (pairErrors288101 <= 3) XposedBridge.log(
                            "KYANT288101_PAIR_ERROR host=$trace28815Host " +
                            "type=${t101.javaClass.simpleName}")
                    }
                }
            }
            // Record only after drawing completed; changes to source, target or
            // position force the next full sample. Never alter optical parameters.
            lastBridgeSampleSource288108 = activeReference
            lastBridgeSampleTarget288108 = bitmap
            lastBridgeSamplePosition288108 = pos
            bridgePublishedPosition.set(pos)
            if (reboundFrameAb28891 == 1 && nativeCarrierAb28869 == 1 &&
                pipelineMode28819 == 2 && !disposed) {
                val age = SystemClock.elapsedRealtimeNanos() - releaseNs28820.get()
                val eligible = releaseSeq28820.get() > 0L &&
                    age in 0L..650_000_000L && releaseDx28820 > 0f &&
                    (gestureIsolation28894 == 0 || !touchActive28894)
                // Never publish a partial copy or mismatched position.
                if (eligible) try {
                    // Capture gesture identity BEFORE copying pixels. Reject a copy
                    // if DOWN or release changed while Bitmap.copy was running.
                    val downAtStart94 = gestureDownSerial28893
                    val releaseAtStart94 = releaseSeq28820.get()
                    val frozen = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val frame91 = BridgeFrame28891(frozen, pos, gen,
                        SystemClock.elapsedRealtimeNanos(), System.identityHashCode(activeReference),
                        downAtStart94, releaseAtStart94)
                    if (gestureIsolation28894 == 0 || (!touchActive28894 &&
                        downAtStart94 == gestureDownSerial28893 &&
                        releaseAtStart94 == releaseSeq28820.get())) {
                        reboundFrame28891.value = frame91
                        if (reboundTrace28890 && gen % 12L == 1L) XposedBridge.log(
                            "KYANT28891_PUBLISH host=$trace28815Host gen=$gen x=${pos.x} " +
                            "bitmap=${System.identityHashCode(frozen)} ageMs=${age/1_000_000f}")
                    }
                } catch (t: Throwable) {
                    reboundCopyFailures28891++
                    if (reboundCopyFailures28891 <= 3) XposedBridge.log(
                        "KYANT28891_COPY_FAILED error=${t.javaClass.simpleName}")
                }
            }
            recordNativeSettle28872("NATIVE_BITMAP_BRIDGE", pos, activeReference, gen,
                bitmap.width, bitmap.height)
            // CPU-side bridge processing only, including source selection and
            // generation bookkeeping; not GPU upload or SurfaceFlinger present.
            if (perfProfile288109) KyantPerf288109.record(
                KyantPerf288109.BRIDGE, SystemClock.elapsedRealtimeNanos() - perfStart288109,
                bitmap.width.toLong() * bitmap.height.toLong())
            // One summary per 180 samples per host. Measures producer CPU time only.
            val perfNs107 = SystemClock.elapsedRealtimeNanos() - perfStart107
            perfSamples107++
            perfTotalNs107 += perfNs107
            if (perfNs107 > perfMaxNs107) perfMaxNs107 = perfNs107
            if (perfSamples107 == 180L) {
                XposedBridge.log("KYANT288107_BRIDGE host=$trace28815Host " +
                    "samples=$perfSamples107 bitmap=${bitmap.width}x${bitmap.height} " +
                    "totalMs=${perfTotalNs107 / 1_000_000f} " +
                    "averageMs=${perfTotalNs107 / 180_000_000f} " +
                    "maxMs=${perfMaxNs107 / 1_000_000f}")
                perfSamples107 = 0L
                perfTotalNs107 = 0L
                perfMaxNs107 = 0L
            }
        }
        val bridgeView = if (bridgeBitmap != null) object : View(anchor.context) {
            private var count = 0L
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (disposed || width <= 0 || height <= 0) return
                val nativeDrawStart288109 = if (perfProfile288109)
                    SystemClock.elapsedRealtimeNanos() else 0L
                val oldPos = try { glassScreenPosition(lastBounds) } catch (_: Throwable) { return }
                val pos = if (nativeCarrierAb28869 == 1)
                    nativeCarrierDrawPosition28870(this, oldPos, "NATIVE_BITMAP_BRIDGE") else oldPos
                // V288.22 mode 1: Compose draw itself samples the reference frame.
                // Do not publish an independent native Bitmap frame first.
                if (!gpuReference288110 &&
                    (frameSync28822 != 1 || nativeCarrierAb28869 == 1)) {
                    sampleBridgeAt(pos, this)
                } else if (gpuReference288110 && perfProfile288109) {
                    KyantPerf288109.skipNativeBridge288110()
                }
                if (nativeCarrierAb28869 == 1) traceNativeFrame28873(
                    "NATIVE_BITMAP_BRIDGE", this, pos, bridgeDrawCount.get())
                if (nativeCarrierAb28869 == 1) distanceNative28874(
                    "NATIVE_BITMAP_BRIDGE", pos, bridgeDrawCount.get())
                releaseTrace28820("NATIVE_SAMPLE", pos, "draw=${count + 1L} bridgeW=${bridgeBitmap?.width} bridgeH=${bridgeBitmap?.height}")
                // Mode 3: sample into exactly the same bridge Bitmap as mode 2,
                // but render it in the native View's own Canvas (no Compose handoff).
                if ((pipelineMode28819 == 3 && nativeCarrierAb28869 == 0) ||
                    bitmapPipeline28880 == 1) {
                    // Native bridge mode: the Bitmap is allocated when bridgeView exists.
                    val buffer = bridgeBitmap
                    if (buffer != null) canvas.drawBitmap(buffer, 0f, 0f, bridgePaint)
                }
                count++
                if (count == 1L || count % 240L == 0L) {
                    android.util.Log.i("KyantGpu288111", "KYANT288111_NATIVE host=$trace28815Host " +
                        "draws=$count effective=$gpuReference288110 " +
                        "cpuBridgeExpected=${!gpuReference288110 && (frameSync28822 != 1 || nativeCarrierAb28869 == 1)} " +
                        "posX=${pos.x} posY=${pos.y}")
                }
                if ((frameSync28822 != 1 || nativeCarrierAb28869 == 1) &&
                    pipelineMode28819 != 3 && bridgeNotifyPending.compareAndSet(false, true)) {
                    post {
                        bridgeNotifyPending.set(false)
                        if (!disposed) {
                            bridgeVersion.intValue++
                            if (perfProfile288109) KyantPerf288109.notifyCompose()
                            releaseTrace28820("COMPOSE_NOTIFY", bridgePublishedPosition.get(), "pending=false")
                        }
                    }
                }
                if (count % 60L == 1L && pipelineMode28819 >= 0) XposedBridge.log(
                    "KYANT28819_NATIVE_BRIDGE host=$trace28815Host mode=$pipelineMode28819 " +
                    "draws=$count x=${pos.x} y=${pos.y} version=${bridgeVersion.intValue}")
                if (nativeCarrierAb28869 != 0 && count % 60L == 0L) XposedBridge.log(
                    "KYANT28869_NATIVE_DRAW mode=$nativeCarrierAb28869 draws=$count " +
                    "x=${pos.x} y=${pos.y} bridgeGen=${bridgeDrawCount.get()} " +
                    "publishedX=${bridgePublishedPosition.get()?.x} canvasHardware=${canvas.isHardwareAccelerated}")
                if (count % 120L == 0L) XposedBridge.log(
                    "KYANT2889_NATIVE_PUBLISH draws=$count x=${pos.x} y=${pos.y} version=${bridgeVersion.intValue}")
                if (perfProfile288109) KyantPerf288109.record(
                    KyantPerf288109.NATIVE_DRAW,
                    SystemClock.elapsedRealtimeNanos() - nativeDrawStart288109)
            }
        }.apply { isClickable = false; isFocusable = false; setWillNotDraw(false) } else null
        if (releaseTrace28820) {
            liveHostProbes28821[trace28815Host] = ReleaseHostProbe28821(WeakReference(anchor)) {
                val livePos = glassScreenPosition(lastBounds)
                val published = bridgePublishedPosition.get()
                "sampleX=${livePos.x} sampleY=${livePos.y} publishedX=${published?.x} " +
                    "publishedY=${published?.y} committedX=${samplePosition.value.x} " +
                    "version=${bridgeVersion.intValue} samples=${bridgeDrawCount.get()} " +
                    "hostX=${frame.x} hostTx=${frame.translationX} frameVisible=${frame.visibility} " +
                    "anchorVisible=${anchor.isShown} frameLayer=${frame.layerType}"
            }
            XposedBridge.log("KYANT28821_HOST_REGISTER host=$trace28815Host source=${System.identityHashCode(reference)}")
        }
        // V288.7: native Canvas reference, intentionally ABOVE Kyant on the LEFT half
        // only. Right half stays the unmodified Compose/Kyant optical renderer.
        // This is an A/B diagnostic, NOT the final hybrid optical implementation.
        var fullOverlayInstalled28877 = false
        var nativeSampleView28878Invalidator: (() -> Unit)? = null
        val fullOverlayRootLocation28877 = IntArray(2)
        var fullOverlayTraceCount28877 = 0L
        var rootCompareTraceCount28878 = 0L
        var fullOverlayLastPosition28877 = Offset(Float.NaN, Float.NaN)
        fun positionFullOverlay28877(pos: Offset, host: View) {
            if (!fullOverlayInstalled28877) return
            root.getLocationOnScreen(fullOverlayRootLocation28877)
            val targetX = pos.x - fullOverlayRootLocation28877[0]
            val targetY = pos.y - fullOverlayRootLocation28877[1]
            if (kotlin.math.abs(host.translationX - targetX) > 0.0001f ||
                kotlin.math.abs(host.translationY - targetY) > 0.0001f) {
                host.translationX = targetX
                host.translationY = targetY
                host.postInvalidateOnAnimation()
            }
            if (rootDirect28878) {
                // A root-host translation can be a RenderNode property update with no
                // native onDraw. This invalidate keeps the native-only control active.
                nativeSampleView28878Invalidator?.invoke()
            }
            if (fullOverlayTrace28877) {
                val last = fullOverlayLastPosition28877
                val dx = if (last.x.isFinite()) pos.x - last.x else 0f
                if (fullOverlayTraceCount28877++ % 30L == 0L) XposedBridge.log(
                    "KYANT288771_ROOT_MOTION host=${System.identityHashCode(anchor)} " +
                    "sampleX=${pos.x} overlayX=$targetX frameTx=${host.translationX} " +
                    "dx=$dx frameParent=${host.parent?.javaClass?.simpleName} " +
                    "width=${host.width} height=${host.height}")
                fullOverlayLastPosition28877 = pos
            }
        }
        var overlayInstalled28876 = false
        val overlayMatrix28876 = Matrix()
        val overlayOrigin28876 = FloatArray(2)
        val overlayRootScreen28876 = IntArray(2)
        val overlayRootWindow28876 = IntArray(2)
        fun nativeCarrierDrawPosition28876(view: View, fallback: Offset): Offset = try {
            overlayMatrix28876.reset()
            view.transformMatrixToGlobal(overlayMatrix28876)
            overlayOrigin28876[0] = 0f; overlayOrigin28876[1] = 0f
            overlayMatrix28876.mapPoints(overlayOrigin28876)
            root.getLocationOnScreen(overlayRootScreen28876)
            root.getLocationInWindow(overlayRootWindow28876)
            Offset(overlayOrigin28876[0] + overlayRootScreen28876[0] - overlayRootWindow28876[0],
                overlayOrigin28876[1] + overlayRootScreen28876[1] - overlayRootWindow28876[1])
        } catch (_: Throwable) { fallback }
        val nativeSampleView = if (v230NativeMode28887 != 0 || nativeCarrierAb28869 == 2 || rootDirect28878 || siblingNative28883 || orderNative28884 ||
            (nativeCompareMode == 1 && nativeBridgeMode == 0 && displayIsolationMode == 0) ||
            displayIsolationMode == 1 || nativeLayerMode in 1..3) object : View(anchor.context) {
            private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val destination = RectF()
            // V288.87: cache the BitmapShader for the live wallpaper generation.
            // Local matrix changes only on the View's native draw, not through
            // asynchronous Compose State/Backdrop publishing.
            private var v230Source287: Bitmap? = null
            private var v230Shader287: android.graphics.BitmapShader? = null
            private val v230SampleMatrix287 = Matrix()
            private val v230Valid287 = RectF()
            private val v230Bounds287 = RectF()
            private val v230ClipPath287 = android.graphics.Path()
            private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
            }
            private var drawCount = 0L
            private var lastDrawTimeNs = 0L
            private var maxGapMs = 0L
            private var lastX = Float.NaN
            private var lastY = Float.NaN
            private val nativeMatrix28826 = Matrix()
            private val nativePoint28826 = FloatArray(2)
            private val nativeScreen28826 = IntArray(2)
            private val nativeWindow28826 = IntArray(2)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (width <= 0 || height <= 0 || disposed) return
                // Do not read a second Workspace transform after onPreDraw has
                // already scheduled this frame's native redraw.
                val basePosition28870 = when (nativeCoordinateMode28826) {
                    1 -> try {
                        // Compute from the native sampling View's own transformed
                        // origin; avoids mixing anchor and host transform epochs.
                        nativeMatrix28826.reset()
                        transformMatrixToGlobal(nativeMatrix28826)
                        nativePoint28826[0] = 0f
                        nativePoint28826[1] = 0f
                        nativeMatrix28826.mapPoints(nativePoint28826)
                        root.getLocationOnScreen(nativeScreen28826)
                        root.getLocationInWindow(nativeWindow28826)
                        Offset(nativePoint28826[0] + nativeScreen28826[0] - nativeWindow28826[0],
                            nativePoint28826[1] + nativeScreen28826[1] - nativeWindow28826[1])
                    } catch (_: Throwable) { nativePredrawPosition28824 }
                    2 -> try { glassScreenPosition(lastBounds) }
                        catch (_: Throwable) { nativePredrawPosition28824 }
                    else -> nativePredrawPosition28824
                }
                val position = if (overlayInstalled28876) {
                    nativeCarrierDrawPosition28876(this, basePosition28870)
                } else if (nativeCarrierAb28869 == 2 || rootDirect28878 || siblingNative28883 || orderNative28884)
                    nativeCarrierDrawPosition28870(this, basePosition28870, "NATIVE_VIEW_DIRECT")
                    else basePosition28870
                if (releaseTrace28820) releaseTrace28820("COORD28826", position,
                    "mode=$nativeCoordinateMode28826 snapshotX=${nativePredrawPosition28824.x} deltaX=${position.x-nativePredrawPosition28824.x}")
                // V288.25: native Canvas diagnostics also work in pipeline=-1.
                // Record only during the bounded release window; no bitmap readback.
                if (releaseTrace28820) releaseTrace28820("NATIVE_DRAW", position,
                    "nativeLayer=$layerType frameLayer=${frame.layerType} hardware=${canvas.isHardwareAccelerated} sourceId=${System.identityHashCode(reference)}")
                if (rightStartTraceEnabled && onsetFramesRemaining > 0 && onsetNativeDrawCount < 90) {
                    XposedBridge.log("KYANT28813_NATIVE_DRAW onset=$onsetSerial direction=$onsetDirection x=${position.x} y=${position.y} sourceId=$onsetSourceId")
                    onsetNativeDrawCount++
                }
                if (matrixTrace28814 && trace28814DrawRemaining > 0 && trace28814Events < 180) {
                    try {
                        trace28814DrawSerial++
                        trace28814AnchorMatrix.reset()
                        anchor.transformMatrixToGlobal(trace28814AnchorMatrix)
                        trace28814AnchorPoint[0] = lastBounds.left.toFloat()
                        trace28814AnchorPoint[1] = lastBounds.top.toFloat()
                        trace28814AnchorMatrix.mapPoints(trace28814AnchorPoint)
                        trace28814HostMatrix.reset()
                        frame.transformMatrixToGlobal(trace28814HostMatrix)
                        trace28814HostPoint[0] = 0f
                        trace28814HostPoint[1] = 0f
                        trace28814HostMatrix.mapPoints(trace28814HostPoint)
                        root.getLocationOnScreen(trace28814RootScreen)
                        root.getLocationInWindow(trace28814RootWindow)
                        val windowOffset = trace28814RootScreen[0] - trace28814RootWindow[0]
                        val anchorScreen = trace28814AnchorPoint[0] + windowOffset
                        val hostScreen = trace28814HostPoint[0] + windowOffset
                        XposedBridge.log("KYANT28814_DRAW_MATRIX host=${System.identityHashCode(anchor)} onset=$onsetSerial dir=$trace28814LastDirection predraw=$trace28814PredrawSerial draw=$trace28814DrawSerial sampleX=${position.x} anchorX=$anchorScreen hostX=$hostScreen sampleAnchorDelta=${position.x-anchorScreen} sampleHostDelta=${position.x-hostScreen} rootOffset=$windowOffset frameLeft=${frame.left} frameTx=${frame.translationX} sourceId=$onsetSourceId")
                        trace28814Events++
                    } catch (t: Throwable) {
                        XposedBridge.log("KYANT28814_DRAW_ERROR ${t.javaClass.simpleName}")
                    }
                    trace28814DrawRemaining--
                }
                if (frameTrace28815 && trace28815Remaining > 0 && trace28815Events < 160) {
                    val now28815 = SystemClock.elapsedRealtimeNanos()
                    trace28815DrawSerial++
                    val gapMs = if (trace28815LastDrawNs > 0L)
                        (now28815 - trace28815LastDrawNs) / 1000000L else -1L
                    val drawDx = if (trace28815LastDrawX.isFinite())
                        position.x - trace28815LastDrawX else 0f
                    val preAgeMs = if (trace28815LastPreNs > 0L)
                        (now28815 - trace28815LastPreNs) / 1000000L else -1L
                    XposedBridge.log(
                        "KYANT28815_NATIVE_DRAW host=$trace28815Host dir=$trace28815Direction draw=$trace28815DrawSerial pre=$trace28815PreSerial preAdvance=${trace28815PreSerial-trace28815LastPreSerialAtDraw} x=${position.x} dx=$drawDx preX=$trace28815LastPreX preDiff=${position.x-trace28815LastPreX} preAgeMs=$preAgeMs drawGapMs=$gapMs hardware=${canvas.isHardwareAccelerated} layerType=$layerType sourceId=${System.identityHashCode(reference)}")
                    trace28815LastPreSerialAtDraw = trace28815PreSerial
                    trace28815LastDrawNs = now28815
                    trace28815LastDrawX = position.x
                    trace28815Remaining--
                    trace28815Events++
                }
                if (redraw28816Pending) {
                    redraw28816Draws++
                    val latencyMs = (SystemClock.elapsedRealtimeNanos() - redraw28816PendingNs) / 1000000L
                    XposedBridge.log("KYANT28816_REDRAW_DRAW host=$trace28815Host request=$redraw28816Requests draw=$redraw28816Draws latencyMs=$latencyMs requestX=$redraw28816PendingX drawX=${position.x} delta=${position.x-redraw28816PendingX}")
                    redraw28816Pending = false
                }
                if (layerTrace28817 && layerTrace28817Remaining > 0 && layerTrace28817Count < 120) {
                    val hostLoc = IntArray(2); val nativeLoc = IntArray(2)
                    frame.getLocationOnScreen(hostLoc); getLocationOnScreen(nativeLoc)
                    XposedBridge.log("KYANT28817_DRAW_STATE host=$trace28815Host dir=$layerTrace28817Direction x=${position.x} frameX=${hostLoc[0]} nativeX=${nativeLoc[0]} frameTx=${frame.translationX} nativeTx=$translationX frameLayer=${frame.layerType} nativeLayer=$layerType frameVis=${frame.visibility} nativeVis=$visibility frameAlpha=${frame.alpha} nativeAlpha=$alpha attached=$isAttachedToWindow hardware=${canvas.isHardwareAccelerated}")
                    layerTrace28817Remaining--; layerTrace28817Count++
                }
                val screenW = android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
                val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels.coerceAtLeast(1)
                val activeReference = referenceState28832.value
                if (activeReference.isRecycled) return
                val scaleX = activeReference.width.toFloat() / screenW
                val scaleY = activeReference.height.toFloat() / screenH
                val x = position.x * scaleX
                val y = position.y * scaleY
                val sw = width.toFloat() * scaleX
                val sh = height.toFloat() * scaleY
                if (sw <= 0f || sh <= 0f) return
                if (v230NativeMode28887 != 0) {
                    // Same global screen->wallpaper geometry as V230 HookEntry:
                    // shader bitmap-to-local matrix = screen/bitmap scale and
                    // translation = -current actual FolderIcon screen origin.
                    // Both optics and wallpaper sampling execute in THIS
                    // native View.onDraw(), without a Compose/Bitmap handoff.
                    if (v230Source287 !== activeReference || v230Shader287 == null) {
                        v230Source287 = activeReference
                        v230Shader287 = android.graphics.BitmapShader(
                            activeReference, android.graphics.Shader.TileMode.DECAL,
                            android.graphics.Shader.TileMode.DECAL)
                    }
                    v230SampleMatrix287.setValues(floatArrayOf(
                        screenW.toFloat() / activeReference.width, 0f, -position.x,
                        0f, screenH.toFloat() / activeReference.height, -position.y,
                        0f, 0f, 1f))
                    val source287 = v230Shader287!!
                    source287.setLocalMatrix(v230SampleMatrix287)
                    v230Bounds287.set(0f, 0f, width.toFloat(), height.toFloat())
                    v230Valid287.set(-position.x, -position.y,
                        screenW.toFloat() - position.x,
                        screenH.toFloat() - position.y)
                    val save287 = canvas.save()
                    // Clip only to this native optical container, never the
                    // entire Launcher or its icon children.
                    val radius287 = kotlin.math.min(width, height) * 0.205f
                    v230ClipPath287.reset()
                    v230ClipPath287.addRoundRect(
                        v230Bounds287, radius287, radius287,
                        android.graphics.Path.Direction.CW)
                    canvas.clipPath(v230ClipPath287)
                    val usedV230287 = if (v230NativeMode28887 == 1 && canvas.isHardwareAccelerated) {
                        if (fusionMode28889 == 2)
                            UnifiedGlass230.drawFusion289(this, canvas, v230Bounds287,
                                radius287, source287, source287, v230Valid287, 0f)
                        else
                            UnifiedGlass230.drawExperimental287(this, canvas, v230Bounds287,
                                radius287, source287, source287, v230Valid287, 0f)
                    } else false
                    if (fusionMode28889 == 2 && fusionTrace28889 &&
                        (drawCount < 2L || drawCount % 180L == 0L))
                        XposedBridge.log("KYANT28889_NATIVE_EDGE_AGSL " +
                            "host=${System.identityHashCode(anchor)} used=${usedV230287} " +
                            "source=${System.identityHashCode(activeReference)} " +
                            "sampleX=${position.x} radius=$radius287")
                    if (!usedV230287) {
                        // On AGSL compilation failure, fall back to the exact
                        // same native wallpaper sample (do not show blank glass).
                        bitmapPaint.shader = source287
                        canvas.drawRect(v230Bounds287, bitmapPaint)
                        bitmapPaint.shader = null
                    }
                    canvas.restoreToCount(save287)
                    drawCount++
                    if (v230NativeTrace28887 && (drawCount <= 3L || drawCount % 180L == 0L))
                        XposedBridge.log("KYANT28887_NATIVE_DRAW mode=$v230NativeMode28887 " +
                            "host=${System.identityHashCode(anchor)} usedV230=$usedV230287 " +
                            "hardware=${canvas.isHardwareAccelerated} source=${System.identityHashCode(activeReference)} " +
                            "sampleX=${position.x} sampleY=${position.y} " +
                            "width=$width height=$height radius=$radius287")
                    return
                }
                destination.set(-x * width / sw, -y * height / sh,
                    (activeReference.width - x) * width / sw,
                    (activeReference.height - y) * height / sh)
                val checkpoint = canvas.save()
                if (nativeCarrierAb28869 == 2 || rootDirect28878 || siblingNative28883 || orderNative28884 ||
                    displayIsolationMode == 1 || nativeLayerMode in 1..3) {
                    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                } else {
                    canvas.clipRect(0f, 0f, width * 0.5f, height.toFloat())
                }
                if (bitmapPipeline28880 == 1 && bridgeBitmap != null) {
                    // Native Canvas consumes the same bridge pixels that Compose
                    // consumed in V288.79 stage 1. Publish synchronously here to
                    // prevent draw ordering from becoming a hidden variable.
                    sampleBridgeAt(position, this)
                    canvas.drawBitmap(bridgeBitmap, 0f, 0f, bitmapPaint)
                } else if (!(affineSampling28876 &&
                        drawAffineWallpaper28876(canvas, this, activeReference, "DIRECT"))) {
                    canvas.drawBitmap(activeReference, null, destination, bitmapPaint)
                }
                canvas.restoreToCount(checkpoint)
                if (nativeCarrierAb28869 == 2) recordNativeSettle28872(
                    "NATIVE_VIEW_DIRECT", position, activeReference, drawCount + 1L,
                    width, height)
                if (nativeCarrierAb28869 == 2) traceNativeFrame28873(
                    "NATIVE_VIEW_DIRECT", this, position, drawCount + 1L)
                if (nativeCarrierAb28869 == 2) distanceNative28874(
                    "NATIVE_VIEW_DIRECT", position, drawCount + 1L)
                if (nativeCarrierAb28869 != 2 && !rootDirect28878 && !siblingNative28883 && !orderNative28884 &&
                    displayIsolationMode != 1 && nativeLayerMode == 0)
                    canvas.drawLine(width * 0.5f, 0f, width * 0.5f, height.toFloat(), dividerPaint)
                val now = SystemClock.elapsedRealtimeNanos()
                if (lastDrawTimeNs > 0L) {
                    val gap = (now - lastDrawTimeNs) / 1_000_000L
                    if (gap > maxGapMs) maxGapMs = gap
                }
                lastDrawTimeNs = now
                drawCount++
                if (drawCount % 120L == 0L) {
                    XposedBridge.log("KYANT2887_NATIVE_DRAW host=" + System.identityHashCode(anchor) +
                        " draws=" + drawCount + " x=" + position.x + " y=" + position.y +
                        " deltaX=" + (position.x - lastX) + " deltaY=" + (position.y - lastY) +
                        " maxGapMs=" + maxGapMs + " hardware=" + canvas.isHardwareAccelerated)
                    maxGapMs = 0L
                }
                lastX = position.x; lastY = position.y
            }
        }.apply { isClickable = false; isFocusable = false; setWillNotDraw(false) } else null
        nativeSampleView28878Invalidator = { nativeSampleView?.postInvalidateOnAnimation() }
        // Root overlay native-only control: no Workspace parent transform.
        // Only mode 2 with native carrier 2 requests this, and removal is
        // guaranteed during dispose. Wallpaper updates keep native invalidation.
        val overlayRootLocation28876 = IntArray(2)
        fun positionOverlay28876(pos: Offset) {
            if (!overlayInstalled28876) return
            val view = nativeSampleView ?: return
            root.getLocationOnScreen(overlayRootLocation28876)
            val nextX = pos.x - overlayRootLocation28876[0]
            val nextY = pos.y - overlayRootLocation28876[1]
            if (kotlin.math.abs(view.translationX - nextX) > 0.0001f ||
                kotlin.math.abs(view.translationY - nextY) > 0.0001f) {
                view.translationX = nextX
                view.translationY = nextY
                view.postInvalidateOnAnimation()
            }
        }
        var recomposer: Recomposer? = null
        var scope: CoroutineScope? = null
        fun restoreRebound28818(reason: String) {
            val target = rebound28818Target ?: return
            rebound28818Restore?.let { target.removeCallbacks(it) }
            try {
                // Restore only if this experiment still owns the software layer.
                if (target.layerType == View.LAYER_TYPE_SOFTWARE) target.setLayerType(rebound28818OldLayer, null)
                XposedBridge.log("KYANT28818_RESTORE host=$trace28815Host reason=$reason old=$rebound28818OldLayer")
            } catch (t: Throwable) { XposedBridge.log("KYANT28818_RESTORE_ERROR ${t.javaClass.simpleName}") }
            rebound28818Target = null
            rebound28818Restore = null
            reboundLayerGuard28818.set(false)
        }
        // V288.32: the FolderIcon listens to the SAME source-generation event
        // as Dock. This callback is always dispatched on the Launcher main thread.
        val referenceListener28832 = object : KyantLauncherFrameBridge266.ReferenceListener {
            override fun onReferenceFrameReady(next: Bitmap, version: Long) {
                if (disposed || !anchor.isAttachedToWindow || next.isRecycled) return
                if (referenceState28832.value === next) return
                referenceState28832.value = next
                // Invalidate the cached native/Compose child, not the whole desktop.
                nativeSampleView?.postInvalidateOnAnimation()
                bridgeView?.postInvalidateOnAnimation()
                compose.postInvalidateOnAnimation()
                frame.postInvalidateOnAnimation()
                anchor.postInvalidateOnAnimation()
                XposedBridge.log("KYANT28832_FOLDER_REFERENCE_UPDATED host=$trace28815Host generation=$version sourceId=${System.identityHashCode(next)} size=${next.width}x${next.height}")
            }
        }
        fun dispose(reason: String) {
            KyantLauncherFrameBridge266.removeReferenceListener(referenceListener28832)
            restoreRebound28818("dispose")
            if (disposed) return
            disposed = true
            if (folderContainment28886) XposedBridge.log(
                "KYANT28886_FOLDER_DISPOSE host=$trace28815Host reason=$reason " +
                "parent=${frame.parent?.javaClass?.simpleName}")
            if (releaseTrace28820) liveHostProbes28821.remove(trace28815Host)
            if (activeCountRegistered) {
                if (perfProfile288109) KyantPerf288109.unregister(trace28815Host)
                activeHostCount.decrementAndGet()
                activeCountRegistered = false
            }
            KyantLauncherLensProbe270.setActiveFor273(anchor, false)
            KyantLauncherLensProbe270.clearAttempt(anchor)
            try { followListener?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(it) } } catch (_: Throwable) {}
            try { treeDrawListener28865?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(it) } } catch (_: Throwable) {}
            try { detachListener?.let { anchor.removeOnAttachStateChangeListener(it) } } catch (_: Throwable) {}
            try { layoutListener?.let { anchor.removeOnLayoutChangeListener(it) } } catch (_: Throwable) {}
            if (fullOverlayInstalled28877) try {
                if (frame.parent === fullOverlayParent28877) fullOverlayParent28877?.removeView(frame)
                fullOverlayInstalled28877 = false
                XposedBridge.log("KYANT288771_ROOT_CHILD_REMOVED host=$trace28815Host reason=$reason")
            } catch (t: Throwable) {
                XposedBridge.log("KYANT288771_REMOVE_ERROR ${t.javaClass.simpleName}")
            }
            if (overlayInstalled28876) try {
                overlayParent28876?.overlay?.remove(nativeSampleView!!)
                overlayInstalled28876 = false
            } catch (_: Throwable) {}
            try { compose.disposeComposition() } catch (t: Throwable) {
                XposedBridge.log("KYANT2885_DISPOSE_COMPOSE_ERROR " + t.javaClass.simpleName)
            }
            try { if (frame.parent === folder) folder.removeView(frame) } catch (t: Throwable) {
                XposedBridge.log("KYANT2885_DISPOSE_VIEW_ERROR " + t.javaClass.simpleName)
            }
            try { recomposer?.close() } catch (_: Throwable) {}
            try { scope?.cancel() } catch (_: Throwable) {}
            try { owner.stop() } catch (t: Throwable) {
                XposedBridge.log("KYANT2885_DISPOSE_OWNER_ERROR " + t.javaClass.simpleName)
            }
            XposedBridge.log("KYANT2885_RENDER_DIAG host=" + System.identityHashCode(anchor) +
                " drawIntervals=" + drawIntervals + " over16ms=" + drawGapOver16ms +
                " over33ms=" + drawGapOver33ms + " maxDrawGapMs=" + maxDrawGapMs +
                " sampleCommits=" + sampleCommitCount +
                " avgCommitToDrawMs=" + (if (commitToDrawSamples > 0L) commitToDrawTotalMs / commitToDrawSamples else -1L) +
                " activeHosts=" + activeHostCount.get())
            XposedBridge.log("KYANT2885_PERF frames=" + frameChecks + " geometryChanges=" + geometryChanges +
                " positionChanges=" + positionChanges + " offscreenChecks=" + offscreenChecks +
                " offscreenSkippedUpdates=" + skippedPositionUpdates + " maxPredrawUs=" + maxPredrawUs +
                " avgPredrawUs=" + (if (frameChecks > 0) totalPredrawUs / frameChecks else 0L))
            XposedBridge.log("KYANT2885_DISPOSE_CHECK reason=" + reason +
                " composition=" + compositionSeen.get() +
                " draw=" + drawSeen.get() +
                " removed=" + (frame.parent == null) +
                " elapsedMs=" + (SystemClock.uptimeMillis() - startedAt))
            XposedBridge.log("KYANT2885_HOST_DISPOSED reason=" + reason)
        }
        try {
            owner.start()
            // Explicit parent CompositionContext avoids DecorView's missing LifecycleOwner.
            // AndroidUiDispatcher.Main supplies Android's UI frame clock.
            val dispatcher = AndroidUiDispatcher.Main
            val exceptionHandler = CoroutineExceptionHandler { _, t ->
                XposedBridge.log("KYANT2885_RECOMPOSER_ERROR " + t.javaClass.simpleName + " " + (t.message ?: ""))
                frame.post {
                    if (selectedEngine28888 == 0) {
                        try { Settings.Global.putInt(anchor.context.contentResolver, KEY, 0) } catch (_: Throwable) {}
                    }
                    dispose("recomposer_error")
                }
            }
            val newScope = CoroutineScope(dispatcher + SupervisorJob() + exceptionHandler)
            scope = newScope
            val newRecomposer = Recomposer(newScope.coroutineContext)
            recomposer = newRecomposer
            compose.setParentCompositionContext(newRecomposer)
            bridgeView?.let { frame.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
            // V288.84 isolates child z-order, holding native canvas content fixed.
            // 0 native on top; 1 native below hidden Compose; 2 native on top of
            // empty Compose; 3 native below GONE Compose; 4 native below Kyant.
            val nativeBelowCompose28884 = if (v230NativeMode28887 != 0) false
                else if (stateActive28885) true
                else if (orderPipeline28884 >= 0)
                orderPipeline28884 == 1 || orderPipeline28884 == 3 || orderPipeline28884 == 4
                else nativeLayerMode in 2..3 || siblingNative28883
            if (!overlayActive28876 && nativeBelowCompose28884) nativeSampleView?.let { frame.addView(it,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
            frame.addView(compose, FrameLayout.LayoutParams(previewWidth, previewHeight))
            // Mode 1 is a control: native fully covers Compose, same as V288.11 A.
            if (!overlayActive28876 && !nativeBelowCompose28884) nativeSampleView?.let { frame.addView(it,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }

            // V288.78: root hosts would otherwise cover OEM folder icons.
            // Draw the existing FolderIcon's *child Views* above the glass,
            // without drawing the FolderIcon's own opaque background. This is
            // a diagnostic forwarding layer; turn it off to isolate draw costs.
            if (fullOverlayActive28877 && rootIconPassthrough28878) {
                val iconForward = object : View(anchor.context) {
                    private var count = 0L
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        if (disposed || !anchor.isAttachedToWindow) return
                        try {
                            for (i in 0 until folder.childCount) {
                                val child = folder.getChildAt(i)
                                if (child === frame || child.visibility != View.VISIBLE ||
                                    child.width <= 0 || child.height <= 0) continue
                                val save = canvas.save()
                                canvas.translate((child.left - lastBounds.left).toFloat(),
                                    (child.top - lastBounds.top).toFloat())
                                canvas.concat(child.matrix)
                                child.draw(canvas)
                                canvas.restoreToCount(save)
                            }
                            count++
                            if (rootCompareTrace28878 && count % 180L == 1L)
                                XposedBridge.log("KYANT28878_ICON_FORWARD host=$trace28815Host " +
                                    "draw=$count children=${folder.childCount} frameParent=${frame.parent?.javaClass?.simpleName}")
                        } catch (error: Throwable) {
                            if (count++ % 120L == 0L)
                                XposedBridge.log("KYANT28878_ICON_ERROR host=$trace28815Host " +
                                    error.javaClass.simpleName)
                        }
                    }
                }.apply { isClickable = false; isFocusable = false; setWillNotDraw(false) }
                frame.addView(iconForward, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            if (siblingPipeline28883 >= 0 && siblingTrace28883) XposedBridge.log(
                "KYANT28883_SIBLING_HOST host=$trace28815Host stage=$siblingPipeline28883 " +
                "root=$fullOverlayActive28877 native=${nativeSampleView != null} " +
                "composeAlpha=${compose.alpha} frameChildren=${frame.childCount} " +
                "nativeParent=${nativeSampleView?.parent?.javaClass?.simpleName} " +
                "composeParent=${compose.parent?.javaClass?.simpleName}")
            if (positionPipeline28882 >= 0 && positionTrace28882) XposedBridge.log(
                "KYANT28882_POSITION_HOST host=$trace28815Host stage=$positionPipeline28882 " +
                "root=$fullOverlayActive28877 native=$rootDirect28878 " +
                "compose=$rootFullKyant28878 bridge=${bridgeBitmap != null}")
            if (nodePipeline28881 >= 0 && nodeTrace28881) XposedBridge.log(
                "KYANT28881_NODE_HOST host=$trace28815Host node=$nodePipeline28881 " +
                "native=$rootDirect28878 compose=$rootFullKyant28878 " +
                "root=$fullOverlayActive28877 viewLayer=${compose.layerType}")
            if (bitmapPipeline28880 >= 0) XposedBridge.log(
                "KYANT28880_BITMAP_HOST host=$trace28815Host stage=$bitmapPipeline28880 " +
                "native=$rootDirect28878 compose=$rootFullKyant28878 " +
                "bridge=${bridgeBitmap != null} root=$fullOverlayActive28877")
            if (rootPipeline28879 >= 0 || rootPipelineTrace28879) XposedBridge.log(
                "KYANT28879_PIPELINE_HOST host=$trace28815Host stage=$rootPipeline28879 " +
                "native=$rootDirect28878 compose=$rootFullKyant28878 " +
                "root=$fullOverlayActive28877 bridge=${bridgeBitmap != null} " +
                "iconForward=${fullOverlayActive28877 && rootIconPassthrough28878} " +
                "rootParent=${frame.parent?.javaClass?.simpleName}")
            if (rootCompareTrace28878 || rootCompareAb28878 != 0) XposedBridge.log(
                "KYANT28878_RENDER_HOST host=$trace28815Host mode=$rootCompareAb28878 " +
                "root=$fullOverlayActive28877 native=$rootDirect28878 kyant=$rootFullKyant28878 " +
                "iconForward=${fullOverlayActive28877 && rootIconPassthrough28878} " +
                "nativeView=${nativeSampleView != null} composeAlpha=${compose.alpha}")

            if (overlayActive28876 && nativeSampleView != null) try {
                nativeSampleView.layout(0, 0, previewWidth, previewHeight)
                overlayParent28876?.overlay?.add(nativeSampleView)
                overlayInstalled28876 = true
                positionOverlay28876(glassScreenPosition(lastBounds))
                XposedBridge.log("KYANT28876_OVERLAY_ATTACHED host=$trace28815Host " +
                    "width=$previewWidth height=$previewHeight")
            } catch (t: Throwable) {
                XposedBridge.log("KYANT28876_OVERLAY_ERROR ${t.javaClass.simpleName}")
            }
            compose.setContent {
                if (v230NativeMode28887 != 0 ||
                    (stateActive28885 && statePipeline28885 in 0..3) ||
                    (orderPipeline28884 >= 0 && orderPipeline28884 in 1..3 && !stateActive28885) ||
                    (orderPipeline28884 < 0 && (siblingPipeline28883 == 2 || siblingPipeline28883 == 3))) {
                    // No optical composition is invoked in these modes.
                    // Mode2 keeps an invisible ComposeView, mode3 leaves an
                    // empty transparent composition on top of native pixels.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize())
                    if (compositionSeen.compareAndSet(false, true)) {
                        if (orderPipeline28884 >= 0 && orderTrace28884)
                            XposedBridge.log("KYANT28884_COMPOSITION_EMPTY host=$trace28815Host mode=$orderPipeline28884")
                        XposedBridge.log("KYANT28883_COMPOSITION_EMPTY host=$trace28815Host " +
                            "stage=$siblingPipeline28883")
                        if (v230NativeMode28887 != 0) XposedBridge.log(
                            "KYANT28888_COMPOSE_OPTICS_SKIPPED host=$trace28815Host " +
                            "nativeMode=$v230NativeMode28887 alpha=${compose.alpha}")
                    }
                } else {
                LensContent270(referenceState28832.value, sampleWidth.intValue, sampleHeight.intValue,
                displayWidth, displayHeight, samplePosition, sampleRadiusPx.floatValue, effectiveSamplingMode28862,
                effectiveLargeContinuous28862, effectiveRenderMode28862, motionAbMode, legacySyncAbMode, motionCompare28861, layerSampleEffective28862, if (nativeLayerMode in 2..3) 3 else displayIsolationMode, nativeLayerMode, bridgeBitmap, bridgeVersion,
                gpuReference288110,
                if (nativeCarrierAb28869 == 1) 2 else if (displayIsolationMode in 1..3) 0 else bridgeSyncMode,
                pipelineMode28819, frameSync28822,
                bridgePublishedPosition, bridgeMismatchObserved, { pos -> sampleBridgeAt(pos) }, predrawMatrixSnapshot,
                reboundTrace28890, trace28815Host, perfProfile288109,
                predrawSample28890, bridgeSample28890,
                { glassScreenPosition(lastBounds) },
                sourceMatrixAb28866, { composeViewScreenPosition28866() },
                zeroLensAb28868, continuousMotionProbe28868, continuousRun28868,
                rootPipeline28879, rootPipelineTrace28879,
                bitmapPipeline28880, bitmapTrace28880, immutableBridge28880, immutableGen28880,
                if (stateActive28885 || orderPipeline28884 >= 0) -1 else if (siblingPipeline28883 == 1) 2 else nodePipeline28881, nodeTrace28881,
                positionPipeline28882, positionTrace28882,
                { nativeCarrierDrawPosition28876(frame, glassScreenPosition(lastBounds)) },
                nativeCarrierAb28869, distanceAb28874, distanceTrace28874,
                bufferExperiment28875, bridgeFront28875, bridgeFrontGen28875, bridgeFrontPosition28875,
                reboundFrameAb28891, reboundFrame28891, gestureIsolation28894, continuityAb28895,
                fusionMode28889 != 1 && !affineSampling28876,
                coordinateTrace28892, bridgeCoordinate28892, timelineTrace28893, dualMotionTrace28896, finalSampleTrace28897, sampleSyncAb28898, visibleGestureTrace28899, snapshotTrace288100, bridgePairAb288101, bridgePairTrace288101, bridgePairFrame288101, timingTraceEnabled288102, scrollTraceEnabled288103, coordinateBasisTrace288104, anchor, timings288102,
                uvProbe28867, { frame.visibility == View.VISIBLE &&
                    frame.isShown && frame.width > 0 && frame.height > 0 &&
                    glassScreenPosition(lastBounds).let { pos ->
                        pos.x + previewWidth > 0f && pos.y + previewHeight > 0f &&
                        pos.x < displayWidth && pos.y < displayHeight } },
                { nativePosition28866, sourcePosition28866 ->
                    val dx = kotlin.math.abs(nativePosition28866.x - sourcePosition28866.x)
                    val dy = kotlin.math.abs(nativePosition28866.y - sourcePosition28866.y)
                    sourceMatrixCounts28866[0]++
                    sourceMatrixStats28866[0] = maxOf(sourceMatrixStats28866[0], dx)
                    sourceMatrixStats28866[1] = maxOf(sourceMatrixStats28866[1], dy)
                    sourceMatrixStats28866[2] += maxOf(dx, dy)
                    if (maxOf(dx, dy) > 1f) sourceMatrixCounts28866[2]++
                    val now66 = SystemClock.elapsedRealtimeNanos()
                    if (now66 - sourceMatrixWindowNs28866 >= 1_000_000_000L) {
                        XposedBridge.log("KYANT28866_SOURCE_MATRIX host=${System.identityHashCode(anchor)} " +
                            "mode=$sourceMatrixAb28866 samples=${sourceMatrixCounts28866[0]} " +
                            "invalid=${sourceMatrixCounts28866[1]} over1px=${sourceMatrixCounts28866[2]} " +
                            "maxDxPx=${sourceMatrixStats28866[0]} maxDyPx=${sourceMatrixStats28866[1]} " +
                            "meanGapPx=${sourceMatrixStats28866[2] / sourceMatrixCounts28866[0].coerceAtLeast(1L)} " +
                            "sourceX=${sourcePosition28866.x} anchorX=${nativePosition28866.x}")
                        sourceMatrixStats28866.fill(0f)
                        sourceMatrixCounts28866.fill(0L)
                        sourceMatrixWindowNs28866 = now66
                    }
                }, lifecycleProbe28864, drawProbe28865,
                sourceDrawSerial28864, sourceDrawPosition28864,
                { stage, pos, info -> releaseTrace28820(stage, pos, info) }) {
                if (compositionSeen.compareAndSet(false, true)) {
                    XposedBridge.log("KYANT2885_COMPOSITION_CONFIRMED elapsedMs=" +
                        (SystemClock.uptimeMillis() - startedAt))
                }
            } }
            }
            newScope.launch { newRecomposer.runRecomposeAndApplyChanges() }
            // V288.77.1: full Kyant + bitmap bridge as a NORMAL root child,
            // not ViewGroupOverlay. Root hierarchy should provide a regular
            // Compose/Backdrop rendering context while decoupling from Workspace.
            // On unexpected OEM ViewGroup rejection, safely fall back to the
            // known-good FolderIcon child instead of leaving glass disabled.
            if (fullOverlayActive28877) {
                try {
                    frame.layoutParams = ViewGroup.LayoutParams(previewWidth, previewHeight)
                    fullOverlayParent28877!!.addView(frame, ViewGroup.LayoutParams(previewWidth, previewHeight))
                    frame.layout(0, 0, previewWidth, previewHeight)
                    fullOverlayInstalled28877 = true
                    positionFullOverlay28877(glassScreenPosition(lastBounds), frame)
                    if (stateActive28885 && stateTrace28885) XposedBridge.log(
                        "KYANT28885_STATE_ATTACHED host=$trace28815Host mode=$statePipeline28885 " +
                        "nativeIndex=${nativeSampleView?.let { frame.indexOfChild(it) } ?: -1} " +
                        "composeIndex=${frame.indexOfChild(compose)} " +
                        "composeAlpha=${compose.alpha} composeVisibility=${compose.visibility} " +
                        "frameChildren=${frame.childCount} rootParent=${frame.parent?.javaClass?.simpleName}")
                    if (orderPipeline28884 >= 0 && orderTrace28884 && !stateActive28885) XposedBridge.log(
                        "KYANT28884_ORDER_ATTACHED host=$trace28815Host mode=$orderPipeline28884 " +
                        "nativeIndex=${nativeSampleView?.let { frame.indexOfChild(it) } ?: -1} " +
                        "composeIndex=${frame.indexOfChild(compose)} " +
                        "composeAlpha=${compose.alpha} composeVisibility=${compose.visibility} " +
                        "frameChildren=${frame.childCount} rootParent=${frame.parent?.javaClass?.simpleName}")
                    if (siblingPipeline28883 >= 0 && siblingTrace28883) XposedBridge.log(
                        "KYANT28883_SIBLING_ATTACHED host=$trace28815Host " +
                        "stage=$siblingPipeline28883 " +
                        "nativeIndex=${nativeSampleView?.let { frame.indexOfChild(it) } ?: -1} " +
                        "composeIndex=${frame.indexOfChild(compose)} " +
                        "composeAlpha=${compose.alpha} rootChild=true")
                    XposedBridge.log("KYANT288771_ROOT_CHILD_ATTACHED host=$trace28815Host " +
                        "width=$previewWidth height=$previewHeight " +
                        "parent=${frame.parent?.javaClass?.name} " +
                        "carrier=$nativeCarrierAb28869 lens=full " +
                        "composeAlpha=${compose.alpha} rootChildCount=${fullOverlayParent28877!!.childCount}")
                } catch (attachError: Throwable) {
                    XposedBridge.log("KYANT288771_ROOT_CHILD_ERROR host=$trace28815Host " +
                        "error=${attachError.javaClass.simpleName} ${attachError.message}")
                    try { (frame.parent as? ViewGroup)?.removeView(frame) } catch (_: Throwable) {}
                    fullOverlayInstalled28877 = false
                    frame.translationX = 0f
                    frame.translationY = 0f
                    folder.addView(frame, 0, ViewGroup.LayoutParams(previewWidth, previewHeight))
                    XposedBridge.log("KYANT288771_FALLBACK_FOLDER host=$trace28815Host")
                }
            } else {
                // V288.86: never cover OEM icons / Recents with a DecorView child.
                // The host's lifetime and inherited transforms now follow FolderIcon.
                folder.addView(frame, 0, ViewGroup.LayoutParams(previewWidth, previewHeight))
                if (folderContainment28886) XposedBridge.log(
                    "KYANT28886_FOLDER_ATTACHED host=$trace28815Host " +
                    "parent=${frame.parent?.javaClass?.simpleName} " +
                    "index=${folder.indexOfChild(frame)} " +
                    "root=${frame.parent === root} " +
                    "nativeIconChildren=${(folder.childCount - 1).coerceAtLeast(0)}")
            }
            val onDetach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    dispose("folder_detached")
                }
            }
            detachListener = onDetach
            anchor.addOnAttachStateChangeListener(onDetach)
            val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!disposed) {
                    if (largeSquare28829) largeGeometryDirty28829 = true
                    // Native layout may overwrite injected child's local placement.
                    // Schedule one frame after layout so the pre-draw reconciliation runs
                    // even when the user does not touch the Launcher.
                    anchor.postInvalidateOnAnimation()
                    frame.postInvalidateOnAnimation()
                }
            }
            layoutListener = onLayout
            anchor.addOnLayoutChangeListener(onLayout)
            if (!fullOverlayInstalled28877) frame.layout(initialBounds.left, initialBounds.top,
                initialBounds.left + previewWidth, initialBounds.top + previewHeight)
            else frame.layout(0, 0, previewWidth, previewHeight)
            // Mark old glass inactive only AFTER the Kyant child is successfully attached.
            KyantLauncherLensProbe270.setActiveFor273(anchor, true)
            activeHostCount.incrementAndGet()
            activeCountRegistered = true
            if (perfProfile288109) KyantPerf288109.register(trace28815Host,
                bridgeBitmap != null, previewWidth.toLong() * previewHeight.toLong())
            KyantLauncherFrameBridge266.addReferenceListener(referenceListener28832)
            // Close the attach/publication race: a newer frame may have arrived.
            KyantLauncherFrameBridge266.peekReferenceFrame270()?.let {
                referenceListener28832.onReferenceFrameReady(it, KyantLauncherFrameBridge266.peekGeneration270())
            }
            XposedBridge.log("KYANT2885_MUTUAL_EXCLUSION active=true layer=below_icons fallback=automatic_on_dispose")
            var lastX = anchorXY[0]
            var lastY = anchorXY[1]
            // V288.5: frame cadence counters; no changes to sampling or optical drawing.
        var cadenceWindowNs = SystemClock.elapsedRealtimeNanos()
        var cadencePredraw = 0L
        var cadenceMoving = 0L
        var cadenceCommits = 0L
        var cadenceLongestGapNs = 0L
        var cadenceLastNs = 0L
        var cadenceStartX = Float.NaN
        var cadenceEndX = Float.NaN
        // V288.84 bounded diagnostics. No finger-up callback is hooked;
        // near-settle is inferred from the position delta only.
        var orderLastX28884 = Float.NaN
        var orderLastDx28884 = 0f
        var orderLogNs28884 = 0L
        var orderEventCount28884 = 0
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                if (!disposed && anchor.isAttachedToWindow) {
                    // The switch is intentionally checked during drawing, not on a fixed timer.
                    val nextEngine28888 = KyantLauncherLensProbe270.selectedEngine28888(anchor.context)
                    if (nextEngine28888 != selectedEngine28888) {
                        XposedBridge.log("KYANT28888_ENGINE_STOP host=$trace28815Host " +
                            "previous=$selectedEngine28888 next=$nextEngine28888")
                        frame.post { dispose("engine_changed") }
                        return@OnPreDrawListener true
                    }
                    // Fallback, like Dock, if notification is still queued.
                    KyantLauncherFrameBridge266.peekReferenceFrame270()?.let {
                        if (it !== referenceState28832.value) {
                            referenceListener28832.onReferenceFrameReady(it, KyantLauncherFrameBridge266.peekGeneration270())
                        }
                    }
                    frameChecks++
                    val cadenceNow = SystemClock.elapsedRealtimeNanos()
                    cadencePredraw++
                    if (cadenceLastNs > 0L) cadenceLongestGapNs = maxOf(cadenceLongestGapNs, cadenceNow - cadenceLastNs)
                    cadenceLastNs = cadenceNow
                    // Observe ColorOS drag overlay class without modifying its view hierarchy.
                    // DragView is commonly a separate bitmap snapshot; original FolderIcon
                    // cannot provide real-time sampling while that snapshot is being dragged.
                    if ((frameTrace28815 || layerTrace28817) &&
                        frameChecks % 30L == 0L && dragDiagnosticCount < 12L) {
                        try {
                            val group = root as? ViewGroup
                            if (group != null) {
                                val queue = java.util.ArrayDeque<View>()
                                queue.add(group)
                                var examined = 0
                                while (!queue.isEmpty() && examined++ < 200) {
                                    val candidate = queue.removeFirst()
                                    val className = candidate.javaClass.name
                                    if (className.contains("DragView", ignoreCase = true) &&
                                        candidate.visibility == View.VISIBLE) {
                                        dragDiagnosticCount++
                                        XposedBridge.log("KYANT2885_DRAG_VIEW_FOUND class=" + className +
                                            " x=" + candidate.x + " y=" + candidate.y +
                                            " width=" + candidate.width + " height=" + candidate.height)
                                        break
                                    }
                                    if (candidate is ViewGroup) {
                                        for (i in 0 until candidate.childCount) queue.add(candidate.getChildAt(i))
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("KYANT2885_DRAG_DIAG_ERROR " + t.javaClass.simpleName)
                        }
                    }
                    val predrawStartedNs = SystemClock.elapsedRealtimeNanos()
                    // Native FolderIcon child already inherits Workspace translation.
                    // V286.2 also corrects OEM child relayout with no anchor movement.
                    // Screen coordinates are required only when position changes, and do
                    // not need to trigger recomposition for a stationary desktop.
                    anchor.getLocationOnScreen(locationScratch)
                    val xy = locationScratch
                    val actualSample = glassScreenPosition(lastBounds)
                    if (orderPipeline28884 >= 0 && orderTrace28884 && fullOverlayInstalled28877) {
                        val px84 = orderLastX28884
                        val dx84 = if (px84.isFinite()) actualSample.x - px84 else 0f
                        val signChange84 = px84.isFinite() && kotlin.math.abs(dx84) > 0.5f &&
                            kotlin.math.abs(orderLastDx28884) > 0.5f && dx84 * orderLastDx28884 < 0f
                        val nearSettle84 = kotlin.math.abs(orderLastDx28884) >= 2f &&
                            kotlin.math.abs(dx84) <= 0.5f
                        val burst84 = px84.isFinite() && kotlin.math.abs(dx84) >= 16f
                        val now84 = SystemClock.elapsedRealtimeNanos()
                        // Limit to <= 10 records/second per host and <= 140 total.
                        if ((signChange84 || nearSettle84 || burst84) &&
                            orderEventCount28884 < 140 && now84 - orderLogNs28884 >= 100_000_000L) {
                            orderLogNs28884 = now84
                            orderEventCount28884++
                            XposedBridge.log("KYANT28884_ORDER_MOTION host=$trace28815Host " +
                                "mode=$orderPipeline28884 event=${if (signChange84) "reverse" else if (nearSettle84) "settle" else "move"} " +
                                "x=${actualSample.x} dx=$dx84 lastDx=$orderLastDx28884 " +
                                "frameTx=${frame.translationX} alpha=${compose.alpha} " +
                                "visibility=${compose.visibility} nativeIndex=${nativeSampleView?.let { frame.indexOfChild(it) } ?: -1} " +
                                "composeIndex=${frame.indexOfChild(compose)}")
                        }
                        orderLastX28884 = actualSample.x
                        orderLastDx28884 = dx84
                    }
                    if (overlayInstalled28876) positionOverlay28876(actualSample)
                    if (fullOverlayInstalled28877) {
                        if (stateActive28885) {
                            state85PendingX = actualSample.x
                            state85PendingNs = SystemClock.elapsedRealtimeNanos()
                        }
                        positionFullOverlay28877(actualSample, frame)
                        if (stateActive28885) {
                            state85CommitX = frame.translationX
                            if (stateTrace28885 && state85LogCount < 90) {
                                val ns85 = SystemClock.elapsedRealtimeNanos()
                                if (ns85 - state85LogNs >= 120_000_000L) {
                                    state85LogNs = ns85
                                    state85LogCount++
                                    XposedBridge.log("KYANT28885_COMMIT host=$trace28815Host mode=$statePipeline28885 " +
                                        "targetX=${actualSample.x} frameTx=${frame.translationX} " +
                                        "rootX=${fullOverlayRootLocation28877[0]} composeAlpha=${compose.alpha} " +
                                        "composeVisibility=${compose.visibility} nativeIndex=${nativeSampleView?.let { frame.indexOfChild(it) } ?: -1} " +
                                        "composeIndex=${frame.indexOfChild(compose)}")
                                }
                            }
                        }
                        if (rootCompareTrace28878 && rootCompareTraceCount28878++ % 120L == 0L)
                            XposedBridge.log("KYANT28878_ROOT_SAMPLE host=$trace28815Host " +
                                "mode=$rootCompareAb28878 x=${actualSample.x} y=${actualSample.y} " +
                                "hostTx=${frame.translationX} rootChild=${frame.parent === fullOverlayParent28877}")
                    }
                    distancePredraw28874(actualSample)
                    traceDistance28875(actualSample)
                    nativePredrawPosition28824 = actualSample
                    nativePredrawSerial28824++
                    if (frameTrace28873) {
                        tracePreSerial28873++
                        tracePreNs28873 = SystemClock.elapsedRealtimeNanos()
                        tracePrePosition28873 = actualSample
                    }
                    releaseTrace28820("PREDRAW", actualSample,
                        "committedX=${samplePosition.value.x} committedY=${samplePosition.value.y} frameX=${frame.x} frameTx=${frame.translationX}")
                    // V288.14: compare sample, anchor and host in the same PreDraw.
                    // Diagnostic only: no changes to wallpaper sampling or transforms.
                    // Ignore only transform-matrix numerical noise (< 1/64 px).
                    // Keep real fractional Workspace movement live.
                    val sampleDelta = kotlin.math.abs(actualSample.x - lastSample.x) +
                        kotlin.math.abs(actualSample.y - lastSample.y)
                    // V288.18: detect a rightward drag followed by leftward spring-back.
                    // The comparison is in screen space, not Workspace scroll coordinates.
                    val reboundNow28818 = SystemClock.elapsedRealtimeNanos()
                    if (pipelineMode28819 < 0 && anchor.isShown) {
                        val reboundDx28818 = if (rebound28818LastX.isFinite()) actualSample.x - rebound28818LastX else 0f
                        if (reboundDx28818 > 0.5f) {
                            rebound28818RightDrag = true
                            rebound28818LastPositiveNs = reboundNow28818
                        } else if (reboundDx28818 < -0.5f && rebound28818RightDrag) {
                            val ageMs = (reboundNow28818 - rebound28818LastPositiveNs) / 1000000L
                            if (ageMs in 0..800 && rebound28818Count < 6) {
                                rebound28818Count++
                                XposedBridge.log("KYANT28818_REVERSAL host=$trace28815Host mode=$reboundMode28818 dx=$reboundDx28818 x=${actualSample.x} ageMs=$ageMs")
                                if (reboundMode28818 == 1 && reboundLayerGuard28818.compareAndSet(false, true)) {
                                    var cell28818: View? = anchor.parent as? View
                                    var hops28818 = 0
                                    while (cell28818 != null && hops28818++ < 6 && !cell28818.javaClass.name.contains("CellLayout", ignoreCase = true)) cell28818 = cell28818.parent as? View
                                    if (cell28818 != null && cell28818.layerType == View.LAYER_TYPE_HARDWARE) {
                                        try {
                                            rebound28818Target = cell28818
                                            rebound28818OldLayer = cell28818.layerType
                                            cell28818.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                            XposedBridge.log("KYANT28818_LAYER_TEST host=$trace28815Host old=$rebound28818OldLayer new=${cell28818.layerType} class=${cell28818.javaClass.simpleName}")
                                            val restore28818 = Runnable { restoreRebound28818("timeout") }
                                            rebound28818Restore = restore28818
                                            cell28818.postDelayed(restore28818, 220L)
                                        } catch (t: Throwable) {
                                            XposedBridge.log("KYANT28818_LAYER_ERROR ${t.javaClass.simpleName}")
                                            restoreRebound28818("error")
                                        }
                                    } else {
                                        XposedBridge.log("KYANT28818_SKIP host=$trace28815Host reason=no_hardware_cell layer=${cell28818?.layerType}")
                                        reboundLayerGuard28818.set(false)
                                    }
                                }
                            }
                            rebound28818RightDrag = false
                        }
                        if (rebound28818RightDrag && (reboundNow28818 - rebound28818LastPositiveNs) > 800000000L) rebound28818RightDrag = false
                        rebound28818LastX = actualSample.x
                    }
                    val moved = xy[0] != lastX || xy[1] != lastY ||
                        sampleDelta > (1f / 64f)
                    if (continuousMotionProbe28868) {
                        // Use the SAME visibility and screen-intersection constraints
                        // as the source. Offscreen and stationary periods terminate a run.
                        val visibleNow68 = frame.visibility == View.VISIBLE && anchor.isShown &&
                            frame.width > 0 && frame.height > 0 &&
                            actualSample.x + previewWidth > 0f && actualSample.y + previewHeight > 0f &&
                            actualSample.x < displayWidth && actualSample.y < displayHeight
                        if (moved && visibleNow68) {
                            if (lastMovingPredrawNs28868 > 0L) {
                                val dt68 = cadenceNow - lastMovingPredrawNs28868
                                maxMovingPredrawGapNs28868 = maxOf(maxMovingPredrawGapNs28868, dt68)
                                if (dt68 > 100_000_000L || currentRunStartNs28868 == 0L) {
                                    currentRunStartNs28868 = cadenceNow
                                    continuousBreaks28868++
                                }
                            } else currentRunStartNs28868 = cadenceNow
                            lastMovingPredrawNs28868 = cadenceNow
                            continuousRun28868.set(Pair(cadenceNow, currentRunStartNs28868))
                        } else {
                            lastMovingPredrawNs28868 = 0L
                            currentRunStartNs28868 = 0L
                            continuousRun28868.set(Pair(0L, 0L))
                        }
                        if (cadenceNow - continuousWindowNs28868 >= 1_000_000_000L) {
                            if (continuousBreaks28868 > 0L) XposedBridge.log(
                                "KYANT28868_PREDRAW_RUN host=${System.identityHashCode(anchor)} " +
                                "runBreaks=$continuousBreaks28868 " +
                                "maxMovingPredrawGapMs=${maxMovingPredrawGapNs28868 / 1_000_000f}")
                            continuousWindowNs28868 = cadenceNow
                            continuousBreaks28868 = 0L
                            maxMovingPredrawGapNs28868 = 0L
                        }
                    }
                    if (moved) {
                        positionChanges++
                        // Native invalidation only. No Compose snapshot writes in draw.
                        nativeSampleView?.postInvalidateOnAnimation()
                        bridgeView?.postInvalidateOnAnimation()
                    }
                    // Background geometry is local to FolderIcon; skip expensive reflection
                    // while only the Workspace scrolls, but recheck periodically for OEM resize.
                    // A reparented/moved folder can change its local geometry as well as its global transform.
                    val mustResolveGeometry28829 = if (largeSquare28829) {
                        frame.visibility == View.INVISIBLE || largeGeometryDirty28829 ||
                            frameChecks % 8L == 0L
                    } else frame.visibility == View.INVISIBLE || moved || frameChecks % 20L == 0L
                    val b = if (mustResolveGeometry28829)
                        FolderGlassGeometry272.resolve(anchor) else lastBounds
                    if (largeSquare28829 && mustResolveGeometry28829) largeGeometryDirty28829 = false
                    val boundsChangedNow = b != lastBounds
                    // On large squares use ONE immutable screen position for
                    // state commit + native snapshot within this pre-draw pass.
                    // If OEM local geometry changed, calculate it once for b.
                    val largeFrameSample28829 = if (largeSquare28829) {
                        if (boundsChangedNow) glassScreenPosition(b) else actualSample
                    } else null
                    // OEM FolderIcon can relayout an injected child after Launcher restores
                    // its workspace. Detect that independent of anchor motion or sampling.
                    val expectedW = b.width().coerceAtLeast(1)
                    val expectedH = b.height().coerceAtLeast(1)
                    // Read ancestor Workspace transform only for diagnosis; this must not
                    // become a second translation applied to the child host.
                    var workspace: View? = anchor.parent as? View
                    var depth = 0
                    while (workspace != null && depth++ < 12 &&
                        !workspace.javaClass.name.contains("Workspace", ignoreCase = true)) {
                        workspace = workspace.parent as? View
                    }
                    val workspaceScrollX = workspace?.scrollX ?: Int.MIN_VALUE
                    val workspaceTranslationX = workspace?.translationX ?: Float.NaN
                    val workspaceMoved = workspaceScrollX != lastWorkspaceScrollX ||
                        workspaceTranslationX != lastWorkspaceTranslationX
                    lastWorkspaceScrollX = workspaceScrollX
                    lastWorkspaceTranslationX = workspaceTranslationX
                    val hostMisplaced = if (fullOverlayInstalled28877) {
                        frame.left != 0 || frame.top != 0 ||
                            frame.width != expectedW || frame.height != expectedH
                    } else {
                        frame.left != b.left || frame.top != b.top ||
                            frame.width != expectedW || frame.height != expectedH ||
                            frame.translationX != 0f || frame.translationY != 0f
                    }
                    // Full visibility test: partially visible folders must continue sampling.
                    val visible = xy[0] + b.right > 0 && xy[0] + b.left < displayWidth &&
                        xy[1] + b.bottom > 0 && xy[1] + b.top < displayHeight
                    if (!visible) offscreenChecks++
                    if ((frameTrace28815 || layerTrace28817) && visible && trace28815Events < 160) {
                        val now28815 = SystemClock.elapsedRealtimeNanos()
                        trace28815PreSerial++
                        val dx28815 = if (trace28815LastPreX.isFinite())
                            actualSample.x - trace28815LastPreX else 0f
                        val gap28815 = if (trace28815LastPreNs > 0L)
                            (now28815 - trace28815LastPreNs) / 1000000L else -1L
                        if (kotlin.math.abs(dx28815) < 0.15f) trace28815Quiet++
                        else {
                            if (trace28815Quiet >= 3) {
                                trace28815Direction = if (dx28815 > 0f) 1 else -1
                                trace28815Remaining = 12
                                if (layerTrace28817 && layerTrace28817Count < 120) {
                                    layerTrace28817Direction = trace28815Direction
                                    layerTrace28817Remaining = 8
                                    val hostLoc = IntArray(2); val nativeLoc = IntArray(2)
                                    frame.getLocationOnScreen(hostLoc)
                                    nativeSampleView?.getLocationOnScreen(nativeLoc)
                                    XposedBridge.log("KYANT28817_ONSET_LAYER host=$trace28815Host dir=$layerTrace28817Direction dx=$dx28815 sampleX=${actualSample.x} hostX=${hostLoc[0]} nativeX=${nativeLoc[0]} frameLayer=${frame.layerType} nativeLayer=${nativeSampleView?.layerType} frameTx=${frame.translationX} nativeTx=${nativeSampleView?.translationX} frameAlpha=${frame.alpha} nativeAlpha=${nativeSampleView?.alpha} frameVisible=${frame.visibility} nativeVisible=${nativeSampleView?.visibility} frameAttached=${frame.isAttachedToWindow} nativeAttached=${nativeSampleView?.isAttachedToWindow}")
                                    var ancestor28817: View? = anchor
                                    var depth28817 = 0
                                    while (ancestor28817 != null && depth28817 < 7) {
                                        val loc28817 = IntArray(2)
                                        ancestor28817.getLocationOnScreen(loc28817)
                                        XposedBridge.log("KYANT28817_ANCESTOR host=$trace28815Host dir=$layerTrace28817Direction depth=$depth28817 class=${ancestor28817.javaClass.simpleName} x=${loc28817[0]} tx=${ancestor28817.translationX} scrollX=${ancestor28817.scrollX} layer=${ancestor28817.layerType} alpha=${ancestor28817.alpha} clip=${(ancestor28817 as? ViewGroup)?.clipChildren} attached=${ancestor28817.isAttachedToWindow}")
                                        ancestor28817 = ancestor28817.parent as? View
                                        depth28817++
                                    }
                                    layerTrace28817Count += depth28817 + 1
                                }
                                if (trace28815Direction > 0 && redrawMode28816 == 1 &&
                                    redraw28816Requests < 24 && nativeSampleView != null) {
                                    redraw28816Requests++
                                    redraw28816Pending = true
                                    redraw28816PendingNs = now28815
                                    redraw28816PendingX = actualSample.x
                                    // One-shot request on rightward motion onset only.
                                    // Do not request invalidate from onDraw or every PreDraw.
                                    nativeSampleView.postInvalidateOnAnimation()
                                    XposedBridge.log("KYANT28816_REDRAW_REQUEST host=$trace28815Host request=$redraw28816Requests pre=$trace28815PreSerial x=${actualSample.x} dx=$dx28815")
                                }
                                XposedBridge.log(
                                    "KYANT28815_MOTION_ONSET host=$trace28815Host dir=$trace28815Direction dx=$dx28815 x=${actualSample.x} predraw=$trace28815PreSerial preGapMs=$gap28815 scrollX=$workspaceScrollX frameLayer=${frame.layerType} nativeLayer=${nativeSampleView?.layerType} sourceId=${System.identityHashCode(reference)}")
                                trace28815Events++
                            }
                            trace28815Quiet = 0
                        }
                        if (trace28815Remaining > 0 && trace28815Events < 160) {
                            XposedBridge.log(
                                "KYANT28815_PREDRAW host=$trace28815Host dir=$trace28815Direction serial=$trace28815PreSerial x=${actualSample.x} dx=$dx28815 gapMs=$gap28815 draw=$trace28815DrawSerial sourceId=${System.identityHashCode(reference)}")
                            trace28815Events++
                        }
                        trace28815LastPreX = actualSample.x
                        trace28815LastPreNs = now28815
                    }
                    if (matrixTrace28814 && visible && trace28814Events < 180) {
                        try {
                            trace28814PredrawSerial++
                            trace28814AnchorMatrix.reset()
                            anchor.transformMatrixToGlobal(trace28814AnchorMatrix)
                            trace28814AnchorPoint[0] = lastBounds.left.toFloat()
                            trace28814AnchorPoint[1] = lastBounds.top.toFloat()
                            trace28814AnchorMatrix.mapPoints(trace28814AnchorPoint)
                            trace28814HostMatrix.reset()
                            frame.transformMatrixToGlobal(trace28814HostMatrix)
                            trace28814HostPoint[0] = 0f
                            trace28814HostPoint[1] = 0f
                            trace28814HostMatrix.mapPoints(trace28814HostPoint)
                            root.getLocationOnScreen(trace28814RootScreen)
                            root.getLocationInWindow(trace28814RootWindow)
                            val offset = trace28814RootScreen[0] - trace28814RootWindow[0]
                            val anchorX = trace28814AnchorPoint[0] + offset
                            val hostX = trace28814HostPoint[0] + offset
                            val sampleDx = if (trace28814PrevSample.isFinite()) actualSample.x - trace28814PrevSample else 0f
                            val hostDx = if (trace28814PrevHost.isFinite()) hostX - trace28814PrevHost else 0f
                            val anchorDx = if (trace28814PrevAnchor.isFinite()) anchorX - trace28814PrevAnchor else 0f
                            // Observe first motion after >=3 quiet frames, then capture next draws.
                            val start = kotlin.math.abs(sampleDx) > 0.35f &&
                                (trace28814PrevSample.isFinite()) && onsetQuietFrames >= 3
                            if (start) {
                                trace28814LastDirection = if (sampleDx > 0f) 1 else -1
                                trace28814DrawRemaining = 12
                                XposedBridge.log("KYANT28814_PREDRAW_START host=${System.identityHashCode(anchor)} dir=$trace28814LastDirection sampleDx=$sampleDx hostDx=$hostDx anchorDx=$anchorDx sampleX=${actualSample.x} anchorX=$anchorX hostX=$hostX sampleHostDelta=${actualSample.x-hostX} boundsLeft=${lastBounds.left} frameLeft=${frame.left} frameTx=${frame.translationX} workspaceScrollX=$workspaceScrollX workspaceTx=$workspaceTranslationX sourceId=$onsetSourceId")
                                trace28814Events++
                            }
                            if (trace28814DrawRemaining > 0 && trace28814Events < 180) {
                                XposedBridge.log("KYANT28814_PREDRAW_FRAME host=${System.identityHashCode(anchor)} dir=$trace28814LastDirection serial=$trace28814PredrawSerial sampleX=${actualSample.x} anchorX=$anchorX hostX=$hostX sampleDx=$sampleDx hostDx=$hostDx anchorDx=$anchorDx sampleHostDelta=${actualSample.x-hostX} sourceId=$onsetSourceId")
                                trace28814Events++
                            }
                            trace28814PrevSample = actualSample.x
                            trace28814PrevHost = hostX
                            trace28814PrevAnchor = anchorX
                        } catch (t: Throwable) {
                            XposedBridge.log("KYANT28814_PREDRAW_ERROR ${t.javaClass.simpleName}")
                        }
                    }
                    // Track the real host/anchor relationship (including parent transforms),
                    // separately from the reference Bitmap's sampling coordinate.
                    // Throttle diagnostics to avoid log I/O every animation frame.
                    if (rightStartTraceEnabled && visible && onsetLogCount < 180) {
                        val dx = if (onsetPrevX.isFinite()) actualSample.x - onsetPrevX else 0f
                        val dy = if (onsetPrevY.isFinite()) actualSample.y - onsetPrevY else 0f
                        val moving = kotlin.math.abs(dx) > 0.35f
                        if (moving) onsetQuietFrames = 0 else onsetQuietFrames++
                        if (onsetDirection == 0 && moving && onsetPrevX.isFinite()) {
                            onsetDirection = if (dx > 0f) 1 else -1
                            onsetFramesRemaining = 10
                            onsetSerial++
                            XposedBridge.log("KYANT28813_MOTION_START host=${System.identityHashCode(anchor)} onset=$onsetSerial direction=$onsetDirection dx=$dx x=${actualSample.x} workspaceScrollX=$workspaceScrollX sourceId=$onsetSourceId")
                            onsetLogCount++
                        }
                        val hostMatrix28813 = Matrix()
                        frame.transformMatrixToGlobal(hostMatrix28813)
                        val hostPoint28813 = floatArrayOf(0f, 0f)
                        hostMatrix28813.mapPoints(hostPoint28813)
                        val nowNs28813 = SystemClock.elapsedRealtimeNanos()
                        if (onsetFramesRemaining > 0 && onsetLogCount < 180) {
                            val scrollDelta28813 = if (onsetPrevScrollX == Int.MIN_VALUE) 0 else workspaceScrollX - onsetPrevScrollX
                            XposedBridge.log("KYANT28813_ONSET_FRAME host=${System.identityHashCode(anchor)} onset=$onsetSerial direction=$onsetDirection remain=$onsetFramesRemaining x=${actualSample.x} dx=$dx y=${actualSample.y} dy=$dy anchorX=${xy[0]} hostX=${hostPoint28813[0]} hostDx=${hostPoint28813[0]-onsetPrevHostX} scrollX=$workspaceScrollX scrollDx=$scrollDelta28813 workspaceTx=$workspaceTranslationX boundsLeft=${lastBounds.left} sourceId=$onsetSourceId dtMs=${if (onsetPrevNs > 0L) (nowNs28813-onsetPrevNs)/1000000L else -1L}")
                            onsetLogCount++
                            onsetFramesRemaining--
                        }
                        if (onsetFramesRemaining == 0 && onsetQuietFrames >= 3) onsetDirection = 0
                        onsetPrevX = actualSample.x
                        onsetPrevY = actualSample.y
                        onsetPrevHostX = hostPoint28813[0]
                        onsetPrevScrollX = workspaceScrollX
                        onsetPrevNs = nowNs28813
                    }
                    val traceNowMs = SystemClock.uptimeMillis()
                    if ((frameTrace28815 || layerTrace28817 || matrixTrace28814) &&
                        visible && (hostMisplaced ||
                        (workspaceMoved && traceNowMs - lastPositionTraceMs >= 180L))) {
                        val hostMatrix = android.graphics.Matrix()
                        frame.transformMatrixToGlobal(hostMatrix)
                        val hostPoint = floatArrayOf(0f, 0f)
                        hostMatrix.mapPoints(hostPoint)
                        val anchorMatrix = android.graphics.Matrix()
                        anchor.transformMatrixToGlobal(anchorMatrix)
                        val anchorPoint = floatArrayOf(b.left.toFloat(), b.top.toFloat())
                        anchorMatrix.mapPoints(anchorPoint)
                        val hostDeltaX = hostPoint[0] - anchorPoint[0]
                        val hostDeltaY = hostPoint[1] - anchorPoint[1]
                        if (hostMisplaced || traceNowMs - lastPositionTraceMs >= 180L) {
                            XposedBridge.log("KYANT2885_HOST_WORKSPACE_TRACE host=" +
                                System.identityHashCode(anchor) +
                                " workspaceScrollX=" + workspaceScrollX +
                                " workspaceTranslationX=" + workspaceTranslationX +
                                " workspaceMoved=" + workspaceMoved +
                                " hostMisplaced=" + hostMisplaced +
                                " hostLocalX=" + frame.left + " expectedLocalX=" + b.left +
                                " hostGlobalX=" + hostPoint[0] +
                                " anchorGlassGlobalX=" + anchorPoint[0] +
                                " globalDx=" + hostDeltaX + " globalDy=" + hostDeltaY +
                                " deltaHostX=" + (hostPoint[0] - lastHostScreenX) +
                                " deltaAnchorX=" + (anchorPoint[0] - lastAnchorScreenX))
                            lastPositionTraceMs = traceNowMs
                        }
                        lastHostScreenX = hostPoint[0]
                        lastAnchorScreenX = anchorPoint[0]
                    }
                    // Only fully offscreen folders skip sampling. Partially visible
                    // folders update every frame, with no fixed temporal throttle.
                    // Commit every visible transform change; offscreen transitions are reconciled on return.
                    val updatePosition = (moved || !firstPositionSettled || boundsChangedNow) && visible
                    if (moved && !updatePosition) skippedPositionUpdates++
                    if (boundsChangedNow || updatePosition || hostMisplaced) {
                        geometryChanges++
                        if (hostMisplaced) {
                            hostLayoutCorrections++
                            val nowMs = SystemClock.uptimeMillis()
                            if (hostLayoutCorrections <= 8 || nowMs - lastHostCorrectionMs >= 1500L) {
                                XposedBridge.log("KYANT2885_HOST_RELAYOUT_FIX host=" + System.identityHashCode(anchor) +
                                    " actual=" + frame.left + "," + frame.top + "," + frame.width + "x" + frame.height +
                                    " expected=" + b.left + "," + b.top + "," + expectedW + "x" + expectedH +
                                    " elapsedMs=" + (nowMs - startedAt))
                                lastHostCorrectionMs = nowMs
                            }
                        }
                        val boundsChanged = b != lastBounds
                        if (updatePosition) { lastX = xy[0]; lastY = xy[1] }
                        lastBounds = android.graphics.Rect(b)
                        // Child geometry uses FolderIcon-local coordinates, not DecorView coordinates.
                        if (!fullOverlayInstalled28877) {
                            frame.translationX = 0f
                            frame.translationY = 0f
                        }
                        val w = b.width().coerceAtLeast(1)
                        val h = b.height().coerceAtLeast(1)
                        if (frame.layoutParams.width != w || frame.layoutParams.height != h) {
                            frame.layoutParams = frame.layoutParams.apply {
                                width = w; height = h
                            }
                            compose.layoutParams = (compose.layoutParams as FrameLayout.LayoutParams).apply {
                                width = w; height = h
                            }
                            sampleWidth.intValue = w; sampleHeight.intValue = h
                        }
                        if (boundsChanged || w != sampleWidth.intValue || h != sampleHeight.intValue) {
                            sampleRadiusPx.floatValue = HookEntry.kyantFolderGlassRadius273(anchor)
                                .coerceIn(0f, minOf(w, h) * 0.49f)
                        }
                        if (boundsChanged || hostMisplaced) {
                            if (fullOverlayInstalled28877) {
                                frame.layout(0, 0, w, h)
                                positionFullOverlay28877(glassScreenPosition(b), frame)
                            } else frame.layout(b.left, b.top, b.left + w, b.top + h)
                            if (hostMisplaced) {
                                frame.postInvalidateOnAnimation()
                                compose.postInvalidateOnAnimation()
                            }
                        }
                        run {
                            val position = largeFrameSample28829 ?: glassScreenPosition(b)
                            if (samplePosition.value != position) {
                                samplePosition.value = position
                                lastCommitNs = SystemClock.elapsedRealtimeNanos()
                                sampleCommitCount++
                                cadenceCommits++
                            }
                            lastSample = position
                            firstPositionSettled = true
                        }
                    }
                    // V286.1: visibility MUST NOT depend on a matching draw-state snapshot.
                    // An INVISIBLE Compose host may not produce the draw that would make
                    // sampleMatches true, leaving every folder permanently hidden.
                    // Keep geometry valid and composition initialized as the only gate.
                    val layoutMatches = (if (fullOverlayInstalled28877)
                        frame.left == 0 && frame.top == 0
                    else frame.left == b.left && frame.top == b.top) &&
                        frame.width == b.width() && frame.height == b.height()
                    val currentSample = largeFrameSample28829 ?: glassScreenPosition(b)
                    // Geometry may have changed since the initial pre-draw read.
                    nativePredrawPosition28824 = currentSample
                    // The existing moved/geometry gate may skip committing the
                    // real native matrix while Workspace is scrolling. Mode 1
                    // closes that gap using the same PreDraw snapshot for every
                    // visible host; there is no background thread or timer.
                    if (nativeFrameCommit28863 == 1 && visible &&
                        frame.visibility == View.VISIBLE && layoutMatches &&
                        currentSample.x.isFinite() && currentSample.y.isFinite()) {
                        val previous = samplePosition.value
                        val deltaX = kotlin.math.abs(currentSample.x - previous.x)
                        val deltaY = kotlin.math.abs(currentSample.y - previous.y)
                        val maxLag = kotlin.math.max(deltaX, deltaY)
                        sync28863Checks++
                        sync28863MaxLag = kotlin.math.max(sync28863MaxLag, maxLag)
                        if (sync28863LastX.isFinite()) {
                            sync28863MaxStep = kotlin.math.max(sync28863MaxStep,
                                kotlin.math.abs(currentSample.x - sync28863LastX))
                        }
                        sync28863LastX = currentSample.x
                        if (maxLag > 0.015625f) {
                            sync28863NativeChanged++
                            samplePosition.value = currentSample
                            lastSample = currentSample
                            lastCommitNs = SystemClock.elapsedRealtimeNanos()
                            sampleCommitCount++
                            cadenceCommits++
                            sync28863Commits++
                            // A changed Compose source state invalidates its draw
                            // node; request a View frame as well so the native
                            // parent's scrolling display list is refreshed.
                            compose.postInvalidateOnAnimation()
                        }
                        val ns = SystemClock.elapsedRealtimeNanos()
                        if (sync28863WindowNs == 0L) sync28863WindowNs = ns
                        if (ns - sync28863WindowNs >= 1_000_000_000L) {
                            XposedBridge.log("KYANT28863_FRAME_COMMIT host=${System.identityHashCode(anchor)} " +
                                "checks=$sync28863Checks nativeChanges=$sync28863NativeChanged " +
                                "commits=$sync28863Commits maxLagPx=$sync28863MaxLag " +
                                "maxStepPx=$sync28863MaxStep")
                            sync28863WindowNs = ns
                            sync28863Checks = 0L
                            sync28863NativeChanged = 0L
                            sync28863Commits = 0L
                            sync28863MaxLag = 0f
                            sync28863MaxStep = 0f
                        }
                    }
                    if (lifecycleProbe28864 && currentSample.x.isFinite() && currentSample.y.isFinite()) {
                        probePreDraw28864++
                        val movedNow28864 = probeLastX28864.isFinite() &&
                            (kotlin.math.abs(currentSample.x - probeLastX28864) > 0.5f ||
                             kotlin.math.abs(currentSample.y - probeLastY28864) > 0.5f)
                        if (movedNow28864) {
                            probeMoving28864++
                            val sourceSerialNow = sourceDrawSerial28864.get()
                            if (sourceSerialNow == probeLastSourceSerial28864) probeMovingWithoutSource28864++
                            val sourcePos = sourceDrawPosition28864.get()
                            if (sourcePos != null) {
                                val gap = kotlin.math.max(
                                    kotlin.math.abs(sourcePos.x - currentSample.x),
                                    kotlin.math.abs(sourcePos.y - currentSample.y))
                                probeMaxSourceGap28864 = kotlin.math.max(probeMaxSourceGap28864, gap)
                            }
                            probeLastSourceSerial28864 = sourceSerialNow
                        }
                        probeLastX28864 = currentSample.x
                        probeLastY28864 = currentSample.y
                        val probeNow = SystemClock.elapsedRealtimeNanos()
                        if (probeNow - probeWindow28864 >= 1_000_000_000L) {
                            val parentTotal = parentDrawSerial28864.get()
                            val sourceTotal = sourceDrawSerial28864.get()
                            val noSourceTotal = parentWithoutSource28864.get()
                            XposedBridge.log("KYANT28864_LIFECYCLE host=${System.identityHashCode(anchor)} " +
                                "predraw=$probePreDraw28864 moving=$probeMoving28864 " +
                                "movesWithoutSource=$probeMovingWithoutSource28864 " +
                                "parentDraws=${parentTotal - probeLastParentSerial28864} " +
                                "parentDrawWithoutSource=${noSourceTotal - probeLastNoSource28864} " +
                                "sourceDraws=${sourceTotal - probeLastSourceTotal28864} maxSourceGapPx=$probeMaxSourceGap28864 " +
                                "visible=$visible")
                            probeLastParentSerial28864 = parentTotal
                            probeLastSourceTotal28864 = sourceTotal
                            probeLastNoSource28864 = noSourceTotal
                            probePreDraw28864 = 0L
                            probeMoving28864 = 0L
                            probeMovingWithoutSource28864 = 0L
                            probeMaxSourceGap28864 = 0f
                            probeWindow28864 = probeNow
                        }
                    }
                    // V288.65 A/B: request a fresh Compose source draw when a
                    // *visible* native container actually changes screen position.
                    // Guard against stationary invalidation loops; avoid changing
                    // Compose snapshot state during drawing or PreDraw.
                    if (nativeDrawSync28865 == 1 && visible && layoutMatches &&
                        frame.visibility == View.VISIBLE && currentSample.x.isFinite() &&
                        currentSample.y.isFinite()) {
                        val delta = if (drawLastRequestedX28865.isFinite())
                            kotlin.math.max(kotlin.math.abs(currentSample.x - drawLastRequestedX28865),
                                kotlin.math.abs(currentSample.y - drawLastRequestedY28865)) else 0f
                        if (delta > 0.5f) {
                            drawRequests28865++
                            drawMaxGap28865 = kotlin.math.max(drawMaxGap28865, delta)
                            // Invalidate only the Compose source-bearing View.
                            // On native draw-list caching, this is the minimal
                            // request that may cause the source Canvas to redraw.
                            compose.postInvalidateOnAnimation()
                        }
                        drawLastRequestedX28865 = currentSample.x
                        drawLastRequestedY28865 = currentSample.y
                    }
                    if (drawProbe28865) {
                        val now65 = SystemClock.elapsedRealtimeNanos()
                        if (now65 - drawRequestWindow28865 >= 1_000_000_000L) {
                            val source65 = sourceDrawSerial28864.get()
                            val tree65 = treeDrawSerial28865.get()
                            XposedBridge.log("KYANT28865_DRAW_SYNC host=${System.identityHashCode(anchor)} " +
                                "mode=$nativeDrawSync28865 requests=$drawRequests28865 " +
                                "sourceDraws=${source65 - drawSourceAtStart28865} " +
                                "treeDraws=${tree65 - drawTreeAtStart28865} " +
                                "maxRequestStepPx=$drawMaxGap28865 visible=$visible")
                            drawRequests28865 = 0L
                            drawMaxGap28865 = 0f
                            drawSourceAtStart28865 = source65
                            drawTreeAtStart28865 = tree65
                            drawRequestWindow28865 = now65
                        }
                    }
                    if (reboundTrace28890 || timelineTrace28893) {
                        val now90 = SystemClock.elapsedRealtimeNanos()
                        val releaseSeq90 = releaseSeq28820.get()
                        predrawSerial28890++
                        predrawSample28890.set(ReboundSample28890(
                            predrawSerial28890, now90, currentSample, releaseSeq90))
                        val step90 = if (previousPredrawX28890.isFinite())
                            currentSample.x - previousPredrawX28890 else 0f
                        val reversal90 = kotlin.math.abs(step90) > 0.35f &&
                            kotlin.math.abs(previousPredrawDx28890) > 0.35f &&
                            step90 * previousPredrawDx28890 < 0f
                        if (reversal90) lastReversalNs28890 = now90
                        val releaseAge90 = now90 - releaseNs28820.get()
                        val inRelease90 = releaseSeq90 > 0L &&
                            releaseAge90 in 0L..650_000_000L
                        val relevant90 = inRelease90 ||
                            now90 - lastReversalNs28890 in 0L..350_000_000L
                        // No every-frame logging. At most 9 events per host,
                        // with a minimum 45ms spacing; records include real source
                        // and host observations but cannot establish GPU display time.
                        if (reboundTrace28890 && relevant90 && predrawLogBudget28890 < 9 &&
                            now90 - lastPredrawLogNs28890 >= 45_000_000L &&
                            trace28815Host in selectedHostIds28821) {
                            predrawLogBudget28890++
                            lastPredrawLogNs28890 = now90
                            val b90 = bridgeSample28890.get()
                            XposedBridge.log("KYANT28890_PREDRAW host=$trace28815Host " +
                                "seq=$predrawSerial28890 release=$releaseSeq90 " +
                                "releaseAgeMs=${if (inRelease90) releaseAge90 / 1_000_000f else -1f} " +
                                "reverse=$reversal90 stepX=$step90 originX=${currentSample.x} " +
                                "composeStateX=${samplePosition.value.x} " +
                                "bridgeGen=${b90?.gen ?: -1L} bridgeX=${b90?.pos?.x} " +
                                "bridgeAgeMs=${if (b90 != null) (now90-b90.atNs)/1_000_000f else -1f} " +
                                "hostTx=${frame.translationX}")
                        }
                        if (!relevant90) predrawLogBudget28890 = 0
                        previousPredrawX28890 = currentSample.x
                        if (kotlin.math.abs(step90) > 0.35f) previousPredrawDx28890 = step90
                    }
                    predrawMatrixSnapshot.set(Pair(currentSample, SystemClock.elapsedRealtimeNanos()))
                    releaseTrace28820("POST_PREDRAW", currentSample,
                        "committedX=${samplePosition.value.x} committedY=${samplePosition.value.y} changed=$updatePosition")
                    if (cadenceStartX.isNaN()) cadenceStartX = currentSample.x
                    if (!cadenceEndX.isNaN() && kotlin.math.abs(currentSample.x - cadenceEndX) > 0.015625f) cadenceMoving++
                    cadenceEndX = currentSample.x
                    val cadenceElapsed = cadenceNow - cadenceWindowNs
                    if (cadenceElapsed >= 1_000_000_000L) {
                        XposedBridge.log("KYANT2885_PREDRAW_CADENCE host=" + System.identityHashCode(anchor) +
                            " elapsedMs=" + (cadenceElapsed / 1_000_000L) +
                            " predraw=" + cadencePredraw + " moving=" + cadenceMoving +
                            " commits=" + cadenceCommits + " longestGapMs=" + (cadenceLongestGapNs / 1_000_000L) +
                            " startX=" + cadenceStartX + " endX=" + cadenceEndX +
                            " visible=" + (frame.visibility == View.VISIBLE))
                        cadenceWindowNs = cadenceNow
                        cadencePredraw = 0L; cadenceMoving = 0L; cadenceCommits = 0L
                        cadenceLongestGapNs = 0L; cadenceStartX = Float.NaN
                    }
                    if (frameChecks % 180L == 0L) {
                        val integerX = xy[0].toFloat() + b.left
                        val integerY = xy[1].toFloat() + b.top
                        XposedBridge.log("KYANT2885_COORD host=" + System.identityHashCode(anchor) +
                            " matrixX=" + currentSample.x + " matrixY=" + currentSample.y +
                            " integerX=" + integerX + " integerY=" + integerY +
                            " dx=" + (currentSample.x - integerX) + " dy=" + (currentSample.y - integerY) +
                            " committedX=" + samplePosition.value.x + " committedY=" + samplePosition.value.y)
                    }
                    if (layoutMatches && b.width() > 0 && b.height() > 0 && compositionSeen.get()) {
                        if (frame.visibility != View.VISIBLE) {
                            // Commit the CURRENT matrix position before the first visible draw.
                            samplePosition.value = currentSample
                            lastSample = currentSample
                            firstPositionSettled = true
                            frame.visibility = View.VISIBLE
                            frame.postInvalidateOnAnimation()
                            compose.postInvalidateOnAnimation()
                            XposedBridge.log("KYANT2885_FIRST_VISIBLE frames=" + frameChecks +
                                " bounds=" + b + " delayMs=" + (SystemClock.uptimeMillis() - startedAt))
                        }
                    } else if (frameChecks % 120L == 0L) {
                        XposedBridge.log("KYANT2885_VISIBILITY_WAIT layout=" + layoutMatches +
                            " composed=" + compositionSeen.get() + " bounds=" + b +
                            " frame=" + frame.width + "x" + frame.height)
                    }
                    val predrawElapsed288109 = SystemClock.elapsedRealtimeNanos() - predrawStartedNs
                    if (perfProfile288109) KyantPerf288109.record(
                        KyantPerf288109.PREDRAW, predrawElapsed288109)
                    val predrawUs = predrawElapsed288109 / 1000L
                    totalPredrawUs += predrawUs
                    if (predrawUs > maxPredrawUs) maxPredrawUs = predrawUs
                }
                if (perfProfile288109) KyantPerf288109.flushIfDue()
                true
            }
            followListener = listener
            root.viewTreeObserver.addOnPreDrawListener(listener)
            if (drawProbe28865) {
                val treeListener = android.view.ViewTreeObserver.OnDrawListener {
                    // Traversal-level draw event. This is NOT FolderIcon.dispatchDraw
                    // and does not establish when GPU composition presents the frame.
                    treeDrawSerial28865.incrementAndGet()
                }
                treeDrawListener28865 = treeListener
                root.viewTreeObserver.addOnDrawListener(treeListener)
            }
            if (!fullOverlayInstalled28877) {
                frame.translationX = 0f
                frame.translationY = 0f
            }
            XposedBridge.log("KYANT2885_HOST_ATTACHED layer=${if (fullOverlayInstalled28877) "root_child_288771" else "folder_child_index_0"} owners=isolated recomposer=explicit view=${anchor.width}x${anchor.height} glassBounds=$initialBounds lens=${previewWidth}x${previewHeight} reference=${reference.width}x${reference.height}")
            // V286: keep the first position live through layout, scroll and pre-draw.
            // The previous 32-ms delayed gate made glass appear seconds late on page switches.
            // OnPreDraw above is the single source of truth for reference-frame coordinates.
            XposedBridge.log("KYANT2885_STARTUP_GATE_REMOVED continuous_predraw_tracking=true")
            // Persistent host remains until disabled, detached or an error occurs.
            XposedBridge.log("KYANT2885_PERSISTENT_READY stop=switch_off_or_folder_detached")
        } catch (t: Throwable) {
            XposedBridge.log("KYANT2885_HOST_ERROR " + t.javaClass.simpleName + " " + (t.message ?: ""))
            if (selectedEngine28888 == 0) {
                try { Settings.Global.putInt(anchor.context.contentResolver, KEY, 0) } catch (_: Throwable) {}
            }
            dispose("setup_error")
        }
    }

    /** Real wallpaper pixels sampled from the existing Launcher Bitmap, without a CPU crop. */
    @Composable private fun LensContent270(
        reference: Bitmap, previewWidth: Int, previewHeight: Int,
        rootWidth: Int, rootHeight: Int, screenPosition: androidx.compose.runtime.State<Offset>,
        cornerRadiusPx: Float, samplingMode: Int,
        largeContinuousSource28830: Boolean, renderAbMode: Int, motionAbMode: Int,
        legacySyncAbMode: Int, motionCompare28861: Boolean, layerSampleMode28862: Int, displayIsolationMode: Int, nativeLayerMode: Int, bridgeBitmap: Bitmap?,
        bridgeVersion: androidx.compose.runtime.State<Int>,
        gpuReference288110: Boolean,
        bridgeSyncMode: Int,
        pipelineMode28819: Int, frameSync28822: Int,
        bridgePublishedPosition: java.util.concurrent.atomic.AtomicReference<Offset?>,
        bridgeMismatchObserved: java.util.concurrent.atomic.AtomicLong,
        bridgeResample: (Offset) -> Unit,
        predrawMatrixSnapshot: java.util.concurrent.atomic.AtomicReference<Pair<Offset, Long>>,
        reboundTrace28890: Boolean, traceHost28890: Int,
        perfProfile288109: Boolean,
        predrawSample28890: java.util.concurrent.atomic.AtomicReference<ReboundSample28890?>,
        bridgeSample28890: java.util.concurrent.atomic.AtomicReference<BridgeSample28890?>,
        currentNativePosition: () -> Offset,
        sourceMatrixAb28866: Int, currentComposeViewPosition28866: () -> Offset,
        zeroLensAb28868: Int, continuousMotionProbe28868: Boolean,
        continuousRun28868: java.util.concurrent.atomic.AtomicReference<Pair<Long, Long>>,
        rootPipeline28879: Int, rootPipelineTrace28879: Boolean,
        bitmapPipeline28880: Int, bitmapTrace28880: Boolean,
        immutableBridge28880: androidx.compose.runtime.State<Bitmap?>,
        immutableGen28880: androidx.compose.runtime.State<Long>,
        nodePipeline28881: Int, nodeTrace28881: Boolean,
        positionPipeline28882: Int, positionTrace28882: Boolean,
        currentHostPosition28882: () -> Offset,
        nativeCarrierAb28869: Int, distanceAb28874: Int, distanceTrace28874: Boolean,
        bufferExperiment28875: Boolean,
        bridgeFront28875: androidx.compose.runtime.State<Bitmap?>,
        bridgeFrontGen28875: androidx.compose.runtime.State<Long>,
        bridgeFrontPosition28875: androidx.compose.runtime.State<Offset?>,
        reboundFrameAb28891: Int,
        reboundFrame28891: androidx.compose.runtime.State<BridgeFrame28891?>,
        gestureIsolation28894: Int,
        continuityAb28895: Int,
        directReferenceEligible28895: Boolean,
        coordinateTrace28892: Boolean,
        bridgeCoordinate28892: java.util.concurrent.atomic.AtomicReference<BridgeCoordinate28892?>,
        timelineTrace28893: Boolean,
        dualMotionTrace28896: Boolean,
        finalSampleTrace28897: Boolean,
        sampleSyncAb28898: Int,
        visibleGestureTrace28899: Boolean,
        snapshotTrace288100: Boolean,
        bridgePairAb288101: Int,
        bridgePairTrace288101: Boolean,
        bridgePairFrame288101: androidx.compose.runtime.State<VerifiedBridgeFrame288101?>,
        timingTraceEnabled288102: Boolean,
        scrollTraceEnabled288103: Boolean,
        coordinateBasisTrace288104: Boolean,
        diagnosticAnchor288104: View,
        timings288102: TimingTrace288102,
        uvProbe28867: Boolean, visibleSource28867: () -> Boolean,
        recordSourceMatrix28866: (Offset, Offset) -> Unit,
        lifecycleProbe28864: Boolean, drawProbe28865: Boolean,
        sourceDrawSerial28864: AtomicLong,
        sourceDrawPosition28864: java.util.concurrent.atomic.AtomicReference<Offset?>,
        trace28820: (String, Offset?, String) -> Unit,
        onComposed: () -> Unit
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val optics28827 = KyantOptics28827.material
        val backdrop = rememberLayerBackdrop()
        val image = androidx.compose.runtime.remember(reference) { reference.asImageBitmap() }
        val bitmapPaint = androidx.compose.runtime.remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
        val wholeDestination = androidx.compose.runtime.remember { RectF() }
        // Freeze only the SOURCE coordinate, not the Host position or the live
        // Compose State. Capture on the first actual draw to avoid startup offset.
        val frozenSource = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicReference<Offset?>(null) }
        val sourceDrawCount = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        // Last reported drawing source (not a SurfaceFlinger frame id).
        val sourceTraceState28895 = androidx.compose.runtime.remember { longArrayOf(-1L, 0L) }
        val reboundLogState28890 = androidx.compose.runtime.remember { longArrayOf(0L, 0L, 0L) }
        // [release sequence, last log timestamp, samples]; no composition mutation.
        val coordinateLogState28892 = androidx.compose.runtime.remember { longArrayOf(0L, 0L, 0L) }
        val timelineLogState28893 = androidx.compose.runtime.remember { longArrayOf(-1L, 0L, 0L) }
        // [last touch epoch, last release, last log timestamp, per-epoch count].
        val dualTraceState28896 = androidx.compose.runtime.remember { longArrayOf(-1L, -1L, 0L, 0L) }
        // [last down serial, last release serial, last trace time, samples]
        val finalTraceState28897 = androidx.compose.runtime.remember { longArrayOf(-1L, -1L, 0L, 0L) }
        // [last diagnostic time, draws in current host]. Kept out of Compose State.
        val syncTraceState28898 = androidx.compose.runtime.remember { longArrayOf(0L, 0L) }
        // [last down, last release, last visible log, visible samples, last filter log].
        // Local to one host, no Compose state invalidation or Bitmap writes.
        val visibleTraceState28899 = androidx.compose.runtime.remember {
            longArrayOf(-1L, -1L, 0L, 0L, 0L)
        }
        // [last down, last release, last time, count], logging only.
        val pairLog288101 = androidx.compose.runtime.remember { longArrayOf(0L, 0L) }
        // Draw-callback counters are non-observable; no Recompose triggers.
        val timingLastLog288102 = androidx.compose.runtime.remember { longArrayOf(0L, 0L, 0L) }
        val snapshotLog288100 = androidx.compose.runtime.remember {
            longArrayOf(-1L, -1L, 0L, 0L)
        }
        val previousGap28893 = androidx.compose.runtime.remember { floatArrayOf(Float.NaN, Float.NaN) }
        val sourceLastDrawNs = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val drawCadenceWindowNs = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val drawCadenceCount = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val drawCadenceOver16 = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val drawCadenceOver33 = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val drawCadenceMaxNs = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val sourceOver16 = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val sourceOver33 = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val sourceMaxGapNs = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(0L) }
        // V288.61: draw-side, per-host aggregated motion comparison.
        // No per-frame Logcat and no writes to Compose State during drawing.
        val motionStats28861 = androidx.compose.runtime.remember { FloatArray(6) }
        val motionWindow28861 = androidx.compose.runtime.remember { longArrayOf(0L, 0L, 0L, 0L) }
        // Draw-thread local diagnostics, reset every ~1 second. No state updates,
        // texture copying, frame forcing, or per-frame Logcat.
        val uvPrev28867 = androidx.compose.runtime.remember { floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN) }
        val uvStats28867 = androidx.compose.runtime.remember { floatArrayOf(0f, 0f, 0f, 0f, 0f) }
        val uvCounts28867 = androidx.compose.runtime.remember { longArrayOf(0L, 0L, 0L, 0L, 0L, 0L) }
        val uvTimes28867 = androidx.compose.runtime.remember { longArrayOf(0L, 0L) }
        // Per-Host, draw-thread only. Reset window and active-run context separately.
        val continuousCounts28868 = androidx.compose.runtime.remember { LongArray(5) }
        // qualified draws, intervals >16.7, >33.3, >50, excluded transitions
        val continuousValues28868 = androidx.compose.runtime.remember { FloatArray(3) }
        // max draw gap, max position step, largest PreDraw-to-draw age
        val continuousTimes28868 = androidx.compose.runtime.remember { longArrayOf(0L, 0L, 0L) }
        // window start, prior qualified source draw time, prior run start

        // Clip the WHOLE optical pipeline, including wallpaper source and highlight,
        // not just the Kyant lens shape. Prevent pixels escaping rounded corners.
        val cornerDp = (cornerRadiusPx / density).dp
        if (nodePipeline28881 == 2 || positionPipeline28882 in 1..4) {
            // ComposeView -> AndroidView -> native View.onDraw. This keeps the
            // ComposeView host but removes Compose Canvas as the pixel painter.
            // Read live position at the native draw, not a recomposition snapshot.
            val composePosition = screenPosition.value // invalidation trigger
            val referenceHolder81 = androidx.compose.runtime.remember {
                java.util.concurrent.atomic.AtomicReference(reference)
            }
            referenceHolder81.set(reference)
            AndroidView(
                modifier = Modifier.size((previewWidth / density).dp,
                    (previewHeight / density).dp),
                factory = { context ->
                    object : View(context) {
                        private val paint81 = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        private val target81 = RectF()
                        var frames81 = 0L
                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val currentRef = referenceHolder81.get()
                            if (width <= 0 || height <= 0 || currentRef.isRecycled) return
                            // All sources are observed in THIS onDraw call; never
                            // compare values logged on different Hosts/frames.
                            val now82 = SystemClock.elapsedRealtimeNanos()
                            val nativePos82 = try { currentNativePosition() } catch (_: Throwable) { composePosition }
                            val hostPos82 = try { currentHostPosition28882() } catch (_: Throwable) { nativePos82 }
                            val pre82 = predrawMatrixSnapshot.get()
                            val preAge82 = if (pre82.second in 1L..now82)
                                (now82 - pre82.second) / 1_000_000f else -1f
                            val pos = when (positionPipeline28882) {
                                2 -> composePosition
                                3 -> hostPos82
                                4 -> if (preAge82 in 0f..50f) pre82.first else nativePos82
                                else -> nativePos82 // V288.81 stage2 and V288.82 stage1
                            }
                            val screenW = android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
                            val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels.coerceAtLeast(1)
                            // Same full reference Bitmap and float destination as mode 0.
                            target81.set(-pos.x, -pos.y,
                                screenW.toFloat() - pos.x, screenH.toFloat() - pos.y)
                            // Bitmap is the full-screen reference; match native
                            // root sampling when reference dimensions differ.
                            canvas.drawBitmap(currentRef, null, target81, paint81)
                            frames81++
                            if (positionTrace28882 && positionPipeline28882 in 1..4 &&
                                frames81 % 48L == 1L) {
                                val nativeGap = kotlin.math.max(
                                    kotlin.math.abs(nativePos82.x - composePosition.x),
                                    kotlin.math.abs(nativePos82.y - composePosition.y))
                                val hostGap = kotlin.math.max(
                                    kotlin.math.abs(nativePos82.x - hostPos82.x),
                                    kotlin.math.abs(nativePos82.y - hostPos82.y))
                                val preGap = kotlin.math.max(
                                    kotlin.math.abs(nativePos82.x - pre82.first.x),
                                    kotlin.math.abs(nativePos82.y - pre82.first.y))
                                XposedBridge.log("KYANT28882_ANDROIDVIEW_DRAW stage=$positionPipeline28882 " +
                                    "draw=$frames81 usedX=${pos.x} nativeX=${nativePos82.x} " +
                                    "composeX=${composePosition.x} hostX=${hostPos82.x} " +
                                    "preX=${pre82.first.x} preAgeMs=$preAge82 " +
                                    "composeGapPx=$nativeGap hostGapPx=$hostGap " +
                                    "preGapPx=$preGap width=$width hardware=${canvas.isHardwareAccelerated}")
                            }
                            if (nodeTrace28881 && positionPipeline28882 < 0 && frames81 % 120L == 1L)
                                XposedBridge.log("KYANT28881_ANDROIDVIEW_DRAW draw=$frames81 " +
                                    "x=${pos.x} composeX=${composePosition.x} " +
                                    "viewW=$width viewH=$height hardware=${canvas.isHardwareAccelerated}")
                        }
                    }.apply { isClickable = false; isFocusable = false; setWillNotDraw(false) }
                },
                update = { nativeView ->
                    // A new Compose position schedules the embedded native view,
                    // while its onDraw reads the position at actual draw time.
                    // AndroidView callback is typed View; re-create when reference
                    // identity changes using the key at the calling composition.
                    if (composePosition.x.isFinite()) nativeView.postInvalidateOnAnimation()
                }
            )
            SideEffect { onComposed() }
            return
        }
        // A/B 2 isolates the optical composition in an explicit offscreen layer.
        // A/B 0/1 retain the exact baseline parent layer path.
        val opticalContainer = Modifier.size(
            (previewWidth / density).dp,
            (previewHeight / density).dp
        ).then(if (renderAbMode == 2) Modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        } else Modifier).then(if (nodePipeline28881 == 4)
            Modifier else Modifier.clip(RoundedCornerShape(cornerDp)))
        Box(opticalContainer) {
            // Diagnostic only: transparent source may also remove the backdrop input.
            // Never interpret visible highlights alone as genuine refraction.
            val sourceAlpha = when (nativeLayerMode) { 2 -> 0f; 3 -> .02f; else -> 1f }
            // The four pipeline A/B paths intentionally avoid capturing a Kyant
            // backdrop or introducing a Compose graphicsLayer on the source canvas.
            val pipelineSourceOnly28879 = nodePipeline28881 in 1..4 ||
                bitmapPipeline28880 in 2..4 ||
                (bitmapPipeline28880 < 0 && rootPipeline28879 == 1)
            val pipelineCaptureOnly28879 = bitmapPipeline28880 < 0 && rootPipeline28879 == 2
            val sourceModifier = if (nodePipeline28881 == 3) {
                Modifier.fillMaxSize().graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            } else if (pipelineSourceOnly28879 || pipelineMode28819 >= 0) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().layerBackdrop(backdrop).graphicsLayer { alpha = sourceAlpha }
            }
            ComposeCanvas(sourceModifier) {
                val composeDrawStart288109 = if (perfProfile288109)
                    SystemClock.elapsedRealtimeNanos() else 0L
                // Reference is screen-aligned; root offset is accounted for explicitly.
                val screenW = android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
                val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels.coerceAtLeast(1)
                val scaleX = reference.width.toFloat() / screenW
                val scaleY = reference.height.toFloat() / screenH
                // Read snapshot state INSIDE the draw phase. Position changes now
                // invalidate the source drawing, not the whole Compose composition.
                val committedPosition = screenPosition.value
                // V288.6 A/B: reuse the legacy native View matrix sampling at the
                // moment Compose actually draws. The state remains the invalidation
                // trigger; it is NOT written from this draw callback.
                val livePosition = if (legacySyncAbMode == 1) {
                    try { currentNativePosition() } catch (_: Throwable) { committedPosition }
                } else committedPosition
                if (motionAbMode == 1 && sourceMatrixAb28866 == 0) frozenSource.compareAndSet(null, livePosition)
                val selectedOrigin28866 = if (sourceMatrixAb28866 == 1) {
                    // Guard against unattached/invalid source hosts: never allow
                    // bad matrix readings to produce a blank or displaced glass.
                    val nativeSource = try { currentComposeViewPosition28866() }
                        catch (_: Throwable) { null }
                    if (nativeSource != null && nativeSource.x.isFinite() && nativeSource.y.isFinite()) {
                        recordSourceMatrix28866(livePosition, nativeSource)
                        nativeSource
                    } else livePosition
                } else livePosition
                if (motionAbMode == 1 && sourceMatrixAb28866 == 1)
                    frozenSource.compareAndSet(null, selectedOrigin28866)
                val drawPosition = if (motionAbMode == 1)
                    (frozenSource.get() ?: selectedOrigin28866) else selectedOrigin28866
                if (drawProbe28865) {
                    sourceDrawPosition28864.set(drawPosition)
                    sourceDrawSerial28864.incrementAndGet()
                }
                if (motionCompare28861) {
                    val nativeNow = try { currentNativePosition() } catch (_: Throwable) { null }
                    if (nativeNow != null) {
                        val statNow = SystemClock.elapsedRealtimeNanos()
                        val lastTime = motionWindow28861[1]
                        val lagPx = kotlin.math.max(kotlin.math.abs(nativeNow.x - committedPosition.x),
                            kotlin.math.abs(nativeNow.y - committedPosition.y))
                        val drawLagPx = kotlin.math.max(kotlin.math.abs(nativeNow.x - drawPosition.x),
                            kotlin.math.abs(nativeNow.y - drawPosition.y))
                        val stepPx = if (lastTime > 0L)
                            kotlin.math.max(kotlin.math.abs(nativeNow.x - motionStats28861[4]),
                                kotlin.math.abs(nativeNow.y - motionStats28861[5])) else 0f
                        motionStats28861[0] = kotlin.math.max(motionStats28861[0], lagPx)
                        motionStats28861[1] = kotlin.math.max(motionStats28861[1], drawLagPx)
                        motionStats28861[2] = kotlin.math.max(motionStats28861[2], stepPx)
                        if (lagPx > 1f) motionWindow28861[2]++
                        motionStats28861[3] += lagPx
                        motionStats28861[4] = nativeNow.x
                        motionStats28861[5] = nativeNow.y
                        motionWindow28861[1] = statNow
                        val count = ++motionWindow28861[0]
                        if (motionWindow28861[0] > 0L && (lastTime == 0L ||
                                statNow - (motionWindow28861[3]) >= 1_000_000_000L)) {
                            // The first sample initializes the aggregation window.
                            if (motionWindow28861[3] != 0L && count > 1L && motionStats28861[2] > 0.5f) {
                                val observed = predrawMatrixSnapshot.get()
                                val ageMs = if (observed.second > 0L)
                                    (statNow - observed.second) / 1_000_000L else -1L
                                XposedBridge.log("KYANT28861_MOTION_COMPARE mode=$legacySyncAbMode ab28862=$layerSampleMode28862 " +
                                    "draws=$count maxCommitLagPx=${motionStats28861[0]} " +
                                    "maxDrawLagPx=${motionStats28861[1]} " +
                                    "maxStepPx=${motionStats28861[2]} " +
                                    "meanCommitLagPx=${motionStats28861[3] / count} " +
                                    "commitLagOver1px=${motionWindow28861[2]} " +
                                    "predrawAgeMs=$ageMs")
                            }
                            motionWindow28861[3] = statNow
                            motionWindow28861[0] = 0L
                            motionWindow28861[2] = 0L
                            for (i in 0..3) motionStats28861[i] = 0f
                        }
                    }
                }

                if (reboundTrace28890 && traceHost28890 in selectedHostIds28821) {
                    val now90 = SystemClock.elapsedRealtimeNanos()
                    val releaseSeq90 = releaseSeq28820.get()
                    val releaseAge90 = now90 - releaseNs28820.get()
                    val inRelease90 = releaseSeq90 > 0L && releaseAge90 in 0L..650_000_000L
                    if (releaseSeq90 != reboundLogState28890[0]) {
                        reboundLogState28890[0] = releaseSeq90
                        reboundLogState28890[2] = 0L
                    }
                    val p90 = predrawSample28890.get()
                    val b90 = bridgeSample28890.get()
                    if (inRelease90 && reboundLogState28890[2] < 12L &&
                        now90 - reboundLogState28890[1] >= 35_000_000L) {
                        reboundLogState28890[1] = now90
                        reboundLogState28890[2]++
                        val uiAge90 = if (p90 != null) (now90 - p90.atNs)/1_000_000f else -1f
                        val pubAge90 = if (b90 != null) (now90 - b90.atNs)/1_000_000f else -1f
                        XposedBridge.log("KYANT28890_COMPOSE_SOURCE host=$traceHost28890 " +
                            "release=$releaseSeq90 releaseAgeMs=${releaseAge90/1_000_000f} " +
                            "preSeq=${p90?.serial ?: -1L} preAgeMs=$uiAge90 " +
                            "preX=${p90?.pos?.x} stateX=${committedPosition.x} " +
                            "drawX=${drawPosition.x} nativeX=${try { currentNativePosition().x } catch (_: Throwable) { Float.NaN }} " +
                            "bridgeGen=${b90?.gen ?: -1L} bridgeX=${b90?.pos?.x} " +
                            "bridgeAgeMs=$pubAge90 visibleBridgeVersion=${bridgeVersion.value} " +
                            "reference=${System.identityHashCode(reference)} " +
                            "bridgeReference=${b90?.referenceIdentity ?: -1} " +
                            "opticsStrength=${optics28827.strength} blur=${optics28827.blur} " +
                            "highlight=${optics28827.highlight} " +
                            "backdropPath=${rootPipeline28879 < 0 && nodePipeline28881 < 0}")
                    }
                }
                val rootScreenX = drawPosition.x
                val rootScreenY = drawPosition.y
                val x = rootScreenX * scaleX
                val y = rootScreenY * scaleY
                val sw = previewWidth * scaleX
                val sh = previewHeight * scaleY
                if (continuousMotionProbe28868) {
                    val now68 = SystemClock.elapsedRealtimeNanos()
                    if (continuousTimes28868[0] == 0L) continuousTimes28868[0] = now68
                    val run68 = continuousRun28868.get()
                    val visible68 = try { visibleSource28867() } catch (_: Throwable) { false }
                    val predrawAge68 = if (run68.first > 0L) now68 - run68.first else Long.MAX_VALUE
                    // A qualified source draw belongs to a visible, continuously
                    // moving run lasting >=80ms and with a recent native PreDraw.
                    val qualified68 = visible68 && run68.second > 0L &&
                        now68 - run68.second >= 80_000_000L &&
                        predrawAge68 in 0L..33_000_000L
                    if (qualified68) {
                        continuousCounts28868[0]++
                        continuousValues28868[2] = maxOf(continuousValues28868[2], predrawAge68 / 1_000_000f)
                        if (continuousTimes28868[1] > 0L && continuousTimes28868[2] == run68.second) {
                            val gap68 = (now68 - continuousTimes28868[1]) / 1_000_000f
                            continuousValues28868[0] = maxOf(continuousValues28868[0], gap68)
                            if (gap68 > 16.7f) continuousCounts28868[1]++
                            if (gap68 > 33.3f) continuousCounts28868[2]++
                            if (gap68 > 50f) continuousCounts28868[3]++
                        } else continuousCounts28868[4]++
                        continuousTimes28868[1] = now68
                        continuousTimes28868[2] = run68.second
                    } else {
                        // Never bridge an idle/offscreen/drag-snapshot interval.
                        continuousTimes28868[1] = 0L
                        continuousTimes28868[2] = 0L
                        continuousCounts28868[4]++
                    }
                    if (now68 - continuousTimes28868[0] >= 1_000_000_000L) {
                        if (continuousCounts28868[0] > 0L) XposedBridge.log(
                            "KYANT28868_CONTINUOUS_MOTION path=" + when {
                                bridgeBitmap != null -> "BRIDGE"
                                renderAbMode != 1 && (samplingMode == 1 || largeContinuousSource28830) -> "FULL_FRAME"
                                else -> "SOURCE_CROP"
                            } + " qualifiedDraws=${continuousCounts28868[0]} " +
                            "over16=${continuousCounts28868[1]} over33=${continuousCounts28868[2]} " +
                            "over50=${continuousCounts28868[3]} excluded=${continuousCounts28868[4]} " +
                            "maxQualifiedGapMs=${continuousValues28868[0]} " +
                            "maxPredrawAgeMs=${continuousValues28868[2]} " +
                            "zeroLens=$zeroLensAb28868")
                        continuousCounts28868.fill(0L)
                        continuousValues28868.fill(0f)
                        continuousTimes28868[0] = now68
                    }
                }
                if (uvProbe28867) {
                    val now67 = SystemClock.elapsedRealtimeNanos()
                    if (uvTimes28867[0] == 0L) uvTimes28867[0] = now67
                    val visible67 = try { visibleSource28867() } catch (_: Throwable) { false }
                    // Wallpaper origin in normalized UV coordinates, before Kyant lens.
                    val u67 = x / reference.width.coerceAtLeast(1).toFloat()
                    val v67 = y / reference.height.coerceAtLeast(1).toFloat()
                    if (visible67 && uvPrev28867[0].isFinite()) {
                        val positionStep67 = kotlin.math.max(
                            kotlin.math.abs(rootScreenX - uvPrev28867[0]),
                            kotlin.math.abs(rootScreenY - uvPrev28867[1]))
                        val uvStepPx67 = kotlin.math.max(
                            kotlin.math.abs(u67 - uvPrev28867[2]) * screenW,
                            kotlin.math.abs(v67 - uvPrev28867[3]) * screenH)
                        if (positionStep67 > 0.5f) {
                            uvCounts28867[0]++ // actual moving source draws
                            uvStats28867[0] = maxOf(uvStats28867[0], positionStep67)
                            uvStats28867[1] = maxOf(uvStats28867[1], uvStepPx67)
                            val residual67 = kotlin.math.abs(uvStepPx67 - positionStep67)
                            uvStats28867[2] = maxOf(uvStats28867[2], residual67)
                            if (residual67 > 0.5f) uvCounts28867[1]++
                            val gapMs67 = if (uvTimes28867[1] > 0L)
                                (now67 - uvTimes28867[1]) / 1_000_000f else 0f
                            uvStats28867[3] = maxOf(uvStats28867[3], gapMs67)
                            if (gapMs67 > 16.7f) uvCounts28867[2]++
                            if (gapMs67 > 33.3f) uvCounts28867[3]++
                            if (gapMs67 > 50f) uvCounts28867[4]++
                            val predraw = predrawMatrixSnapshot.get()
                            if (predraw.second > 0L) {
                                uvStats28867[4] = maxOf(uvStats28867[4],
                                    kotlin.math.max(kotlin.math.abs(predraw.first.x - rootScreenX),
                                        kotlin.math.abs(predraw.first.y - rootScreenY)))
                            }
                        }
                    }
                    // Do not let an offscreen interval appear as a motion draw gap.
                    if (visible67) {
                        uvPrev28867[0] = rootScreenX
                        uvPrev28867[1] = rootScreenY
                        uvPrev28867[2] = u67
                        uvPrev28867[3] = v67
                        uvTimes28867[1] = now67
                    } else {
                        uvPrev28867.fill(Float.NaN)
                        uvTimes28867[1] = 0L
                    }
                    uvCounts28867[5]++
                    if (now67 - uvTimes28867[0] >= 1_000_000_000L) {
                        if (uvCounts28867[0] >= 3L) {
                            val path67 = when {
                                bridgeBitmap != null -> "BRIDGE"
                                renderAbMode != 1 && (samplingMode == 1 || largeContinuousSource28830) -> "FULL_FRAME"
                                else -> "SOURCE_CROP"
                            }
                            XposedBridge.log("KYANT28867_UV_MOTION path=$path67 " +
                                "hostDraws=${uvCounts28867[5]} movingDraws=${uvCounts28867[0]} " +
                                "uvResidualOverHalfPx=${uvCounts28867[1]} " +
                                "maxPositionStepPx=${uvStats28867[0]} maxUvStepPx=${uvStats28867[1]} " +
                                "maxUvResidualPx=${uvStats28867[2]} " +
                                "movingGapOver16=${uvCounts28867[2]} " +
                                "movingGapOver33=${uvCounts28867[3]} " +
                                "movingGapOver50=${uvCounts28867[4]} " +
                                "maxMovingGapMs=${uvStats28867[3]} " +
                                "maxPredrawPositionDiffPx=${uvStats28867[4]} " +
                                "reference=${reference.width}x${reference.height}")
                        }
                        uvStats28867.fill(0f)
                        uvCounts28867.fill(0L)
                        uvTimes28867[0] = now67
                    }
                }
                // A/B: keep a stable, full-frame bitmap as the source and change only
                // the destination transform. No per-frame source rectangle allocation.
                // This isolates dynamic cropping from Compose/Backdrop frame scheduling.
                if (gpuReference288110 && bridgeBitmap != null) {
                    // Read Bridge version for the SAME draw-phase invalidation
                    // behavior as the legacy path; do not launch another Recomposer.
                    // Each Host still has independent geometry, but no per-Host
                    // CPU Bitmap crop/clear/overwrite is performed in normal mode.
                    @Suppress("UNUSED_VARIABLE")
                    val bridgeInvalidation288110 = bridgeVersion.value
                    // Exact native bridge mapping: screen coordinates (pos) map
                    // the shared reference onto the local preview rectangle.
                    // Avoid rounding the origin. Scaling also handles surface
                    // dimensions differing slightly from the folder View bounds.
                    val localScaleX288110 = size.width / previewWidth.coerceAtLeast(1).toFloat()
                    val localScaleY288110 = size.height / previewHeight.coerceAtLeast(1).toFloat()
                    wholeDestination.set(-drawPosition.x * localScaleX288110,
                        -drawPosition.y * localScaleY288110,
                        (screenW - drawPosition.x) * localScaleX288110,
                        (screenH - drawPosition.y) * localScaleY288110)
                    drawIntoCanvas { canvas ->
                        val hw = canvas.nativeCanvas.isHardwareAccelerated
                        if (hw && !reference.isRecycled) {
                            canvas.nativeCanvas.drawBitmap(reference, null,
                                wholeDestination, bitmapPaint)
                        } else {
                            // Preserve the original source pixels if a software
                            // canvas or disposed reference is encountered. This
                            // fallback is deliberately limited to exceptional
                            // frames and is not used by the GPU fast path.
                            bridgeResample(drawPosition)
                            bridgeBitmap.let { canvas.nativeCanvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
                        }
                        if (perfProfile288109) KyantPerf288109.recordGpuReference288110(hw)
                        val num288110 = sourceDrawCount.get()
                        if (num288110 == 0L || num288110 % 240L == 0L) {
                            android.util.Log.i("KyantGpu288110", "KYANT288110_DRAW " +
                                "host=$traceHost28890 hw=$hw gpu=${hw && !reference.isRecycled} " +
                                "posX=${drawPosition.x} posY=${drawPosition.y} " +
                                "source=${reference.width}x${reference.height} " +
                                "target=${size.width}x${size.height} " +
                                "backdropCapture=${nodePipeline28881 < 0 && pipelineMode28819 < 0}")
                        }
                    }
                } else if (bridgeBitmap != null && bitmapPipeline28880 != 2 &&
                    nodePipeline28881 < 0) {
                    // State read here registers the draw invalidation dependency.
                    val version = bridgeVersion.value
                    if (pipelineMode28819 == 2 && frameSync28822 == 1) {
                        // Same callback: sample at the exact position used to draw.
                        // No post() handoff and no independent bridge-version update.
                        bridgeResample(drawPosition)
                        val n = drawCadenceCount.get()
                        if (n % 60L == 1L) XposedBridge.log("KYANT28822_SAME_DRAW posX=${drawPosition.x} publishedX=${bridgePublishedPosition.get()?.x} draw=$n")
                    }
                    if (pipelineMode28819 == 2) trace28820("COMPOSE_DRAW", drawPosition,
                        "consumedVersion=$version prePublished=${bridgePublishedPosition.get()?.x}")
                    if (bridgeSyncMode == 1 || bridgeSyncMode == 2) {
                        // Mode 1: experimental catch-up, as in V288.9.
                        // Mode 2: observe the exact same coordinate mismatch but NEVER
                        // mutate the bridge bitmap or published position during Compose draw.
                        // Logging is rate-limited to avoid introducing additional jank.
                        val nowPos = try { currentNativePosition() } catch (_: Throwable) { null }
                        val published = bridgePublishedPosition.get()
                        if (nowPos != null && (published == null ||
                            kotlin.math.abs(nowPos.x - published.x) > 0.015f ||
                            kotlin.math.abs(nowPos.y - published.y) > 0.015f)) {
                            val dx = if (published == null) 0f else nowPos.x - published.x
                            val dy = if (published == null) 0f else nowPos.y - published.y
                            if (bridgeSyncMode == 1) {
                                bridgeResample(nowPos)
                                if (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f)
                                    XposedBridge.log("KYANT2889_FRAME_CATCHUP dx=$dx dy=$dy version=$version")
                            } else {
                                val n = bridgeMismatchObserved.incrementAndGet()
                                if (n % 60L == 1L) XposedBridge.log(
                                    "KYANT28810_MISMATCH_OBSERVE count=$n dx=$dx dy=$dy version=$version noResample=true")
                            }
                        }
                    }
                    // V288.74 A/B: If Compose consumes a bridge bitmap painted
                    // for an earlier native position, translate its pixels by
                    // the small coordinate delta. This does NOT freeze the source.
                    // Keep mode 0 byte-for-byte equivalent to previous rendering.
                    val published74 = bridgePublishedPosition.get()
                    val lagX74 = if (published74 != null) drawPosition.x - published74.x else 0f
                    val lagY74 = if (published74 != null) drawPosition.y - published74.y else 0f
                    val canShift74 = nativeCarrierAb28869 == 1 && distanceAb28874 == 1 &&
                        published74 != null && kotlin.math.abs(lagX74) <= 8f &&
                        kotlin.math.abs(lagY74) <= 8f &&
                        (kotlin.math.abs(lagX74) > 0.015f || kotlin.math.abs(lagY74) > 0.015f)
                    // A bridge pixel at local x contains wallpaper(global publishedX+x).
                    // For current global drawX, sample the bridge at x+(drawX-publishedX).
                    // Shift the bitmap left by that delta, without any integer rounding.
                    // Mode 1: consume the *published front* instead of the bitmap
                    // that native sampling may already have repainted. Inactive
                    // bridge buffers alternate on each native onDraw callback.
                    val visibleBridge75 = if (bitmapPipeline28880 == 4)
                        immutableBridge28880.value
                        else if (bufferExperiment28875)
                        bridgeFront28875.value else bridgeBitmap
                    val visibleGen75 = if (bitmapPipeline28880 == 4)
                        immutableGen28880.value
                        else if (bufferExperiment28875)
                        bridgeFrontGen28875.value else version.toLong()
                    val visiblePosition75 = if (bufferExperiment28875)
                        bridgeFrontPosition28875.value else published74
                    // V288.91: only in the opt-in right-swipe rebound window,
                    // consume the image and position from the SAME publication.
                    // A stale snapshot is not substituted for a live bridge.
                    val frame91 = if (reboundFrameAb28891 == 1 &&
                        nativeCarrierAb28869 == 1 && pipelineMode28819 == 2)
                        reboundFrame28891.value else null
                    val frameAge91 = if (frame91 != null)
                        SystemClock.elapsedRealtimeNanos() - frame91.sampledAtNs else Long.MAX_VALUE
                    val releaseAge91 = SystemClock.elapsedRealtimeNanos() - releaseNs28820.get()
                    val useFrame91 = frame91 != null && frameAge91 in 0L..50_000_000L &&
                        releaseAge91 in 0L..650_000_000L && releaseDx28820 > 0f &&
                        frame91.referenceIdentity == System.identityHashCode(reference) &&
                        (gestureIsolation28894 == 0 || (!touchActive28894 &&
                            frame91.downSerial28894 == gestureDownSerial28893 &&
                            frame91.releaseSerial28894 == releaseSeq28820.get()))
                    // V288.94 invalidates *selection*, never the current canvas or bitmap.
                    // This avoids blank frames when the producer has not sampled again.
                    if (gestureIsolation28894 == 1 && frame91 != null &&
                        sourceDrawCount.get() % 12L == 1L) {
                        val reason94 = when {
                            touchActive28894 -> "TOUCH_ACTIVE"
                            frame91.downSerial28894 != gestureDownSerial28893 -> "OLD_DOWN"
                            frame91.releaseSerial28894 != releaseSeq28820.get() -> "OLD_RELEASE"
                            frameAge91 !in 0L..50_000_000L -> "STALE_AGE"
                            releaseAge91 !in 0L..650_000_000L -> "OUTSIDE_WINDOW"
                            else -> if (useFrame91) "SELECTED" else "OTHER"
                        }
                        XposedBridge.log("KYANT28894_ISOLATE host=$traceHost28890 selected=$useFrame91 " +
                            "reason=$reason94 frameDown=${frame91.downSerial28894} " +
                            "currentDown=$gestureDownSerial28893 frameRelease=${frame91.releaseSerial28894} " +
                            "currentRelease=${releaseSeq28820.get()} active=$touchActive28894 " +
                            "ageMs=${frameAge91 / 1_000_000f} rawGapX=${drawPosition.x - frame91.position.x}")
                    }
                    // V288.101 A/B: prefer a real immutable producer snapshot only
                    // in its original gesture window. Default mode 0 is identical
                    // to V288.100; reject all stale/mismatched snapshots.
                    val pair101 = if (bridgePairAb288101 > 0) bridgePairFrame288101.value else null
                    val now101 = if (bridgePairAb288101 > 0 || bridgePairTrace288101)
                        SystemClock.elapsedRealtimeNanos() else 0L
                    val pairAge101 = if (pair101 != null) now101 - pair101.atNs else -1L
                    val pairStart101 = pair101?.phase == "TOUCH_START" &&
                        bridgePairAb288101 == 2 && touchActive28894 &&
                        now101 - touchDownNs28896 in 0L..150_000_000L
                    val pairRebound101 = pair101?.phase == "REBOUND" &&
                        !touchActive28894 && releaseAction28820 == "UP" &&
                        releaseDownSerial28899 == gestureDownSerial28893 &&
                        now101 - releaseNs28820.get() in 0L..650_000_000L
                    val usePair101 = pair101 != null && !useFrame91 &&
                        pairAge101 in 0L..90_000_000L &&
                        pair101.down == gestureDownSerial28893 &&
                        pair101.release == releaseSeq28820.get() &&
                        pair101.referenceId == System.identityHashCode(reference) &&
                        !pair101.bitmap.isRecycled &&
                        (pairStart101 || pairRebound101)
                    val selectedBitmap91 = when {
                        useFrame91 -> frame91!!.bitmap
                        usePair101 -> pair101!!.bitmap
                        else -> visibleBridge75
                    }
                    val selectedPos91 = when {
                        useFrame91 -> frame91!!.position
                        usePair101 -> pair101!!.pos
                        else -> visiblePosition75
                    }
                    val dx91 = if (selectedPos91 != null) drawPosition.x - selectedPos91.x else 0f
                    val dy91 = if (selectedPos91 != null) drawPosition.y - selectedPos91.y else 0f
                    // V288.91 compensates only small residual motion. A large
                    // offset falls back to the untouched 288.90 draw transform.
                    val compensate91 = useFrame91 && kotlin.math.abs(dx91) <= 10f &&
                        kotlin.math.abs(dy91) <= 10f
                    // V288.95: isolate the source transition responsible for a
                    // suspected first-move flash. This is an opt-in A/B experiment,
                    // NOT a confirmed fix: the direct reference bypasses mutable
                    // carrier pixels only while touching (optionally through 120ms
                    // of rebound). Never apply it to shader/affine sampling because
                    // those sources have a different transform.
                    val directWindow95 = touchActive28894 ||
                        (continuityAb28895 == 2 && releaseSeq28820.get() > 0L &&
                            releaseDx28820 > 0f && releaseAge91 in 0L..120_000_000L)
                    val direct95 = continuityAb28895 > 0 && nativeCarrierAb28869 == 1 &&
                        pipelineMode28819 == 2 && directReferenceEligible28895 &&
                        directWindow95 &&
                        !reference.isRecycled && reference.width == screenW &&
                        reference.height == screenH &&
                        size.width > 0f && size.height > 0f
                    // V288.95.2 SAFETY: direct reference replaces the optical carrier
                    // and caused all glass effects to disappear on device. Keep the
                    // original selected paired/bridge raster source in every mode.
                    // direct95 is now *candidate only* and never changes rendering.
                    // V288.98: A/B ONLY on the existing BRIDGE bitmap, not Shader or wallpaper source.
                    // Mode 0 uses the precise V288.97 draw offsets. Modes 1/2 change only the
                    // small residual when neither legacy compensator is active. Never resample,
                    // mutate state, replace the bitmap or jump across a new gesture.
                    val originalTx98 = if (compensate91) -dx91 else
                        if (canShift74 && !useFrame91 && !usePair101) -lagX74 else 0f
                    val originalTy98 = if (compensate91) -dy91 else
                        if (canShift74 && !useFrame91 && !usePair101) -lagY74 else 0f
                    val now98 = if (sampleSyncAb28898 > 0) SystemClock.elapsedRealtimeNanos() else 0L
                    val startAge98 = now98 - touchDownNs28896
                    val releaseAge98 = now98 - releaseNs28820.get()
                    val inTouchStart98 = touchActive28894 &&
                        startAge98 in 0L..150_000_000L
                    val inRebound98 = !touchActive28894 && releaseSeq28820.get() > 0L &&
                        releaseAge98 in 0L..850_000_000L
                    val experimentWindow98 = sampleSyncAb28898 > 0 &&
                        (inRebound98 || (sampleSyncAb28898 == 2 && inTouchStart98))
                    val bridgeMeta98 = if (experimentWindow98) bridgeCoordinate28892.get() else null
                    val bridgeAge98 = if (bridgeMeta98 != null) now98 - bridgeMeta98.atNs else -1L
                    // IMPORTANT: bridge position metadata does not prove pixel-content identity.
                    // Restrict to a fresh and position-consistent publication, and do not
                    // touch large offsets. This is a bounded diagnostic experiment, not a fix.
                    val validPosition98 = selectedPos91 != null &&
                        selectedPos91.x.isFinite() && selectedPos91.y.isFinite() &&
                        drawPosition.x.isFinite() && drawPosition.y.isFinite()
                    val freshMatchingBridge98 = bridgeMeta98 != null &&
                        bridgeAge98 in 0L..50_000_000L &&
                        bridgeMeta98.referenceIdentity == System.identityHashCode(reference) &&
                        selectedPos91 != null &&
                        kotlin.math.abs(bridgeMeta98.position.x - selectedPos91.x) <= 1.5f &&
                        kotlin.math.abs(bridgeMeta98.position.y - selectedPos91.y) <= 1.5f
                    val boundedGap98 = validPosition98 &&
                        kotlin.math.abs(dx91) <= 24f && kotlin.math.abs(dy91) <= 24f
                    val applySync98 = experimentWindow98 && nativeCarrierAb28869 == 1 &&
                        !useFrame91 && !compensate91 && !canShift74 &&
                        freshMatchingBridge98 && boundedGap98 && selectedBitmap91 != null &&
                        (kotlin.math.abs(dx91) > 0.5f || kotlin.math.abs(dy91) > 0.5f)
                    val actualTx98 = if (applySync98) -dx91 else originalTx98
                    val actualTy98 = if (applySync98) -dy91 else originalTy98
                    if (selectedBitmap91 != null) drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(selectedBitmap91,
                            actualTx98, actualTy98, bitmapPaint)
                    }
                    if (bridgePairAb288101 > 0 || bridgePairTrace288101) {
                        pairLog288101[1]++
                        if (now101 - pairLog288101[0] >= 70_000_000L) {
                            pairLog288101[0] = now101
                            val gap101 = selectedPos91?.let { drawPosition.x - it.x }
                            XposedBridge.log("KYANT288101_PAIR_CONSUME host=$traceHost28890 " +
                                "mode=$bridgePairAb288101 selected=$usePair101 " +
                                "phase=${pair101?.phase} ageMs=${if (pairAge101 >= 0L) pairAge101 / 1_000_000f else -1f} " +
                                "gen=${pair101?.gen} down=${gestureDownSerial28893} " +
                                "frameDown=${pair101?.down} release=${releaseSeq28820.get()} " +
                                "frameRelease=${pair101?.release} x=${drawPosition.x} " +
                                "sampleX=${selectedPos91?.x} gapX=$gap101 " +
                                "bitmapId=${System.identityHashCode(selectedBitmap91)} " +
                                "reason=${when { bridgePairAb288101 == 0 -> "CONTROL"; usePair101 -> "PAIRED"; pair101 == null -> "NO_FRAME"; pairAge101 !in 0L..90_000_000L -> "STALE"; pair101.down != gestureDownSerial28893 -> "OTHER_GESTURE"; else -> "WINDOW_OR_REFERENCE" }}")
                        }
                    }
                    if (experimentWindow98) {
                        syncTraceState28898[1]++
                        if (now98 - syncTraceState28898[0] >= 40_000_000L) {
                            syncTraceState28898[0] = now98
                            val reason98 = when {
                                selectedBitmap91 == null -> "NO_BITMAP"
                                useFrame91 || compensate91 || canShift74 -> "LEGACY_COMPENSATION"
                                !validPosition98 -> "INVALID_POSITION"
                                !freshMatchingBridge98 -> "UNVERIFIED_SOURCE"
                                !boundedGap98 -> "LARGE_GAP"
                                applySync98 -> "APPLIED"
                                else -> "SUBPIXEL"
                            }
                            try {
                                XposedBridge.log("KYANT28898_SYNC host=$traceHost28890 " +
                                    "mode=$sampleSyncAb28898 phase=${if (inTouchStart98) "TOUCH_START" else "REBOUND"} " +
                                    "reason=$reason98 applied=$applySync98 " +
                                    "down=$gestureDownSerial28893 release=${releaseSeq28820.get()} " +
                                    "gapX=$dx91 gapY=$dy91 tx=$actualTx98 ty=$actualTy98 " +
                                    "effectiveX=${selectedPos91?.let { it.x-actualTx98 }} " +
                                    "residualX=${selectedPos91?.let { drawPosition.x-it.x+actualTx98 }} " +
                                    "bridgeAgeMs=${bridgeAge98/1_000_000f} " +
                                    "bridgeGen=${bridgeMeta98?.generation} source=BRIDGE " +
                                    "comp91=$compensate91 comp74=$canShift74")
                            } catch (_: Throwable) { /* diagnostics must not impact optics */ }
                        }
                    }
                    if (continuityAb28895 > 0) {
                        val source95 = if (useFrame91) 2L else 3L
                        val now95 = SystemClock.elapsedRealtimeNanos()
                        val switched95 = sourceTraceState28895[0] != source95
                        if (switched95 || now95 - sourceTraceState28895[1] >= 130_000_000L) {
                            sourceTraceState28895[0] = source95
                            sourceTraceState28895[1] = now95
                            val latest95 = bridgeCoordinate28892.get()
                            XposedBridge.log("KYANT28895_SOURCE host=$traceHost28890 " +
                                "mode=$continuityAb28895 source=${if (useFrame91) "PAIRED" else "BRIDGE"} referenceCandidate=$direct95 safeBypass=true " +
                                "switch=$switched95 active=$touchActive28894 down=$gestureDownSerial28893 " +
                                "release=${releaseSeq28820.get()} releaseAgeMs=${releaseAge91 / 1_000_000f} " +
                                "drawX=${drawPosition.x} bridgeX=${latest95?.position?.x} " +
                                "gapX=${latest95?.let { drawPosition.x - it.position.x }} " +
                                "bridgeAgeMs=${latest95?.let { (now95-it.atNs)/1_000_000f }} " +
                                "pairedGen=${frame91?.generation} bridgeGen=${latest95?.generation} " +
                                "frameSelected=$useFrame91 bitmapId=${if (selectedBitmap91 != null) System.identityHashCode(selectedBitmap91) else -1}")
                        }
                    }
                    if (reboundFrameAb28891 == 1 && reboundTrace28890 &&
                        sourceDrawCount.get() % 12L == 1L) XposedBridge.log(
                        "KYANT28891_CONSUME host=$traceHost28890 selected=$useFrame91 " +
                        "gen=${frame91?.generation} drawX=${drawPosition.x} sampleX=${selectedPos91?.x} " +
                        "deltaX=$dx91 deltaY=$dy91 compensated=$compensate91 " +
                        "ageMs=${if (frameAge91 >= 0L && frameAge91 < 1_000_000_000L) frameAge91/1_000_000f else -1f}")
                    // V288.92: report raw (pre-compensation) positions alongside the
                    // chosen bitmap/position. Only visible hosts during rebound; ≤12
                    // messages per host/release, spaced by at least 40 ms.
                    if (coordinateTrace28892 && nativeCarrierAb28869 == 1 &&
                        pipelineMode28819 == 2) {
                        val diagNow92 = SystemClock.elapsedRealtimeNanos()
                        val seq92 = releaseSeq28820.get()
                        val relAge92 = diagNow92 - releaseNs28820.get()
                        if (coordinateLogState28892[0] != seq92) {
                            coordinateLogState28892[0] = seq92
                            coordinateLogState28892[1] = 0L
                            coordinateLogState28892[2] = 0L
                        }
                        val visible92 = try { visibleSource28867() } catch (_: Throwable) { false }
                        if (visible92 && seq92 > 0L && releaseDx28820 > 0f &&
                            relAge92 in 0L..850_000_000L &&
                            coordinateLogState28892[2] < 12L &&
                            diagNow92 - coordinateLogState28892[1] >= 40_000_000L) {
                            coordinateLogState28892[1] = diagNow92
                            coordinateLogState28892[2]++
                            val diag92 = bridgeCoordinate28892.get()
                            val age92 = if (diag92 != null) (diagNow92 - diag92.atNs)/1_000_000f else -1f
                            val pre92 = predrawSample28890.get()
                            val chosenX92 = selectedPos91?.x
                            val chosenY92 = selectedPos91?.y
                            val appliedX92 = if (compensate91) -dx91 else if (canShift74 && !useFrame91 && !usePair101) -lagX74 else 0f
                            val appliedY92 = if (compensate91) -dy91 else if (canShift74 && !useFrame91 && !usePair101) -lagY74 else 0f
                            // This is the source-image local matrix, NOT the OEM live wallpaper matrix.
                            XposedBridge.log("KYANT28892_COORD host=$traceHost28890 release=$seq92 " +
                                "releaseAgeMs=${relAge92/1_000_000f} visible=$visible92 " +
                                "rawDrawX=${drawPosition.x} rawDrawY=${drawPosition.y} " +
                                "bridgeSampleX=${diag92?.position?.x} bridgeSampleY=${diag92?.position?.y} " +
                                "chosenSampleX=$chosenX92 chosenSampleY=$chosenY92 " +
                                "rawGapX=${diag92?.let { drawPosition.x-it.position.x }} " +
                                "rawGapY=${diag92?.let { drawPosition.y-it.position.y }} " +
                                "appliedShiftX=$appliedX92 appliedShiftY=$appliedY92 " +
                                "frameSelected=$useFrame91 frameGen=${frame91?.generation} " +
                                "bridgeGen=${diag92?.generation} bridgeAgeMs=$age92 " +
                                "path=${diag92?.path} sourceMatrixTx=${diag92?.sourceMatrixTx} " +
                                "sourceMatrixTy=${diag92?.sourceMatrixTy} " +
                                "sourceScaleX=${diag92?.scaleX} sourceScaleY=${diag92?.scaleY} " +
                                "drawScaleX=$scaleX drawScaleY=$scaleY " +
                                "screen=${diag92?.screenWidth}x${diag92?.screenHeight} " +
                                "ref=${diag92?.referenceWidth}x${diag92?.referenceHeight} " +
                                "refMatch=${diag92?.referenceIdentity == System.identityHashCode(reference)} " +
                                "predrawX=${pre92?.pos?.x} " +
                                "sourceBitmap=${if (selectedBitmap91 != null) System.identityHashCode(selectedBitmap91) else -1}")
                        }
                    }
                    // V288.93: independent read-only timeline. Do not infer actual GPU
                    // presentation timing from these UI-thread callback timestamps.
                    if (timelineTrace28893 && nativeCarrierAb28869 == 1 &&
                        pipelineMode28819 == 2) {
                        val now93 = SystemClock.elapsedRealtimeNanos()
                        val seq93 = releaseSeq28820.get()
                        val sinceRelease93 = now93 - releaseNs28820.get()
                        val currentBucket93 = sinceRelease93 / 50_000_000L
                        if (timelineLogState28893[0] != seq93) {
                            timelineLogState28893[0] = seq93
                            timelineLogState28893[1] = 0L
                            timelineLogState28893[2] = 0L
                            previousGap28893[0] = Float.NaN
                            previousGap28893[1] = Float.NaN
                        }
                        val eligible93 = seq93 > 0L && releaseDx28820 > 0f &&
                            sinceRelease93 in 0L..950_000_000L &&
                            try { visibleSource28867() } catch (_: Throwable) { false }
                        if (eligible93 && timelineLogState28893[2] < 18L &&
                            now93 - timelineLogState28893[1] >= 40_000_000L) {
                            timelineLogState28893[1] = now93
                            timelineLogState28893[2]++
                            val bridge93 = bridgeCoordinate28892.get()
                            val pre93 = predrawSample28890.get()
                            val live93 = bridgeSample28890.get()
                            val gap93 = bridge93?.let { drawPosition.x-it.position.x }
                            val gapY93 = bridge93?.let { drawPosition.y-it.position.y }
                            val preAge93 = pre93?.let { (now93-it.atNs)/1_000_000f }
                            val bridgeAge93 = bridge93?.let { (now93-it.atNs)/1_000_000f }
                            val sampleAge93 = live93?.let { (now93-it.atNs)/1_000_000f }
                            val ws93 = activeTouchWorkspace28820?.get()
                            XposedBridge.log("KYANT28893_TIMELINE host=$traceHost28890 release=$seq93 " +
                                "releaseAgeMs=${sinceRelease93/1_000_000f} bucket=$currentBucket93 " +
                                "downSerial=$gestureDownSerial28893 " +
                                "drawX=${drawPosition.x} bridgeX=${bridge93?.position?.x} " +
                                "gapX=$gap93 gapY=$gapY93 " +
                                "previousGapX=${previousGap28893[0]} " +
                                "gapStepX=${gap93?.let { if (previousGap28893[0].isFinite()) it-previousGap28893[0] else Float.NaN }} " +
                                "frameSelected=$useFrame91 chosenGen=${frame91?.generation} " +
                                "bridgeGen=${bridge93?.generation} " +
                                "predrawSeq=${pre93?.serial} predrawRelease=${pre93?.releaseSeq} " +
                                "predrawX=${pre93?.pos?.x} predrawAgeMs=$preAge93 " +
                                "bridgeAgeMs=$bridgeAge93 sampledAgeMs=$sampleAge93 " +
                                "workspaceX=${ws93?.x} workspaceTx=${ws93?.translationX} " +
                                "workspaceScrollX=${ws93?.scrollX} " +
                                "refMatch=${bridge93?.referenceIdentity == System.identityHashCode(reference)}")
                            if (gap93 != null) {
                                observeCommonGap28893(seq93, traceHost28890,
                                    sinceRelease93, gap93, kotlin.math.abs(gap93) >= 0.5f)
                                previousGap28893[0] = gap93
                            }
                            if (gapY93 != null) previousGap28893[1] = gapY93
                        }
                    }
                    if (bufferExperiment28875 && sourceDrawCount.get() % 24L == 0L)
                        XposedBridge.log("KYANT28875_BUFFER_CONSUME gen=$visibleGen75 " +
                            "front=${System.identityHashCode(visibleBridge75)} " +
                            "sampleX=${drawPosition.x} pubX=${visiblePosition75?.x} " +
                            "positionGapPx=${visiblePosition75?.let { drawPosition.x - it.x }} " +
                            "bridgeVersion=$version")
                    if (nativeCarrierAb28869 == 1 && distanceTrace28874 &&
                        sourceDrawCount.get() % 24L == 0L) XposedBridge.log(
                        "KYANT28874_CONSUME ab=$distanceAb28874 " +
                        "sourceX=${drawPosition.x} publishedX=${published74?.x} " +
                        "lagPx=$lagX74 lagYPx=$lagY74 compensated=$canShift74 " +
                        "version=$version")
                    if (drawCadenceCount.get() % 240L == 0L)
                        XposedBridge.log("KYANT2889_COMPOSE_CONSUME version=$version mode=$bridgeSyncMode")
                } else if (renderAbMode != 1 && (samplingMode == 1 || largeContinuousSource28830)) {
                    val sourceScaleX = size.width / sw.coerceAtLeast(0.001f)
                    val sourceScaleY = size.height / sh.coerceAtLeast(0.001f)
                    wholeDestination.set(-x * sourceScaleX, -y * sourceScaleY,
                        (reference.width - x) * sourceScaleX,
                        (reference.height - y) * sourceScaleY)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(reference, null, wholeDestination, bitmapPaint)
                    }
                } else {
                // Keep the wallpaper's continuous source-to-target transform. The source
                // rectangle is expanded by one pixel for bilinear filtering, and the
                // destination is positioned with float precision. No roundToInt origin.
                val left = kotlin.math.floor(x).toInt().coerceAtLeast(0)
                val top = kotlin.math.floor(y).toInt().coerceAtLeast(0)
                val right = kotlin.math.ceil(x + sw).toInt().coerceAtMost(reference.width)
                val bottom = kotlin.math.ceil(y + sh).toInt().coerceAtMost(reference.height)
                if (right > left && bottom > top && sw > 0f && sh > 0f) {
                    val dst = RectF(
                        (left - x) * size.width / sw,
                        (top - y) * size.height / sh,
                        (right - x) * size.width / sw,
                        (bottom - y) * size.height / sh)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(reference,
                            Rect(left, top, right, bottom), dst,
                            bitmapPaint)
                    }
                }
                }
                if (nodeTrace28881 && nodePipeline28881 in 1..4 &&
                    sourceDrawCount.get() % 120L == 0L) XposedBridge.log(
                    "KYANT28881_COMPOSE_DRAW node=$nodePipeline28881 x=${drawPosition.x} " +
                    "rootX=$rootScreenX layer=${nodePipeline28881 == 3} " +
                    "clip=${nodePipeline28881 != 4}")
                if (bitmapTrace28880 && bitmapPipeline28880 in 2..4 &&
                    sourceDrawCount.get() % 120L == 0L) XposedBridge.log(
                    "KYANT28880_COMPOSE_SOURCE stage=$bitmapPipeline28880 " +
                    "bridge=${bitmapPipeline28880 != 2} snapshotGen=${immutableGen28880.value} " +
                    "x=${drawPosition.x} bridgeGen=${bridgeVersion.value}")
                val now = SystemClock.elapsedRealtimeNanos()
                val previous = sourceLastDrawNs.getAndSet(now)
                val draws = sourceDrawCount.incrementAndGet()
                if (rootPipelineTrace28879 && rootPipeline28879 in 1..4 && draws % 120L == 1L)
                    XposedBridge.log("KYANT28879_PIPELINE_SOURCE stage=$rootPipeline28879 " +
                        "draw=$draws originX=$rootScreenX bridge=${bridgeBitmap != null} " +
                        "bridgeGen=${bridgeVersion.value} capture=${!pipelineSourceOnly28879}")
                if (nativeCarrierAb28869 == 1 && draws % 60L == 1L) XposedBridge.log(
                    "KYANT28869_OPTICAL_CONSUME draws=$draws nativeGen=${bridgeVersion.value} " +
                    "composeOriginX=$rootScreenX publishedX=${bridgePublishedPosition.get()?.x} " +
                    "posGapPx=${bridgePublishedPosition.get()?.let { kotlin.math.abs(rootScreenX - it.x) }}")
                if (pipelineMode28819 >= 0 && draws % 60L == 1L) XposedBridge.log(
                    "KYANT28819_COMPOSE_DRAW mode=$pipelineMode28819 draws=$draws " +
                    "sourceX=$rootScreenX bridge=${bridgeBitmap != null} " +
                    "bridgeVersion=${bridgeVersion.value} publishedX=${bridgePublishedPosition.get()?.x}")
                if (previous > 0L) {
                    val delta = now - previous
                    if (delta <= 100_000_000L) {
                        if (delta > 16_666_667L) sourceOver16.incrementAndGet()
                        if (delta > 33_333_334L) sourceOver33.incrementAndGet()
                    }
                    sourceMaxGapNs.accumulateAndGet(delta) { a, b -> maxOf(a, b) }
                }
                // Count actual source draw invocations over a one-second wall-clock window.
                if (drawCadenceWindowNs.get() == 0L) drawCadenceWindowNs.compareAndSet(0L, now)
                drawCadenceCount.incrementAndGet()
                if (previous > 0L) {
                    val gap = now - previous
                    if (gap > 16_666_667L) drawCadenceOver16.incrementAndGet()
                    if (gap > 33_333_334L) drawCadenceOver33.incrementAndGet()
                    drawCadenceMaxNs.accumulateAndGet(gap) { a, b -> maxOf(a, b) }
                }
                val windowStart = drawCadenceWindowNs.get()
                if (now - windowStart >= 1_000_000_000L && drawCadenceWindowNs.compareAndSet(windowStart, now)) {
                    XposedBridge.log("KYANT2885_DRAW_CADENCE elapsedMs=" + ((now - windowStart) / 1_000_000L) +
                        " draws=" + drawCadenceCount.getAndSet(0L) +
                        " over16=" + drawCadenceOver16.getAndSet(0L) +
                        " over33=" + drawCadenceOver33.getAndSet(0L) +
                        " maxGapMs=" + (drawCadenceMaxNs.getAndSet(0L) / 1_000_000L) +
                        " sourceX=" + rootScreenX + " sourceY=" + rootScreenY +
                        " mode=" + motionAbMode)
                }
                // Diagnostics only: compare the actual Compose source position to
                // the latest PreDraw matrix observation; no writes or invalidations.
                if (draws % 120L == 0L) {
                    val observed = predrawMatrixSnapshot.get()
                    if (legacySyncAbMode == 1) {
                        XposedBridge.log("KYANT2886_DRAW_MATRIX_COMPARE nativeX=" + livePosition.x +
                            " nativeY=" + livePosition.y + " committedX=" + committedPosition.x +
                            " committedY=" + committedPosition.y +
                            " deltaX=" + (livePosition.x - committedPosition.x) +
                            " deltaY=" + (livePosition.y - committedPosition.y))
                    }
                    val ageMs = if (observed.second > 0L)
                        (now - observed.second) / 1_000_000L else -1L
                    XposedBridge.log("KYANT2885_SOURCE_MATRIX_DELTA draws=" + draws +
                        " matrixX=" + observed.first.x + " matrixY=" + observed.first.y +
                        " sourceX=" + rootScreenX + " sourceY=" + rootScreenY +
                        " motionAb=" + motionAbMode +
                        " liveX=" + livePosition.x + " liveY=" + livePosition.y +
                        " dx=" + (rootScreenX - observed.first.x) +
                        " dy=" + (rootScreenY - observed.first.y) +
                        " matrixAgeMs=" + ageMs)
                }
                if (draws % 240L == 0L) {
                    XposedBridge.log("KYANT2885_SOURCE_DRAW mode=$samplingMode renderAb=$renderAbMode motionAb=$motionAbMode draws=$draws " +
                        "gapMs=" + (if (previous > 0L) (now - previous) / 1_000_000L else -1L) + " " +
                        "over16=${sourceOver16.get()} over33=${sourceOver33.get()} maxGapMs=${sourceMaxGapNs.get()/1_000_000L} " +
                        "sampleX=$rootScreenX sampleY=$rootScreenY bitmapIdentity=" +
                        System.identityHashCode(reference))
                }
                if (perfProfile288109) KyantPerf288109.record(
                    KyantPerf288109.COMPOSE_SOURCE,
                    SystemClock.elapsedRealtimeNanos() - composeDrawStart288109)
            }
            // Mode B: source Bitmap is displayed by Compose, but no Kyant backdrop
            // is captured or rendered. Mode C keeps the original optical pipeline.
            if (nodePipeline28881 < 0 && pipelineMode28819 < 0 &&
                !pipelineSourceOnly28879 && !pipelineCaptureOnly28879 &&
                (displayIsolationMode != 2 || nativeLayerMode in 2..3)) Box(Modifier.fillMaxSize().drawBackdrop(
                backdrop = backdrop,
                // The OEM radius is in physical pixels. Convert to dp and clamp to avoid a circle.
                shape = { RoundedRectangle(cornerDp) },
                effects = {
                    val m = size.minDimension
                    blur(optics28827.blur.dp.toPx())
                    if (rootPipeline28879 != 3 && motionAbMode != 2 && zeroLensAb28868 == 0 && optics28827.strength > 0) {
                        val amount28827 = optics28827.strength / 100f
                        lens(refractionHeight = .1f * m * amount28827,
                            refractionAmount = .2f * m * amount28827,
                            depthEffect = true, chromaticAberration = false)
                    }
                },
                highlight = { Highlight(alpha = optics28827.highlight / 100f,
                    style = com.kyant.backdrop.highlight.HighlightStyle.Plain) }
            ))
        }
        SideEffect { onComposed() }
    }
}
