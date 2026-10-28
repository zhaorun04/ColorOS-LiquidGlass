package top.liquidglass.vector;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Path;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Uri CONFIG = Uri.parse("content://top.liquidglass.vector.config/settings");
    private static volatile long lastRead = 0;
    private static volatile boolean glassEnabled = false;
    private static volatile int glassAlpha = 210, tintMode = 0, tintStrength = 30;
    private static volatile boolean highlightEnabled = false;
    private static volatile int highlightStrength = 25;
    private static final java.util.Map<Drawable,Integer> ORIGINAL_ALPHA = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Drawable,Integer>());
    private static final java.util.Set<Drawable> TINTED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Drawable,Boolean>());
    private static volatile boolean diagnostics = true;
    private static int alphaLogs = 0;
    private static volatile String lastConfigSummary="";
    private static final Set<Object> SEEN = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static int installed, failed, drawCount;
    private static int backgroundCalls, drawableCalls, blurCalls, switchLogs;
    private static void log(String s) {
        Log.i(TAG, s);
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) {}
    }
    private static Class<?> find(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (Throwable t) { log("CLASS_UNAVAILABLE name=" + name + " reason=" + t.getClass().getSimpleName()); return null; }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static void inspect(String label, Object object) {
        if (!(object instanceof View)) return;
        View v = (View) object;
        synchronized (SEEN) { if (!SEEN.add(object)) return; }
        Drawable bg = v.getBackground();
        log("INSTANCE label=" + label + " class=" + v.getClass().getName() + " size=" + v.getWidth() + "x" + v.getHeight() + " background=" + (bg == null ? "null" : bg.getClass().getName()));
        if ("FolderIcon".equals(label)) {
            try { log("FOLDER_BLUR_FLAG value=" + field(v.getClass(), "mIsSupportNewBlurLocal").get(v)); }
            catch (Throwable t) { log("FOLDER_BLUR_FLAG_ERROR=" + t.getClass().getSimpleName()); }
            try {
                Object preview = field(v.getClass(), "mBackground").get(v);
                log("PREVIEW_BACKGROUND class=" + (preview == null ? "null" : preview.getClass().getName()));
                if (preview != null) {
                    for (Class<?> k=preview.getClass();k!=null;k=k.getSuperclass()) {
                        for (Field f:k.getDeclaredFields()) {
                            String n=f.getName().toLowerCase();
                            if(n.contains("blur") || n.contains("drawable")) {
                                try { f.setAccessible(true); Object val=f.get(preview); log("NATIVE_BLUR_FIELD name="+f.getName()+" type="+f.getType().getName()+" value="+(val==null?"null":val.getClass().getName())); }
                                catch(Throwable e){log("NATIVE_BLUR_FIELD_ERROR name="+f.getName()+" reason="+e.getClass().getSimpleName());}
                            }
                        }
                    }
                }
                if (preview != null) {
                    int n = 0;
                    for (Method m : preview.getClass().getDeclaredMethods()) {
                        String name = m.getName().toLowerCase();
                        if (name.contains("draw") || name.contains("blur") || name.contains("color") || name.contains("alpha")) {
                            log("PREVIEW_METHOD " + m.toGenericString());
                            if (++n >= 16) break;
                        }
                    }
                    n = 0;
                    for (Field f : preview.getClass().getDeclaredFields()) {
                        String name = f.getName().toLowerCase();
                        if (name.contains("paint") || name.contains("blur") || name.contains("color") || name.contains("alpha")) {
                            log("PREVIEW_FIELD " + f.toGenericString());
                            if (++n >= 12) break;
                        }
                    }
                }
            } catch (Throwable t) { log("PREVIEW_BACKGROUND_ERROR=" + t.getClass().getSimpleName()); }
        }
        if ("Folder".equals(label)) {
            try {
                Object value = field(v.getClass(), "mBackground").get(v);
                log("FOLDER_BACKGROUND_FIELD=" + (value == null ? "null" : value.getClass().getName()));
            } catch (Throwable t) { log("FOLDER_BACKGROUND_ERROR=" + t.getClass().getSimpleName()); }
        }
    }
    private static void refresh(View v) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now-lastRead < 1500) return;
        lastRead=now;
        try (Cursor c = v.getContext().getContentResolver().query(CONFIG,null,null,null,null)) {
            if(c!=null && c.moveToFirst()) {
                glassEnabled=c.getInt(0)==1;
                glassAlpha=Math.max(40,Math.min(255,c.getInt(1)));
                tintMode=Math.max(0,Math.min(3,c.getInt(2)));
                tintStrength=Math.max(0,Math.min(90,c.getInt(3)));
                highlightEnabled=c.getInt(4)==1;
                diagnostics=c.getInt(5)==1;
                highlightStrength=Math.max(0,Math.min(100,c.getInt(6)));
                String summary="enabled="+glassEnabled+" alpha="+glassAlpha+" tint="+tintMode+" tintStrength="+tintStrength+" highlight="+highlightEnabled+" highlightStrength="+highlightStrength;
                if(!summary.equals(lastConfigSummary)){lastConfigSummary=summary;log("CONFIG_SYNC "+summary);}
            }
        } catch(Throwable t) { if(alphaLogs++<3) log("CONFIG_READ_FAILED reason="+t.getClass().getSimpleName()); }
    }
    private static int tintColor() {
        if(tintMode==1)return Color.rgb(225,240,255);
        if(tintMode==2)return Color.rgb(185,192,202);
        return Color.rgb(40,50,65);
    }
    private static void applyNativeBackground(View v) {
        refresh(v);
        try {
            Object preview=field(v.getClass(),"mBackground").get(v);
            if(preview==null)return;
            Method m=preview.getClass().getDeclaredMethod("getBgDrawable");m.setAccessible(true);
            Object result=m.invoke(preview);
            if(!(result instanceof Drawable))return;
            Drawable d=(Drawable)result;
            if (d instanceof GradientDrawable) {
                synchronized (TRACKED_BACKGROUNDS) { TRACKED_BACKGROUNDS.add(d); }
            }
            synchronized(ORIGINAL_ALPHA){if(!ORIGINAL_ALPHA.containsKey(d))ORIGINAL_ALPHA.put(d,d.getAlpha());}
            int original=ORIGINAL_ALPHA.get(d);
            int requested=glassAlpha;
            int alpha=glassEnabled?requested:original;
            if(d.getAlpha()!=alpha)d.setAlpha(alpha);
            // V1.9.4 tint is painted directly on the native Canvas after background.draw.
            // Clear any legacy color filter previously applied by V1.9.3.
            synchronized(TINTED){if(TINTED.remove(d))d.clearColorFilter();}
            if(alphaLogs++<16)log("MATERIAL_APPLIED enabled="+glassEnabled+" size="+v.getWidth()+"x"+v.getHeight()+
                " alpha="+alpha+" tint="+tintMode+" drawable="+d.getClass().getSimpleName());
        }catch(Throwable t){if(alphaLogs++<12)log("MATERIAL_FAILED reason="+t.getClass().getSimpleName());}
    }
    // Weak references avoid retaining Launcher drawables beyond their lifecycle.
    // Drawing inside GradientDrawable.draw(Canvas) uses the SAME canvas matrix and
    // bounds as the native background. No FolderIcon size/padding guesses.
    private static final Set<Drawable> TRACKED_BACKGROUNDS = Collections.newSetFromMap(new WeakHashMap<Drawable, Boolean>());
    private static int geometryLogs = 0;
    private static int nativeDrawLogs = 0;
    // The path is built from the actual GradientDrawable corner data rather than
    // an arbitrary percentage of the rectangle. The native drawable owns the Canvas matrix.
    private static Path nativeShape(GradientDrawable d, RectF r) {
        Path path = new Path();
        float[] corners = d.getCornerRadii();
        if (corners != null && corners.length == 8) {
            path.addRoundRect(r, corners, Path.Direction.CW);
        } else {
            float radius = Math.max(0f, d.getCornerRadius());
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
        }
        return path;
    }
    private static void drawMaterialOnNativeCanvas(GradientDrawable drawable, Canvas canvas) {
        if (!glassEnabled) return;
        boolean tint = tintMode != 0 && tintStrength > 0;
        boolean shine = highlightEnabled && highlightStrength > 0;
        if (!tint && !shine) return;
        Rect bounds = drawable.getBounds();
        if (bounds == null || bounds.isEmpty()) return;
        RectF base = new RectF(bounds);
        if (base.width() < 4 || base.height() < 4) return;
        Path path = nativeShape(drawable, base);
        if (tint) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setStyle(Paint.Style.FILL);
            int c = tintColor();
            // Overlay opacity is deliberately tied to the slider; no SRC_ATOP filter.
            int opacity = Math.round(160f * tintStrength / 90f);
            fill.setColor(Color.argb(opacity,Color.red(c),Color.green(c),Color.blue(c)));
            canvas.drawPath(path, fill);
            if (nativeDrawLogs++ < 32) log("GLASS_TINT_APPLIED mode="+tintMode+" strength="+tintStrength+" overlayAlpha="+opacity);
        }
        if (shine) {
            float density = drawable.getCallback() instanceof View ?
                    ((View)drawable.getCallback()).getResources().getDisplayMetrics().density :
                    android.content.res.Resources.getSystem().getDisplayMetrics().density;
            float stroke = Math.max(0.7f, density * 0.55f);
            float inset = stroke / 2f;
            RectF strokeRect = new RectF(base.left+inset,base.top+inset,base.right-inset,base.bottom-inset);
            if(strokeRect.width() > 4 && strokeRect.height() > 4){
                Path strokePath = nativeShape(drawable, strokeRect);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                int opacity = Math.round(72f * (highlightStrength / 100f) * (highlightStrength / 100f));
                paint.setColor(Color.argb(opacity,255,255,255));
                canvas.drawPath(strokePath, paint);
                if (nativeDrawLogs++ < 32) log("GLASS_HIGHLIGHT_STRENGTH value="+highlightStrength+" alpha="+opacity+
                    " GLASS_CORNER_RADIUS="+drawable.getCornerRadius()+
                    " GLASS_CORNER_RADII="+java.util.Arrays.toString(drawable.getCornerRadii())+
                    " bounds="+bounds.toShortString());
            }
        }
    }
    private static void hookNativeDrawableDraw() {
        try {
            Method method = GradientDrawable.class.getDeclaredMethod("draw", Canvas.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Drawable) || param.args == null || param.args.length == 0 ||
                            !(param.args[0] instanceof Canvas)) return;
                    Drawable drawable = (Drawable) param.thisObject;
                    synchronized (TRACKED_BACKGROUNDS) {
                        if (!TRACKED_BACKGROUNDS.contains(drawable)) return;
                    }
                    try { drawMaterialOnNativeCanvas((GradientDrawable)drawable, (Canvas)param.args[0]); }
                    catch (Throwable t) {
                        if (geometryLogs++ < 8) log("HIGHLIGHT_NATIVE_ERROR reason=" + t.getClass().getSimpleName());
                    }
                }
            });
            installed++;
            log("NATIVE_DRAW_HOOK_INSTALLED class=android.graphics.drawable.GradientDrawable method=draw");
        } catch (Throwable t) {
            failed++;
            log("NATIVE_DRAW_HOOK_FAILED reason=" + t.getClass().getName() + " message=" + t.getMessage());
        }
    }
    private static void hook(Class<?> c, String methodName, Class<?>[] args, final String label, final boolean draw) {
        if (c == null) return;
        try {
            Method method = c.getDeclaredMethod(methodName, args);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if(draw && param.thisObject instanceof View)applyNativeBackground((View)param.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        inspect(label,param.thisObject);
                        // No highlight in FolderIcon.dispatchDraw: its canvas coordinates
                        // are NOT the same as the background drawable's coordinates.
                    } catch(Throwable t){log("PROBE_ERROR label="+label+" reason="+t.getClass().getSimpleName());}
                }
            });
            installed++;
            log("HOOK_INSTALLED class=" + c.getName() + " method=" + methodName);
        } catch (Throwable t) {
            failed++;
            log("HOOK_FAILED class=" + c.getName() + " method=" + methodName + " reason=" + t.getClass().getName() + " message=" + t.getMessage());
        }
    }
    private static void hookPreview(ClassLoader loader) {
        Class<?> c = find("com.android.launcher3.folder.OplusPreviewBackground", loader);
        if (c == null) return;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            boolean target = ("drawBackground".equals(name) && m.getParameterTypes().length == 2)
                    || ("getBgDrawable".equals(name) && m.getParameterTypes().length == 0)
                    || ("getBlurProp".equals(name) && m.getParameterTypes().length == 0)
                    || ("initBlurDrawable".equals(name))
                    || ("isBgSupportNewBlur".equals(name));
            if (!target) continue;
            try {
                m.setAccessible(true);
                final String methodName = name;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if ("drawBackground".equals(methodName)) {
                                if (backgroundCalls++ < 12) log("BG_DRAW_RUNTIME signature=" + ((Method)param.method).toGenericString()
                                        + " arg1=" + (param.args[1] == null ? "null" : param.args[1].getClass().getName()));
                            } else if ("getBgDrawable".equals(methodName)) {
                                if (drawableCalls++ < 12) {
                                    Object result = param.getResult();
                                    log("BG_DRAWABLE_RUNTIME result=" + (result == null ? "null" : result.getClass().getName())
                                            + (result instanceof Drawable ? " alpha=" + ((Drawable)result).getAlpha() : ""));
                                }
                            } else if (blurCalls++ < 24) {
                                Object result = param.getResult();
                                log("NATIVE_BLUR_CALL method="+methodName+" signature="+((Method)param.method).toGenericString()+" result="+(result==null?"null":String.valueOf(result))+" argCount="+(param.args==null?0:param.args.length));
                            }
                        } catch (Throwable t) { log("BG_RUNTIME_ERROR=" + t.getClass().getSimpleName()); }
                    }
                });
                installed++;
                log("HOOK_INSTALLED class=" + c.getName() + " method=" + m.toGenericString());
            } catch (Throwable t) { failed++; log("HOOK_FAILED class=" + c.getName() + " method=" + m.toGenericString() + " error=" + t); }
        }
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.android.systemui".equals(p.packageName) && !"com.android.launcher".equals(p.packageName)) return;
        installed = 0; failed = 0;
        log("V1.9.4 MATERIAL_CONTROL_LOADED package=" + p.packageName + " process=" + p.processName);
        Class<?>[] layout = {boolean.class, int.class, int.class, int.class, int.class};
        if ("com.android.systemui".equals(p.packageName)) {
            hook(find("com.android.systemui.qs.tileimpl.QSTileViewImpl", p.classLoader), "onLayout", layout, "QSTileViewImpl", false);
            hook(find("com.android.systemui.qs.QSPanel", p.classLoader), "onLayout", layout, "QSPanel", false);
        } else {
            hookNativeDrawableDraw();
            hook(find("com.android.launcher3.folder.Folder", p.classLoader), "onAttachedToWindow", new Class<?>[0], "Folder", false);
            hook(find("com.android.launcher3.folder.FolderIcon", p.classLoader), "dispatchDraw", new Class<?>[]{Canvas.class}, "FolderIcon", true);
            hookPreview(p.classLoader);
        }
        log("V1.9.4 PROBE_READY package=" + p.packageName + " installed=" + installed + " failed=" + failed);
    }
}
