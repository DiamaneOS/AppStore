package android.util;
public class SparseArray<E> {
    private final java.util.TreeMap<Integer, E> map = new java.util.TreeMap<>();
    public void put(int key, E value) { map.put(key, value); }
    public E get(int key) { return map.get(key); }
    public int size() { return map.size(); }
    public int keyAt(int i) { return new java.util.ArrayList<>(map.keySet()).get(i); }
    public E valueAt(int i) { return new java.util.ArrayList<>(map.values()).get(i); }
    public int indexOfKey(int key) { return new java.util.ArrayList<>(map.keySet()).indexOf(key); }
}
