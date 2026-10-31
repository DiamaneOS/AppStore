// SPDX-License-Identifier: Apache-2.0
package app.grapheneos.apps.util

import app.grapheneos.apps.core.Catalog
import app.grapheneos.apps.core.REPO_BASE_URL
import app.grapheneos.apps.core.sha256FromString
import de.diamaneos.downloads.DownloadTransport
import java.net.HttpURLConnection.HTTP_OK
import java.net.ProtocolException
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Icons obey the same origin and redirect policy as the signed catalog and packages. Only an icon of
 * one of the two catalogs is fetched, at most 1 MiB, and it must match the size and SHA-256 that the
 * signed catalog gives, before it reaches the image decoder.
 */
internal object DownloadIconLoader {
    private const val MAX_BYTES = 1024 * 1024
    private val ICON = Regex("packages/[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/icon\\.[A-Za-z0-9]+")

    // An icon of one of the catalogs, under that catalog's prefix.
    fun isIconPath(path: String) =
        Catalog.entries.any { path.startsWith(it.prefix) && path.substring(it.prefix.length).matches(ICON) }

    // Runs on Glide's thread, which is where the download policy is read for the origin.
    fun load(path: String, size: Int, sha256: String): ByteArray {
        if (!isIconPath(path)) throw ProtocolException("Unexpected icon path")
        if (size !in 1..MAX_BYTES) throw ProtocolException("Unexpected icon size")
        val url = "$REPO_BASE_URL/$path"
        val bytes = openConnection(null, url, {}).use { conn ->
            if (conn.v.responseCode != HTTP_OK) throwResponseCodeException(conn.v)
            conn.v.inputStream.use { DownloadTransport.readBounded(it, size) }
        }
        if (bytes.size != size) throw ProtocolException("Icon size mismatch for $path")
        if (!MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(sha256FromString(sha256))) {
            throw GeneralSecurityException("SHA-256 mismatch for $path")
        }
        return bytes
    }
}
