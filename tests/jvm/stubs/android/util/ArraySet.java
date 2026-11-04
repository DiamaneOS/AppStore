package android.util;
public class ArraySet<E> extends java.util.LinkedHashSet<E> {
    public ArraySet() {}
    public ArraySet(int capacity) { super(Math.max(capacity, 1)); }
    public ArraySet(java.util.Collection<? extends E> c) { super(c); }
    public E valueAt(int i) { return new java.util.ArrayList<>(this).get(i); }
}
