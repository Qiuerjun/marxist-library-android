package app.marxistlibrary.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import app.marxistlibrary.reader.R
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.sources.originalPageAddress
import app.marxistlibrary.reader.sources.trustedOriginalPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume

// The source site runs in a separate WebView from the local document readers.
@Composable
internal fun OriginalPageScreen(address: String, back: () -> Unit, notice: (String) -> Unit) {
    val context = LocalContext.current
    val browserLabel = stringResource(R.string.original_page_browser)
    val browserUnavailable = stringResource(R.string.app_update_link_failed)
    val initial = remember(address) { originalPageAddress(address)?.trustedOriginalPage()?.toString() }
    var current by rememberSaveable(address) { mutableStateOf(initial.orEmpty()) }
    var web by remember(address) { mutableStateOf<WebView?>(null) }
    var lastPage by remember(address) { mutableStateOf<String?>(null) }
    var fileLink by remember(address) { mutableStateOf<String?>(null) }
    var external by remember(address) { mutableStateOf<HttpUrl?>(null) }
    var failure by remember(address) { mutableStateOf<String?>(null) }
    var rendererGone by remember(address) { mutableStateOf(false) }
    var progress by remember(address) { mutableIntStateOf(0) }

    fun openBrowser(url: String) {
        if (!openWebsite(context, url)) notice(browserUnavailable)
    }

    fun browser() = openBrowser(fileLink ?: current)

    fun backPage() {
        val displayed = lastPage
        val view = web
        when {
            external != null -> external = null
            fileLink != null && displayed != null -> {
                current = displayed
                fileLink = null
                failure = null
                progress = 100
            }
            !rendererGone && view?.canGoBack() == true -> {
                fileLink = null
                failure = null
                view.goBack()
            }
            else -> back()
        }
    }

    fun reload() {
        val target = originalPageAddress(fileLink ?: current)?.trustedOriginalPage()?.toString() ?: initial ?: return
        progress = 0
        failure = null
        fileLink = null
        if (rendererGone) {
            rendererGone = false
        } else {
            web?.let { if (it.url == target) it.reload() else it.loadUrl(target) }
        }
    }

    BackHandler { backPage() }
    val host = originalPageAddress(fileLink ?: current)?.host.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Toolbar(host.ifBlank { stringResource(R.string.original_page_title) }, ::backPage) {
            IconAction(Glyph.REFRESH, stringResource(R.string.original_page_reload), action = ::reload)
            TextButton(onClick = ::browser) { Text(browserLabel) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!rendererGone && initial != null) {
                AndroidView(
                    factory = { pageContext ->
                        WebView(pageContext).apply {
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    if (web === view) progress = newProgress.coerceIn(0, 100)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView, request: WebResourceRequest
                                ): WebResourceResponse? {
                                    val url = originalPageAddress(request.url.toString())
                                    if (url?.isHttps == true && url.trustedOriginalPage() == url && request.method == "GET") return null
                                    if (request.isForMainFrame) view.post {
                                        if (web === view && request.method != "GET") {
                                            failure = pageContext.getString(R.string.original_page_browser_required)
                                        }
                                    }
                                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                                        emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = originalPageAddress(request.url.toString()) ?: return true
                                    val trusted = url.trustedOriginalPage()
                                    if (url.isHttps && trusted == url) return false
                                    if (request.isForMainFrame && web === view) {
                                        if (trusted != null) view.loadUrl(trusted.toString())
                                        else {
                                            external = url
                                            progress = 100
                                        }
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (web !== view) return
                                    val target = originalPageAddress(url)
                                    if (target == null || !target.isHttps || target.trustedOriginalPage() != target) {
                                        view.stopLoading()
                                        if (target != null) external = target
                                        progress = 100
                                        return
                                    }
                                    current = target.toString()
                                    failure = null
                                    fileLink = null
                                    progress = 0
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    val target = originalPageAddress(url)
                                    if (web === view && target?.isHttps == true && target.trustedOriginalPage() == target) {
                                        current = target.toString()
                                    }
                                }

                                override fun onPageCommitVisible(view: WebView, url: String) {
                                    val target = originalPageAddress(url)
                                    if (web === view && failure == null && target?.isHttps == true && target.trustedOriginalPage() == target) {
                                        lastPage = target.toString()
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView, request: WebResourceRequest, error: WebResourceError
                                ) {
                                    if (web === view && request.isForMainFrame) failure = error.description.toString()
                                }

                                override fun onReceivedHttpError(
                                    view: WebView, request: WebResourceRequest, response: WebResourceResponse
                                ) {
                                    if (web === view && request.isForMainFrame) {
                                        failure = pageContext.getString(R.string.original_page_http_error, response.statusCode)
                                    }
                                }

                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    if (web === view) {
                                        rendererGone = true
                                        failure = pageContext.getString(R.string.original_page_interrupted)
                                    }
                                    return true
                                }
                            }
                            setDownloadListener { url, _, _, _, _ ->
                                if (web === this) {
                                    val target = originalPageAddress(url)
                                    if (target?.isHttps == true && target.trustedOriginalPage() == target) fileLink = target.toString()
                                }
                            }
                            web = this
                            current = originalPageAddress(current)?.trustedOriginalPage()?.toString() ?: initial
                            loadUrl(current)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        if (web === view) web = null
                        view.destroy()
                    }
                )
            }
            val error = failure
            if (fileLink != null || error != null || initial == null) {
                Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        if (fileLink != null) {
                            MessagePanel(stringResource(R.string.original_page_file),
                                action = browserLabel, onAction = ::browser)
                        } else {
                            MessagePanel(stringResource(R.string.original_page_unavailable), error.orEmpty(),
                                stringResource(R.string.original_page_retry), ::reload)
                        }
                    }
                }
            } else if (progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                )
            }
        }
    }
    external?.let { target ->
        AlertDialog(
            onDismissRequest = { external = null },
            title = { Text(stringResource(R.string.original_page_external_title)) },
            text = { Text(stringResource(R.string.original_page_external_host, target.host)) },
            confirmButton = {
                TextButton(onClick = {
                    external = null
                    openBrowser(target.toString())
                }) { Text(browserLabel) }
            },
            dismissButton = { TextButton(onClick = { external = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

internal suspend fun clearOriginalPageData(context: Context) = withContext(Dispatchers.Main.immediate) {
    val cleared = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val storage = WebStorage.getInstance()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                WebStorageCompat.deleteBrowsingData(storage) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            } else {
                val view = WebView(context)
                try { view.clearCache(true) } finally { view.destroy() }
                storage.deleteAllData()
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies {
                    cookies.flush()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }
    if (cleared == null) throw IOException(context.getString(R.string.original_page_data_clear_failed))
}
