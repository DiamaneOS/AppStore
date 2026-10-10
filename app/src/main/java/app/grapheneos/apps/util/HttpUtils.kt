package app.grapheneos.apps.util

import android.net.Network
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.requireCatalogConfiguration
import de.diamaneos.downloads.DownloadPolicy
import de.diamaneos.downloads.DownloadPolicyClient
import de.diamaneos.downloads.DownloadTransport
import java.net.HttpURLConnection
import java.net.ProtocolException
import javax.net.ssl.HttpsURLConnection
import org.grapheneos.tls.ModernTLSSocketFactory

private val tlsSocketFactory = ModernTLSSocketFactory()

fun openConnection(network: Network?, urlString: String, configure: HttpURLConnection.() -> Unit): ScopedHttpConnection {
    requireCatalogConfiguration()
    val path = DownloadPolicy.appsPath(urlString)
    val connection = DownloadTransport.open(DownloadPolicyClient.read(appContext), true, path,
        { url -> (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection },
        { conn ->
            conn.sslSocketFactory = tlsSocketFactory
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.configure()
        })
    return ScopedHttpConnection(connection)
}

class ScopedHttpConnection(val v: HttpURLConnection) : AutoCloseable {
    override fun close() {
        v.disconnect()
    }
}

fun throwResponseCodeException(conn: HttpURLConnection): Nothing {
    throw ProtocolException("Unexpected HTTP response: ${conn.responseCode} ${conn.responseMessage}, when connecting to ${conn.url}")
}
