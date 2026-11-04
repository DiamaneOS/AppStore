package jvmshim;
// Android's FileInputStream(FileDescriptor) does not own the descriptor, OpenJDK's closes it. The
// harness compiles fd-based streams of the app against this class.
public class FileInputStream extends java.io.InputStream {
    private final java.io.FileDescriptor fd;
    public FileInputStream(java.io.FileDescriptor fd) { this.fd = fd; }
    @Override public int read() throws java.io.IOException { byte[] b = new byte[1]; return read(b, 0, 1) == -1 ? -1 : b[0] & 0xff; }
    @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
        try { return android.system.Os.raf(fd).read(b, off, len); }
        catch (android.system.ErrnoException e) { throw new java.io.IOException(e); }
    }
}
