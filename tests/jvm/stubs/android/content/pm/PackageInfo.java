package android.content.pm;
public class PackageInfo {
    public String packageName;
    public String versionName;
    public int versionCode;
    public ApplicationInfo applicationInfo;
    public SigningInfo signingInfo;
    private long longVersionCode;
    public long getLongVersionCode() { return longVersionCode; }
    public void setLongVersionCode(long v) { longVersionCode = v; versionCode = (int) v; }
}
