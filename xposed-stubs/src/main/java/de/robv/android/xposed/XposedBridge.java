package de.robv.android.xposed;
public final class XposedBridge {
    private XposedBridge() {}
    public static synchronized void log(String text) { throw new UnsupportedOperationException("Compile-only stub"); }
}
