package android.content.pm;
public class PackageManager {
    public static final PackageManager instance = new PackageManager();
    public static final int PERMISSION_GRANTED = 0, SIGNATURE_MATCH = 0, INSTALL_SCENARIO_FAST = 1, INSTALL_SCENARIO_BULK = 2;
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;
    // Installed packages by name, set by the harness.
    public final java.util.Map<String, PackageInfo> installed = new java.util.concurrent.ConcurrentHashMap<>();
    public final PackageInstaller installer = new PackageInstaller();
    public PackageInstaller getPackageInstaller() { return installer; }
    public int checkSignatures(String a, String b) { return 0; }
    public boolean canRequestPackageInstalls() { return false; }
    public static class NameNotFoundException extends Exception {}
}
