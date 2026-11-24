package app.marxistlibrary.reader.sources

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.Serializable
import java.security.MessageDigest

// Convert website URLs to a host-independent document address.
@Serializable
data class ArchiveRef(val path: String, val anchor: String = "") {
    val key: String get() = if (anchor.isEmpty()) path else "$path#$anchor"
    val original: String
        get() = MainSource.address(path).newBuilder().fragment(anchor.ifEmpty { null }).build().toString()

    companion object {
        fun parse(path: String): ArchiveRef? {
            if (path.length !in 1..2500 || !path.startsWith('/') || path.startsWith("//")) return null
            return "https://www.marxists.org$path".toHttpUrlOrNull()?.let(MainSource::reference)
        }

        fun fromUrl(url: String, sources: List<Source> = OfficialSources): ArchiveRef? {
            val address = url.trim().takeIf { it.length <= 2500 }?.toHttpUrlOrNull() ?: return null
            return sources.firstNotNullOfOrNull { it.reference(address) }
        }
    }
}

data class Source(val id: String, val title: String, val base: HttpUrl, val custom: Boolean = false) {
    fun address(path: String): HttpUrl = (base.toString().trimEnd('/') + path).toHttpUrl()

    fun reference(url: HttpUrl): ArchiveRef? {
        if (!matchesHost(url)) return null
        val prefix = base.encodedPath.trimEnd('/')
        if (!url.encodedPath.startsWith("$prefix/")) return null
        val path = url.encodedPath.removePrefix(prefix)
        if (path != "/chinese" && !path.startsWith("/chinese/")) return null
        val canonical = when (path) {
            "/chinese", "/chinese/index.html", "/chinese/index.htm" -> "/chinese/"
            else -> path
        }
        val query = url.encodedQuery?.let { "?$it" }.orEmpty()
        return ArchiveRef(canonical + query, url.fragment.orEmpty().take(500))
    }

    fun accepts(url: HttpUrl): Boolean =
        url.isHttps && url.username.isEmpty() && url.password.isEmpty() && reference(url) != null

    private fun matchesHost(url: HttpUrl): Boolean {
        val acceptedHost = url.host == base.host || (id == "primary" && url.host == "marxists.org")
        return acceptedHost && (url.port == base.port || (url.scheme == "http" && url.port == 80 && base.port == 443))
    }

    companion object {
        fun custom(name: String, url: String): Source? {
            val base = url.trim().takeIf { it.length < 600 }?.toHttpUrlOrNull() ?: return null
            val valid = base.isHttps && base.host.contains('.') && base.port == 443 &&
                base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null
            if (!valid) return null
            val prefix = base.encodedPath.trimEnd('/').removeSuffix("/chinese")
            val normalized = base.newBuilder().encodedPath(prefix.ifEmpty { "/" }).build()
            return Source(digest(normalized.toString()).take(24), name.trim().take(60), normalized, true)
        }
    }
}

val MainSource = Source("primary", "主站 · Marxists Internet Archive", "https://www.marxists.org".toHttpUrl())

val OfficialSources = listOf(
    MainSource,
    Source("dallas", "达拉斯镜像", "https://marxists.info".toHttpUrl()),
    Source("paris", "巴黎镜像", "https://marxists.architexturez.net".toHttpUrl()),
    Source("nuremberg", "纽伦堡镜像", "https://marxists.wikis.cc".toHttpUrl()),
    Source("san-francisco", "旧金山镜像", "https://marxists.pages.dev".toHttpUrl()),
    Source("malta", "马耳他镜像", "https://marxists-malta.org".toHttpUrl())
)

fun digest(text: String): String = digest(text.toByteArray(Charsets.UTF_8))

fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun hex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 255
            append(alphabet[value shr 4])
            append(alphabet[value and 15])
        }
    }
}

