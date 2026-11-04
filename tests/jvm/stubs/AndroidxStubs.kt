package androidx.core.util

import android.util.SparseArray

inline fun <T> SparseArray<T>.getOrElse(key: Int, defaultValue: () -> T): T = get(key) ?: defaultValue()
fun <T> SparseArray<T>.keyIterator(): IntIterator = object : IntIterator() {
    private var i = 0
    override fun hasNext() = i < size()
    override fun nextInt() = keyAt(i++)
}
inline val <T> SparseArray<T>.size: Int get() = size()
