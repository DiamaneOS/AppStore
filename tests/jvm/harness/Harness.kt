package harness

import android.content.pm.PackageManager
import android.os.Handler
import android.system.Os
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.core.Catalog
import app.grapheneos.apps.core.CatalogPart
import app.grapheneos.apps.core.InstallParams
import app.grapheneos.apps.core.InstallTask
import app.grapheneos.apps.core.PartialRepoUpdate
import app.grapheneos.apps.core.Repo
import app.grapheneos.apps.core.ReleaseChannel
import app.grapheneos.apps.core.appRoot
import app.grapheneos.apps.core.cacheDir
import app.grapheneos.apps.core.fetchRepo
import app.grapheneos.apps.core.filesDir
import app.grapheneos.apps.core.getCachedRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

object Harness {
    private var installed = false

    fun setUp() {
        if (!installed) {
            FakeServer.install()
            installed = true
        }
        reset()
    }

    fun reset() {
        FakeServer.reset()
        Handler.queue.clear()
        PackageStates.reset()
        PackageManager.instance.installed.clear()
        PackageManager.instance.installer.sessions.clear()
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        File(appRoot, "installed").deleteRecursively()
        check(Os.openCount() == 0) { "file descriptors leaked: ${Os.openCount()}" }
    }

    // Every file the app left in its folders, relative to them.
    fun appFiles(): List<String> = appRoot.walk().filter { it.isFile }.map { it.relativeTo(appRoot).path }.sorted().toList()

    // One background catalog check. Returns the repo the app keeps and the failure, if any.
    fun fetch(current: Repo): Pair<Repo, Throwable?> = try {
        fetchRepo(current) to null
    } catch (e: PartialRepoUpdate) {
        e.repo to e
    } catch (e: Exception) {
        current to e
    }

    fun cached(): Repo = getCachedRepo()

    // Serves our catalog and none of the other one.
    fun serveCatalog(body: ByteArray?, etag: String? = null) {
        if (body == null) FakeServer.replies.remove("/metadata.1.0.sjson")
        else FakeServer.replies["/metadata.1.0.sjson"] = FakeServer.Reply(200, body, etag)
    }

    fun ourTime(repo: Repo): Long = repo.parts.first { it.catalog == Catalog.DiamaneOS }.timestamp
}

// The install of one APK of one package, updated from version 1 to version 2 of the other catalog,
// with a delta from version 1.
object InstallRun {
    const val PKG = "org.example.fuzzapp"
    private val dir = File(System.getProperty("fixture.files") ?: error("fixture.files is not set"))
    private fun f(name: String) = File(dir, name).readBytes()
    val old = f("old.apk")
    val new = f("new.apk")
    val goodIna = f("good.ina")
    val wrongIna = f("wrong.ina")
    val bombIna = f("bomb.ina")
    val goodBr = f("good.br")
    val wrongBr = f("wrong.br")
    val bombBr = f("bomb.br")
    private val deltaPath = "/grapheneos/packages/$PKG/2/base.apk.1.ina"
    private val brPath = "/grapheneos/packages/$PKG/2/base.apk.br"

    class Scenario(
        val delta: FakeServer.Reply?,
        val deltaListedSize: Long,
        val deltaListedSha256: ByteArray,
        val br: FakeServer.Reply,
        val brListedSize: Long = goodBr.size.toLong(),
        val brListedSha256: ByteArray = sha256(goodBr),
        val installed: ByteArray = old,
        val userInitiated: Boolean = false,
        // A cached file of the delta or the .br from an earlier attempt.
        val cachedDelta: ByteArray? = null,
        val cachedBr: ByteArray? = null,
    ) {
        val fullIsGood get() = br.code == 200 && br.body.contentEquals(goodBr) && br.cut < 0 && br.reset < 0 &&
            brListedSize == goodBr.size.toLong() && brListedSha256.contentEquals(sha256(goodBr)) &&
            (cachedBr == null || goodBr.copyOf(cachedBr.size).contentEquals(cachedBr)) &&
            // A resume needs a server that answers the range: a 200 to it fails the update
            (cachedBr == null || cachedBr.isEmpty() || cachedBr.size >= goodBr.size || br.honorRange)
    }

    class Outcome(val committed: Boolean, val apk: ByteArray?, val error: Throwable?,
                  val files: List<String>, val requests: List<String>, val ignoredDeltas: Boolean)

    fun catalogJson(s: Scenario): JSONObject {
        val apk = CatalogJson.ApkEntry("base.apk", hex(sha256(new)), new.size.toLong(), s.brListedSize,
            hex(s.brListedSha256), listOf(s.deltaListedSize), listOf(hex(sha256(old))), listOf(hex(s.deltaListedSha256)))
        val variant = CatalogJson.variant("Fixture", null, listOf(apk), listOf(1L))
        return CatalogJson.catalog(Catalog.GrapheneOS.minTimestamp, mapOf(PKG to CatalogJson.otherPackage(mapOf(2L to variant))))
    }

    fun run(s: Scenario): Outcome {
        Harness.reset()
        val repo = Repo(listOf(dummyPart(Catalog.DiamaneOS), CatalogPart(Catalog.GrapheneOS, catalogJson(s), "")))
        val rPackage = repo.packages[PKG]!!.getPackage(ReleaseChannel.stable)
        val base = File(appRoot, "installed/base.apk").apply { parentFile.mkdirs(); writeBytes(s.installed) }
        val info = installedInfo(1, base.path)
        PackageManager.instance.installed[PKG] = info
        val state = PackageStates.getPackageState(PKG)
        state.osPackageInfo = info
        state.setRPackage(rPackage)
        s.delta?.let { FakeServer.replies[deltaPath] = it }
        FakeServer.replies[brPath] = s.br
        val apksDir = File(cacheDir, "packages/$PKG/2").apply { mkdirs() }
        s.cachedDelta?.let { File(apksDir, "base.apk.1.ina").writeBytes(it) }
        s.cachedBr?.let { File(apksDir, "base.apk.br").writeBytes(it) }

        val task = InstallTask(rPackage, state, rPackage.apks, InstallParams(null, true, s.userInitiated), null)
        val error = try {
            runBlocking(Dispatchers.IO) { task.run() }
            null
        } catch (e: Exception) {
            e
        }
        Handler.drain()
        PackageStates.completeInstallTask(task)
        val session = PackageManager.instance.installer.sessions.values.singleOrNull()
        val committed = session?.committed == true
        return Outcome(committed, session?.files?.get("base.apk")?.toByteArray()?.takeIf { committed }, error,
            Harness.appFiles(), FakeServer.requests.toList(), state.ignoreDeltasForVersion != null)
    }
}
