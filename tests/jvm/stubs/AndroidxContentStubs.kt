package androidx.core.content

import android.content.Context

inline fun <reified T : Any> Context.getSystemService(): T? = getSystemService(T::class.java)
