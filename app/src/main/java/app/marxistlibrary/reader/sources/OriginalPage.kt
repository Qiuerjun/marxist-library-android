package app.marxistlibrary.reader.sources

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val OriginalPageHosts = OfficialSources.mapTo(mutableSetOf()) { it.base.host }.apply {
    add("marxists.org")
}

internal fun originalPageAddress(value: String): HttpUrl? = value.takeIf { it.length <= 4096 }
    ?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }

// Legacy same-site HTTP links can be upgraded before WebView makes a request.
internal fun HttpUrl.trustedOriginalPage(): HttpUrl? {
    if (host !in OriginalPageHosts || port != HttpUrl.defaultPort(scheme)) return null
    return if (isHttps) this else newBuilder().scheme("https").port(443).build()
}
