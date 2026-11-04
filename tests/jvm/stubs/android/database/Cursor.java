package android.database;
public class Cursor implements AutoCloseable {
    public int getCount() { return 1; }
    public int getColumnCount() { return 1; }
    public boolean moveToFirst() { return true; }
    public String getString(int i) { return android.provider.Settings.Global.value; }
    public void close() {}
}
