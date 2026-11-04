package android.content.res;
public class Resources {
    public Configuration getConfiguration() { return new Configuration(); }
    public String getString(int id) { return "string" + id; }
    public String getString(int id, Object... args) { return "string" + id; }
}
