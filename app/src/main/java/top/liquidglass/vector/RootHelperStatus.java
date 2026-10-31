package top.liquidglass.vector;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Read-only status probe. Never restarts or kills a root process. */
public final class RootHelperStatus {
    private static final String[] NAMES={"99-liquidglass-wallpaper.sh","99-liquidglass-wallpaper-notify.sh"};
    public interface Callback { void complete(String status); }
    private RootHelperStatus() {}
    private static String hex(byte[] a){StringBuilder s=new StringBuilder();for(byte b:a)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static String bundledHash(Context c,String name)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=c.getAssets().open(name)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        return hex(d.digest());
    }
    public static void checkAsync(Context c,Callback callback){
        Context app=c.getApplicationContext();
        new Thread(()->{
            String result=check(app);
            new Handler(Looper.getMainLooper()).post(()->callback.complete(result));
        },"LiquidGlassRootStatus").start();
    }
    private static String check(Context c){
        try{
            String a=bundledHash(c,NAMES[0]), b=bundledHash(c,NAMES[1]);
            // Shell output is structured; no user-controlled values are interpolated.
            String cmd="d=/data/adb/service.d; l=/data/local/tmp/.liquidglass-wallpaper-sync.lock; " +
                "for f in 99-liquidglass-wallpaper.sh 99-liquidglass-wallpaper-notify.sh; do " +
                "if [ -f \"$d/$f\" ]; then sha256sum \"$d/$f\" | cut -d ' ' -f1; else echo MISSING; fi; done; " +
                "p=$(cat \"$l/pid\" 2>/dev/null); " +
                "case \"$p\" in ''|*[!0-9]*) echo STOPPED;; *) " +
                "if [ -r \"/proc/$p/stat\" ]; then " +
                "actual=$(sed 's/.*) //' \"/proc/$p/stat\" 2>/dev/null | awk '{print $20}'); " +
                "expected=$(cat \"$l/starttime\" 2>/dev/null); " +
                "cmdline=$(tr '\\000' ' ' < \"/proc/$p/cmdline\" 2>/dev/null); " +
                "case \"$cmdline\" in *99-liquidglass-wallpaper.sh*) " +
                "if [ -n \"$expected\" ] && [ \"$actual\" = \"$expected\" ]; then echo RUNNING; else echo STOPPED; fi;; " +
                "*) echo STOPPED;; esac; else echo STOPPED; fi;; esac; " +
                "cat \"$l/running_sha256\" 2>/dev/null || true; echo";
            Process p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
            if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();return "Root 状态检查超时，请检查 Root 授权";}
            if(p.exitValue()!=0)return "无法读取 Root 服务状态，请检查 Root 授权";
            byte[] out;
            try(InputStream in=p.getInputStream()){java.io.ByteArrayOutputStream os=new java.io.ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1 && os.size()<8192)os.write(buf,0,n);out=os.toByteArray();}
            String[] lines=new String(out,java.nio.charset.StandardCharsets.UTF_8).trim().split("\\r?\\n");
            if(lines.length<3)return "Root 状态响应不完整，请手动检查服务脚本";
            String disk=lines[0].trim(), notify=lines[1].trim(), state=lines[2].trim();
            boolean current=a.equalsIgnoreCase(disk)&&b.equalsIgnoreCase(notify);
            if(!current)return "Root 脚本不是最新版本：请点击「检查并更新 Root 辅助脚本」，更新后重启手机替换旧服务进程";
            if(!"RUNNING".equals(state))return "Root 脚本已是最新，但服务进程未运行；建议重启手机检查";
            String running=lines.length>=4?lines[3].trim():"";
            if(!a.equalsIgnoreCase(running))return "Root 脚本已是最新，但运行中的仍是旧服务进程；请重启手机替换";
            return "Root 脚本已是最新 · 当前服务进程也是最新版本";
        }catch(Exception e){return "Root 状态暂不可用："+e.getClass().getSimpleName();}
    }
}
