package app.marxistlibrary.reader.update

import app.marxistlibrary.reader.sources.hex
import app.marxistlibrary.reader.sources.readBounded
import app.marxistlibrary.reader.sources.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val Repository = "Qiuerjun/marxist-library-android"
internal const val ProjectHomepage = "https://github.com/$Repository"
internal const val ProjectReleases = "$ProjectHomepage/releases"
private const val LatestReleaseApi = "https://api.github.com/repos/$Repository/releases/latest"
private val DownloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal class UpdateProblem(message: String) : IOException(message)

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parse(value: String?): ReleaseVersion? {
            val parts = pattern.matchEntire(value.orEmpty())?.groupValues ?: return null
            val major = parts[1].toIntOrNull() ?: return null
            val minor = parts[2].toIntOrNull() ?: return null
            val patch = parts[3].toIntOrNull() ?: return null
            return ReleaseVersion(major, minor, patch)
        }
    }
}

internal data class ReleaseApk(val url: HttpUrl, val size: Long, val sha256: String?)

internal data class GitHubRelease(
    val version: ReleaseVersion,
    val page: String,
    val notes: String,
    val apk: ReleaseApk?
)

internal class GitHubReleases(client: OkHttpClient = OkHttpClient()) {
    private val transferClient = client.newBuilder()
        .followRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val apiClient = transferClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun latest(): GitHubRelease? {
        val request = Request.Builder().url(LatestReleaseApi)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MarxistLibrary Android")
            .build()
        return apiClient.newCall(request).readResponse { response ->
            when (response.code) {
                404 -> null
                403, 429 -> throw UpdateProblem("GitHub 暂时限制请求，可稍后重试或打开发布页")
                200 -> {
                    val body = response.body ?: throw UpdateProblem("GitHub 未返回发布信息")
                    try {
                        parseRelease(readBounded(body.byteStream(), 1024 * 1024).toString(Charsets.UTF_8))
                    } catch (_: SerializationException) {
                        throw UpdateProblem("暂时无法识别 GitHub 返回的发布信息")
                    }
                }
                else -> throw UpdateProblem("GitHub 暂时无法提供更新信息")
            }
        }
    }

    suspend fun download(
        asset: ReleaseApk,
        directory: File,
        received: (Long) -> Unit,
        verifyApk: (File) -> Unit
    ): File {
        var url = asset.url
        repeat(6) {
            if (!trustedDownload(url)) throw UpdateProblem("APK 下载地址不属于受支持的 GitHub 来源")
            val request = Request.Builder().url(url).header("User-Agent", "MarxistLibrary Android").build()
            val reply = transferClient.newCall(request).readResponse(discard = {
                if (it is DownloadReply.Saved) it.apk.delete()
            }) { response ->
                if (response.isRedirect) {
                    val redirect = response.header("Location")?.let(url::resolve)
                        ?: throw UpdateProblem("GitHub 返回了无法打开的 APK 下载地址")
                    DownloadReply.Redirect(redirect)
                } else {
                    if (response.code != 200) throw UpdateProblem("GitHub 暂时无法提供安装包")
                    DownloadReply.Saved(saveApk(response, asset, directory, received, verifyApk))
                }
            }
            when (reply) {
                is DownloadReply.Redirect -> url = reply.url
                is DownloadReply.Saved -> return publishApk(reply.apk, directory)
            }
        }
        throw UpdateProblem("GitHub 安装包重定向次数过多")
    }

    private fun saveApk(
        response: Response,
        asset: ReleaseApk,
        directory: File,
        received: (Long) -> Unit,
        verifyApk: (File) -> Unit
    ): File {
        val body = response.body ?: throw UpdateProblem("GitHub 未返回安装包")
        val length = body.contentLength()
        if (length >= 0 && length != asset.size) throw UpdateProblem("安装包大小与发布信息不符")
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateProblem("无法创建更新缓存目录")
        val temporary = File.createTempFile("download-", ".apk", directory)
        var verified = false
        try {
            val hash = asset.sha256?.let { MessageDigest.getInstance("SHA-256") }
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            body.byteStream().use { input ->
                temporary.outputStream().buffered().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > asset.size - size) throw UpdateProblem("安装包大小与发布信息不符")
                        output.write(buffer, 0, count)
                        hash?.update(buffer, 0, count)
                        size += count
                        received(size)
                    }
                }
            }
            if (size != asset.size) throw UpdateProblem("安装包未下载完整")
            if (hash != null && !hex(hash.digest()).equals(asset.sha256, ignoreCase = true)) {
                throw UpdateProblem("安装包的 SHA-256 与 GitHub 发布信息不符")
            }
            verifyApk(temporary)
            verified = true
            return temporary
        } finally {
            if (!verified) temporary.delete()
        }
    }

    // Only a live download can replace the APK that will be offered to the installer.
    private suspend fun publishApk(temporary: File, directory: File): File = try {
        withContext(Dispatchers.IO) {
            val destination = File(directory, "update.apk")
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            destination
        }
    } finally {
        temporary.delete()
    }

    private fun trustedDownload(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.host in DownloadHosts && url.username.isEmpty() && url.password.isEmpty()

    private sealed interface DownloadReply {
        data class Redirect(val url: HttpUrl) : DownloadReply
        data class Saved(val apk: File) : DownloadReply
    }
}

private val ReleaseJson = Json { ignoreUnknownKeys = true }
private val Sha256 = Regex("sha256:([0-9a-fA-F]{64})")

internal fun parseRelease(json: String): GitHubRelease? {
    val reply = ReleaseJson.decodeFromString<ReleaseReply>(json)
    if (reply.draft || reply.prerelease) return null
    val version = ReleaseVersion.parse(reply.tag) ?: throw UpdateProblem("GitHub 发布标签应采用 v1.2.3 这样的版本号")
    val releases = ProjectReleases.toHttpUrl()
    val page = releases.newBuilder().addPathSegment("tag").addPathSegment(reply.tag).build().toString()
    val apks = reply.assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.state == "uploaded" && it.size > 0 }
    val asset = apks.singleOrNull { it.name == "MarxistLibrary-$version.apk" } ?: apks.singleOrNull()
    val apk = asset?.let {
        val digest = it.digest?.let(Sha256::matchEntire)?.groupValues?.get(1)
        if (it.digest != null && digest == null) null else {
            val url = releases.newBuilder().addPathSegment("download").addPathSegment(reply.tag).addPathSegment(it.name).build()
            ReleaseApk(url, it.size, digest)
        }
    }
    return GitHubRelease(version, page, reply.body.orEmpty().trim().take(1200), apk)
}

@Serializable
private data class ReleaseReply(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetReply> = emptyList()
)

@Serializable
private data class AssetReply(val name: String, val state: String, val size: Long, val digest: String? = null)
