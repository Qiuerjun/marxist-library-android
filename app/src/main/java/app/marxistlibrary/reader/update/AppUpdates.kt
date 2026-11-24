package app.marxistlibrary.reader.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

internal sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data object NoRelease : AppUpdateStatus
    data class Current(val release: GitHubRelease) : AppUpdateStatus
    data class Failed(val message: String) : AppUpdateStatus
    data class Available(val release: GitHubRelease, val message: String? = null) : AppUpdateStatus
    data class Downloading(val release: GitHubRelease, val progress: StateFlow<UpdateProgress>) : AppUpdateStatus
    data class Ready(val release: GitHubRelease, val apk: File, val message: String? = null) : AppUpdateStatus
}

internal data class UpdateProgress(val received: Long = 0, val verifying: Boolean = false)

internal class AppUpdates(private val context: Context, private val scope: CoroutineScope) {
    private val github = GitHubReleases()
    private val manager = context.packageManager
    @Suppress("DEPRECATION")
    private val signingFlags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private val installed by lazy { manager.getPackageInfo(context.packageName, signingFlags) }
    private val mutableStatus = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.Idle)
    private var transfer: Job? = null
    val status = mutableStatus.asStateFlow()
    val currentVersion: String get() = installed.versionName.orEmpty()
    val allowsDownload: Boolean get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0

    fun check() {
        when (mutableStatus.value) {
            AppUpdateStatus.Checking, is AppUpdateStatus.Downloading, is AppUpdateStatus.Ready -> return
            else -> Unit
        }
        mutableStatus.value = AppUpdateStatus.Checking
        scope.launch {
            mutableStatus.value = try {
                val current = ReleaseVersion.parse(currentVersion)
                    ?: throw UpdateProblem("暂时无法识别当前应用的版本号")
                val release = github.latest()
                when {
                    release == null -> AppUpdateStatus.NoRelease
                    release.version > current -> AppUpdateStatus.Available(release)
                    else -> AppUpdateStatus.Current(release)
                }
            } catch (problem: IOException) {
                AppUpdateStatus.Failed(problem.detail("暂时无法连接 GitHub，请检查网络或打开发布页"))
            }
        }
    }

    fun download() {
        val available = mutableStatus.value as? AppUpdateStatus.Available ?: return
        val release = available.release
        val asset = release.apk ?: return
        if (!allowsDownload) return
        val progress = MutableStateFlow(UpdateProgress())
        mutableStatus.value = AppUpdateStatus.Downloading(release, progress.asStateFlow())
        transfer = scope.launch {
            try {
                val file = github.download(
                    asset,
                    File(context.cacheDir, "app-updates"),
                    received = { progress.value = UpdateProgress(it) }
                ) { candidate ->
                    progress.value = UpdateProgress(asset.size, verifying = true)
                    verifyPackage(candidate, release)
                }
                mutableStatus.value = AppUpdateStatus.Ready(release, file)
            } catch (cancelled: CancellationException) {
                mutableStatus.value = available.copy(message = null)
                throw cancelled
            } catch (problem: IOException) {
                mutableStatus.value = available.copy(message = problem.detail("下载更新失败，可重试或打开 GitHub 发布页"))
            } catch (_: SecurityException) {
                mutableStatus.value = available.copy(message = "系统无法校验安装包，可打开 GitHub 发布页")
            }
        }
    }

    fun cancelDownload() {
        if (mutableStatus.value is AppUpdateStatus.Downloading) transfer?.cancel()
    }

    fun install(activity: Context) {
        val ready = mutableStatus.value as? AppUpdateStatus.Ready ?: return
        if (!ready.apk.isFile) {
            mutableStatus.value = AppUpdateStatus.Available(ready.release, "更新缓存已清理，请重新下载")
            return
        }
        try {
            if (!manager.canRequestPackageInstalls()) {
                val permissions = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", context.packageName, null)
                )
                activity.startActivity(permissions)
                mutableStatus.value = ready.copy(message = "在系统设置允许安装后，请返回此处再次点击「安装更新」")
                return
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", ready.apk)
            val installer = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(installer)
            mutableStatus.value = ready.copy(message = null)
        } catch (_: ActivityNotFoundException) {
            mutableStatus.value = ready.copy(message = "设备没有可用的安装入口，可打开 GitHub 发布页")
        } catch (_: SecurityException) {
            mutableStatus.value = ready.copy(message = "系统限制了应用内安装，可打开 GitHub 发布页")
        } catch (_: IllegalArgumentException) {
            mutableStatus.value = AppUpdateStatus.Available(ready.release, "无法打开本地安装包，请重新下载")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: GitHubRelease) {
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, signingFlags)
            ?: throw UpdateProblem("下载的文件不是有效的 Android 安装包")
        if (candidate.packageName != context.packageName) throw UpdateProblem("安装包的应用包名与当前版本不符")
        if (ReleaseVersion.parse(candidate.versionName) != release.version) throw UpdateProblem("安装包版本与 GitHub 发布标签不符")
        if (PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateProblem("安装包的内部版本号未高于当前版本")
        }
        val currentSigners = installed.signers()
        if (currentSigners.isEmpty() || candidate.signers() != currentSigners) {
            throw UpdateProblem("安装包签名与当前应用不一致")
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.signers(): Set<Signature> =
    if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners.orEmpty().toSet() else signatures.orEmpty().toSet()

private fun IOException.detail(fallback: String): String = when (this) {
    is UpdateProblem -> message ?: fallback
    is SocketTimeoutException -> "连接 GitHub 超时，可重试或打开发布页"
    else -> fallback
}
