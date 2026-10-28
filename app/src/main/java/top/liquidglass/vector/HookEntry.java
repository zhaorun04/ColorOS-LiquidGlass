package top.liquidglass.vector;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Read-only discovery: never hooks or mutates UI. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorOSLiquidGlass";
    private static void report(String message) {
        Log.i(TAG, message);
        de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;
        if (!"com.android.systemui".equals(pkg)
                && !"com.oplus.systemui.plugins".equals(pkg)
                && !"com.android.launcher".equals(pkg)) return;
        report("V1.1 DIAGNOSTICS_LOADED package=" + pkg + " process=" + p.processName);
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
            try {
                Class<?> cls = Class.forName(name, false, p.classLoader);
                report("CLASS_FOUND package=" + pkg + " name=" + cls.getName());
            } catch (ClassNotFoundException e) {
                report("CLASS_MISSING package=" + pkg + " name=" + name);
            } catch (Throwable t) {
                report("CLASS_CHECK_ERROR package=" + pkg + " name=" + name
                    + " error=" + t.getClass().getSimpleName());
            }
        }
    }
}
