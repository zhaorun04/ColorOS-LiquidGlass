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
    private static volatile int probeMode = 0;
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
                probeMode=Math.max(0,Math.min(3,c.getInt(7)));
                String summary="enabled="+glassEnabled+" alpha="+glassAlpha+" tint="+tintMode+" tintStrength="+tintStrength+" highlight="+highlightEnabled+" highlightStrength="+highlightStrength+" probeMode="+probeMode;
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
                " alpha="+alpha+" tint="+tintMode+" drawable="+drawableTag(d));
        }catch(Throwable t){if(alphaLogs++<12)log("MATERIAL_FAILED reason="+t.getClass().getSimpleName());}
    }
    // Weak references avoid retaining Launcher drawables beyond their lifecycle.
    // Drawing inside GradientDrawable.draw(Canvas) uses the SAME canvas matrix and
    // bounds as the native background. No FolderIcon size/padding guesses.
    private static final Set<Drawable> TRACKED_BACKGROUNDS = Collections.newSetFromMap(new WeakHashMap<Drawable, Boolean>());
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
        final int w, h, alpha, radiusHash;
        final android.graphics.Bitmap mask, edge;
        MaskCache(int w,int h,int a,int rh,android.graphics.Bitmap m,android.graphics.Bitmap e){
            this.w=w;this.h=h;this.alpha=a;this.radiusHash=rh;this.mask=m;this.edge=e;
        }
    }
    private static MaskCache nativeMask(GradientDrawable d) {
        Rect b=d.getBounds();
        int w=b.width(),h=b.height();
        if(w<4||h<4||w>1400||h>1400)return null;
        int rh=Float.floatToIntBits(d.getCornerRadius()) ^ java.util.Arrays.hashCode(d.getCornerRadii());
        MaskCache old=MASKS.get(d);
        if(old!=null && old.w==w && old.h==h && old.alpha==d.getAlpha() && old.radiusHash==rh)return old;
        android.graphics.Bitmap bitmap=null, edge=null;
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
            // Native alpha is the only shape authority. A 1-2 pixel inner edge
            // follows all four corners including OEM antialiasing and clipping.
            int[] edgePixels=new int[w*h];
            // Include all outermost pixels. Off-bitmap neighbours are transparent.
            // The previous 1..w-2 / 1..h-2 loop dropped straight sides entirely
            // when the native shape reached the drawable bounds.
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=y*w+x, center=pixels[i]>>>24;
                if(center<8)continue;
                int left = x==0 ? 0 : pixels[i-1]>>>24;
                int right = x==w-1 ? 0 : pixels[i+1]>>>24;
                int top = y==0 ? 0 : pixels[i-w]>>>24;
                int bottom = y==h-1 ? 0 : pixels[i+w]>>>24;
                int min=Math.min(Math.min(left,right),Math.min(top,bottom));
                int a=Math.max(0,Math.min(center,(center-min)*2));
                edgePixels[i]=(a<<24)|0x00ffffff;
            }
            edge=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            edge.setPixels(edgePixels,0,w,0,0,w,h);
            MaskCache result=new MaskCache(w,h,d.getAlpha(),rh,bitmap,edge);
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
    private static void drawMaterialOnNativeCanvas(GradientDrawable drawable, Canvas canvas) {
        if(Boolean.TRUE.equals(MASK_RENDERING.get()) || !glassEnabled || probeMode==1)return;
        boolean tint=tintMode!=0 && tintStrength>0 && probeMode!=2;
        boolean shine=(highlightEnabled && highlightStrength>0 && probeMode!=3)||probeMode==2;
        if(!tint&&!shine)return;
        MaskCache cache=nativeMask(drawable);
        if(cache==null)return;
        Rect b=drawable.getBounds();
        canvas.save();
        canvas.translate(b.left,b.top);
        if(tint){
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            int c=tintColor();
            int a=Math.round(160f*tintStrength/90f);
            p.setColorFilter(new android.graphics.PorterDuffColorFilter(
                Color.rgb(Color.red(c),Color.green(c),Color.blue(c)),android.graphics.PorterDuff.Mode.SRC_IN));
            p.setAlpha(a);
            canvas.drawBitmap(cache.mask,0,0,p);
            if(nativeDrawLogs++<24)log("MASK_TINT_APPLIED strength="+tintStrength+" alpha="+a);
        }
        if(shine){
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(probeMode==2?220:Math.round(110f*highlightStrength/100f));
            if(probeMode==2)p.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.RED,android.graphics.PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(cache.edge,0,0,p);
            if(nativeDrawLogs++<24)log("MASK_EDGE_APPLIED strength="+highlightStrength+" probe="+probeMode);
        }
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
                            } else if (blurCalls++ < 24) {
                                Object result = param.getResult();
                                log("NATIVE_BLUR_CALL method="+methodName+" signature="+((Method)param.method).toGenericString()+" result="+(result==null?"null":String.valueOf(result))+" argCount="+(param.args==null?0:param.args.length));
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
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.android.systemui".equals(p.packageName) && !"com.android.launcher".equals(p.packageName)) return;
        installed = 0; failed = 0;
        log("V1.9.7 LAYER_ATTRIBUTION_PROBE_LOADED package=" + p.packageName + " process=" + p.processName);
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
        log("V1.9.7 PROBE_READY package=" + p.packageName + " installed=" + installed + " failed=" + failed);
    }
}
