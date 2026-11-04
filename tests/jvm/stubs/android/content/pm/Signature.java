package android.content.pm;
public class Signature {
    private final byte[] b;
    public Signature(byte[] b) { this.b = b.clone(); }
    public byte[] toByteArray() { return b.clone(); }
}
