package top.liquidglass.vector;

import android.graphics.BitmapShader;
import android.provider.Settings;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.util.Log;
import android.view.View;
import java.util.WeakHashMap;

/**
 * Opt-in V230 glass material; a SINGLE surface height field drives the normal,
 * transmission displacement, Fresnel response and wallpaper-derived reflection.
 * Never paints an extra rim on top of the production glass; the V229 shader is
 * left untouched and is the direct fallback when disabled or compilation fails.
 */
final class UnifiedGlass230 {
    private UnifiedGlass230(){}
    private static final WeakHashMap<View,RuntimeShader> CACHE=new WeakHashMap<>();
    // V240: recoverable shader failures (preserved in V241); no permanent process-wide kill switch.
    private static int consecutiveFailures240=0;
    private static long retryAfter240=0L;
    private static boolean lastEnabled240=false;
    static void reset240(String reason){
        synchronized(CACHE){ CACHE.clear(); }
        consecutiveFailures240=0;
        retryAfter240=0L;
        Log.i("LiquidGlass240","OPTICAL_RESET_240 reason="+reason);
    }
    private static int logs=0;
    private static volatile int diagnostic235=0;
    private static volatile int refractionAB244=0;
    private static final String REFRACTION_AB_KEY_244="liquidglass_refraction_ab_244";
    private static int probeCounter235=0;
    private static int probeLogs235=0;
    private static int routeLogs237=0;
    // Diagnostic is read-only and intentionally NOT part of LG230 persistence.
    private static final String DIAG_KEY_235="liquidglass_optical_diagnostic_235";

    private static final String AGSL="""
        uniform shader wallpaper;
        uniform shader blurredWallpaper;
        uniform float4 bounds;
        uniform float4 validBounds;
        uniform float radius;
        uniform float strength230;
        uniform float thickness230;
        uniform float environment230;
        uniform float quality230;
        uniform float blurMix;
        uniform float diagnostic235;
        uniform float refractionAB244;

        // Signed distance: positive within this particular rounded container.
        float inside230(float2 p){
            float2 halfSize=max((bounds.zw-bounds.xy)*0.5,float2(1.0));
            float r=clamp(radius,0.0,min(halfSize.x,halfSize.y));
            float2 q=abs(p-(bounds.xy+bounds.zw)*0.5)-(halfSize-r);
            return r-length(max(q,float2(0.0)))-min(max(q.x,q.y),0.0);
        }
        // V245: native-radius-aware, continuously differentiable rounded lens.
        // Use a smooth rounded-rectangle distance instead of a quartic superellipse
        // (which produced a square-looking central shoulder and wrong corner radius).
        // Every join is softened; no nearest-edge branch or diagonal normal seam.
        // V246: one smooth signed-distance field for the entire rounded shape.
        // Unlike four independent edge ramps, corners and sides share a
        // single scalar height field and therefore a single normal.
        float smoothMax246(float a,float b,float eps){
            return 0.5*(a+b+sqrt((a-b)*(a-b)+eps*eps));
        }
        float smoothPositive246(float a,float eps){
            return 0.5*(a+sqrt(a*a+eps*eps));
        }
        float signedOutside246(float2 p){
            float2 halfSize=max((bounds.zw-bounds.xy)*0.5,float2(1.0));
            float r=clamp(radius,1.0,min(halfSize.x,halfSize.y));
            float2 center=(bounds.xy+bounds.zw)*0.5;
            float eps=max(0.35,min(halfSize.x,halfSize.y)*0.003);
            float2 v=p-center;
            float2 a=sqrt(v*v+float2(eps*eps));
            float2 q=a-(halfSize-float2(r));
            float2 outside=float2(smoothPositive246(q.x,eps),
                                  smoothPositive246(q.y,eps));
            float interior=min(smoothMax246(q.x,q.y,eps),0.0);
            return length(outside)+interior-r;
        }
        // V248: a single full-domain superelliptic optical height field.
        // Unlike a clamped distance-to-edge ramp, this has no rectangular
        // interior plateau and no independent X/Y shoulder junctions.
        // The existing rounded-rectangle clip still defines the silhouette.
        float surface230(float2 p){
            float2 size=max(bounds.zw-bounds.xy,float2(2.0));
            float2 center=(bounds.xy+bounds.zw)*0.5;
            float2 uv=(p-center)/max(size*0.5,float2(1.0));
            float2 uv2=uv*uv;
            float radial4=uv2.x*uv2.x+uv2.y*uv2.y;
            // Exponential decay gives continuous curvature across the entire
            // interior; no clamp(t,1) and no min/max edge selection.
            float curvature=mix(0.95,1.75,clamp(thickness230,0.0,1.0));
            float dome=exp(-curvature*radial4);
            // Narrow optical edge transition, separate from the body dome.
            // Smoothstep has matching zero first derivative at both ends.
            float shortSide=min(size.x,size.y);
            float depth=max(0.0,-signedOutside246(p));
            float rimWidth=max(1.0,shortSide*0.055);
            float rimT=clamp(depth/rimWidth,0.0,1.0);
            float rim=rimT*rimT*(3.0-2.0*rimT);
            return dome*rim;
        }
        float2 normalSlope230(float2 p){
            float e=1.0;
            return float2(surface230(p+float2(e,0.0))-surface230(p-float2(e,0.0)),
                          surface230(p+float2(0.0,e))-surface230(p-float2(0.0,e)))/(2.0*e);
        }
        // V239: Convert derivatives from screen pixels to normalized optical slopes.
        // The V238 derivatives were tiny on large folders and therefore generated
        // almost no visible wallpaper displacement even at maximum UI strength.
        float2 opticalSlope239(float2 slope){
            float2 size=max(bounds.zw-bounds.xy,float2(2.0));
            float2 normalized=slope*size*0.5;
            return normalized/(1.0+length(normalized)*0.42);
        }
        float2 warp230(float2 p,float2 slope){
            float2 size=max(bounds.zw-bounds.xy,float2(2.0));
            float shortSide=min(size.x,size.y);
            float2 ns=opticalSlope239(slope);
            float strength=clamp(strength230,0.0,1.0);
            // One continuous displacement field, not four independent edge warps.
            // Smoothly saturate at the configured maximum without a hard cut.
            float amplitude=min(shortSide*0.115,32.0)*(0.20+0.80*thickness230)*strength;
            // 0: original V243 mapping, 1: reversed sampling direction.
            // 2: reversed mapping with a smooth center-attenuation experiment.
            // All three use the identical seam-free V243 normal field.
            float direction=refractionAB244<0.5?-1.0:1.0;
            float2 raw=direction*ns*amplitude;
            if(refractionAB244>1.5){
                float2 uv=(p-(bounds.xy+bounds.zw)*0.5)/(size*0.5);
                float centerWeight=smoothstep(0.10,0.70,length(uv));
                raw*=mix(0.25,1.0,centerWeight);
            }
            float limit=min(shortSide*0.14,36.0);
            return raw*limit/sqrt(limit*limit+dot(raw,raw)+0.0001);
        }
        // V249 experimental reference-inspired edge sampling map (AB=3).
        // Unlike a painted rim, this moves the actual wallpaper sample.
        // The derivative bound is controlled by a profile amplitude/width ratio;
        // the legacy 0/1/2 algorithms remain byte-for-byte unchanged.
        float2 opticalReference249(float2 p){
            float2 size=max(bounds.zw-bounds.xy,float2(2.0));
            float shortSide=min(size.x,size.y);
            float width=max(4.0,min(shortSide*0.19,48.0));
            float depth=max(0.0,-signedOutside246(p));
            float t=clamp(depth/width,0.0,1.0);
            // Inward-going sample shift that vanishes continuously at the
            // outer silhouette and at the inner end of the optical shoulder.
            float profile=16.0*t*t*(1.0-t)*(1.0-t);
            float e=1.0;
            float2 grad=float2(signedOutside246(p+float2(e,0.0))
                -signedOutside246(p-float2(e,0.0)),
                signedOutside246(p+float2(0.0,e))
                -signedOutside246(p-float2(0.0,e)))/(2.0*e);
            float2 n=grad/sqrt(dot(grad,grad)+0.0001);
            float amplitude=width*0.12*clamp(strength230,0.0,1.0)
                *(0.30+0.70*clamp(thickness230,0.0,1.0));
            return -n*(amplitude*profile);
        }
        float2 safe230(float2 p){
            float2 lo=validBounds.xy+float2(2.0);
            float2 hi=max(lo+float2(1.0),validBounds.zw-float2(2.0));
            return clamp(p,lo,hi);
        }
        half4 transmission230(float2 samplePos){
            float2 src=safe230(samplePos);
            half4 clear=wallpaper.eval(src);
            half4 softened=blurredWallpaper.eval(src);
            return mix(clear,softened,half(clamp(blurMix,0.0,1.0)));
        }
        half4 main(float2 p){
            float2 slope=normalSlope230(p);
            float2 delta=refractionAB244>2.5?opticalReference249(p):warp230(p,slope);
            // V240: a smooth valid-domain falloff prevents hard-clamp lines across
        // faces and wallpaper details. Fade the warp before it hits source edges.
        float2 innerLo=validBounds.xy+float2(3.0);
        float2 innerHi=validBounds.zw-float2(3.0);
        float margin=min(min(p.x-innerLo.x,innerHi.x-p.x),
                         min(p.y-innerLo.y,innerHi.y-p.y));
        float edgeGuard=smoothstep(0.0,16.0,max(0.0,margin));
        float2 q=safe230(p+delta*edgeGuard);
            // V235: isolate actual shader stages without replacing the production path.
            // 1 displacement, 2 normals, 3 continuous surface, 4 raw source,
            // 5 warped source only, 6 final material (same as mode 0).
            if(diagnostic235>0.5 && diagnostic235<1.5){
                float2 size=max(bounds.zw-bounds.xy,float2(1.0));
                float2 disp=(q-p)/max(size,float2(1.0));
                return half4(half3(clamp(0.5+disp.x*5.0,0.0,1.0),
                    clamp(0.5+disp.y*5.0,0.0,1.0),0.5),1.0);
            }
            if(diagnostic235>1.5 && diagnostic235<2.5){
                float3 nn=normalize(float3(-slope*(13.0+8.0*thickness230),1.0));
                return half4(half3(nn*0.5+0.5),1.0);
            }
            if(diagnostic235>2.5 && diagnostic235<3.5){
                float h=surface230(p);
                return half4(half3(h,h,h),1.0);
            }
            if(diagnostic235>3.5 && diagnostic235<4.5){
                return transmission230(p);
            }
            if(diagnostic235>4.5 && diagnostic235<5.5){
                return transmission230(q);
            }
            float3 n=normalize(float3(-opticalSlope239(slope)*(0.75+0.85*thickness230),1.0));
            float grazing=clamp(1.0-n.z,0.0,1.0);
            float edgeZone=smoothstep(0.005,0.22,grazing);
            half4 base=transmission230(q);
            if(quality230>0.5 && edgeZone>0.07){
                float span=0.58;
                half4 aa5=(base + transmission230(q+float2(span,0.0))
                    +transmission230(q+float2(-span,0.0))
                    +transmission230(q+float2(0.0,span))
                    +transmission230(q+float2(0.0,-span)))/5.0;
                if(quality230>1.5){
                    half4 aa9=(aa5*5.0
                        +transmission230(q+float2(span,span))
                        +transmission230(q+float2(-span,span))
                        +transmission230(q+float2(span,-span))
                        +transmission230(q+float2(-span,-span)))/9.0;
                    base=mix(base,aa9,half(edgeZone*0.65));
                } else {
                    base=mix(base,aa5,half(edgeZone*0.65));
                }
            }
            // Schlick Fresnel, shared surface normal. The reflected environment
            // is approximated from actual wallpaper pixels, not a fixed tint.
            float cosTheta=clamp(n.z,0.0,1.0);
            float f0=0.04;
            float fresnel=f0+(1.0-f0)*pow(1.0-cosTheta,5.0);
            float2 envDir=normalize(float2(-n.x-0.23,-n.y-0.17)+float2(0.0001));
            float envReach=(8.0+20.0*environment230)*strength230;
            float3 envA=float3(wallpaper.eval(safe230(p+envDir*envReach)).rgb);
            float3 envB=float3(wallpaper.eval(safe230(p-envDir*envReach*0.55)).rgb);
            float3 envRgb=mix(envA,envB,0.25);
            float3 baseRgb=float3(base.rgb);
            float reflectWeight=clamp(fresnel*environment230*(0.25+0.75*strength230),0.0,0.24);
            // Transmission remains dominant. Reflection is a local perturbation
            // of the real wallpaper, never an opaque colored overlay.
            float3 color=mix(baseRgb,envRgb,reflectWeight);
            float3 light=normalize(float3(-0.48,-0.58,0.66));
            float spec=pow(clamp(dot(n,light),0.0,1.0),24.0)*edgeZone;
            color+=(1.0-color)*spec*0.075*environment230*strength230;
            return half4(half3(clamp(color,0.0,1.0)),1.0);
        }
        """;

    static boolean draw(View view,Canvas canvas,RectF rect,float radius,
            BitmapShader wallpaper,BitmapShader blurredWallpaper,RectF valid,float blurMix){
        return drawInternal287(view,canvas,rect,radius,wallpaper,blurredWallpaper,valid,blurMix,false);
    }
    // V288.87: opt-in reuse of the ORIGINAL V230 AGSL curve inside the safe
    // FolderIcon-native Canvas. Does NOT change the UI material enable switch
    // nor the old UnifiedGlass230.draw() contract used by legacy surfaces.
    // The three actual V230 slider values are taken from MaterialConfig230.
    static boolean drawExperimental287(View view,Canvas canvas,RectF rect,float radius,
            BitmapShader wallpaper,BitmapShader blurredWallpaper,RectF valid,float blurMix){
        return drawInternal287(view,canvas,rect,radius,wallpaper,blurredWallpaper,valid,blurMix,true);
    }
    private static boolean drawInternal287(View view,Canvas canvas,RectF rect,float radius,
            BitmapShader wallpaper,BitmapShader blurredWallpaper,RectF valid,float blurMix,
            boolean forceExperimental287){
        MaterialConfig230.State s=MaterialConfig230.get();
        // Settings.Global is a transient, opt-in test switch; normal mode is 0.
        // Sampling every 60 draws avoids a binder read for every frame.
        if((++probeCounter235 % 60)==1){
            try{
                int next=Settings.Global.getInt(view.getContext().getContentResolver(),DIAG_KEY_235,0);
                diagnostic235=Math.max(0,Math.min(6,next));
                int ab=Settings.Global.getInt(view.getContext().getContentResolver(),REFRACTION_AB_KEY_244,0);
                refractionAB244=Math.max(0,Math.min(3,ab));
            }catch(Throwable ignored){diagnostic235=0;}
        }
        if(!s.enabled && !forceExperimental287){
            if(lastEnabled240){reset240("disabled");}
            lastEnabled240=false;
            return false;
        }
        if(!lastEnabled240){reset240("enabled");lastEnabled240=true;}
        if(android.os.SystemClock.uptimeMillis()<retryAfter240)return false;
        try{
            RuntimeShader shader;
            synchronized(CACHE){
                shader=CACHE.get(view);
                if(shader==null){
                    shader=new RuntimeShader(AGSL);
                    CACHE.put(view,shader);
                    Log.i("LiquidGlass243","MATERIAL_SHADER_CREATED_249 opticalReferenceAB3=true");
                }
            }
            shader.setInputShader("wallpaper",wallpaper);
            shader.setInputShader("blurredWallpaper",blurredWallpaper);
            shader.setFloatUniform("bounds",rect.left,rect.top,rect.right,rect.bottom);
            shader.setFloatUniform("validBounds",valid.left,valid.top,valid.right,valid.bottom);
            shader.setFloatUniform("radius",Math.max(0f,radius));
            shader.setFloatUniform("strength230",s.strength/100f);
            shader.setFloatUniform("thickness230",s.thickness/100f);
            shader.setFloatUniform("environment230",s.environment/100f);
            shader.setFloatUniform("quality230",(float)s.quality);
            shader.setFloatUniform("blurMix",Math.max(0f,Math.min(1f,blurMix)));
            shader.setFloatUniform("diagnostic235",(float)diagnostic235);
            shader.setFloatUniform("refractionAB244",(float)refractionAB244);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            paint.setShader(shader);
            canvas.drawRoundRect(rect,radius,radius,paint);
            if(routeLogs237++<20)Log.i("LiquidGlass239",
                "OPTICAL_PIPELINE_244 wallpaper=bound blurred=bound bounds="+rect.toShortString()+
                " valid="+valid.toShortString()+" blurMix="+blurMix+
                " mode="+diagnostic235+" ab="+refractionAB244+" material=singlePass");
            if(diagnostic235!=0 && probeLogs235++<24)Log.i("LiquidGlass235",
                "OPTICAL_STAGE mode="+diagnostic235+" bounds="+rect.toShortString()+
                " valid="+valid.toShortString()+" blurMix="+blurMix);
            if(logs++<16)Log.i("LiquidGlass230","MATERIAL_RENDER_249 dimensions="+rect.width()+"x"+rect.height()+
                " strength="+s.strength+" thick="+s.thickness+" env="+s.environment+" quality="+s.quality);
            if(consecutiveFailures240!=0){
                Log.i("LiquidGlass240","OPTICAL_RECOVERED_240 failures="+consecutiveFailures240);
                consecutiveFailures240=0;
                retryAfter240=0L;
            }
            return true;
        }catch(Throwable fail){
            // Fall back to V229 for this frame, then rebuild with bounded backoff.
            // Avoid tight retry loops if a device rejects RuntimeShader.
            consecutiveFailures240=Math.min(12,consecutiveFailures240+1);
            long delay=Math.min(30000L,250L*(1L<<Math.min(7,consecutiveFailures240-1)));
            retryAfter240=android.os.SystemClock.uptimeMillis()+delay;
            synchronized(CACHE){CACHE.remove(view);}
            Log.e("LiquidGlass240","OPTICAL_FAILURE_240 attempt="+consecutiveFailures240+
                " retryMs="+delay+" fallback=V229",fail);
            return false;
        }
    }
}
