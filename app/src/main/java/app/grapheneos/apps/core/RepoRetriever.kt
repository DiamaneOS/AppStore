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

// Each catalog is fetched, verified with its own key and parsed on its own. A failing catalog keeps
// its previous part and never stops the other's update.
fun fetchRepo(currentRepo: Repo): Repo {
    requireCatalogConfiguration()
    val previous = currentRepo.parts
    var failure: Exception? = null
    val failed = mutableSetOf<Int>()
    val fetched = previous.mapIndexed { i, part ->
        try {
            fetchCatalog(part)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null) failure = e
            failed += i
            part
        }
    }
    val built = if (fetched.indices.all { fetched[it] === previous[it] }) {
        RepoParts(currentRepo, emptySet(), null)
    } else {
        buildRepo(fetched, previous)
    }
    failed += built.failed
    if (failure == null) failure = built.failure
    val parts = built.repo.parts
    val repo = if (parts.indices.all { parts[it] === previous[it] }) currentRepo else built.repo
    // Caches only take parts that parsed together with the other catalog.
    parts.forEachIndexed { i, part ->
        if (part !== previous[i]) part.cacheBytes?.let { cacheFiles[part.catalog]!!.write(it) }
    }
    failure?.let { throw if (failed.size < parts.size) PartialRepoUpdate(repo, it) else it }
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
                    fetchInner(conn.v, part)
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

            fetchInner(conn.v, part)
        }
    }
}

private fun fetchInner(conn: HttpURLConnection, current: CatalogPart): CatalogPart {
    val catalog = current.catalog
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

    val json = JSONObject(verifiedJson.toString(UTF_8))
    val timestamp = json.getLong("time")

    // A valid but older copy, for example on the other server, counts as not modified.
    if (!current.isDummy && timestamp < current.timestamp) {
        return current
    }
    if (timestamp < catalog.minTimestamp) {
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

    return CatalogPart(catalog, json, eTag, cacheBytes = baos.toByteArray())
}

fun getCachedRepo(): Repo {
    val dummies = Catalog.entries.map { createDummy(it) }
    if (PUBLIC_KEY.isEmpty()) return Repo(dummies)
    requireCatalogConfiguration()
    // A cache this App Store cannot read starts that catalog afresh.
    val parts = Catalog.entries.map { catalog ->
        try {
            getCachedCatalog(catalog)
        } catch (e: Exception) {
            createDummy(catalog)
        }
    }
    return buildRepo(parts, dummies).repo
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
        put("time", catalog.minTimestamp)
        put("packages", JSONObject())
    }
    return CatalogPart(catalog, jo, eTag = "", isDummy = true)
}
