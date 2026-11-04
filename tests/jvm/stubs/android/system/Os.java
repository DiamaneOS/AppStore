package android.system;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;

// File calls on real files, so the native patcher works on the same descriptors. O_TMPFILE is an
// unlinked file in the directory. The harness checks for leaks and for the largest temporary file.
public final class Os {
    private static final java.util.Map<FileDescriptor, RandomAccessFile> open =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    public static int openCount() { return open.size(); }

    public static RandomAccessFile raf(FileDescriptor fd) throws ErrnoException {
        RandomAccessFile r = open.get(fd);
        if (r == null) throw new ErrnoException("fd", OsConstants.EBADF);
        return r;
    }

    public static int intFd(FileDescriptor fd) {
        try {
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("fd");
            f.setAccessible(true);
            return f.getInt(fd);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    public static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        File f = new File(path);
        try {
            RandomAccessFile r;
            boolean isTmp = (flags & 0x400000) != 0;
            if (isTmp) {
                if (!f.isDirectory()) throw new ErrnoException("open", OsConstants.ENOTDIR);
                File t = File.createTempFile("tmpfile", null, f);
                r = new RandomAccessFile(t, "rw");
                if (!t.delete()) throw new ErrnoException("open", OsConstants.EIO);
            } else {
                boolean create = (flags & OsConstants.O_CREAT) != 0;
                if (create && (flags & OsConstants.O_EXCL) != 0 && f.exists()) throw new ErrnoException("open", OsConstants.EEXIST);
                if (!create && !f.exists()) throw new ErrnoException("open", OsConstants.ENOENT);
                if (f.isDirectory()) {
                    // Only opened for fsync.
                    FileDescriptor d = new FileDescriptor();
                    open.put(d, null);
                    return d;
                }
                r = new RandomAccessFile(f, (flags & 3) == OsConstants.O_RDONLY ? "r" : "rw");
                if ((flags & OsConstants.O_TRUNC) != 0) r.setLength(0);
            }
            FileDescriptor fd = r.getFD();
            open.put(fd, r);
            return fd;
        } catch (IOException e) {
            throw new ErrnoException("open", OsConstants.ENOENT, e);
        }
    }

    public static void close(FileDescriptor fd) throws ErrnoException {
        if (!open.containsKey(fd)) throw new ErrnoException("close", OsConstants.EBADF);
        RandomAccessFile r = open.remove(fd);
        if (r == null) return;
        try {
            r.close();
        } catch (IOException e) {
            throw new ErrnoException("close", OsConstants.EIO, e);
        }
    }

    public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        try {
            return new StructStat(raf(fd).length(), OsConstants.S_IFREG, 0);
        } catch (IOException e) {
            throw new ErrnoException("fstat", OsConstants.EIO, e);
        }
    }

    public static StructStat stat(String path) throws ErrnoException {
        File f = new File(path);
        if (!f.exists()) throw new ErrnoException("stat", OsConstants.ENOENT);
        return new StructStat(f.length(), f.isDirectory() ? OsConstants.S_IFDIR : OsConstants.S_IFREG, f.lastModified() / 1000);
    }

    public static long lseek(FileDescriptor fd, long offset, int whence) throws ErrnoException {
        try {
            RandomAccessFile r = raf(fd);
            long base = whence == OsConstants.SEEK_SET ? 0 : whence == OsConstants.SEEK_CUR ? r.getFilePointer() : r.length();
            r.seek(base + offset);
            return r.getFilePointer();
        } catch (IOException e) {
            throw new ErrnoException("lseek", OsConstants.EIO, e);
        }
    }

    public static void fsync(FileDescriptor fd) throws ErrnoException {
        if (!open.containsKey(fd)) throw new ErrnoException("fsync", OsConstants.EBADF);
    }

    public static void rename(String from, String to) throws ErrnoException {
        try {
            java.nio.file.Files.move(new File(from).toPath(), new File(to).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ErrnoException("rename", OsConstants.ENOENT, e);
        }
    }

    public static int write(FileDescriptor fd, byte[] b, int off, int len) throws ErrnoException {
        try {
            raf(fd).write(b, off, len);
            return len;
        } catch (IOException e) {
            throw new ErrnoException("write", OsConstants.EIO, e);
        }
    }

    public static long sendfile(FileDescriptor out, FileDescriptor in, Object offset, long count) throws ErrnoException {
        try {
            byte[] buf = new byte[(int) Math.min(count, 65536)];
            int n = raf(in).read(buf);
            if (n <= 0) return 0;
            raf(out).write(buf, 0, n);
            return n;
        } catch (IOException e) {
            throw new ErrnoException("sendfile", OsConstants.EIO, e);
        }
    }
}
