package harness

import android.content.pm.PackageInfo
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.core.Catalog
import app.grapheneos.apps.core.CatalogPart
import app.grapheneos.apps.core.DeltaHashException
import app.grapheneos.apps.core.DeltaPatchException
import app.grapheneos.apps.core.InstallTask
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.ReleaseChannel
import app.grapheneos.apps.core.Repo
import app.grapheneos.apps.core.withDeltaFallback
import app.grapheneos.apps.util.AtomicFile2
import de.diamaneos.downloads.DownloadTransport
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import kotlin.random.Random

// Property and regression checks over the app's own code. Each prints one line and fails with a
// message naming the input that broke it: Properties <name>...
fun main(args: Array<String>) {
    Harness.setUp()
    // accepts <file>...: whether the app takes each file as our catalog
    if (args.firstOrNull() == "accepts") {
        for (f in args.drop(1)) println("${File(f).name}: ${if (freshFetchAccepts(File(f).readBytes())) "taken" else "refused"}")
        return
    }
    val all = mapOf(
        "signed_catalog_byte_changes" to ::signedCatalogByteChanges,
        "catalog_signature_and_rollback_model" to ::catalogRollbackModel,
        "catalog_paths_stay_in_the_app" to ::catalogPaths,
        "delta_failures_fall_back" to ::deltaFailuresFallBack,
        "full_apk_failures_never_install" to ::fullApkFailures,
        "wrong_bytes_never_install" to ::wrongBytesNeverInstall,
        "channels_and_versions" to ::channelsAndVersions,
        "cache_file_corruption" to ::cacheFileCorruption,
        "transport_bounds" to ::transportBounds,
        "delta_fallback_unit" to ::deltaFallbackUnit,
    )
    for (name in args.ifEmpty { all.keys.toTypedArray() }) {
        val start = System.nanoTime()
        val n = all[name]?.invoke() ?: error("unknown property $name")
        println("ok $name $n checks ${(System.nanoTime() - start) / 1_000_000} ms")
    }
}

private fun check(ok: Boolean, what: () -> String) {
    if (!ok) throw AssertionError(what())
}

const val OUR_PKG = "de.diamaneos.fuzz.app"
val minTime = Catalog.DiamaneOS.minTimestamp

fun ourCatalog(time: Long, pkg: String = OUR_PKG, apk: String = "base.apk", channel: String? = null,
               version: Long = 7): ByteArray {
    val variant = CatalogJson.variant("Fixture", channel, listOf(CatalogJson.simpleApk(apk)))
    return CatalogJson.catalog(time, mapOf(pkg to CatalogJson.ourPackage(mapOf(version to variant)))).toString().toByteArray()
}

// Serves body as our catalog to an app without a cache. Returns whether the app took it.
fun freshFetchAccepts(body: ByteArray): Boolean {
    Harness.reset()
    Harness.serveCatalog(body)
    val (repo, _) = Harness.fetch(Harness.cached())
    return !repo.parts.first { it.catalog == Catalog.DiamaneOS }.isDummy
}

// Every changed, added or removed byte of a signed catalog makes the app refuse it.
fun signedCatalogByteChanges(): Int {
    val good = TestKey.frame(ourCatalog(minTime + 100))
    check(freshFetchAccepts(good)) { "the unchanged catalog is refused" }
    var n = 1
    for (i in good.indices) {
        for (mask in intArrayOf(0x01, 0x02, 0x20, 0x80)) {
            val bad = good.copyOf()
            bad[i] = (bad[i].toInt() xor mask).toByte()
            check(!freshFetchAccepts(bad)) { "byte $i of ${good.size} xor $mask is accepted" }
            n++
        }
    }
    for (i in listOf(0, good.size - 102, good.size - 101, good.size - 1, good.size)) {
        check(!freshFetchAccepts(good.copyOfRange(0, i) + byteArrayOf(10) + good.copyOfRange(i, good.size))) { "insertion at $i is accepted" }
        if (i < good.size) {
            check(!freshFetchAccepts(good.copyOfRange(0, i) + good.copyOfRange(i + 1, good.size))) { "removal at $i is accepted" }
        }
        n += 2
    }
    return n
}

// Model: the app's catalog time never goes back, whatever order valid, old, replayed, badly signed
// and unchanged answers come in, and across restarts that read the cache.
fun catalogRollbackModel(): Int {
    val rnd = Random(20261104)
    val otherKey = ByteArray(40).also { rnd.nextBytes(it) }
    var checks = 0
    repeat(200) { run ->
        Harness.reset()
        var repo = Harness.cached()
        var floor = minTime
        val served = mutableListOf<ByteArray>()
        // Whether the server now answers with a catalog the test key signed
        var valid = false
        repeat(30) { step ->
            val time = floor + rnd.nextInt(-3, 4)
            val json = ourCatalog(time, version = rnd.nextLong(1, 5))
            when (rnd.nextInt(7)) {
                0, 1 -> { Harness.serveCatalog(TestKey.frame(json).also { served += it }); valid = true }
                2 -> if (served.isNotEmpty()) { Harness.serveCatalog(served.random(rnd)); valid = true }
                3 -> {
                    val f = TestKey.frame(json)
                    val at = f.size - 3 - rnd.nextInt(80)
                    f[at] = if (f[at] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
                    Harness.serveCatalog(f)
                    valid = false
                }
                4 -> { Harness.serveCatalog(json + "\n${TestKeyMaterial.sign(otherKey, json)}\n".toByteArray()); valid = false }
                5 -> { FakeServer.replies["/metadata.1.0.sjson"] = FakeServer.Reply(if (rnd.nextBoolean()) 304 else 500); valid = false }
                6 -> repo = Harness.cached()
            }
            val before = Harness.ourTime(repo)
            val (next, _) = Harness.fetch(repo)
            val after = Harness.ourTime(next)
            check(after >= before) { "run $run step $step: time went back from $before to $after" }
            check(after >= minTime) { "run $run step $step: time $after is below the floor" }
            if (after != before || next.parts[0] !== repo.parts[0]) {
                check(valid) { "run $run step $step: a catalog without a valid signature is taken" }
            }
            repo = next
            floor = maxOf(floor, after)
            check(Harness.ourTime(Harness.cached()) == after || Harness.cached().parts[0].isDummy && after == minTime) {
                "run $run step $step: the cache does not hold the time the app uses"
            }
            checks++
        }
    }
    return checks
}

private val unsafeNames = listOf("..", ".", "", "a/b", "../../../files/repo", "x/../../..", "base.apk\u0000")

// Names in the catalog are used as file names. None may lead out of the package cache.
fun catalogPaths(): Int {
    var n = 0
    val cache = InstallTask.packageCacheDir.canonicalFile
    for (bad in unsafeNames) {
        for (asPackage in listOf(true, false)) {
            val pkg = if (asPackage) "$OUR_PKG/$bad" else OUR_PKG
            val apk = if (asPackage) "base.apk" else bad
            val accepted = freshFetchAccepts(TestKey.frame(ourCatalog(minTime + 1, pkg = pkg, apk = apk)))
            if (accepted) {
                val repo = Harness.cached()
                for (c in repo.packages.values) for (v in c.variants) for (a in v.apks) {
                    val f = File(File(cache, "${c.packageName}/${v.versionCode}"), "${a.name}.br").canonicalFile
                    check(f.path.startsWith(cache.path + File.separator) && f.parentFile.parentFile.parentFile == cache) {
                        "package '${c.packageName}' apk '${a.name}' writes to $f"
                    }
                }
            }
            n++
        }
    }
    // Ordinary names still work
    check(freshFetchAccepts(TestKey.frame(ourCatalog(minTime + 1, apk = "split_config.arm64_v8a.apk")))) { "a split name is refused" }
    return n + 1
}

private fun reply(body: ByteArray) = FakeServer.Reply(200, body)

private fun scenario(delta: FakeServer.Reply?, listedSize: Long = InstallRun.goodIna.size.toLong(),
                     listedSha: ByteArray = sha256(InstallRun.goodIna), br: FakeServer.Reply = reply(InstallRun.goodBr),
                     installed: ByteArray = InstallRun.old, user: Boolean = false, cachedDelta: ByteArray? = null,
                     brSize: Long = InstallRun.goodBr.size.toLong(), brSha: ByteArray = sha256(InstallRun.goodBr)) =
    InstallRun.Scenario(delta, listedSize, listedSha, br, brSize, brSha, installed, user, cachedDelta)

// What must hold after every install attempt.
private fun checkOutcome(name: String, s: InstallRun.Scenario, o: InstallRun.Outcome) {
    check(!o.committed || o.apk.contentEquals(InstallRun.new)) { "$name: wrong bytes were installed" }
    check(o.files.none { it.endsWith(".tmp") }) { "$name: temporary files left: ${o.files}" }
    check(o.files.all { it.startsWith("cache/packages/${InstallRun.PKG}/2/") || it.startsWith("files/") || it == "installed/base.apk" }) {
        "$name: files outside the package cache: ${o.files}"
    }
    check(o.error !is Error) { "$name: ${o.error}" }
}

// Each way a delta can be unusable ends with the full APK installed in the same attempt, and leaves
// no delta file behind. A dropped delta fails the attempt and stays for the retry, which resumes it.
fun deltaFailuresFallBack(): Int {
    val good = InstallRun.goodIna
    val cases = linkedMapOf(
        "missing" to scenario(FakeServer.Reply(404)),
        "server error" to scenario(FakeServer.Reply(500)),
        "corrupt byte" to scenario(reply(good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })),
        "garbage" to scenario(reply(ByteArray(good.size) { it.toByte() })),
        "longer than listed" to scenario(reply(good + ByteArray(10))),
        "listed size too small" to scenario(reply(good), listedSize = good.size - 5L),
        "signed delta patches to wrong bytes" to scenario(reply(InstallRun.wrongIna), InstallRun.wrongIna.size.toLong(), sha256(InstallRun.wrongIna)),
        "signed delta too large output" to scenario(reply(InstallRun.bombIna), InstallRun.bombIna.size.toLong(), sha256(InstallRun.bombIna)),
        "signed delta not a patch" to scenario(reply(InstallRun.goodBr), InstallRun.goodBr.size.toLong(), sha256(InstallRun.goodBr)),
        "stale cached delta" to scenario(reply(good), cachedDelta = InstallRun.wrongIna.copyOf(good.size)),
        "installed APK changed" to scenario(reply(good), installed = InstallRun.old.copyOf().also { it[7] = 1 }),
        "good delta" to scenario(reply(good)),
    )
    for ((name, s) in cases) {
        val o = InstallRun.run(s)
        checkOutcome(name, s, o)
        check(o.committed) { "delta $name: the update failed with ${o.error}" }
        // Only the listed number of bytes is read, so extra bytes leave a working delta.
        if (name != "good delta" && name != "longer than listed") {
            check(o.files.none { it.endsWith(".ina") }) { "delta $name: the delta file was kept: ${o.files}" }
        }
    }
    val dropped = linkedMapOf(
        "truncated" to scenario(FakeServer.Reply(200, good, cut = good.size / 2L)),
        "connection reset" to scenario(FakeServer.Reply(200, good, reset = good.size / 3L)),
        "listed size too large" to scenario(reply(good), listedSize = good.size + 5L),
    )
    for ((name, s) in dropped) {
        val o = InstallRun.run(s)
        checkOutcome(name, s, o)
        check(!o.committed && o.files.any { it.endsWith(".ina") }) { "delta $name: ${o.error}, ${o.files}" }
    }
    val resumed = scenario(reply(good), cachedDelta = good.copyOf(good.size / 2))
    check(InstallRun.run(resumed).committed) { "a partial delta was not resumed" }
    // A user starting the update on a changed APK is told so, as before.
    val o = InstallRun.run(scenario(reply(good), installed = InstallRun.old.copyOf().also { it[7] = 1 }, user = true))
    check(o.error?.javaClass?.simpleName == "InvalidDeltaBaseApkException" && o.ignoredDeltas) { "changed APK, user: ${o.error}" }
    return cases.size + dropped.size + 2
}

// A full APK that is missing, wrong or too large is never installed, with or without a delta.
fun fullApkFailures(): Int {
    val br = InstallRun.goodBr
    val cases = linkedMapOf(
        "missing" to (FakeServer.Reply(404) to sha256(br)),
        "truncated" to (FakeServer.Reply(200, br, cut = br.size / 2L) to sha256(br)),
        "corrupt" to (reply(br.copyOf().also { it[100] = (it[100] + 1).toByte() }) to sha256(br)),
        "signed wrong APK" to (reply(InstallRun.wrongBr) to sha256(InstallRun.wrongBr)),
        "signed too large output" to (reply(InstallRun.bombBr) to sha256(InstallRun.bombBr)),
        "signed not brotli" to (reply(InstallRun.goodIna) to sha256(InstallRun.goodIna)),
    )
    var n = 0
    for ((name, c) in cases) {
        for (delta in listOf(null, FakeServer.Reply(404))) {
            val body = c.first.body
            val s = scenario(delta, br = c.first, brSize = if (body.isEmpty()) br.size.toLong() else body.size.toLong(), brSha = c.second)
            val o = InstallRun.run(s)
            checkOutcome("full $name", s, o)
            check(!o.committed) { "full $name: installed" }
            n++
        }
    }
    return n
}

// Random changes to signed deltas and APKs: whatever the catalog lists, only the right APK installs.
fun wrongBytesNeverInstall(): Int {
    val rnd = Random(7)
    repeat(150) { i ->
        fun mutate(b: ByteArray): ByteArray = b.copyOf().also { x -> repeat(rnd.nextInt(1, 4)) { x[rnd.nextInt(x.size)] = rnd.nextInt().toByte() } }
        val delta = if (rnd.nextBoolean()) mutate(InstallRun.goodIna) else InstallRun.goodIna
        val br = if (rnd.nextBoolean()) mutate(InstallRun.goodBr) else InstallRun.goodBr
        val s = scenario(reply(delta), delta.size.toLong(), sha256(delta), reply(br), brSize = br.size.toLong(), brSha = sha256(br))
        val o = InstallRun.run(s)
        checkOutcome("mutation $i", s, o)
    }
    return 150
}

// Model of channel and version choice over random variant sets.
fun channelsAndVersions(): Int {
    val rnd = Random(3)
    var n = 0
    var noAcceptable = 0
    repeat(2000) {
        Harness.reset()
        val variants = (0 until rnd.nextInt(1, 6)).associate {
            rnd.nextLong(1, 40) to ReleaseChannel.entries.random(rnd)
        }
        val variantJson = variants.mapValues { (v, ch) ->
            CatalogJson.variant("Fixture", ch.name, listOf(CatalogJson.simpleApk(seed = "$v")))
        }
        val json = CatalogJson.catalog(minTime, mapOf(InstallRun.PKG to CatalogJson.otherPackage(variantJson)))
        val repo = Repo(listOf(dummyPart(Catalog.DiamaneOS), CatalogPart(Catalog.GrapheneOS, json, "")))
        val container = repo.packages[InstallRun.PKG]!!
        // One variant per channel, the highest version of that channel
        for (ch in ReleaseChannel.entries) {
            val best = variants.filterValues { it == ch }.keys.maxOrNull()
            val got = container.variants.filter { it.releaseChannel == ch }
            check(got.size == (if (best == null) 0 else 1) && got.all { it.versionCode == best }) { "channel $ch of $variants: $got" }
        }
        val installed = rnd.nextLong(0, 45)
        val info = installedInfo(installed, "/nonexistent").takeIf { installed > 0 }
        for (chosen in ReleaseChannel.entries) {
            val p = container.getPackage(chosen)
            if (variants.values.any { it >= chosen }) {
                check(p.releaseChannel >= chosen) { "chose $chosen of $variants, got ${p.releaseChannel}" }
            } else {
                // Without a variant on a channel the user accepts, the most stable one is offered.
                noAcceptable++
                check(p.releaseChannel == variants.values.max()) { "no acceptable variant of $variants: ${p.releaseChannel}" }
            }
            PackageStates.defaultReleaseChannel = chosen
            val state = PackageState(InstallRun.PKG, 1)
            state.osPackageInfo = info
            state.setRPackage(p)
            val offered = state.status() == PackageState.Status.OUT_OF_DATE || state.isEligibleForBulkUpdate()
            check(!offered || (info != null && p.versionCode > installed)) { "version ${p.versionCode} offered over $installed" }
            check(info == null || p.versionCode <= installed || state.status() == PackageState.Status.OUT_OF_DATE) { "update not offered" }
            n++
        }
    }
    PackageStates.defaultReleaseChannel = ReleaseChannel.stable
    println("note: $noAcceptable of $n choices had no variant on an accepted channel")
    return n
}

// A damaged cache file starts the catalog afresh and never stops the app from starting.
fun cacheFileCorruption(): Int {
    val rnd = Random(11)
    Harness.reset()
    Harness.serveCatalog(TestKey.frame(ourCatalog(minTime + 5)))
    Harness.fetch(Harness.cached())
    val cacheFile = File(app.grapheneos.apps.core.filesDir, "repo")
    val good = cacheFile.readBytes()
    var n = 0
    val variants = mutableListOf<ByteArray>()
    for (at in listOf(4, 8, 12, 16, 20, 24, 28, 32)) {
        for (v in listOf(0x7fffffff, 0x40000000, -1, 0x10000)) {
            variants += good.copyOf().also { b -> for (k in 0 until 4) if (at + k < b.size) b[at + k] = (v ushr (24 - 8 * k)).toByte() }
        }
    }
    repeat(200) { variants += good.copyOf().also { b -> b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() } }
    repeat(50) { variants += good.copyOf(rnd.nextInt(good.size)) }
    for (b in variants) {
        cacheFile.writeBytes(b)
        try {
            val repo = Harness.cached()
            check(Harness.ourTime(repo) >= minTime) { "cache time below the floor" }
        } catch (e: Throwable) {
            throw AssertionError("a damaged cache file stops the start: $e", e)
        }
        n++
    }
    return n
}

// Downloads never take more than their bound, and a resume takes only the exact rest of the file.
fun transportBounds(): Int {
    var n = 0
    for (limit in listOf(1, 100, 8192, 8193, 1 shl 20)) {
        for (size in listOf(0, 1, limit - 1, limit, limit + 1, limit * 2)) {
            if (size < 0) continue
            val data = ByteArray(size)
            val r = try { DownloadTransport.readBounded(ByteArrayInputStream(data), limit).size } catch (e: IOException) { -1 }
            check(if (size <= limit) r == size else r == -1) { "readBounded($size, $limit) = $r" }
            n++
        }
    }
    return n
}

// The fallback helper on its own: an unusable delta tries the full APK once, anything else comes out.
fun deltaFallbackUnit(): Int {
    fun run(useDelta: Boolean, vararg failures: Exception?): String {
        val attempts = mutableListOf<Boolean>()
        var fallbacks = 0
        return try {
            withDeltaFallback(useDelta, { delta ->
                attempts += delta
                failures.getOrNull(attempts.size - 1)?.let { throw it }
            }, { fallbacks++ })
            "$attempts $fallbacks"
        } catch (e: Exception) {
            "$attempts $fallbacks ${e.javaClass.simpleName}"
        }
    }
    val failures = listOf(ProtocolException("404"), DeltaHashException("hash"),
        DeltaPatchException(GeneralSecurityException("patched")), DeltaPatchException(IOException("ina_patch error 5")))
    for (f in failures) {
        check(run(true, f) == "[true, false] 1") { "${f.javaClass.simpleName}: ${run(true, f)}" }
        check(run(true, f, f) == "[true, false] 1 ${f.javaClass.simpleName}") { "full fails after ${f.javaClass.simpleName}" }
    }
    val others = listOf(SocketTimeoutException("slow"), EOFException("cut"), GeneralSecurityException("full"),
        IllegalStateException("native"), CancellationException())
    for (f in others) {
        val name = f.javaClass.simpleName
        check(run(true, f) == "[true] 0 $name") { "$name: ${run(true, f)}" }
    }
    check(run(false, ProtocolException()) == "[false] 0 ProtocolException") { "no delta" }
    return failures.size * 2 + others.size + 1
}
