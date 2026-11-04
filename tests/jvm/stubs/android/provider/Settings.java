package android.provider;
public class Settings {
    public static class Global {
        public static String value = "2:primary_eu";
        public static final String VALUE = "value";
        public static android.net.Uri getUriFor(String k) { return new android.net.Uri(k); }
        public static boolean putString(android.content.ContentResolver r, String k, String v) { return false; }
    }
}
