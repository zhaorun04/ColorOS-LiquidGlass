package top.liquidglass.vector;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
public final class GlassConfigProvider extends ContentProvider {
    // Wallpaper previews are private app data. Only Launcher can publish; only this app can read.
    private static final String CACHE = "calibration_preview.jpg";
    private boolean callerIsSelf() {
        return android.os.Binder.getCallingUid() == android.os.Process.myUid();
    }
    private boolean callerIsLauncher() {
        int uid=android.os.Binder.getCallingUid();
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        if(packages==null)return false;
        for(String name:packages) if("com.android.launcher".equals(name)
            || "com.android.launcher3".equals(name) || "com.oplus.launcher".equals(name)) return true;
        return false;
    }
    @Override public android.os.ParcelFileDescriptor openFile(Uri uri,String mode) throws java.io.FileNotFoundException {
        if(!"/wallpaper_reference".equals(uri.getPath()) || !"r".equals(mode) || !callerIsLauncher())
            throw new java.io.FileNotFoundException("Not authorized");
        java.io.File f=new java.io.File(getContext().getFilesDir(),"wallpaper_reference.jpg");
        if(!f.isFile() || f.length()<1024 || f.length()>12000000)
            throw new java.io.FileNotFoundException("No valid staged wallpaper reference");
        return android.os.ParcelFileDescriptor.open(f,android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public android.os.Bundle call(String method,String arg,android.os.Bundle extras) {
        android.os.Bundle result=new android.os.Bundle();
        if("referenceInfo".equals(method)) {
            if(!callerIsLauncher() && !callerIsSelf()) {result.putString("status","DENIED_CALLER");return result;}
            java.io.File f=new java.io.File(getContext().getFilesDir(),"wallpaper_reference.jpg");
            if(!f.isFile() || f.length()<1024 || f.length()>12000000) {result.putString("status","MISSING");return result;}
            result.putString("status","OK");
            result.putLong("modified",f.lastModified());
            result.putLong("length",f.length());
            return result;
        }
        if("publishCalibrationPreview".equals(method)) {
            if(!callerIsLauncher()) {result.putString("status","DENIED_CALLER");return result;}
            if(extras==null) {result.putString("status","NO_DATA");return result;}
            byte[] bytes=extras.getByteArray("jpeg");
            int w=extras.getInt("sourceW",0),h=extras.getInt("sourceH",0);
            if(bytes==null || bytes.length==0 || bytes.length>350000 || w<=0 || h<=0) {
                result.putString("status","INVALID_PREVIEW");return result;
            }
            try {
                java.io.File tmp=new java.io.File(getContext().getFilesDir(),CACHE+".tmp");
                java.io.File dst=new java.io.File(getContext().getFilesDir(),CACHE);
                try(java.io.FileOutputStream os=new java.io.FileOutputStream(tmp)){os.write(bytes);os.getFD().sync();}
                if(!tmp.renameTo(dst))throw new java.io.IOException("rename failed");
                getContext().getSharedPreferences("calibration",0).edit()
                    .putInt("sourceW",w).putInt("sourceH",h)
                    .putInt("wallpaperId",extras.getInt("wallpaperId",-1))
                    .putLong("time",System.currentTimeMillis()).apply();
                result.putString("status","SAVED");
            }catch(Exception e){result.putString("status","WRITE_"+e.getClass().getSimpleName());}
            return result;
        }
        if("readCalibrationPreview".equals(method)) {
            if(!callerIsSelf()){result.putString("status","DENIED_CALLER");return result;}
            try {
                java.io.File src=new java.io.File(getContext().getFilesDir(),CACHE);
                if(!src.isFile() || src.length()>350000){result.putString("status","MISSING");return result;}
                byte[] bytes=new byte[(int)src.length()];
                try(java.io.FileInputStream in=new java.io.FileInputStream(src)){
                    int n=0,k;while(n<bytes.length&&(k=in.read(bytes,n,bytes.length-n))>0)n+=k;
                    if(n!=bytes.length)throw new java.io.IOException("short read");
                }
                android.content.SharedPreferences pref=getContext().getSharedPreferences("calibration",0);
                result.putByteArray("jpeg",bytes);
                result.putInt("sourceW",pref.getInt("sourceW",0));
                result.putInt("sourceH",pref.getInt("sourceH",0));
                result.putInt("wallpaperId",pref.getInt("wallpaperId",-1));
                result.putLong("time",pref.getLong("time",0));
                result.putString("status","OK");
            }catch(Exception e){result.putString("status","READ_"+e.getClass().getSimpleName());}
            return result;
        }
        result.putString("status","UNKNOWN_METHOD");return result;
    }

    public static final Uri URI=Uri.parse("content://top.liquidglass.vector.config/settings");
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        if(!URI.equals(uri))return null;
        android.content.SharedPreferences p=getContext().getSharedPreferences("glass",0);
        MatrixCursor c=new MatrixCursor(new String[]{"enabled","alpha","tint","tint_strength","highlight","diagnostics","highlight_strength","probe_mode","highlight_width","directional_light","native_blur_experiment","blur_radius_percent","native_alpha_probe","wallpaper_compare","real_refraction","custom_blur_dp","refraction_percent","glass_tint_opacity","custom_blur_mode","clear_refraction","dynamic_sheen_mode","surface_mode_195","surface_strength_195","surface_softness_195","icon_optics_204","dock_glass_204"});
        int old=p.getInt("alpha",210);
        c.addRow(new Object[]{p.getBoolean("enabled",false)?1:0,p.getInt("alpha",p.getInt("alpha_normal",old)),
            p.getInt("tint",0),p.getInt("tint_strength",30),p.getBoolean("highlight",false)?1:0,p.getBoolean("diagnostics",false)?1:0,p.getInt("highlight_strength",25),p.getInt("probe_mode",0),p.getInt("highlight_width",2),p.getBoolean("directional_light",true)?1:0,(p.getBoolean("enabled",false)?1:0),p.getInt("blur_radius_percent",100),0,p.getBoolean("wallpaper_compare",false)?1:0,p.getBoolean("real_refraction",true)?1:0,p.getInt("custom_blur_dp",14),p.getInt("refraction_percent",35),p.getInt("glass_tint_opacity",18),p.getInt("custom_blur_mode",1),p.getBoolean("clear_refraction",false)?1:0,p.getInt("dynamic_sheen_mode",0),p.getInt("surface_mode_195",3),p.getInt("surface_strength_195",100),p.getInt("surface_softness_195",100),p.getBoolean("icon_optics_204",false)?1:0,p.getBoolean("dock_glass_204",false)?1:0});
        return c;
    }
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/vnd.liquidglass.settings";}
    @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
