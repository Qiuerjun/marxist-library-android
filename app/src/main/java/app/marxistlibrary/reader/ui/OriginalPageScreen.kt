package app.marxistlibrary.reader.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.marxistlibrary.reader.R
import app.marxistlibrary.reader.openWebsite

// The source site runs in a separate WebView from the local document readers.
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OriginalPageScreen(address: String, back: () -> Unit, notice: (String) -> Unit) {
    val context = LocalContext.current
    val browserLabel = stringResource(R.string.original_page_browser)
    val browserUnavailable = stringResource(R.string.app_update_link_failed)
    var current by rememberSaveable(address) { mutableStateOf(address) }
    var web by remember(address) { mutableStateOf<WebView?>(null) }
    var lastPage by remember(address) { mutableStateOf<String?>(null) }
    var fileLink by remember(address) { mutableStateOf<String?>(null) }
    var failure by remember(address) { mutableStateOf<String?>(null) }
    var rendererGone by remember(address) { mutableStateOf(false) }
    var progress by remember(address) { mutableIntStateOf(0) }

    fun browser() {
        if (!openWebsite(context, fileLink ?: current)) notice(browserUnavailable)
    }

    fun backPage() {
        val displayed = lastPage
        val view = web
        when {
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
        val target = fileLink ?: current
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
    val host = Uri.parse(fileLink ?: current).host.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Toolbar(host.ifBlank { stringResource(R.string.original_page_title) }, ::backPage) {
            IconAction(Glyph.REFRESH, stringResource(R.string.original_page_reload), action = ::reload)
            TextButton(onClick = ::browser) { Text(browserLabel) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!rendererGone) {
                AndroidView(
                    factory = { pageContext ->
                        WebView(pageContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    if (web === view) progress = newProgress.coerceIn(0, 100)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url
                                    if (!uri.isWebAddress()) return true
                                    // Older source pages still link to HTTP. Open tapped links through HTTPS.
                                    if (request.isForMainFrame && request.hasGesture() && uri.scheme == "http") {
                                        view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (web !== view) return
                                    if (Uri.parse(url).isWebAddress()) current = url
                                    failure = null
                                    fileLink = null
                                    progress = 0
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    if (web === view && Uri.parse(url).isWebAddress()) current = url
                                }

                                override fun onPageCommitVisible(view: WebView, url: String) {
                                    if (web === view && failure == null && Uri.parse(url).isWebAddress()) lastPage = url
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
                                if (web === this && Uri.parse(url).isWebAddress()) fileLink = url
                            }
                            web = this
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
            if (fileLink != null || error != null) {
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
}

private fun Uri.isWebAddress() = (scheme == "https" || scheme == "http") && !host.isNullOrBlank()
