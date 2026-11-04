package android.content.pm;
public class ApplicationInfo {
    public static final int FLAG_SYSTEM = 1, FLAG_HAS_CODE = 4;
    public int flags = FLAG_HAS_CODE;
    public int privateFlags;
    public boolean enabled = true;
    public String sourceDir;
    public String[] splitSourceDirs;
    public String[] splitNames;
}
