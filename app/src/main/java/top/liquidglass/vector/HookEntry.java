package top.liquidglass.vector;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Read-only view discovery. Does not install hooks or mutate UI. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorOSLiquidGlass";
    private static void report(String message) {
        Log.i(TAG, message);
        de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
    }
    private static boolean relevant(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        return s.contains("draw") || s.contains("background") || s.contains("blur")
            || s.contains("corner") || s.contains("radius") || s.contains("tint")
            || s.contains("color") || s.contains("attach") || s.contains("inflate")
            || s.contains("bind") || s.contains("tile") || s.contains("folder")
            || s.contains("update") || s.contains("layout") || s.contains("view");
    }
    private static void inspect(Class<?> cls) {
        report("CLASS_FOUND name=" + cls.getName() + " superclass="
            + (cls.getSuperclass() == null ? "none" : cls.getSuperclass().getName()));
        int count = 0;
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (count++ >= 8) break;
                report("CTOR class=" + cls.getName() + " signature=" + c.toGenericString());
            }
        } catch (Throwable t) { report("CTOR_ERROR class=" + cls.getName() + " error=" + t.getClass().getSimpleName()); }
        count = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!relevant(m.getName())) continue;
                if (count++ >= 24) { report("METHOD_LIMIT class=" + cls.getName()); break; }
                report("METHOD class=" + cls.getName() + " signature=" + m.toGenericString());
            }
        } catch (Throwable t) { report("METHOD_ERROR class=" + cls.getName() + " error=" + t.getClass().getSimpleName()); }
        count = 0;
        try {
            for (Field f : cls.getDeclaredFields()) {
                if (!relevant(f.getName()) && !relevant(f.getType().getSimpleName())) continue;
                if (count++ >= 20) { report("FIELD_LIMIT class=" + cls.getName()); break; }
                report("FIELD class=" + cls.getName() + " signature=" + f.toGenericString());
            }
        } catch (Throwable t) { report("FIELD_ERROR class=" + cls.getName() + " error=" + t.getClass().getSimpleName()); }
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;
        if (!"com.android.systemui".equals(pkg) && !"com.android.launcher".equals(pkg)
                && !"com.oplus.systemui.plugins".equals(pkg)) return;
        report("V1.2 VIEW_DISCOVERY_LOADED package=" + pkg + " process=" + p.processName);
        String[] candidates = "com.android.systemui".equals(pkg) ? new String[] {
            "com.android.systemui.qs.tileimpl.QSTileViewImpl",
            "com.android.systemui.qs.tileimpl.QSIconViewImpl",
            "com.android.systemui.qs.QSPanel",
            "com.android.systemui.qs.QuickQSPanel",
            "com.android.systemui.qs.QSContainerImpl",
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        } : "com.android.launcher".equals(pkg) ? new String[] {
            "com.android.launcher3.folder.Folder",
            "com.android.launcher3.folder.FolderIcon",
            "com.android.launcher3.folder.FolderPagedView",
            "com.android.launcher3.Launcher"
        } : new String[] {
            "com.oplus.systemui.plugins.OplusSystemUIPlugin",
            "com.oplus.systemui.plugins.SystemUIPlugin"
        };
        for (String name : candidates) {
            try { inspect(Class.forName(name, false, p.classLoader)); }
            catch (ClassNotFoundException e) { report("CLASS_MISSING package=" + pkg + " name=" + name); }
            catch (Throwable t) { report("CLASS_CHECK_ERROR name=" + name + " error=" + t.getClass().getSimpleName()); }
        }
        report("V1.2 DISCOVERY_DONE package=" + pkg);
    }
}
