package app.grapheneos.apps.util

import android.net.Network
import app.grapheneos.apps.core.CATALOG_PATH
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.requireCatalogConfiguration
import de.diamaneos.downloads.DownloadPolicy
import de.diamaneos.downloads.DownloadPolicyClient
import de.diamaneos.downloads.DownloadTransport
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import org.grapheneos.tls.ModernTLSSocketFactory

private val tlsSocketFactory = ModernTLSSocketFactory()

private val baseConfigure = DownloadTransport.Configure { conn ->
    conn.sslSocketFactory = tlsSocketFactory
    conn.connectTimeout = 10_000
    conn.readTimeout = 30_000
}

fun openConnection(network: Network?, urlString: String, configure: HttpURLConnection.() -> Unit): ScopedHttpConnection {
    requireCatalogConfiguration()
    val path = DownloadPolicy.appsPath(urlString)
    val connector = DownloadTransport.Connector { url ->
        (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
    }
    val fullConfigure = DownloadTransport.Configure { conn ->
        baseConfigure.apply(conn)
        conn.configure()
    }
    val route = DownloadTransport.route(DownloadPolicyClient.read(appContext), true, CATALOG_PATH,
        connector, baseConfigure)
    val connection = DownloadTransport.open(route, path, 0, connector, fullConfigure)
    return ScopedHttpConnection(connection, route, path, connector, fullConfigure)
}

class ScopedHttpConnection internal constructor(
    val v: HttpsURLConnection,
    private val route: DownloadTransport.Route,
    private val path: String,
    private val connector: DownloadTransport.Connector,
    private val configure: DownloadTransport.Configure,
) : AutoCloseable {
    // A failure or stall mid-way continues on the other server from the same offset.
    fun copyBody(out: OutputStream, offset: Long, total: Long, progress: AtomicLong,
            beforeWrite: () -> Unit): Long =
        DownloadTransport.copy(route, path, v, offset, total, { buffer, count ->
            beforeWrite()
            out.write(buffer, 0, count)
            progress.addAndGet(count.toLong())
        }, connector, configure)

    override fun close() {
        v.disconnect()
    }
}

fun throwResponseCodeException(conn: HttpURLConnection): Nothing {
    throw ProtocolException("Unexpected HTTP response: ${conn.responseCode} ${conn.responseMessage}, when connecting to ${conn.url}")
}
