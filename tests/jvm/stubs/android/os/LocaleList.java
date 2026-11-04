package android.os;
public class LocaleList {
    private final java.util.Locale[] list;
    public LocaleList(java.util.Locale... list) { this.list = list; }
    public static LocaleList getEmptyLocaleList() { return new LocaleList(); }
    public boolean isEmpty() { return list.length == 0; }
    public int size() { return list.length; }
    public java.util.Locale get(int i) { return list[i]; }
    public String toLanguageTags() {
        StringBuilder b = new StringBuilder();
        for (java.util.Locale l : list) { if (b.length() > 0) b.append(','); b.append(l.toLanguageTag()); }
        return b.toString();
    }
}
