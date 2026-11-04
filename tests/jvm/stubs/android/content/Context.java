package android.content;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public android.content.pm.PackageManager getPackageManager() { return android.content.pm.PackageManager.instance; }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public <T> T getSystemService(Class<T> c) { return null; }
    public int checkSelfPermission(String p) { return -1; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public String getString(int id, Object... args) { return "string" + id; }
    public String getPackageName() { return "app.grapheneos.apps"; }
}
