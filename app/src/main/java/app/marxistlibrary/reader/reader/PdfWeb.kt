package app.marxistlibrary.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.marxistlibrary.reader.settings.PdfSettings
import app.marxistlibrary.reader.ui.ReaderColors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

internal sealed interface PdfRender {
    data object Opening : PdfRender
    data object Password : PdfRender
    data class Page(val number: Int, val painted: Boolean) : PdfRender
}

// The PDF reader can reach its own offline assets and slices of one catalog attachment.
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class PdfWeb(
    context: Context,
    private val file: File,
    start: PdfPosition,
    options: PdfSettings,
    colors: ReaderColors,
    private val onLocation: (PdfPosition) -> Unit,
    private val onRender: (PdfRender) -> Unit,
    private val onProblem: (String) -> Unit,
    private val onLink: (Uri) -> Unit
) : WebView(context) {
    private val fileSize = file.length()
    private var theme = colors
    private var options = options
    private var openingUrl: String? = null
    private var engineReady = false
    private var pageWaiting = 0
    private var visualRequest = 0L
    private var rendererGone = false
    private var closed = false

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.domStorageEnabled = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        setBackgroundColor(Color.parseColor(colors.paper))
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false

        val loader = WebViewAssetLoader.Builder().addPathHandler("/pdf/") { path ->
            if (path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return@addPathHandler null
            val mime = when (path.substringAfterLast('.')) {
                "html" -> "text/html"
                "mjs", "js" -> "application/javascript"
                "css" -> "text/css"
                "wasm" -> "application/wasm"
                "svg" -> "image/svg+xml"
                "gif" -> "image/gif"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "pfb", "icc", "bcmap" -> "application/octet-stream"
                else -> return@addPathHandler null
            }
            try {
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("script")) "UTF-8" else null,
                    context.assets.open("pdf/$path"))
            } catch (_: IOException) {
                null
            }
        }.build()

        WebViewCompat.addWebMessageListener(this, "archivePdfAndroid", setOf(Origin)) {
                _, message, source, mainFrame, _ ->
            if (mainFrame && local(source) && !closed) receive(message.data)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (request.method != "GET" || !local(uri)) return emptyResponse(404, "Not Found")
                if (uri.path == "/pdf/file" && !request.isForMainFrame) return slice(uri)
                return loader.shouldInterceptRequest(uri) ?: emptyResponse(404, "Not Found")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!request.isForMainFrame) return true
                if (local(uri)) return uri.path != "/pdf/index.html"
                if (request.hasGesture() && (uri.scheme == "https" || uri.scheme == "http")) onLink(uri)
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !closed) problem("暂时无法启动 PDF 阅读器")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) {
                    rendererGone = true
                    problem("系统已释放 PDF 渲染窗口")
                }
                return true
            }
        }

        openingUrl = Uri.parse("$Origin/pdf/index.html").buildUpon()
            .appendQueryParameter("length", fileSize.toString())
            .appendQueryParameter("page", start.page.toString())
            .appendQueryParameter("offset", start.offset.toString())
            .appendQueryParameter("mode", options.mode.name)
            .appendQueryParameter("animation", options.animation.name)
            .appendQueryParameter("edges", options.tapEdges.toString())
            .appendQueryParameter("paper", colors.paper)
            .appendQueryParameter("ink", colors.ink)
            .appendQueryParameter("accent", colors.accent)
            .appendQueryParameter("rule", colors.rule)
            .appendQueryParameter("dark", colors.dark.toString())
            .build().toString()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // Start loading only after Compose has given the WebView a viewport.
        val url = openingUrl ?: return
        if (width <= 0 || height <= 0 || closed) return
        openingUrl = null
        loadUrl(url)
    }

    fun configure(options: PdfSettings, colors: ReaderColors) {
        if (theme != colors) {
            theme = colors
            setBackgroundColor(Color.parseColor(colors.paper))
            if (engineReady) applyTheme()
        }
        if (this.options != options) {
            this.options = options
            if (engineReady) applySettings()
        }
    }

    suspend fun capture(): PdfPosition? = position(json(ask("window.archivePdf.location()")))

    suspend fun dismiss(): Boolean = ask("window.archivePdf.dismiss()") == "true"

    // Back and mode changes remain usable even if the renderer never replies.
    private suspend fun ask(script: String): String? {
        if (!engineReady || closed) return null
        return withTimeoutOrNull(350L) {
            suspendCancellableCoroutine { continuation ->
                evaluateJavascript(script) { continuation.resume(it) }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        WebViewCompat.removeWebMessageListener(this, "archivePdfAndroid")
        if (!rendererGone) stopLoading()
        destroy()
    }

    private fun receive(message: String?) {
        if (message == null || message.length > 1_200) return
        val event = json(message) ?: return
        when (event.optString("type")) {
            "engine" -> {
                engineReady = true
                applyTheme()
                applySettings()
            }
            "position" -> position(event)?.let(onLocation)
            "opening" -> onRender(PdfRender.Opening)
            "password" -> onRender(PdfRender.Password)
            "rendering" -> {
                val number = position(event)?.page ?: return
                pageWaiting = number
                visualRequest++
                onRender(PdfRender.Page(number, painted = false))
            }
            "painted" -> position(event)?.page?.takeIf { it == pageWaiting }?.let(::confirmPaint)
            "problem" -> problem(event.optString("message").take(180).ifBlank { "暂时无法读取 PDF" })
        }
    }

    private fun problem(message: String) {
        engineReady = false
        onProblem(message)
    }

    private fun confirmPaint(page: Int) {
        val request = ++visualRequest
        // A canvas in the DOM can precede the frame becoming available to Android.
        postVisualStateCallback(request, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (engineReady && !closed && requestId == visualRequest) {
                    onRender(PdfRender.Page(page, painted = true))
                }
            }
        })
    }

    private fun applySettings() {
        val value = JSONObject().put("mode", options.mode.name)
            .put("animation", options.animation.name).put("edges", options.tapEdges)
        evaluateJavascript("window.archivePdf.settings($value)", null)
    }

    private fun applyTheme() {
        val colors = JSONObject().put("paper", theme.paper).put("ink", theme.ink)
            .put("accent", theme.accent).put("rule", theme.rule).put("dark", theme.dark)
        evaluateJavascript("window.archivePdf.appearance($colors)", null)
    }

    private fun slice(uri: Uri): WebResourceResponse {
        val range = PdfSlice.within(fileSize, uri.getQueryParameter("start")?.toLongOrNull(),
            uri.getQueryParameter("end")?.toLongOrNull()) ?: return emptyResponse(416, "Range Not Satisfiable")
        return try {
            WebResourceResponse("application/pdf", null, 200, "OK",
                mapOf("Cache-Control" to "no-store", "Content-Length" to range.length.toString()), range.open(file))
        } catch (_: IOException) {
            emptyResponse(404, "Not Found")
        }
    }

    companion object {
        private const val Origin = "https://appassets.androidplatform.net"
        private const val Host = "appassets.androidplatform.net"

        fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

        private fun local(uri: Uri) = uri.scheme == "https" && uri.encodedAuthority == Host

        private fun json(raw: String?) = raw?.let { runCatching { JSONObject(it) }.getOrNull() }

        private fun position(json: JSONObject?): PdfPosition? {
            val page = json?.optInt("page")?.takeIf { it > 0 } ?: return null
            return PdfPosition(page, json.optInt("offset").coerceIn(0, 10_000))
        }

        private fun emptyResponse(code: Int, message: String) =
            WebResourceResponse("text/plain", "UTF-8", code, message, mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0)))
    }
}
