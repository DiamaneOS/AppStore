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
) {
    // Our offline catalog key signs our own apps.
    DiamaneOS(BuildConfig.REPO_PUBLIC_KEY, BuildConfig.REPO_KEY_VERSION, "", "repo"),
    // GrapheneOS's signed catalog and APKs, served unchanged by our servers.
    GrapheneOS(GRAPHENEOS_CATALOG_KEY, "0", "grapheneos/", "repo-grapheneos");

    val path: String
        get() = "${prefix}metadata.1.$keyVersion.sjson"

    // The package name and the name it may update through the original-package system both count.
    fun lists(packageName: String, originalPackage: String?, source: String?): Boolean {
        val names = listOfNotNull(packageName, originalPackage)
        return when (this) {
            DiamaneOS -> originalPackage == null && packageName in DIAMANEOS_PACKAGES && source == "DiamaneOS"
            GrapheneOS -> source != "DiamaneOS" && names.none {
                it in DIAMANEOS_PACKAGES || it in EXCLUDED_PACKAGES || it.startsWith("de.diamaneos.")
            }
        }
    }
}

// One verified catalog, or a placeholder for one not fetched yet.
class CatalogPart(val catalog: Catalog, val json: JSONObject, val eTag: String, val isDummy: Boolean = false) {
    val timestamp = json.getLong("time")
}

// One catalog was updated and the other failed.
class PartialRepoUpdate(val repo: Repo, cause: Throwable) : Exception(cause)
