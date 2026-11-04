package android.content.pm;
public class SigningInfo {
    private final Signature[] s;
    private final boolean multiple, past;
    public SigningInfo(Signature[] s, boolean multiple, boolean past) { this.s = s; this.multiple = multiple; this.past = past; }
    public boolean hasMultipleSigners() { return multiple; }
    public boolean hasPastSigningCertificates() { return past; }
    public Signature[] getApkContentsSigners() { return s; }
}
