package android.content.pm;
public class SigningInfo {
    final Signature[] s, history;
    final boolean multiple, past;
    public SigningInfo(Signature[] s, boolean multiple, boolean past) { this(s, multiple, past, past ? s : null); }
    public SigningInfo(Signature[] s, boolean multiple, boolean past, Signature[] history) { this.s = s; this.multiple = multiple; this.past = past; this.history = history; }
    public boolean hasMultipleSigners() { return multiple; }
    public boolean hasPastSigningCertificates() { return past; }
    public Signature[] getApkContentsSigners() { return s; }
    // As Android: null with several signers, the signers without a rotation record, else the record, oldest first.
    public Signature[] getSigningCertificateHistory() { return multiple ? null : past ? history : s; }
}
