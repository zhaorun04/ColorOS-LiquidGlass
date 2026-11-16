package top.liquidglass.vector;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** V202: deterministic schema-aligned publisher; no dependence on a stale installed helper. */
final class ConfigPublisher202 {
    private static final String[] KEYS={
        "enabled","alpha","tint","tint_strength","highlight","diagnostics",
        "highlight_strength","probe_mode","highlight_width","directional_light",
        "native_blur_experiment","blur_radius_percent","native_alpha_probe",
        "wallpaper_compare","real_refraction","custom_blur_dp","refraction_percent",
        "glass_tint_opacity","custom_blur_mode","clear_refraction","dynamic_sheen_mode",
        "surface_mode_195","surface_strength_195","surface_softness_195",
        "icon_optics_204","dock_glass_204","nonlinear_107","thickness_107",
        "interior_107","focus_107","reflection_107","optical_style_186","custom_blur_tenths_95","icon_specular_brightness_288199"
    };
    private static final int[] DEFAULTS={
        1,210,0,30,0,0,25,0,2,1,0,100,0,0,0,14,35,18,1,0,0,
        3,100,100,1,1,65,55,20,45,30,0,140,100
    };
    private static boolean boolKey(String k){
        return k.equals("enabled")||k.equals("highlight")||k.equals("diagnostics")||
            k.equals("directional_light")||k.equals("native_blur_experiment")||
            k.equals("native_alpha_probe")||k.equals("wallpaper_compare")||
            k.equals("real_refraction")||k.equals("clear_refraction")||
            k.equals("icon_optics_204")||k.equals("dock_glass_204");
    }
    static String snapshot(Context context){
        SharedPreferences p=context.getSharedPreferences("glass",Context.MODE_PRIVATE);
        StringBuilder s=new StringBuilder("LG1");
        for(int i=0;i<KEYS.length;i++){
            String k=KEYS[i]; int value=DEFAULTS[i];
            try{
                if(p.contains(k)) value=boolKey(k)?(p.getBoolean(k,value!=0)?1:0):p.getInt(k,value);
            }catch(ClassCastException e){Log.w("LiquidGlass202","CONFIG_202_TYPE_MISMATCH "+k);}
            s.append('|').append(value);
        }
        return s.toString();
    }
    // V230: a separate, versioned material record; 33-field LG1 is untouched.
    static String materialSnapshot(Context context){
        return MaterialConfig230.snapshot(context);
    }
    static synchronized boolean publishMaterialVerified(String expected){
        if(expected==null || !expected.matches("LG230(\\|[0-9]+){5}"))return false;
        String current=root("settings get global "+MaterialConfig230.GLOBAL_KEY);
        if(expected.equals(current))return true;
        root("settings put global "+MaterialConfig230.GLOBAL_KEY+" '"+expected+"'");
        boolean ok=expected.equals(root("settings get global "+MaterialConfig230.GLOBAL_KEY));
        Log.i("LiquidGlass230","MATERIAL_ROOT_VERIFIED_230="+ok);
        return ok;
    }
    private static String root(String cmd){
        Process p=null;
        try{
            p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
            StringBuilder result=new StringBuilder();
            BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()));
            // Output is bounded and expected to be a single settings line.
            char[] buf=new char[256]; int n;
            while(result.length()<2048 && (n=r.read(buf,0,Math.min(buf.length,2048-result.length())))!=-1)
                result.append(buf,0,n);
            if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();return null;}
            return p.exitValue()==0?result.toString().trim():null;
        }catch(Throwable t){Log.w("LiquidGlass202","CONFIG_202_ROOT_ERROR "+t.getClass().getSimpleName());return null;}
        finally{if(p!=null)p.destroy();}
    }
    static synchronized boolean publishVerified(String expected){
        if(expected==null||!expected.matches("LG1(\\|-?[0-9]+){34}"))return false;
        String current=root("settings get global liquidglass_boot_snapshot_v1");
        if(expected.equals(current)){Log.i("LiquidGlass202","CONFIG_202_ALREADY_CURRENT");return true;}
        // First try the installed root service for compatibility and its own diagnostics.
        root("sh /data/adb/service.d/99-liquidglass-wallpaper.sh --event-config");
        current=root("settings get global liquidglass_boot_snapshot_v1");
        if(expected.equals(current)){Log.i("LiquidGlass202","CONFIG_202_HELPER_VERIFIED");return true;}
        // Root helper can be absent/stale/locked: publish the same numeric-only
        // schema directly, then verify before notifying Launcher.
        Log.w("LiquidGlass202","CONFIG_202_HELPER_STALE fallback=direct");
        root("settings put global liquidglass_boot_snapshot_v1 '"+expected+"'");
        current=root("settings get global liquidglass_boot_snapshot_v1");
        boolean ok=expected.equals(current);
        Log.i("LiquidGlass202","CONFIG_202_DIRECT_VERIFIED="+ok);
        return ok;
    }
}
