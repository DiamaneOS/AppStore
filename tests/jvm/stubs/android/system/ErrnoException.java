package android.system;
public class ErrnoException extends Exception {
    public final int errno;
    public ErrnoException(String functionName, int errno) { super(functionName + " failed: errno " + errno); this.errno = errno; }
    public ErrnoException(String functionName, int errno, Throwable cause) { this(functionName, errno); initCause(cause); }
    public java.io.IOException rethrowAsIOException() throws java.io.IOException {
        java.io.IOException e = new java.io.IOException(getMessage());
        e.initCause(this);
        throw e;
    }
}
