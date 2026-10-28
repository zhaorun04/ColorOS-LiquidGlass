package top.liquidglass.vector;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorOSLiquidGlass";
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg = p.packageName;
        if (!"com.android.systemui".equals(pkg)
                && !"com.oplus.systemui.plugins".equals(pkg)
                && !"com.android.launcher".equals(pkg)) return;
        String msg = "V1.0 HOOK_LOADED package=" + pkg + " process=" + p.processName;
        Log.i(TAG, msg);
        de.robv.android.xposed.XposedBridge.log(TAG + ": " + msg);
        // Deliberately do not change any system view in the compatibility test.
    }
}
