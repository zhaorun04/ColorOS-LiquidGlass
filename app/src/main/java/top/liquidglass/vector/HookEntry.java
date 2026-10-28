package top.liquidglass.vector;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorOSLiquidGlass";
    // Safe default: diagnostics only. Set true and rebuild for folder glass experiment.
    private static final boolean ENABLE_FOLDER_GLASS_EXPERIMENT = false;
    private static final Set<Object> SEEN = Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    private static void report(String s) {
        Log.i(TAG, s);
        XposedBridge.log(TAG + ": " + s);
    }
    private static Class<?> lookup(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (Throwable t) { report("CLASS_UNAVAILABLE name="+name+" loader="+loader); return null; }
    }
    private static String bg(View v) {
        try { Drawable d=v.getBackground(); return d==null ? "null" : d.getClass().getName(); }
        catch (Throwable t) { return "error:"+t.getClass().getSimpleName(); }
    }
    private static void inspectView(String label, Object o) {
        if (!(o instanceof View)) return;
        View v=(View)o;
        synchronized(SEEN) { if (!SEEN.add(o)) return; }
        report("INSTANCE label="+label+" class="+v.getClass().getName()+" id="+v.getId()+" size="+v.getWidth()+"x"+v.getHeight()+" background="+bg(v));
        if (label.equals("Folder")) {
            try {
                Field f=v.getClass().getDeclaredField("mBackground"); f.setAccessible(true);
                Object d=f.get(v); report("FOLDER_FIELD mBackground="+(d==null?"null":d.getClass().getName()));
            } catch(Throwable t) { report("FOLDER_FIELD_ERROR "+t.getClass().getSimpleName()); }
        }
        if (label.equals("FolderIcon")) {
            try {
                Field f=v.getClass().getDeclaredField("mIsSupportNewBlurLocal"); f.setAccessible(true);
                report("FOLDER_BLUR_FLAG value="+f.getBoolean(v));
            } catch(Throwable t) { report("FOLDER_BLUR_FLAG_ERROR "+t.getClass().getSimpleName()); }
        }
    }
    private static void folderGlass(View v) {
        if (!ENABLE_FOLDER_GLASS_EXPERIMENT) return;
        try {
            // Only the expanded folder's background, never SystemUI or individual app icons.
            GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x66FFFFFF,0x28FFFFFF,0x40D9F2FF});
            glass.setCornerRadius(34f * v.getResources().getDisplayMetrics().density);
            glass.setStroke(Math.max(1,(int)(v.getResources().getDisplayMetrics().density)),0x88FFFFFF);
            v.setBackground(glass);
            report("FOLDER_GLASS_APPLIED class="+v.getClass().getName());
        } catch(Throwable t) { report("FOLDER_GLASS_ERROR "+t.getClass().getName()); }
    }
    private static void hookMethod(Class<?> c, String method, final String label, final boolean apply) {
        if (c==null) return;
        try {
            Method m=c.getDeclaredMethod(method);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        inspectView(label,p.thisObject);
                        if(apply && p.thisObject instanceof View) folderGlass((View)p.thisObject);
                    } catch(Throwable t) { report("PROBE_ERROR label="+label+" error="+t.getClass().getSimpleName()); }
                }
            });
            report("HOOK_INSTALLED class="+c.getName()+" method="+method);
        } catch(Throwable t) { report("HOOK_FAILED class="+c.getName()+" method="+method+" error="+t.getClass().getSimpleName()); }
    }
    private static void hookLayout(Class<?> c, final String label) {
        if(c==null) return;
        try {
            Method m=c.getDeclaredMethod("onLayout",boolean.class,int.class,int.class,int.class,int.class);
            XposedBridge.hookMethod(m,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { inspectView(label,p.thisObject); }
                    catch(Throwable t) { report("LAYOUT_PROBE_ERROR "+t.getClass().getSimpleName()); }
                }
            });
            report("HOOK_INSTALLED class="+c.getName()+" method=onLayout");
        } catch(Throwable t) { report("HOOK_FAILED class="+c.getName()+" method=onLayout error="+t.getClass().getSimpleName()); }
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg=p.packageName;
        if(!"com.android.systemui".equals(pkg) && !"com.android.launcher".equals(pkg)) return;
        report("V1.3 RUNTIME_PROBE_LOADED package="+pkg+" process="+p.processName+" loader="+p.classLoader);
        if("com.android.launcher".equals(pkg)) {
            hookMethod(lookup("com.android.launcher3.folder.Folder",p.classLoader),"onAttachedToWindow","Folder",true);
            hookMethod(lookup("com.android.launcher3.folder.FolderIcon",p.classLoader),"onAttachedToWindow","FolderIcon",false);
        } else {
            hookLayout(lookup("com.android.systemui.qs.tileimpl.QSTileViewImpl",p.classLoader),"QSTileViewImpl");
            hookLayout(lookup("com.android.systemui.qs.QSPanel",p.classLoader),"QSPanel");
        }
        report("V1.3 PROBE_READY package="+pkg);
    }
}
