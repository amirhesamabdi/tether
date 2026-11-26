package app.tether.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException

/** User-initiated update flow. State lives on the application so it survives recreating the screen. */
internal class AppUpdates(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object Current : State
        data class Available(val release: GatewayRelease) : State
        data class Downloading(val release: GatewayRelease) : State
        data class Ready(val release: GatewayRelease, val file: File) : State
        data class Failed(val reason: Failure) : State
    }

    enum class Failure { CHECK, MISSING, DOWNLOAD, INVALID }

    private val main = Handler(Looper.getMainLooper())
    var state: State = State.Idle; private set
    var onChange: (() -> Unit)? = null

    fun check() {
        if (state is State.Checking || state is State.Downloading) return
        run(State.Checking, Failure.CHECK) {
            val release = GitHubUpdates.check(installed().code())
            if (release == null) State.Current else State.Available(release)
        }
    }

    fun download() {
        val release = (state as? State.Available)?.release ?: return
        run(State.Downloading(release), Failure.DOWNLOAD) {
            val directory = UpdateFiles.directory(context).apply { mkdirs() }
            // Android's archive parser expects an .apk suffix. The provider never serves staging names.
            directory.listFiles()?.filter { it.name.startsWith("download-") }?.forEach { it.delete() }
            val staging = File.createTempFile("download-", ".apk", directory)
            try {
                GitHubUpdates.download(release, staging)
                val apk = context.packageManager.getPackageArchiveInfo(staging.path, 0) ?: throw InvalidUpdate()
                if (apk.packageName != context.packageName || apk.versionName != release.version || apk.code() <= installed().code()) {
                    throw InvalidUpdate()
                }
                val verified = File(directory, release.fileName)
                if (!staging.renameTo(verified)) throw IOException("could not store update")
                directory.listFiles()?.filter { it != verified && GatewayRelease.APK_NAME.matches(it.name) }?.forEach { it.delete() }
                State.Ready(release, verified)
            } finally {
                staging.delete()
            }
        }
    }

    private fun run(initial: State, defaultFailure: Failure, work: () -> State) {
        state = initial
        onChange?.invoke()
        Thread({
            val result = try { work() }
            catch (_: MissingUpdate) { State.Failed(Failure.MISSING) }
            catch (_: InvalidUpdate) { State.Failed(Failure.INVALID) }
            catch (_: Exception) { State.Failed(defaultFailure) }
            main.post { state = result; onChange?.invoke() }
        }, "gateway-update").start()
    }

    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}
