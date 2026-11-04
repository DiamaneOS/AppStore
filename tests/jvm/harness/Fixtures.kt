package harness

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import app.grapheneos.apps.core.Catalog
import app.grapheneos.apps.core.CatalogPart
import app.grapheneos.apps.core.DIAMANEOS_SIGNERS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

object TestKey {
    private val seed: ByteArray by lazy { File(System.getProperty("fixture.key") ?: error("fixture.key is not set")).readBytes() }
    fun signature(message: ByteArray): String = TestKeyMaterial.sign(seed, message)
    // The catalog file: JSON, newline, base64 signature, newline.
    fun frame(json: ByteArray): ByteArray = json + "\n${signature(json)}\n".toByteArray()
}

// Catalog JSON as the signer writes it.
object CatalogJson {
    class ApkEntry(val name: String, val sha256: String, val size: Long, val brSize: Long, val brSha256: String,
                   val deltaSizes: List<Long> = emptyList(), val deltaSrc: List<String> = emptyList(),
                   val deltaSha256: List<String> = emptyList())

    fun variant(label: String, channel: String?, apks: List<ApkEntry>, deltaVersions: List<Long> = emptyList()) =
        JSONObject().apply {
            put("label", label)
            channel?.let { put("channel", it) }
            put("apks", JSONArray(apks.map { it.name }))
            put("apkHashes", JSONArray(apks.map { it.sha256 }))
            put("apkSizes", JSONArray(apks.map { it.size }))
            put("apkBrSizes", JSONArray(apks.map { it.brSize }))
            put("apkBrHashes", JSONArray(apks.map { it.brSha256 }))
            if (deltaVersions.isNotEmpty()) {
                put("availableDeltaVersions", JSONArray(deltaVersions))
                put("apkDeltaSizes", JSONArray(apks.flatMap { it.deltaSizes }))
                put("apkDeltaSrcHashes", JSONArray(apks.flatMap { it.deltaSrc }))
                put("apkDeltaHashes", JSONArray(apks.flatMap { it.deltaSha256 }))
            }
        }

    // Our catalog lists only packages signed by one of our signers.
    fun ourPackage(variants: Map<Long, JSONObject>) = JSONObject().apply {
        put("source", "DiamaneOS")
        put("signatures", JSONArray(listOf(DIAMANEOS_SIGNERS.first())))
        put("variants", JSONObject().apply { variants.forEach { (v, j) -> put(v.toString(), j) } })
    }

    fun otherPackage(variants: Map<Long, JSONObject>) = JSONObject().apply {
        put("signatures", JSONArray(listOf(hex(sha256("fixture signer".toByteArray())))))
        put("variants", JSONObject().apply { variants.forEach { (v, j) -> put(v.toString(), j) } })
    }

    fun catalog(time: Long, packages: Map<String, JSONObject>) = JSONObject().apply {
        put("time", time)
        put("packages", JSONObject().apply { packages.forEach { (k, v) -> put(k, v) } })
    }

    fun simpleApk(name: String = "base.apk", seed: String = name) = ApkEntry(name,
        hex(sha256(seed.toByteArray())), 1000, 500, hex(sha256("$seed.br".toByteArray())))
}

fun dummyPart(catalog: Catalog) = CatalogPart(catalog, JSONObject().put("time", catalog.minTimestamp)
    .put("packages", JSONObject()), "", isDummy = true)

fun installedInfo(versionCode: Long, sourceDir: String) = PackageInfo().apply {
    setLongVersionCode(versionCode)
    applicationInfo = ApplicationInfo().apply { this.sourceDir = sourceDir }
}
