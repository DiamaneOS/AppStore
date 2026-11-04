package android.system;
// Linux values.
public class OsConstants {
    public static final int O_RDONLY = 0, O_WRONLY = 1, O_RDWR = 2, O_CREAT = 0x40, O_EXCL = 0x80, O_TRUNC = 0x200;
    public static final int SEEK_SET = 0, SEEK_CUR = 1, SEEK_END = 2;
    public static final int S_IRUSR = 0x100, S_IWUSR = 0x80, S_IFREG = 0x8000, S_IFDIR = 0x4000;
    public static final int ENOENT = 2, EBADF = 9, EACCES = 13, EEXIST = 17, ENOTDIR = 20, EIO = 5;
    public static boolean S_ISREG(int mode) { return (mode & 0xF000) == S_IFREG; }
}
