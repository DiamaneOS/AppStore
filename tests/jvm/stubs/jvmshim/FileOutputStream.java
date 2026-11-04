package jvmshim;
// See FileInputStream.
public class FileOutputStream extends java.io.OutputStream {
    private final java.io.FileDescriptor fd;
    public FileOutputStream(java.io.FileDescriptor fd) { this.fd = fd; }
    @Override public void write(int b) throws java.io.IOException { write(new byte[]{(byte) b}, 0, 1); }
    @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
        try { android.system.Os.raf(fd).write(b, off, len); }
        catch (android.system.ErrnoException e) { throw new java.io.IOException(e); }
    }
}
