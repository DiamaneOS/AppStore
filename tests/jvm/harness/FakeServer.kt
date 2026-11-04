package harness

import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.cert.Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

// Both download servers in memory. Nothing leaves the process.
object FakeServer {
    // cut: the body ends early at this offset. reset: the connection drops at this offset.
    class Reply(val code: Int = 200, val body: ByteArray = ByteArray(0), val etag: String? = null,
                val cut: Long = -1, val reset: Long = -1, val honorRange: Boolean = true,
                val contentRange: String? = null)

    val replies = ConcurrentHashMap<String, Reply>()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun reset() {
        replies.clear()
        requests.clear()
    }

    fun install() {
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host == "apps.diamaneos.de" || url.host == "apps-na.diamaneos.de") { "unexpected host" }
                    return Connection(url)
                }
            }
        }
    }

    private class Connection(url: URL) : HttpsURLConnection(url) {
        // A reply for one server comes before one for both
        val reply: Reply get() = replies[url.host + url.path] ?: replies[url.path] ?: Reply(404)
        fun start() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull() ?: 0L
        val ranged get() = start() > 0 && reply.honorRange && reply.code == 200

        override fun connect() { connected = true }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            check(!instanceFollowRedirects)
            requests += "$requestMethod ${url.path} ${getRequestProperty("Range") ?: ""}"
            if (requestMethod == "HEAD") return 200
            val r = reply
            if (ranged) return if (start() >= r.body.size) 416 else 206
            return r.code
        }
        override fun getResponseMessage() = "fixture"
        override fun getContentLengthLong(): Long = reply.body.size - if (ranged) start() else 0L
        override fun getHeaderField(name: String): String? = when (name) {
            "ETag" -> reply.etag
            "Content-Range" -> reply.contentRange ?: if (ranged) "bytes ${start()}-${reply.body.size - 1}/${reply.body.size}" else null
            else -> null
        }
        override fun getInputStream(): InputStream {
            val r = reply
            if (responseCode >= 400) throw IOException("HTTP $responseCode")
            return object : InputStream() {
                var at = if (ranged) start() else 0L
                override fun read(): Int {
                    val b = ByteArray(1)
                    return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xff
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (r.reset in 0..at) throw SocketException("fixture reset")
                    val end = minOf(r.body.size.toLong(), if (r.cut >= 0) r.cut else Long.MAX_VALUE,
                        if (r.reset >= 0) r.reset else Long.MAX_VALUE)
                    if (at >= end) return -1
                    val n = minOf(len.toLong(), end - at).toInt()
                    System.arraycopy(r.body, at.toInt(), b, off, n)
                    at += n
                    return n
                }
            }
        }
        override fun getCipherSuite(): String = throw UnsupportedOperationException()
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = throw UnsupportedOperationException()
    }
}
