package app.marxistlibrary.reader.sources

import android.net.Network
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

enum class ConnectionIssue(val label: String) {
    OFFLINE("当前设备未联网"),
    TIMEOUT("连接超时"),
    DNS("无法解析站点"),
    CERTIFICATE("无法验证 HTTPS 证书"),
    MISSING("当前来源缺少这篇文献"),
    LIMITED("来源暂时限流"),
    SERVER("站点暂时无法响应"),
    REJECTED("站点拒绝了请求"),
    WRONG_CONTENT("网站返回的内容与所选文献不符"),
    UNSUPPORTED("此文献暂时无法在阅读器中显示"),
    TOO_LARGE("文件超出阅读器处理上限"),
    WIFI("等待 Wi-Fi"),
    STORAGE("无法写入本机存储")
}

class ArchiveProblem(
    val issue: ConnectionIssue,
    val retrySeconds: Long? = null
) : IOException(issue.label)

data class WebReply(
    val url: HttpUrl,
    val bytes: ByteArray,
    val media: String?,
    val charset: String?,
    val tag: String?,
    val modified: String?,
    val unchanged: Boolean
)

// The reader and downloader use the same URL, redirect and response constraints.
class ArchiveHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(publicDns(Dns.SYSTEM::lookup))
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(10))
        .build()
) {
    suspend fun get(
        source: Source,
        path: String,
        limit: Int,
        tag: String? = null,
        modified: String? = null,
        network: Network? = null
    ): WebReply {
        val headers = Headers.Builder().apply {
            tag?.let { add("If-None-Match", it) }
            modified?.let { add("If-Modified-Since", it) }
        }.build()
        return fetch(source, path, headers, network) { response ->
            if (response.code != 200 && response.code != 304) {
                throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
            }
            val body = response.body
            val type = body?.contentType()
            val unchanged = response.code == 304
            val bytes = if (unchanged) ByteArray(0) else {
                if (body == null || body.contentLength() > limit) throw ArchiveProblem(ConnectionIssue.TOO_LARGE)
                readBounded(body.byteStream(), limit)
            }
            WebReply(response.request.url, bytes, type?.toString(), type?.charset()?.name(),
                response.header("ETag"), response.header("Last-Modified"), unchanged)
        }
    }

    suspend fun <T> fetch(
        source: Source,
        path: String,
        headers: Headers,
        network: Network?,
        read: (Response) -> T
    ): T {
        val transport = if (network == null) client else client.newBuilder()
            .dns(publicDns { network.getAllByName(it).toList() })
            .socketFactory(network.socketFactory)
            .build()
        var url = source.address(path)
        repeat(5) {
            if (!source.accepts(url)) throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
            val request = Request.Builder().url(url).headers(headers)
                .header("User-Agent", "ChineseArchiveReader/1.0 (Android)")
                .build()
            val step = try {
                transport.newCall(request).readResponse { response ->
                    if (response.code in RedirectCodes) {
                        val target = response.header("Location")?.let(url::resolve)
                            ?: throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
                        Step.Redirect(target)
                    } else {
                        if (!response.isSuccessful && response.code != 304 && response.code != 416) {
                            throw fromStatus(response.code, response.header("Retry-After"))
                        }
                        Step.Result(read(response))
                    }
                }
            } catch (failure: IOException) {
                throw when (failure) {
                    is ArchiveProblem -> failure
                    is UnknownHostException -> ArchiveProblem(ConnectionIssue.DNS)
                    is SocketTimeoutException -> ArchiveProblem(ConnectionIssue.TIMEOUT)
                    is SSLException -> ArchiveProblem(ConnectionIssue.CERTIFICATE)
                    else -> ArchiveProblem(ConnectionIssue.SERVER)
                }
            }
            when (step) {
                is Step.Result -> return step.value
                is Step.Redirect -> url = step.target
            }
        }
        throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
    }

    private sealed interface Step<out T> {
        data class Redirect(val target: HttpUrl) : Step<Nothing>
        data class Result<T>(val value: T) : Step<T>
    }

    companion object {
        private val RedirectCodes = setOf(301, 302, 303, 307, 308)

        private fun fromStatus(code: Int, retryAfter: String?): ArchiveProblem {
            val delay = retryAfter?.toLongOrNull()
                ?: retryAfter?.let {
                    runCatching {
                        Duration.between(Instant.now(), ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
                    }.getOrNull()
                }
            return when {
                code == 429 || (code == 503 && delay != null) ->
                    ArchiveProblem(ConnectionIssue.LIMITED, (delay ?: 60L).coerceIn(30, 86_400))
                code == 404 || code == 410 -> ArchiveProblem(ConnectionIssue.MISSING)
                code in 500..599 -> ArchiveProblem(ConnectionIssue.SERVER)
                else -> ArchiveProblem(ConnectionIssue.REJECTED)
            }
        }

        private fun publicDns(resolve: (String) -> List<InetAddress>) = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val public = resolve(hostname).filter(::isPublic)
                if (public.isEmpty()) throw UnknownHostException("Public address required for archive sources")
                return public
            }
        }

        private fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            if (bytes.size == 16) return first and 0xfe != 0xfc
            return first in 1..223 && !(first == 100 && second in 64..127)
        }
    }
}

internal suspend fun <T> Call.readResponse(discard: (T) -> Unit = {}, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        val result = read(response)
                        if (continuation.isActive) {
                            continuation.resume(result) { _, value, _ -> discard(value) }
                        } else {
                            discard(result)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw ArchiveProblem(ConnectionIssue.TOO_LARGE)
        output.write(buffer, 0, count)
    }
}

