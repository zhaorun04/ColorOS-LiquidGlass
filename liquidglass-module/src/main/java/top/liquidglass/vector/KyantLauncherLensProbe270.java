package top.liquidglass.vector;

import android.graphics.Canvas;
import android.provider.Settings;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;

/** V270: one-shot, 200dp bounded Kyant wallpaper lens experiment in Launcher. */
public final class KyantLauncherLensProbe270 {
    private static final String KEY = "liquidglass_kyant_persistent_probe_2884";
    private static final java.util.Set<View> attempted =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View, Boolean>());
    private static final java.util.Set<View> activeAnchors =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View, Boolean>());
    public static boolean isActiveFor273(View v) { synchronized (activeAnchors) { return activeAnchors.contains(v); } }
    public static void setActiveFor273(View v, boolean active) {
        synchronized (activeAnchors) { if(active) activeAnchors.add(v); else activeAnchors.remove(v); }
    }
    public static void clearAttempt(View v) {
        synchronized (KyantLauncherLensProbe270.class) { attempted.remove(v); }
    }
    // V288.88: the folder host belongs to the selected rendering engine, NOT to
    // the Kyant Compose switch. A native V230 mode must remain usable when the
    // Kyant UI engine is disabled. Keep the existing 400-ms Settings TTL, but
    // do not cache the V230 UI state: it is published by MaterialConfig230.
    private static volatile long lastSwitchCheckMs = 0L;
    private static volatile boolean cachedKyantSwitch = false;
    private static volatile int cachedNativeMode = 0;
    private static volatile boolean cachedContained = true;
    private static final String NATIVE_MODE_KEY_28887 = "liquidglass_kyant_v230_native_ab_28887";
    private static final String CONTAINMENT_KEY_28886 = "liquidglass_kyant_folder_containment_28886";
    public static int selectedEngine28888(android.content.Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSwitchCheckMs >= 400L) {
            synchronized (KyantLauncherLensProbe270.class) {
                now = android.os.SystemClock.uptimeMillis();
                if (now - lastSwitchCheckMs >= 400L) {
                    try {
                        cachedKyantSwitch = Settings.Global.getInt(context.getContentResolver(), KEY, 0) == 1;
                        cachedNativeMode = Math.max(0, Math.min(2, Settings.Global.getInt(
                            context.getContentResolver(), NATIVE_MODE_KEY_28887, 0)));
                        cachedContained = Settings.Global.getInt(context.getContentResolver(),
                            CONTAINMENT_KEY_28886, 1) != 0;
                    } catch (Throwable ignored) {
                        cachedKyantSwitch = false;
                        cachedNativeMode = 0;
                        cachedContained = true;
                    }
                    lastSwitchCheckMs = now;
                }
            }
        }
        // Native experiments are always restricted to the safe FolderIcon host.
        // A disabled V230 UI switch must not silently paint an experimental lens.
        if (cachedContained && cachedNativeMode == 1)
            return MaterialConfig230.get().enabled ? 1 : -1;
        if (cachedContained && cachedNativeMode == 2) return 2;
        return cachedKyantSwitch ? 0 : -1;
    }
    public static boolean isExperimentEnabled(android.content.Context context) {
        return selectedEngine28888(context) >= 0;
    }
    private KyantLauncherLensProbe270() {}
    public static void install(ClassLoader loader) {
        try {
            Class<?> folder = Class.forName("com.android.launcher3.folder.FolderIcon", false, loader);
            int hooks = 0;
            for (Method m : folder.getDeclaredMethods()) {
                if (!m.getName().equals("dispatchDraw") || m.getParameterTypes().length != 1 ||
                    !Canvas.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        View view = (View) param.thisObject;
                        if (!view.isAttachedToWindow()) return;
                        try {
                            if (!isExperimentEnabled(view.getContext())) return;
                            if (KyantLauncherFrameBridge266.peekReferenceFrame270() == null) return;
                            synchronized (KyantLauncherLensProbe270.class) {
                                if (!attempted.add(view)) return;
                            }
                            view.post(() -> {
                                try { KyantLauncherLens270.run(view); }
                                catch (Throwable t) { XposedBridge.log("KYANT2884_RUN_ERROR " + t.getClass().getSimpleName()); }
                            });
                        } catch (Throwable t) { XposedBridge.log("KYANT2884_CHECK_ERROR " + t.getClass().getSimpleName()); }
                    }
                });
                hooks++;
            }
            XposedBridge.log("KYANT2884_INSTALLED hooks=" + hooks + " defaultOff=true layer=folder_child_index_0 legacyGlassMutualExclusion=true");
        } catch (Throwable t) { XposedBridge.log("KYANT2884_INSTALL_ERROR " + t.getClass().getSimpleName()); }
    }
}
