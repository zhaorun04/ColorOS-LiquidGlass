package top.liquidglass.vector;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Installs only the two bundled helper scripts after explicit root authorization. */
public final class RootHelperUpdater {
    private static final String TAG="LiquidGlassRootUpdate";
    private static final String[] NAMES={"99-liquidglass-wallpaper.sh","99-liquidglass-wallpaper-notify.sh"};
    private RootHelperUpdater() {}
    public interface Callback { void complete(String result); }
    public static void runAsync(Context context, Callback callback) {
        Context app=context.getApplicationContext();
        new Thread(()->{
            String result=install(app);
            if(callback!=null)new Handler(Looper.getMainLooper()).post(()->callback.complete(result));
        },"LiquidGlassRootUpdater").start();
    }
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(java.util.Locale.US,"%02x",v&255));return b.toString();}
    private static String install(Context context) {
        try {
            File dir=new File(context.getFilesDir(),"root-helper-bundle");
            if(!dir.exists()&&!dir.mkdirs())return "无法创建脚本暂存目录";
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            StringBuilder command=new StringBuilder("set -e; umask 077; d=/data/adb/service.d; [ -d \"$d\" ] || mkdir -p \"$d\"; ");
            for(String name:NAMES){
                File f=new File(dir,name);
                try(InputStream in=context.getAssets().open(name);FileOutputStream output=new FileOutputStream(f,false)){
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)output.write(buffer,0,n);
                }
                digest.reset();try(InputStream in=new java.io.FileInputStream(f)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
                String expected=hex(digest.digest());
                String target="/data/adb/service.d/"+name;
                String source=quote(f.getAbsolutePath());
                // Atomic replacement; do not overwrite when identical. Root may read app-private DE/CE files after unlock.
                command.append("if [ ! -f ").append(quote(target)).append(" ] || [ \"$(sha256sum ").append(quote(target)).append(" 2>/dev/null | cut -d ' ' -f1)\" != ").append(quote(expected)).append(" ]; then ")
                    .append("cp ").append(source).append(" ").append(quote(target+".new")).append("; chmod 755 ").append(quote(target+".new"))
                    .append("; mv -f ").append(quote(target+".new")).append(" ").append(quote(target)).append("; fi; ");
            }
            command.append("chmod 755 ").append(quote("/data/adb/service.d/"+NAMES[0])).append(" ").append(quote("/data/adb/service.d/"+NAMES[1])).append("; echo LIQUIDGLASS_HELPER_OK");
            Process p=new ProcessBuilder("su","-c",command.toString()).redirectErrorStream(true).start();
            boolean finished=p.waitFor(25,TimeUnit.SECONDS);
            if(!finished){p.destroyForcibly();return "Root 授权超时，请打开应用重试并检查授权弹窗";}
            String response;try(InputStream in=p.getInputStream()){byte[] buf=new byte[4096];int n=in.read(buf);response=n>0?new String(buf,0,n,StandardCharsets.UTF_8):"";}
            if(p.exitValue()!=0||!response.contains("LIQUIDGLASS_HELPER_OK"))return "更新失败：请检查 Root 授权与 /data/adb/service.d 写入权限 (exit="+p.exitValue()+")";
            context.getSharedPreferences("glass",0).edit().putString("root_helper_installed_version","1.9.95").apply();
            Log.i(TAG,"Root helper scripts installed/verified");
            return "Root 辅助脚本已自动校验并更新；下次开机自动运行";
        }catch(Exception e){Log.w(TAG,"Root helper update failed",e);return "自动更新失败："+e.getClass().getSimpleName();}
    }
}
