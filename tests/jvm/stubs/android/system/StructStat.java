package android.system;
public class StructStat {
    public final long st_size;
    public final int st_mode;
    public final StructTimespec st_mtim;
    public StructStat(long size, int mode, long mtimeSec) { st_size = size; st_mode = mode; st_mtim = new StructTimespec(mtimeSec); }
}
