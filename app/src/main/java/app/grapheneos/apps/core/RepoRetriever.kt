package app.grapheneos.apps.core;

import app.grapheneos.apps.BuildConfig
import app.grapheneos.apps.util.AtomicFile2
import app.grapheneos.apps.util.openConnection
import app.grapheneos.apps.util.readByteArray
import app.grapheneos.apps.util.readString
import app.grapheneos.apps.util.throwResponseCodeException
import app.grapheneos.apps.util.writeByteArray
import app.grapheneos.apps.util.writeString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_OK
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException

private const val METADATA_VERSION = 1
private const val CACHE_FILE_VERSION = 2

private const val MIN_TIMESTAMP = 1_770_000_000L
private const val PUBLIC_KEY = BuildConfig.REPO_PUBLIC_KEY
private const val KEY_VERSION = BuildConfig.REPO_KEY_VERSION

private val cacheFiles = Catalog.entries.associateWith { AtomicFile2(it.cacheName) }

// Our catalog. Both servers serve it, so Automatic also probes it.
internal const val CATALOG_PATH = "metadata.$METADATA_VERSION.$KEY_VERSION.sjson"

internal fun requireCatalogConfiguration() {
    if (PUBLIC_KEY.isEmpty() || !KEY_VERSION.matches(Regex("0|[1-9][0-9]{0,5}"))) {
        throw GeneralSecurityException("Catalog verification key is not configured")
    }
    // Delegate public material parsing to the existing verifier.
    FileVerifier(PUBLIC_KEY)
}

// Each catalog is fetched and verified with its own key. A failure of one keeps the other's update.
fun fetchRepo(currentRepo: Repo): Repo {
    requireCatalogConfiguration()
    var failure: Exception? = null
    val parts = currentRepo.parts.map { part ->
        try {
            fetchCatalog(part)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null) failure = e
            part
        }
    }
    if (parts.indices.all { parts[it] === currentRepo.parts[it] }) {
        failure?.let { throw it }
        return currentRepo
    }
    val repo = Repo(parts)
    failure?.let { throw PartialRepoUpdate(repo, it) }
    return repo
}

private fun fetchCatalog(part: CatalogPart): CatalogPart {
    val url = "$REPO_BASE_URL/${part.catalog.path}"

    return if (!part.isDummy) {
        openConnection(null, url) {
            setRequestProperty("If-None-Match", part.eTag)
        }.use { conn ->
            when (conn.v.responseCode) {
                HTTP_OK -> {
                    fetchInner(conn.v, part.catalog,
                        // in case MIN_TIMESTAMP was updated
                        maxOf(part.timestamp, MIN_TIMESTAMP))
                }
                HTTP_NOT_MODIFIED -> {
                    part
                }
                else -> throwResponseCodeException(conn.v)
            }
        }
    } else {
        openConnection(null, url, {}).use { conn ->
            if (conn.v.responseCode != HTTP_OK) {
                throwResponseCodeException(conn.v)
            }

            fetchInner(conn.v, part.catalog, MIN_TIMESTAMP)
        }
    }
}

private fun fetchInner(conn: HttpURLConnection, catalog: Catalog, minTimestamp: Long): CatalogPart {
    val eTag = conn.getHeaderField("ETag") ?: ""

    val unverifiedBytes = conn.inputStream.use {
        val bytes = de.diamaneos.downloads.DownloadTransport.readBounded(it, 8 * 1024 * 1024)
        if (bytes.size < 103) throw GeneralSecurityException("Unexpected catalog size")
        bytes
    }

    // format:
    // JSON as UTF-8 string
    // 1 byte of newline
    // 100 bytes of base64 encoded signature (its real size is 74 bytes)
    // 1 byte of newline

    val unverifiedJson: ByteArray = unverifiedBytes.copyOfRange(0, unverifiedBytes.size - 102)
    val signature: String =
        unverifiedBytes.copyOfRange(unverifiedBytes.size - 101, unverifiedBytes.size - 1)
            .toString(UTF_8)

    FileVerifier(catalog.publicKey).verifySignature(unverifiedJson, signature)

    val verifiedJson = unverifiedJson

    val part = CatalogPart(catalog, JSONObject(verifiedJson.toString(UTF_8)), eTag)

    if (part.timestamp < minTimestamp) {
        throw GeneralSecurityException("repo downgrade")
    }

    val baos = ByteArrayOutputStream(verifiedJson.size + 100)
    DataOutputStream(baos).let {
        it.writeInt(CACHE_FILE_VERSION)
        it.writeString(catalog.publicKey)
        it.writeString(catalog.keyVersion)
        it.writeString(eTag)
        it.writeByteArray(verifiedJson)
    }

    cacheFiles[catalog]!!.write(baos.toByteArray())

    return part
}

fun getCachedRepo(): Repo {
    if (PUBLIC_KEY.isEmpty()) return Repo(Catalog.entries.map { createDummy(it) })
    requireCatalogConfiguration()
    return Repo(Catalog.entries.map { getCachedCatalog(it) })
}

private fun getCachedCatalog(catalog: Catalog): CatalogPart {
    val bytes = cacheFiles[catalog]!!.read() ?: return createDummy(catalog)

    val dis = DataInputStream(ByteArrayInputStream(bytes))
    val fileVersion = dis.readInt()
    if (fileVersion != CACHE_FILE_VERSION) return createDummy(catalog)
    if (dis.readString() != catalog.publicKey || dis.readString() != catalog.keyVersion) {
        return createDummy(catalog)
    }

    val eTag = dis.readString()

    val json = dis.readByteArray().toString(UTF_8)
    check(dis.available() == 0)

    return CatalogPart(catalog, JSONObject(json), eTag)
}

// make a dummy catalog to remove the need to check for null everywhere
private fun createDummy(catalog: Catalog): CatalogPart {
    val jo = JSONObject().apply {
        put("time", MIN_TIMESTAMP)
        put("packages", JSONObject())
    }
    return CatalogPart(catalog, jo, eTag = "", isDummy = true)
}
