package top.liquidglass.vector;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Path;
import android.graphics.Outline;
import android.graphics.Matrix;
import android.view.ViewGroup;
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
import java.util.HashSet;
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
    private static volatile long CONFIG_READY_AT_146 = 0L;
    private static volatile long CONFIG_UNLOCK_AT_146 = 0L;
    // V1.9.154: ACTION_USER_UNLOCKED fires only after credential storage unlock,
    // not after every ordinary screen unlock. Track both events separately.
    private static volatile long UNLOCK_EVENT_AT_154=0L;
    private static volatile int UNLOCK_EVENT_SEQ_154=0;
    private static volatile int UNLOCK_DRAW_LOGS_154=0;
    private static void requestUnlockRedraw154(final android.os.Handler handler,final int sequence,final int delayMs){
        handler.postDelayed(()->{
            if(sequence!=UNLOCK_EVENT_SEQ_154)return;
            int count=0;
            synchronized(BOOT_FOLDER_VIEWS){
                for(View v:BOOT_FOLDER_VIEWS.keySet()){
                    if(v!=null && v.isAttachedToWindow()){
                        v.postInvalidateOnAnimation();
                        count++;
                    }
                }
            }
            // Existing fallback also finds late FolderIcons and invalidates parents.
            if(delayMs==0 || (count==0 && delayMs==240))invalidateGlassViews();
            log("UNLOCK_REDRAW_154 seq="+sequence+" delay="+delayMs+" folders="+count+
                " frame="+(REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled())+
                " glass="+glassEnabled+" elapsed="+(android.os.SystemClock.uptimeMillis()-UNLOCK_EVENT_AT_154));
        },delayMs);
    }

    private static volatile int CONFIG_LOGS_146 = 0;
    private static volatile boolean configEverLoaded = false;
    private static volatile android.content.Context launcherContext;
    private static volatile long lastExperimentRead = 0;
    private static volatile boolean glassEnabled = false;
    private static volatile int glassAlpha = 210, tintMode = 0, tintStrength = 30;
    private static volatile boolean highlightEnabled = false;
    private static volatile int highlightStrength = 25;
    private static volatile int highlightWidth = 2;
    private static volatile boolean directionalLight = true;
    // V1.9.23: Do not mutate LayerBlurDrawable.mRadius: it is also used as
    // the background corner radius and is not a verified blur-strength API.
    private static volatile int blurRadiusPercent = 100; // legacy config ignored
    private static volatile int customBlurDp=14, refractionPercent=35, glassTintOpacity=18, customBlurMode=1;
    private static int opticalPipelineLogs195=0;
    private static volatile boolean iconOptics204=false, dockGlass204=false;
    private static volatile boolean clearRefraction=false; // false: frosted glass, true: clear refraction
    private static volatile android.graphics.Bitmap CUSTOM_BLUR_FRAME;
    private static volatile android.graphics.Bitmap CUSTOM_BLUR_SOURCE;
    private static volatile int CUSTOM_BLUR_RADIUS=-1;
    private static final java.util.Map<Drawable,Integer> ORIGINAL_ALPHA = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Drawable,Integer>());
    private static final java.util.Set<Drawable> TINTED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Drawable,Boolean>());
    private static volatile boolean diagnostics = false;
    private static volatile boolean wallpaperCompare = false;
    private static final java.util.Map<View, VisualPatch> VISUAL_PATCHES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, VisualPatch>());
    private static int visualProbeLogs = 0;
    private static final class VisualPatch {
        long time; long revision; android.graphics.Bitmap cover, desired; float cx, cy, dx, dy;
        void dispose() { if(cover!=null)cover.recycle(); if(desired!=null)desired.recycle(); }
    }
    private static volatile int probeMode = 0;
    private static volatile boolean nativeAlphaProbe = false;
    private static final Set<String> BLUR_API_SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> BLUR_PARAMS_PROBED = Collections.synchronizedSet(new HashSet<String>());
    private static volatile int nativeBlurExperiment = 0; // 0=off, 1=only material switch accessor
    private static final java.util.Set<String> EXPERIMENT_ONCE = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static int layerTraceLogs = 0;
    private static int previewTraceLogs = 0;
    private static final ThreadLocal<Integer> PREVIEW_DRAW_DEPTH = new ThreadLocal<Integer>();
    private static String drawableTag(Object d) {
        return d == null ? "null" : d.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(d));
    }
    private static int alphaLogs = 0;
    private static volatile String lastConfigSummary="";
    private static final Set<Object> SEEN = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static int installed, failed, drawCount;
    private static int backgroundCalls, drawableCalls, blurCalls, switchLogs;
    private static void log(String s) {
        if(!diagnostics && (s.startsWith("PREVIEW_METHOD ") || s.startsWith("PREVIEW_FIELD ") || s.startsWith("NATIVE_BLUR_FIELD ") || s.startsWith("FUSION_GEOMETRY_FIELD ") || s.startsWith("NATIVE_BLUR_API ") || s.startsWith("NATIVE_BLUR_CHILD_API ")))return;
        Log.i(TAG, s);
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) {}
    }
    private static Class<?> find(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (Throwable t) { log("CLASS_UNAVAILABLE name=" + name + " reason=" + t.getClass().getSimpleName()); return null; }
    }
    // V1.9.132: reflection metadata is stable for the lifetime of a Launcher
    // class loader. Cache it once rather than walking class hierarchies on draw.
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>,
        java.util.concurrent.ConcurrentHashMap<String,Field>> FIELD_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>,Method> BG_GETTERS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        java.util.concurrent.ConcurrentHashMap<String,Field> fields=FIELD_CACHE.get(type);
        if(fields!=null){Field cached=fields.get(name);if(cached!=null)return cached;}
        for(Class<?> c=type;c!=null;c=c.getSuperclass()){
            try {
                Field found=c.getDeclaredField(name);
                found.setAccessible(true);
                if(fields==null){
                    java.util.concurrent.ConcurrentHashMap<String,Field> fresh=new java.util.concurrent.ConcurrentHashMap<>();
                    java.util.concurrent.ConcurrentHashMap<String,Field> previous=FIELD_CACHE.putIfAbsent(type,fresh);
                    fields=previous==null?fresh:previous;
                }
                Field previous=fields.putIfAbsent(name,found);
                return previous==null?found:previous;
            }catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }
    private static Method backgroundGetter(Class<?> type) throws NoSuchMethodException {
        Method cached=BG_GETTERS.get(type);
        if(cached!=null)return cached;
        Method found=type.getDeclaredMethod("getBgDrawable");
        found.setAccessible(true);
        Method previous=BG_GETTERS.putIfAbsent(type,found);
        return previous==null?found:previous;
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
                inspectBlurState(preview,"inspect");
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
    // Root service publishes a last-known-good settings snapshot into Settings.Global.
    // This avoids launching our ContentProvider from ColorOS Launcher at boot,
    // which OplusAppStartupManager blocks on affected devices.
    private static final String BOOT_SNAPSHOT_KEY = "liquidglass_boot_snapshot_v1";
    private static volatile String lastBootSnapshot = "";
    // Separate the user switch from the independent blur/wallpaper sampling pipeline.
    private static volatile long REFRACTION_CONFIG_EPOCH = 0L;
    private static volatile int REFRACTION_SWITCH_LOGS = 0;
    private static boolean restoreBootSnapshot(android.content.Context context) {
        try {
            String raw=android.provider.Settings.Global.getString(context.getContentResolver(),BOOT_SNAPSHOT_KEY);
            if(raw==null || !raw.startsWith("LG1|"))return false;
            String[] v=raw.split("\\|",-1);
            if(v.length!=16 && v.length!=20 && v.length!=21 && v.length!=22 && v.length!=25 && v.length!=27){log("BOOT_SNAPSHOT_REJECT reason=field_count count="+v.length);return false;}
            // Parse all fields first: never partially apply a corrupt snapshot.
            int[] x=new int[15];
            for(int i=0;i<x.length;i++)x[i]=Integer.parseInt(v[i+1]);
            if(x[0]<0||x[0]>1||x[1]<0||x[1]>255||x[2]<0||x[2]>3||x[3]<0||x[3]>90||x[6]<0||x[6]>100||x[8]<1||x[8]>5){log("BOOT_SNAPSHOT_REJECT reason=range alpha="+x[1]+" tint="+x[2]);return false;}
            boolean previousRefraction=REFRACTION_ENABLED;
            glassEnabled=x[0]==1;glassAlpha=x[1];tintMode=x[2];tintStrength=x[3];
            highlightEnabled=x[4]==1;diagnostics=x[5]==1;highlightStrength=x[6];
            probeMode=x[7];highlightWidth=x[8];directionalLight=x[9]==1;
            nativeBlurExperiment=0;blurRadiusPercent=x[11];
            nativeAlphaProbe=false;wallpaperCompare=x[13]==1;REFRACTION_ENABLED=x[14]==1;
            if(v.length>=20){customBlurDp=clampInt(Integer.parseInt(v[16]),0,40);refractionPercent=clampInt(Integer.parseInt(v[17]),0,100);glassTintOpacity=clampInt(Integer.parseInt(v[18]),0,100);customBlurMode=clampInt(Integer.parseInt(v[19]),0,2);}
            if(v.length>=21)clearRefraction="1".equals(v[20]);
            // V196: launcher reads root-published snapshot, not the APK ContentProvider.
            if(v.length<25 && !raw.equals(lastBootSnapshot))log("SURFACE_SNAPSHOT_197_MISSING count="+v.length+" expected=25");
            if(v.length>=25){surfaceMode194=clampInt(Integer.parseInt(v[22]),0,3);surfaceStrength194=clampInt(Integer.parseInt(v[23]),0,200);surfaceSoftness194=clampInt(Integer.parseInt(v[24]),50,200);}
            if(v.length>=27){iconOptics204="1".equals(v[25]);dockGlass204="1".equals(v[26]);}
            configEverLoaded=true;
            CONFIG_READY_AT_146=android.os.SystemClock.uptimeMillis();
            if(previousRefraction!=REFRACTION_ENABLED){
                REFRACTION_CONFIG_EPOCH++;
                if(REFRACTION_SWITCH_LOGS++<40)log("REFRACTION_SWITCH_138 enabled="+REFRACTION_ENABLED+
                    " epoch="+REFRACTION_CONFIG_EPOCH+" blurMode="+customBlurMode+
                    " customBlurDp="+customBlurDp+" clear="+clearRefraction);
            }
            if(!raw.equals(lastBootSnapshot)){
                lastBootSnapshot=raw;
                log("BOOT_SNAPSHOT_APPLIED enabled="+glassEnabled+" highlight="+highlightEnabled+" refraction="+REFRACTION_ENABLED+" fields="+v.length+" surface="+surfaceMode194+" strength="+surfaceStrength194+" softness="+surfaceSoftness194);
                if(CONFIG_LOGS_146++<32)log("CONFIG_FIRST_FRAME_146 snapshotReady="+CONFIG_READY_AT_146+" unlockAt="+CONFIG_UNLOCK_AT_146+" blur="+customBlurDp+" alpha="+glassAlpha+" refract="+refractionPercent);
                return true;
            }
            return false;
        }catch(Throwable t){log("BOOT_SNAPSHOT_FAILED reason="+t.getClass().getSimpleName());return false;}
    }
    private static void refresh(View v) { refreshFromContext(v.getContext()); }
    private static void refreshFromContext(android.content.Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (configEverLoaded && now-lastRead < 1500) return;
        lastRead=now;
        // V1.9.97: Launcher never cold-starts the module ContentProvider.
        // ColorOS blocks that startup during boot; use the root-published snapshot.
        if ("com.android.launcher".equals(context.getPackageName())) {
            restoreBootSnapshot(context);
            return;
        }
        try (Cursor c = context.getContentResolver().query(CONFIG,null,null,null,null)) {
            if(c!=null && c.moveToFirst()) {
                lastRead=now;
                configEverLoaded=true;
                glassEnabled=c.getInt(0)==1;
                glassAlpha=Math.max(40,Math.min(255,c.getInt(1)));
                tintMode=Math.max(0,Math.min(3,c.getInt(2)));
                tintStrength=Math.max(0,Math.min(90,c.getInt(3)));
                highlightEnabled=c.getInt(4)==1;
                diagnostics=c.getInt(5)==1;
                int visualIndex=c.getColumnIndex("wallpaper_compare");
                wallpaperCompare=visualIndex>=0 && c.getInt(visualIndex)==1;
                int clearIndex=c.getColumnIndex("clear_refraction");
                clearRefraction=clearIndex>=0 && c.getInt(clearIndex)==1;
                int refractIndex=c.getColumnIndex("real_refraction");
                REFRACTION_ENABLED=refractIndex>=0 && c.getInt(refractIndex)==1;
                highlightStrength=Math.max(0,Math.min(100,c.getInt(6)));
                highlightWidth=Math.max(1,Math.min(5,c.getInt(8)));
                directionalLight=c.getInt(9)==1;
                int radiusIndex=c.getColumnIndex("blur_radius_percent");
                blurRadiusPercent=radiusIndex>=0 ? Math.max(10,Math.min(100,c.getInt(radiusIndex))) : 100;
                probeMode=Math.max(0,Math.min(3,c.getInt(7)));
                int expIndex=c.getColumnIndex("native_blur_experiment");
                nativeBlurExperiment=0; // OEM material switch is no longer forced
                int alphaProbeIndex=c.getColumnIndex("native_alpha_probe");
                nativeAlphaProbe=alphaProbeIndex>=0 && c.getInt(alphaProbeIndex)==1;
                int ci=c.getColumnIndex("custom_blur_dp");if(ci>=0)customBlurDp=clampInt(c.getInt(ci),0,40);
                ci=c.getColumnIndex("refraction_percent");if(ci>=0)refractionPercent=clampInt(c.getInt(ci),0,100);
                ci=c.getColumnIndex("glass_tint_opacity");if(ci>=0)glassTintOpacity=clampInt(c.getInt(ci),0,100);
                ci=c.getColumnIndex("custom_blur_mode");if(ci>=0)customBlurMode=clampInt(c.getInt(ci),0,2);
                ci=c.getColumnIndex("icon_optics_204");iconOptics204=ci>=0&&c.getInt(ci)==1;
                ci=c.getColumnIndex("dock_glass_204");dockGlass204=ci>=0&&c.getInt(ci)==1;
                ci=c.getColumnIndex("surface_mode_195");if(ci>=0)surfaceMode194=clampInt(c.getInt(ci),0,3);
                ci=c.getColumnIndex("surface_strength_195");if(ci>=0)surfaceStrength194=clampInt(c.getInt(ci),0,200);
                ci=c.getColumnIndex("surface_softness_195");if(ci>=0)surfaceSoftness194=clampInt(c.getInt(ci),50,200);
                String summary="enabled="+glassEnabled+" alpha="+glassAlpha+" tint="+tintMode+" tintStrength="+tintStrength+" highlight="+highlightEnabled+" highlightStrength="+highlightStrength+" highlightWidth="+highlightWidth+" directional="+directionalLight+" probeMode="+probeMode+" nativeBlurExperiment="+nativeBlurExperiment+" blurRadiusPercent="+blurRadiusPercent+" nativeAlphaProbe="+nativeAlphaProbe;
                summary += " surfaceMode="+surfaceMode194+" surfaceStrength="+surfaceStrength194+" surfaceSoftness="+surfaceSoftness194;
                summary += " realRefraction="+REFRACTION_ENABLED+" wallpaperCompare="+wallpaperCompare+" customBlurDp="+customBlurDp+" refractionPercent="+refractionPercent+" glassTintOpacity="+glassTintOpacity+" customBlurMode="+customBlurMode+" clearRefraction="+clearRefraction;
                if(!summary.equals(lastConfigSummary)){lastConfigSummary=summary;log("CONFIG_SYNC "+summary);}
            }
        } catch(Throwable t) { if(alphaLogs++<8) log("CONFIG_READ_FAILED reason="+t.getClass().getSimpleName()); }
        if(!configEverLoaded)restoreBootSnapshot(context);
    }
    private static void refreshConfigFromContext(android.content.Context ctx) {
        long now=android.os.SystemClock.uptimeMillis();
        if(now-lastExperimentRead<1500)return;
        nativeBlurExperiment=0;
        lastExperimentRead=now;
    }
    private static int tintColor() {
        if(tintMode==1)return Color.rgb(225,240,255);
        if(tintMode==2)return Color.rgb(185,192,202);
        return Color.rgb(245,248,252);
    }
    // V1.9.35: read-only, bounded native blur parameter probe. Never mutate OEM blur state.
    private static void inspectBlurParameters(Object child) {
        if (!diagnostics) return;
        if (child == null) { log("BLUR_PARAMS_PROBE_SKIP reason=child_null"); return; }
        log("BLUR_PARAMS_PROBE_ENTER child=" + child.getClass().getName());
        try {
            Method getter;
            try { getter = child.getClass().getMethod("getBlurParams"); }
            catch (NoSuchMethodException missingPublic) {
                getter = child.getClass().getDeclaredMethod("getBlurParams");
                getter.setAccessible(true);
            }
            if (getter.getParameterTypes().length != 0) { log("BLUR_PARAMS_PROBE_SKIP reason=requires_arguments"); return; }
            Object params = getter.invoke(child);
            if (params == null) { log("BLUR_PARAMS null"); return; }
            log("BLUR_PARAMS_CLASS " + params.getClass().getName());
            // V1.9.38: inspect construction/copy options only. No mutation or setter calls.
            try {
                java.lang.reflect.Constructor<?>[] constructors = params.getClass().getDeclaredConstructors();
                log("BLUR_COPY_SAFETY constructors=" + constructors.length + " class=" + params.getClass().getName());
                int shown = 0;
                for (java.lang.reflect.Constructor<?> constructor : constructors) {
                    if (shown++ >= 8) break;
                    log("BLUR_COPY_CONSTRUCTOR " + constructor.toGenericString());
                }
                int finals = 0, mutable = 0;
                for (Field f : params.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (Modifier.isFinal(f.getModifiers())) finals++; else mutable++;
                }
                log("BLUR_COPY_SAFETY mutableFields=" + mutable + " finalFields=" + finals
                    + " action=READ_ONLY no_setBlurParams");
            } catch (Throwable t) { log("BLUR_COPY_SAFETY_ERROR " + t.getClass().getSimpleName()); }
            // V1.9.37: log geometry as coordinates, not merely a Rect class name.
            // Read-only: no setters, no field mutation, no draw calls.
            for (Method candidate : child.getClass().getMethods()) {
                String n = candidate.getName().toLowerCase(java.util.Locale.ROOT);
                if ((n.contains("blur") || n.contains("invalidate") || n.contains("update")) &&
                        candidate.getParameterTypes().length <= 3) {
                    log("BLUR_CONTROL_CANDIDATE " + candidate.toGenericString());
                }
            }
            int count = 0;
            for (Class<?> c = params.getClass(); c != null && count < 20; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (count >= 20) break;
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!(name.contains("blur") || name.contains("radius") || name.contains("level") || name.contains("sigma") || name.contains("alpha") || name.contains("strength"))) continue;
                    try {
                        f.setAccessible(true);
                        Object value = f.get(params);
                        if (value instanceof android.graphics.Rect) {
                            android.graphics.Rect r = (android.graphics.Rect) value;
                            log("BLUR_PARAMS_RECT " + f.getName() + "=[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]");
                        } else if (value instanceof android.graphics.RectF) {
                            android.graphics.RectF r = (android.graphics.RectF) value;
                            log("BLUR_PARAMS_RECT_F " + f.getName() + "=[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]");
                        } else if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
                            log("BLUR_PARAMS_FIELD " + f.getName() + "=" + String.valueOf(value));
                        } else {
                            log("BLUR_PARAMS_FIELD " + f.getName() + " type=" + value.getClass().getName());
                        }
                    } catch (Throwable t) { log("BLUR_PARAMS_FIELD_ERROR " + f.getName() + "=" + t.getClass().getSimpleName()); }
                    count++;
                }
            }
            if (count == 0) log("BLUR_PARAMS_NO_MATCHING_FIELDS");
        } catch (NoSuchMethodException t) { log("BLUR_PARAMS_GETTER_UNAVAILABLE child=" + child.getClass().getName()); }
        catch (Throwable t) { log("BLUR_PARAMS_ERROR " + t.getClass().getSimpleName() + " cause=" + (t.getCause() == null ? "none" : t.getCause().getClass().getSimpleName())); }
    }
    private static final java.util.concurrent.atomic.AtomicBoolean SAMPLE_FEASIBILITY_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // V1.9.38: inspect backdrop acquisition conditions, without screenshots or pixel reads.
    // Launcher root captures include the folder itself and are NOT valid backdrop sources.
    private static void inspectSamplingFeasibility(View folder) {
        if (!diagnostics || !SAMPLE_FEASIBILITY_LOGGED.compareAndSet(false, true)) return;
        try {
            View root = folder.getRootView();
            int[] folderLocation = new int[2];
            int[] rootLocation = new int[2];
            folder.getLocationOnScreen(folderLocation);
            if (root != null) root.getLocationOnScreen(rootLocation);
            log("REFRACTION_FEASIBILITY root=" + (root == null ? "null" : root.getClass().getName())
                + " folder=" + folder.getWidth() + "x" + folder.getHeight()
                + " attached=" + folder.isAttachedToWindow());
            log("REFRACTION_COORDS folderScreen=[" + folderLocation[0] + "," + folderLocation[1] + "]"
                + " rootScreen=[" + rootLocation[0] + "," + rootLocation[1] + "]"
                + " rootSize=" + (root == null ? "null" : root.getWidth() + "x" + root.getHeight()));
            if (root != null) {
                Drawable bg = root.getBackground();
                log("REFRACTION_ROOT_BACKGROUND " + (bg == null ? "null" : bg.getClass().getName()));
                android.view.ViewGroup.LayoutParams lp = root.getLayoutParams();
                if (lp instanceof android.view.WindowManager.LayoutParams) {
                    android.view.WindowManager.LayoutParams wlp = (android.view.WindowManager.LayoutParams) lp;
                    boolean showsWallpaper = (wlp.flags & android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0;
                    log("REFRACTION_WINDOW showWallpaperFlag=" + showsWallpaper + " type=" + wlp.type);
                } else {
                    log("REFRACTION_WINDOW layoutParams=" + (lp == null ? "null" : lp.getClass().getName()));
                }
            }
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(folder.getContext());
                log("REFRACTION_WALLPAPER_SERVICE available=" + (wm != null)
                    + " supported=" + (wm != null && wm.isWallpaperSupported()));
                // Do not call getDrawable/getWallpaperFile: wallpaper alone does not include icons,
                // widgets or live scene layers, and accessing wallpaper pixels may be restricted.
            } catch (Throwable t) { log("REFRACTION_WALLPAPER_SERVICE_ERROR " + t.getClass().getSimpleName()); }
            // V1.9.42: classify backdrop candidates without reading any pixels.
            // Wallpaper-only images cannot reproduce launcher icons/widgets; do not mistake
            // a wallpaper drawable for a complete pre-folder composited backdrop.
            try {
                android.app.WallpaperManager wallpaper = android.app.WallpaperManager.getInstance(folder.getContext());
                android.app.WallpaperInfo info = wallpaper == null ? null : wallpaper.getWallpaperInfo();
                log("REFRACTION_WALLPAPER_KIND " + (info == null ? "STATIC_OR_UNREPORTED" : "LIVE")
                    + " fullScene=false pixelsRead=false");
            } catch (Throwable t) {
                log("REFRACTION_WALLPAPER_KIND_ERROR " + t.getClass().getSimpleName());
            }
            try {
                View parent = folder.getParent() instanceof View ? (View) folder.getParent() : null;
                int siblings = parent instanceof ViewGroup ? ((ViewGroup) parent).getChildCount() : -1;
                int index = parent instanceof ViewGroup ? ((ViewGroup) parent).indexOfChild(folder) : -1;
                log("REFRACTION_LAYER_CONTEXT parent=" + (parent == null ? "null" : parent.getClass().getName())
                    + " siblingCount=" + siblings + " folderIndex=" + index
                    + " preFolderFrameAvailable=false");
            } catch (Throwable t) {
                log("REFRACTION_LAYER_CONTEXT_ERROR " + t.getClass().getSimpleName());
            }
            log("REFRACTION_SAMPLING_DECISION source=NONE captureAttempted=false"
                + " reason=requires_verified_pre_folder_backdrop");
            log("REFRACTION_FEASIBILITY status=BACKDROP_UNAVAILABLE"
                + " reason=need_pre_folder_composited_frame_not_root_screenshot");
        } catch (Throwable t) { log("REFRACTION_FEASIBILITY_ERROR " + t.getClass().getSimpleName()); }
    }
    // V1.9.44: observe offsets submitted by Launcher to WallpaperManager.
    // These are normalized requests, NOT proof of final compositor crop or pixels.
    private static volatile float wallpaperRequestedX = Float.NaN;
    private static volatile float wallpaperRequestedY = Float.NaN;
    private static volatile long wallpaperOffsetTime = 0L;
    // A stationary wallpaper request remains the last known state; age alone does not invalidate it.
    // It remains a REQUEST, never a verified compositor transform.
    private static volatile long wallpaperOffsetRevision = 0L;
    private static volatile long wallpaperLastMotionTime = 0L;
    private static volatile boolean wallpaperOffsetMoving = false;
    private static volatile int wallpaperOffsetViewportW = -1, wallpaperOffsetViewportH = -1;
    private static volatile int wallpaperOffsetWallpaperId = -1;
    private static int wallpaperStateLogs = 0;
    private static final long WALLPAPER_SETTLE_MS = 650L;
    private static String wallpaperRequestState(long now) {
        if (wallpaperOffsetTime == 0L || !Float.isFinite(wallpaperRequestedX)
            || !Float.isFinite(wallpaperRequestedY)
            || wallpaperRequestedX < 0f || wallpaperRequestedX > 1f
            || wallpaperRequestedY < 0f || wallpaperRequestedY > 1f) return "UNKNOWN";
        return now - wallpaperLastMotionTime < WALLPAPER_SETTLE_MS ? "MOVING" : "SETTLED";
    }
    private static void invalidateWallpaperRequest(String reason) {
        wallpaperOffsetTime = 0L;
        wallpaperRequestedX = Float.NaN;
        wallpaperRequestedY = Float.NaN;
        wallpaperOffsetRevision++;
        if (diagnostics && wallpaperStateLogs++ < 28)
            log("REFRACTION_OFFSET_STATE invalidated="+reason+" state=UNKNOWN revision="+wallpaperOffsetRevision);
    }
    private static void validateWallpaperRequestContext(View folder, int sw, int sh) {
        if (wallpaperOffsetViewportW != -1 && (wallpaperOffsetViewportW != sw || wallpaperOffsetViewportH != sh))
            invalidateWallpaperRequest("display_size_changed");
        wallpaperOffsetViewportW = sw;
        wallpaperOffsetViewportH = sh;
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(folder.getContext());
            int id = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
            if (wallpaperOffsetWallpaperId != -1 && id != wallpaperOffsetWallpaperId)
                invalidateWallpaperRequest("wallpaper_id_changed");
            wallpaperOffsetWallpaperId = id;
        } catch (Throwable ignored) { /* not all launchers can read wallpaper ID */ }
    }
    // V1.9.60: diagnostic-only state transitions; does not change wallpaper requests.
    private static final java.util.concurrent.atomic.AtomicInteger OFFSET_TRACK_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger FOLDER_TRACK_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile String lastFolderPhase = "UNKNOWN";
    private static void trackFolderPhase(View folder, String phase) {
        if (!diagnostics || folder == null || FOLDER_TRACK_LOGS.get() >= 72) return;
        try {
            int[] xy = new int[2]; folder.getLocationOnScreen(xy);
            long now = android.os.SystemClock.uptimeMillis();
            String state = wallpaperRequestState(now);
            String key = phase + ":" + Integer.toHexString(System.identityHashCode(folder))
                + ":" + wallpaperOffsetRevision + ":" + xy[0] + ":" + xy[1];
            if (key.equals(lastFolderPhase)) return;
            lastFolderPhase = key;
            if (FOLDER_TRACK_LOGS.incrementAndGet() > 72) return;
            log("REFRACTION_DYNAMIC_FOLDER phase=" + phase
                + " id=" + Integer.toHexString(System.identityHashCode(folder))
                + " screen=[" + xy[0] + "," + xy[1] + "]"
                + " size=" + folder.getWidth() + "x" + folder.getHeight()
                + " request=[" + wallpaperRequestedX + "," + wallpaperRequestedY + "]"
                + " offsetState=" + state + " revision=" + wallpaperOffsetRevision
                + " requestAgeMs=" + (wallpaperOffsetTime == 0 ? -1 : now - wallpaperOffsetTime)
                + " mappingVerified=false pixelsAvailable=false");
        } catch (Throwable t) {
            if (FOLDER_TRACK_LOGS.incrementAndGet() <= 72)
                log("REFRACTION_DYNAMIC_FOLDER_ERROR " + t.getClass().getSimpleName());
        }
    }
    // V1.9.62: read-only settled geometry snapshots. No framebuffer capture.
    private static final java.util.Map<View, String> SNAPSHOT_LAST =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());
    private static final java.util.concurrent.atomic.AtomicInteger SNAPSHOT_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    // V1.9.62: diagnostic-only gate. Never enables real refraction or reads framebuffer pixels.
    private static final java.util.Map<View, String> GATE_LAST =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());
    private static final java.util.concurrent.atomic.AtomicInteger GATE_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static void inspectSamplingGate(View folder, String phase) {
        if (!diagnostics || folder == null || GATE_LOGS.get() >= 120) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            int[] loc = new int[2]; folder.getLocationOnScreen(loc);
            int w = folder.getWidth(), h = folder.getHeight();
            android.util.DisplayMetrics dm = folder.getResources().getDisplayMetrics();
            int sw = dm.widthPixels, sh = dm.heightPixels;
            if (sw <= 0 || sh <= 0) return;
            validateWallpaperRequestContext(folder, sw, sh);
            long left = loc[0], top = loc[1], right = left + w, bottom = top + h;
            long clipL = Math.max(0L, left), clipT = Math.max(0L, top);
            long clipR = Math.min((long)sw, right), clipB = Math.min((long)sh, bottom);
            // Normalize empty intersections: never publish inverted clip coordinates.
            if (clipR <= clipL || clipB <= clipT) {
                clipL = 0L; clipT = 0L; clipR = 0L; clipB = 0L;
            }
            boolean attached = folder.isAttachedToWindow();
            boolean nonEmpty = w > 0 && h > 0 && clipR > clipL && clipB > clipT;
            boolean visible = attached && folder.isShown() && folder.getAlpha() > 0f && nonEmpty;
            boolean fullyInside = nonEmpty && left >= 0 && top >= 0 && right <= sw && bottom <= sh;
            String offset = wallpaperRequestState(now);
            boolean offsetStable = "SETTLED".equals(offset);
            boolean requestValid = Float.isFinite(wallpaperRequestedX) && Float.isFinite(wallpaperRequestedY)
                && wallpaperRequestedX >= 0f && wallpaperRequestedX <= 1f
                && wallpaperRequestedY >= 0f && wallpaperRequestedY <= 1f;
            // This is a metadata readiness gate, NOT permission to sample wallpaper pixels.
            boolean metadataReady = visible && fullyInside && offsetStable && requestValid;
            String reason = !attached ? "DETACHED" : !visible ? "NOT_VISIBLE_OR_OUTSIDE"
                : !fullyInside ? "PARTIALLY_CLIPPED" : !requestValid ? "OFFSET_UNKNOWN"
                : !offsetStable ? "OFFSET_MOVING" : "METADATA_ONLY_CROP_UNVERIFIED";
            String key = phase + ":" + reason + ":" + wallpaperOffsetRevision
                + ":" + left + ":" + top + ":" + w + ":" + h;
            synchronized (GATE_LAST) {
                if (key.equals(GATE_LAST.get(folder))) return;
                GATE_LAST.put(folder, key);
            }
            if (metadataReady && SNAPSHOT_LOGS.get() < 48) {
                String snapshotKey = left + ":" + top + ":" + w + ":" + h + ":"
                    + wallpaperOffsetRevision + ":" + wallpaperRequestedX + ":" + wallpaperRequestedY;
                boolean changed;
                synchronized (SNAPSHOT_LAST) {
                    changed = !snapshotKey.equals(SNAPSHOT_LAST.get(folder));
                    if (changed) SNAPSHOT_LAST.put(folder, snapshotKey);
                }
                if (changed && SNAPSHOT_LOGS.incrementAndGet() <= 48) {
                    log("REFRACTION_STABLE_SNAPSHOT id="
                        + Integer.toHexString(System.identityHashCode(folder))
                        + " phase=" + phase + " screenRect=[" + left + "," + top + "," + right + "," + bottom + "]"
                        + " viewport=" + sw + "x" + sh
                        + " wallpaperRequest=[" + wallpaperRequestedX + "," + wallpaperRequestedY + "]"
                        + " requestRevision=" + wallpaperOffsetRevision
                        + " requestAgeMs=" + (now - wallpaperOffsetTime)
                        + " cropTransform=UNKNOWN compositorFrame=UNAVAILABLE"
                        + " mappingVerified=false samplingAllowed=false");
                }
            }
            if (metadataReady) {
                inspectIndependentReferenceCandidates(folder, left, top, w, h, sw, sh, phase);
                inspectDisplayedWallpaperCoordinates(folder, left, top, w, h, sw, sh, phase);
            }
            if (GATE_LOGS.incrementAndGet() > 120) return;
            log("REFRACTION_SAMPLING_GATE phase=" + phase
                + " id=" + Integer.toHexString(System.identityHashCode(folder))
                + " screenRect=[" + left + "," + top + "," + right + "," + bottom + "]"
                + " clipRect=[" + clipL + "," + clipT + "," + clipR + "," + clipB + "]"
                + " viewport=" + sw + "x" + sh + " attached=" + attached
                + " viewShown=" + folder.isShown() + " visible=" + visible
                + " fullyInside=" + fullyInside + " offsetState=" + offset
                + " request=[" + wallpaperRequestedX + "," + wallpaperRequestedY + "]"
                + " revision=" + wallpaperOffsetRevision + " metadataReady=" + metadataReady
                + " samplingAllowed=false mappingVerified=false reason=" + reason);
        } catch (Throwable t) {
            if (GATE_LOGS.incrementAndGet() <= 120)
                log("REFRACTION_SAMPLING_GATE_ERROR " + t.getClass().getSimpleName());
        }
    }
    // V1.9.65: screen-space correspondence is independent of WallpaperManager request offsets.
    // These points identify WHERE pixels are displayed, not WHICH source image texels produced them.
    // Do not read the launcher framebuffer, perform screenshot capture, or turn on refraction here.
    private static final java.util.Map<View, String> DISPLAY_COORD_LAST =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());
    private static final java.util.concurrent.atomic.AtomicInteger DISPLAY_COORD_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static void inspectDisplayedWallpaperCoordinates(View folder, long left, long top,
            int w, int h, int sw, int sh, String phase) {
        if (!diagnostics || DISPLAY_COORD_LOGS.get() >= 48 || sw <= 0 || sh <= 0) return;
        // Wallpaper is user-observed stationary. This is a test hypothesis, not an API guarantee.
        String key = left + ":" + top + ":" + w + ":" + h;
        synchronized (DISPLAY_COORD_LAST) {
            if (key.equals(DISPLAY_COORD_LAST.get(folder))) return;
            DISPLAY_COORD_LAST.put(folder, key);
        }
        if (DISPLAY_COORD_LOGS.incrementAndGet() > 48) return;
        final float[][] pts = {{0f,0f},{.5f,.5f},{1f,1f}};
        for (int i = 0; i < pts.length; i++) {
            float sx = left + w * pts[i][0];
            float sy = top + h * pts[i][1];
            log("REFRACTION_DISPLAY_COORD phase=" + phase
                + " folder=" + Integer.toHexString(System.identityHashCode(folder))
                + " point=" + (i+1) + " screenPx=[" + fmt(sx) + "," + fmt(sy) + "]"
                + " screenUV=[" + fmt(sx/sw) + "," + fmt(sy/sh) + "]"
                + " viewport=" + sw + "x" + sh
                + " wallpaperMotion=USER_REPORTED_STATIONARY"
                + " offsetRequestIgnoredForDisplayCoordinates=true"
                + " independentRenderedWallpaperPixels=UNAVAILABLE"
                + " compositorTextureTransform=UNKNOWN mappingVerified=false samplingAllowed=false");
        }
        log("REFRACTION_DISPLAY_REFERENCE status=EXTERNAL_REFERENCE_REQUIRED"
            + " coordinateSpace=DISPLAY_PIXELS"
            + " referenceKind=UNOBSTRUCTED_WALLPAPER_SCREENSHOT"
            + " captureAttempted=false screenshotAttempted=false"
            + " systemUiWallpaperSurfaceRead=false sourceImageCropAssumed=false"
            + " fixedWallpaper=USER_OBSERVATION_ONLY mappingVerified=false samplingAllowed=false");
    }
    // V1.9.64: audit source provenance; candidate geometry is not a measured compositor transform.
    private static final java.util.Map<View, String> REFERENCE_CANDIDATE_LAST =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());
    private static final java.util.concurrent.atomic.AtomicInteger REFERENCE_CANDIDATE_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger DIMENSION_AUDIT_LOGS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static int lastAuditedDesiredW = Integer.MIN_VALUE;
    private static int lastAuditedDesiredH = Integer.MIN_VALUE;
    private static void auditWallpaperDimensions(View folder, String phase) {
        if (!diagnostics || DIMENSION_AUDIT_LOGS.get() >= 20) return;
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(folder.getContext());
            int dw = wm.getDesiredMinimumWidth(), dh = wm.getDesiredMinimumHeight();
            boolean changed;
            synchronized (REFERENCE_CANDIDATE_LAST) {
                changed = dw != lastAuditedDesiredW || dh != lastAuditedDesiredH;
                if (changed) { lastAuditedDesiredW = dw; lastAuditedDesiredH = dh; }
            }
            if (changed && DIMENSION_AUDIT_LOGS.incrementAndGet() <= 20) {
                log("REFRACTION_DIMENSION_AUDIT phase=" + phase
                    + " desiredMinimum=" + dw + "x" + dh
                    + " initialDesiredMinimum=" + wallpaperDesiredW + "x" + wallpaperDesiredH
                    + " source=WallpaperManager.getDesiredMinimumWidth/Height"
                    + " originalImage=" + wallpaperImageW + "x" + wallpaperImageH
                    + " viewport=" + wallpaperDisplayW + "x" + wallpaperDisplayH
                    + " desiredIsRenderedSize=false compositorCrop=UNKNOWN mappingVerified=false");
            }
        } catch (Throwable t) {
            if (DIMENSION_AUDIT_LOGS.incrementAndGet() <= 20)
                log("REFRACTION_DIMENSION_AUDIT status=UNAVAILABLE reason=" + t.getClass().getSimpleName());
        }
    }
    private static void inspectIndependentReferenceCandidates(View folder, long left, long top,
            int w, int h, int sw, int sh, String phase) {
        if (!diagnostics || REFERENCE_CANDIDATE_LOGS.get() >= 36 ||
                wallpaperImageW <= 0 || wallpaperImageH <= 0 ||
                !Float.isFinite(wallpaperRequestedX) || !Float.isFinite(wallpaperRequestedY)) return;
        auditWallpaperDimensions(folder, phase);
        String key = left + ":" + top + ":" + w + ":" + h + ":" + wallpaperOffsetRevision;
        synchronized (REFERENCE_CANDIDATE_LAST) {
            if (key.equals(REFERENCE_CANDIDATE_LAST.get(folder))) return;
            REFERENCE_CANDIDATE_LAST.put(folder, key);
        }
        if (REFERENCE_CANDIDATE_LOGS.incrementAndGet() > 36) return;
        float cover = Math.max((float)sw / wallpaperImageW, (float)sh / wallpaperImageH);
        // Desired minimum is a launcher/system hint, NOT the image's measured render width.
        // Keep it as an explicitly untrusted diagnostic candidate; reject excessive scaling.
        int currentDesired = lastAuditedDesiredW;
        float rawDesired = currentDesired > 0 ? Math.max(cover, (float)currentDesired / wallpaperImageW) : Float.NaN;
        boolean desiredUsable = Float.isFinite(rawDesired) && rawDesired > 0f
            && rawDesired <= cover * 1.5f;
        float desired = desiredUsable ? rawDesired : Float.NaN;
        float[][] pts = {{.25f,.25f},{.5f,.5f},{.75f,.75f}};
        for (int i=0; i<pts.length; i++) {
            float px = left + w*pts[i][0], py = top + h*pts[i][1];
            log("REFRACTION_REFERENCE_CANDIDATE phase=" + phase
                + " folder=" + Integer.toHexString(System.identityHashCode(folder))
                + " point=" + (i+1) + " screen=[" + fmt(px) + "," + fmt(py) + "]"
                + " request=[" + fmt(wallpaperRequestedX) + "," + fmt(wallpaperRequestedY) + "]"
                + " source=" + wallpaperImageW + "x" + wallpaperImageH
                + " viewport=" + sw + "x" + sh + " revision=" + wallpaperOffsetRevision
                + " coverSource=IMAGE_BOUNDS_AND_DISPLAY"
                + " desiredSource=WallpaperManager.getDesiredMinimumWidth"
                + " desiredWidth=" + currentDesired
                + " desiredScaleRaw=" + fmt(rawDesired)
                + " desiredStatus=" + (desiredUsable ? "HYPOTHESIS_ONLY" : "REJECTED_UNTRUSTED_DIMENSIONS")
                + " " + mappingHypothesis("COVER",cover,wallpaperRequestedX,px,py,
                    wallpaperImageW,wallpaperImageH,sw,sh)
                + " " + mappingHypothesis("DESIRED",desired,wallpaperRequestedX,px,py,
                    wallpaperImageW,wallpaperImageH,sw,sh)
                + " independentReference=UNAVAILABLE modelVerified=false samplingAllowed=false");
        }
    }
    private static int wallpaperOffsetLogs = 0;
    private static final java.util.Map<View, long[]> REFRACTION_MOTION =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, long[]>());
    private static int refractionMotionLogs = 0;
    private static void hookDynamicFolderLifecycle(Class<?> folderClass) {
        if (folderClass == null) return;
        for (String name : new String[]{"onAttachedToWindow", "onDetachedFromWindow"}) {
            try {
                Method m = folderClass.getDeclaredMethod(name);
                m.setAccessible(true);
                final String phase = name.equals("onAttachedToWindow") ? "ATTACHED" : "DETACHING";
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.thisObject instanceof View) {
                            trackFolderPhase((View)p.thisObject, phase);
                            inspectSamplingGate((View)p.thisObject, phase);
                        }
                    }
                });
                installed++;
                log("REFRACTION_DYNAMIC_LIFECYCLE_HOOK method=" + name + " installed=true");
            } catch (Throwable t) {
                log("REFRACTION_DYNAMIC_LIFECYCLE_HOOK method=" + name
                    + " installed=false reason=" + t.getClass().getSimpleName());
            }
        }
    }
    private static void hookWallpaperOffsetRequests() {
        try {
            for (Method m : android.app.WallpaperManager.class.getDeclaredMethods()) {
                if (!"setWallpaperOffsets".equals(m.getName())) continue;
                Class<?>[] args = m.getParameterTypes();
                if (args.length != 3 || args[1] != float.class || args[2] != float.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!(p.args[1] instanceof Float) || !(p.args[2] instanceof Float)) return;
                        float x=(Float)p.args[1], y=(Float)p.args[2];
                        long now=android.os.SystemClock.uptimeMillis();
                        boolean changed=Float.isNaN(wallpaperRequestedX) || Float.isNaN(wallpaperRequestedY)
                            || Math.abs(x-wallpaperRequestedX)>0.0015f || Math.abs(y-wallpaperRequestedY)>0.0015f;
                        wallpaperRequestedX=x; wallpaperRequestedY=y;
                        wallpaperOffsetTime=now;
                        if (changed) { wallpaperLastMotionTime=now; wallpaperOffsetRevision++; }
                        wallpaperOffsetMoving=changed;
                        if (diagnostics && changed && OFFSET_TRACK_LOGS.incrementAndGet() <= 96)
                            log("REFRACTION_DYNAMIC_OFFSET event=REQUEST_CHANGED x=" + x + " y=" + y
                                + " revision=" + wallpaperOffsetRevision + " uptimeMs=" + now
                                + " source=Launcher.WallpaperManager.setWallpaperOffsets"
                                + " verifiedCompositorCrop=false");
                        if (diagnostics && wallpaperOffsetLogs++ < 40)
                            log("REFRACTION_WALLPAPER_OFFSET_REQUEST x="+x+" y="+y
                                +" timestamp="+wallpaperOffsetTime+" source=WallpaperManager.setWallpaperOffsets");
                    }
                });
                installed++;
                log("REFRACTION_WALLPAPER_OFFSET_HOOK installed=true");
                return;
            }
            log("REFRACTION_WALLPAPER_OFFSET_HOOK installed=false reason=method_not_found");
        } catch (Throwable t) {
            failed++;
            log("REFRACTION_WALLPAPER_OFFSET_HOOK installed=false reason="+t.getClass().getSimpleName());
        }
    }
    // V1.9.47: one-shot metadata and optional tiny region pixel diagnostic; no rendering.
    private static final java.util.concurrent.atomic.AtomicBoolean WALLPAPER_GEOMETRY_ONCE =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int wallpaperImageW = -1, wallpaperImageH = -1;
    private static volatile int wallpaperDisplayW = -1, wallpaperDisplayH = -1;
    private static volatile int wallpaperDesiredW = -1, wallpaperDesiredH = -1;
    private static int mappingModelLogs = 0;
    // V1.9.47: diagnostic-only, one-shot, tiny source-image region decode.
    // The sampled points are in SOURCE IMAGE coordinates, not verified screen coordinates.
    // This never draws sampled pixels and never retains the wallpaper bitmap.
    private static void inspectWallpaperPixels(android.os.ParcelFileDescriptor fd, int iw, int ih) {
        if (fd == null || iw <= 0 || ih <= 0) return;
        android.graphics.BitmapRegionDecoder decoder = null;
        try {
            decoder = android.graphics.BitmapRegionDecoder.newInstance(fd.getFileDescriptor(), false);
            if (decoder == null) {
                log("REFRACTION_PIXEL_PROBE status=DECODER_NULL pixelsRead=false");
                return;
            }
            int[][] points = {{iw / 4, ih / 4}, {iw / 2, ih / 2}, {iw * 3 / 4, ih * 3 / 4}};
            int count = 0;
            StringBuilder out = new StringBuilder();
            for (int[] point : points) {
                int x = Math.max(0, Math.min(iw - 1, point[0]));
                int y = Math.max(0, Math.min(ih - 1, point[1]));
                android.graphics.Bitmap sample = null;
                try {
                    android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
                    opt.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
                    sample = decoder.decodeRegion(new android.graphics.Rect(x, y, x + 1, y + 1), opt);
                    if (sample != null && sample.getWidth() > 0 && sample.getHeight() > 0) {
                        int c = sample.getPixel(0, 0);
                        if (out.length() > 0) out.append(';');
                        out.append('(').append(x).append(',').append(y).append(")=#")
                           .append(String.format(java.util.Locale.US, "%08X", c));
                        count++;
                    }
                } catch (Throwable e) {
                    log("REFRACTION_PIXEL_POINT_ERROR x="+x+" y="+y+" reason="+e.getClass().getSimpleName());
                } finally { if (sample != null) sample.recycle(); }
            }
            log("REFRACTION_PIXEL_PROBE status="+(count==3?"SUCCESS":"PARTIAL")
                +" sourcePixelsRead="+count+"/3 sample="+out
                +" sourceCoordinatesOnly=true screenMappingVerified=false rendering=false");
        } catch (Throwable e) {
            log("REFRACTION_PIXEL_PROBE status=UNAVAILABLE reason="+e.getClass().getSimpleName()
                +" rendering=false");
        } finally { if (decoder != null) try { decoder.recycle(); } catch (Throwable ignored) {} }
    }
    // V1.9.53: explicit diagnostic opt-in; publish a bounded JPEG preview to the
    // module's private provider, never the full-resolution wallpaper or screenshots.
    private static void publishCalibrationPreview(View folder, int sourceW, int sourceH) {
        android.os.ParcelFileDescriptor fd=null;
        android.graphics.Bitmap sample=null;
        try {
            android.app.WallpaperManager wm=android.app.WallpaperManager.getInstance(folder.getContext());
            fd=wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
            if(fd==null){log("REFRACTION_CALIBRATION_SOURCE status=NO_FILE");return;}
            android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();
            o.inSampleSize=Math.max(1,Math.max(sourceW/480,sourceH/900));
            sample=android.graphics.BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,o);
            if(sample==null){log("REFRACTION_CALIBRATION_SOURCE status=DECODE_NULL");return;}
            // Cap binder payload; downsample further when required.
            java.io.ByteArrayOutputStream stream=new java.io.ByteArrayOutputStream();
            sample.compress(android.graphics.Bitmap.CompressFormat.JPEG,68,stream);
            byte[] jpeg=stream.toByteArray();
            if(jpeg.length>320000){stream.reset();sample.compress(android.graphics.Bitmap.CompressFormat.JPEG,40,stream);jpeg=stream.toByteArray();}
            if(jpeg.length>320000){log("REFRACTION_CALIBRATION_SOURCE status=TOO_LARGE");return;}
            android.os.Bundle data=new android.os.Bundle();
            data.putByteArray("jpeg",jpeg);data.putInt("sourceW",sourceW);data.putInt("sourceH",sourceH);
            try {data.putInt("wallpaperId",wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM));}
            catch(Throwable ignored){data.putInt("wallpaperId",-1);}
            android.os.Bundle result=folder.getContext().getContentResolver().call(
                android.net.Uri.parse("content://top.liquidglass.vector.config/settings"),
                "publishCalibrationPreview",null,data);
            log("REFRACTION_CALIBRATION_SOURCE status="+(result==null?"NULL_REPLY":result.getString("status","UNKNOWN"))
                +" source="+sourceW+"x"+sourceH+" preview="+sample.getWidth()+"x"+sample.getHeight()
                +" bytes="+jpeg.length+" mappingVerified=false");
        }catch(Throwable t){log("REFRACTION_CALIBRATION_SOURCE status=ERROR reason="+t.getClass().getSimpleName());}
        finally {if(sample!=null)sample.recycle();if(fd!=null)try{fd.close();}catch(Throwable ignored){}}
    }
    private static void inspectWallpaperGeometry(View folder) {
        if (!diagnostics || !WALLPAPER_GEOMETRY_ONCE.compareAndSet(false, true)) return;
        try {
            android.app.WallpaperManager wm=android.app.WallpaperManager.getInstance(folder.getContext());
            android.graphics.Point real=new android.graphics.Point();
            android.view.Display display=folder.getDisplay();
            if(display!=null) display.getRealSize(real);
            wallpaperDisplayW=real.x; wallpaperDisplayH=real.y;
            int desiredW=wm.getDesiredMinimumWidth(), desiredH=wm.getDesiredMinimumHeight();
            wallpaperDesiredW=desiredW; wallpaperDesiredH=desiredH;
            log("REFRACTION_WALLPAPER_GEOMETRY displayReal="+real.x+"x"+real.y
                +" desiredMinimum="+desiredW+"x"+desiredH
                +" note=desired_minimum_not_rendered_size");
            android.os.ParcelFileDescriptor fd=null;
            try {
                fd=wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
                if(fd==null) {
                    log("REFRACTION_WALLPAPER_FILE status=NULL bitmapRead=false");
                } else {
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
                    options.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,options);
                    wallpaperImageW=options.outWidth;wallpaperImageH=options.outHeight;
                    log("REFRACTION_WALLPAPER_FILE status=BOUNDS_ONLY image="+wallpaperImageW+"x"+wallpaperImageH
                        +" bitmapRead=false pixelsRead=false");
                    // Explicit diagnostics opt-in only. Never use these pixels for the folder UI.
                    inspectWallpaperPixels(fd, wallpaperImageW, wallpaperImageH);
                    if(wallpaperImageW>0&&wallpaperImageH>0) publishCalibrationPreview(folder,wallpaperImageW,wallpaperImageH);
                }
            } catch (Throwable t) {
                log("REFRACTION_WALLPAPER_FILE status=UNAVAILABLE reason="+t.getClass().getSimpleName()
                    +" bitmapRead=false pixelsRead=false");
            } finally { if(fd!=null)try{fd.close();}catch(Throwable ignored){} }
            log("REFRACTION_MAPPING_STATUS mode=METADATA_ONLY actualCrop=UNKNOWN actualScale=UNKNOWN"
                +" compositorTransform=UNKNOWN wallpaperPixels=NOT_SAMPLED");
        } catch(Throwable t){log("REFRACTION_WALLPAPER_GEOMETRY_ERROR "+t.getClass().getSimpleName());}
    }
    // V1.9.58: read-only feasibility probe. Neither window contents nor pixels are captured.
    // Launcher window geometry and wallpaper metadata are NOT compositor transforms.
    private static final java.util.concurrent.atomic.AtomicBoolean WALLPAPER_REFERENCE_ONCE =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static void inspectWallpaperReferenceFeasibility(View folder) {
        if (!diagnostics || !WALLPAPER_REFERENCE_ONCE.compareAndSet(false, true)) return;
        log("REFRACTION_REFERENCE_PROBE version=1.9.65 mode=READ_ONLY captureAttempted=false");
        try {
            android.content.Context ctx=folder.getContext();
            android.app.WallpaperManager wm=android.app.WallpaperManager.getInstance(ctx);
            int id=wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
            int desiredW=wm.getDesiredMinimumWidth(), desiredH=wm.getDesiredMinimumHeight();
            log("REFRACTION_REFERENCE_WALLPAPER id="+id+" desiredMinimum="+desiredW+"x"+desiredH
                +" desiredIsCompositorSize=false");
            try {
                android.app.WallpaperInfo info=wm.getWallpaperInfo(android.app.WallpaperManager.FLAG_SYSTEM);
                log("REFRACTION_REFERENCE_ENGINE liveInfo="+(info==null?"NULL_OR_UNAVAILABLE":"AVAILABLE")
                    +" actualEngineOffset=UNAVAILABLE actualEngineZoom=UNAVAILABLE"
                    +" note=WallpaperInfo_does_not_expose_rendered_crop");
            } catch(Throwable t) {
                log("REFRACTION_REFERENCE_ENGINE status=UNAVAILABLE reason="+t.getClass().getSimpleName());
            }
            try {
                int[] location=new int[2]; folder.getLocationOnScreen(location);
                View root=folder.getRootView();
                android.view.WindowInsets insets=root.getRootWindowInsets();
                String insetStatus="UNAVAILABLE";
                if(insets!=null) {
                    android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    insetStatus="["+bars.left+","+bars.top+","+bars.right+","+bars.bottom+"]";
                }
                android.view.ViewGroup.LayoutParams lp=root.getLayoutParams();
                String flags="UNAVAILABLE";
                if(lp instanceof android.view.WindowManager.LayoutParams) {
                    android.view.WindowManager.LayoutParams wlp=(android.view.WindowManager.LayoutParams)lp;
                    flags="0x"+Integer.toHexString(wlp.flags)+" type="+wlp.type;
                }
                log("REFRACTION_REFERENCE_WINDOW folderScreen=["+location[0]+","+location[1]+"]"
                    +" folderSize="+folder.getWidth()+"x"+folder.getHeight()
                    +" rootSize="+root.getWidth()+"x"+root.getHeight()
                    +" systemBarsInsets="+insetStatus+" rootWindow="+flags
                    +" wallpaperLayerTransform=UNAVAILABLE");
            } catch(Throwable t) {
                log("REFRACTION_REFERENCE_WINDOW status=UNAVAILABLE reason="+t.getClass().getSimpleName());
            }
        } catch(Throwable t) {
            log("REFRACTION_REFERENCE_PROBE status=UNAVAILABLE reason="+t.getClass().getSimpleName());
        }
        inspectWallpaperLayerAccessibility(folder);
        log("REFRACTION_REFERENCE_FEASIBILITY status=LAUNCHER_REFERENCE_FILE_SEPARATELY_CHECKED"
            +" screenshotAttempted=false screenCaptureAttempted=false"
            +" wallpaperServiceHookAttempted=false compositorCrop=UNKNOWN compositorScale=UNKNOWN"
            +" modelVerified=false refractionEnabled=false");
    }
    // V1.9.59: bounded, read-only Launcher view-tree feasibility check.
    // A matching view name is not proof of a wallpaper Surface or rendered pixels.
    private static void inspectWallpaperLayerAccessibility(View folder) {
        try {
            View root=folder.getRootView();
            java.util.ArrayDeque<View> pending=new java.util.ArrayDeque<View>();
            pending.add(root);
            int inspected=0, wallpaperNames=0, surfaceViews=0, textureViews=0;
            StringBuilder matches=new StringBuilder();
            while(!pending.isEmpty() && inspected<300) {
                View v=pending.removeFirst();
                inspected++;
                String type=v.getClass().getName();
                String lower=type.toLowerCase(java.util.Locale.ROOT);
                boolean named=lower.contains("wallpaper") || lower.contains("backdrop");
                if(named) wallpaperNames++;
                if(v instanceof android.view.SurfaceView) surfaceViews++;
                if(v instanceof android.view.TextureView) textureViews++;
                if(named && matches.length()<480) {
                    if(matches.length()>0) matches.append(';');
                    matches.append(type.replace(' ', '_')).append('(')
                        .append(v.getWidth()).append('x').append(v.getHeight()).append(')');
                }
                if(v instanceof ViewGroup) {
                    ViewGroup group=(ViewGroup)v;
                    for(int i=0;i<group.getChildCount() && pending.size()<400;i++) {
                        View child=group.getChildAt(i);
                        if(child!=null) pending.addLast(child);
                    }
                }
            }
            log("REFRACTION_LAYER_VIEW_TREE mode=READ_ONLY inspected="+inspected
                +" truncated="+(!pending.isEmpty())+" wallpaperNamed="+wallpaperNames
                +" surfaceViews="+surfaceViews+" textureViews="+textureViews
                +" candidates="+(matches.length()==0?"NONE":matches.toString())
                +" independentWallpaperPixels=false");
            boolean token=root.getWindowToken()!=null;
            log("REFRACTION_LAYER_ACCESSIBILITY launcherWindowTokenPresent="+token
                +" viewTreeIsLauncherOnly=true wallpaperWindowEnumerated=false"
                +" surfaceControlCaptured=false compositorLayersRead=false"
                +" actualWallpaperCrop=UNKNOWN actualWallpaperScale=UNKNOWN");
        } catch(Throwable t) {
            log("REFRACTION_LAYER_ACCESSIBILITY status=UNAVAILABLE reason="+t.getClass().getSimpleName());
        }
        log("REFRACTION_LAYER_NEXT_STEP independentWallpaperFrame=UNAVAILABLE"
            +" adbDumpsysMayProvideMetadata=true requiresExternalUserAction=true"
            +" screenshotAttempted=false screenCaptureAttempted=false verified=false");
    }
    // V1.9.48: hypothetical screen->source mappings. These are NOT compositor measurements.
    // Center-crop assumes a static source image scaled uniformly to fill the display.
    // A second model assumes the source is uniformly scaled to at least desiredMinimumWidth.
    private static String mappingHypothesis(String name, float scale, float offset,
                                            float sx, float sy, int iw, int ih, int dw, int dh) {
        if (!(scale > 0f) || Float.isNaN(scale) || Float.isInfinite(scale)) return name + "=INVALID_SCALE";
        float renderedW = iw * scale, renderedH = ih * scale;
        float extraX = Math.max(0f, renderedW - dw);
        float extraY = Math.max(0f, renderedH - dh);
        // Android wallpaper offset request is treated as a hypothesis for excess-image panning.
        float sourceX = (sx + extraX * offset) / scale;
        float sourceY = (sy + extraY * 0.5f) / scale;
        boolean inside = sourceX >= 0f && sourceX < iw && sourceY >= 0f && sourceY < ih;
        return name + "{scale=" + fmt(scale) + ",rendered=" + Math.round(renderedW) + "x"
            + Math.round(renderedH) + ",excess=" + Math.round(extraX) + "x" + Math.round(extraY)
            + ",src=" + fmt(sourceX) + "," + fmt(sourceY) + ",inside=" + inside + "}";
    }
    private static String fmt(float n) {
        return String.format(java.util.Locale.US, "%.3f", n);
    }
    private static void inspectMappingModels(View folder, int x, int y, int w, int h,
                                             int sw, int sh, boolean visible, long now) {
        if (mappingModelLogs >= 32 || !visible || wallpaperImageW <= 0 || wallpaperImageH <= 0
            || wallpaperDisplayW <= 0 || wallpaperDisplayH <= 0) return;
        // The request is process-local and may be stale when a folder first appears.
        long age = wallpaperOffsetTime == 0 ? -1 : now - wallpaperOffsetTime;
        float offset = wallpaperRequestedX;
        if ("UNKNOWN".equals(wallpaperRequestState(now))) {
            if (mappingModelLogs++ < 32) log("REFRACTION_MAPPING_MODELS status=SKIPPED reason=offset_unknown_or_invalid ageMs="+age
                +" id="+Integer.toHexString(System.identityHashCode(folder)));
            return;
        }
        int iw=wallpaperImageW, ih=wallpaperImageH, dw=wallpaperDisplayW, dh=wallpaperDisplayH;
        float sx=Math.max(0f,Math.min(dw-1f,x+w*0.5f));
        float sy=Math.max(0f,Math.min(dh-1f,y+h*0.5f));
        float coverScale=Math.max((float)dw/iw,(float)dh/ih);
        float desiredScale=wallpaperDesiredW>0 ? Math.max(coverScale,(float)wallpaperDesiredW/iw) : Float.NaN;
        if (Float.isFinite(desiredScale) && desiredScale > coverScale * 1.5f) desiredScale = Float.NaN;
        mappingModelLogs++;
        log("REFRACTION_MAPPING_MODELS status=HYPOTHETICAL id="+Integer.toHexString(System.identityHashCode(folder))
            +" screenPoint=["+fmt(sx)+","+fmt(sy)+"] wallpaperOffsetRequest="+fmt(offset)
            +" offsetAgeMs="+age+" requestState="+wallpaperRequestState(now)+" revision="+wallpaperOffsetRevision
            +" "+mappingHypothesis("CENTER_COVER",coverScale,offset,sx,sy,iw,ih,dw,dh)
            +" "+mappingHypothesis("DESIRED_WIDTH",desiredScale,offset,sx,sy,iw,ih,dw,dh)
            +" compositorCrop=UNKNOWN compositorScale=UNKNOWN verified=false pixelsRead=false rendering=false");
    }
    // V1.9.51: keep the optional comparison inside the actual OEM blur preview,
    // not the FolderIcon view (which includes labels and other child content).
    private static RectF visualPreviewRect(View folder) {
        try {
            Object preview=field(folder.getClass(),"mBackground").get(folder);
            if(preview==null)return null;
            Method getter=preview.getClass().getDeclaredMethod("getBgDrawable");
            getter.setAccessible(true);
            Object value=getter.invoke(preview);
            if(!(value instanceof Drawable))return null;
            Rect bounds=((Drawable)value).getBounds();
            if(bounds==null||bounds.isEmpty())return null;
            float ox=((Number)field(preview.getClass(),"basePreviewOffsetX").get(preview)).floatValue();
            float oy=((Number)field(preview.getClass(),"basePreviewOffsetY").get(preview)).floatValue();
            RectF r=new RectF(ox+bounds.left,oy+bounds.top,ox+bounds.right,oy+bounds.bottom);
            if(r.left<0||r.top<0||r.right>folder.getWidth()+1||r.bottom>folder.getHeight()+1)return null;
            return r;
        }catch(Throwable ignored){return null;}
    }
    // This is a source-pixel experiment, NOT a validated wallpaper-to-screen transform.
    private static void drawWallpaperVisualComparison(View folder, Canvas canvas) {
        if (!wallpaperCompare || !diagnostics || !glassEnabled || canvas==null ||
            wallpaperImageW<=0 || wallpaperImageH<=0 || wallpaperDisplayW<=0 || wallpaperDisplayH<=0 ||
            "UNKNOWN".equals(wallpaperRequestState(android.os.SystemClock.uptimeMillis()))) return;
        RectF preview=visualPreviewRect(folder);
        // Only the large square folder: prevent repeated overlays on every desktop folder.
        if(preview==null || preview.width()<360f || preview.height()<360f ||
            Math.abs(preview.width()-preview.height())>preview.width()*0.12f)return;
        int[] pos=new int[2]; folder.getLocationOnScreen(pos);
        float screenLeft=pos[0]+preview.left, screenTop=pos[1]+preview.top;
        if(screenLeft<0||screenTop<0||screenLeft+preview.width()>wallpaperDisplayW||
            screenTop+preview.height()>wallpaperDisplayH)return;
        long now=android.os.SystemClock.uptimeMillis();
        VisualPatch patch;
        synchronized(VISUAL_PATCHES){patch=VISUAL_PATCHES.get(folder);}
        if(patch==null || (now-patch.time>4500L && patch.revision!=wallpaperOffsetRevision)) {
            VisualPatch next=new VisualPatch(); next.time=now; next.revision=wallpaperOffsetRevision;
            android.os.ParcelFileDescriptor fd=null; android.graphics.BitmapRegionDecoder decoder=null;
            try {
                float sx=screenLeft+preview.width()*0.5f, sy=screenTop+preview.height()*0.5f;
                float cover=Math.max((float)wallpaperDisplayW/wallpaperImageW,(float)wallpaperDisplayH/wallpaperImageH);
                float desired=wallpaperDesiredW>0?Math.max(cover,(float)wallpaperDesiredW/wallpaperImageW):cover;
                float[] scales={cover,desired};
                android.app.WallpaperManager wm=android.app.WallpaperManager.getInstance(folder.getContext());
                fd=wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
                if(fd==null)return;
                decoder=android.graphics.BitmapRegionDecoder.newInstance(fd.getFileDescriptor(),false);
                for(int i=0;i<2;i++){
                    float scale=scales[i];
                    float px=(sx+Math.max(0f,wallpaperImageW*scale-wallpaperDisplayW)*wallpaperRequestedX)/scale;
                    float py=(sy+Math.max(0f,wallpaperImageH*scale-wallpaperDisplayH)*0.5f)/scale;
                    int half=16, left=Math.max(0,Math.min(wallpaperImageW-1,(int)px-half));
                    int top=Math.max(0,Math.min(wallpaperImageH-1,(int)py-half));
                    android.graphics.Rect region=new android.graphics.Rect(left,top,Math.min(wallpaperImageW,left+32),Math.min(wallpaperImageH,top+32));
                    android.graphics.Bitmap bitmap=decoder.decodeRegion(region,new android.graphics.BitmapFactory.Options());
                    if(i==0){next.cover=bitmap;next.cx=px;next.cy=py;}
                    else{next.desired=bitmap;next.dx=px;next.dy=py;}
                }
                synchronized(VISUAL_PATCHES){VisualPatch old=VISUAL_PATCHES.put(folder,next);if(old!=null)old.dispose();}
                patch=next;
                if(visualProbeLogs++<16)log("REFRACTION_VISUAL_COMPARE status=PATCH_READY layout=PREVIEW_CLIPPED offset="+fmt(wallpaperRequestedX)
                    +" cover=["+fmt(next.cx)+","+fmt(next.cy)+"] desired=["+fmt(next.dx)+","+fmt(next.dy)
                    +"] verified=false pixelsRendered=PATCH_ONLY");
            } catch(Throwable t){if(visualProbeLogs++<16)log("REFRACTION_VISUAL_COMPARE status=UNAVAILABLE reason="+t.getClass().getSimpleName());return;}
            finally{if(decoder!=null)try{decoder.recycle();}catch(Throwable ignored){} if(fd!=null)try{fd.close();}catch(Throwable ignored){}}
        }
        if(patch==null)return;
        float density=folder.getResources().getDisplayMetrics().density;
        // Width is strictly bounded by preview, including labels, padding and spacing.
        float inset=12f, gap=8f;
        float panelWidth=Math.min(preview.width()-2f*inset,260f);
        float tile=(panelWidth-gap)/2f;
        float header=17f, panelHeight=tile+header+10f;
        float x=preview.left+(preview.width()-panelWidth)/2f;
        float y=preview.bottom-inset-panelHeight;
        if(tile<50f||y<preview.top+inset)return;
        int save=canvas.save();
        try {
            android.graphics.Path path=new android.graphics.Path();
            float corner=Math.min(34f,Math.min(preview.width(),preview.height())*0.09f);
            path.addRoundRect(preview,corner,corner,android.graphics.Path.Direction.CW);
            canvas.clipPath(path);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xD9182028);
            canvas.drawRoundRect(new RectF(x-5f,y-5f,x+panelWidth+5f,y+panelHeight+5f),8f,8f,p);
            p.setColor(Color.WHITE);p.setTextSize(Math.min(12f,10f*density));
            canvas.drawText("COVER",x+3f,y+12f,p);
            canvas.drawText("DESIRED",x+tile+gap+3f,y+12f,p);
            p.setFilterBitmap(true);p.setColor(Color.WHITE);
            RectF a=new RectF(x,y+header,x+tile,y+header+tile);
            RectF b=new RectF(x+tile+gap,y+header,x+panelWidth,y+header+tile);
            if(patch.cover!=null)canvas.drawBitmap(patch.cover,null,a,p);
            if(patch.desired!=null)canvas.drawBitmap(patch.desired,null,b,p);
            p.setColor(0xAAFFFFFF);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1f);
            canvas.drawRect(a,p);canvas.drawRect(b,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFE08A);p.setStrokeWidth(1.5f);
            for(RectF r:new RectF[]{a,b}){
                float cx=r.centerX(),cy=r.centerY();
                canvas.drawLine(cx-5f,cy,cx+5f,cy,p);
                canvas.drawLine(cx,cy-5f,cx,cy+5f,p);
            }
        }finally{canvas.restoreToCount(save);}
    }
    private static void inspectScrollCoordinates(View folder) {
        if (!diagnostics || refractionMotionLogs >= 48 || !folder.isAttachedToWindow()) return;
        try {
            long now=android.os.SystemClock.uptimeMillis();
            int[] screen=new int[2]; folder.getLocationOnScreen(screen);
            int w=folder.getWidth(), h=folder.getHeight();
            if (w<=0 || h<=0) return;
            android.util.DisplayMetrics dm=folder.getResources().getDisplayMetrics();
            int sw=dm.widthPixels, sh=dm.heightPixels;
            validateWallpaperRequestContext(folder,sw,sh);
            boolean visible=screen[0]<sw && screen[1]<sh && screen[0]+w>0 && screen[1]+h>0;
            boolean fullyVisible=screen[0]>=0 && screen[1]>=0 && screen[0]+w<=sw && screen[1]+h<=sh;
            long[] prev;
            synchronized (REFRACTION_MOTION) {
                prev=REFRACTION_MOTION.get(folder);
                if (prev!=null && now-prev[0]<300L) return;
                if (prev!=null && now-prev[0]<1500L &&
                    Math.abs(screen[0]-prev[1])<2 && Math.abs(screen[1]-prev[2])<2) return;
                REFRACTION_MOTION.put(folder,new long[]{now,screen[0],screen[1]});
            }
            int dx=prev==null?0:screen[0]-(int)prev[1];
            int dy=prev==null?0:screen[1]-(int)prev[2];
            View ancestor=folder;
            StringBuilder chain=new StringBuilder();
            for(int i=0;i<7 && ancestor!=null;i++) {
                if(i>0)chain.append('>');
                chain.append(ancestor.getClass().getSimpleName()).append('(')
                    .append(ancestor.getScrollX()).append(',').append(ancestor.getScrollY()).append(')');
                Object parent=ancestor.getParent();
                ancestor=parent instanceof View?(View)parent:null;
            }
            if (refractionMotionLogs < 48 && (refractionMotionLogs % 6 == 0)) {
                long age=wallpaperOffsetTime==0?-1:now-wallpaperOffsetTime;
                boolean valid=visible && wallpaperRequestedX>=0f && wallpaperRequestedX<=1f
                    && wallpaperRequestedY>=0f && wallpaperRequestedY<=1f
                    && !"UNKNOWN".equals(wallpaperRequestState(now)) && wallpaperImageW>0 && wallpaperImageH>0
                    && wallpaperDisplayW>0 && wallpaperDisplayH>0;
                log("REFRACTION_MAPPING_CANDIDATE id="+Integer.toHexString(System.identityHashCode(folder))
                    +" visible="+visible+" offsetState="+wallpaperRequestState(now)+" offsetKnown="+(!"UNKNOWN".equals(wallpaperRequestState(now)))
                    +" imageBoundsKnown="+(wallpaperImageW>0&&wallpaperImageH>0)
                    +" screenRect=["+screen[0]+","+screen[1]+","+(screen[0]+w)+","+(screen[1]+h)+"]"
                    +" wallpaperRequest=["+wallpaperRequestedX+","+wallpaperRequestedY+"]"
                    +" metadataReady="+valid+" mappingVerified=false reason=final_crop_scale_unavailable");
            }
            if (wallpaperStateLogs < 28 && (wallpaperStateLogs == 0 || refractionMotionLogs % 6 == 0)) {
                wallpaperStateLogs++;
                log("REFRACTION_OFFSET_STATE state="+wallpaperRequestState(now)
                    +" revision="+wallpaperOffsetRevision
                    +" lastRequestAgeMs="+(wallpaperOffsetTime==0?-1:now-wallpaperOffsetTime)
                    +" lastMotionAgeMs="+(wallpaperLastMotionTime==0?-1:now-wallpaperLastMotionTime)
                    +" request=["+wallpaperRequestedX+","+wallpaperRequestedY+"] verified=false");
            }
            inspectMappingModels(folder,screen[0],screen[1],w,h,sw,sh,visible,now);
            refractionMotionLogs++;
            log("REFRACTION_SCROLL_SAMPLE id="+Integer.toHexString(System.identityHashCode(folder))
                +" screen=["+screen[0]+","+screen[1]+"] delta=["+dx+","+dy+"]"
                +" size="+w+"x"+h+" viewport="+sw+"x"+sh
                +" visible="+visible+" fullyVisible="+fullyVisible
                +" wallpaperRequest=["+wallpaperRequestedX+","+wallpaperRequestedY+"]"
                +" wallpaperRequestAgeMs="+(wallpaperOffsetTime==0?-1:now-wallpaperOffsetTime)
                +" offsetState="+wallpaperRequestState(now)+" offsetRevision="+wallpaperOffsetRevision
                +" ancestorScroll="+chain);
            if(refractionMotionLogs==1)
                log("REFRACTION_COORDINATE_LIMIT wallpaperOffset=REQUEST_ONLY"
                    +" wallpaperCrop=UNVERIFIED wallpaperScale=UNVERIFIED"
                    +" backdropPixels=UNAVAILABLE sampling=false"
                    +" reason=wallpaper_manager_requests_not_final_surface_transform");
        } catch(Throwable t) {
            if(refractionMotionLogs++<48)log("REFRACTION_SCROLL_ERROR "+t.getClass().getSimpleName());
        }
    }
    private static void applyNativeBackground(View v) {
        refresh(v);
        inspectFolderBlur(v);
        inspectSamplingFeasibility(v);
        inspectWallpaperGeometry(v);
        inspectWallpaperReferenceFeasibility(v);
        inspectScrollCoordinates(v);
        trackFolderPhase(v, "DRAW");
        inspectSamplingGate(v, "DRAW");
        traceViewHierarchy(v);
        try {
            Object preview=field(v.getClass(),"mBackground").get(v);
            if(preview==null)return;
            Method m=preview.getClass().getDeclaredMethod("getBgDrawable");m.setAccessible(true);
            Object result=m.invoke(preview);
            if(!(result instanceof Drawable))return;
            Drawable d=(Drawable)result;
            if (d instanceof GradientDrawable) {
                synchronized (TRACKED_BACKGROUNDS) { TRACKED_BACKGROUNDS.add(d); }
            } else if (d.getClass().getName().endsWith(".LayerBlurDrawable")) {
                synchronized (TRACKED_BLUR_BACKGROUNDS) { TRACKED_BLUR_BACKGROUNDS.add(d); }
            }
            synchronized(ORIGINAL_ALPHA){if(!ORIGINAL_ALPHA.containsKey(d))ORIGINAL_ALPHA.put(d,d.getAlpha());}
            int original=ORIGINAL_ALPHA.get(d);
            int requested=glassAlpha;
            boolean isBlur=d.getClass().getName().endsWith(".LayerBlurDrawable");
            if(isBlur && BLUR_API_SEEN.add(d.getClass().getName())) {
                try {
                    for(Method method:d.getClass().getMethods()) {
                        String name=method.getName().toLowerCase(java.util.Locale.ROOT);
                        if(name.contains("blur") || name.contains("alpha") || name.contains("radius"))
                            log("NATIVE_BLUR_API "+method.toGenericString());
                    }
                    Object child=d.getClass().getMethod("getBlurDrawable").invoke(d);
                    log("NATIVE_BLUR_CHILD "+(child==null?"null":child.getClass().getName()));
                    if(child!=null)for(Method method:child.getClass().getMethods()) {
                        String name=method.getName().toLowerCase(java.util.Locale.ROOT);
                        if(name.contains("blur") || name.contains("alpha") || name.contains("radius"))
                            log("NATIVE_BLUR_CHILD_API "+method.toGenericString());
                    }
                }catch(Throwable t){log("NATIVE_BLUR_API_ERROR "+t.getClass().getSimpleName());}
            }
            // V1.9.36: diagnostic probing is independent of BLUR_API_SEEN.
            // A first draw with diagnostics off must not permanently suppress later probes.
            if (isBlur && diagnostics && BLUR_PARAMS_PROBED.add(d.getClass().getName())) {
                try {
                    Object child = d.getClass().getMethod("getBlurDrawable").invoke(d);
                    inspectBlurParameters(child);
                } catch (Throwable t) {
                    log("BLUR_PARAMS_PROBE_ERROR " + t.getClass().getSimpleName());
                }
            }
            // Verified native blur drawable alpha control. This does not change blur radius.
            int alpha=isBlur ? original : (glassEnabled && customBlurMode!=2 ? 0 : (glassEnabled?requested:original));
            // V1.9.114: keep OEM blur drawable opacity untouched. The glass
            // alpha slider must never silently weaken the underlying blur.
            if(d.getAlpha()!=alpha)d.setAlpha(alpha);
            // V1.9.4 tint is painted directly on the native Canvas after background.draw.
            // Clear any legacy color filter previously applied by V1.9.3.
            synchronized(TINTED){if(TINTED.remove(d))d.clearColorFilter();}
            if(alphaLogs++<16)log("MATERIAL_APPLIED enabled="+glassEnabled+" size="+v.getWidth()+"x"+v.getHeight()+
                " alpha="+alpha+" nativeAlphaProbe="+nativeAlphaProbe+" tint="+tintMode+" drawable="+drawableTag(d));
        }catch(Throwable t){if(alphaLogs++<12)log("MATERIAL_FAILED reason="+t.getClass().getSimpleName());}
    }
    // Weak references avoid retaining Launcher drawables beyond their lifecycle.
    // Drawing inside GradientDrawable.draw(Canvas) uses the SAME canvas matrix and
    // bounds as the native background. No FolderIcon size/padding guesses.
    private static final Set<Drawable> TRACKED_BACKGROUNDS = Collections.newSetFromMap(new WeakHashMap<Drawable, Boolean>());
    private static final Set<Drawable> TRACKED_BLUR_BACKGROUNDS = Collections.newSetFromMap(new WeakHashMap<Drawable, Boolean>());
    private static int blurFusionLogs = 0;
    // This overlay is drawn AFTER the OEM blur drawable, never changing its alpha or blur state.
    // A round-rect outline is used only when the OEM drawable exposes one. Unknown shapes
    // deliberately skip the overlay instead of guessing corner geometry.
    private static void drawBlurGlassOverlay(Drawable drawable, Canvas canvas) {
        if (!glassEnabled || probeMode == 1) return;
        Rect bounds = drawable.getBounds();
        if (bounds.isEmpty()) return;
        Outline outline = new Outline();
        drawable.getOutline(outline);
        Rect r = new Rect();
        if (!outline.getRect(r) || r.isEmpty() || outline.getRadius() <= 0f) {
            // No arbitrary corner guess: the OEM shape is required for this rendering path.
            if (blurFusionLogs++ < 6) log("BLUR_FUSION_SKIPPED reason=outline_unavailable class="+drawableTag(drawable));
            return;
        }
        RectF rect = new RectF(r);
        float radius = outline.getRadius();
        int save = canvas.save();
        try {
            Path clip = new Path();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            float alphaScale = glassAlpha / 255f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // Glass alpha controls the surface overlay only; it does not alter OEM blur alpha.
            int topAlpha = Math.round(32f * alphaScale);
            int bottomAlpha = Math.round(12f * alphaScale);
            paint.setShader(new android.graphics.LinearGradient(rect.left,rect.top,rect.left,rect.bottom,
                new int[]{Color.argb(topAlpha,255,255,255),Color.argb(bottomAlpha,180,205,230)},null,
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect,radius,radius,paint);
            paint.setShader(null);
            if (tintMode != 0 && tintStrength > 0 && probeMode != 2) {
                paint.setColor(tintColor());
                paint.setAlpha(Math.round(tintStrength * 0.85f * alphaScale));
                canvas.drawRoundRect(rect,radius,radius,paint);
            }
            if (highlightEnabled && highlightStrength > 0 && probeMode != 3) {
                float inset = Math.max(0.5f,highlightWidth * 0.35f);
                RectF inner = new RectF(rect); inner.inset(inset,inset);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f,highlightWidth));
                paint.setShader(new android.graphics.LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
                    directionalLight ? new int[]{0xCCFFFFFF,0x66E8F4FF,0x22FFFFFF} : new int[]{0x88FFFFFF,0x88FFFFFF},
                    null,android.graphics.Shader.TileMode.CLAMP));
                paint.setAlpha(Math.round(highlightStrength * 255f / 100f));
                canvas.drawRoundRect(inner,Math.max(0,radius-inset),Math.max(0,radius-inset),paint);
            }
            if (blurFusionLogs++ < 10) log("BLUR_FUSION_APPLIED drawable="+drawableTag(drawable)+" bounds="+r.toShortString()+" radius="+radius);
        } finally { canvas.restoreToCount(save); }
    }
    // V1.9.92: inspect the real Drawable.draw implementation; do not assume
    // LayerBlurDrawable declares draw(Canvas), and do not modify its output.
    private static void probeBlurDrawImplementation(ClassLoader loader){
        try{
            Class<?> type=Class.forName("com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable",false,loader);
            Method method=type.getMethod("draw",Canvas.class);
            log("BLUR_DRAW_IMPLEMENTATION owner="+method.getDeclaringClass().getName()+" signature="+method.toGenericString());
            // This diagnostic hook is read-only and counts actual draw invocations.
            XposedBridge.hookMethod(method,new XC_MethodHook(){
                int calls=0;
                @Override protected void beforeHookedMethod(MethodHookParam param){
                    if(calls++>=32)return;
                    boolean tracked=false;
                    if(param.thisObject instanceof Drawable){
                        synchronized(TRACKED_BLUR_BACKGROUNDS){tracked=TRACKED_BLUR_BACKGROUNDS.contains(param.thisObject);}
                    }
                    log("BLUR_DRAW_ACTUAL class="+param.thisObject.getClass().getName()+" tracked="+tracked+" insideFolderDispatch="+(ACTIVE_FOLDER_ICON.get()!=null));
                }
            });
        }catch(Throwable t){log("BLUR_DRAW_IMPLEMENTATION_UNAVAILABLE reason="+t.getClass().getSimpleName());}
    }
    private static void hookLayerBlurDrawableDraw(ClassLoader loader) {
        try {
            Class<?> type = Class.forName("com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable",false,loader);
            Method draw = type.getDeclaredMethod("draw",Canvas.class);
            draw.setAccessible(true);
            XposedBridge.hookMethod(draw,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Drawable) || param.args == null || param.args.length < 1 || !(param.args[0] instanceof Canvas)) return;
                    Drawable d=(Drawable)param.thisObject;
                    synchronized(TRACKED_BLUR_BACKGROUNDS) { if (!TRACKED_BLUR_BACKGROUNDS.contains(d)) return; }
                    try { drawBlurGlassOverlay(d,(Canvas)param.args[0]); }
                    catch(Throwable t) { if(blurFusionLogs++<10)log("BLUR_FUSION_FAILED reason="+t.getClass().getSimpleName()); }
                }
            });
            installed++;
            log("BLUR_FUSION_HOOK_INSTALLED class="+type.getName());
        } catch(Throwable t) { log("BLUR_FUSION_HOOK_UNAVAILABLE reason="+t.getClass().getSimpleName()); }
    }

    // V1.9.19: draw within FolderIcon's own Canvas. Blur is composited by the OEM
    // beneath this View; material goes before child icons, edge light after them.
    // V1.9.20: read-only diagnostics for OEM geometry and blur control points.
    // We do not fabricate a blur-strength slider until a real setter is identified.
    private static int fusionGeometryLogs = 0;
    private static void inspectFusionGeometry(View v, Object preview, Drawable blur) {
        if (!diagnostics || fusionGeometryLogs >= 14) return;
        fusionGeometryLogs++;
        try {
            Rect bounds = blur.getBounds();
            Outline outline = new Outline(); blur.getOutline(outline);
            Rect outlineRect = new Rect();
            boolean hasOutline = outline.getRect(outlineRect);
            log("FUSION_GEOMETRY_NATIVE view="+v.getWidth()+"x"+v.getHeight()
                +" padding="+v.getPaddingLeft()+","+v.getPaddingTop()+","+v.getPaddingRight()+","+v.getPaddingBottom()
                +" blurBounds="+bounds.toShortString()
                +" blurOutline="+(hasOutline?outlineRect.toShortString():"none")
                +" blurRadius="+outline.getRadius());
            int count=0;
            for(Class<?> c=preview.getClass();c!=null && count<60;c=c.getSuperclass()) {
                for(Field f:c.getDeclaredFields()) {
                    if(Modifier.isStatic(f.getModifiers())) continue;
                    String n=f.getName().toLowerCase(java.util.Locale.ROOT);
                    if(!(n.contains("size")||n.contains("width")||n.contains("height")||n.contains("radius")||n.contains("offset")||n.contains("padding")||n.contains("scale")||n.contains("rect")||n.contains("bounds")))continue;
                    try { f.setAccessible(true); Object value=f.get(preview);
                        if(value instanceof Number || value instanceof Rect || value instanceof RectF)
                            log("FUSION_GEOMETRY_FIELD "+f.getName()+"="+String.valueOf(value));
                    }catch(Throwable ignored){}
                    count++;
                }
            }
            if(fusionGeometryLogs<=3) {
                int found=0;
                for(Class<?> c=blur.getClass();c!=null && found<32;c=c.getSuperclass()) {
                    for(Method m:c.getDeclaredMethods()) {
                        String n=m.getName().toLowerCase(java.util.Locale.ROOT);
                        if(n.contains("blur")||n.contains("radius")||n.contains("strength")) {
                            log("FUSION_BLUR_API "+m.toGenericString());
                            found++;
                        }
                    }
                }
            }
        }catch(Throwable t){log("FUSION_GEOMETRY_FAILED "+t.getClass().getSimpleName());}
    }
    // V1.9.24: Feather only the material fill, not the OEM blur or icon content.
    // The OEM contour is not available as a Path; a gradual inner fade prevents
    // the independently drawn rounded-rect fill from producing a hard second edge.
    private static final java.util.LinkedHashMap<String, android.graphics.Bitmap> EDGE_MASK_CACHE =
        new java.util.LinkedHashMap<String, android.graphics.Bitmap>(12, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) {
                return size() > 8;
            }
        };
    private static android.graphics.Bitmap materialEdgeMask(int width, int height, float radius, float feather) {
        if (width <= 0 || height <= 0 || width > 1200 || height > 1200) return null;
        String key = width+"x"+height+"/"+Math.round(radius*4)+"/"+Math.round(feather*4);
        synchronized (EDGE_MASK_CACHE) {
            android.graphics.Bitmap cached = EDGE_MASK_CACHE.get(key);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        android.graphics.Bitmap mask = android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width*height];
        float r = Math.min(radius,Math.min(width,height)*0.5f);
        float cx = width*0.5f, cy = height*0.5f;
        float bx = width*0.5f-r, by = height*0.5f-r;
        float soft = Math.max(3f,feather);
        for (int y=0;y<height;y++) {
            float py = Math.abs(y+0.5f-cy)-by;
            for (int x=0;x<width;x++) {
                float px = Math.abs(x+0.5f-cx)-bx;
                float outside = (float)Math.hypot(Math.max(px,0f),Math.max(py,0f));
                float inside = Math.min(Math.max(px,py),0f);
                float signed = outside+inside-r;
                float t = Math.max(0f,Math.min(1f,(-signed)/soft));
                t=t*t*(3f-2f*t);
                int alpha=Math.round(t*255f);
                pixels[y*width+x]=(alpha<<24)|0x00FFFFFF;
            }
        }
        mask.setPixels(pixels,0,width,0,0,width,height);
        synchronized (EDGE_MASK_CACHE) { EDGE_MASK_CACHE.put(key,mask); }
        return mask;
    }
    // V1.9.26: both modes use the same native-alpha-mask material renderer.
    // The blur mode constructs a contour source from the OEM outline; it does
    // NOT claim to capture the internal LayerBlurDrawable alpha pixels.
    private static final java.util.Map<String, MaskCache> BLUR_SHAPE_MASKS =
        new java.util.LinkedHashMap<String, MaskCache>();
    // V1.9.28: preserve all four straight edges; correct only the corner curve.
    // This is a geometry experiment, not an exact OEM LayerBlurDrawable alpha mask.
    private static final float BLUR_CORNER_RADIUS_DELTA_PX = 0f;
    private static volatile MaskCache LAST_SHAPE_MASK;
    private static volatile int LAST_SHAPE_W=-1,LAST_SHAPE_H=-1,LAST_SHAPE_RADIUS=-1,LAST_SHAPE_WIDTH=-1;
    private static MaskCache blurShapeMask(int w,int h,float radius) {
        int radiusKey=Math.round(radius*100);
        int widthKey=highlightWidth;
        MaskCache hot=LAST_SHAPE_MASK;
        if(hot!=null && LAST_SHAPE_W==w && LAST_SHAPE_H==h &&
            LAST_SHAPE_RADIUS==radiusKey && LAST_SHAPE_WIDTH==widthKey)return hot;
        String key=w+"x"+h+"/"+radiusKey+"/"+widthKey+"/cornerDelta"+BLUR_CORNER_RADIUS_DELTA_PX;
        synchronized(BLUR_SHAPE_MASKS) {
            MaskCache cached=BLUR_SHAPE_MASKS.get(key);
            if(cached!=null){
                LAST_SHAPE_W=w;LAST_SHAPE_H=h;LAST_SHAPE_RADIUS=radiusKey;
                LAST_SHAPE_WIDTH=widthKey;LAST_SHAPE_MASK=cached;
                return cached;
            }
        }
        if(w<8||h<8||w>1400||h>1400)return null;
        try {
            final float correctedRadius=Math.min(Math.min(w,h)*0.5f, radius+BLUR_CORNER_RADIUS_DELTA_PX);
            android.graphics.Bitmap base=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            Canvas mc=new Canvas(base);
            Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.WHITE);
            RectF inner=new RectF(0f,0f,w,h);
            mc.drawRoundRect(inner,correctedRadius,correctedRadius,fill);
            int[] pixels=new int[w*h];base.getPixels(pixels,0,w,0,0,w,h);
            int[] edgePixels=new int[w*h],glowPixels=new int[w*h];
            int width=Math.max(1,Math.min(5,highlightWidth));
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                int i=y*w+x,center=pixels[i]>>>24;
                if(center<8)continue;
                int near=255,far=255;
                for(int dy=-width;dy<=width;dy++)for(int dx=-width;dx<=width;dx++) {
                    int dist=Math.max(Math.abs(dx),Math.abs(dy));
                    if(dist==0)continue;
                    int xx=x+dx, yy=y+dy;
                    int sample=(xx<0||xx>=w||yy<0||yy>=h)?0:(pixels[yy*w+xx]>>>24);
                    if(dist==1 && sample<near)near=sample;
                    if(sample<far)far=sample;
                }
                int sharp=Math.max(0,Math.min(center,(center-near)*2));
                int soft=Math.max(0,Math.min(center,center-far));
                edgePixels[i]=(sharp<<24)|0x00FFFFFF;
                glowPixels[i]=(soft<<24)|0x00FFFFFF;
            }
            android.graphics.Bitmap edge=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Bitmap glow=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            edge.setPixels(edgePixels,0,w,0,0,w,h);
            glow.setPixels(glowPixels,0,w,0,0,w,h);
            MaskCache result=new MaskCache(w,h,255,Float.floatToIntBits(radius),width,base,edge,glow);
            synchronized(BLUR_SHAPE_MASKS) {
                if(BLUR_SHAPE_MASKS.size()>12)BLUR_SHAPE_MASKS.clear();
                BLUR_SHAPE_MASKS.put(key,result);
            }
            return result;
        } catch(Throwable t) {
            if(folderFusionLogs++<12)log("BLUR_CORNER_MASK_FAILED reason="+t.getClass().getSimpleName());
            return null;
        }
    }
    // V1.9.41: size-adaptive directional optical depth. Light comes from upper-left;
    // dark optical thickness is concentrated inside the lower-right corner.
    // This is an optical shading simulation, NOT sampled backdrop refraction.
    // Keep the V1.9.28 +6px corner geometry and straight edges unchanged.
    private static void drawAdaptiveBottomDepth(Canvas canvas, MaskCache cache, float strength) {
        if (cache == null || cache.mask == null || strength <= 0f) return;
        final int w = cache.w, h = cache.h;
        final float x = Math.max(0f, Math.min(1f, (w - 162f) / 282f));
        final float y = Math.max(0f, Math.min(1f, (h - 162f) / 282f));
        // Small folder is the visual reference. Larger backgrounds need more reach,
        // but not a full-width dark horizontal strip.
        final float reach = 10f + 4f * x + 4f * y + 8f * x * y;
        final float gain = 1f + 0.18f * x + 0.15f * y + 0.02f * x * y;
        // Large square folders were too dark over a broad lower-right area.
        // Reduce both coverage and intensity smoothly with size, preserving
        // the small 162x162 reference and non-square folders.
        final float largeSquare = x * y;
        final float opacity = Math.min(1f, strength * gain * (1f - 0.15f * largeSquare));
        final int coreAlpha = Math.min(112, Math.round(90f * opacity));
        if (coreAlpha <= 0) return;
        // Shift the focus slightly outside the lower-right corner on large squares.
        final float cx = w * (0.98f + 0.055f * largeSquare);
        final float cy = h * (0.98f + 0.055f * largeSquare);
        // Elliptical radial gradient: scales with each axis independently,
        // so horizontal and vertical folders keep a consistent light direction.
        final float rx = Math.max(reach * 2.8f, w * (0.39f + 0.09f * x)) * (1f - 0.20f * largeSquare);
        final float ry = Math.max(reach * 2.8f, h * (0.39f + 0.09f * y)) * (1f - 0.20f * largeSquare);
        final float radius = Math.max(rx, ry);
        android.graphics.RadialGradient gradient = new android.graphics.RadialGradient(
            cx, cy, radius,
            new int[]{(coreAlpha << 24) | 0x00152132,
                      (Math.round(coreAlpha * 0.58f) << 24) | 0x00203242,
                      0x00203242},
            new float[]{0f, 0.44f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        android.graphics.Matrix transform = new android.graphics.Matrix();
        transform.setScale(rx / radius, ry / radius, cx, cy);
        gradient.setLocalMatrix(transform);
        final int layer = canvas.saveLayer(0f, 0f, w, h, null);
        try {
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setShader(gradient);
            canvas.drawRect(0f, 0f, w, h, shadow);
            shadow.setShader(null);
            Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            clip.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
            canvas.drawBitmap(cache.mask, 0f, 0f, clip);
            clip.setXfermode(null);
        } finally { canvas.restoreToCount(layer); }
    }
    private static void drawUnifiedMaterial(Canvas canvas, MaskCache cache, boolean edgeOnly) {
        drawUnifiedMaterial(canvas,cache,edgeOnly,1f);
    }
    // V1.9.105: apply one temporal envelope to ALL native-shaped highlight
    // passes, not an additional overlay. Keep mask geometry unchanged.
    private static void drawUnifiedMaterial(Canvas canvas, MaskCache cache, boolean edgeOnly, float highlightEnvelope) {
        if(cache==null)return;
        boolean tint=tintMode!=0 && tintStrength>0 && glassTintOpacity>0 && probeMode!=2;
        boolean shine=(highlightEnabled && highlightStrength>0 && probeMode!=3)||probeMode==2;
        // New-only mode replaces the legacy glow/edge/rim passes, not the tint.
        if(edgeOnly && effectiveUnified193()==2)shine=false;
        if(!edgeOnly) {
            if(probeMode==0 && glassTintOpacity>0)drawOpticalGlass(canvas,cache);
            if(tint){
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                int c=tintColor();
                p.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    Color.rgb(Color.red(c),Color.green(c),Color.blue(c)),android.graphics.PorterDuff.Mode.SRC_IN));
                p.setAlpha(Math.round(160f*tintStrength/90f*glassTintOpacity/100f));
                canvas.drawBitmap(cache.mask,0,0,p);
            }
        } else if(shine) {
            float strength=probeMode==2?1f:(highlightStrength/100f)*Math.max(0f,Math.min(1f,highlightEnvelope));
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            if(probeMode==2){
                p.setAlpha(205);
                p.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.RED,android.graphics.PorterDuff.Mode.SRC_IN));
                canvas.drawBitmap(cache.glow,0,0,p);
                canvas.drawBitmap(cache.edge,0,0,p);
            }else{
                int layer=canvas.saveLayer(0,0,cache.w,cache.h,null);
                p.setAlpha(Math.round(100*strength));canvas.drawBitmap(cache.glow,0,0,p);
                p.setAlpha(Math.round(190*strength));canvas.drawBitmap(cache.edge,0,0,p);
                Paint light=new Paint(Paint.ANTI_ALIAS_FLAG);
                light.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                if(directionalLight)light.setShader(new android.graphics.LinearGradient(
                    0f,0f,cache.w*0.88f,cache.h*0.95f,
                    new int[]{0xFFFFFFFF,0xEAF4FCFF,0x75C8E3F5,0x30A3BCD1},
                    new float[]{0f,0.22f,0.64f,1f},android.graphics.Shader.TileMode.CLAMP));
                else light.setColor(0xCCFFFFFF);
                canvas.drawRect(0,0,cache.w,cache.h,light);
                light.setXfermode(null);canvas.restoreToCount(layer);
                // V1.9.32: stronger optical rim, reusing the verified SAME
                // OEM-aligned glow mask. No geometry changes or extra stroke.
                // First pass: broad luminous rim on top/left, subdued on bottom.
                if (probeMode==0) {
                    int rimLayer=canvas.saveLayer(0,0,cache.w,cache.h,null);
                    try {
                        Paint rim=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                        rim.setAlpha(Math.round(150f*strength));
                        canvas.drawBitmap(cache.glow,0,0,rim);
                        Paint shade=new Paint(Paint.ANTI_ALIAS_FLAG);
                        shade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                        shade.setShader(new android.graphics.LinearGradient(
                            0f,0f,cache.w*0.78f,cache.h,
                            new int[]{0xEFFFFFFF,0xA6F0FAFF,0x40D2E5F2,0x005A748A},
                            new float[]{0f,0.22f,0.65f,1f},
                            android.graphics.Shader.TileMode.CLAMP));
                        canvas.drawRect(0,0,cache.w,cache.h,shade);
                        shade.setXfermode(null);
                        shade.setShader(null);
                    } finally { canvas.restoreToCount(rimLayer); }
                    // V1.9.33: concentrated top-left specular reflection. Reuse
                    // the SAME OEM-aligned glow bitmap, so no second contour,
                    // geometry inset or outline is introduced. The shader only
                    // changes intensity inside the existing optical rim.
                    int specLayer=canvas.saveLayer(0,0,cache.w,cache.h,null);
                    try {
                        Paint spec=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                        spec.setAlpha(Math.round(190f*strength));
                        canvas.drawBitmap(cache.glow,0,0,spec);
                        Paint beam=new Paint(Paint.ANTI_ALIAS_FLAG);
                        beam.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                        beam.setShader(new android.graphics.RadialGradient(
                            cache.w*0.10f,cache.h*0.04f,
                            Math.max(cache.w,cache.h)*0.78f,
                            new int[]{0xDFFFFFFF,0x74F1FAFF,0x00FFFFFF},
                            new float[]{0f,0.38f,1f},
                            android.graphics.Shader.TileMode.CLAMP));
                        canvas.drawRect(0,0,cache.w,cache.h,beam);
                        beam.setXfermode(null);
                        beam.setShader(null);
                    } finally { canvas.restoreToCount(specLayer); }
                    // V1.9.74: no additional bottom-right dark arc. The OEM
                    // folder already supplies its own shadow and corner antialiasing.

                }
            }
        }
    }
    private static int folderFusionLogs = 0;
    private static final ThreadLocal<Boolean> FOLDER_FUSION_GUARD = new ThreadLocal<Boolean>();
    // V1.9.66: opt-in, independently supplied screen-space wallpaper reference.
    // Decode off the UI thread. Never read protected system paths in Launcher.
    private static volatile android.graphics.Bitmap REFRACTION_FRAME;
    private static volatile long REFRACTION_LAST_CHECK;
    private static volatile long REFRACTION_FRAME_MODIFIED = -1L;
    private static volatile long REFRACTION_FRAME_LENGTH = -1L;
    private static volatile boolean REFRACTION_LOADING;
    // 145: do not allow concurrent boot prewarm loops on repeated unlock broadcasts.
    private static volatile boolean BOOT_PREWARM_RUNNING;
    private static volatile long BOOT_PREWARM_GENERATION;
    private static final java.util.Map<View, Boolean> BOOT_FOLDER_VIEWS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());
    private static volatile boolean REFRACTION_ENABLED;
    private static int REFRACTION_LOGS;
    private static void scheduleReferenceLoad(final View view) {
        scheduleReferenceLoad(view.getContext().getApplicationContext(), view);
    }
    private static void scheduleReferenceLoad(final android.content.Context context, final View view) {
        if (!glassEnabled || REFRACTION_LOADING || (customBlurMode!=1 && !REFRACTION_ENABLED)) return;
        long now=android.os.SystemClock.uptimeMillis();
        // On boot the Launcher-owned wallpaper file can arrive after the first draw.
        // Retry rapidly until the first valid frame exists; use the longer interval
        // only after the frame is ready, to avoid steady-state disk churn.
        // Metadata checks run on the worker thread; hot wallpaper changes should not wait 30s.
        long retryMs=REFRACTION_FRAME==null ? 120L : 1000L;
        if(now-REFRACTION_LAST_CHECK<retryMs)return;
        REFRACTION_LAST_CHECK=now;
        REFRACTION_LOADING=true;
        final android.content.Context app=context;
        new Thread(new Runnable(){public void run(){
            android.graphics.Bitmap next=null;
            try {
                // Prefer a Launcher-owned reference: ColorOS blocks cold-starting
                // the module ContentProvider from Launcher at boot.
                // V1.9.98: Never call the blocked module provider from Launcher.
                // A missing file must be retried after CE unlock, not treated as a provider failure.
                java.io.File local=new java.io.File(app.getFilesDir(),"liquidglass_wallpaper_reference.jpg");
                // V148: DE mirror survives lock/unlock and is readable before CE unlock.
                java.io.File deMirror=new java.io.File(app.createDeviceProtectedStorageContext().getFilesDir(),"liquidglass_wallpaper_reference.jpg");
                // CE storage may become available only after unlock. Check both
                // locations on every attempt; prefer a valid, nonempty file.
                java.io.File alternate=new java.io.File("/data/user/0/com.android.launcher/files/liquidglass_wallpaper_reference.jpg");
                // V147: choose a usable CE/DE reference immediately, without
                // repeatedly waiting on an unavailable device-protected path.
                // If both exist, prefer the newest complete frame.
                boolean localValid=local.isFile() && local.length()>1024L;
                boolean alternateValid=alternate.isFile() && alternate.length()>1024L;
                boolean deValid=deMirror.isFile() && deMirror.length()>1024L;
                if(deValid && (!localValid || deMirror.lastModified()>local.lastModified())){ local=deMirror; localValid=true; }
                if(alternateValid && (!localValid || alternate.lastModified()>local.lastModified()))
                    local=alternate;
                long modified=-1L,length=-1L;
                if(!local.isFile() || local.length()<=1024L){
                    if(REFRACTION_LOGS++<24)log("BOOT_REFERENCE_WAIT path="+local.getAbsolutePath()+" exists="+local.exists());
                    return;
                }
                modified=local.lastModified();length=local.length();
                if(REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled() && modified==REFRACTION_FRAME_MODIFIED && length==REFRACTION_FRAME_LENGTH){
                    if(REFRACTION_LOGS++<24)log("REFERENCE_REUSE_148 memory_hit=true source="+local.getAbsolutePath());
                    if(customBlurMode==1 && customBlurDp>0){
                        android.util.DisplayMetrics metrics=app.getResources().getDisplayMetrics();
                        blurredWallpaper(REFRACTION_FRAME,metrics.density,null);
                    }
                    return;
                }
                android.graphics.BitmapFactory.Options opts=new android.graphics.BitmapFactory.Options();
                opts.inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888;
                next=android.graphics.BitmapFactory.decodeFile(local.getAbsolutePath(),opts);
                if(REFRACTION_LOGS++<24)log("BOOT_REFERENCE_DECODE path="+local.getAbsolutePath()+" decoded="+(next!=null));
                if(REFRACTION_LOGS++<24)log("REFERENCE_REUSE_148 disk_hit="+(next!=null)+" source="+local.getAbsolutePath());
                final long loadedModified=modified,loadedLength=length;
                if(next!=null && next.getWidth()>=320 && next.getHeight()>=640) {
                    REFRACTION_FRAME=next;
                    if(REFRACTION_LOGS++<24)log("BOOT_REFRACTION_READY source=launcher_private_wallpaper_reference");
                    REFRACTION_FRAME_MODIFIED=loadedModified;
                    REFRACTION_FRAME_LENGTH=loadedLength;
                    // V1.9.137: Prewarm blur as soon as the reference is decoded.
                    // Do not wait for the folder's first draw after unlock.
                    if(customBlurMode==1 && customBlurDp>0){
                        android.util.DisplayMetrics metrics=app.getResources().getDisplayMetrics();
                        blurredWallpaper(next,metrics.density,null);
                    }
                    // The first reference frame arrives off-thread. Force one draw even
                    // when the icon is stationary and hardware-render-node cached.
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable(){
                        @Override public void run(){
                            invalidateGlassViews();
                            synchronized(DYNAMIC_REFRACTION){
                                for(View trackedView:DYNAMIC_REFRACTION.keySet())
                                    if(trackedView!=null && trackedView.isAttachedToWindow()){
                                        trackedView.invalidate();
                                    }
                            }
                            if(view!=null && view.isAttachedToWindow()){
                                view.invalidate();
                                // A new wallpaper frame is a one-shot update, not a
                                // reason to invalidate the entire workspace repeatedly.
                            }
                        }
                    });
                    if(REFRACTION_LOGS++<12)log("REFRACTION_FRAME status=READY size="+next.getWidth()+"x"+next.getHeight());
                } else {
                    if(REFRACTION_LOGS++<12)log("REFRACTION_FRAME status=INVALID_OR_MISSING");
                }
            }catch(Throwable t){if(REFRACTION_LOGS++<24)log("REFRACTION_FRAME status=UNAVAILABLE reason="+t.getClass().getSimpleName()+" detail="+String.valueOf(t.getMessage()));}
            finally{REFRACTION_LOADING=false;}
        }},"LiquidGlass-Wallpaper-Reference").start();
    }
    // Keep each icon's shader and pre-draw observer in a weak map. No disk IO on UI thread.
    private static final java.util.Map<View, DynamicRefraction> DYNAMIC_REFRACTION =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, DynamicRefraction>());
    private static final class DynamicRefraction {
        final int[] screen=new int[2], window=new int[2];
        final float[] points=new float[2];
        final Matrix transform=new Matrix(), shaderMatrix=new Matrix();
        final Paint samplePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final Paint rimPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint cachedBlurPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final Matrix cachedBlurMatrix=new Matrix();
        android.graphics.Bitmap cachedBlurBitmap;
        android.graphics.BitmapShader cachedBlurShader;
        final Paint cachedTintPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip=new Path();
        android.graphics.BitmapShader shader;
        android.graphics.RuntimeShader continuousShader;
        boolean continuousUnavailable;
        boolean shaderStatusLogged;
        android.graphics.Shader rimGradient;
        android.graphics.Bitmap source;
        float lastX=Float.NaN,lastY=Float.NaN;
        android.view.ViewTreeObserver.OnPreDrawListener listener;
        int moveLogs;
        long coordLogAt=0L;
        int coordLogCount=0;
        float prevSampleX=Float.NaN,prevSampleY=Float.NaN;
        float prevLocationX=Float.NaN,prevLocationY=Float.NaN;
        float probe143LockedLeft=Float.NaN,probe143LockedTop=Float.NaN;
        float sample160AnchorX=Float.NaN,sample160AnchorY=Float.NaN;
        int sample160LastMode=-1;
        // Per-folder temporal response: smooth the optical overlay on real movement.
        float opticalOpacity=0f;
        long opticalUpdatedAt=0L;
        long opticalMotionRevision=-1L;
        long opticalLastMoveAt=0L;
        boolean opticalSettleScheduled=false;
        // V187: cached folder RenderNode must be explicitly refreshed on page return.
        float lastSpecularDirection187=0f;
        long lastSpecularDrawNs187=0L;
        boolean recenterPending187=false;
        boolean recenterRedrawScheduled187=false;
        // V188: per-view visual direction is eased independently of the motion sensor.
        float visualDirection188=0f;
        long visualUpdatedNs188=0L;
        long lastVisibleFrameNs188=0L;
        boolean redrawScheduled188=false;
    }
    private static DynamicRefraction dynamicState(final View view) {
        DynamicRefraction state=DYNAMIC_REFRACTION.get(view);
        if(state!=null)return state;
        state=new DynamicRefraction();
        final DynamicRefraction tracked=state;
        // V1.9.135: custom wallpaper blur must track folder motion even when AGSL refraction is OFF.
        // A parent translation can move a hardware-cached child without dispatchDraw.
        // Only invalidate on actual screen-coordinate change, never every frame.
        tracked.listener=new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                if(!(REFRACTION_ENABLED || (customBlurMode==1 && customBlurDp>0) || effectiveRim192()>=2) || !view.isAttachedToWindow())return true;
                try {
                    float[] xy=dynamicScreenPosition(view,tracked);
                    if(Float.isNaN(tracked.lastX) || Math.abs(xy[0]-tracked.lastX)>0.5f || Math.abs(xy[1]-tracked.lastY)>0.5f){
                        tracked.lastX=xy[0];tracked.lastY=xy[1];
                        view.invalidate();
                    }
                }catch(Throwable ignored){}
                return true;
            }
        };
        view.getViewTreeObserver().addOnPreDrawListener(tracked.listener);
        // V188: page-return detection is observational. Never start a burst of
        // delayed redraws for all folder views while the Workspace is animating.
        view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                if((specularMode184()!=3 && effectiveRim192()<2) || !view.isAttachedToWindow())return true;
                if(!tracked.recenterPending187)return true;
                long now=android.os.SystemClock.elapsedRealtimeNanos();
                if(now-tracked.lastVisibleFrameNs188>350000000L && Math.abs(tracked.visualDirection188)>0.005f){
                    tracked.lastVisibleFrameNs188=now;
                    scheduleRecenter188(view,tracked);
                    if(recenterLogs187++<24)log("SPECULAR_PAGE_RETURN_188 view="+System.identityHashCode(view)+" visual="+tracked.visualDirection188);
                }
                return true;
            }
        });
        DYNAMIC_REFRACTION.put(view,state);
        view.postInvalidate(); // Draw a stable refraction frame even before the first swipe
        return state;
    }
    private static int recenterLogs187=0;
    // V188: exactly one pending vsync callback per folder. Only animate when
    // the view is visible; hidden pages must not generate redundant GPU work.
    private static void scheduleRecenter188(final View view,final DynamicRefraction state){
        if(state.redrawScheduled188 || !view.isAttachedToWindow() || !view.isShown())return;
        if(Math.abs(state.visualDirection188)<0.005f)return;
        state.redrawScheduled188=true;
        view.postOnAnimation(new Runnable(){
            @Override public void run(){
                state.redrawScheduled188=false;
                if(!view.isAttachedToWindow() || !view.isShown() || (specularMode184()!=3 && effectiveRim192()<2))return;
                android.graphics.Rect visible=new android.graphics.Rect();
                if(!view.getGlobalVisibleRect(visible) || visible.isEmpty())return;
                // The shader's time-based envelope is applied during onDraw.
                // Avoid repeated invalidations in the same vsync.
                view.postInvalidateOnAnimation();
                if(recenterLogs187++<24)log("SPECULAR_RECENTER_FRAME_188 view="+System.identityHashCode(view)+" visual="+state.visualDirection188);
            }
        });
    }
    // V1.9.104: a single per-folder animation envelope shared by the optical
    // sampling pass and the extra native-shaped highlight. Static edge lighting
    // is deliberately left untouched, preserving V1.9.74 corner geometry.
    // V1.9.131: no motion-triggered invalidate loop. Wallpaper-position updates
    // are already handled by the pre-draw location observer.
    // V1.9.155: sampling-coordinate A/B probe; mode 0 is byte-for-byte legacy behavior.
    // 1 uses View.getLocationOnScreen; 2 uses matrix coordinates rounded to physical pixels.
    private static volatile int coordinateProbe155=0;
    private static volatile long coordinateProbeRead155=0L;
    private static int coordinateProbe155(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-coordinateProbeRead155>800L){
            coordinateProbeRead155=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int next=Math.max(0,Math.min(2,android.provider.Settings.Global.getInt(
                        c.getContentResolver(),"liquidglass_coordinate_probe_155",0)));
                    if(next!=coordinateProbe155){coordinateProbe155=next;log("COORD_PROBE_155 mode="+next);}
                }
            }catch(Throwable t){log("COORD_PROBE_155 read_failed="+t.getClass().getSimpleName());}
        }
        return coordinateProbe155;
    }
    private static long coordinateTraceAt155=0L;
    private static int coordinateTraceCount155=0;
    private static float[] dynamicScreenPosition(View view,DynamicRefraction state) {
        state.transform.reset();
        view.transformMatrixToGlobal(state.transform);
        state.points[0]=0f;state.points[1]=0f;
        state.transform.mapPoints(state.points);
        // transformMatrixToGlobal is in window coordinates; compensate window origin.
        View root=view.getRootView();
        root.getLocationOnScreen(state.screen);
        root.getLocationInWindow(state.window);
        state.points[0]+=state.screen[0]-state.window[0];
        state.points[1]+=state.screen[1]-state.window[1];
        return state.points;
    }
    // V1.9.90: independently controlled temporary visibility diagnostics.
    // 0=normal; 1=red pre-background / green post-background marker;
    // 2=strong optical displacement; 3=post-dispatch AGSL visibility probe. Read at most once per second.
    private static volatile int refractionVisibilityTest=0;
    private static volatile long refractionVisibilityReadAt=0;
    private static final ThreadLocal<Boolean> REFRACTION_AFTER_OEM=new ThreadLocal<Boolean>();
    private static int refractionTestLogs=0;
    private static int refractionReadLogs=0;
    private static int refractionPassLogs=0;
    private static int refractionMarkerLogs=0;
    private static int currentRefractionVisibilityTest(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-refractionVisibilityReadAt>1000L){
            refractionVisibilityReadAt=now;
            try {
                android.content.Context c=launcherContext;
                if(c!=null){
                    int next=android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_refraction_visibility_test",0);
                    next=Math.max(0,Math.min(3,next));
                    if(refractionReadLogs++<16)log("REFRACTION_TEST_READ raw="+android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_refraction_visibility_test",0)+" effective="+next+" context="+c.getPackageName());
                    if(next!=refractionVisibilityTest){
                        refractionVisibilityTest=next;
                        log("REFRACTION_VISIBILITY_TEST mode="+next);
                    }
                }
            }catch(Throwable t){if(refractionTestLogs++<5)log("REFRACTION_VISIBILITY_TEST_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return refractionVisibilityTest;
    }
    // V1.9.110: independent optical displacement A/B test. 0 = no horizontal
    // displacement, 1 = original 22px displacement. Sampling tracks the screen
    // in both cases; no coordinate interpolation or additional motion delay.
    private static volatile int displacementIsolationMode = 1;
    private static volatile long displacementIsolationReadAt = 0L;
    private static int currentDisplacementIsolationMode(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-displacementIsolationReadAt>1000L){
            displacementIsolationReadAt=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int mode=android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_refraction_displacement_test",1);
                    mode=mode==0?0:1;
                    if(mode!=displacementIsolationMode){
                        displacementIsolationMode=mode;
                        log("REFRACTION_DISPLACEMENT_TEST mode="+mode+
                            " (0=NO_DISPLACEMENT,1=ORIGINAL)");
                    }
                }
            }catch(Throwable t){log("REFRACTION_DISPLACEMENT_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return displacementIsolationMode;
    }
    // V1.9.110: isolate the two refraction composition entry points.
    // 0=original dual-pass, 1=early only, 2=callback only.
    private static volatile int compositionIsolationMode=0;
    private static volatile long compositionIsolationReadAt=0L;
    private static int currentCompositionIsolationMode(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-compositionIsolationReadAt>1000L){
            compositionIsolationReadAt=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int value=android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_refraction_composition_test",0);
                    value=Math.max(0,Math.min(2,value));
                    if(value!=compositionIsolationMode){
                        compositionIsolationMode=value;
                        log("REFRACTION_COMPOSITION_TEST mode="+value);
                    }
                }
            }catch(Throwable t){log("REFRACTION_COMPOSITION_TEST_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return compositionIsolationMode;
    }
    // V1.9.113: keep original wallpaper sampling at 0 and the proven fixed-gray
    // control at 1. Compare sampling boundary and sampled luminance separately:
    // 2 = explicit display-safe sampling clamp (only outside screen),
    // 3 = sampled luminance as neutral grayscale (same per-pixel luminance),
    // 4 = visualize out-of-display sampling in amber, 5 = reduced-luminance-contrast.
    // No temporal filtering, sampling lag, or motion-state-dependent alpha.
    private static volatile int sampleIsolationMode=0;
    private static volatile long sampleIsolationReadAt=0L;
    private static int currentSampleIsolationMode(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-sampleIsolationReadAt>1000L){
            sampleIsolationReadAt=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int mode=android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_refraction_sample_test",0);
                    mode=Math.max(0,Math.min(5,mode));
                    if(mode!=sampleIsolationMode){
                        sampleIsolationMode=mode;
                        log("REFRACTION_SAMPLE_ISOLATION_113 mode="+mode+
                            " legend=0-original,1-neutral,2-screen-clamp,3-luma,4-boundary-overlay,5-compress-luma");
                    }
                }
            }catch(Throwable t){log("REFRACTION_SAMPLE_ISOLATION_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return sampleIsolationMode;
    }
    // V1.9.134.1: mode 0 restores real AGSL displacement without the extra luminance sheen.
    // Mode 1 retains the legacy AGSL comparison; mode 2 disables AGSL for diagnosis.
    private static volatile int OPTICAL_AB_133=0;
    private static volatile long OPTICAL_AB_READ_AT=0L;
    private static int opticalAb133(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-OPTICAL_AB_READ_AT<2000L)return OPTICAL_AB_133;
        OPTICAL_AB_READ_AT=now;
        try{
            android.content.Context context=launcherContext;
            if(context==null)return OPTICAL_AB_133;
            int mode=android.provider.Settings.Global.getInt(
                context.getContentResolver(),
                "liquidglass_optical_ab_133",0);
            OPTICAL_AB_133=Math.max(0,Math.min(2,mode));
        }catch(Throwable ignored){}
        return OPTICAL_AB_133;
    }
    // V1.9.167: independent displacement/compositing diagnostic. No changes in mode 0.
    private static volatile int fixedOutput167=0;
    private static volatile long fixedRead167=0L;
    private static int fixedOutput167(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-fixedRead167>500L){
            fixedRead167=now;
            try{android.content.Context c=launcherContext;
                if(c!=null)fixedOutput167=Math.max(0,Math.min(2,android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_fixed_output_167",0)));
            }catch(Throwable t){log("FIXED_MODE_READ_167 error="+t.getClass().getSimpleName());}
        }
        return fixedOutput167;
    }
    private static int fixedLogs167=0;
    private static int fixedLastMode167=-1;
    private static long fixedLogAt167=0L;
    private static void traceFixed167(View view,android.graphics.Canvas canvas,int mode,float x,float y){
        long now=android.os.SystemClock.uptimeMillis();
        if(mode!=fixedLastMode167){fixedLastMode167=mode;log("FIXED_MODE_167 mode="+mode+" legend=0-normal,1-constant-translucent,2-constant-opaque");}
        if(fixedLogs167>=360 || now-fixedLogAt167<85L)return;
        fixedLogAt167=now;fixedLogs167++;
        StringBuilder chain=new StringBuilder();
        View cur=view;
        for(int i=0;i<9 && cur!=null;i++){
            String name=cur.getClass().getSimpleName();
            if(i>0)chain.append('>');
            chain.append(name).append(':').append(cur.getScrollX()).append(',').append(cur.getScrollY());
            android.view.ViewParent parent=cur.getParent();
            cur=parent instanceof View?(View)parent:null;
        }
        int[] loc=new int[2];view.getLocationOnScreen(loc);
        log("FIXED_DRAW_167 mode="+mode+" view="+System.identityHashCode(view)+
            " uptime="+now+" xy="+x+","+y+" screen="+loc[0]+","+loc[1]+
            " canvasHardware="+canvas.isHardwareAccelerated()+" viewAlpha="+view.getAlpha()+
            " chain="+chain);
    }
    // V1.9.168: read live Settings.Global to avoid requiring a Launcher restart.
    private static int REFRACTION_BLUR_LOGS_168=0;
    private static int refractionBlurIsolation168(){
        try{
            android.content.Context ctx=launcherContext;
            if(ctx!=null)return Math.max(0,Math.min(1,android.provider.Settings.Global.getInt(
                ctx.getContentResolver(),"liquidglass_refraction_blur_isolation_168",0)));
        }catch(Throwable ignored){}
        return 0;
    }

    // V1.9.171: isolate only the AGSL Paint alpha, keeping Shader, blur, tint,
    // geometry and original draw order unchanged. 0=255, 1=128, 2=64, 3=0.
    private static int agslPaintAlphaMode171(){
        try{
            android.content.Context ctx=launcherContext;
            if(ctx!=null)return Math.max(0,Math.min(3,android.provider.Settings.Global.getInt(
                ctx.getContentResolver(),"liquidglass_agsl_paint_alpha_171",0)));
        }catch(Throwable ignored){}
        return 0;
    }
    private static final int[] AGSL_ALPHA_LOG_COUNTS_171=new int[4];
    private static void traceAgslAlpha171(View view,Canvas canvas,int mode,Paint paint){
        synchronized(AGSL_ALPHA_LOG_COUNTS_171){
            if(AGSL_ALPHA_LOG_COUNTS_171[mode]++>=96)return;
        }
        StringBuilder parents=new StringBuilder();
        android.view.ViewParent par=view.getParent();
        for(int i=0;i<5 && par instanceof View;i++){
            View v=(View)par;
            if(i>0)parents.append('>');
            parents.append(v.getClass().getSimpleName()).append("(a=").append(v.getAlpha())
                .append(",layer=").append(v.getLayerType()).append(",sx=").append(v.getScaleX())
                .append(",tx=").append(v.getTranslationX()).append(",scroll=").append(v.getScrollX()).append(')');
            par=v.getParent();
        }
        log("AGSL_ALPHA_CHAIN_171 mode="+mode+" paintAlpha="+paint.getAlpha()+
            " xfer="+(paint.getXfermode()==null?"default":paint.getXfermode().getClass().getSimpleName())+
            " colorFilter="+(paint.getColorFilter()==null?"null":paint.getColorFilter().getClass().getSimpleName())+
            " viewAlpha="+view.getAlpha()+" hardware="+canvas.isHardwareAccelerated()+
            " parents="+parents);
    }

    // V1.9.170: compositing order experiment. 0=legacy AGSL->blur;
    // 1=opaque blur foundation->AGSL. This keeps actual AGSL pixels visible.
    private static int refractionComposite170(){
        try{
            android.content.Context ctx=launcherContext;
            if(ctx!=null)return Math.max(0,Math.min(1,android.provider.Settings.Global.getInt(
                ctx.getContentResolver(),"liquidglass_refraction_composite_170",0)));
        }catch(Throwable ignored){}
        return 0;
    }
    private static final int[] REFRACTION_COMPOSITE_LOGS_170=new int[2];
    private static void logComposite170(int mode,String stage,int alpha){
        synchronized(REFRACTION_COMPOSITE_LOGS_170){
            if(REFRACTION_COMPOSITE_LOGS_170[mode]++<100)
                log("REFRACTION_COMPOSITE_170 mode="+mode+" stage="+stage+" blurAlpha="+alpha);
        }
    }

    // V1.9.169: isolate the real-refraction switch without changing other settings.
    // 0=baseline; 1=skip AGSL while preserving refraction-active blur alpha;
    // 2=AGSL only, omit custom blur/tint/surface material (edge pass unchanged).
    private static final int[] REFRACTION_PATH_COUNTS_169=new int[3];
    private static int refractionPathMode169(){
        try{
            android.content.Context ctx=launcherContext;
            if(ctx!=null)return Math.max(0,Math.min(2,android.provider.Settings.Global.getInt(
                ctx.getContentResolver(),"liquidglass_refraction_path_169",0)));
        }catch(Throwable ignored){}
        return 0;
    }
    private static void logRefractionPath169(int mode,String stage,boolean enabled){
        synchronized(REFRACTION_PATH_COUNTS_169){
            if(REFRACTION_PATH_COUNTS_169[mode]++<120)
                log("REFRACTION_PATH_169 mode="+mode+" stage="+stage+
                    " master="+enabled+" count="+REFRACTION_PATH_COUNTS_169[mode]);
        }
    }
    // V1.9.175: isolated AGSL compositing experiment. Does not mutate Launcher parents.
    // 0=direct, 1=tight offscreen, 2=full view offscreen, 3=tight offscreen/half restore alpha.
    private static volatile int layerExperiment175=0;
    private static volatile long layerExperimentRead175=0L;
    private static int layerExperiment175(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-layerExperimentRead175>350L){
            layerExperimentRead175=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)layerExperiment175=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_layer_composite_175",0)));
            }catch(Throwable ignored){}
        }
        return layerExperiment175;
    }
    private static final int[] LAYER_LOG_COUNTS_175=new int[4];
    private static void traceLayer175(int mode,View view,Canvas canvas,int paintAlpha){
        if(LAYER_LOG_COUNTS_175[mode]++>=140)return;
        StringBuilder b=new StringBuilder("LAYER_COMPOSITE_175 mode=").append(mode)
            .append(" id=").append(System.identityHashCode(view))
            .append(" paintAlpha=").append(paintAlpha)
            .append(" hardware=").append(canvas.isHardwareAccelerated());
        for(android.view.ViewParent p=view.getParent();p instanceof View;p=((View)p).getParent()){
            View v=(View)p;
            String name=v.getClass().getSimpleName();
            if(name.contains("Workspace")||name.contains("CellLayout")||name.contains("DragLayer"))
                b.append(" parent=").append(name).append(':').append(v.getLayerType())
                    .append(':').append(v.getAlpha()).append(':').append(v.getScrollX());
        }
        log(b.toString());
    }
    // V1.9.173: isolate wallpaper sampling from motion-time layer compositing.
    // 0=original; 1=constant RGB with original AGSL alpha; 2=no optical displacement;
    // 3=constant RGB + no displacement. These are diagnostic modes only.
    private static volatile int motionIsolation173=0;
    private static volatile long motionIsolationRead173=0L;
    private static int motionIsolation173(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-motionIsolationRead173>400L){
            motionIsolationRead173=now;
            try{android.content.Context c=launcherContext;
                if(c!=null)motionIsolation173=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_motion_composite_173",0)));
            }catch(Throwable t){log("MOTION_COMPOSITE_173_READ_ERROR "+t.getClass().getSimpleName());}
        }
        return motionIsolation173;
    }
    private static int motionIsolationLast173=-1;
    private static int motionIsolationLogs173=0;
    private static long motionIsolationAt173=0L;
    private static void traceMotionIsolation173(View v,int mode,float x,float y,int frameId){
        long now=android.os.SystemClock.uptimeMillis();
        if(mode!=motionIsolationLast173){motionIsolationLast173=mode;
            log("MOTION_COMPOSITE_173_MODE mode="+mode+" legend=0-original,1-flatRGB-originalAlpha,2-noDisplacement,3-flatRGB-noDisplacement");}
        if(motionIsolationLogs173>=480 || now-motionIsolationAt173<35L)return;
        motionIsolationAt173=now;motionIsolationLogs173++;
        int[] xy=new int[2];v.getLocationOnScreen(xy);
        StringBuilder parents=new StringBuilder();android.view.ViewParent p=v.getParent();
        for(int i=0;i<7 && p instanceof View;i++){
            View parent=(View)p;
            if(i>0)parents.append('>');
            parents.append(parent.getClass().getSimpleName()).append(':')
                .append(parent.getScrollX()).append(',').append(parent.getLayerType())
                .append(',').append(parent.getAlpha());
            p=parent.getParent();
        }
        log("MOTION_COMPOSITE_173 mode="+mode+" id="+System.identityHashCode(v)+
            " t="+now+" screen="+xy[0]+","+xy[1]+" sample="+x+","+y+
            " frame="+frameId+" parents="+parents);
    }

    // V1.9.174: per-folder parent-composition transition audit. Observation only:
    // never force parent alpha or layer type (would alter Launcher animations).
    private static final class ParentSnapshot174 {
        long time; float screenX, screenY; int workspaceScroll, dragLayer, cellLayer;
        float dragAlpha, cellAlpha, folderAlpha; boolean initialized;
    }
    private static final java.util.WeakHashMap<View,ParentSnapshot174> PARENT_SNAPSHOTS_174 = new java.util.WeakHashMap<>();
    private static int parentTraceCount174=0;
    private static void traceParentComposite174(View folder, Canvas canvas, float sampleX,float sampleY,
                                                  int paintAlpha,int frameId){
        if(parentTraceCount174>=900)return;
        try{
            long now=android.os.SystemClock.uptimeMillis();
            int[] xy=new int[2];folder.getLocationOnScreen(xy);
            View workspace=null,drag=null,cell=null;
            android.view.ViewParent node=folder.getParent();
            for(int i=0;i<15 && node instanceof View;i++){
                View v=(View)node;String name=v.getClass().getSimpleName();
                if(workspace==null && name.contains("Workspace"))workspace=v;
                if(drag==null && name.contains("DragLayer"))drag=v;
                if(cell==null && name.contains("CellLayout"))cell=v;
                node=v.getParent();
            }
            int ws=workspace==null?Integer.MIN_VALUE:workspace.getScrollX();
            int dl=drag==null?-1:drag.getLayerType(),cl=cell==null?-1:cell.getLayerType();
            float da=drag==null?Float.NaN:drag.getAlpha(),ca=cell==null?Float.NaN:cell.getAlpha();
            ParentSnapshot174 previous=PARENT_SNAPSHOTS_174.get(folder);
            if(previous==null){previous=new ParentSnapshot174();PARENT_SNAPSHOTS_174.put(folder,previous);}
            float dx=previous.initialized?xy[0]-previous.screenX:0f;
            float dy=previous.initialized?xy[1]-previous.screenY:0f;
            int scrollDelta=previous.initialized && ws!=Integer.MIN_VALUE && previous.workspaceScroll!=Integer.MIN_VALUE?ws-previous.workspaceScroll:0;
            boolean changed=previous.initialized && (dl!=previous.dragLayer || cl!=previous.cellLayer ||
                Math.abs(da-previous.dragAlpha)>0.005f || Math.abs(ca-previous.cellAlpha)>0.005f ||
                Math.abs(folder.getAlpha()-previous.folderAlpha)>0.005f);
            boolean moved=Math.abs(dx)>0.5f || Math.abs(dy)>0.5f || scrollDelta!=0;
            // Log at most once per 25ms per folder, but always log parent-state transitions.
            boolean shouldLog=!previous.initialized || changed || ((moved || now-previous.time>350L) && now-previous.time>=25L);
            if(shouldLog && parentTraceCount174++<900){
                log("PARENT_COMPOSITE_174 id="+System.identityHashCode(folder)+" t="+now+
                    " screen="+xy[0]+","+xy[1]+" delta="+dx+","+dy+
                    " workspaceScroll="+ws+" scrollDelta="+scrollDelta+
                    " dragLayer="+dl+" dragAlpha="+da+
                    " cellLayer="+cl+" cellAlpha="+ca+
                    " folderAlpha="+folder.getAlpha()+" changed="+changed+" moved="+moved+
                    " paintAlpha="+paintAlpha+" sample="+sampleX+","+sampleY+
                    " frame="+frameId+" hardware="+canvas.isHardwareAccelerated());
            }
            previous.initialized=true;previous.time=now;
            previous.screenX=xy[0];previous.screenY=xy[1];previous.workspaceScroll=ws;
            previous.dragLayer=dl;previous.cellLayer=cl;previous.dragAlpha=da;
            previous.cellAlpha=ca;previous.folderAlpha=folder.getAlpha();
        }catch(Throwable e){if(parentTraceCount174++<900)log("PARENT_COMPOSITE_174_ERROR "+e.getClass().getSimpleName());}
    }

    // V1.9.177: separate AGSL opacity from the pixels beneath the glass.
    // 0=baseline; 1=opaque AGSL; 2=opaque local underlay + original AGSL;
    // 3=same underlay and AGSL grouped in a local saveLayer.
    private static volatile int alphaUnderlay177=0;
    private static volatile long alphaUnderlayRead177=0L;
    private static int alphaUnderlayMode177(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-alphaUnderlayRead177>350L){
            alphaUnderlayRead177=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)alphaUnderlay177=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_alpha_underlay_177",0)));
            }catch(Throwable ignored){}
        }
        return alphaUnderlay177;
    }
    private static final int[] ALPHA_UNDERLAY_LOGS_177=new int[4];
    private static void traceAlphaUnderlay177(int mode,View view,Canvas canvas,int paintAlpha){
        if(ALPHA_UNDERLAY_LOGS_177[mode]++>=120)return;
        log("ALPHA_UNDERLAY_177 mode="+mode+" view="+System.identityHashCode(view)+
            " paintAlpha="+paintAlpha+" hardware="+canvas.isHardwareAccelerated());
    }
    // V1.9.178: independent shader-output alpha threshold experiment.
    private static volatile int shaderAlphaMode178=0;
    private static volatile long shaderAlphaRead178=0L;
    private static int shaderAlphaMode178(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-shaderAlphaRead178>350L){
            shaderAlphaRead178=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)shaderAlphaMode178=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_shader_alpha_178",0)));
            }catch(Throwable ignored){}
        }
        return shaderAlphaMode178;
    }
    private static final int[] SHADER_ALPHA_LOGS_178=new int[4];
    private static void traceShaderAlpha178(int mode,View view,int paintAlpha){
        if(SHADER_ALPHA_LOGS_178[mode]++>=120)return;
        log("SHADER_ALPHA_178 mode="+mode+" shaderAlpha="+(mode==0?"original":mode==1?"0.50":mode==2?"0.85":"1.00")+
            " view="+System.identityHashCode(view)+" paintAlpha="+paintAlpha);
    }
    // V1.9.184: RGB-only directional specular reflection; opaque refraction remains stable.
    private static volatile int specularMode184=0;
    private static volatile long specularRead184=0L;
    private static int specularMode184(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-specularRead184>350L){
            specularRead184=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)specularMode184=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_specular_185",0)));
            }catch(Throwable ignored){}
        }
        return specularMode184;
    }
    // V189: 0 legacy in-AGSL stripe; 1 post-blur crisp stripe;
    // 2 post-blur crisp RED diagnostic stripe. Does not change background blur.
    private static volatile int surfacePass189=0;
    private static volatile long surfacePassRead189=0L;
    private static int surfacePass189(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-surfacePassRead189>350L){
            surfacePassRead189=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)surfacePass189=Math.max(0,Math.min(2,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_surface_pass_189",0)));
            }catch(Throwable ignored){}
        }
        return surfacePass189;
    }
    // V190: post-blur rounded surface optics. 0=V189, 1=static rim,
    // 2=directional rim, 3=directional rim + subtle inner shadow.
    private static volatile int rimMode190=0;
    private static volatile long rimModeRead190=0L;
    private static int rimMode190(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-rimModeRead190>350L){
            rimModeRead190=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)rimMode190=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_rim_mode_190",0)));
            }catch(Throwable ignored){}
        }
        return rimMode190;
    }
    // V1.9.192: staged replacement of the legacy soft highlight / optical contour.
    // 0 = unchanged V191 behavior; 1 = legacy native highlight only;
    // 2 = new post-blur surface rim only; 3 = both (A/B overlap diagnostic).
    // The wallpaper refraction, blur, tint and motion scheduler are unchanged.
    // V1.9.193: ship the successfully tested new-only surface path as default.
    // 0=honor V192 setting (rollback), 1=new-only (default),
    // 2=legacy-only A/B, 3=both A/B. This only selects existing renderers.
    private static volatile int surfaceDefault193=1;
    private static volatile long surfaceDefaultRead193=0L;
    private static int surfaceDefault193(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-surfaceDefaultRead193>=350L){
            surfaceDefaultRead193=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)surfaceDefault193=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_surface_default_193",1)));
            }catch(Throwable ignored){}
        }
        return surfaceDefault193;
    }
    // V1.9.194: single authoritative surface-optics selector.
    // 0=off, 1=static rounded reflection, 2=dynamic reflection,
    // 3=dynamic reflection + inner depth (default).
    // Legacy experiment switches are only used when explicit rollback=1.
    private static volatile int surfaceMode194=3;
    private static volatile int surfaceStrength194=100;
    private static volatile int surfaceSoftness194=100;
    private static volatile int surfaceRollback194=0;
    private static volatile long surfaceRead194=0L;
    private static void readSurface194(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-surfaceRead194<350L)return;
        surfaceRead194=now;
        try{
            android.content.Context c=launcherContext;
            if(c==null)return;
            android.content.ContentResolver cr=c.getContentResolver();
            // V195: mode, strength and softness are supplied by GlassConfigProvider
            // from the actual APK UI; Settings.Global no longer overrides them.
            surfaceRollback194=Math.max(0,Math.min(1,android.provider.Settings.Global.getInt(cr,"liquidglass_surface_rollback_194",0)));
        }catch(Throwable ignored){}
    }
    private static boolean surface194Enabled(){readSurface194();return surfaceRollback194==0;}
    private static int surfaceMode194(){readSurface194();return surfaceMode194;}
    private static int surfaceStrength194(){readSurface194();return surfaceStrength194;}
    private static int surfaceSoftness194(){readSurface194();return surfaceSoftness194;}
    private static int surface194Logs=0;
    private static int effectiveUnified193(){
        if(surface194Enabled())return 2; // one surface renderer; never double legacy highlight
        int selected=surfaceDefault193();
        return selected==0?unifiedSurface192():(selected==1?2:(selected==2?1:3));
    }
    private static int surfaceDefaultLogs193=0;
    private static volatile int unifiedSurface192=0;
    private static volatile long unifiedSurfaceRead192=0L;
    private static int unifiedSurface192(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-unifiedSurfaceRead192>=350L){
            unifiedSurfaceRead192=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)unifiedSurface192=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_surface_unified_192",0)));
            }catch(Throwable ignored){}
        }
        return unifiedSurface192;
    }
    private static int effectiveRim192(){
        if(surface194Enabled())return surfaceMode194();
        int mode=effectiveUnified193();
        if(mode==1)return 0; // legacy only
        if(mode==2 || mode==3){
            int rim=rimMode190();
            return rim==0?3:rim; // default to dynamic rim + subtle inner shadow
        }
        return rimMode190(); // original V191 baseline
    }
    // V198: independent post-blur surface motion. Do not rely on the AGSL/refraction branch.
    private static float updateSurfaceMotion198(View view, DynamicRefraction state, int mode){
        if(mode<2){state.visualDirection188=0f;return 0f;}
        updateMotion183(view,false);
        float target;
        synchronized(MOTION_183){
            long age= MOTION_183.lastMovementNs==0L?Long.MAX_VALUE:
                Math.max(0L,android.os.SystemClock.elapsedRealtimeNanos()-MOTION_183.lastMovementNs);
            float ageMs=age==Long.MAX_VALUE?100000f:age/1000000f;
            float decay=ageMs<=120f?1f:(float)Math.exp(-Math.min(2000f,ageMs-120f)/180f);
            if(ageMs>=650f)decay=0f;
            target=Math.max(-1f,Math.min(1f,MOTION_183.signed/900f))*decay;
        }
        long now=android.os.SystemClock.elapsedRealtimeNanos();
        float dt=state.visualUpdatedNs188==0L?16f:Math.max(0f,Math.min(120f,(now-state.visualUpdatedNs188)/1000000f));
        float tau=Math.abs(target)>Math.abs(state.visualDirection188)?85f:180f;
        state.visualDirection188+=(1f-(float)Math.exp(-dt/tau))*(target-state.visualDirection188);
        if(Math.abs(state.visualDirection188)<0.004f && Math.abs(target)<0.004f)state.visualDirection188=0f;
        state.visualUpdatedNs188=now;
        state.recenterPending187=Math.abs(state.visualDirection188)>0.005f;
        if(state.recenterPending187)scheduleRecenter188(view,state);
        return state.visualDirection188;
    }
    private static int surfaceUnifiedLogs192=0;
    private static int rimLogs190=0;
    private static void drawRoundedRim190(Canvas canvas,android.graphics.RectF bounds,
            float cornerRadius,float direction,int mode){
        if(mode==0)return;
        float w=bounds.width(),h=bounds.height();
        if(w<4f || h<4f)return;
        // V198: physical-pixel edge thickness is bounded by density, not folder size.
        // Large folders must not inherit a proportionally heavier double outline.
        float density=launcherContext==null?1f:launcherContext.getResources().getDisplayMetrics().density;
        float softness=surface194Enabled()?surfaceSoftness194()/100f:1f;
        final float edge=Math.max(0.65f*density,Math.min(1.45f*density,1.05f*density*softness));
        final float inset=edge*0.72f;
        android.graphics.RectF r=new android.graphics.RectF(bounds);
        r.inset(inset,inset);
        float radius=Math.max(0f,Math.min(Math.min(r.width(),r.height())*0.5f,cornerRadius-inset));
        float d=mode==1?0f:Math.max(-1f,Math.min(1f,direction));
        // Reflection travels along the rounded contour rather than across the glass face.
        // The opposing side stays faint; the entire outline never pulses in unison.
        float center=r.centerX()+d*r.width()*0.26f;
        float spread=Math.max(1f,r.width()*0.62f);
        int save=canvas.save();
        try{
            android.graphics.Path clip=new android.graphics.Path();
            clip.addRoundRect(bounds,cornerRadius,cornerRadius,android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(edge);
            p.setStrokeJoin(Paint.Join.ROUND);
            int strength=surface194Enabled()?surfaceStrength194():100;
            int faint=Math.min(255,Math.round(24f*strength/100f));
            int bright=Math.min(255,Math.round(200f*strength/100f));
            p.setShader(new android.graphics.LinearGradient(center-spread,r.top,center+spread,r.top,
                new int[]{(faint<<24)|0xFFFFFF,(bright<<24)|0xFFFFFF,(faint<<24)|0xFFFFFF},
                new float[]{0f,0.5f,1f},android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r,radius,radius,p);
            // V1.9.191: Do not draw a full-bounds ellipse arc here. It crosses
            // the glass interior. The rounded-rectangle contour above is the
            // only visible bright rim; its gradient already follows motion.
            if(mode==3){
                android.graphics.RectF inner=new android.graphics.RectF(bounds);
                float offset=edge*1.55f;
                inner.inset(offset,offset);
                float innerRadius=Math.max(0f,cornerRadius-offset);
                Paint shadow=new Paint(Paint.ANTI_ALIAS_FLAG);
                shadow.setStyle(Paint.Style.STROKE);
                shadow.setStrokeWidth(Math.max(0.55f*density,edge*0.42f));
                int dark=surface194Enabled()?Math.min(255,Math.round(25f*surfaceStrength194()/100f)):39;
                shadow.setColor(dark<<24);
                canvas.drawRoundRect(inner,innerRadius,innerRadius,shadow);
            }
        }finally{canvas.restoreToCount(save);}
    }
    private static int surfaceLogs189=0;
    // Paint on the FINAL local Canvas, after both AGSL and the custom bitmap blur.
    // Its edge softness is in pixels and never derived from customBlurDp.
    private static void drawSurfaceStripe189(Canvas canvas,android.graphics.RectF rect,
            float cornerRadius,float direction,int specularMode,int pass){
        if(specularMode==0 || pass==0)return;
        final float w=rect.width(),h=rect.height();
        if(w<=0f || h<=0f)return;
        float center=0.5f+(specularMode==3?0.30f*Math.max(-1f,Math.min(1f,direction)):0f);
        float stripeHalf=Math.max(1f,w*0.055f);
        float bandTop=rect.top+h*0.05f;
        float bandBottom=rect.top+h*0.51f;
        float cx=rect.left+w*center;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pass==2?Color.rgb(255,25,25):Color.WHITE);
        paint.setAlpha(215);
        paint.setShader(new android.graphics.LinearGradient(cx-stripeHalf,0f,cx+stripeHalf,0f,
            new int[]{0x00FFFFFF,pass==2?0xEEFF1919:0xEEFFFFFF,0x00FFFFFF},
            new float[]{0f,0.5f,1f},android.graphics.Shader.TileMode.CLAMP));
        int save=canvas.save();
        try{
            android.graphics.Path clip=new android.graphics.Path();
            clip.addRoundRect(rect,cornerRadius,cornerRadius,android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawRoundRect(new android.graphics.RectF(cx-stripeHalf,bandTop,cx+stripeHalf,bandBottom),
                Math.min(stripeHalf,8f),Math.min(stripeHalf,8f),paint);
        }finally{canvas.restoreToCount(save);}
    }
    private static int specularLogs184=0;
    // V1.9.183: one shared Workspace motion clock for all glass views.
    // Diagnostic only: the stable opaque AGSL output is untouched.
    private static final MotionState183 MOTION_183 = new MotionState183();
    private static int motionLogs183=0;
    private static final class MotionState183 {
        long timeNs, lastMovementNs;
        float x, scroll, raw, filtered, signed, highlight;
        boolean scheduled, active;
        int ticks, settleTicks;
        java.lang.ref.WeakReference<View> owner;
    }
    private static void scheduleMotion183(final View view){
        final MotionState183 st=MOTION_183;
        if(st.scheduled || !view.isAttachedToWindow())return;
        st.scheduled=true;
        final java.lang.ref.WeakReference<View> weak=new java.lang.ref.WeakReference<>(view);
        view.postOnAnimation(new Runnable(){
            @Override public void run(){
                View v=weak.get();
                synchronized(MOTION_183){
                    st.scheduled=false;
                    if(v==null || !v.isAttachedToWindow()){
                        st.owner=null;st.active=false;st.filtered=0f;st.signed=0f;st.highlight=0f;st.lastMovementNs=0L;
                        if(motionLogs183++<120)log("MOTION_SETTLED_183 reason=detached");
                        return;
                    }
                }
                updateMotion183(v,true);
                // The Shader uniform is refreshed by the next draw. Redraw only while active.
                if((specularMode184()!=0 || effectiveRim192()>=2) && v.isAttachedToWindow())v.invalidate();
            }
        });
    }
    private static void updateMotion183(View view,boolean fromFrame){
        if(view==null || !view.isAttachedToWindow())return;
        try{
            final long now=android.os.SystemClock.elapsedRealtimeNanos();
            // V200: reject redundant motion probes before allocating coordinate arrays
            // or querying the Workspace. This does not change motion filtering.
            synchronized(MOTION_183){
                MotionState183 st=MOTION_183;
                View owner=st.owner==null?null:st.owner.get();
                if(fromFrame && owner!=null && owner!=view)return;
                // A single owner controls frame callbacks. Other views consume the shared state.
                if(!fromFrame && owner!=null && owner.isAttachedToWindow() && st.scheduled)return;
                final int[] workspace=workspaceOffset163();
                final int[] p=new int[2];view.getLocationOnScreen(p);
                if(owner==null || !owner.isAttachedToWindow()){
                    st.owner=new java.lang.ref.WeakReference<>(view);
                    st.timeNs=0L;st.lastMovementNs=0L;st.filtered=0f;st.signed=0f;st.highlight=0f;
                }
                float x=p[0], scroll=workspace[0];
                float dtMs=st.timeNs==0L?0f:Math.max(0f,(now-st.timeNs)/1000000f);
                float signedDx=0f;
                boolean discontinuity=false;
                if(dtMs>=4f){
                    // Workspace scroll is common to every folder; screen position is fallback.
                    float scrollDx=scroll-st.scroll;
                    float screenDx=x-st.x;
                    signedDx=Math.abs(scrollDx)>0.25f?-scrollDx:screenDx;
                    discontinuity=Math.abs(signedDx)>Math.max(80f,dtMs*2.5f);
                    if(!discontinuity && Math.abs(signedDx)>0.35f)st.lastMovementNs=now;
                    float velocity=discontinuity?0f:Math.min(2500f,Math.abs(signedDx)*1000f/dtMs);
                    float tau=velocity>st.filtered?85f:220f;
                    float blend=1f-(float)Math.exp(-Math.min(dtMs,100f)/tau);
                    st.raw=velocity;
                    st.filtered+=blend*(velocity-st.filtered);
                    float signedTarget=velocity*Math.signum(signedDx);
                    st.signed+=blend*(signedTarget-st.signed);
                    float target=Math.min(1f,st.filtered/950f);
                    float highlightTau=target>st.highlight?120f:220f;
                    st.highlight+=(1f-(float)Math.exp(-Math.min(dtMs,100f)/highlightTau))*(target-st.highlight);
                    st.timeNs=now;st.x=x;st.scroll=scroll;
                }else if(st.timeNs==0L){st.timeNs=now;st.x=x;st.scroll=scroll;}
                boolean nonzero=st.filtered>1f || st.highlight>0.003f || Math.abs(st.signed)>1f;
                if(nonzero){st.active=true;st.settleTicks=0;}
                else if(st.active){
                    if(++st.settleTicks>=3){
                        st.active=false;st.filtered=0f;st.signed=0f;st.highlight=0f;
                        if(motionLogs183<120){motionLogs183++;log("MOTION_SETTLED_183 frames="+st.ticks+" highlight="+st.highlight);}
                    }
                }
                if(st.active)scheduleMotion183(view);
                if(motionLogs183<120 && (st.ticks<55 || st.ticks%5==0 || !st.active)){
                    motionLogs183++;
                    log("MOTION_SIGNAL_183 owner="+System.identityHashCode(view)+" frame="+fromFrame+
                        " dtMs="+dtMs+" screenX="+x+" workspaceScroll="+scroll+
                        " signedDx="+signedDx+" discontinuity="+discontinuity+
                        " velocityPxS="+st.raw+" filteredPxS="+st.filtered+
                        " signedPxS="+st.signed+" highlight="+st.highlight+
                        " active="+st.active+" scheduled="+st.scheduled+
                        " settleTicks="+st.settleTicks);
                }
                st.ticks++;
            }
        }catch(Throwable t){if(motionLogs183++<8)log("MOTION_SIGNAL_183_ERROR "+t.getClass().getSimpleName());}
    }
    // V1.9.179: opaque wallpaper-refraction base with sheen composited in RGB.
    // Modes: 0 stable baseline; 1 subtle sheen; 2 stronger sheen; 3 no added sheen.
    private static volatile int stableSheenMode179=0;
    private static volatile long stableSheenRead179=0L;
    private static int stableSheenMode179(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-stableSheenRead179>350L){
            stableSheenRead179=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null)stableSheenMode179=Math.max(0,Math.min(3,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_stable_sheen_179",0)));
            }catch(Throwable ignored){}
        }
        return stableSheenMode179;
    }
    private static final int[] STABLE_SHEEN_LOGS_179=new int[4];
    private static void traceStableSheen179(int mode,View view){
        if(STABLE_SHEEN_LOGS_179[mode]++>=80)return;
        log("STABLE_SHEEN_179 mode="+mode+" view="+System.identityHashCode(view)+" shaderAlpha=1.0");
    }
    private static final String REFRACTION_AGSL =
        "uniform shader wallpaper;\n"+
        "uniform shader wallpaperClear201;\n"+
        "uniform float clearDetailMix201;\n"+
        "uniform float4 bounds;\n"+
        "uniform float strength;\n"+
        "uniform float diagnosticMode;\n"+
        "uniform float fixedOutput167;\n"+
        "uniform float alphaUnderlay177;\n"+
        "uniform float shaderAlpha178;\n"+
        "uniform float stableSheen179;\n"+
        "uniform float specularMode184;\n"+
        "uniform float specularLevel184;\n"+
        "uniform float specularDirection184;\n"+
        "uniform float motionIsolation173;\n"+
        "uniform float passId;\n"+
        "uniform float temporalOpacity;\n"+
        "uniform float motionHighlight;\n"+
        "uniform float opticalSheenEnabled;\n"+
        "uniform float stableOpticalMode;\n"+
        "uniform float clearRefraction;\n"+
        "uniform float sampleIsolation;\n"+
        "uniform float compositeProbe157;\n"+
        "uniform float4 screenClip;\n"+
        "uniform float probe143Composite;\n"+
        "half4 main(float2 p) {\n"+
        " float u=clamp((p.x-bounds.x)/max(bounds.z,1.0),0.0,1.0);\n"+
        " float edge=abs(2.0*u-1.0);\n"+
        " float envelope=edge*edge*(3.0-2.0*edge);\n"+
        " float direction=1.0-2.0*smoothstep(0.47,0.53,u);\n"+
        " float displacement173=(motionIsolation173>1.5?0.0:strength);\n"+
        " float2 samplingPoint=p+float2(direction*envelope*displacement173,0.0);\n"+
        " bool outOfDisplay=samplingPoint.x<screenClip.x || samplingPoint.y<screenClip.y || samplingPoint.x>screenClip.z || samplingPoint.y>screenClip.w;\n"+
        " if(sampleIsolation>1.5 && sampleIsolation<2.5){samplingPoint=clamp(samplingPoint,screenClip.xy,screenClip.zw); }\n"+
        " half4 color=wallpaper.eval(samplingPoint);\n"+
        // V201: blend only texture detail, never sampling displacement or output alpha.
        " if(clearDetailMix201>0.001){half4 crisp=wallpaperClear201.eval(samplingPoint);color.rgb=mix(color.rgb,crisp.rgb,half(clamp(clearDetailMix201,0.0,1.0)));}\n"+
        " if(fixedOutput167>0.5){return fixedOutput167<1.5?half4(0.50,0.50,0.50,0.18):half4(0.50,0.50,0.50,1.0); }\n"+
        " if(sampleIsolation>0.5 && sampleIsolation<1.5){return half4(0.50,0.50,0.50,0.65); }\n"+
        " if(sampleIsolation>3.5 && sampleIsolation<4.5 && outOfDisplay){return half4(1.0,0.48,0.02,0.93); }\n"+
        " if(sampleIsolation>2.5 && sampleIsolation<3.5){float lum=dot(float3(color.rgb),float3(0.2126,0.7152,0.0722));return half4(half3(lum),half(0.65)); }\n"+
        " if(sampleIsolation>4.5 && sampleIsolation<5.5){float lum=dot(float3(color.rgb),float3(0.2126,0.7152,0.0722));float3 toned=clamp(float3(color.rgb)+(0.5-lum)*0.85,0.0,1.0);return half4(half3(toned),half(0.65)); }\n"+
        " if(diagnosticMode>0.5 && diagnosticMode<1.5){\n"+
        "  float stripe=step(0.5,fract((p.x+p.y)*0.055));\n"+
        "  return passId>1.5 ? half4(0.05,0.95,0.20,0.92) : half4(0.95,0.08+0.20*stripe,0.12,0.92);\n"+
        " }\n"+
        // Mode 2 retains the exact wallpaper sample, with a separately animated
        // additive sheen. No smoothing or interpolation of sample coordinates.
        " if(diagnosticMode>1.5){\n"+
        "  float edgeSheen=pow(clamp(abs(2.0*u-1.0),0.0,1.0),3.0);\n"+
        // V1.9.129: ambient light follows the *current sampled wallpaper*
        // even at rest. Motion adds only a small bounded enhancement.
        "  float lum=dot(float3(color.rgb),float3(0.2126,0.7152,0.0722));\n"+
        "  float light=smoothstep(0.58,0.96,lum);\n"+
        "  float ambient=(0.035+0.090*light)*(0.28+0.72*edgeSheen)*opticalSheenEnabled;\n"+
        "  float motion=0.0; // No touch-triggered luminance jumps\n"+
        "  if(compositeProbe157>0.5 && compositeProbe157<1.5){ambient=0.0;}\n"+
        "  if(probe143Composite<0.5){color.rgb=mix(color.rgb,half3(0.97,0.98,1.0),half(min(ambient+motion,0.125)));}\n"+
        "  if(stableOpticalMode>0.5){ color.rgb=min(color.rgb,half3(0.92,0.92,0.92)); }\n"+
        "  float strengthFactor=clamp(abs(strength)/22.0,0.0,2.5);\n"+
        "  color.a=half(probe143Composite>0.5?0.18:0.15*min(1.0,strengthFactor)*temporalOpacity);\n"+
        "  if(compositeProbe157>1.5 && compositeProbe157<2.5){color.a=half(0.15); }\n"+
        "  if(compositeProbe157>2.5 && compositeProbe157<3.5){color.a=half(0.075); }\n"+
        "  if(motionIsolation173>0.5 && motionIsolation173<1.5 || motionIsolation173>2.5){color.rgb=half3(0.50,0.50,0.50); }\n"+
        "  if(alphaUnderlay177>0.5 && alphaUnderlay177<1.5){color.a=half(1.0); }\n"+
        "  if(shaderAlpha178>0.5){color.a=half(shaderAlpha178<1.5?0.50:(shaderAlpha178<2.5?0.85:1.0));}\n"+
        // Stable RGB-only optical highlight; no translucent shader output or motion switch.
        "  if(stableSheen179>-0.5){\n"+
        "   float rim=pow(clamp(abs(2.0*u-1.0),0.0,1.0),3.0);\n"+
        "   float sheenWeight=(stableSheen179<0.5?0.0:(stableSheen179<1.5?0.035:(stableSheen179<2.5?0.065:0.0)));\n"+
        "   float sheen=clamp(sheenWeight*(0.30+0.70*rim),0.0,0.09);\n"+
        "   color.rgb=mix(color.rgb,half3(0.97,0.98,1.0),half(sheen));\n"+
        "   // V185: static visibility diagnostics, unaffected by motion for modes 1 and 2.\n"+
        "   if(specularMode184>0.5){\n"+
        "    float2 uv=float2(u,clamp((p.y-bounds.y)/max(bounds.w,1.0),0.0,1.0));\n"+
        "    float d=clamp(specularDirection184,-1.0,1.0);\n"+
        "    float stripeCenter=specularMode184>2.5?0.50+0.30*d:0.50;\n"+
        "    float stripe=1.0-smoothstep(0.045,0.065,abs(uv.x-stripeCenter));\n"+
        "    float band=1.0-smoothstep(0.15,0.23,abs(uv.y-0.28));\n"+
        "    float mask=stripe*band;\n"+
        "    float gain=specularMode184>2.5?0.95:0.92;\n"+
        "    half3 target=specularMode184>1.5 && specularMode184<2.5?half3(1.0,0.02,0.02):half3(1.0,1.0,1.0);\n"+
        "    color.rgb=mix(color.rgb,target,half(mask*gain));\n"+
        "   }\n"+
        "   color.a=half(1.0);\n"+
        "  }\n"+

        "  return color;\n"+
        " }\n"+
        // Keep optical displacement separate from the overlay opacity. The old
        // 0.439 edge alpha washed out both sides of the glass, especially on
        // bright wallpapers. Attenuate overlay opacity with sampled luminance.
        " float luminance=dot(float3(color.rgb),float3(0.2126,0.7152,0.0722));\n"+
        // V1.9.81: near-white wallpaper requires contrast compensation, not
        // just transparent refraction (the OEM blur underneath can be white).
        // Continuous luminance response prevents abrupt changes while swiping.
        " float bright=smoothstep(0.48,0.96,luminance);\n"+
        // A persistent low-opacity refracted sample across the entire glass,
        // plus a stronger but bounded optical edge. Never depend on motion.
        " float edgeWeight=envelope*envelope;\n"+
        " float midOpacity=0.115*(1.0-0.55*bright);\n"+
        " float edgeOpacity=0.115*edgeWeight*(1.0-0.75*bright);\n"+
        " float neutralOpacity=0.055*bright;\n"+
        " // No forced dark tint on bright wallpaper; preserve source colors.\n"+
        " float softSheen=0.0; // V1.9.136: no luminance-triggered overlay sheen\n"+
        " if(probe143Composite<0.5){color.rgb=mix(color.rgb,half3(0.96,0.97,1.0),half(softSheen));}\n"+
        " float frostedAlpha=(0.045+0.080*edgeWeight);\n"+
        " float clearAlpha=(0.18+0.08*edgeWeight);\n"+
        " color.a=half(probe143Composite>0.5?0.18:frostedAlpha*temporalOpacity);\n"+
        " return color;\n"+
        "}\n";
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    // V1.9.126: expensive blur work never runs on Launcher's drawing thread.
    // One worker, one in-flight request, cached results. Old bitmaps are not
    // recycled during a frame because the RenderThread may still reference them.
    private static final java.util.concurrent.ExecutorService BLUR_WORKER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t=new Thread(r,"LiquidGlass-Blur126");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
        });
    private static volatile boolean BLUR_PENDING=false;
    private static int blurMissLogs141=0;
    private static int blurHitLogs142=0;
    private static android.graphics.Bitmap blurredWallpaper(final android.graphics.Bitmap src,final float density,final View view){
        final int requested=customBlurDp;
        if(requested<=0 || customBlurMode!=1)return null;
        android.graphics.Bitmap cached=CUSTOM_BLUR_FRAME;
        if(CUSTOM_BLUR_SOURCE==src && CUSTOM_BLUR_RADIUS==requested && cached!=null && !cached.isRecycled()){
            if(blurHitLogs142++<16)log("BLUR_CACHE_142 HIT src="+System.identityHashCode(src)+" radius="+requested);
            return cached;
        }
        if(blurMissLogs141<24){
            blurMissLogs141++;
            log("BLUR_CACHE_141 miss src="+System.identityHashCode(src)+
                " cachedSrc="+System.identityHashCode(CUSTOM_BLUR_SOURCE)+
                " radius="+requested+" cachedRadius="+CUSTOM_BLUR_RADIUS+
                " cached="+(cached!=null && !cached.isRecycled())+
                " pending="+BLUR_PENDING);
        }
        synchronized(HookEntry.class){
            if(BLUR_PENDING)return null;
            BLUR_PENDING=true;
        }
        BLUR_WORKER.execute(() -> {
            android.graphics.Bitmap small=null;
            try{
                final int factor=4,w=Math.max(1,src.getWidth()/factor),h=Math.max(1,src.getHeight()/factor);
                small=android.graphics.Bitmap.createScaledBitmap(src,w,h,true);
                int[] a=new int[w*h],b=new int[w*h];small.getPixels(a,0,w,0,0,w,h);
                int r=Math.max(1,Math.min(22,Math.round(requested*density/(factor*1.7f))));
                // O(width*height) sliding-window box filters, rather than
                // O(width*height*radius) nested kernel loops on the UI thread.
                for(int pass=0;pass<3;pass++){
                    for(int y=0;y<h;y++){
                        int rr=0,gg=0,bb=0;
                        for(int k=-r;k<=r;k++){int c=a[y*w+Math.max(0,Math.min(w-1,k))];rr+=(c>>16)&255;gg+=(c>>8)&255;bb+=c&255;}
                        int count=2*r+1;
                        for(int x=0;x<w;x++){
                            b[y*w+x]=0xff000000|((rr/count)<<16)|((gg/count)<<8)|(bb/count);
                            int remove=a[y*w+Math.max(0,x-r)],add=a[y*w+Math.min(w-1,x+r+1)];
                            rr+=((add>>16)&255)-((remove>>16)&255);
                            gg+=((add>>8)&255)-((remove>>8)&255);bb+=(add&255)-(remove&255);
                        }
                    }
                    for(int x=0;x<w;x++){
                        int rr=0,gg=0,bb=0;
                        for(int k=-r;k<=r;k++){int c=b[Math.max(0,Math.min(h-1,k))*w+x];rr+=(c>>16)&255;gg+=(c>>8)&255;bb+=c&255;}
                        int count=2*r+1;
                        for(int y=0;y<h;y++){
                            a[y*w+x]=0xff000000|((rr/count)<<16)|((gg/count)<<8)|(bb/count);
                            int remove=b[Math.max(0,y-r)*w+x],add=b[Math.min(h-1,y+r+1)*w+x];
                            rr+=((add>>16)&255)-((remove>>16)&255);
                            gg+=((add>>8)&255)-((remove>>8)&255);bb+=(add&255)-(remove&255);
                        }
                    }
                }
                android.graphics.Bitmap result=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
                result.setPixels(a,0,w,0,0,w,h);
                if(customBlurDp==requested && customBlurMode==1 && REFRACTION_FRAME==src){
                    CUSTOM_BLUR_FRAME=result;CUSTOM_BLUR_SOURCE=src;CUSTOM_BLUR_RADIUS=requested;
                    // A prewarm request has no originating view. Refresh all tracked
                    // glass nodes on the main thread when the new cache is published.
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        invalidateGlassViews();
                        if(view!=null && view.isAttachedToWindow())view.invalidate();
                        synchronized(DYNAMIC_REFRACTION){
                            for(View tracked:DYNAMIC_REFRACTION.keySet()){
                                if(tracked!=null && tracked.isAttachedToWindow())tracked.invalidate();
                            }
                        }
                    });
                    if(REFRACTION_LOGS++<24)log("GLASS137_BLUR_READY radiusDp="+requested+" texture="+w+"x"+h);
                }else result.recycle();
            }catch(Throwable t){log("GLASS126_BLUR_FAILED "+t.getClass().getSimpleName());}
            finally{
                if(small!=null && !small.isRecycled())small.recycle();
                synchronized(HookEntry.class){BLUR_PENDING=false;}
                // A settings/wallpaper change while the worker was busy must not
                // strand the new request until a later user gesture.
                android.graphics.Bitmap current=REFRACTION_FRAME;
                if(current!=null && !current.isRecycled() && customBlurMode==1 && customBlurDp>0
                    && (CUSTOM_BLUR_SOURCE!=current || CUSTOM_BLUR_RADIUS!=customBlurDp
                        || CUSTOM_BLUR_FRAME==null || CUSTOM_BLUR_FRAME.isRecycled())){
                    blurredWallpaper(current,density,null);
                }
            }
        });
        return null;
    }
    private static final java.util.concurrent.atomic.AtomicInteger GLASS_PIPELINE_DIAGNOSTICS = new java.util.concurrent.atomic.AtomicInteger();
    // V1.9.141: controlled A/B isolation of the touch-related brightness jump.
    // 0 normal; 1 no AGSL (blur/tint remain); 2 AGSL without ambient light;
    // 3 skip AGSL after OEM; 4 only after OEM; 5 only before OEM;
    // 6 freeze sampling transform; 7 fixed AGSL output alpha and no additive sheen.
    private static volatile int opticalProbe141=0;
    private static volatile long opticalProbeReadAt141=0L;
    private static int opticalProbe141(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-opticalProbeReadAt141>800L){
            opticalProbeReadAt141=now;
            android.content.Context c=launcherContext;
            if(c!=null){
                try { opticalProbe141=Math.max(0,Math.min(7,
                    android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_optical_probe_141",0)));
                } catch(Throwable ignored){}
            }
        }
        return opticalProbe141;
    }
    private static int opticalProbeLogged141=-1;
    // V1.9.156: isolated AGSL diagnostics; mode 0 preserves V1.9.155 rendering.
    // 1 = zero displacement, 2 = constant shader sample, 3 = diagnostics only.
    private static volatile int opticalProbe156=0;
    private static volatile long opticalProbeReadAt156=0L;
    private static int opticalProbeLogged156=-1;
    private static int opticalDrawLogs156=0;
    private static int opticalDuplicateLogs156=0;
    private static int opticalProbe156(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-opticalProbeReadAt156>=500L){
            opticalProbeReadAt156=now;
            try{
                int v=android.provider.Settings.Global.getInt(
                    launcherContext.getContentResolver(),"liquidglass_optical_probe_156",0);
                opticalProbe156=(v>=0 && v<=3)?v:0;
            }catch(Throwable ignored){}
        }
        if(opticalProbeLogged156!=opticalProbe156){
            opticalProbeLogged156=opticalProbe156;
            log("OPTICAL_PROBE_156 mode="+opticalProbe156);
        }
        return opticalProbe156;
    }
    // V1.9.144: one AGSL surface submission per FolderIcon dispatchDraw.
    // The OEM callback can run during the same dispatch; do not composite twice.
    // V1.9.176.1: verified dispatch-scoped draw-chain audit. Diagnostic only.
    private static final java.util.concurrent.atomic.AtomicLong DRAW_SEQ_1761 = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger DRAW_LOG_BUDGET_1761 = new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<long[]> DRAW_CONTEXT_1761 = new ThreadLocal<>();
    private static void drawTrace1761(String stage, View v, Canvas c, String detail) {
        if(DRAW_LOG_BUDGET_1761.getAndIncrement()>=1400)return;
        long[] ctx=DRAW_CONTEXT_1761.get();
        String matrix="none", clip="none";
        if(c!=null){
            try{Matrix m=new Matrix();c.getMatrix(m);float[] a=new float[9];m.getValues(a);
                matrix=a[0]+","+a[1]+","+a[2]+","+a[3]+","+a[4]+","+a[5];}catch(Throwable ignored){}
            try{Rect r=new Rect();clip=c.getClipBounds(r)?r.toShortString():"empty";}catch(Throwable ignored){}
        }
        log("DRAW_TRACE_176 stage="+stage+" seq="+(ctx==null?-1:ctx[0])+
            " view="+(v==null?0:System.identityHashCode(v))+
            " canvas="+(c==null?0:System.identityHashCode(c))+
            " attempts="+(ctx==null?-1:ctx[1])+" submits="+(ctx==null?-1:ctx[2])+
            " surface="+(ctx==null?-1:ctx[3])+" edge="+(ctx==null?-1:ctx[4])+
            " hardware="+(c!=null&&c.isHardwareAccelerated())+
            " matrix="+matrix+" clip="+clip+" "+detail);
    }
    private static final ThreadLocal<Boolean> AGSL_SUBMITTED_144 = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SURFACE_SUBMITTED_144 = new ThreadLocal<>();
    private static int agslSkipLogs144=0;
    // V1.9.157: source-level optical compositing A/B. Mode 0 is identical to 156.
    private static volatile int compositeProbe157=0;
    private static volatile long compositeProbeRead157=0L;
    private static int compositeProbeLogged157=-1;
    private static int compositeProbe157(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-compositeProbeRead157>500L){
            compositeProbeRead157=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){int v=android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_composite_probe_157",0);
                    compositeProbe157=(v>=0 && v<=3)?v:0;}
            }catch(Throwable ignored){}
        }
        if(compositeProbeLogged157!=compositeProbe157){
            compositeProbeLogged157=compositeProbe157;
            log("COMPOSITE_PROBE_157 mode="+compositeProbe157);
        }
        return compositeProbe157;
    }
    private static int compositeDrawLogs157=0;
    // V1.9.158: per-dispatch accounting; counters do not change rendering behavior.
    private static final ThreadLocal<int[]> COMPOSITE_COUNTS_158=new ThreadLocal<>();
    private static int compositeTraceLogs158=0;
    // V1.9.159: isolated Canvas compositing A/B; default 0 is bit-for-bit original path.
    private static volatile int canvasComposite159=0;
    private static volatile long canvasCompositeRead159=0L;
    private static int canvasCompositeLogged159=-1;
    private static int canvasComposite159(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-canvasCompositeRead159>=400L){
            canvasCompositeRead159=now;
            try{android.content.Context c=launcherContext;
                if(c!=null){int v=android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_canvas_composite_159",0);
                    canvasComposite159=(v>=0&&v<=3)?v:0;}
            }catch(Throwable ignored){}
        }
        if(canvasCompositeLogged159!=canvasComposite159){
            canvasCompositeLogged159=canvasComposite159;
            log("CANVAS_MODE_159 mode="+canvasComposite159);
        }
        return canvasComposite159;
    }
    private static final int[] CANVAS_LOG_COUNTS_159=new int[4];
    private static final int[] CANVAS_DISPATCH_LOG_COUNTS_159=new int[4];


    // V1.9.160: wallpaper sampling anchor A/B. Mode 0 preserves the original path.
    // Mode 1 freezes only the texture transform per folder; geometry and AGSL stay live.
    private static volatile int sampleAnchor160=0;
    private static volatile long sampleAnchorRead160=0L;
    private static int sampleAnchorLogged160=-1;
    private static final int[] SAMPLE_TRACE_COUNTS_160=new int[2];
    private static int sampleAnchor160(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-sampleAnchorRead160>=400L){
            sampleAnchorRead160=now;
            try{android.content.Context c=launcherContext;
                if(c!=null){int v=android.provider.Settings.Global.getInt(c.getContentResolver(),"liquidglass_sample_anchor_160",0);
                    sampleAnchor160=(v==1?1:0);}
            }catch(Throwable ignored){}
        }
        if(sampleAnchorLogged160!=sampleAnchor160){
            sampleAnchorLogged160=sampleAnchor160;
            log("SAMPLE_MODE_160 mode="+sampleAnchor160);
        }
        return sampleAnchor160;
    }

    // V1.9.161: diagnostic-only correlation between Launcher horizontal motion,
    // the parent transform chain and the real AGSL submit. Never modifies Canvas.
    private static final java.util.WeakHashMap<View, float[]> MOTION_LAST_161=new java.util.WeakHashMap<>();
    private static int motionTraceCount161=0;
    private static long motionTraceAt161=0L;
    private static void traceFolderMotion161(View view, Canvas canvas, float sampleLeft, float sampleTop,
                                              android.graphics.Bitmap frame, int sampleMode, int canvasMode) {
        try {
            long now=android.os.SystemClock.uptimeMillis();
            if(motionTraceCount161>=320 || now-motionTraceAt161<55L)return;
            motionTraceAt161=now;
            int[] screen=new int[2];view.getLocationOnScreen(screen);
            float[] prev=MOTION_LAST_161.get(view);
            float delta=prev==null?0f:screen[0]-prev[0];
            float deltaY=prev==null?0f:screen[1]-prev[1];
            MOTION_LAST_161.put(view,new float[]{screen[0],screen[1]});
            StringBuilder parents=new StringBuilder();
            android.view.ViewParent node=view.getParent();
            for(int depth=0;depth<5 && node instanceof View;depth++){
                View v=(View)node;
                if(depth>0)parents.append('>');
                parents.append(v.getClass().getSimpleName())
                    .append('(').append(v.getLeft()).append(',').append(v.getScrollX())
                    .append(',').append(v.getTranslationX()).append(',').append(v.getAlpha()).append(')');
                node=v.getParent();
            }
            android.graphics.Matrix cm=new android.graphics.Matrix();
            canvas.getMatrix(cm);
            float[] vals=new float[9];cm.getValues(vals);
            boolean moving=Math.abs(delta)>0.5f || Math.abs(deltaY)>0.5f;
            motionTraceCount161++;
            log("MOTION_CHAIN_161 id="+System.identityHashCode(view)+
                " t="+now+" moving="+moving+" screen="+screen[0]+","+screen[1]+
                " delta="+delta+","+deltaY+" sample="+sampleLeft+","+sampleTop+
                " local="+view.getLeft()+","+view.getTop()+
                " translation="+view.getTranslationX()+","+view.getTranslationY()+
                " scroll="+view.getScrollX()+","+view.getScrollY()+
                " alpha="+view.getAlpha()+" layer="+view.getLayerType()+
                " canvasMatrix="+vals[0]+","+vals[1]+","+vals[2]+","+vals[3]+","+vals[4]+","+vals[5]+
                " sampleMode="+sampleMode+" compositeMode="+canvasMode+
                " frame="+System.identityHashCode(frame)+
                " afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+
                " parents="+parents);
        }catch(Throwable t){if(motionTraceCount161++<4)log("MOTION_CHAIN_161_ERROR "+t.getClass().getSimpleName());}
    }

    // V1.9.172: bounded per-frame motion/optical-chain telemetry. Diagnostic only.
    private static final java.util.WeakHashMap<View,float[]> MOTION_LAST_172=new java.util.WeakHashMap<>();
    private static int motionCount172=0;
    private static long motionAt172=0L;
    private static void traceMotionOptics172(View view, Canvas canvas, RectF rect,
            float left, float top, float shaderLeft, float shaderTop,
            float scaleX, float scaleY, int alpha, int frameId,
            int pathMode, int compositeMode, int canvasMode, int sampleMode,
            float strength, int shaderMode, int fixedMode) {
        try {
            long now=android.os.SystemClock.uptimeMillis();
            if(motionCount172>=600 || now-motionAt172<14L)return;
            motionAt172=now;
            int[] pos=new int[2]; view.getLocationOnScreen(pos);
            float[] prev=MOTION_LAST_172.get(view);
            float dx=prev==null?0f:pos[0]-prev[0];
            float dy=prev==null?0f:pos[1]-prev[1];
            long dt=prev==null?0L:now-(long)prev[2];
            MOTION_LAST_172.put(view,new float[]{pos[0],pos[1],now});
            float[] cv=new float[9];android.graphics.Matrix cm=new android.graphics.Matrix();
            canvas.getMatrix(cm);cm.getValues(cv);
            StringBuilder chain=new StringBuilder();
            android.view.ViewParent p=view.getParent();
            for(int i=0;i<6 && p instanceof View;i++){
                View v=(View)p;
                if(i>0)chain.append('>');
                chain.append(v.getClass().getSimpleName()).append(':')
                    .append(v.getScrollX()).append(',').append(v.getTranslationX())
                    .append(',').append(v.getAlpha()).append(',').append(v.getLayerType());
                p=v.getParent();
            }
            motionCount172++;
            log("MOTION_OPTICS_172 n="+motionCount172+" id="+System.identityHashCode(view)+
                " t="+now+" dt="+dt+" moving="+(Math.abs(dx)>0.5f||Math.abs(dy)>0.5f)+
                " screen="+pos[0]+","+pos[1]+" delta="+dx+","+dy+
                " sample="+left+","+top+" shaderOrigin="+shaderLeft+","+shaderTop+
                " scale="+scaleX+","+scaleY+" rect="+rect.toShortString()+
                " canvas="+cv[0]+","+cv[1]+","+cv[2]+","+cv[3]+","+cv[4]+","+cv[5]+
                " alpha="+alpha+" viewAlpha="+view.getAlpha()+" layer="+view.getLayerType()+
                " frame="+frameId+" path="+pathMode+" composite="+compositeMode+
                " canvasMode="+canvasMode+" sampleMode="+sampleMode+
                " strength="+strength+" shaderMode="+shaderMode+" fixed="+fixedMode+
                " afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+
                " parents="+chain);
        }catch(Throwable t){if(motionCount172++<8)log("MOTION_OPTICS_172_ERROR "+t.getClass().getSimpleName());}
    }

    // V1.9.162: read-only full transform chain and OEM background correlation.
    // No Canvas mutation, no extra drawing and no per-frame allocation beyond sampled traces.
    private static int layerTrace162=0;
    private static long layerTraceAt162=0L;
    private static void traceLayerChain162(View view, Canvas canvas, String stage) {
        try {
            long now=android.os.SystemClock.uptimeMillis();
            if(layerTrace162>=240 || now-layerTraceAt162<65L)return;
            layerTraceAt162=now;
            int[] screen=new int[2]; view.getLocationOnScreen(screen);
            float[] origin=new float[]{0f,0f};
            android.graphics.Matrix global=new android.graphics.Matrix();
            view.transformMatrixToGlobal(global);
            boolean mapped=true;
            global.mapPoints(origin);
            float[] cv=new float[9];android.graphics.Matrix cm=new android.graphics.Matrix();
            canvas.getMatrix(cm);cm.getValues(cv);
            StringBuilder chain=new StringBuilder();
            View node=view;
            for(int i=0;i<9 && node!=null;i++){
                if(i>0)chain.append('>');
                float[] mv=new float[9];node.getMatrix().getValues(mv);
                chain.append(node.getClass().getSimpleName()).append('#').append(System.identityHashCode(node))
                    .append('(').append(node.getLeft()).append(',').append(node.getTop())
                    .append(';').append(node.getScrollX()).append(',').append(node.getScrollY())
                    .append(';').append(node.getTranslationX()).append(',').append(node.getTranslationY())
                    .append(';').append(node.getAlpha()).append(',').append(node.getLayerType())
                    .append(';').append(mv[0]).append(',').append(mv[2]).append(',').append(mv[5]).append(')');
                android.view.ViewParent parent=node.getParent();node=parent instanceof View?(View)parent:null;
            }
            layerTrace162++;
            log("LAYER_CHAIN_162 stage="+stage+" id="+System.identityHashCode(view)+
                " time="+now+" screen="+screen[0]+","+screen[1]+
                " global="+origin[0]+","+origin[1]+" mapped="+mapped+
                " canvas="+cv[0]+","+cv[1]+","+cv[2]+","+cv[3]+","+cv[4]+","+cv[5]+
                " oemAfter="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+
                " agslSubmitted="+Boolean.TRUE.equals(AGSL_SUBMITTED_144.get())+
                " chain="+chain);
        }catch(Throwable e){if(layerTrace162++<5)log("LAYER_CHAIN_162_ERROR "+e.getClass().getSimpleName());}
    }

    // V1.9.166: reversible, diagnostic-only DragLayer hardware-layer A/B test.
    // Mode 0 = original, mode 1 = temporarily disable explicit hardware layer.
    // Do not modify AGSL, wallpaper coordinates, or unlock redraw logic.
    private static final java.util.WeakHashMap<View,Integer> DRAG_LAYER_ORIGINAL_166 = new java.util.WeakHashMap<>();
    private static int dragLayerMode166=-1, dragLayerLog166=0;
    private static long dragLayerModeReadAt166=0L;
    private static int readDragLayerMode166(View v){
        long now=android.os.SystemClock.uptimeMillis();
        if(dragLayerMode166<0 || now-dragLayerModeReadAt166>250L){
            dragLayerModeReadAt166=now;
            try { dragLayerMode166=android.provider.Settings.Global.getInt(v.getContext().getContentResolver(),
                "liquidglass_draglayer_layer_probe_166",0); }
            catch(Throwable e){dragLayerMode166=0;if(dragLayerLog166++<12)log("DRAG_LAYER_MODE_166 read_error="+e.getClass().getSimpleName());}
            if(dragLayerMode166!=1)dragLayerMode166=0;
        }
        return dragLayerMode166;
    }
    private static void traceDragLayer166(View folder, Canvas canvas){
        try {
            int mode=readDragLayerMode166(folder);
            View layer=null; android.view.ViewParent parent=folder.getParent();
            for(int i=0;i<14 && parent instanceof View;i++){
                View v=(View)parent;
                if(v.getClass().getName().contains("OplusDragLayer")){layer=v;break;}
                parent=v.getParent();
            }
            if(layer==null){if(dragLayerLog166++<70)log("DRAG_LAYER_166 missing mode="+mode);return;}
            int before=layer.getLayerType();
            Integer saved=DRAG_LAYER_ORIGINAL_166.get(layer);
            String action="observe";
            if(mode==1){
                if(saved==null){DRAG_LAYER_ORIGINAL_166.put(layer,before);saved=before;}
                if(before!=View.LAYER_TYPE_NONE){layer.setLayerType(View.LAYER_TYPE_NONE,null);action="set_none";}
            }else if(saved!=null){
                // Restore only when our test left the layer as NONE; avoid overwriting
                // another component's independent change to a different layer type.
                if(before==View.LAYER_TYPE_NONE && saved!=View.LAYER_TYPE_NONE){
                    layer.setLayerType(saved,null);action="restore_"+saved;
                }
                DRAG_LAYER_ORIGINAL_166.remove(layer);
            }
            if(dragLayerLog166++<180 || !"observe".equals(action)){
                float[] m=new float[9];layer.getMatrix().getValues(m);
                log("DRAG_LAYER_166 mode="+mode+" action="+action+
                    " before="+before+" after="+layer.getLayerType()+" original="+saved+
                    " layer="+System.identityHashCode(layer)+" scale="+m[0]+","+m[4]+
                    " translate="+m[2]+","+m[5]+" alpha="+layer.getAlpha()+
                    " canvasHardware="+canvas.isHardwareAccelerated());
            }
        }catch(Throwable e){if(dragLayerLog166++<180)log("DRAG_LAYER_166 error="+e.getClass().getSimpleName()+" "+e.getMessage());}
    }

    private static void drawReferenceRefraction(View view,Canvas canvas,RectF rect,float radius) {
        long[] drawCtx1761=DRAW_CONTEXT_1761.get();
        if(drawCtx1761!=null)drawCtx1761[1]++;
        drawTrace1761("AGSL_ATTEMPT",view,canvas,"refractionActive="+glassEnabled);
        int[] compositeCounts158=COMPOSITE_COUNTS_158.get();
        if(compositeCounts158!=null)compositeCounts158[0]++;
        if(rect.width()<80 || rect.height()<80)return;
        scheduleReferenceLoad(view);
        android.graphics.Bitmap frame=REFRACTION_FRAME;
        if(frame==null||frame.isRecycled()){
            if(GLASS_PIPELINE_DIAGNOSTICS.getAndIncrement()<32)log("GLASS119_PIPELINE stage=FRAME_UNAVAILABLE mode="+customBlurMode+" radiusDp="+customBlurDp+" frameNull="+(frame==null));
            if(glassTintOpacity>0){
                Paint fallback=new Paint(Paint.ANTI_ALIAS_FLAG);
                int tint=tintColor();
                fallback.setColor(Color.argb(Math.round(glassTintOpacity*255f/100f),Color.red(tint),Color.green(tint),Color.blue(tint)));
                canvas.drawRoundRect(rect,radius,radius,fallback);
            }
            int rim198=effectiveRim192();
            if(rim198!=0){
                DynamicRefraction surfaceState198=dynamicState(view);
                float dir198=updateSurfaceMotion198(view,surfaceState198,rim198);
                drawRoundedRim190(canvas,rect,radius,dir198,rim198);
            }
            return;
        }
        android.util.DisplayMetrics dm=view.getResources().getDisplayMetrics();
        int sw=dm.widthPixels,sh=dm.heightPixels;
        // V1.9.99: wallpaper screenshot may be captured at a lower logical
        // resolution than Launcher. This is not an invalid frame: sample it
        // through a scaled BitmapShader rather than skipping refraction.
        final float referenceScaleX=(float)sw/(float)frame.getWidth();
        final float referenceScaleY=(float)sh/(float)frame.getHeight();
        if((frame.getWidth()!=sw || frame.getHeight()!=sh) && REFRACTION_LOGS++<12)
            log("REFRACTION_FRAME status=SCALED frame="+frame.getWidth()+"x"+frame.getHeight()+" display="+sw+"x"+sh+" scale="+referenceScaleX+","+referenceScaleY);
        DynamicRefraction state=dynamicState(view);
        float[] xy=dynamicScreenPosition(view,state);
        float left=xy[0]+rect.left,top=xy[1]+rect.top;
        final int coordProbe155=coordinateProbe155();
        if(coordProbe155!=0){
            int[] screenLoc155=new int[2];
            view.getLocationOnScreen(screenLoc155);
            if(coordProbe155==1){left=screenLoc155[0]+rect.left;top=screenLoc155[1]+rect.top;}
            else if(coordProbe155==2){left=Math.round(left);top=Math.round(top);}
            long now155=android.os.SystemClock.uptimeMillis();
            if(coordinateTraceCount155<160 && now155-coordinateTraceAt155>=100L){
                coordinateTraceAt155=now155;coordinateTraceCount155++;
                log("COORD_COMPARE_155 mode="+coordProbe155+" matrix="+xy[0]+","+xy[1]+
                    " screen="+screenLoc155[0]+","+screenLoc155[1]+
                    " chosen="+left+","+top+" diff="+(xy[0]-screenLoc155[0])+","+(xy[1]-screenLoc155[1])+
                    " frame="+System.identityHashCode(frame));
            }
        }
        // V1.9.112: diagnostic only. No sampling delay, no alpha changes.
        // Compare matrix-derived position with Android's screen location and
        // record abrupt sampling jumps during touch down/move/up.
        long coordNow=android.os.SystemClock.uptimeMillis();
        if(diagnostics && state.coordLogCount<240 && coordNow-state.coordLogAt>=65L){
            state.coordLogAt=coordNow;
            state.coordLogCount++;
            int[] loc=new int[2];
            view.getLocationOnScreen(loc);
            float dx=Float.isNaN(state.prevSampleX)?0f:left-state.prevSampleX;
            float dy=Float.isNaN(state.prevSampleY)?0f:top-state.prevSampleY;
            float lx=Float.isNaN(state.prevLocationX)?0f:loc[0]-state.prevLocationX;
            float ly=Float.isNaN(state.prevLocationY)?0f:loc[1]-state.prevLocationY;
            android.view.ViewParent vp=view.getParent();
            String parent=vp instanceof View?vp.getClass().getSimpleName():"none";
            log("REFRACTION_COORD_112 id="+System.identityHashCode(view)+
                " t="+coordNow+" matrixXY="+xy[0]+","+xy[1]+
                " locationXY="+loc[0]+","+loc[1]+
                " sampleXY="+left+","+top+
                " sampleDelta="+dx+","+dy+
                " locationDelta="+lx+","+ly+
                " matrixVsLocation="+(xy[0]-loc[0])+","+(xy[1]-loc[1])+
                " translation="+view.getTranslationX()+","+view.getTranslationY()+
                " scroll="+view.getScrollX()+","+view.getScrollY()+
                " parent="+parent+
                " frame="+System.identityHashCode(frame)+
                " refStamp="+REFRACTION_FRAME_MODIFIED+
                " size="+frame.getWidth()+"x"+frame.getHeight());
            state.prevSampleX=left;state.prevSampleY=top;
            state.prevLocationX=loc[0];state.prevLocationY=loc[1];
        }
        // V1.9.76: retain the refraction pass while ANY part of the folder
        // intersects the display. Requiring the whole rectangle to fit made
        // the highlight blink off as soon as a folder crossed a screen edge.
        // The BitmapShader uses CLAMP, so partial off-screen sampling is safe.
        float right=left+rect.width(), bottom=top+rect.height();
        if(right<=0f || bottom<=0f || left>=sw || top>=sh)return;
        if(state.source!=frame){
            state.source=frame;
            state.shader=new android.graphics.BitmapShader(frame,android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
        }
        int save=canvas.save();
        try {
            // Match the V1.9.28 material/highlight mask: +6px CORNER radius,
            // without shrinking the straight edges.
            float contourRadius=Math.min(Math.min(rect.width(),rect.height())*0.5f,radius+BLUR_CORNER_RADIUS_DELTA_PX);
            state.clip.reset();state.clip.addRoundRect(rect,contourRadius,contourRadius,Path.Direction.CW);
            canvas.clipPath(state.clip);
            if(GLASS_PIPELINE_DIAGNOSTICS.getAndIncrement()<32)log("GLASS119_PIPELINE stage=DRAW_ENTER mode="+customBlurMode+" radiusDp="+customBlurDp+" refractPercent="+refractionPercent+" tintPercent="+glassTintOpacity+" frame="+frame.getWidth()+"x"+frame.getHeight()+" hardware="+canvas.isHardwareAccelerated());
            // V1.9.70: one hardware-accelerated draw with continuous per-pixel
            // displacement. No per-strip matrix mutation or 48 draw commands.
            boolean continuousDrawn=false;
            int opticalAb=opticalAb133(); // 0: stable refractive AGSL; 1: legacy comparison; 2: no AGSL
            int probe141=opticalProbe141();
            if(probe141!=opticalProbeLogged141){
                opticalProbeLogged141=probe141;
                log("OPTICAL_PROBE_141 mode="+probe141+" refraction="+REFRACTION_ENABLED+
                    " blur="+customBlurDp+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get()));
            }
            // Strict master switch: clear-refraction and blur must never activate AGSL.
            final boolean refractionActive=REFRACTION_ENABLED && refractionPercent>0;
            final int pathMode169=refractionPathMode169();
            logRefractionPath169(pathMode169,"ENTER",refractionActive);
            final int compositeMode170=refractionComposite170();
            logComposite170(compositeMode170,"ENTER",-1);
            if(compositeMode170==1 && customBlurMode==1 && customBlurDp>0 && !refractionActive){
                android.graphics.Bitmap blurred=blurredWallpaper(frame,dm.density,view);
                if(blurred!=null){
                    if(blurHitLogs142++<24)log("BLUR_DRAW_142 READY radius="+customBlurDp+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get()));
                    if(state.cachedBlurBitmap!=blurred || state.cachedBlurShader==null){
                        state.cachedBlurBitmap=blurred;
                        state.cachedBlurShader=new android.graphics.BitmapShader(blurred,android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
                        state.cachedBlurPaint.setShader(state.cachedBlurShader);
                    }
                    // V1.9.168: audit isolation: preserve the historical alpha by default.
                    // Setting mode=1 keeps the same blur opacity when refraction is toggled,
                    // isolating the AGSL contribution from the blur-alpha coupling.
                    final int blurIsolation168=refractionBlurIsolation168();
                    final int blurAlpha168=255;
                    state.cachedBlurPaint.setAlpha(blurAlpha168);
                    logComposite170(compositeMode170,"PRE_AGSL_BLUR",blurAlpha168);
                    if(REFRACTION_BLUR_LOGS_168++<80)log("REFRACTION_BLUR_168 isolation="+blurIsolation168+
                        " refractionActive="+refractionActive+" alpha="+blurAlpha168+
                        " blurDp="+customBlurDp+" frame="+System.identityHashCode(frame));
                    state.cachedBlurMatrix.reset();
                    state.cachedBlurMatrix.setScale((float)sw/blurred.getWidth(),(float)sh/blurred.getHeight());
                    state.cachedBlurMatrix.postTranslate(-left,-top);
                    state.cachedBlurShader.setLocalMatrix(state.cachedBlurMatrix);
                    canvas.drawRoundRect(rect,contourRadius,contourRadius,state.cachedBlurPaint);
                    if(GLASS_PIPELINE_DIAGNOSTICS.getAndIncrement()<48)log("GLASS126_BLUR_DRAWN enabled="+REFRACTION_ENABLED+" radiusDp="+customBlurDp);
                }else if(blurHitLogs142++<24){log("BLUR_DRAW_142 WAIT radius="+customBlurDp+" frame="+System.identityHashCode(frame)+" pending="+BLUR_PENDING);}
            }
            if(opticalAb!=2 && probe141!=1 && !(probe141==3 && Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())) && !(probe141==4 && !Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())) && !(probe141==5 && Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())) && refractionActive && pathMode169!=1 && canvas.isHardwareAccelerated() && !state.continuousUnavailable && !Boolean.TRUE.equals(AGSL_SUBMITTED_144.get())){
                try {
                    if(state.continuousShader==null){
                        state.continuousShader=new android.graphics.RuntimeShader(REFRACTION_AGSL);
                    }
                    state.shaderMatrix.reset();
                    state.shaderMatrix.setScale(referenceScaleX,referenceScaleY);
                    // V143 mode 6: freeze the screen-to-wallpaper transform for this view.
                    // This intentionally makes wallpaper alignment drift during swipes.
                    // Mode 7 and normal modes continue tracking actual screen position.
                    if(probe141==6){
                        if(Float.isNaN(state.probe143LockedLeft)){
                            state.probe143LockedLeft=left;state.probe143LockedTop=top;
                            log("OPTICAL_PROBE_143 LOCK id="+System.identityHashCode(view)+" x="+left+" y="+top);
                        }
                    }else{state.probe143LockedLeft=Float.NaN;state.probe143LockedTop=Float.NaN;}
                    final int sampleMode160=sampleAnchor160();
                    if(state.sample160LastMode!=sampleMode160){
                        state.sample160AnchorX=Float.NaN;
                        state.sample160AnchorY=Float.NaN;
                        state.sample160LastMode=sampleMode160;
                    }
                    if(sampleMode160==1 && Float.isNaN(state.sample160AnchorX)){
                        state.sample160AnchorX=left;
                        state.sample160AnchorY=top;
                    }
                    float shaderLeft=sampleMode160==1?state.sample160AnchorX:(probe141==6?state.probe143LockedLeft:left);
                    float shaderTop=sampleMode160==1?state.sample160AnchorY:(probe141==6?state.probe143LockedTop:top);
                    if(SAMPLE_TRACE_COUNTS_160[sampleMode160]<120){
                        SAMPLE_TRACE_COUNTS_160[sampleMode160]++;
                        log("SAMPLE_TRACE_160 mode="+sampleMode160+" view="+System.identityHashCode(view)+
                            " canvas="+System.identityHashCode(canvas)+" frame="+System.identityHashCode(frame)+
                            " live="+left+","+top+" anchor="+shaderLeft+","+shaderTop+
                            " delta="+(left-shaderLeft)+","+(top-shaderTop)+
                            " scale="+referenceScaleX+","+referenceScaleY+
                            " afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get()));
                    }
                    // V195: blur is a SOURCE texture, not a later overlay. Both blurred and
                    // clear wallpaper use the exact same AGSL displacement/strength.
                    android.graphics.Shader opticalInput195=state.shader;
                    boolean blurredInput195=false;
                    if(customBlurMode==1 && customBlurDp>0){
                        android.graphics.Bitmap b195=blurredWallpaper(frame,dm.density,view);
                        if(b195!=null && !b195.isRecycled()){
                            if(state.cachedBlurBitmap!=b195 || state.cachedBlurShader==null){
                                state.cachedBlurBitmap=b195;
                                state.cachedBlurShader=new android.graphics.BitmapShader(b195,
                                    android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
                                state.cachedBlurPaint.setShader(state.cachedBlurShader);
                            }
                            state.cachedBlurMatrix.reset();
                            state.cachedBlurMatrix.setScale((float)sw/b195.getWidth(),(float)sh/b195.getHeight());
                            state.cachedBlurMatrix.postTranslate(-shaderLeft,-shaderTop);
                            state.cachedBlurShader.setLocalMatrix(state.cachedBlurMatrix);
                            opticalInput195=state.cachedBlurShader;
                            blurredInput195=true;
                        }
                    }
                    state.shaderMatrix.postTranslate(-shaderLeft,-shaderTop);
                    state.shader.setLocalMatrix(state.shaderMatrix);
                    state.continuousShader.setInputShader("wallpaper",opticalInput195);
                    // V201: clear mode restores original wallpaper detail at the SAME refracted coordinate.
                    // Blur=0 / unavailable: both inputs are identical, so no forced blur or visual shift.
                    state.continuousShader.setInputShader("wallpaperClear201",state.shader);
                    // V202: preserve the user's blur setting. Clear refraction adds only a
                    // small amount of fine detail, decreasing as blur radius grows.
                    // At 0dp the two sources are identical; never manufacture blur.
                    final float detailMix202 = (clearRefraction && blurredInput195)
                        ? Math.max(0.04f, 0.16f - 0.003f * Math.min(40, Math.max(0, customBlurDp)))
                        : 0.0f;
                    state.continuousShader.setFloatUniform("clearDetailMix201", detailMix202);
                    if(opticalPipelineLogs195++<100)log("OPTICAL_PIPELINE_195 source="+
                        (blurredInput195?"BLURRED_WALLPAPER":"CLEAR_WALLPAPER")+
                        " blurDp="+customBlurDp+" refractPercent="+refractionPercent+
                        " composite="+compositeMode170+" clear="+clearRefraction+
                        " detailMix202="+detailMix202+" view="+System.identityHashCode(view));
                    state.continuousShader.setFloatUniform("bounds",rect.left,rect.top,rect.width(),rect.height());
                    int visibilityTest=currentRefractionVisibilityTest();
                    // V1.9.93: mode 0 uses the exact proven mode-2 optical profile.
                    // Keep the requested mode 1/3 diagnostics available.
                    int shaderMode=(visibilityTest==0?2:visibilityTest);
                    int displacementMode=currentDisplacementIsolationMode();
                    int probe156=opticalProbe156();
                    state.continuousShader.setFloatUniform("strength",probe156==1?0.0f:(displacementMode==0?0.0f:22.0f*refractionPercent/35.0f));
                    state.continuousShader.setFloatUniform("diagnosticMode",(float)shaderMode);
                    final int alphaMode177=0; // V179: disable legacy gray underlay experiments
                    state.continuousShader.setFloatUniform("alphaUnderlay177",(float)alphaMode177);
                    final int shaderMode178=0; // V179: alpha controlled by stableSheen179
                    state.continuousShader.setFloatUniform("shaderAlpha178",(float)shaderMode178);
                    final int stableMode179=stableSheenMode179();
                    // V179 modes override older alpha experiments inside AGSL.
                    state.continuousShader.setFloatUniform("stableSheen179",(float)stableMode179);
                    traceStableSheen179(stableMode179,view);
                    updateMotion183(view,false);
                    final int specular184=specularMode184();
                    float motionLevel184, motionDirection184;
                    synchronized(MOTION_183){
                        motionLevel184=Math.max(0f,Math.min(1f,MOTION_183.highlight));
                        // V186: direction is transient velocity, NEVER a persistent page offset.
                        // Wall-clock timeout also handles detached/replaced folder views.
                        long ageNs=MOTION_183.lastMovementNs==0L?Long.MAX_VALUE:
                            Math.max(0L,android.os.SystemClock.elapsedRealtimeNanos()-MOTION_183.lastMovementNs);
                        float ageMs=ageNs==Long.MAX_VALUE?100000f:ageNs/1000000f;
                        float recenter=ageMs<=120f?1f:(float)Math.exp(-Math.min(2000f,ageMs-120f)/180f);
                        if(ageMs>=650f)recenter=0f;
                        motionDirection184=Math.max(-1f,Math.min(1f,MOTION_183.signed/900f))*recenter;
                    }
                    // V188: ease the *rendered* direction with wall-clock time.
                    // After returning from another Workspace page, do not jump
                    // directly to the sensor's already-settled zero value.
                    final long drawNs188=android.os.SystemClock.elapsedRealtimeNanos();
                    if(specular184==3){
                        if(state.visualUpdatedNs188==0L){
                            state.visualDirection188=motionDirection184;
                        }else{
                            float dt188=Math.max(0f,Math.min(120f,(drawNs188-state.visualUpdatedNs188)/1000000f));
                            float tau188=Math.abs(motionDirection184)>Math.abs(state.visualDirection188)?85f:180f;
                            float alpha188=1f-(float)Math.exp(-dt188/tau188);
                            state.visualDirection188+=alpha188*(motionDirection184-state.visualDirection188);
                            if(Math.abs(state.visualDirection188)<0.004f && Math.abs(motionDirection184)<0.004f)state.visualDirection188=0f;
                        }
                        motionDirection184=state.visualDirection188;
                    }else{state.visualDirection188=0f;}
                    state.visualUpdatedNs188=drawNs188;
                    state.lastVisibleFrameNs188=drawNs188;
                    // V189: post-blur surface stripe replaces (not stacks over)
                    // the legacy AGSL stripe when the diagnostic pass is enabled.
                    state.continuousShader.setFloatUniform("specularMode184",
                        (float)((surface194Enabled() || effectiveRim192()!=0 || surfacePass189()!=0 || effectiveUnified193()==1)?0:specular184));
                    state.continuousShader.setFloatUniform("specularLevel184",motionLevel184);
                    state.continuousShader.setFloatUniform("specularDirection184",motionDirection184);
                    state.lastSpecularDirection187=motionDirection184;
                    state.lastSpecularDrawNs187=drawNs188;
                    state.recenterPending187=specular184==3 && Math.abs(motionDirection184)>0.005f;
                    if(state.recenterPending187)scheduleRecenter188(view,state);
                    if(specularLogs184++<250)log("SPECULAR_DIAG_186 shaderCreated="+(state.continuousShader!=null)+" uniformApplied=true drawPathActive=true mode="+specular184+
                        " level="+motionLevel184+" direction="+motionDirection184+
                        " view="+System.identityHashCode(view));
                    if(specular184==3 && recenterLogs187<100 && state.recenterPending187)
                        log("SPECULAR_EASE_188 view="+System.identityHashCode(view)+" direction="+motionDirection184+" scheduled="+state.redrawScheduled188);
                    final int fixed167=fixedOutput167();
                    state.continuousShader.setFloatUniform("fixedOutput167",(float)fixed167);
                    final int isolation173=motionIsolation173();
                    state.continuousShader.setFloatUniform("motionIsolation173",(float)isolation173);
                    traceMotionIsolation173(view,isolation173,left,top,System.identityHashCode(frame));
                    int samplingDiagnostic=currentSampleIsolationMode();
                    state.continuousShader.setFloatUniform("sampleIsolation",(float)(probe156==2?1:samplingDiagnostic));
                    final int composite157=compositeProbe157();
                    state.continuousShader.setFloatUniform("compositeProbe157",(float)composite157);
                    // Same local p-space as wallpaper.eval(), but bounded by
                    // the actual display rectangle. Only mode 2 clamps p.
                    state.continuousShader.setFloatUniform("screenClip",0.5f-left,0.5f-top,
                        (float)sw-0.5f-left,(float)sh-0.5f-top);
                    state.continuousShader.setFloatUniform("probe143Composite",probe141==7?1.0f:0.0f);
                    state.continuousShader.setFloatUniform("passId",Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())?2.0f:1.0f);
                    // Only the motion sheen is time-smoothed. Wallpaper sampling
                    // stays locked to the real screen position on every frame.
                    state.continuousShader.setFloatUniform("stableOpticalMode",opticalAb==0?1.0f:0.0f);
                    state.continuousShader.setFloatUniform("clearRefraction",clearRefraction?1.0f:0.0f);
                    state.continuousShader.setFloatUniform("opticalSheenEnabled",probe141==2?0.0f:1.0f); // V141: isolate AGSL ambient without disabling displacement
                    state.continuousShader.setFloatUniform("motionHighlight",0.0f); // legacy uniform disabled; no touch-driven sheen
                    state.continuousShader.setFloatUniform("temporalOpacity",1.0f); // keep wallpaper refraction stable; animate only highlight
                    final int alphaMode171=agslPaintAlphaMode171();
                    state.samplePaint.setAlpha(alphaMode171==0?255:(alphaMode171==1?128:(alphaMode171==2?64:0)));
                    state.samplePaint.setShader(state.continuousShader);
                    traceAgslAlpha171(view,canvas,alphaMode171,state.samplePaint);
                    traceParentComposite174(view,canvas,left,top,state.samplePaint.getAlpha(),System.identityHashCode(frame));
                    // V159: only the AGSL surface is changed. The native blur,
                    // tint, rim and children remain on their original Canvas path.
                    // SRC is NEVER applied directly to the parent Canvas: that would
                    // erase existing pixels outside the shader's non-opaque area.
                    final int canvasMode159=0; // V179: normal SRC_OVER path
                    final int layerMode175=0; // V179: normal canvas composition
                    int isolatedLayer159=-1;
                    android.graphics.Xfermode previousXfer159=state.samplePaint.getXfermode();
                    Paint restorePaint175=null;
                    traceLayer175(layerMode175,view,canvas,state.samplePaint.getAlpha());
                    traceAlphaUnderlay177(alphaMode177,view,canvas,state.samplePaint.getAlpha());
                    traceShaderAlpha178(shaderMode178,view,state.samplePaint.getAlpha());
                    try{
                        if(layerMode175!=0){
                            if(layerMode175==3){
                                restorePaint175=new Paint(Paint.ANTI_ALIAS_FLAG);
                                restorePaint175.setAlpha(128);
                            }
                            if(layerMode175==2){
                                isolatedLayer159=canvas.saveLayer(0f,0f,view.getWidth(),view.getHeight(),restorePaint175);
                            }else{
                                isolatedLayer159=canvas.saveLayer(rect.left,rect.top,rect.right,rect.bottom,restorePaint175);
                            }
                            // Preserve the AGSL shader's original SRC_OVER semantics inside the layer.
                            state.samplePaint.setXfermode(null);
                        }else{
                            // Retain V159 diagnostic behavior when mode 175 is disabled.
                            if(canvasMode159==2 || canvasMode159==3)
                                isolatedLayer159=canvas.saveLayer(rect.left,rect.top,rect.right,rect.bottom,null);
                            if(canvasMode159==1)
                                state.samplePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_OVER));
                            else if(canvasMode159==2)
                                state.samplePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC));
                            else state.samplePaint.setXfermode(null);
                        }
                        // V177 modes 2/3: constant opaque local base, not wallpaper-derived.
                        // Mode 3 groups base+AGSL into a saveLayer before restoring.
                        int localLayer177=-1;
                        try{
                            if(alphaMode177==3)
                                localLayer177=canvas.saveLayer(rect.left,rect.top,rect.right,rect.bottom,null);
                            if(alphaMode177>=2){
                                Paint base177=new Paint(Paint.ANTI_ALIAS_FLAG);
                                base177.setColor(Color.rgb(100,100,100));
                                canvas.drawRoundRect(rect,contourRadius,contourRadius,base177);
                            }
                            canvas.drawRoundRect(rect,contourRadius,contourRadius,state.samplePaint);
                        }finally{
                            if(localLayer177>=0)canvas.restoreToCount(localLayer177);
                        }
                    }finally{
                        state.samplePaint.setXfermode(previousXfer159);
                        if(isolatedLayer159>=0)canvas.restoreToCount(isolatedLayer159);
                    }
                    traceFixed167(view,canvas,fixed167,left,top);
                    traceMotionOptics172(view,canvas,rect,left,top,shaderLeft,shaderTop,
                        referenceScaleX,referenceScaleY,state.samplePaint.getAlpha(),
                        System.identityHashCode(frame),pathMode169,compositeMode170,
                        canvasMode159,sampleMode160,
                        probe156==1?0.0f:(displacementMode==0?0.0f:22.0f*refractionPercent/35.0f),
                        shaderMode,fixed167);
                    traceFolderMotion161(view,canvas,left,top,frame,sampleMode160,canvasMode159);
                    traceLayerChain162(view,canvas,"AGSL_SUBMIT");
                    if(CANVAS_LOG_COUNTS_159[canvasMode159]++<72)
                        log("CANVAS_SUBMIT_159 mode="+canvasMode159+" view="+System.identityHashCode(view)+
                            " canvas="+System.identityHashCode(canvas)+" layer="+(isolatedLayer159>=0)+
                            " attempts="+(compositeCounts158==null?-1:compositeCounts158[0])+
                            " submissionsBefore="+(compositeCounts158==null?-1:compositeCounts158[1])+
                            " frame="+System.identityHashCode(frame)+" left="+left+" top="+top);
                    if(compositeCounts158!=null){
                        compositeCounts158[1]++;
                        if(compositeTraceLogs158++<240)log("COMPOSITE_SUBMIT_158 view="+System.identityHashCode(view)+" mode="+composite157+" attempts="+compositeCounts158[0]+" submissions="+compositeCounts158[1]+" canvas="+System.identityHashCode(canvas)+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+" frame="+System.identityHashCode(frame));
                    }
                    if(compositeDrawLogs157++<160)log("COMPOSITE_DRAW_157 mode="+composite157+" view="+System.identityHashCode(view)+" canvas="+System.identityHashCode(canvas)+" frame="+System.identityHashCode(frame)+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+" alphaPaint="+state.samplePaint.getAlpha()+" left="+left+" top="+top);
                    continuousDrawn=true;
                    long[] submitted1761=DRAW_CONTEXT_1761.get();
                    if(submitted1761!=null)submitted1761[2]++;
                    drawTrace1761("AGSL_SUBMIT",view,canvas,"paintAlpha="+state.samplePaint.getAlpha()+" frame="+System.identityHashCode(frame));
                    AGSL_SUBMITTED_144.set(Boolean.TRUE);
                    if(opticalDrawLogs156++<100)log("OPTICAL_DRAW_156 mode="+probe156+
                        " view="+System.identityHashCode(view)+" canvas="+System.identityHashCode(canvas)+
                        " frame="+System.identityHashCode(frame)+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+
                        " left="+left+" top="+top+" strengthZero="+(probe156==1)+" sampleNeutral="+(probe156==2));
                    if(GLASS_PIPELINE_DIAGNOSTICS.getAndIncrement()<64)log("GLASS119_PIPELINE stage=AGSL_DRAWN mode="+customBlurMode+" shaderMode="+shaderMode+" displacementMode="+displacementMode);
                    if(refractionPassLogs++<32)log("REFRACTION_TEST_PASS mode="+visibilityTest+" shaderMode="+shaderMode+" displacementMode="+displacementMode+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get())+" hardware="+canvas.isHardwareAccelerated()+" rect="+rect.toShortString());
                    if(!state.shaderStatusLogged){state.shaderStatusLogged=true;log("REFRACTION_SHADER_STATUS mode=AGSL_ACTIVE");}
                }catch(Throwable t){
                    state.continuousUnavailable=true;
                    if(!state.shaderStatusLogged){state.shaderStatusLogged=true;log("REFRACTION_SHADER_STATUS mode=SHADER_FAILED reason="+t.getClass().getName()+" detail="+String.valueOf(t.getMessage()));}
                }
            }
            if(refractionActive && Boolean.TRUE.equals(AGSL_SUBMITTED_144.get()) && !continuousDrawn && agslSkipLogs144++<24)
                log("AGSL_ONCE_144 SKIP duplicate view="+System.identityHashCode(view));
            if(refractionActive && opticalAb!=2 && !continuousDrawn){
                // No banded fallback: the former 12-strip implementation caused
                // visible vertical seams. Keep the native folder blur intact.
                if(!state.shaderStatusLogged){state.shaderStatusLogged=true;log("REFRACTION_SHADER_STATUS mode=NATIVE_BLUR_FALLBACK");}
            }
            logRefractionPath169(pathMode169,continuousDrawn?"AGSL_DRAWN":"AGSL_NOT_DRAWN",refractionActive);
            logComposite170(compositeMode170,continuousDrawn?"AGSL_DRAWN":"AGSL_NOT_DRAWN",-1);
            state.samplePaint.setShader(null);
            state.samplePaint.setAlpha(255);
            // Independent background blur and tint: always visible with refraction OFF.
            // With refraction ON, retain some optical detail beneath the blur.
            if(customBlurMode==1 && customBlurDp>0 && !continuousDrawn){
                android.graphics.Bitmap blurred=blurredWallpaper(frame,dm.density,view);
                if(blurred!=null){
                    if(blurHitLogs142++<24)log("BLUR_DRAW_142 READY radius="+customBlurDp+" afterOEM="+Boolean.TRUE.equals(REFRACTION_AFTER_OEM.get()));
                    if(state.cachedBlurBitmap!=blurred || state.cachedBlurShader==null){
                        state.cachedBlurBitmap=blurred;
                        state.cachedBlurShader=new android.graphics.BitmapShader(blurred,android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
                        state.cachedBlurPaint.setShader(state.cachedBlurShader);
                    }
                    // V1.9.168: audit isolation: preserve the historical alpha by default.
                    // Setting mode=1 keeps the same blur opacity when refraction is toggled,
                    // isolating the AGSL contribution from the blur-alpha coupling.
                    final int blurIsolation168=refractionBlurIsolation168();
                    final int blurAlpha168=blurIsolation168==1 ? 255 : (refractionActive ? 235 : 255);
                    state.cachedBlurPaint.setAlpha(blurAlpha168);
                    if(REFRACTION_BLUR_LOGS_168++<80)log("REFRACTION_BLUR_168 isolation="+blurIsolation168+
                        " refractionActive="+refractionActive+" alpha="+blurAlpha168+
                        " blurDp="+customBlurDp+" frame="+System.identityHashCode(frame));
                    state.cachedBlurMatrix.reset();
                    state.cachedBlurMatrix.setScale((float)sw/blurred.getWidth(),(float)sh/blurred.getHeight());
                    state.cachedBlurMatrix.postTranslate(-left,-top);
                    state.cachedBlurShader.setLocalMatrix(state.cachedBlurMatrix);
                    canvas.drawRoundRect(rect,contourRadius,contourRadius,state.cachedBlurPaint);
                    if(GLASS_PIPELINE_DIAGNOSTICS.getAndIncrement()<48)log("GLASS126_BLUR_DRAWN enabled="+REFRACTION_ENABLED+" radiusDp="+customBlurDp);
                }else if(blurHitLogs142++<24){log("BLUR_DRAW_142 WAIT radius="+customBlurDp+" frame="+System.identityHashCode(frame)+" pending="+BLUR_PENDING);}
            }
            // Glass material is last, so AGSL cannot overwrite its opacity/style.
            if(pathMode169!=2 && glassTintOpacity>0){
                Paint tintPaint=state.cachedTintPaint;
                int tint=tintColor();
                tintPaint.setColor(Color.argb(Math.round(glassTintOpacity*255f/100f),Color.red(tint),Color.green(tint),Color.blue(tint)));
                canvas.drawRoundRect(rect,contourRadius,contourRadius,tintPaint);
            }
            if(pathMode169!=2 && tintMode!=0 && tintStrength>0){
                Paint stylePaint=state.cachedTintPaint;
                int styleColor=tintColor();
                stylePaint.setColor(Color.argb(Math.round(0.72f*255f*tintStrength/90f*glassTintOpacity/100f), Color.red(styleColor),Color.green(styleColor),Color.blue(styleColor)));
                canvas.drawRoundRect(rect,contourRadius,contourRadius,stylePaint);
            }
            // V189: intentionally AFTER custom blur and tint, independent of blur radius.
            // This is a diagnostic comparison path, not the final curved specular model.
            final int surface189=surfacePass189();
            final int spec189=specularMode184();
            final int unified192=effectiveUnified193();
            if(surfaceDefaultLogs193++<24)
                log("SURFACE_DEFAULT_193 selection="+surfaceDefault193()+
                    " effectiveUnified="+unified192+
                    " rimMode="+rimMode190()+
                    " blurDp="+customBlurDp+
                    " direction="+state.visualDirection188);
            final int rim190=effectiveRim192();
            if(surface194Logs++<24)log("SURFACE_MODE_194 mode="+surfaceMode194()+
                " rollback="+(!surface194Enabled())+" strength="+surfaceStrength194()+
                " softness="+surfaceSoftness194()+" rim="+rim190+
                " blurDp="+customBlurDp+" refraction="+refractionActive+" agsl="+continuousDrawn+" direction="+state.visualDirection188);
            if(unified192!=0 && surfaceUnifiedLogs192++<24)
                log("SURFACE_UNIFIED_192 mode="+unified192+
                    " legacyNative="+(unified192==1 || unified192==3)+
                    " newRim="+(rim190!=0)+
                    " rimMode="+rim190+
                    " blurDp="+customBlurDp+
                    " direction="+state.visualDirection188+
                    " view="+System.identityHashCode(view));
            if(!surface194Enabled() && unified192==0 && rim190==0 && surface189!=0 && refractionActive && continuousDrawn && spec189!=0){
                drawSurfaceStripe189(canvas,rect,contourRadius,
                    state.visualDirection188,spec189,surface189);
                if(surfaceLogs189++<180)log("SURFACE_PASS_189 pass="+surface189+
                    " specular="+spec189+" blurDp="+customBlurDp+
                    " composite="+compositeMode170+" afterCustomBlur=true"+
                    " afterTint=true view="+System.identityHashCode(view)+
                    " direction="+state.visualDirection188);
            }
            if(rim190!=0){ // V198: independent motion and size-normalized rim
                float surfaceDirection198=updateSurfaceMotion198(view,state,rim190);
                drawRoundedRim190(canvas,rect,contourRadius,surfaceDirection198,rim190);
                if(rimLogs190++<24)log("SURFACE_RIM_190 mode="+rim190+
                    " blurDp="+customBlurDp+" radius="+contourRadius+
                    " direction="+state.visualDirection188+
                    " view="+System.identityHashCode(view));
            }
            // V1.9.74: the refraction pass must not draw a second outline.
            // The unified highlight renderer owns the complete edge treatment.
            if(state.moveLogs++<18)log("REFRACTION_GATE_138 active="+refractionActive+
                " agslDrawn="+continuousDrawn+" configEpoch="+REFRACTION_CONFIG_EPOCH+
                " blurDp="+customBlurDp+" tintOpacity="+glassTintOpacity);
            if(state.moveLogs++<18)log("REFRACTION_DYNAMIC_SAMPLE viewScreenX="+xy[0]+" viewScreenY="+xy[1]+
                " sampleX="+left+" sampleY="+top+" mode="+(continuousDrawn?"AGSL":"NATIVE_BLUR_FALLBACK")+" frameReady=true");
        }finally{canvas.restoreToCount(save);}
    }
    private static int glass123GateLogs=0;
    private static void drawFolderIconFusion(View v, Canvas canvas, boolean edgeOnly) {
        long[] fusion1761=DRAW_CONTEXT_1761.get();
        if(fusion1761!=null)fusion1761[edgeOnly?4:3]++;
        drawTrace1761("FUSION_ENTRY",v,canvas,"edge="+edgeOnly+
            " surfaceGuard="+Boolean.TRUE.equals(SURFACE_SUBMITTED_144.get())+
            " fusionGuard="+Boolean.TRUE.equals(FOLDER_FUSION_GUARD.get()));
        if (!glassEnabled || probeMode == 1 || canvas == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        if (!edgeOnly && Boolean.TRUE.equals(SURFACE_SUBMITTED_144.get())) return;
        if (Boolean.TRUE.equals(FOLDER_FUSION_GUARD.get())) return;
        try {
            Object preview = field(v.getClass(), "mBackground").get(v);
            if (preview == null) { if(glass123GateLogs++<40)log("GLASS123_GATE reason=preview_null edge="+edgeOnly); return; }
            Method getter = backgroundGetter(preview.getClass());
            Object value = getter.invoke(preview);
            if (!(value instanceof Drawable)) { if(glass123GateLogs++<40)log("GLASS124_GATE reason=not_drawable edge="+edgeOnly); return; }
            // V1.9.124: ColorOS 15 can expose a GradientDrawable instead of
            // LayerBlurDrawable. Geometry and wallpaper rendering must not
            // depend on the OEM blur implementation class.
            final boolean nativeBlurDrawable=value.getClass().getName().endsWith(".LayerBlurDrawable");
            if(glass123GateLogs++<40)log("GLASS124_GATE accepted="+value.getClass().getName()+" nativeBlur="+nativeBlurDrawable+" edge="+edgeOnly);
            if(glass123GateLogs++<40)log("GLASS124_GATE reason=passed edge="+edgeOnly+" frameReady="+(REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled()));
            if (diagnostics && !edgeOnly) inspectFusionGeometry(v,preview,(Drawable)value);
            // V1.9.21: native blur bounds are in preview-local coordinates; the
            // preview offsets translate them into FolderIcon's Canvas coordinates.
            // Never use FolderIcon's full dimensions (they include the label).
            Drawable blur = (Drawable)value;
            // Preserve OEM geometry and blur parameters; no setMRadius calls.
            Rect nativeBounds = blur.getBounds();
            if (nativeBounds == null || nativeBounds.isEmpty()) {
                if (folderFusionLogs++ < 12) log("GLASS124_GEOMETRY_SKIP reason=empty_drawable_bounds type="+value.getClass().getSimpleName());
                return;
            }
            float offsetX, offsetY;
            float radius;
            try {
                offsetX = ((Number)field(preview.getClass(), "basePreviewOffsetX").get(preview)).floatValue();
                offsetY = ((Number)field(preview.getClass(), "basePreviewOffsetY").get(preview)).floatValue();
            } catch (Throwable missingOffset) {
                if (folderFusionLogs++ < 12) log("FOLDER_FUSION_GEOMETRY_SKIP reason=offset_unavailable");
                return;
            }
            Outline nativeShape = new Outline();
            blur.getOutline(nativeShape);
            Rect outlineBounds = new Rect();
            boolean nativeOutline = nativeShape.getRect(outlineBounds) && !outlineBounds.isEmpty();
            Rect sourceBounds = nativeOutline ? outlineBounds : nativeBounds;
            try {
                radius = ((Number)field(preview.getClass(), "mRadius").get(preview)).floatValue();
            } catch (Throwable ignored) {
                radius = nativeOutline ? nativeShape.getRadius() : 0f;
            }
            if (nativeOutline && nativeShape.getRadius() > 0f) radius = nativeShape.getRadius();
            if (radius <= 0f) {
                // GradientDrawable may not expose a round-rect Outline on
                // all OEM builds; use preview radius only when positive.
                if (folderFusionLogs++ < 12) log("GLASS124_GEOMETRY_SKIP reason=radius_unavailable type="+value.getClass().getSimpleName());
                return;
            }
            RectF rect = new RectF(offsetX + sourceBounds.left, offsetY + sourceBounds.top,
                offsetX + sourceBounds.right, offsetY + sourceBounds.bottom);
            // V1.9.115: avoid antialiased glow extending beyond OEM drawable bounds.
            // Keep refraction and edge on the same inset contour.
            final float geometryInset = 0.0f;
            if(rect.width() > 2f*geometryInset && rect.height() > 2f*geometryInset) {
                rect.inset(geometryInset, geometryInset);
                radius = Math.max(0f, radius-geometryInset);
            }
            if (rect.isEmpty() || rect.left < -1f || rect.top < -1f ||
                rect.right > v.getWidth()+1f || rect.bottom > v.getHeight()+1f) {
                if (folderFusionLogs++ < 12) log("FOLDER_FUSION_GEOMETRY_SKIP reason=out_of_view rect="+rect);
                return;
            }
            float w = rect.width(), h = rect.height();
            radius = Math.min(radius, Math.min(w,h)*0.5f);
            // Surface material is optional; the wallpaper/tint pass remains independent.
            // Edge mask is still required for enabled highlights.
            MaskCache mask=blurShapeMask(Math.round(w),Math.round(h),radius);
            if(mask==null)return;
            int save=canvas.save();
            FOLDER_FUSION_GUARD.set(Boolean.TRUE);
            try {
                if(!edgeOnly){
                    SURFACE_SUBMITTED_144.set(Boolean.TRUE);
                    drawReferenceRefraction(v,canvas,rect,radius);
                }
                canvas.translate(rect.left,rect.top);
                // The exact original glow/edge/rim/specular passes now share
                // one envelope. No separate overlay or abrupt full-strength pass.
                float envelope=1f;
                if(edgeOnly && probeMode==0 && highlightEnabled && highlightStrength>0){
                    DynamicRefraction animation=dynamicState(v);
                    envelope=1.0f; // keep the native outline static; motion sheen lives in AGSL only
                }
                if(edgeOnly || refractionPathMode169()!=2)drawUnifiedMaterial(canvas,mask,edgeOnly,envelope);
                if(folderFusionLogs++<16)log("UNIFIED_MATERIAL_DRAW phase="+(edgeOnly?"edge":"surface")+
                    " rect="+rect+" radius="+radius+" nativeOutline="+nativeOutline+
                    " geometryInsetPx=0 maskSource=corner-only-synthetic-mask cornerDeltaPx="+BLUR_CORNER_RADIUS_DELTA_PX+" alphaMask=true");
            } finally { FOLDER_FUSION_GUARD.remove(); canvas.restoreToCount(save); }
        } catch (Throwable t) {
            if (folderFusionLogs++ < 12) log("FOLDER_CANVAS_FUSION_FAILED reason="+t.getClass().getSimpleName());
        }
    }
    // Keep the surface pass directly after the OEM preview background, before icons.
    // The edge pass remains after dispatchDraw, preserving V1.9.74 corner geometry.
    private static final ThreadLocal<View> ACTIVE_FOLDER_ICON = new ThreadLocal<View>();
    private static final ThreadLocal<Boolean> PREVIEW_SURFACE_DRAWN = new ThreadLocal<Boolean>();
    private static int surfaceOrderLogs = 0;
    // Only promote a folder to background-after-blur drawing after the OEM callback
    // has been observed INSIDE its dispatchDraw. A per-view weak map avoids global
    // assumptions across different ColorOS folder implementations.
    private static final java.util.Map<View,Boolean> PREVIEW_CONFIRMED =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View,Boolean>());
    private static final ThreadLocal<Boolean> EARLY_SURFACE_DRAWN = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Matrix> FOLDER_CANVAS_MATRIX = new ThreadLocal<Matrix>();
    private static int compositionLogs = 0;
    // V1.9.122: independent visibility probe, no wallpaper or OEM blur dependency.
    // 0=off; 1=opaque markers before/after children; 2=post-children AGSL
    // with a small diagnostic clipped patch; 3=opaque post-children patch.
    private static volatile int visibility122=0;
    private static volatile long visibility122ReadAt=0L;
    private static int visibilityProbe122(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-visibility122ReadAt>1000L){
            visibility122ReadAt=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int next=Math.max(0,Math.min(3,android.provider.Settings.Global.getInt(
                        c.getContentResolver(),"liquidglass_visibility_122",0)));
                    if(next!=visibility122){visibility122=next;log("GLASS122_MODE value="+next);}
                }
            }catch(Throwable t){log("GLASS122_MODE_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return visibility122;
    }
    private static void drawVisibilityPatch122(View v,Canvas canvas,boolean after,int mode){
        if(mode==0)return;
        int saved=canvas.save();
        try{
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(after?0xFF00FF60:0xFFFF2850);
            float x=after?42f:8f;
            canvas.drawRect(x,8f,x+24f,32f,paint);
            if(after && mode==3){
                paint.setColor(0xFF00EFFF);
                canvas.drawRect(8f,38f,Math.min(v.getWidth()-8f,92f),Math.min(v.getHeight()-8f,90f),paint);
            }
            if(visibility122Logs++<90)log("GLASS122_VISIBILITY phase="+
                (after?"AFTER_CHILDREN":"BEFORE_CHILDREN")+" mode="+mode+
                " hardware="+canvas.isHardwareAccelerated()+" view="+v.getWidth()+"x"+v.getHeight());
        }finally{canvas.restoreToCount(saved);}
    }
    private static int visibility122Logs=0;
    private static void drawRefractionProbeMarker(View v,Canvas canvas,boolean after){
        // A simple opaque stripe to prove Canvas visibility independently of RuntimeShader.
        // Top left of the FolderIcon View; this marker is diagnostic only.
        int save=canvas.save();
        try{
            Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);
            marker.setColor(after?0xFF00E83A:0xFFFF2040);
            float left=after?26f:2f;
            canvas.drawRect(left,2f,left+20f,22f,marker);
            if(refractionMarkerLogs++<24)log("REFRACTION_CANVAS_MARKER phase="+(after?"AFTER_DISPATCH":"BEFORE_DISPATCH")+" hardware="+canvas.isHardwareAccelerated()+" size="+v.getWidth()+"x"+v.getHeight());
        }finally{canvas.restoreToCount(save);}
    }
    // V1.9.92: draw the proven post-dispatch AGSL surface while excluding
    // child icon bounds. This avoids washing out icons as mode 3 did.
    // OEM blur remains untouched; corner edge remains on the original pass.
    private static void drawPostDispatchRefractionIconSafe(View folder, Canvas canvas) {
        if (!glassEnabled || !REFRACTION_ENABLED || !(folder instanceof ViewGroup)) return;
        ViewGroup group=(ViewGroup)folder;
        int saved=canvas.save();
        try {
            int excluded=0;
            for(int i=0;i<group.getChildCount();i++){
                View child=group.getChildAt(i);
                if(child==null || child.getVisibility()!=View.VISIBLE || child.getWidth()<=0 || child.getHeight()<=0)continue;
                // Child hit-boxes include app icons, avoiding semi-transparent overlays.
                float x=child.getX(),y=child.getY();
                canvas.clipOutRect(x-2f,y-2f,x+child.getWidth()+2f,y+child.getHeight()+2f);
                excluded++;
            }
            REFRACTION_AFTER_OEM.set(Boolean.TRUE);
            try { drawFolderIconFusion(folder,canvas,false); }
            finally { REFRACTION_AFTER_OEM.remove(); }
            if(postSafeLogs++<30)log("REFRACTION_POST_ICON_SAFE drawn=true excludedChildren="+excluded+" hardware="+canvas.isHardwareAccelerated());
        } catch(Throwable t) {
            if(postSafeLogs++<30)log("REFRACTION_POST_ICON_SAFE failed="+t.getClass().getSimpleName());
        } finally {canvas.restoreToCount(saved);}
    }
    private static int postSafeLogs=0;
    // V1.9.117: folder-scoped native blur isolation. Default OFF for safe rollback.
    // This only intercepts the tracked LayerBlurDrawable while a FolderIcon is drawing.
    private static volatile int folderBlurIsolation = 0;
    private static volatile long folderBlurIsolationReadAt = 0L;
    private static int currentFolderBlurIsolation() {
        long now=android.os.SystemClock.uptimeMillis();
        if(now-folderBlurIsolationReadAt>1000L){
            folderBlurIsolationReadAt=now;
            try {
                android.content.Context c=launcherContext;
                if(c!=null){
                    int next=android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_folder_native_blur_isolation",0);
                    next=(next==1)?1:0;
                    if(next!=folderBlurIsolation){folderBlurIsolation=next;
                        log("FOLDER_NATIVE_BLUR_ISOLATION mode="+next);}
                }
            }catch(Throwable t){log("FOLDER_NATIVE_BLUR_ISOLATION_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return folderBlurIsolation;
    }
    private static int folderBlurSkipLogs=0;
    private static void hookTrackedFolderNativeBlur(ClassLoader loader){
        try{
            Class<?> type=Class.forName("com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable",false,loader);
            Method draw=type.getMethod("draw",Canvas.class);
            draw.setAccessible(true);
            XposedBridge.hookMethod(draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam param){
                    if(currentFolderBlurIsolation()!=1 || !glassEnabled || ACTIVE_FOLDER_ICON.get()==null)return;
                    if(!(param.thisObject instanceof Drawable))return;
                    boolean tracked;
                    synchronized(TRACKED_BLUR_BACKGROUNDS){tracked=TRACKED_BLUR_BACKGROUNDS.contains(param.thisObject);}
                    if(!tracked)return;
                    // Skip only this drawable draw, without altering OEM blur state/alpha.
                    param.setResult(null);
                    if(folderBlurSkipLogs++<40)log("FOLDER_NATIVE_BLUR_SKIPPED tracked=true folderActive=true");
                }
            });
            log("FOLDER_NATIVE_BLUR_ISOLATION_HOOK owner="+draw.getDeclaringClass().getName());
        }catch(Throwable t){log("FOLDER_NATIVE_BLUR_ISOLATION_UNAVAILABLE "+t.getClass().getSimpleName());}
    }
    // V1.9.165: read-only tracing of the *actual* FolderIcon draw chain.
    // Do not skip any OEM call or change paint/shader/Canvas state.
    private static int drawChainLogs165=0, backgroundLogs165=0;
    private static final java.util.Set<Class<?>> DRAWABLE_CLASSES_165 =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Class<?>>());
    private static void traceFolderBackground165(View v,String stage) {
        if(v==null || backgroundLogs165>=120)return;
        try {
            Drawable bg=v.getBackground();
            String parent=v.getParent()==null?"null":v.getParent().getClass().getName();
            log("FOLDER_BG_165 stage="+stage+" view="+System.identityHashCode(v)+
                " bg="+drawableTag(bg)+" alpha="+(bg==null?-1:bg.getAlpha())+
                " parent="+parent+" scroll="+(v.getParent() instanceof View?((View)v.getParent()).getScrollX():0)+
                " glass="+glassEnabled);
            backgroundLogs165++;
            if(bg!=null)hookObservedBackgroundDrawable165(bg);
        }catch(Throwable t){if(backgroundLogs165++<120)log("FOLDER_BG_165 error="+t.getClass().getSimpleName());}
    }
    private static void hookObservedBackgroundDrawable165(Drawable bg) {
        Class<?> type=bg.getClass();
        if(type==Drawable.class || type.getName().startsWith("android."))return;
        synchronized(DRAWABLE_CLASSES_165){if(!DRAWABLE_CLASSES_165.add(type))return;}
        try {
            Method draw=type.getDeclaredMethod("draw",Canvas.class);
            draw.setAccessible(true);
            XposedBridge.hookMethod(draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    View active=ACTIVE_FOLDER_ICON.get();
                    if(active!=null && drawChainLogs165++<180)
                        log("FOLDER_BG_DRAW_165 owner="+drawableTag(p.thisObject)+
                            " active="+System.identityHashCode(active)+
                            " canvasHardware="+((Canvas)p.args[0]).isHardwareAccelerated());
                }
            });
            log("FOLDER_BG_HOOK_165 installed="+draw.toGenericString());
        }catch(NoSuchMethodException e){log("FOLDER_BG_HOOK_165 inherited_or_no_draw="+type.getName());}
        catch(Throwable t){log("FOLDER_BG_HOOK_165 failed="+type.getName()+" "+t.getClass().getSimpleName());}
    }
    private static void hookFolderDrawChain165(ClassLoader loader) {
        Class<?> c=find("com.android.launcher3.folder.FolderIcon",loader);
        if(c==null){log("FOLDER_DRAW_INSTALL_165 missing_class");return;}
        for(String name:new String[]{"draw","onDraw"}) {
            try {
                Method m=c.getDeclaredMethod(name,Canvas.class);
                m.setAccessible(true);
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p){
                        if(p.thisObject instanceof View && drawChainLogs165++<180){
                            View v=(View)p.thisObject;
                            log("FOLDER_DRAW_CHAIN_165 stage=BEFORE method="+name+
                                " view="+System.identityHashCode(v)+" x="+v.getX()+
                                " screen="+screenX165(v)+" alpha="+v.getAlpha());
                            traceFolderBackground165(v,name);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        if(p.thisObject instanceof View && drawChainLogs165++<180)
                            log("FOLDER_DRAW_CHAIN_165 stage=AFTER method="+name+
                                " view="+System.identityHashCode(p.thisObject));
                    }
                });
                log("FOLDER_DRAW_INSTALL_165 installed="+m.toGenericString());
            }catch(NoSuchMethodException e){log("FOLDER_DRAW_INSTALL_165 inherited="+name+" owner="+c.getName());}
            catch(Throwable t){log("FOLDER_DRAW_INSTALL_165 failed="+name+" error="+t.getClass().getSimpleName());}
        }
    }
    private static int screenX165(View v){int[] xy=new int[2];v.getLocationOnScreen(xy);return xy[0];}
    private static void hookFolderIconCanvasFusion(ClassLoader loader) {
        Class<?> c = find("com.android.launcher3.folder.FolderIcon",loader);
        if (c == null) return;
        try {
            Method m = c.getDeclaredMethod("dispatchDraw",Canvas.class);
            m.setAccessible(true);
            XposedBridge.hookMethod(m,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View && p.args.length > 0 && p.args[0] instanceof Canvas) {
                        View v=(View)p.thisObject;
                        BOOT_FOLDER_VIEWS.put(v,Boolean.TRUE);
                        verifyOemModeAtDispatch164(v);
                        if(drawChainLogs165++<180)log("FOLDER_DRAW_CHAIN_165 stage=DISPATCH_BEFORE view="+System.identityHashCode(v)+" screen="+screenX165(v));
                        traceFolderBackground165(v,"dispatch");
                        traceTouchComposite147(v,(Canvas)p.args[0]);
                        traceComposition151(v,(Canvas)p.args[0],"before");
                        traceLayerChain162(v,(Canvas)p.args[0],"DISPATCH_BEFORE");
                        traceDragLayer166(v,(Canvas)p.args[0]);
                        traceFirstDraw149(v,"dispatch");
                        if(UNLOCK_EVENT_AT_154>0 && android.os.SystemClock.uptimeMillis()-UNLOCK_EVENT_AT_154<2500L
                                && UNLOCK_DRAW_LOGS_154++<36)
                            log("UNLOCK_DRAW_154 seq="+UNLOCK_EVENT_SEQ_154+" elapsed="+
                                (android.os.SystemClock.uptimeMillis()-UNLOCK_EVENT_AT_154)+
                                " view="+System.identityHashCode(v)+" frame="+
                                (REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled())+
                                " glass="+glassEnabled+" hardware="+((Canvas)p.args[0]).isHardwareAccelerated());

                        DRAW_CONTEXT_1761.set(new long[]{DRAW_SEQ_1761.incrementAndGet(),0,0,0,0});
                        drawTrace1761("DISPATCH_BEGIN",v,(Canvas)p.args[0],"x="+v.getX()+" y="+v.getY());
                        // A new dispatch is a new frame for this folder.
                        COMPOSITE_COUNTS_158.set(new int[2]);
                        AGSL_SUBMITTED_144.remove();
                        SURFACE_SUBMITTED_144.remove();
                        refresh(v);
                        ACTIVE_FOLDER_ICON.set(v);
                        PREVIEW_SURFACE_DRAWN.set(Boolean.FALSE);
                        EARLY_SURFACE_DRAWN.set(Boolean.FALSE);
                        Matrix folderMatrix=FOLDER_CANVAS_MATRIX.get();
                        if(folderMatrix==null){folderMatrix=new Matrix();FOLDER_CANVAS_MATRIX.set(folderMatrix);}
                        ((Canvas)p.args[0]).getMatrix(folderMatrix);
                        // V1.9.87: restore the known-working surface entry point.
                        // The OEM preview hook is not guaranteed to run inside dispatchDraw.
                        // V1.9.89: Always preserve the proven pre-background draw.
                        // A callback observed in a previous frame does not prove that
                        // it will run or successfully composite in this frame.
                        // V1.9.115: OEM blur is drawn later; avoid placing the glass beneath it.
                        // Post-dispatch surface is clipped away from app icon child regions.
                        // Independent Canvas marker: does not rely on wallpaper, AGSL, or OEM callback.
                        // Red is drawn BEFORE OEM/children; green is drawn AFTER OEM/children.
                        if(currentRefractionVisibilityTest()==1)drawRefractionProbeMarker(v,(Canvas)p.args[0],false);
                        int diag123=visibilityProbe122();
                        if(glass123GateLogs++<70)log("GLASS123_DISPATCH stage=before mode="+diag123+" glass="+glassEnabled+" frame="+(REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled())+" canvasHardware="+((Canvas)p.args[0]).isHardwareAccelerated());
                        drawVisibilityPatch122(v,(Canvas)p.args[0],false,diag123);
                        // V1.9.120: dispatchDraw is entered after View.draw has drawn its
                        // background and before children are painted. This is the stable
                        // folder-background composition slot even when the OEM preview
                        // drawBackground callback is not invoked by this launcher build.
                        // Do not use a post-children overlay: it washes out icons.
                        if(currentCompositionIsolationMode()!=2 && glassEnabled){
                            REFRACTION_AFTER_OEM.set(Boolean.TRUE);
                            try { if(layerProbe151()!=1)drawFolderIconFusion(v,(Canvas)p.args[0],false); }
                            finally { REFRACTION_AFTER_OEM.remove(); traceFirstDraw149(v,"surface"); }
                            EARLY_SURFACE_DRAWN.set(Boolean.TRUE);
                            if(GLASS_PIPELINE_DIAGNOSTICS.getAndIncrement()<64)
                                log("GLASS120_COMPOSITION stage=BEFORE_CHILDREN backgroundAlreadyDrawn=true frameReady="+
                                    (REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled())+
                                    " mode="+customBlurMode+" radiusDp="+customBlurDp);
                        }
                        if (surfaceOrderLogs++ < 20) log("REFRACTION_LAYER_ORDER mode=DISPATCH_BEFORE_CHILDREN backgroundPass=true");
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject instanceof View && p.args.length > 0 && p.args[0] instanceof Canvas) {
                            // Do not draw the surface here: it would cover app icons.
                            // V1.9.116: the OEM drawBackground callback is the only pass that
                            // can paint after native blur and before app icons. Never draw a
                            // second surface on top of children when that pass succeeded.
                            // For launchers without this callback, retain the clipped fallback.
                            // V1.9.120: never redraw glass after children in normal mode.
                            // The pre-children pass owns the complete rounded surface.
                            traceComposition151((View)p.thisObject,(Canvas)p.args[0],"after");
                            traceLayerChain162((View)p.thisObject,(Canvas)p.args[0],"DISPATCH_AFTER");
                            if(layerProbe151()!=2)drawFolderIconFusion((View)p.thisObject,(Canvas)p.args[0],true);
                            int v122=visibilityProbe122();
                            if(v122==2){
                                // Small clipped post-children AGSL diagnostic; icon overlap is intentional.
                                Canvas c122=(Canvas)p.args[0];
                                int saved122=c122.save();
                                try{
                                    c122.clipRect(8f,38f,Math.min(100f,((View)p.thisObject).getWidth()),
                                        Math.min(110f,((View)p.thisObject).getHeight()));
                                    REFRACTION_AFTER_OEM.set(Boolean.TRUE);
                                    try{drawFolderIconFusion((View)p.thisObject,c122,false);}
                                    finally{REFRACTION_AFTER_OEM.remove();}
                                    if(visibility122Logs++<90)log("GLASS122_POST_AGSL submitted=true");
                                }finally{c122.restoreToCount(saved122);}
                            }
                            drawVisibilityPatch122((View)p.thisObject,(Canvas)p.args[0],true,v122);
                            if(glass123GateLogs++<70)log("GLASS123_DISPATCH stage=after mode="+v122);
                            int probe=currentRefractionVisibilityTest();
                            // V1.9.93: do not add the post-dispatch overlay in normal mode.
                            // Mode 2 looked correct without it; keeping it would change
                            // the visual result and potentially overlay icon regions.
                            if(probe==1)drawRefractionProbeMarker((View)p.thisObject,(Canvas)p.args[0],true);
                            if(probe==3){
                                // Diagnostic ONLY: draw AGSL after all FolderIcon children.
                                // May temporarily cover icons. Never active in normal mode.
                                REFRACTION_AFTER_OEM.set(Boolean.TRUE);
                                try{drawFolderIconFusion((View)p.thisObject,(Canvas)p.args[0],false);}
                                finally{REFRACTION_AFTER_OEM.remove();}
                                if(refractionMarkerLogs++<20)log("REFRACTION_POST_DISPATCH_PROBE mode=3 executed=true");
                            }
                            boolean callback=Boolean.TRUE.equals(PREVIEW_SURFACE_DRAWN.get());
                            View icon=(View)p.thisObject;
                            if (!callback && compositionLogs++ < 24)
                                log("REFRACTION_COMPOSITION_FALLBACK reason=callback_missing early_surface_preserved=true");
                            if(surfaceOrderLogs++<12)log("REFRACTION_LAYER_ORDER mode=DISPATCH_AFTER_EDGE previewCallback="+callback);
                        }
                    } finally {
                        int[] counts158=COMPOSITE_COUNTS_158.get();
                        if(counts158!=null){
                            int mode159=canvasComposite159;
                            if(CANVAS_DISPATCH_LOG_COUNTS_159[mode159]++<72)
                                log("CANVAS_DISPATCH_159 mode="+mode159+" view="+System.identityHashCode(p.thisObject)+
                                    " attempts="+counts158[0]+" submissions="+counts158[1]+
                                    " canvas="+System.identityHashCode(p.args[0]));
                            if(compositeTraceLogs158++<240)log("COMPOSITE_DISPATCH_158 view="+System.identityHashCode(p.thisObject)+" attempts="+counts158[0]+" submissions="+counts158[1]+" mode="+compositeProbe157+" canvas="+System.identityHashCode(p.args[0]));
                            COMPOSITE_COUNTS_158.remove();
                        }
                        if(p.thisObject instanceof View && p.args.length>0 && p.args[0] instanceof Canvas)
                            drawTrace1761("DISPATCH_END",(View)p.thisObject,(Canvas)p.args[0],"complete=true");
                        DRAW_CONTEXT_1761.remove();
                        AGSL_SUBMITTED_144.remove();
                        SURFACE_SUBMITTED_144.remove();
                        ACTIVE_FOLDER_ICON.remove();
                        PREVIEW_SURFACE_DRAWN.remove();
                        EARLY_SURFACE_DRAWN.remove();
                        FOLDER_CANVAS_MATRIX.remove();
                    }
                }
            });
            installed++;
            log("FOLDER_CANVAS_FUSION_HOOK_INSTALLED method="+m.toGenericString());
        } catch(Throwable t) { failed++; log("FOLDER_CANVAS_FUSION_HOOK_FAILED reason="+t.getClass().getSimpleName()); }
    }

    private static int geometryLogs = 0;
    private static int nativeDrawLogs = 0;
    private static int contourTraceLogs = 0;
    private static int hierarchyTraceLogs = 0;
    // Trace only; do not mutate Launcher clipping or hidden blur state.
    private static void traceCanvasContour(GradientDrawable drawable, Canvas canvas) {
        if (!diagnostics || contourTraceLogs >= 24) return;
        contourTraceLogs++;
        try {
            Matrix matrix = new Matrix();
            canvas.getMatrix(matrix);
            float[] values = new float[9];
            matrix.getValues(values);
            Rect clip = canvas.getClipBounds();
            Rect bounds = drawable.getBounds();
            RectF mapped = new RectF(bounds);
            matrix.mapRect(mapped);
            Outline outline = new Outline();
            drawable.getOutline(outline);
            Rect outlineRect = new Rect();
            boolean hasOutlineRect = outline.getRect(outlineRect);
            log("CONTOUR_CANVAS_TRACE bounds=" + bounds.toShortString()
                + " clip=" + (clip == null ? "null" : clip.toShortString())
                + " matrix=" + java.util.Arrays.toString(values)
                + " mappedBounds=" + mapped.toShortString()
                + " outlineRect=" + (hasOutlineRect ? outlineRect.toShortString() : "non-rect")
                + " outlineRadius=" + outline.getRadius()
                + " outlineCanClip=" + outline.canClip());
        } catch (Throwable t) {
            log("CONTOUR_CANVAS_TRACE_FAILED reason=" + t.getClass().getSimpleName());
        }
    }
    private static void traceViewHierarchy(View view) {
        if (!diagnostics || hierarchyTraceLogs >= 12) return;
        hierarchyTraceLogs++;
        try {
            View node = view;
            for (int depth = 0; node != null && depth < 4; depth++) {
                Outline outline = new Outline();
                boolean hasOutline = false;
                try {
                    android.view.ViewOutlineProvider provider = node.getOutlineProvider();
                    if (provider != null) { provider.getOutline(node, outline); hasOutline = true; }
                } catch (Throwable ignored) {}
                Rect r = new Rect();
                boolean hasRect = hasOutline && outline.getRect(r);
                log("CONTOUR_VIEW_TRACE depth=" + depth
                    + " class=" + node.getClass().getName()
                    + " size=" + node.getWidth() + "x" + node.getHeight()
                    + " left=" + node.getLeft() + " top=" + node.getTop()
                    + " scroll=" + node.getScrollX() + "," + node.getScrollY()
                    + " translation=" + node.getTranslationX() + "," + node.getTranslationY()
                    + " scale=" + node.getScaleX() + "," + node.getScaleY()
                    + " clipToOutline=" + node.getClipToOutline()
                    + " outline=" + (hasRect ? r.toShortString() : "unavailable")
                    + " outlineRadius=" + (hasOutline ? outline.getRadius() : -1)
                    + (node instanceof ViewGroup ? " clipChildren=" + ((ViewGroup)node).getClipChildren() + " clipToPadding=" + ((ViewGroup)node).getClipToPadding() : ""));
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable t) {
            log("CONTOUR_VIEW_TRACE_FAILED reason=" + t.getClass().getSimpleName());
        }
    }
    // V1.9.5: inspect Outline as a second source of geometry. The drawable may
    // use a custom internal outline; never assume Outline equals the final mask.
    private static final class ShapeGeometry {
        final RectF rect;
        final float[] radii;
        final String source;
        ShapeGeometry(RectF rect, float[] radii, String source) {
            this.rect = rect; this.radii = radii; this.source = source;
        }
    }
    private static float[] uniformRadii(float radius) {
        float[] a = new float[8];
        java.util.Arrays.fill(a, Math.max(0f, radius));
        return a;
    }
    private static ShapeGeometry getShapeGeometry(GradientDrawable d, Rect bounds) {
        RectF rect = new RectF(bounds);
        float[] radii = d.getCornerRadii();
        if (radii != null && radii.length == 8) radii = radii.clone();
        else radii = uniformRadii(d.getCornerRadius());
        String source = "drawable";
        try {
            Outline outline = new Outline();
            d.getOutline(outline);
            Rect outlineRect = new Rect();
            if (outline.getRect(outlineRect) && !outlineRect.isEmpty()) {
                // An Outline can describe the drawable's actual outer contour.
                // Prefer it only if it is close to drawable bounds, avoiding
                // unrelated clipping rectangles on vendor implementations.
                if (Math.abs(outlineRect.left-bounds.left)<=2 &&
                    Math.abs(outlineRect.top-bounds.top)<=2 &&
                    Math.abs(outlineRect.right-bounds.right)<=2 &&
                    Math.abs(outlineRect.bottom-bounds.bottom)<=2) {
                    float outlineRadius = outline.getRadius();
                    if (outlineRadius > 0f) {
                        rect = new RectF(outlineRect);
                        radii = uniformRadii(outlineRadius);
                        source = "outline-roundrect";
                    }
                }
            }
            if (geometryLogs++ < 16) {
                log("GLASS_OUTLINE_PROBE bounds="+bounds.toShortString()+
                    " outlineRect="+(outline.getRect(outlineRect)?outlineRect.toShortString():"non-rect")+
                    " outlineRadius="+outline.getRadius()+
                    " drawableRadius="+d.getCornerRadius()+
                    " drawableRadii="+java.util.Arrays.toString(d.getCornerRadii())+
                    " source="+source);
            }
        } catch (Throwable t) {
            if (geometryLogs++ < 16) log("GLASS_OUTLINE_PROBE_FAILED reason="+t.getClass().getSimpleName());
        }
        return new ShapeGeometry(rect,radii,source);
    }
    private static Path shapePath(ShapeGeometry g, float inset) {
        RectF r = new RectF(g.rect);
        r.inset(inset,inset);
        Path path = new Path();
        if (r.width() <= 0 || r.height() <= 0) return path;
        float[] adjusted = new float[8];
        for (int i=0;i<8;i++) adjusted[i] = Math.max(0f,g.radii[i]-inset);
        path.addRoundRect(r, adjusted, Path.Direction.CW);
        return path;
    }
    // V1.9.8: never approximate the vendor's contour with Path.addRoundRect.
    // Render the SAME drawable to an offscreen alpha mask, then extract a narrow
    // inward-facing edge directly from its pixels. Cache by drawable and bounds.
    private static final ThreadLocal<Boolean> MASK_RENDERING = new ThreadLocal<Boolean>();
    private static final java.util.Map<Drawable, MaskCache> MASKS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Drawable, MaskCache>());
    private static final class MaskCache {
        final int w, h, alpha, radiusHash, width;
        final android.graphics.Bitmap mask, edge, glow;
        MaskCache(int w,int h,int a,int rh,int width,android.graphics.Bitmap m,android.graphics.Bitmap e,android.graphics.Bitmap g){
            this.w=w;this.h=h;this.alpha=a;this.radiusHash=rh;this.width=width;this.mask=m;this.edge=e;this.glow=g;
        }
    }
    private static MaskCache nativeMask(GradientDrawable d) {
        Rect b=d.getBounds();
        int w=b.width(),h=b.height();
        if(w<4||h<4||w>1400||h>1400)return null;
        int rh=Float.floatToIntBits(d.getCornerRadius()) ^ java.util.Arrays.hashCode(d.getCornerRadii());
        MaskCache old=MASKS.get(d);
        if(old!=null && old.w==w && old.h==h && old.alpha==d.getAlpha() && old.radiusHash==rh && old.width==highlightWidth)return old;
        android.graphics.Bitmap bitmap=null, edge=null, glow=null;
        Rect original=new Rect(b);
        try {
            bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(bitmap);
            MASK_RENDERING.set(true);
            d.setBounds(0,0,w,h);
            d.draw(c);
            d.setBounds(original);
            MASK_RENDERING.remove();
            int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
            // Two inward-facing layers, both derived from the OEM drawable alpha.
            // Out-of-bitmap samples are transparent. Avoid drawing any new round rect.
            int[] edgePixels=new int[w*h];
            int[] glowPixels=new int[w*h];
            int width=Math.max(1,Math.min(5,highlightWidth));
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=y*w+x, center=pixels[i]>>>24;
                if(center<8)continue;
                int near=255, far=255;
                for(int dy=-width;dy<=width;dy++)for(int dx=-width;dx<=width;dx++){
                    int dist=Math.max(Math.abs(dx),Math.abs(dy));
                    if(dist==0)continue;
                    int xx=x+dx, yy=y+dy;
                    int sample=(xx<0||xx>=w||yy<0||yy>=h)?0:(pixels[yy*w+xx]>>>24);
                    if(dist==1 && sample<near)near=sample;
                    if(sample<far)far=sample;
                }
                int sharp=Math.max(0,Math.min(center,(center-near)*2));
                int soft=Math.max(0,Math.min(center,(center-far)));
                // The core is thin and sharp; the inner layer adds adjustable body.
                edgePixels[i]=(sharp<<24)|0x00ffffff;
                glowPixels[i]=(soft<<24)|0x00ffffff;
            }
            edge=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            edge.setPixels(edgePixels,0,w,0,0,w,h);
            glow=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            glow.setPixels(glowPixels,0,w,0,0,w,h);
            MaskCache result=new MaskCache(w,h,d.getAlpha(),rh,width,bitmap,edge,glow);
            MASKS.put(d,result);
            if(nativeDrawLogs++<20)log("NATIVE_ALPHA_MASK_READY size="+w+"x"+h+" radiusHash="+rh);
            return result;
        }catch(Throwable t){
            if(nativeDrawLogs++<12)log("NATIVE_ALPHA_MASK_FAILED reason="+t.getClass().getSimpleName());
            return null;
        }finally{
            try{d.setBounds(original);}catch(Throwable ignored){}
            MASK_RENDERING.remove();
        }
    }
    // V1.9.14: self-rendered optical material. No screen capture, no native blur
    // flags, no synthetic round-rect. The OEM alpha mask remains the sole shape.
    // This is a physically-inspired glass sheen, NOT a true backdrop blur.
    private static void drawOpticalGlass(Canvas canvas, MaskCache cache) {
        if (cache == null || cache.w < 8 || cache.h < 8 || glassTintOpacity <= 0) return;
        Paint layerOpacity = new Paint(Paint.ANTI_ALIAS_FLAG);
        layerOpacity.setAlpha(Math.round(255f * Math.min(1f, glassTintOpacity / 30f)));
        int layer = canvas.saveLayer(0, 0, cache.w, cache.h, layerOpacity);
        try {
            Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            // Low opacity central illumination and darker lower edge add depth
            // without covering the icon content or changing OEM geometry.
            glass.setShader(new android.graphics.LinearGradient(
                0, 0, cache.w * 0.72f, cache.h,
                new int[]{0x31FFFFFF, 0x12DCEEFF, 0x08FFFFFF, 0x1B7E9BB4},
                new float[]{0f, 0.30f, 0.68f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, cache.w, cache.h, glass);
            glass.setShader(null);
            // A broad curved reflection with very low opacity.
            glass.setShader(new android.graphics.RadialGradient(
                cache.w * 0.12f, cache.h * 0.08f,
                Math.max(cache.w, cache.h) * 0.9f,
                new int[]{0x24FFFFFF, 0x0AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.50f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, cache.w, cache.h, glass);
            glass.setShader(null);
            // Mask the entire optical material to the real OEM alpha shape.
            glass.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
            canvas.drawBitmap(cache.mask, 0, 0, glass);
            glass.setXfermode(null);
        } finally {
            canvas.restoreToCount(layer);
        }
    }
    private static void drawMaterialOnNativeCanvas(GradientDrawable drawable, Canvas canvas) {
        if(Boolean.TRUE.equals(MASK_RENDERING.get()) || !glassEnabled || probeMode==1)return;
        boolean tint=tintMode!=0 && tintStrength>0 && probeMode!=2;
        boolean shine=(highlightEnabled && highlightStrength>0 && probeMode!=3)||probeMode==2;
        // Optical layer is active whenever the glass switch is on, except probe modes.
        boolean optical=probeMode==0;
        if(!tint&&!shine&&!optical)return;
        MaskCache cache=nativeMask(drawable);
        if(cache==null)return;
        Rect b=drawable.getBounds();
        canvas.save();
        canvas.translate(b.left,b.top);
        drawUnifiedMaterial(canvas,cache,false);
        drawUnifiedMaterial(canvas,cache,true);
        canvas.restore();
    }
    private static void hookNativeDrawableDraw() {
        try {
            Method method = GradientDrawable.class.getDeclaredMethod("draw", Canvas.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(MASK_RENDERING.get())) return;
                    if (!(param.thisObject instanceof Drawable) || param.args == null || param.args.length == 0 ||
                            !(param.args[0] instanceof Canvas)) return;
                    Drawable drawable = (Drawable) param.thisObject;
                    synchronized (TRACKED_BACKGROUNDS) {
                        if (!TRACKED_BACKGROUNDS.contains(drawable)) return;
                    }
                    try {
                        if (diagnostics && layerTraceLogs++ < 48) {
                            GradientDrawable gd=(GradientDrawable)drawable;
                            log("LAYER_DRAW id="+drawableTag(drawable)+" tracked=true previewDepth="+
                                (PREVIEW_DRAW_DEPTH.get()==null?0:PREVIEW_DRAW_DEPTH.get())+
                                " bounds="+gd.getBounds().toShortString()+" alpha="+gd.getAlpha()+
                                " radius="+gd.getCornerRadius()+" mode="+probeMode);
                        }
                        traceCanvasContour((GradientDrawable)drawable, (Canvas)param.args[0]);
                        drawMaterialOnNativeCanvas((GradientDrawable)drawable, (Canvas)param.args[0]); }
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
                        if(draw && "FolderIcon".equals(label) && param.thisObject instanceof View && param.args.length>0 && param.args[0] instanceof Canvas)
                            drawWallpaperVisualComparison((View)param.thisObject,(Canvas)param.args[0]);
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
    // V1.9.11: passive only. Never call blur initialization, mutate flags, or blur icon views.
    private static int blurStateLogs=0;
    private static final Set<Object> BLUR_SEEN=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    private static String safeValue(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean || value instanceof Number || value instanceof String) {
            String s=String.valueOf(value);return s.length()>100?s.substring(0,100):s;
        }
        if(value instanceof Drawable)return drawableTag(value)+" alpha="+((Drawable)value).getAlpha();
        return drawableTag(value);
    }
    private static void inspectBlurState(Object preview, String source) {
        if(!diagnostics || preview==null)return;
        synchronized(BLUR_SEEN){if(!BLUR_SEEN.add(preview))return;}
        if(blurStateLogs++>16)return;
        log("BLUR_PROBE_INSTANCE source="+source+" class="+preview.getClass().getName());
        int count=0;
        for(Class<?> k=preview.getClass();k!=null && count<32;k=k.getSuperclass()){
            for(Field f:k.getDeclaredFields()){
                String n=f.getName().toLowerCase(java.util.Locale.ROOT);
                if(!(n.contains("blur")||n.contains("background")||n.contains("drawable")||n.contains("support")))continue;
                if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
                try{f.setAccessible(true);log("BLUR_PROBE_FIELD "+f.getName()+" type="+f.getType().getName()+" value="+safeValue(f.get(preview)));}
                catch(Throwable e){log("BLUR_PROBE_FIELD_ERROR "+f.getName()+" "+e.getClass().getSimpleName());}
                if(++count>=32)break;
            }
        }
    }
    // V147: low-volume native-background state sampling at the real refraction
    // entry. This does not alter the OEM drawable or the AGSL optical output.
    private static final java.util.Map<View, String> TOUCH_COMPOSITE_STATE_147 =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());
    private static int touchCompositeLogs147=0;
    private static void traceTouchComposite147(View v, Canvas canvas) {
        if(touchCompositeLogs147>=120)return;
        try {
            Drawable bg=v.getBackground();
            Object nativeBg=null;
            try { Field f=field(v.getClass(),"mBackground"); nativeBg=f.get(v); }
            catch(Throwable ignored){}
            String state="pressed="+v.isPressed()+" selected="+v.isSelected()+
                " alpha="+v.getAlpha()+" visibility="+v.getVisibility()+
                " translation="+v.getTranslationX()+","+v.getTranslationY()+
                " native="+(nativeBg==null?"null":nativeBg.getClass().getSimpleName())+
                " drawableAlpha="+(bg==null?-1:bg.getAlpha())+
                " refraction="+REFRACTION_ENABLED+" frame="+(REFRACTION_FRAME!=null)+
                " hw="+canvas.isHardwareAccelerated();
            String prior=TOUCH_COMPOSITE_STATE_147.put(v,state);
            if(!state.equals(prior) && touchCompositeLogs147++<120)
                log("TOUCH_COMPOSITE_147 view="+System.identityHashCode(v)+" "+state);
        }catch(Throwable ignored){}
    }
    private static void inspectFolderBlur(View v) {
        if(!diagnostics || blurStateLogs>24)return;
        try{
            Object flag=field(v.getClass(),"mIsSupportNewBlurLocal").get(v);
            if(blurStateLogs++<20)log("BLUR_PROBE_FOLDER class="+v.getClass().getName()+" localSupport="+safeValue(flag)+" size="+v.getWidth()+"x"+v.getHeight());
            Object preview=field(v.getClass(),"mBackground").get(v);
            inspectBlurState(preview,"FolderIcon");
            traceConditions(v,"folderIcon");
            traceConditions(preview,"preview");
        }catch(Throwable e){if(blurStateLogs++<8)log("BLUR_PROBE_FOLDER_ERROR="+e.getClass().getSimpleName());}
    }
    // V151: unconditional first entry + bounded periodic composition sampling.
    private static final java.util.Map<View,long[]> COMPOSITION_STATE_151 =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View,long[]>());
    private static int compositionLogs151=0;
    private static volatile int layerProbe151=0;
    private static volatile long layerProbeRead151=0;
    private static int layerProbe151(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-layerProbeRead151>500L){
            layerProbeRead151=now;
            try { if(launcherContext!=null)layerProbe151=android.provider.Settings.Global.getInt(
                launcherContext.getContentResolver(),"liquidglass_layer_probe_151",0); }
            catch(Throwable ignored){}
        }
        return layerProbe151;
    }
    private static void traceComposition151(View v, Canvas c, String stage){
        try {
            long now=android.os.SystemClock.uptimeMillis();
            long[] state=COMPOSITION_STATE_151.get(v);
            if(state==null){state=new long[]{0L,0L};COMPOSITION_STATE_151.put(v,state);
                log("COMPOSITION_151_INIT view="+System.identityHashCode(v)+" stage="+stage);}
            if(compositionLogs151>=280 || now-state[0]<120L)return;
            state[0]=now;
            StringBuilder sb=new StringBuilder("COMPOSITION_151 stage=").append(stage)
                .append(" view=").append(System.identityHashCode(v))
                .append(" uptime=").append(now).append(" canvasHW=").append(c.isHardwareAccelerated())
                .append(" frame=").append(REFRACTION_FRAME!=null&&!REFRACTION_FRAME.isRecycled())
                .append(" blurDp=").append(customBlurDp)
                .append(" probe=").append(layerProbe151());
            View cur=v;
            for(int i=0;i<5 && cur!=null;i++){
                sb.append(" |p").append(i).append('=')
                    .append(cur.getClass().getSimpleName()).append('@').append(System.identityHashCode(cur))
                    .append(" a=").append(cur.getAlpha()).append(" l=").append(cur.getLayerType())
                    .append(" xy=").append(cur.getTranslationX()).append(',').append(cur.getTranslationY())
                    .append(" scroll=").append(cur.getScrollX()).append(',').append(cur.getScrollY())
                    .append(" pressed=").append(cur.isPressed());
                android.view.ViewParent parent=cur.getParent();
                cur=parent instanceof View?(View)parent:null;
            }
            if(compositionLogs151++<280)log(sb.toString());
        }catch(Throwable t){if(compositionLogs151++<280)log("COMPOSITION_151_ERROR "+t.getClass().getSimpleName());}
    }
    // V149: opt-in native preview draw isolation. Default 0 never changes OEM behavior.
    // 1 skips only OplusPreviewBackground.drawBackground while retaining AGSL dispatch.
    private static volatile int previewIsolation149=0;
    private static volatile long previewIsolationRead149=0L;
    private static int previewIsolation149(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-previewIsolationRead149>600L){
            previewIsolationRead149=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int mode=android.provider.Settings.Global.getInt(c.getContentResolver(),
                        "liquidglass_native_preview_isolation_149",0)==1?1:0;
                    if(mode!=previewIsolation149){previewIsolation149=mode;log("PREVIEW_ISOLATION_149 mode="+mode);}
                }
            }catch(Throwable ignored){}
        }
        return previewIsolation149;
    }
    private static final java.util.Map<View,long[]> FIRST_DRAW_149 =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View,long[]>());
    private static int firstDrawLogs149=0;
    private static void traceFirstDraw149(View v, String stage){
        if(firstDrawLogs149>=160)return;
        try{
            long now=android.os.SystemClock.uptimeMillis();
            long[] a=FIRST_DRAW_149.get(v);
            if(a==null){a=new long[]{now,0,0,0};FIRST_DRAW_149.put(v,a);}
            boolean frame=REFRACTION_FRAME!=null&&!REFRACTION_FRAME.isRecycled();
            int slot="dispatch".equals(stage)?1:("surface".equals(stage)?2:3);
            if(a[slot]!=0)return;
            if(slot==2&&!frame)return;
            a[slot]=now;
            if(firstDrawLogs149++<160)log("FIRST_DRAW_149 stage="+stage+
                " view="+System.identityHashCode(v)+" elapsed="+(now-a[0])+
                " frame="+frame+" configEpoch="+REFRACTION_CONFIG_EPOCH+
                " glass="+glassEnabled+" refraction="+REFRACTION_ENABLED+
                " blurDp="+customBlurDp+" attached="+v.isAttachedToWindow());
        }catch(Throwable ignored){}
    }
    // V1.9.163: OEM preview isolation correlated with Workspace page offset.
    // 0=untouched; 1=skip OEM preview only between page resting positions;
    // 2=skip OEM preview at all positions. AGSL is never disabled here.
    private static volatile int oemScrollMode163=0;
    private static volatile long oemScrollModeRead163=0L;
    private static int[] oemScrollLogCount163=new int[3];
    private static int oemScrollMode163(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-oemScrollModeRead163>400L){
            oemScrollModeRead163=now;
            try{
                android.content.Context ctx=launcherContext;
                if(ctx!=null){
                    int v=android.provider.Settings.Global.getInt(ctx.getContentResolver(),
                        "liquidglass_oem_scroll_isolation_163",0);
                    if(v<0||v>2)v=0;
                    if(v!=oemScrollMode163){oemScrollMode163=v;log("OEM_SCROLL_MODE_163 mode="+v);}
                }
            }catch(Throwable ignored){}
        }
        return oemScrollMode163;
    }
    // V1.9.164: diagnostics independent of the optional OEM drawBackground callback.
    private static int oemVerifyDispatchLogs164=0;
    private static int oemVerifyEntryLogs164=0;
    private static int oemVerifySkipLogs164=0;
    private static void verifyOemModeAtDispatch164(View view){
        try {
            int mode=oemScrollMode163();
            if(oemVerifyDispatchLogs164++<120){
                int[] off=workspaceOffset163();
                log("OEM_VERIFY_DISPATCH_164 mode="+mode+" context="+(launcherContext!=null)+
                    " glass="+glassEnabled+" scrollX="+off[0]+" width="+off[1]+
                    " offset="+off[2]+" view="+System.identityHashCode(view));
            }
        }catch(Throwable t){if(oemVerifyDispatchLogs164++<120)log("OEM_VERIFY_DISPATCH_164 error="+t.getClass().getSimpleName());}
    }
    private static int[] workspaceOffset163(){
        View v=ACTIVE_FOLDER_ICON.get();
        if(v==null){
            synchronized(BOOT_FOLDER_VIEWS){
                for(View candidate:BOOT_FOLDER_VIEWS.keySet()){
                    if(candidate!=null && candidate.isAttachedToWindow()){v=candidate;break;}
                }
            }
        }
        while(v!=null){
            if(v.getClass().getName().contains("Workspace")){
                int w=v.getWidth();
                int scroll=v.getScrollX();
                if(w<=0)return new int[]{scroll,w,-1};
                int remainder=Math.floorMod(scroll,w);
                int distance=Math.min(remainder,w-remainder);
                return new int[]{scroll,w,distance};
            }
            android.view.ViewParent parent=v.getParent();
            v=parent instanceof View?(View)parent:null;
        }
        return new int[]{0,0,-1};
    }
    private static boolean skipOemForScroll163(String stage){
        int mode=oemScrollMode163();
        if(mode==0||!glassEnabled)return false;
        int[] offset=workspaceOffset163();
        // Unknown workspace: fail open in mode 1 rather than disable OEM globally.
        boolean skip=mode==2 || (offset[2]>=0 && offset[2]>Math.max(12,offset[1]/100));
        if(oemScrollLogCount163[mode]++<120)
            log("OEM_SCROLL_DECISION_163 stage="+stage+" mode="+mode+" skip="+skip+
                " scrollX="+offset[0]+" width="+offset[1]+" pageOffset="+offset[2]+
                " glass="+glassEnabled+" icon="+(ACTIVE_FOLDER_ICON.get()!=null));
        return skip;
    }
    private static int previewIsolationLogs149=0;
    private static void hookPreview(ClassLoader loader) {
        Class<?> c = find("com.android.launcher3.folder.OplusPreviewBackground", loader);
        if (c == null) {log("OEM_VERIFY_INSTALL_164 class=missing");return;}
        int drawCandidates164=0;
        int drawInstalled164=0;
        log("OEM_VERIFY_INSTALL_164 class="+c.getName()+" declared="+c.getDeclaredMethods().length);
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if("drawBackground".equals(name))drawCandidates164++;
            boolean target = ("drawBackground".equals(name) && m.getParameterTypes().length == 2)
                    || ("getBgDrawable".equals(name) && m.getParameterTypes().length == 0)
                    || ("getBlurProp".equals(name) && m.getParameterTypes().length == 0)
                    || ("initBlurDrawable".equals(name))
                    || ("isBgSupportNewBlur".equals(name))
                    || (name.toLowerCase(java.util.Locale.ROOT).contains("blur") && m.getParameterTypes().length<=3);
            if (!target) continue;
            try {
                m.setAccessible(true);
                final String methodName = name;
                if("drawBackground".equals(name))log("OEM_VERIFY_INSTALL_164 attempt="+m.toGenericString());
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!"drawBackground".equals(methodName)) return;
                        int verifiedMode164=oemScrollMode163();
                        if(oemVerifyEntryLogs164++<120)log("OEM_VERIFY_ENTRY_164 method="+param.method+
                            " mode="+verifiedMode164+" glass="+glassEnabled);
                        boolean skip149=previewIsolation149()==1 && glassEnabled;
                        boolean skip163=skipOemForScroll163("BEFORE");
                        if(skip149 || skip163){
                            if(oemVerifySkipLogs164++<120)log("OEM_VERIFY_SKIP_164 old="+skip149+" new="+skip163+" mode="+verifiedMode164);
                            if(previewIsolationLogs149++<80)log("PREVIEW_SKIP_149_163 method=drawBackground");
                            param.setResult(null);
                            return;
                        }
                        Integer depth=PREVIEW_DRAW_DEPTH.get();
                        PREVIEW_DRAW_DEPTH.set(depth==null?1:depth+1);
                        if (diagnostics && previewTraceLogs++ < 32) {
                            String args="";
                            for (Object a:param.args) args+= " " + drawableTag(a);
                            log("PREVIEW_DRAW_ENTER owner="+drawableTag(param.thisObject)+" method="+
                                ((Method)param.method).toGenericString()+" args="+args+
                                " depth="+PREVIEW_DRAW_DEPTH.get());
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if ("drawBackground".equals(methodName)) {
                                if((previewIsolation149()==1 && glassEnabled) || skipOemForScroll163("AFTER"))return;
                                // Draw on the preview's actual Canvas after its OEM background has been drawn.
                                // This avoids hooking an inherited/nonexistent LayerBlurDrawable.draw method.
                                Canvas targetCanvas=null;
                                if (param.args!=null) for(Object arg:param.args) {
                                    if (arg instanceof Canvas) { targetCanvas=(Canvas)arg; break; }
                                }
                                // V1.9.86: draw the refractive surface after OEM blur/background
                                // and before FolderIcon's children. Motion must not toggle the pass.
                                View activeIcon=ACTIVE_FOLDER_ICON.get();
                                if(currentCompositionIsolationMode()!=1 && targetCanvas!=null && activeIcon!=null &&
                                        !Boolean.TRUE.equals(PREVIEW_SURFACE_DRAWN.get()) &&
                                        !Boolean.TRUE.equals(EARLY_SURFACE_DRAWN.get())) {
                                    // Mark successful only after a usable callback canvas/matrix.
                                    // Draw again AFTER the OEM background when a valid
                                    // callback Canvas exists. The earlier surface may have
                                    // been occluded by the OEM background, so it is not a
                                    // substitute for this later composition pass.
                                    {
                                        Matrix folder=FOLDER_CANVAS_MATRIX.get();
                                        Matrix callback=new Matrix();
                                        Matrix inverse=new Matrix();
                                        targetCanvas.getMatrix(callback);
                                        if(folder!=null && callback.invert(inverse)) {
                                            // Convert FolderIcon-local drawing into the OEM
                                            // callback's local Canvas coordinate system.
                                            Matrix localToCallback=new Matrix();
                                            localToCallback.setConcat(inverse,folder);
                                            int save=targetCanvas.save();
                                            try {
                                                targetCanvas.concat(localToCallback);
                                                REFRACTION_AFTER_OEM.set(Boolean.TRUE);
                                                try { drawFolderIconFusion(activeIcon,targetCanvas,false); }
                                                finally { REFRACTION_AFTER_OEM.remove(); }
                                                PREVIEW_SURFACE_DRAWN.set(Boolean.TRUE);
                                                if(compositionLogs++<24)log("REFRACTION_COMPOSITION mode=AFTER_OEM_BACKGROUND beforeChildren=true");
                                            } finally { targetCanvas.restoreToCount(save); }
                                        } else {
                                            PREVIEW_CONFIRMED.remove(activeIcon);
                                            if(compositionLogs++<24)log("REFRACTION_COMPOSITION_FALLBACK reason=matrix_unavailable");
                                        }
                                    }
                                    // Do not treat callback observation alone as proof
                                    // that a refractive surface was actually composited.
                                }
                                if (false && targetCanvas!=null && glassEnabled && probeMode!=1) { // V1.9.25: legacy overlay disabled
                                    Object bg=null;
                                    try {
                                        Method getter=param.thisObject.getClass().getDeclaredMethod("getBgDrawable");
                                        getter.setAccessible(true);
                                        bg=getter.invoke(param.thisObject);
                                    } catch(Throwable ignored) {}
                                    if (bg instanceof Drawable && bg.getClass().getName().endsWith(".LayerBlurDrawable")) {
                                        try { drawBlurGlassOverlay((Drawable)bg,targetCanvas); }
                                        catch(Throwable e) { if(blurFusionLogs++<10) log("BLUR_FUSION_FAILED reason="+e.getClass().getSimpleName()); }
                                    } else if(blurFusionLogs++<5) log("BLUR_FUSION_SKIPPED reason=not_blur_drawable class="+drawableTag(bg));
                                } else if(blurFusionLogs++<5) log("BLUR_FUSION_SKIPPED reason="+(targetCanvas==null?"no_canvas":"legacy_overlay_disabled"));
                                if (diagnostics && previewTraceLogs++ < 32) {
                                    Object result=null;
                                    try { Method bg=param.thisObject.getClass().getDeclaredMethod("getBgDrawable"); bg.setAccessible(true); result=bg.invoke(param.thisObject); }
                                    catch(Throwable ignored) {}
                                    log("PREVIEW_DRAW_EXIT owner="+drawableTag(param.thisObject)+
                                        " background="+drawableTag(result)+" depth="+PREVIEW_DRAW_DEPTH.get());
                                }
                                if (backgroundCalls++ < 12) log("BG_DRAW_RUNTIME signature=" + ((Method)param.method).toGenericString()
                                        + " arg1=" + (param.args[1] == null ? "null" : param.args[1].getClass().getName()));
                            } else if ("getBgDrawable".equals(methodName)) {
                                if (drawableCalls++ < 12) {
                                    Object result = param.getResult();
                                    log("BG_DRAWABLE_RUNTIME result=" + (result == null ? "null" : result.getClass().getName())
                                            + (result instanceof Drawable ? " alpha=" + ((Drawable)result).getAlpha() : ""));
                                }
                            } else if (blurCalls++ < 48) {
                                Object result = param.getResult();
                                StringBuilder args=new StringBuilder();
                                if(param.args!=null)for(Object arg:param.args)args.append(" ").append(safeValue(arg));
                                log("NATIVE_BLUR_CALL method="+methodName+" signature="+((Method)param.method).toGenericString()+" result="+safeValue(result)+" args="+args);
                                inspectBlurState(param.thisObject,"method:"+methodName);
                                traceConditions(param.thisObject,"method:"+methodName);
                            }
                        } catch (Throwable t) { log("BG_RUNTIME_ERROR=" + t.getClass().getSimpleName()); }
                        finally {
                            if ("drawBackground".equals(methodName)) {
                                Integer depth=PREVIEW_DRAW_DEPTH.get();
                                if (depth==null || depth<=1) PREVIEW_DRAW_DEPTH.remove();
                                else PREVIEW_DRAW_DEPTH.set(depth-1);
                            }
                        }
                    }
                });
                installed++;
                if("drawBackground".equals(name)){drawInstalled164++;log("OEM_VERIFY_INSTALL_164 success="+m.toGenericString());}
                log("HOOK_INSTALLED class=" + c.getName() + " method=" + m.toGenericString());
            } catch (Throwable t) { failed++; log("HOOK_FAILED class=" + c.getName() + " method=" + m.toGenericString() + " error=" + t); }
        }
        log("OEM_VERIFY_INSTALL_164 summary candidates="+drawCandidates164+" installed="+drawInstalled164+" (see success records)");
    }
    // V1.9.12: read-only condition tracing. No forced blur flags or initialization.
    private static final java.util.Map<Object,String> BLUR_SNAPSHOTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object,String>());
    private static final java.util.Set<String> BLUR_METHODS_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static int conditionLogs = 0;
    private static String conditionSnapshot(Object owner) {
        if (owner == null) return "null";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Class<?> c=owner.getClass();c!=null && count<24;c=c.getSuperclass()) {
            for (Field f:c.getDeclaredFields()) {
                String n=f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!n.contains("blur") && !n.contains("support") && !n.contains("effect") && !n.contains("background")) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v=f.get(owner);
                    if (v instanceof Boolean || v instanceof Number || v == null || v instanceof Drawable) {
                        sb.append(f.getName()).append('=').append(safeValue(v)).append(';');
                        count++;
                    }
                } catch (Throwable ignored) {}
                if (count>=24) break;
            }
        }
        return sb.toString();
    }
    private static void traceConditions(Object owner, String source) {
        if (!diagnostics || owner==null) return;
        String snap=conditionSnapshot(owner);
        String prior;
        synchronized(BLUR_SNAPSHOTS) { prior=BLUR_SNAPSHOTS.put(owner,snap); }
        if (!snap.equals(prior) && conditionLogs++<75)
            log("BLUR_CONDITION_CHANGE source="+source+" owner="+drawableTag(owner)+" state="+snap);
    }
    private static void hookFolderConditions(ClassLoader loader) {
        Class<?> folder=find("com.android.launcher3.folder.FolderIcon",loader);
        if (folder==null)return;
        for (Method m:folder.getDeclaredMethods()) {
            String name=m.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("blur") && !name.contains("support"))continue;
            if (m.getParameterTypes().length>4)continue;
            try {
                m.setAccessible(true);
                final String signature=m.toGenericString();
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        try {
                            if (conditionLogs++<75)log("BLUR_FOLDER_METHOD signature="+signature+" result="+safeValue(p.getResult()));
                            traceConditions(p.thisObject,"folderMethod");
                        }catch(Throwable ignored){}
                    }
                });
                installed++;
                log("BLUR_CONDITION_HOOK_INSTALLED "+signature);
            } catch(Throwable e){failed++;log("BLUR_CONDITION_HOOK_FAILED "+m.toGenericString()+" "+e.getClass().getSimpleName());}
        }
    }
    private static void traceBlurMethods(ClassLoader loader) {
        Class<?> preview=find("com.android.launcher3.folder.OplusPreviewBackground",loader);
        if (preview==null)return;
        for (Method m:preview.getDeclaredMethods()) {
            String name=m.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("blur") && !name.contains("support"))continue;
            if (m.getParameterTypes().length>4)continue;
            String signature=m.toGenericString();
            if(BLUR_METHODS_SEEN.add(signature))log("BLUR_METHOD_CATALOG "+signature);
        }
    }
    // V1.9.13: read-only decision-chain snapshots; no global framework hooks.
    private static final java.util.Set<String> DECISION_ONCE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static String decisionStack() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        for (StackTraceElement frame : frames) {
            String owner = frame.getClassName();
            if (owner.equals(Thread.class.getName()) || owner.equals(HookEntry.class.getName())
                    || owner.startsWith("de.robv.android.xposed") || owner.startsWith("java.lang.reflect")) continue;
            if (emitted++ >= 12) break;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(owner).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
        }
        return sb.toString();
    }
    private static String decisionArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<args.length && i<5;i++) {
            if (i>0) sb.append(',');
            Object v=args[i];
            if (v instanceof android.content.Context) {
                android.content.Context c=(android.content.Context)v;
                sb.append("Context:").append(c.getClass().getSimpleName());
            } else sb.append(safeValue(v));
        }
        return sb.append(']').toString();
    }
    private static void decisionSnapshot(String stage, String name, Object owner, Object[] args, Object result) {
        if (!diagnostics) return;
        String key = stage + ':' + name + ':' + safeValue(result);
        if (!DECISION_ONCE.add(key)) return;
        if (DECISION_ONCE.size() > 36) return;
        log("BLUR_DECISION_"+stage+" method="+name+" owner="+drawableTag(owner)
                +" args="+decisionArgs(args)+" result="+safeValue(result));
        log("BLUR_DECISION_STACK method="+name+" stage="+stage+" frames="+decisionStack());
        if (owner != null) traceConditions(owner,"decision:"+name);
    }
    private static void hookDecisionChain(ClassLoader loader) {
        String[] owners = {
                "com.android.launcher3.folder.FolderIcon",
                "com.android.launcher3.folder.OplusPreviewBackground"
        };
        for (String ownerName : owners) {
            Class<?> owner = find(ownerName,loader);
            if (owner==null) continue;
            for (final Method m : owner.getDeclaredMethods()) {
                String n=m.getName();
                if (!(n.equals("isSupportNewBlurLocal") || n.equals("isBgSupportNewBlur")
                        || n.equals("initBlurDrawable") || n.equals("getBlurProp")
                        || n.equals("updateBlurDrawableColor"))) continue;
                try {
                    m.setAccessible(true);
                    final String signature=m.toGenericString();
                    XposedBridge.hookMethod(m,new XC_MethodHook(){
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try { decisionSnapshot("ENTER",signature,p.thisObject,p.args,null); }
                            catch(Throwable ignored) {}
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try { decisionSnapshot("EXIT",signature,p.thisObject,p.args,p.getResult()); }
                            catch(Throwable ignored) {}
                        }
                    });
                    installed++;
                    log("BLUR_DECISION_HOOK_INSTALLED "+signature);
                } catch(Throwable e) {
                    failed++;
                    log("BLUR_DECISION_HOOK_FAILED "+m.toGenericString()+" "+e.getClass().getSimpleName());
                }
            }
        }
        // Enumerate candidate blur property APIs without invoking constructors or methods.
        Class<?> prop = find("com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties",loader);
        if (prop!=null) {
            int count=0;
            for (Method m:prop.getDeclaredMethods()) {
                if(count++>=24)break;
                log("BLUR_PROPERTIES_API "+m.toGenericString());
            }
        }
    }

    // V1.9.121: opt-in A/B test of the original ColorOS material switch.
    // Mode 0 observes OEM return; mode 1 restores the historical forced-true path.
    private static volatile int materialSwitchAB=0;
    private static volatile long materialSwitchReadAt=0L;
    private static int currentMaterialSwitchAB(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-materialSwitchReadAt>1000L){
            materialSwitchReadAt=now;
            try{
                android.content.Context c=launcherContext;
                if(c!=null){
                    int mode=android.provider.Settings.Global.getInt(c.getContentResolver(),
                            "liquidglass_material_switch_ab",0)==1?1:0;
                    if(mode!=materialSwitchAB){materialSwitchAB=mode;
                        log("GLASS121_MATERIAL_AB mode="+mode);}
                }
            }catch(Throwable t){log("GLASS121_MATERIAL_AB_READ_FAILED "+t.getClass().getSimpleName());}
        }
        return materialSwitchAB;
    }
    // V1.9.15: read-only, targeted native blur condition tracing. No return-value changes.
    private static final java.util.Set<String> CONDITION_ONCE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static void hookNativeBlurConditions(ClassLoader loader) {
        String[] owners = {
            "com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties",
            "com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties$Companion",
            "com.android.launcher3.folder.BaseFolderIconType",
            "com.android.launcher3.folder.PreviewBackground",
            "com.android.launcher3.folder.FolderIcon",
            "com.android.launcher3.folder.OplusPreviewBackground",
            "com.android.launcher3.LauncherAnimConfig",
            "com.android.launcher3.util.LauncherAnimConfig",
            "com.android.launcher3.util.AppFeatureUtils"
        };
        String[] names = {
            "isSupportNewBlur", "isSupportNewBlurLocal", "isBgSupportNewBlur",
            "enableFolderIconBlur", "getSysMaterialBlurSwitchEnable",
            "isBlurServiceInstalled", "isLevelAOrAPlus", "isUpgradeMaterialBlur",
            "isMaterialBlurJumpSupported", "getBlurServiceInstalled",
            "access$getMSysMaterialBlurSwitchEnable$cp",
            "access$getBlurServiceInstalled$cp",
            "access$getSBlurIsPaused$cp",
            "access$getSBlurIsPausedByTimeoutCheck$cp"
        };
        for (String ownerName : owners) {
            Class<?> owner;
            try { owner=Class.forName(ownerName,false,loader); }
            catch(Throwable ignored) { continue; }
            for (final Method method : owner.getDeclaredMethods()) {
                boolean matched=false;
                for(String name:names) if(method.getName().equals(name)) { matched=true;break; }
                if(!matched) continue;
                try {
                    method.setAccessible(true);
                    final String signature=method.toGenericString();
                    XposedBridge.hookMethod(method,new XC_MethodHook(){
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object value=p.getResult();
                                if (launcherContext!=null) refreshFromContext(launcherContext);
                                if (p.args!=null) for (Object arg:p.args) {
                                    if (arg instanceof android.content.Context) {
                                        refreshConfigFromContext((android.content.Context)arg);
                                        break;
                                    }
                                }
                                // Read settings on the actual Launcher call, not only on folder drawing.
                                if ("access$getMSysMaterialBlurSwitchEnable$cp".equals(method.getName())
                                        && method.getParameterTypes().length==0
                                        && method.getReturnType()==boolean.class
                                        && "com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties".equals(method.getDeclaringClass().getName())) {
                                    // This experiment intentionally changes ONLY the one observed accessor.
                                    // It never changes system properties, other applications or global blur state.
                                    int mode=currentMaterialSwitchAB();
                                    boolean forced=mode==1 && glassEnabled && Boolean.FALSE.equals(value);
                                    if(forced) p.setResult(Boolean.TRUE);
                                    String abKey="material-switch-"+mode+"-"+safeValue(value);
                                    if(EXPERIMENT_ONCE.add(abKey))
                                        log("GLASS121_MATERIAL_SWITCH mode="+mode
                                                +" original="+safeValue(value)
                                                +" final="+safeValue(p.getResult())
                                                +" forced="+forced);
                                }
                                String key=signature+"="+safeValue(value)+"->"+safeValue(p.getResult());
                                if(CONDITION_ONCE.size()>=64 || !CONDITION_ONCE.add(key))return;
                                log("NATIVE_BLUR_CONDITION method="+signature
                                    +" args="+decisionArgs(p.args)
                                    +" result="+safeValue(value)
                                    );
                            } catch(Throwable t) { log("NATIVE_BLUR_CONDITION_LOG_ERROR "+t.getClass().getSimpleName()); }
                        }
                    });
                    installed++;
                    log("NATIVE_BLUR_HOOK_INSTALLED "+signature);
                } catch(Throwable t) {
                    failed++;
                    log("NATIVE_BLUR_HOOK_FAILED "+method.toGenericString()+" reason="+t.getClass().getSimpleName());
                }
            }
        }
    }

    private static void invalidateGlassViews(){
        synchronized(DYNAMIC_REFRACTION){
            for(View v:DYNAMIC_REFRACTION.keySet()){
                if(v!=null && v.isAttachedToWindow()){
                    v.invalidate();
                    View root=v.getRootView();
                    if(root!=null)root.invalidate();
                }
            }
        }
        int folderCount=0;
        synchronized(BOOT_FOLDER_VIEWS){
            for(View folder:BOOT_FOLDER_VIEWS.keySet()){
                if(folder!=null && folder.isAttachedToWindow()){
                    folderCount++;
                    folder.invalidate();
                    android.view.ViewParent parent=folder.getParent();
                    int depth=0;
                    while(parent instanceof View && depth++<12){((View)parent).invalidate();parent=parent.getParent();}
                }
            }
        }
        // V1.9.144: on cache publication, a FolderIcon may not yet have
        // entered dispatchDraw. Find late siblings in the same attached root.
        java.util.HashSet<View> roots=new java.util.HashSet<>();
        synchronized(BOOT_FOLDER_VIEWS){
            for(View folder:BOOT_FOLDER_VIEWS.keySet())
                if(folder!=null && folder.isAttachedToWindow())roots.add(folder.getRootView());
        }
        int discovered=0;
        for(View root:roots){
            if(root==null)continue;
            java.util.ArrayDeque<View> queue=new java.util.ArrayDeque<>();
            queue.add(root);
            int inspected=0;
            while(!queue.isEmpty() && inspected++<2500){
                View node=queue.removeFirst();
                if(node.getClass().getName().endsWith(".FolderIcon")){
                    BOOT_FOLDER_VIEWS.put(node,Boolean.TRUE);
                    node.postInvalidateOnAnimation();
                    discovered++;
                }
                if(node instanceof ViewGroup){
                    ViewGroup group=(ViewGroup)node;
                    for(int i=0;i<group.getChildCount();i++)queue.addLast(group.getChildAt(i));
                }
            }
        }
        if(agslSkipLogs144++<30)log("BLUR_ALL_FOLDERS_144 roots="+roots.size()+" discovered="+discovered);
        log("BOOT_CONFIG_INVALIDATED tracked="+DYNAMIC_REFRACTION.size()+" folders="+folderCount);
    }

    // V1.9.96: proactively decode the wallpaper reference at Launcher startup,
    // independent of FolderIcon drawing/touch. Stop once ready; bounded retries.
    private static void prewarmBootRefraction(final android.content.Context app,
                                               final android.os.Handler handler) {
        final long generation;
        synchronized(HookEntry.class){
            if(BOOT_PREWARM_RUNNING)return;
            BOOT_PREWARM_RUNNING=true;
            generation=++BOOT_PREWARM_GENERATION;
        }
        final Runnable[] task=new Runnable[1];
        task[0]=new Runnable(){
            int attempts=0;
            @Override public void run(){
                if(generation!=BOOT_PREWARM_GENERATION)return;
                // Keep checking until both configuration and wallpaper are ready.
                // A frame alone is not proof that refraction is enabled.
                restoreBootSnapshot(app);
                android.graphics.Bitmap reference=REFRACTION_FRAME;
                boolean frameReady=reference!=null && !reference.isRecycled();
                boolean blurWanted=customBlurMode==1 && customBlurDp>0;
                boolean blurReady=!blurWanted || (frameReady && CUSTOM_BLUR_SOURCE==reference
                    && CUSTOM_BLUR_RADIUS==customBlurDp && CUSTOM_BLUR_FRAME!=null
                    && !CUSTOM_BLUR_FRAME.isRecycled());
                if(frameReady && blurWanted && !blurReady){
                    blurredWallpaper(reference,app.getResources().getDisplayMetrics().density,null);
                }
                if(frameReady && blurReady){
                    BOOT_PREWARM_RUNNING=false;
                    log("BOOT_MATERIAL_READY_146 attempts="+attempts+" refraction="+REFRACTION_ENABLED+" configAt="+CONFIG_READY_AT_146+" unlockAt="+CONFIG_UNLOCK_AT_146);
                    invalidateGlassViews();
                    return;
                }
                if(++attempts>180){
                    BOOT_PREWARM_RUNNING=false;
                    log("BOOT_PREWARM_TIMEOUT_145 frame="+frameReady+" blur="+blurReady);
                    return;
                }
                // Retry also when the snapshot arrives later than Launcher attach.
                if(REFRACTION_ENABLED || blurWanted) scheduleReferenceLoad(app,null);
                if(attempts==1 || attempts%15==0)
                    log("BOOT_PREWARM_WAIT attempt="+attempts+" enabled="+REFRACTION_ENABLED
                        +" loading="+REFRACTION_LOADING);
                handler.postDelayed(this,frameReady?120L:120L);
            }
        };
        handler.post(task[0]);
    }

    // Restore persisted settings as soon as the hooked process obtains a Context.
    // This is not a background service: LSPosed installs it when Launcher starts.
    private static void hookBootConfigRestore() {
        try {
            Method attach=android.app.Application.class.getDeclaredMethod("attach",android.content.Context.class);
            attach.setAccessible(true);
            XposedBridge.hookMethod(attach,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(p.args.length==0 || !(p.args[0] instanceof android.content.Context))return;
                    // Apply the small Settings.Global snapshot before first folder drawing.
                    final android.content.Context context=(android.content.Context)p.args[0];
                    if (!"com.android.launcher".equals(context.getPackageName())) return;
                    final android.content.Context app=context.getApplicationContext()==null?context:context.getApplicationContext();
                    launcherContext=app;
                    // Settings.Global is local to system_server and does not need
                    // the ColorOS-blocked third-party ContentProvider to start.
                    boolean bootApplied=restoreBootSnapshot(app);
                    log("BOOT_CONFIG_SOURCE=GLOBAL_SNAPSHOT applied="+bootApplied+" loaded="+configEverLoaded+" refraction="+REFRACTION_ENABLED);
                    // The first reference may not exist yet; retry on subsequent draws.
                    REFRACTION_LAST_CHECK=0L;
                    final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
                    // Direct-boot/credential storage can make the config provider
                    // unavailable when Launcher starts. Retry after unlock without
                    // killing Launcher. No persistent polling or background service.
                    final Runnable restore=new Runnable(){
                        @Override public void run(){
                            new Thread(()->{
                                boolean wasLoaded=configEverLoaded;
                                boolean snapshotChanged=restoreBootSnapshot(app);
                                refreshFromContext(app);
                                log("BOOT_CONFIG_RESTORE loaded="+configEverLoaded+" enabled="+glassEnabled+" highlight="+highlightEnabled+" refraction="+REFRACTION_ENABLED);
                                if(configEverLoaded)handler.post(()->invalidateGlassViews());
                            },"LiquidGlass-ConfigRestore").start();
                        }
                    };
                    restore.run();
                    prewarmBootRefraction(app,handler);
                    try {
                        app.getContentResolver().registerContentObserver(CONFIG,false,new android.database.ContentObserver(handler){
                            @Override public void onChange(boolean selfChange){
                                lastRead=0L;
                                restore.run();
                            }
                        });
                    } catch(Throwable t){log("CONFIG_OBSERVER_FAILED "+t.getClass().getSimpleName());}
                    // V1.9.128: Settings.Global is the Launcher source of truth.
                    // Observe the snapshot itself: provider notifications do not cross
                    // the root helper's Settings.Global write boundary.
                    try {
                        app.getContentResolver().registerContentObserver(
                            android.provider.Settings.Global.getUriFor(BOOT_SNAPSHOT_KEY),false,
                            new android.database.ContentObserver(handler){
                                @Override public void onChange(boolean selfChange){
                                    new Thread(()->{
                                        boolean changed=restoreBootSnapshot(app);
                                        if(changed)handler.post(()->invalidateGlassViews());
                                    },"LiquidGlass-SnapshotChanged").start();
                                }
                            });
                        log("BOOT_SNAPSHOT_OBSERVER_READY");
                    }catch(Throwable t){log("BOOT_SNAPSHOT_OBSERVER_FAILED "+t.getClass().getSimpleName());}
                    // Delayed retries are fallback only; the synchronous snapshot above
                    // must already be applied before Launcher starts rendering.
                    handler.postDelayed(restore,2000L);
                    handler.postDelayed(restore,8000L);
                    handler.postDelayed(restore,25000L);
                    handler.postDelayed(restore,60000L);
                    try {
                        android.content.IntentFilter filter=new android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED);
                        filter.addAction(android.content.Intent.ACTION_USER_PRESENT);
                        app.registerReceiver(new android.content.BroadcastReceiver(){
                            @Override public void onReceive(android.content.Context c,android.content.Intent i){
                                final String action=i==null?"unknown":i.getAction();
                                final int seq=++UNLOCK_EVENT_SEQ_154;
                                UNLOCK_EVENT_AT_154=android.os.SystemClock.uptimeMillis();
                                UNLOCK_DRAW_LOGS_154=0;
                                log("UNLOCK_EVENT_154 seq="+seq+" action="+action+
                                    " frame="+(REFRACTION_FRAME!=null && !REFRACTION_FRAME.isRecycled())+
                                    " glass="+glassEnabled);
                                requestUnlockRedraw154(handler,seq,0);
                                requestUnlockRedraw154(handler,seq,80);
                                requestUnlockRedraw154(handler,seq,240);
                                CONFIG_UNLOCK_AT_146=android.os.SystemClock.uptimeMillis();
                                lastRead=0L;
                                boolean changed=restoreBootSnapshot(app);
                                log("CONFIG_UNLOCK_146 changed="+changed+" readyAt="+CONFIG_READY_AT_146+" unlockAt="+CONFIG_UNLOCK_AT_146+" blur="+customBlurDp+" refract="+refractionPercent);
                                if(changed)invalidateGlassViews();
                                // Unlock can make CE wallpaper file readable before
                                // delayed config restore; request the frame immediately.
                                REFRACTION_LAST_CHECK=0L;
                                scheduleReferenceLoad(app,null);
                                prewarmBootRefraction(app,handler);
                                // Run the consistency check immediately, not after 1s.
                                handler.post(restore);
                                handler.postDelayed(restore,1000L);
                                handler.postDelayed(restore,4000L);
                            }
                        },filter);
                    }catch(Throwable t){log("BOOT_UNLOCK_LISTENER_FAILED "+t.getClass().getSimpleName());}
                }
            });
            log("BOOT_CONFIG_RESTORE_HOOK_READY");
        } catch(Throwable t) { log("BOOT_CONFIG_RESTORE_HOOK_FAILED reason="+t.getClass().getSimpleName()); }
    }

    // V1.9.204 preview: draw only inside the actual Hotseat View; never create a touch target.
    private static final java.util.Map<View,android.graphics.drawable.Drawable> DOCK_ORIGINAL_204=
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View,android.graphics.drawable.Drawable>());
    private static final class DockBackground204 extends android.graphics.drawable.Drawable {
        private final View target;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        DockBackground204(View v){target=v;}
        @Override public void draw(Canvas c){
            if(!dockGlass204)return;
            final float density=target.getResources().getDisplayMetrics().density;
            final Rect b=getBounds();
            // Use Hotseat's native bounds; clamp horizontal edge inset to keep all corners on screen.
            // V1.9.205.4: approximately 92% screen width, centered.
            // The measured Hotseat icon DRAWABLES (not their 284px cells) remain inside.
            final float inset=Math.max(12*density,b.width()*.04f);
            final float top=Math.max(0,2*density),bottom=Math.max(top,b.height()-2*density);
            final float r=(bottom-top)*.495f;
            RectF rect=new RectF(b.left+inset,b.top+top,b.right-inset,b.top+bottom);
            // V1.9.205.6: reuse the folder's wallpaper sampling, blur, tint,
            // clear-refraction and surface optics renderer for Hotseat material.
            // The drawable belongs to the Hotseat itself, so coordinates remain
            // in its local space; drawReferenceRefraction maps them to screen.
            // Do not draw a second translucent sheet over the shared renderer.
            if(glassEnabled){
                try{
                    drawReferenceRefraction(target,c,rect,r);
                    return;
                }catch(Throwable t){
                    if(diagnostics)log("DOCK_2056_SHARED_RENDER_FAILED "+t.getClass().getSimpleName());
                }
            }
            // Stable fallback when the glass pipeline is disabled/unavailable.
            paint.setShader(new android.graphics.LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
                new int[]{0x40FFFFFF,0x20B4CEE7,0x38FFFFFF},null,android.graphics.Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);c.drawRoundRect(rect,r,r,paint);paint.setShader(null);
            paint.setColor(0x72FFFFFF);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1f,density*.7f));
            c.drawRoundRect(rect,r,r,paint);paint.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private static volatile java.lang.ref.WeakReference<android.app.Activity> LAUNCHER_ACTIVITY_205=
        new java.lang.ref.WeakReference<android.app.Activity>(null);
    private static void refreshLauncherOptics204(android.app.Activity activity){
        try{
            if(activity==null)return;
            LAUNCHER_ACTIVITY_205=new java.lang.ref.WeakReference<android.app.Activity>(activity);
            refreshFromContext(activity);
            int id=activity.getResources().getIdentifier("hotseat","id","com.android.launcher");
            View hotseat=id==0?null:activity.findViewById(id);
            if(hotseat==null)return;
            if(dockGlass204){
                if(!(hotseat.getBackground() instanceof DockBackground204)){
                    DOCK_ORIGINAL_204.put(hotseat,hotseat.getBackground());
                    hotseat.setBackground(new DockBackground204(hotseat));
                }
            }else if(hotseat.getBackground() instanceof DockBackground204){
                hotseat.setBackground(DOCK_ORIGINAL_204.remove(hotseat));
            }
            hotseat.invalidate();
            if(dockGlass204 && glassEnabled){
                scheduleReferenceLoad(hotseat);
            }
            // The icon optics path is diagnostic-only until per-icon drawable bounds
            // are confirmed. Do not add a full-cell overlay that covers icon labels.
            if(diagnostics)log("HOTSEAT_204 bounds="+hotseat.getWidth()+"x"+hotseat.getHeight()+
                " dock="+dockGlass204+" iconRequested="+iconOptics204);
        }catch(Throwable t){log("HOTSEAT_204_FAILED "+t.getClass().getSimpleName());}
    }
    private static void hookLauncherOptics204(ClassLoader loader){
        try{
            Class<?> launcher=find("com.android.launcher3.Launcher",loader);
            if(launcher==null){log("HOTSEAT_204_LAUNCHER_NOT_FOUND");return;}
            java.lang.reflect.Method m=launcher.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(p.thisObject instanceof android.app.Activity){
                        android.app.Activity a=(android.app.Activity)p.thisObject;
                        a.getWindow().getDecorView().post(()->{
                            refreshLauncherOptics204(a);
                            attachDockConfigObserver205(a);
                        });
                    }
                }
            });
            log("HOTSEAT_204_HOOK_READY");
        }catch(Throwable t){log("HOTSEAT_204_HOOK_FAILED "+t.getClass().getSimpleName());}
    }
    private static final java.util.Map<android.app.Activity,android.database.ContentObserver> DOCK_OBSERVERS_205=
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.app.Activity,android.database.ContentObserver>());
    private static void attachDockConfigObserver205(android.app.Activity a){
        if(DOCK_OBSERVERS_205.containsKey(a))return;
        try{
            android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
            android.database.ContentObserver observer=new android.database.ContentObserver(handler){
                @Override public void onChange(boolean selfChange){
                    lastRead=0L;
                    handler.postDelayed(()->{
                        if(a.isFinishing() || a.isDestroyed())return;
                        refreshLauncherOptics204(a);
                    },350L);
                }
            };
            a.getContentResolver().registerContentObserver(CONFIG,false,observer);
            // Snapshot is asynchronously updated by the root helper.
            a.getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor("liquidglass_config_snapshot"),
                false,observer);
            DOCK_OBSERVERS_205.put(a,observer);
            log("DOCK_2052_CONFIG_OBSERVER_READY");
        }catch(Throwable t){log("DOCK_2052_CONFIG_OBSERVER_FAILED "+t.getClass().getSimpleName());}
    }
    // V1.9.205.9: OEM alpha silhouette path, no speculative rounded rectangle.
    // Lightweight edge optics over an actual icon drawable only.
    // Hook only Launcher icon TextViews, never create an overlay View or touch target.
    private static final Paint ICON_EDGE_205=new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final java.util.Set<String> ICON_HOOK_CLASSES_205=new java.util.HashSet<String>();
    // V1.9.205.8: bounded, one-shot icon geometry diagnostics; no per-frame logs.
    private static final java.util.Set<String> ICON_DIAG_2058=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static void diagnoseIcon2058(android.widget.TextView v,Drawable d,Rect bounds){
        if(!diagnostics)return;
        try{
            String label=String.valueOf(v.getContentDescription());
            String key=v.getClass().getName()+"|"+label;
            if(ICON_DIAG_2058.size()>=40 || !ICON_DIAG_2058.add(key))return;
            String type=d.getClass().getName();
            Drawable current=d;
            for(int i=0;i<4 && current instanceof android.graphics.drawable.InsetDrawable;i++)
                current=((android.graphics.drawable.InsetDrawable)current).getDrawable();
            String mask="none";
            if(current instanceof android.graphics.drawable.AdaptiveIconDrawable){
                android.graphics.Path p=((android.graphics.drawable.AdaptiveIconDrawable)current).getIconMask();
                android.graphics.RectF mb=new android.graphics.RectF();
                if(p!=null){p.computeBounds(mb,true);mask=mb.toShortString();}
            }
            log("ICON_GEOMETRY_2058 label="+label+" view="+v.getClass().getName()+
                " viewSize="+v.getWidth()+"x"+v.getHeight()+
                " padding="+v.getPaddingLeft()+","+v.getPaddingTop()+","+v.getPaddingRight()+","+v.getPaddingBottom()+
                " drawable="+type+" unwrapped="+current.getClass().getName()+
                " bounds="+bounds.toShortString()+" mask="+mask+
                " scroll="+v.getScrollX()+","+v.getScrollY()+
                " scale="+v.getScaleX()+","+v.getScaleY()+
                " translation="+v.getTranslationX()+","+v.getTranslationY()+
                " density="+v.getResources().getDisplayMetrics().density);
        }catch(Throwable t){log("ICON_GEOMETRY_2058_FAILED "+t.getClass().getSimpleName());}
    }
    // V1.9.205.9: sample the OEM drawable's final alpha, not a guessed corner radius.
    // A fully opaque raster cannot reveal its visible mask: never draw a false rim.
    private static final java.util.Map<Drawable,android.graphics.Path> ICON_CONTOUR_2059 =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Drawable,android.graphics.Path>());
    // Failed samples are retried at most once per minute, never on every frame.
    private static final java.util.Map<Drawable,Long> ICON_CONTOUR_RETRY_20510 =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Drawable,Long>());
    private static final java.util.Set<String> ICON_CONTOUR_LOG_2059 =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // V1.9.205.11: inspect the OEM drawable for a hardware-backed Bitmap.
    // Copying the bitmap to ARGB_8888 is safe for a software sampling Canvas.
    // Do not change the OEM drawable's backing bitmap or permanent bounds.
    private static android.graphics.Bitmap extractSoftwareIconBitmap20511(Drawable d){
        Class<?> cls=d.getClass();
        for(int depth=0;cls!=null && depth<5;depth++,cls=cls.getSuperclass()){
            for(java.lang.reflect.Field f:cls.getDeclaredFields()){
                if(!android.graphics.Bitmap.class.isAssignableFrom(f.getType()))continue;
                try{
                    f.setAccessible(true);
                    Object value=f.get(d);
                    if(!(value instanceof android.graphics.Bitmap))continue;
                    android.graphics.Bitmap source=(android.graphics.Bitmap)value;
                    if(source.isRecycled() || source.getWidth()<8 || source.getHeight()<8)continue;
                    android.graphics.Bitmap copy=source.copy(android.graphics.Bitmap.Config.ARGB_8888,false);
                    if(copy!=null){
                        if(diagnostics && ICON_BITMAP_LOG_20511.add(d.getClass().getName()+"|"+f.getName()))
                            log("ICON_BITMAP_20511 field="+f.getName()+" config="+source.getConfig()+
                                " size="+source.getWidth()+"x"+source.getHeight());
                        return copy;
                    }
                }catch(Throwable ignored){}
            }
        }
        return null;
    }
    private static final java.util.Set<String> ICON_BITMAP_LOG_20511 =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static android.graphics.Path computeIconContour2059(Drawable drawable){
        final int n=128;
        android.graphics.Bitmap bitmap=null;
        try{
            bitmap=android.graphics.Bitmap.createBitmap(n,n,android.graphics.Bitmap.Config.ARGB_8888);
            Canvas off=new Canvas(bitmap);
            android.graphics.Bitmap source=extractSoftwareIconBitmap20511(drawable);
            if(source!=null){
                try{
                    Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                    off.drawBitmap(source,null,new Rect(0,0,n,n),p);
                }finally{source.recycle();}
            }else{
                // No safe bitmap accessor: preserve previous behavior, including
                // the visible optics fallback on unsupported hardware drawing.
                Rect original=new Rect(drawable.getBounds());
                try{
                    drawable.setBounds(0,0,n,n);
                    drawable.draw(off);
                }finally{drawable.setBounds(original);}
            }
            int[] pixels=new int[n*n];bitmap.getPixels(pixels,0,n,0,0,n,n);
            // OEM precomposed icons sometimes have an opaque square backing. Its
            // visual contour cannot be determined from alpha, so fail closed.
            int corners=0;
            int[] samples={0,n-1,(n-1)*n,n*n-1};
            for(int pos:samples)if((pixels[pos]>>>24)>40)corners++;
            if(corners>=2)return null;
            int[] left=new int[n],right=new int[n];
            int valid=0;
            for(int y=0;y<n;y++){
                left[y]=n;right[y]=-1;
                for(int x=0;x<n;x++){
                    if((pixels[y*n+x]>>>24)>=96){
                        if(x<left[y])left[y]=x;
                        right[y]=x;
                    }
                }
                if(right[y]>=left[y])valid++;
            }
            if(valid<n*.70f)return null;
            android.graphics.Path path=new android.graphics.Path();
            int first=-1,last=-1;
            for(int y=0;y<n;y++)if(right[y]>=left[y]){if(first<0)first=y;last=y;}
            if(first<0)return null;
            // Build the silhouette on the alpha boundary, with both left and
            // right sides sampled from the same OEM drawable raster.
            path.moveTo(left[first],first);
            for(int y=first+1;y<=last;y++)if(right[y]>=left[y])path.lineTo(left[y],y);
            for(int y=last;y>=first;y--)if(right[y]>=left[y])path.lineTo(right[y],y);
            path.close();
            return path;
        }catch(Throwable t){
            if(diagnostics)log("ICON_CONTOUR_20511_FAILED drawable="+drawable.getClass().getName()+
                " error="+t.getClass().getName()+" message="+String.valueOf(t.getMessage())+
                " at="+(t.getStackTrace().length>0?t.getStackTrace()[0].toString():"unknown"));
            return null;
        }finally{if(bitmap!=null)bitmap.recycle();}
    }
    private static void drawIconEdge205(android.widget.TextView v,Canvas c){
        if(!iconOptics204 || !v.isShown() || v.getWidth()<=0 || v.getHeight()<=0)return;
        if(v.getContentDescription()==null || v.getContentDescription().length()==0)return;
        Drawable[] ds=v.getCompoundDrawables();
        Drawable d=ds==null?null:ds[1];
        if(d==null){Drawable[] rel=v.getCompoundDrawablesRelative();d=rel==null?null:rel[1];}
        if(d==null)return;
        Rect bounds=d.getBounds();
        if(bounds.width()<8 || bounds.height()<8)return;
        diagnoseIcon2058(v,d,bounds);
        float density=v.getResources().getDisplayMetrics().density;
        float w=bounds.width(),h=bounds.height();
        float left=(v.getWidth()-w)*.5f;
        float top=v.getPaddingTop();
        if(top+h>v.getHeight())return;
        android.graphics.Path contour=null;
        long now=android.os.SystemClock.uptimeMillis();
        synchronized(ICON_CONTOUR_2059){
            contour=ICON_CONTOUR_2059.get(d);
            Long retry=ICON_CONTOUR_RETRY_20510.get(d);
            if(contour==null && (retry==null || now>=retry.longValue())){
                contour=computeIconContour2059(d);
                if(contour!=null){
                    ICON_CONTOUR_2059.put(d,contour);
                    ICON_CONTOUR_RETRY_20510.remove(d);
                }else ICON_CONTOUR_RETRY_20510.put(d,now+60000L);
            }
        }
        String type=d.getClass().getName();
        if(diagnostics && ICON_CONTOUR_LOG_2059.size()<40 && ICON_CONTOUR_LOG_2059.add(type+"|"+v.getContentDescription()))
            log("ICON_CONTOUR_20511 label="+v.getContentDescription()+" drawable="+type+
                " alphaContour="+(contour!=null)+" fallback="+(contour==null)+" bounds="+bounds.toShortString());
        if(contour==null){
            // Restore the V1.9.205.6 visible optics rather than disappearing.
            // This is intentionally a compatibility fallback, NOT a precise OEM silhouette.
            float stroke=Math.max(density*.65f,1f);
            float pad=stroke*.5f;
            RectF r=new RectF(left+pad,top+pad,left+w-pad,top+h-pad);
            float corner=Math.min(r.width(),r.height())*.265f;
            ICON_EDGE_205.reset();ICON_EDGE_205.setAntiAlias(true);
            ICON_EDGE_205.setStyle(Paint.Style.STROKE);
            ICON_EDGE_205.setStrokeWidth(stroke);
            ICON_EDGE_205.setShader(new android.graphics.LinearGradient(r.left,r.top,r.right,r.bottom,
                new int[]{0x64FFFFFF,0x1CFFFFFF,0x00FFFFFF,0x38FFFFFF},null,
                android.graphics.Shader.TileMode.CLAMP));
            boolean maskDrawn=false;
            try{
                Drawable unwrapped=d;
                for(int depth=0;depth<3 && unwrapped instanceof android.graphics.drawable.InsetDrawable;depth++)
                    unwrapped=((android.graphics.drawable.InsetDrawable)unwrapped).getDrawable();
                if(unwrapped instanceof android.graphics.drawable.AdaptiveIconDrawable){
                    android.graphics.Path mask=((android.graphics.drawable.AdaptiveIconDrawable)unwrapped).getIconMask();
                    if(mask!=null && !mask.isEmpty()){
                        RectF maskBounds=new RectF();mask.computeBounds(maskBounds,true);
                        float aspect=maskBounds.height()>0?maskBounds.width()/maskBounds.height():0f;
                        if(maskBounds.width()>1f && maskBounds.height()>1f && aspect>.92f && aspect<1.08f){
                            android.graphics.Matrix matrix=new android.graphics.Matrix();
                            matrix.setRectToRect(maskBounds,r,android.graphics.Matrix.ScaleToFit.CENTER);
                            android.graphics.Path mapped=new android.graphics.Path();
                            mask.transform(matrix,mapped);c.drawPath(mapped,ICON_EDGE_205);
                            maskDrawn=true;
                        }
                    }
                }
            }catch(Throwable t){if(diagnostics)log("ICON_MASK_20510_FALLBACK "+t.getClass().getSimpleName());}
            if(!maskDrawn)c.drawRoundRect(r,corner,corner,ICON_EDGE_205);
            ICON_EDGE_205.setShader(null);ICON_EDGE_205.setStyle(Paint.Style.FILL);
            return;
        }
        float stroke=Math.max(density*.52f,1f);
        ICON_EDGE_205.reset();ICON_EDGE_205.setAntiAlias(true);
        ICON_EDGE_205.setStyle(Paint.Style.STROKE);
        ICON_EDGE_205.setStrokeWidth(stroke);
        ICON_EDGE_205.setStrokeJoin(Paint.Join.ROUND);
        ICON_EDGE_205.setStrokeCap(Paint.Cap.ROUND);
        ICON_EDGE_205.setShader(new android.graphics.LinearGradient(left,top,left+w,top+h,
            new int[]{0x54FFFFFF,0x20FFFFFF,0x08FFFFFF,0x26FFFFFF},null,
            android.graphics.Shader.TileMode.CLAMP));
        android.graphics.Matrix matrix=new android.graphics.Matrix();
        matrix.setScale(w/128f,h/128f);
        matrix.postTranslate(left,top);
        android.graphics.Path mapped=new android.graphics.Path();
        contour.transform(matrix,mapped);
        c.drawPath(mapped,ICON_EDGE_205);
        ICON_EDGE_205.setShader(null);ICON_EDGE_205.setStyle(Paint.Style.FILL);
    }
    private static void hookIconOptics205(ClassLoader loader){
        String[] candidates={"com.android.launcher3.BubbleTextView",
            "com.android.launcher3.views.BubbleTextView",
            "com.android.launcher3.widget.BubbleTextView"};
        int count=0;
        for(String name:candidates){
            try{
                Class<?> cls=find(name,loader);
                if(cls==null || !android.widget.TextView.class.isAssignableFrom(cls))continue;
                if(!ICON_HOOK_CLASSES_205.add(cls.getName()))continue;
                // Use the XposedBridge.hookMethod API provided by our compile-time stubs.
                // Only hook a method declared on this concrete class; inherited draw
                // implementations must not be hooked globally across unrelated TextViews.
                java.lang.reflect.Method drawMethod;
                try { drawMethod=cls.getDeclaredMethod("onDraw",Canvas.class); }
                catch(NoSuchMethodException missing) {
                    log("ICON_OPTICS_205_NO_OWN_DRAW "+name);
                    continue;
                }
                XposedBridge.hookMethod(drawMethod,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        try{
                            if(p.thisObject instanceof android.widget.TextView && p.args.length>0 && p.args[0] instanceof Canvas)
                                drawIconEdge205((android.widget.TextView)p.thisObject,(Canvas)p.args[0]);
                        }catch(Throwable t){if(diagnostics)log("ICON_OPTICS_205_DRAW_FAILED "+t.getClass().getSimpleName());}
                    }
                });
                count++;
            }catch(Throwable t){log("ICON_OPTICS_205_HOOK_FAILED "+name+" "+t.getClass().getSimpleName());}
        }
        log("ICON_OPTICS_205_HOOK_CLASSES="+count);
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.android.systemui".equals(p.packageName) && !"com.android.launcher".equals(p.packageName)) return;
        installed = 0; failed = 0;
        hookBootConfigRestore();
        log("GLASS166_BUILD_ID version=V1.9.166 package="+p.packageName+" process="+p.processName);
        log("V1.9.21 NATIVE_CONTOUR_ALIGNMENT_LOADED package=" + p.packageName + " process=" + p.processName);
        Class<?>[] layout = {boolean.class, int.class, int.class, int.class, int.class};
        if ("com.android.systemui".equals(p.packageName)) {
            hook(find("com.android.systemui.qs.tileimpl.QSTileViewImpl", p.classLoader), "onLayout", layout, "QSTileViewImpl", false);
            hook(find("com.android.systemui.qs.QSPanel", p.classLoader), "onLayout", layout, "QSPanel", false);
        } else {
            hookLauncherOptics204(p.classLoader);
            hookIconOptics205(p.classLoader);
            hookWallpaperOffsetRequests();
            hookNativeDrawableDraw();
            // V1.9.18: render from the confirmed preview drawBackground entry, not a nonexistent LayerBlurDrawable.draw override.
            Class<?> folderClass = find("com.android.launcher3.folder.Folder", p.classLoader);
            hook(folderClass, "onAttachedToWindow", new Class<?>[0], "Folder", false);
            hookDynamicFolderLifecycle(folderClass);
            hook(find("com.android.launcher3.folder.FolderIcon", p.classLoader), "dispatchDraw", new Class<?>[]{Canvas.class}, "FolderIcon", true);
            hookTrackedFolderNativeBlur(p.classLoader);
            hookFolderIconCanvasFusion(p.classLoader);
            hookFolderDrawChain165(p.classLoader);
            probeBlurDrawImplementation(p.classLoader);
            hookPreview(p.classLoader);
            traceBlurMethods(p.classLoader);
            hookFolderConditions(p.classLoader);
            hookDecisionChain(p.classLoader);
            hookNativeBlurConditions(p.classLoader);
        }
        log("V1.9.21 NATIVE_CONTOUR_ALIGNMENT_READY package=" + p.packageName + " installed=" + installed + " failed=" + failed);
    }
}
