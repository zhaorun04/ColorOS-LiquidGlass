package de.robv.android.xposed;
import java.lang.reflect.Member;
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam { public Object thisObject; public Object[] args; public Object getResult(){return null;} }
    public class Unhook { public Member getHookedMethod(){return null;} public void unhook(){} }
}
