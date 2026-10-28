package top.liquidglass.vector;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
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
    private static final String TAG="ColorOSLiquidGlass";
    private static final Set<Object> SEEN=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    private static void log(String s) {
        Log.i(TAG,s);
        try { XposedBridge.log(TAG+": "+s); } catch(Throwable ignored) {}
    }
    private static Class<?> find(String name,ClassLoader loader) {
        try { return Class.forName(name,false,loader); }
        catch(Throwable t) { log("CLASS_UNAVAILABLE name="+name+" reason="+t.getClass().getSimpleName());return null; }
    }
    private static void inspect(String label,Object object) {
        if(!(object instanceof View)) return;
        View v=(View)object;
        synchronized(SEEN) { if(!SEEN.add(object)) return; }
        Drawable d=null;
        try {d=v.getBackground();}catch(Throwable ignored){}
        log("INSTANCE label="+label+" class="+v.getClass().getName()+" size="+v.getWidth()+"x"+v.getHeight()+" background="+(d==null?"null":d.getClass().getName()));
        if("FolderIcon".equals(label)) {
            try {
                Field f=v.getClass().getDeclaredField("mIsSupportNewBlurLocal");
                f.setAccessible(true);
                log("FOLDER_BLUR_FLAG value="+f.getBoolean(v));
            } catch(Throwable t) {log("FOLDER_BLUR_FLAG_ERROR="+t.getClass().getSimpleName());}
        }
        if("Folder".equals(label)) {
            try {
                Field f=v.getClass().getDeclaredField("mBackground");
                f.setAccessible(true);
                Object value=f.get(v);
                log("FOLDER_BACKGROUND_FIELD="+(value==null?"null":value.getClass().getName()));
            } catch(Throwable t) {log("FOLDER_BACKGROUND_ERROR="+t.getClass().getSimpleName());}
        }
    }
    private static int installed=0;
    private static int failed=0;
    private static void hook(Class<?> c,String name,Class<?>[] types,final String label) {
        if(c==null)return;
        try {
            Method method=c.getDeclaredMethod(name,types);
            method.setAccessible(true);
            XposedBridge.hookMethod(method,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {inspect(label,param.thisObject);}
                    catch(Throwable t) {log("PROBE_ERROR label="+label+" reason="+t.getClass().getSimpleName());}
                }
            });
            installed++;
            log("HOOK_INSTALLED class="+c.getName()+" method="+name);
        } catch(Throwable t) {
            failed++;
            log("HOOK_FAILED class="+c.getName()+" method="+name+" reason="+t.getClass().getName()+" message="+String.valueOf(t.getMessage()));
        }
    }
    private static final Class<?>[] LAYOUT={boolean.class,int.class,int.class,int.class,int.class};
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        String pkg=p.packageName;
        if(!"com.android.systemui".equals(pkg)&&!"com.android.launcher".equals(pkg))return;
        installed=0;failed=0;
        log("V1.4 HOOK_COMPAT_LOADED package="+pkg+" process="+p.processName+" loader="+p.classLoader);
        if("com.android.systemui".equals(pkg)) {
            hook(find("com.android.systemui.qs.tileimpl.QSTileViewImpl",p.classLoader),"onLayout",LAYOUT,"QSTileViewImpl");
            hook(find("com.android.systemui.qs.QSPanel",p.classLoader),"onLayout",LAYOUT,"QSPanel");
        } else {
            hook(find("com.android.launcher3.folder.Folder",p.classLoader),"onAttachedToWindow",new Class<?>[0],"Folder");
            hook(find("com.android.launcher3.folder.FolderIcon",p.classLoader),"dispatchDraw",new Class<?>[]{Canvas.class},"FolderIcon");
        }
        log("V1.4 PROBE_READY package="+pkg+" installed="+installed+" failed="+failed);
    }
}
