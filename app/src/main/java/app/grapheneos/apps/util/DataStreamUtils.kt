package app.grapheneos.apps.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8

fun DataInputStream.readByteArray(): ByteArray {
    val len = readInt()
    // A damaged length must not allocate more than the rest of the data.
    if (len < 0 || len > available()) {
        throw IOException("Invalid length")
    }
    val arr = ByteArray(len)
    check(read(arr) == len)
    return arr
}

fun DataOutputStream.writeByteArray(arr: ByteArray) {
    writeInt(arr.size)
    write(arr)
}

fun DataInputStream.readString() = readByteArray().toString(UTF_8)

fun DataOutputStream.writeString(s: String) = writeByteArray(s.toByteArray(UTF_8))
