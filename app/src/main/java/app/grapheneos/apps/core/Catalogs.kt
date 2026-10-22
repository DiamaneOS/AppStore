package app.grapheneos.apps.core

import android.content.pm.SigningInfo
import app.grapheneos.apps.BuildConfig
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.MessageDigest

// DiamaneOS: two signed catalogs. Each package can come from only one of them, so neither key can
// offer the other catalog's apps.

// SHA-256 of our app signing certificates: App Store, Camera (and CameraProcessor), Auditor. Our
// catalog may list only packages signed with one of them.
val DIAMANEOS_SIGNERS = setOf(
    "559ffb104edd9386a39d512063cb7458c5ec0e29c9b52a91068ce950dd768a47",
    "285a091a913f1485ce16f280f06b2609dc1af6cca786e45b6f5356aa7e86fa61",
    "427bfd360586b829475f37abffd2548d93db3618e45c994de416469ef04bc2ec",
)

// Our builds that replace GrapheneOS's. GrapheneOS's catalog never lists them.
val DIAMANEOS_PACKAGES = setOf("app.grapheneos.apps", "app.grapheneos.camera", "de.diamaneos.auditor")

// GrapheneOS's apps we never list. Info shows GrapheneOS's release news, and our Auditor replaces its Auditor.
val EXCLUDED_PACKAGES = setOf("app.grapheneos.info", "app.attestation.auditor")

// GrapheneOS's catalog key 0, from its App Store source. It signs only GrapheneOS's catalog.
const val GRAPHENEOS_CATALOG_KEY = "RWQtZwEu1br1lMh911L3yPOs97cQb9LOks/ALBbqGl21ul695ocWR/ir"

enum class Catalog(
    val publicKey: String,
    val keyVersion: String,
    // Where the catalog and its packages are on the Apps servers.
    val prefix: String,
    val cacheName: String,
    // Older catalogs are refused on a phone without one. Raise both at each rebase, as GrapheneOS does.
    val minTimestamp: Long,
) {
    // Our offline catalog key signs our own apps.
    DiamaneOS(BuildConfig.REPO_PUBLIC_KEY, BuildConfig.REPO_KEY_VERSION, "", "repo", 1_792_500_000L),
    // GrapheneOS's signed catalog and APKs, served unchanged by our servers.
    GrapheneOS(GRAPHENEOS_CATALOG_KEY, "0", "grapheneos/", "repo-grapheneos", 1_792_500_000L);

    val path: String
        get() = "${prefix}metadata.1.$keyVersion.sjson"

    // The package name and the name it may update through the original-package system both count.
    fun lists(packageName: String, originalPackage: String?, source: String?): Boolean {
        // Compared in lower case, so a differently cased name cannot pass for another package.
        val names = listOfNotNull(packageName, originalPackage).map { it.lowercase() }
        return when (this) {
            // Signers and GrapheneOS's names are checked in listedEntries().
            DiamaneOS -> originalPackage == null && source == "DiamaneOS" && names.none { it in EXCLUDED_PACKAGES }
            GrapheneOS -> source != "DiamaneOS" && names.none {
                it in DIAMANEOS_PACKAGES || it in EXCLUDED_PACKAGES || it == "de.diamaneos" || it.startsWith("de.diamaneos.")
            }
        }
    }

    // Our builds use upstream's version x 100 + our revision. Version gates in GrapheneOS's catalog
    // name upstream versions, so they are compared with ours divided by 100.
    fun dependencyVersion(packageName: String, versionCode: Long): Long =
        if (this == GrapheneOS && packageName in DIAMANEOS_PACKAGES) versionCode / 100 else versionCode
}

data class CatalogEntry(val catalog: Catalog, val name: String, val json: JSONObject, val originalPackage: String?,
                   val source: String?, val signers: List<String>)

// The entries the App Store takes. Ours must list one of our signers, and a name GrapheneOS's catalog
// lists is GrapheneOS's, so ours may not list it. While GrapheneOS's catalog is missing, its names
// are unknown and ours lists only DIAMANEOS_PACKAGES.
fun listedEntries(entries: List<CatalogEntry>, grapheneOSMissing: Boolean): List<CatalogEntry> {
    val listed = entries.filter { it.catalog.lists(it.name, it.originalPackage, it.source) }
    val grapheneOS = listed.filter { it.catalog == Catalog.GrapheneOS }
        .flatMap { listOfNotNull(it.name, it.originalPackage) }.map { it.lowercase() }.toSet()
    return listed.filter {
        it.catalog == Catalog.GrapheneOS || (it.name.lowercase() !in grapheneOS && it.signers.size == 1
            && it.signers[0] in DIAMANEOS_SIGNERS && (!grapheneOSMissing || it.name in DIAMANEOS_PACKAGES))
    }
}

// Before install, an APK from our catalog must have one signer, without rotation, that is ours and
// listed in its entry.
fun checkDiamaneOSSigner(name: String, info: SigningInfo?, listed: Array<ByteArray>) {
    val signers = info?.takeIf { !it.hasMultipleSigners() && !it.hasPastSigningCertificates() }?.apkContentsSigners
    val digest = signers?.singleOrNull()?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    if (digest == null || digest.joinToString("") { "%02x".format(it) } !in DIAMANEOS_SIGNERS
        || listed.none { it.contentEquals(digest) }) {
        throw GeneralSecurityException("$name is not signed by its DiamaneOS signer")
    }
}

// One verified catalog, or a placeholder for one not fetched yet. A newly fetched one carries the
// bytes for its cache, written once the whole repo has been parsed.
class CatalogPart(val catalog: Catalog, val json: JSONObject, val eTag: String, val isDummy: Boolean = false,
                  val cacheBytes: ByteArray? = null) {
    val timestamp = json.getLong("time")
}

// One catalog failed and the other is current. The repo holds the current catalog's update, if any.
class PartialRepoUpdate(val repo: Repo, cause: Throwable) : Exception(cause)

class RepoParts(val repo: Repo, val failed: Set<Int>, val failure: Exception?)

// Parses the repo. A part this App Store cannot parse falls back to its previous one, so one catalog
// can never break the other or the App Store's start.
fun buildRepo(parts: List<CatalogPart>, previous: List<CatalogPart>): RepoParts {
    try {
        return RepoParts(Repo(parts), emptySet(), null)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        var failure: Exception = e
        val failed = mutableSetOf<Int>()
        val checked = parts.mapIndexed { i, part ->
            if (part === previous[i]) {
                part
            } else {
                try {
                    Repo(listOf(part))
                    part
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    failed += i
                    previous[i]
                }
            }
        }
        return RepoParts(Repo(checked), failed, failure)
    }
}
