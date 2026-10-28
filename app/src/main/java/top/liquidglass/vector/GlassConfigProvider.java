package top.liquidglass.vector;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
public final class GlassConfigProvider extends ContentProvider {
    public static final Uri URI=Uri.parse("content://top.liquidglass.vector.config/settings");
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        if(!URI.equals(uri))return null;
        android.content.SharedPreferences p=getContext().getSharedPreferences("glass",0);
        MatrixCursor c=new MatrixCursor(new String[]{"enabled","alpha","tint","tint_strength","highlight","diagnostics","highlight_strength","probe_mode","highlight_width","directional_light","native_blur_experiment"});
        int old=p.getInt("alpha",210);
        c.addRow(new Object[]{p.getBoolean("enabled",false)?1:0,p.getInt("alpha",p.getInt("alpha_normal",old)),
            p.getInt("tint",0),p.getInt("tint_strength",30),p.getBoolean("highlight",false)?1:0,p.getBoolean("diagnostics",true)?1:0,p.getInt("highlight_strength",25),p.getInt("probe_mode",0),p.getInt("highlight_width",2),p.getBoolean("directional_light",true)?1:0,p.getInt("native_blur_experiment",0)});
        return c;
    }
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/vnd.liquidglass.settings";}
    @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
