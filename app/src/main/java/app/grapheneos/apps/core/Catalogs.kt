package app.grapheneos.apps.core

import app.grapheneos.apps.BuildConfig
import org.json.JSONObject

// DiamaneOS: two signed catalogs. Each package can come from only one of them, so neither key can
// offer the other catalog's apps.

// Our own builds. Our catalog lists only these and GrapheneOS's catalog never does.
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
            DiamaneOS -> originalPackage == null && packageName in DIAMANEOS_PACKAGES && source == "DiamaneOS"
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
