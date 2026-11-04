package android.os;
public class ParcelFileDescriptor implements java.io.Closeable {
    private final java.io.FileDescriptor fd;
    private ParcelFileDescriptor(java.io.FileDescriptor fd) { this.fd = fd; }
    public static ParcelFileDescriptor dup(java.io.FileDescriptor fd) { return new ParcelFileDescriptor(fd); }
    public int getFd() { return android.system.Os.intFd(fd); }
    public void close() {}
}
