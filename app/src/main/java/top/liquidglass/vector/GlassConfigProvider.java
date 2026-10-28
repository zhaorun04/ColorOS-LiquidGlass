package top.liquidglass.vector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Read-only, non-sensitive module appearance settings shared with hooked Launcher. */
public final class GlassConfigProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://top.liquidglass.vector.config/settings");
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri)) return null;
        android.content.SharedPreferences p = getContext().getSharedPreferences("glass", 0);
        MatrixCursor c = new MatrixCursor(new String[]{"enabled","alpha","diagnostics"});
        c.addRow(new Object[]{p.getBoolean("enabled",false)?1:0, p.getInt("alpha",210), p.getBoolean("diagnostics",true)?1:0});
        return c;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.liquidglass.settings"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
