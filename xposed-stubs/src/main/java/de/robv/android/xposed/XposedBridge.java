package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
 private XposedBridge() {}
 public static synchronized void log(String text) { throw new UnsupportedOperationException("Compile-only stub"); }
 // Match Xposed's runtime descriptor exactly; Object would cause NoSuchMethodError.
 public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) { throw new UnsupportedOperationException("Compile-only stub"); }
}
