// Stand-ins for the parts of the app the compiled sources call but that are not under test.
package app.grapheneos.apps

import app.grapheneos.apps.core.InstallTask
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.ReleaseChannel
import kotlinx.coroutines.Deferred

object PackageStates {
    val map = HashMap<String, PackageState>()
    var defaultReleaseChannel = ReleaseChannel.stable
    var updateLoopRunnableRunCount = 0
    var packageCachePruningJob: Deferred<Unit>? = null
    private var id = 0L

    fun getPackageState(name: String) = map.getOrPut(name) { PackageState(name, ++id) }
    fun maybeGetPackageState(name: String) = map[name]
    fun addInstallTask(task: InstallTask) {
        val state = map[task.rPackage.packageName]!!
        check(state.installTask == null)
        state.installTask = task
    }
    fun completeInstallTask(task: InstallTask) {
        check(task.packageState.installTask === task)
        task.packageState.installTask = null
    }
    fun dispatchStateChanged(state: PackageState) {}
    fun reset() { map.clear() }
}
