package harness

import app.grapheneos.apps.core.Catalog
import app.grapheneos.apps.core.InstallTask
import app.grapheneos.apps.core.Repo
import app.grapheneos.apps.core.filesDir
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import de.diamaneos.downloads.DownloadPolicy
import de.diamaneos.downloads.DownloadTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.net.ssl.HttpsURLConnection

// Jazzer targets. Each checks the same properties as Properties.kt on fuzzed inputs.

private fun fail(message: String): Nothing = throw AssertionError(message)

private fun setUpOnce() = Harness.setUp()

// What must hold for any repo the app took. The floor applies to fetched catalogs, a cache holds
// what was verified when it was written.
fun checkRepo(repo: Repo, fetched: Boolean = true) {
    val cache = InstallTask.packageCacheDir.canonicalFile
    for (c in repo.packages.values) {
        for (v in c.variants) {
            for (a in v.apks) {
                val f = File(File(cache, "${c.packageName}/${v.versionCode}"), "${a.name}.br").canonicalFile
                if (f.parentFile.parentFile.parentFile != cache) fail("'${c.packageName}' '${a.name}' leaves the cache")
                if (a.sha256.size != 32 || a.compressedSha256.size != 32) fail("hash size")
                if (a.deltaSizes.size != v.availableDeltaVersions.size) fail("delta sizes")
                for (i in a.deltaSizes.indices) {
                    if (a.deltaSizes[i] != 0L && (a.deltasSha256[i].size != 32 || a.deltasSrcSha256[i].size != 32)) fail("delta hash size")
                }
            }
        }
        for (ch in app.grapheneos.apps.core.ReleaseChannel.entries) {
            if (c.variants.any { it.releaseChannel >= ch } && c.getPackage(ch).releaseChannel < ch) fail("channel $ch")
        }
    }
    if (fetched) for (p in repo.parts) if (p.timestamp < p.catalog.minTimestamp) fail("time below the floor")
}

// Any bytes as our catalog, as a hostile mirror could send them. Only the exact file the test key
// signed is taken.
object CatalogBytesFuzzer {
    @JvmStatic fun fuzzerInitialize() = setUpOnce()

    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        Harness.reset()
        Harness.serveCatalog(data, etag = "\"fixture\"")
        val (repo, _) = Harness.fetch(Harness.cached())
        if (repo.parts.first { it.catalog == Catalog.DiamaneOS }.isDummy) return
        val json = data.copyOfRange(0, data.size - 102)
        if (!data.contentEquals(TestKey.frame(json))) fail("a catalog other than the signed file was taken")
        checkRepo(repo)
    }
}

// Any JSON, signed with the test key, reaches the parser behind the signature.
object SignedCatalogFuzzer {
    @JvmStatic fun fuzzerInitialize() = setUpOnce()

    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        Harness.reset()
        Harness.serveCatalog(TestKey.frame(data))
        val (repo, _) = Harness.fetch(Harness.cached())
        checkRepo(repo)
        // What was taken is what the app reads at the next start
        if (Harness.ourTime(Harness.cached()) != Harness.ourTime(repo)) fail("cache and repo differ")
    }
}

// A sequence of answers: new, old, replayed, badly signed, unchanged, failing, and restarts.
object CatalogSequenceFuzzer {
    @JvmStatic fun fuzzerInitialize() = setUpOnce()

    @JvmStatic fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        Harness.reset()
        var repo = Harness.cached()
        val served = mutableListOf<ByteArray>()
        var valid = false
        repeat(data.consumeInt(1, 12)) {
            val time = minTime + data.consumeInt(-3, 12)
            val json = ourCatalog(time, version = data.consumeLong(1, 4))
            when (data.consumeInt(0, 6)) {
                0 -> { Harness.serveCatalog(TestKey.frame(json).also { served += it }); valid = true }
                1 -> if (served.isNotEmpty()) { Harness.serveCatalog(served[data.consumeInt(0, served.size - 1)]); valid = true }
                2 -> {
                    val f = TestKey.frame(json)
                    val at = data.consumeInt(0, f.size - 1)
                    val b = data.consumeByte()
                    valid = f[at] == b
                    f[at] = b
                    Harness.serveCatalog(f)
                }
                3 -> { Harness.serveCatalog(data.consumeBytes(400)); valid = false }
                4 -> { FakeServer.replies["/metadata.1.0.sjson"] = FakeServer.Reply(data.pickValue(intArrayOf(200, 304, 404, 500))); valid = false }
                5 -> repo = Harness.cached()
                6 -> { Harness.serveCatalog(TestKey.frame(json), etag = data.consumeString(20)); valid = true }
            }
            val before = Harness.ourTime(repo)
            val (next, _) = Harness.fetch(repo)
            val after = Harness.ourTime(next)
            if (after < before) fail("time went back from $before to $after")
            if (next.parts[0] !== repo.parts[0] && !next.parts[0].isDummy && !valid) {
                // A changed byte that left the file as signed is fine, anything else is not
                fail("a catalog without a valid signature was taken")
            }
            checkRepo(next)
            repo = next
        }
    }
}

// The install of an update with any mix of delta and full APK failures.
object InstallFlowFuzzer {
    @JvmStatic fun fuzzerInitialize() = setUpOnce()

    private fun body(data: FuzzedDataProvider, good: ByteArray, others: List<ByteArray>): ByteArray = when (data.consumeInt(0, 4)) {
        0 -> good
        1 -> others[data.consumeInt(0, others.size - 1)]
        2 -> good.copyOf().also { repeat(data.consumeInt(1, 3)) { _ -> it[data.consumeInt(0, it.size - 1)] = data.consumeByte() } }
        3 -> good.copyOf(data.consumeInt(0, good.size + 16))
        else -> data.consumeBytes(2000)
    }

    private fun reply(data: FuzzedDataProvider, body: ByteArray) = FakeServer.Reply(
        data.pickValue(intArrayOf(200, 200, 200, 404, 500, 206)), body,
        cut = if (data.consumeBoolean()) data.consumeLong(0, body.size.toLong()) else -1,
        reset = if (data.consumeBoolean()) data.consumeLong(0, body.size.toLong()) else -1,
        honorRange = data.consumeBoolean())

    @JvmStatic fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val r = InstallRun
        val deltaBody = body(data, r.goodIna, listOf(r.wrongIna, r.bombIna, r.goodBr))
        val brBody = body(data, r.goodBr, listOf(r.wrongBr, r.bombBr, r.goodIna))
        val delta = if (data.consumeBoolean()) reply(data, deltaBody) else null
        val br = if (data.consumeBoolean()) FakeServer.Reply(200, r.goodBr) else reply(data, brBody)
        // The catalog lists the served file, as when it was signed that way, or the good one.
        val deltaListed = if (data.consumeBoolean()) deltaBody else r.goodIna
        val brListed = if (data.consumeBoolean()) brBody else r.goodBr
        val installed = if (data.consumeInt(0, 7) == 0) r.old.copyOf().also { it[data.consumeInt(0, it.size - 1)] = 1 } else r.old
        val user = data.consumeBoolean()
        val cachedDelta = if (data.consumeInt(0, 5) == 0) deltaBody.copyOf(data.consumeInt(0, deltaBody.size)) else null
        val cachedBr = if (data.consumeInt(0, 5) == 0) brBody.copyOf(data.consumeInt(0, brBody.size)) else null
        val s = InstallRun.Scenario(delta, deltaListed.size.toLong(), sha256(deltaListed), br, brListed.size.toLong(),
            sha256(brListed), installed, user, cachedDelta, cachedBr)
        val o = r.run(s)
        if (o.committed && !o.apk.contentEquals(r.new)) fail("wrong bytes installed")
        if (o.files.any { it.endsWith(".tmp") }) fail("temporary files left: ${o.files}")
        if (o.error is Error) throw o.error
        val baseChanged = !installed.contentEquals(r.old)
        // A dropped delta fails the attempt and stays for the retry.
        val dropped = delta != null && (delta.cut >= 0 || delta.reset >= 0 || delta.body.size < deltaListed.size)
        if (s.fullIsGood && !dropped && !(user && baseChanged) && !o.committed) {
            fail("the update failed although the full APK is good: ${o.error}")
        }
    }
}

// Download and resume on the other server with any answers from both.
object TransportFuzzer {
    @JvmStatic fun fuzzerInitialize() = setUpOnce()

    @JvmStatic fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        Harness.reset()
        val path = "grapheneos/packages/org.example.fuzzapp/2/base.apk.br"
        val body = data.consumeBytes(data.consumeInt(1, 3000))
        if (body.isEmpty()) return
        val other = if (data.consumeBoolean()) body else body.copyOf(data.consumeInt(0, body.size + 10))
        val total = if (data.consumeBoolean()) body.size.toLong() else data.consumeLong(0, body.size + 10L)
        val first = FakeServer.Reply(200, body, cut = if (data.consumeBoolean()) data.consumeLong(0, body.size.toLong()) else -1,
            reset = if (data.consumeBoolean()) data.consumeLong(0, body.size.toLong()) else -1)
        val badRange = if (data.consumeBoolean()) data.consumeString(40) else null
        val second = FakeServer.Reply(data.pickValue(intArrayOf(200, 200, 500)), other, honorRange = data.consumeBoolean(),
            contentRange = badRange, reset = if (data.consumeBoolean()) data.consumeLong(0, other.size.toLong()) else -1)
        val connector = DownloadTransport.Connector { url -> url.openConnection() as HttpsURLConnection }
        val configure = DownloadTransport.Configure { }
        val out = ByteArrayOutputStream()
        val offset = try {
            val route = DownloadTransport.route(DownloadPolicy.require(DownloadPolicy.AUTOMATIC), true, "metadata.1.0.sjson", connector, configure)
            // The route's first server gets the first reply, whichever the probe put first
            val firstHost = if (route.server() == DownloadPolicy.PRIMARY) "apps.diamaneos.de" else "apps-na.diamaneos.de"
            val secondHost = if (firstHost == "apps.diamaneos.de") "apps-na.diamaneos.de" else "apps.diamaneos.de"
            FakeServer.replies["$firstHost/$path"] = first
            FakeServer.replies["$secondHost/$path"] = second
            val conn = DownloadTransport.open(route, path, 0, connector, configure)
            if (conn.responseCode != 200) return
            DownloadTransport.copy(route, path, conn, 0, total, { b, n -> out.write(b, 0, n) }, connector, configure)
        } catch (e: IOException) {
            if (out.size() > total) fail("wrote ${out.size()} of $total before failing")
            return
        }
        if (offset != total || out.size().toLong() != total) fail("returned $offset with ${out.size()} of $total bytes")
        // Bytes up to the switch come from the first server, the rest from the second, which must
        // have sent exactly the range of a file of the same size.
        val got = out.toByteArray()
        val from1 = got.indices.takeWhile { it < body.size && got[it] == body[it] }.size
        for (i in from1 until got.size) if (i >= other.size || got[i] != other[i]) fail("byte $i from neither server")
        if (from1 < got.size && (other.size.toLong() != total || badRange != null && badRange != "bytes $from1-${total - 1}/$total")) {
            fail("a resume took a range that does not match")
        }
    }
}

// Any bytes as the cache file of our catalog. The App Store still starts.
object CacheFuzzer {
    @JvmStatic fun fuzzerInitialize() = setUpOnce()

    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        Harness.reset()
        File(filesDir, "repo").writeBytes(data)
        val repo = Harness.cached()
        checkRepo(repo, fetched = false)
        // A fetch after it still keeps to the floor
        Harness.serveCatalog(TestKey.frame(ourCatalog(minTime - 1)))
        val (next, _) = Harness.fetch(repo)
        if (Harness.ourTime(next) < minOf(Harness.ourTime(repo), minTime) || Harness.ourTime(next) == minTime - 1) fail("took a catalog below the floor")
    }
}
