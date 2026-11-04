package android.util;
// Logs nothing, so no fixture value is printed.
public final class Log {
    public static int d(String t, String m) { return 0; }
    public static int d(String t, String m, Throwable e) { return 0; }
    public static int i(String t, String m) { return 0; }
    public static int w(String t, String m) { return 0; }
    public static int w(String t, String m, Throwable e) { return 0; }
    public static int e(String t, String m) { return 0; }
    public static int e(String t, String m, Throwable e) { return 0; }
}
