package android.net;
public class Uri {
    public final String value;
    public Uri(String s) { value = s; }
    public static Uri fromParts(String scheme, String ssp, String fragment) { return new Uri(scheme + ":" + ssp); }
}
