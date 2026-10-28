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
    private static final java.util.Map<Drawable,Integer> ORIGINAL_ALPHA = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Drawable,Integer>());
    private static final java.util.Set<Drawable> TINTED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Drawable,Boolean>());
    private static volatile boolean diagnostics = false;
    private static volatile int probeMode = 0;
    private static volatile boolean nativeAlphaProbe = false;
    private static final Set<String> BLUR_API_SEEN = Collections.synchronizedSet(new HashSet<String>());
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
                highlightWidth=Math.max(1,Math.min(5,c.getInt(8)));
                directionalLight=c.getInt(9)==1;
                int radiusIndex=c.getColumnIndex("blur_radius_percent");
                blurRadiusPercent=radiusIndex>=0 ? Math.max(10,Math.min(100,c.getInt(radiusIndex))) : 100;
                probeMode=Math.max(0,Math.min(3,c.getInt(7)));
                int expIndex=c.getColumnIndex("native_blur_experiment");
                nativeBlurExperiment=expIndex>=0 ? Math.max(0,Math.min(1,c.getInt(expIndex))) : 0;
                int alphaProbeIndex=c.getColumnIndex("native_alpha_probe");
                nativeAlphaProbe=alphaProbeIndex>=0 && c.getInt(alphaProbeIndex)==1;
                String summary="enabled="+glassEnabled+" alpha="+glassAlpha+" tint="+tintMode+" tintStrength="+tintStrength+" highlight="+highlightEnabled+" highlightStrength="+highlightStrength+" highlightWidth="+highlightWidth+" directional="+directionalLight+" probeMode="+probeMode+" nativeBlurExperiment="+nativeBlurExperiment+" blurRadiusPercent="+blurRadiusPercent+" nativeAlphaProbe="+nativeAlphaProbe;
                if(!summary.equals(lastConfigSummary)){lastConfigSummary=summary;log("CONFIG_SYNC "+summary);}
            }
        } catch(Throwable t) { if(alphaLogs++<3) log("CONFIG_READ_FAILED reason="+t.getClass().getSimpleName()); }
    }
    private static void refreshConfigFromContext(android.content.Context ctx) {
        long now=android.os.SystemClock.uptimeMillis();
        if(now-lastExperimentRead<1500)return;
        try (Cursor c=ctx.getContentResolver().query(CONFIG,null,null,null,null)) {
            if(c!=null && c.moveToFirst()) {
                int i=c.getColumnIndex("native_blur_experiment");
                nativeBlurExperiment=i<0?0:Math.max(0,Math.min(1,c.getInt(i)));
                lastExperimentRead=now;
            }
        } catch(Throwable ignored) { /* keep default off on read failure */ }
    }
    private static int tintColor() {
        if(tintMode==1)return Color.rgb(225,240,255);
        if(tintMode==2)return Color.rgb(185,192,202);
        return Color.rgb(40,50,65);
    }
    // V1.9.35: read-only, bounded native blur parameter probe. Never mutate OEM blur state.
    private static void inspectBlurParameters(Object child) {
        if (!diagnostics || child == null) return;
        try {
            Method getter = child.getClass().getMethod("getBlurParams");
            if (getter.getParameterTypes().length != 0) return;
            Object params = getter.invoke(child);
            if (params == null) { log("BLUR_PARAMS null"); return; }
            log("BLUR_PARAMS_CLASS " + params.getClass().getName());
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
                        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
                            log("BLUR_PARAMS_FIELD " + f.getName() + "=" + String.valueOf(value));
                        } else {
                            log("BLUR_PARAMS_FIELD " + f.getName() + " type=" + value.getClass().getName());
                        }
                    } catch (Throwable t) { log("BLUR_PARAMS_FIELD_ERROR " + f.getName() + "=" + t.getClass().getSimpleName()); }
                    count++;
                }
            }
            if (count == 0) log("BLUR_PARAMS_NO_MATCHING_FIELDS");
        } catch (NoSuchMethodException t) { log("BLUR_PARAMS_GETTER_UNAVAILABLE"); }
        catch (Throwable t) { log("BLUR_PARAMS_ERROR " + t.getClass().getSimpleName()); }
    }
    private static void applyNativeBackground(View v) {
        refresh(v);
        inspectFolderBlur(v);
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
                    inspectBlurParameters(child);
                    if(child!=null)for(Method method:child.getClass().getMethods()) {
                        String name=method.getName().toLowerCase(java.util.Locale.ROOT);
                        if(name.contains("blur") || name.contains("alpha") || name.contains("radius"))
                            log("NATIVE_BLUR_CHILD_API "+method.toGenericString());
                    }
                }catch(Throwable t){log("NATIVE_BLUR_API_ERROR "+t.getClass().getSimpleName());}
            }
            // Verified native blur drawable alpha control. This does not change blur radius.
            int alpha=isBlur ? (glassEnabled && nativeAlphaProbe ? requested : original) : (glassEnabled?requested:original);
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
    private static final float BLUR_CORNER_RADIUS_DELTA_PX = 6f;
    private static MaskCache blurShapeMask(int w,int h,float radius) {
        String key=w+"x"+h+"/"+Math.round(radius*100)+"/"+highlightWidth+"/cornerDelta"+BLUR_CORNER_RADIUS_DELTA_PX;
        synchronized(BLUR_SHAPE_MASKS) {
            MaskCache cached=BLUR_SHAPE_MASKS.get(key);
            if(cached!=null)return cached;
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
    private static void drawUnifiedMaterial(Canvas canvas, MaskCache cache, boolean edgeOnly) {
        if(cache==null)return;
        boolean tint=tintMode!=0 && tintStrength>0 && probeMode!=2;
        boolean shine=(highlightEnabled && highlightStrength>0 && probeMode!=3)||probeMode==2;
        if(!edgeOnly) {
            if(probeMode==0)drawOpticalGlass(canvas,cache);
            if(tint){
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                int c=tintColor();
                p.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    Color.rgb(Color.red(c),Color.green(c),Color.blue(c)),android.graphics.PorterDuff.Mode.SRC_IN));
                p.setAlpha(Math.round(160f*tintStrength/90f));
                canvas.drawBitmap(cache.mask,0,0,p);
            }
        } else if(shine) {
            float strength=probeMode==2?1f:highlightStrength/100f;
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
                    // Second pass: soft lower-edge depth; never draws outside mask.
                    int depthLayer=canvas.saveLayer(0,0,cache.w,cache.h,null);
                    try {
                        Paint depth=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                        depth.setAlpha(Math.round(125f*strength));
                        canvas.drawBitmap(cache.glow,0,0,depth);
                        Paint dark=new Paint(Paint.ANTI_ALIAS_FLAG);
                        dark.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                        dark.setShader(new android.graphics.LinearGradient(
                            0f,cache.h*0.25f,0f,cache.h,
                            new int[]{0x00000000,0x00243850,0x7034495C,0xA025384A},
                            new float[]{0f,0.42f,0.8f,1f},
                            android.graphics.Shader.TileMode.CLAMP));
                        canvas.drawRect(0,0,cache.w,cache.h,dark);
                        dark.setXfermode(null);
                        dark.setShader(null);
                    } finally { canvas.restoreToCount(depthLayer); }
                }
            }
        }
    }
    private static int folderFusionLogs = 0;
    private static final ThreadLocal<Boolean> FOLDER_FUSION_GUARD = new ThreadLocal<Boolean>();
    private static void drawFolderIconFusion(View v, Canvas canvas, boolean edgeOnly) {
        if (!glassEnabled || probeMode == 1 || canvas == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        if (Boolean.TRUE.equals(FOLDER_FUSION_GUARD.get())) return;
        try {
            Object preview = field(v.getClass(), "mBackground").get(v);
            if (preview == null) return;
            Method getter = preview.getClass().getDeclaredMethod("getBgDrawable");
            getter.setAccessible(true);
            Object value = getter.invoke(preview);
            if (!(value instanceof Drawable) || !value.getClass().getName().endsWith(".LayerBlurDrawable")) return;
            if (!edgeOnly) inspectFusionGeometry(v,preview,(Drawable)value);
            // V1.9.21: native blur bounds are in preview-local coordinates; the
            // preview offsets translate them into FolderIcon's Canvas coordinates.
            // Never use FolderIcon's full dimensions (they include the label).
            Drawable blur = (Drawable)value;
            // Preserve OEM geometry and blur parameters; no setMRadius calls.
            Rect nativeBounds = blur.getBounds();
            if (nativeBounds == null || nativeBounds.isEmpty()) {
                if (folderFusionLogs++ < 12) log("FOLDER_FUSION_GEOMETRY_SKIP reason=empty_blur_bounds");
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
                if (folderFusionLogs++ < 12) log("FOLDER_FUSION_GEOMETRY_SKIP reason=radius_unavailable");
                return;
            }
            RectF rect = new RectF(offsetX + sourceBounds.left, offsetY + sourceBounds.top,
                offsetX + sourceBounds.right, offsetY + sourceBounds.bottom);
            if (rect.isEmpty() || rect.left < -1f || rect.top < -1f ||
                rect.right > v.getWidth()+1f || rect.bottom > v.getHeight()+1f) {
                if (folderFusionLogs++ < 12) log("FOLDER_FUSION_GEOMETRY_SKIP reason=out_of_view rect="+rect);
                return;
            }
            float w = rect.width(), h = rect.height();
            radius = Math.min(radius, Math.min(w,h)*0.5f);
            MaskCache mask=blurShapeMask(Math.round(w),Math.round(h),radius);
            if(mask==null)return;
            int save=canvas.save();
            FOLDER_FUSION_GUARD.set(Boolean.TRUE);
            try {
                canvas.translate(rect.left,rect.top);
                drawUnifiedMaterial(canvas,mask,edgeOnly);
                if(folderFusionLogs++<16)log("UNIFIED_MATERIAL_DRAW phase="+(edgeOnly?"edge":"surface")+
                    " rect="+rect+" radius="+radius+" nativeOutline="+nativeOutline+
                    " maskSource=corner-only-synthetic-mask cornerDeltaPx="+BLUR_CORNER_RADIUS_DELTA_PX+" alphaMask=true");
            } finally { FOLDER_FUSION_GUARD.remove(); canvas.restoreToCount(save); }
        } catch (Throwable t) {
            if (folderFusionLogs++ < 12) log("FOLDER_CANVAS_FUSION_FAILED reason="+t.getClass().getSimpleName());
        }
    }
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
                        refresh(v);
                        drawFolderIconFusion(v,(Canvas)p.args[0],false);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View && p.args.length > 0 && p.args[0] instanceof Canvas)
                        drawFolderIconFusion((View)p.thisObject,(Canvas)p.args[0],true);
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
        if (cache == null || cache.w < 8 || cache.h < 8) return;
        int layer = canvas.saveLayer(0, 0, cache.w, cache.h, null);
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
    private static void inspectFolderBlur(View v) {
        if(!diagnostics || blurStateLogs>24)return;
        try{
            Object flag=field(v.getClass(),"mIsSupportNewBlurLocal").get(v);
            log("BLUR_PROBE_FOLDER class="+v.getClass().getName()+" localSupport="+safeValue(flag)+" size="+v.getWidth()+"x"+v.getHeight());
            Object preview=field(v.getClass(),"mBackground").get(v);
            inspectBlurState(preview,"FolderIcon");
            traceConditions(v,"folderIcon");
            traceConditions(preview,"preview");
        }catch(Throwable e){if(blurStateLogs++<8)log("BLUR_PROBE_FOLDER_ERROR="+e.getClass().getSimpleName());}
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
                    || ("isBgSupportNewBlur".equals(name))
                    || (name.toLowerCase(java.util.Locale.ROOT).contains("blur") && m.getParameterTypes().length<=3);
            if (!target) continue;
            try {
                m.setAccessible(true);
                final String methodName = name;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!"drawBackground".equals(methodName)) return;
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
                                // Draw on the preview's actual Canvas after its OEM background has been drawn.
                                // This avoids hooking an inherited/nonexistent LayerBlurDrawable.draw method.
                                Canvas targetCanvas=null;
                                if (param.args!=null) for(Object arg:param.args) {
                                    if (arg instanceof Canvas) { targetCanvas=(Canvas)arg; break; }
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
                log("HOOK_INSTALLED class=" + c.getName() + " method=" + m.toGenericString());
            } catch (Throwable t) { failed++; log("HOOK_FAILED class=" + c.getName() + " method=" + m.toGenericString() + " error=" + t); }
        }
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
                                    if (nativeBlurExperiment==1 && Boolean.FALSE.equals(value)) {
                                        p.setResult(Boolean.TRUE);
                                        if (EXPERIMENT_ONCE.add("material-switch-forced"))
                                            log("NATIVE_BLUR_EXPERIMENT material_switch original=false override=true");
                                    } else if (EXPERIMENT_ONCE.add("material-switch-unchanged-"+nativeBlurExperiment)) {
                                        log("NATIVE_BLUR_EXPERIMENT material_switch original="+safeValue(value)+" unchanged mode="+nativeBlurExperiment);
                                    }
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

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.android.systemui".equals(p.packageName) && !"com.android.launcher".equals(p.packageName)) return;
        installed = 0; failed = 0;
        log("V1.9.21 NATIVE_CONTOUR_ALIGNMENT_LOADED package=" + p.packageName + " process=" + p.processName);
        Class<?>[] layout = {boolean.class, int.class, int.class, int.class, int.class};
        if ("com.android.systemui".equals(p.packageName)) {
            hook(find("com.android.systemui.qs.tileimpl.QSTileViewImpl", p.classLoader), "onLayout", layout, "QSTileViewImpl", false);
            hook(find("com.android.systemui.qs.QSPanel", p.classLoader), "onLayout", layout, "QSPanel", false);
        } else {
            hookNativeDrawableDraw();
            // V1.9.18: render from the confirmed preview drawBackground entry, not a nonexistent LayerBlurDrawable.draw override.
            hook(find("com.android.launcher3.folder.Folder", p.classLoader), "onAttachedToWindow", new Class<?>[0], "Folder", false);
            hook(find("com.android.launcher3.folder.FolderIcon", p.classLoader), "dispatchDraw", new Class<?>[]{Canvas.class}, "FolderIcon", true);
            hookFolderIconCanvasFusion(p.classLoader);
            hookPreview(p.classLoader);
            traceBlurMethods(p.classLoader);
            hookFolderConditions(p.classLoader);
            hookDecisionChain(p.classLoader);
            hookNativeBlurConditions(p.classLoader);
        }
        log("V1.9.21 NATIVE_CONTOUR_ALIGNMENT_READY package=" + p.packageName + " installed=" + installed + " failed=" + failed);
    }
}
