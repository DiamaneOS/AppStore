package harness

import app.grapheneos.apps.core.filesDir
import java.io.File
import kotlin.random.Random

// Writes the seed corpora of the Jazzer targets: Seeds <dir>
fun main(args: Array<String>) {
    Harness.setUp()
    val dir = File(args[0])
    fun put(target: String, name: String, data: ByteArray) = File(dir, target).apply { mkdirs() }.resolve(name).writeBytes(data)
    val r = InstallRun
    val catalogs = mapOf(
        "simple" to ourCatalog(minTime + 1),
        "beta" to ourCatalog(minTime + 2, channel = "beta"),
        "splits" to CatalogJson.catalog(minTime + 3, mapOf(OUR_PKG to CatalogJson.ourPackage(mapOf(
            5L to CatalogJson.variant("Fixture", "stable", listOf(CatalogJson.simpleApk(), CatalogJson.simpleApk("split_config.arm64_v8a.apk"),
                CatalogJson.simpleApk("split_config.en.apk"), CatalogJson.simpleApk("split_config.xxhdpi.apk"))),
            6L to CatalogJson.variant("Fixture", "alpha", listOf(CatalogJson.simpleApk())))))).toString().toByteArray(),
        "delta" to CatalogJson.catalog(minTime + 4, mapOf("de.diamaneos.fuzz.delta" to CatalogJson.ourPackage(mapOf(
            2L to CatalogJson.variant("Fixture", null, listOf(CatalogJson.ApkEntry("base.apk", hex(sha256(r.new)), r.new.size.toLong(),
                r.goodBr.size.toLong(), hex(sha256(r.goodBr)), listOf(r.goodIna.size.toLong(), 0), listOf(hex(sha256(r.old)), ""),
                listOf(hex(sha256(r.goodIna)), ""))), listOf(1L, 0L)))))).toString().toByteArray(),
    )
    for ((name, json) in catalogs) {
        val framed = TestKey.frame(json)
        put("catalog_bytes", name, framed)
        put("catalog_bytes", "$name-short", framed.copyOf(framed.size - 50))
        put("signed_catalog", name, json)
        Harness.reset()
        Harness.serveCatalog(framed)
        Harness.fetch(Harness.cached())
        put("cache", name, File(filesDir, "repo").readBytes())
    }
    val rnd = Random(1)
    for (t in listOf("catalog_sequence", "install_flow", "transport")) {
        repeat(8) { put(t, "random$it", ByteArray(64 + 64 * it).also { b -> rnd.nextBytes(b) }) }
    }
}
