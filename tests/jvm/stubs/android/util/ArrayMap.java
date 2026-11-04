package android.util;
public class ArrayMap<K, V> extends java.util.LinkedHashMap<K, V> {
    public ArrayMap() {}
    public ArrayMap(int capacity) { super(Math.max(capacity, 1)); }
    public K keyAt(int i) { return new java.util.ArrayList<>(keySet()).get(i); }
    public V valueAt(int i) { return new java.util.ArrayList<>(values()).get(i); }
}
