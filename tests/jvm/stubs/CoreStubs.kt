package app.grapheneos.apps.core

import android.app.LocaleManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Handler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.io.File

val appContext = Context()
val appResources = Resources()
val mainThread: Thread = Thread.currentThread()
val mainHandler = Handler()
val selfPkgName = "app.grapheneos.apps"
val pkgManager: PackageManager = PackageManager.instance
val pkgInstaller: PackageInstaller = pkgManager.packageInstaller
var isPrivilegedInstaller = false
val canUpdateDisabledPackages = true
val localeManager: LocaleManager? = LocaleManager()

// The app's own folders, under a directory the harness chooses.
val appRoot: File = File(System.getProperty("fixture.root") ?: error("fixture.root is not set"))
val filesDir: File = File(appRoot, "files").also { it.mkdirs() }
val cacheDir: File = File(appRoot, "cache").also { it.mkdirs() }

val httpDownloadSemaphore = Semaphore(3)
val inaPatcherSemaphore = Semaphore(5)

object InstallerSessions {
    suspend fun createSession(params: PackageInstaller.SessionParams, pkgState: PackageState): Int =
        pkgInstaller.createSession(params)

    fun createMultiPackageSession(): Int = error("multi-package sessions are not under test")

    fun abandonSession(sessionId: Int): Boolean {
        pkgInstaller.abandonSession(sessionId)
        return true
    }
}

class PkgInstallerStatusReceiver {
    companion object {
        fun getCompletionChannelForSession(sessionId: Int) = Channel<PackageInstallerError?>(1)
        fun getIntentSender(rPackages: List<RPackage>, isUserInitiated: Boolean) = IntentSender()
    }
}

class PackageInstallerError
class InstallerBusyError(val pkgName: String, val requestedPkgName: String)
