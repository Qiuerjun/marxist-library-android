package app.marxistlibrary.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.text.TextUtils
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import app.marxistlibrary.reader.Bookmark
import app.marxistlibrary.reader.settings.ReadingSettings
import app.marxistlibrary.reader.ui.ReaderColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

data class TextAnchor(val id: String = "", val quote: String = "", val offset: Int = 0) {
    fun json() = JSONObject().put("anchor", id).put("quote", quote).put("offset", offset)

    companion object {
        fun from(json: JSONObject) = TextAnchor(json.optString("anchor").take(500), json.optString("quote").take(190),
            json.optInt("offset").coerceAtLeast(0))
    }
}

data class ReaderSelection(val anchor: TextAnchor, val text: String)
data class ReaderFootnote(val index: Int, val label: String, val text: String, val shortened: Boolean)
data class ReaderLocation(val anchor: TextAnchor, val progress: Int, val atFootnote: Boolean, val section: String)
data class SpokenText(val text: String, val anchor: String)
data class ReaderAsset(val mime: String, val input: InputStream)

// Only this fixed local origin serves WebView content. Archive scripts are filtered before rendering.
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWeb(
    context: Context,
    title: String,
    html: String,
    asset: (String) -> ReaderAsset?,
    private val onLink: (Uri) -> Unit,
    private val onPosition: (ReaderLocation) -> Unit,
    private val onFootnote: (ReaderFootnote) -> Unit,
    private val onSelection: (ReaderSelection, Boolean) -> Unit,
    private val onReady: (ReaderWeb) -> Unit,
    private val onFind: (Int, Int) -> Unit
) : WebView(context) {
    private var positionQueued = false
    private val readScroll = Runnable { reportPosition() }
    private val highlightId = View.generateViewId()
    private val noteId = View.generateViewId()
    private var ready = false
    private val template = document(title, html)

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.domStorageEnabled = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.builtInZoomControls = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/reader/") { path ->
                when (path) {
                    "index.html" -> WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(template))
                    "layout.css" -> WebResourceResponse("text/css", "UTF-8", context.assets.open("reader.css"))
                    "notes.js" -> WebResourceResponse("application/javascript", "UTF-8", context.assets.open("reader-notes.js"))
                    "layout.js" -> WebResourceResponse("application/javascript", "UTF-8", context.assets.open("reader-layout.js"))
                    "runtime.js" -> WebResourceResponse("application/javascript", "UTF-8", context.assets.open("reader.js"))
                    else -> null
                }
            }
            .addPathHandler("/image/") { path -> asset(path)?.let { WebResourceResponse(it.mime, null, it.input) } }
            .build()
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                loader.shouldInterceptRequest(request.url) ?: missing()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!request.isForMainFrame) return true
                if (uri.scheme == "https" && uri.encodedAuthority == Host) {
                    if (uri.path == "/reader/index.html") return false
                    val segments = uri.pathSegments
                    if (request.hasGesture() && segments.size == 3 && segments[0] == "reader" && segments[1] == "note") {
                        segments[2].toIntOrNull()?.takeIf { it in 0 until 3000 }?.let(::previewFootnote)
                    }
                    if (request.hasGesture() && segments.size == 2 && segments[0] == "epub" &&
                        segments[1].toIntOrNull()?.let { it >= 0 } == true) onLink(uri)
                    return true
                }
                if (request.hasGesture() && (uri.scheme == "https" || uri.scheme == "http")) onLink(uri)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!ready && url.substringBefore('#') == Index) {
                    ready = true
                    onReady(this@ReaderWeb)
                }
            }
        }
        setOnScrollChangeListener { _, _, _, _, _ ->
            if (ready && !positionQueued) {
                positionQueued = true
                postDelayed(readScroll, 140)
            }
        }
        setFindListener { index, count, complete -> if (complete) onFind(if (count == 0) 0 else index + 1, count) }
        loadUrl(Index)
    }

    fun contents(ids: List<String>) = script("contents(${JSONArray(ids)})")

    fun go(anchor: TextAnchor, result: (Boolean) -> Unit = {}) {
        script("go(${anchor.json()})") {
            reportPosition()
            result(it == "true")
        }
    }

    private fun readLocation(deliver: (ReaderLocation?) -> Unit) {
        script("position()") { raw ->
            deliver(parseObject(raw)?.let {
                ReaderLocation(TextAnchor.from(it), it.optInt("progress").coerceIn(0, 100),
                    it.optBoolean("atFootnote"), it.optString("section"))
            })
        }
    }

    fun capture(deliver: (TextAnchor?) -> Unit) {
        readLocation { deliver(it?.anchor) }
    }

    private fun reportPosition() {
        removeCallbacks(readScroll)
        positionQueued = false
        readLocation { it?.let(onPosition) }
    }

    fun selection(deliver: (ReaderSelection?) -> Unit) {
        script("selection()") { raw ->
            val value = parseObject(raw)
            deliver(if (value == null) null else value.optString("selection").takeIf(String::isNotBlank)?.let {
                ReaderSelection(TextAnchor.from(value), it)
            })
        }
    }

    fun updateMarks(bookmarks: List<Bookmark>) {
        val data = JSONArray()
        bookmarks.filter { it.selection.isNotBlank() }.forEach {
            data.put(TextAnchor(it.anchor, it.quote, it.offset).json().put("selection", it.selection))
        }
        script("marks($data)")
    }

    fun spoken(deliver: (List<SpokenText>) -> Unit) {
        script("speech()") { raw ->
            val chunks = runCatching {
                val values = JSONArray(raw)
                (0 until values.length()).map { index ->
                    val value = values.getJSONObject(index)
                    SpokenText(value.optString("text"), value.optString("anchor"))
                }
            }.getOrDefault(emptyList())
            deliver(chunks)
        }
    }

    fun appearance(colors: ReaderColors, preferences: ReadingSettings, fontScale: Float, keepPosition: Boolean = true) {
        settings.textZoom = (fontScale * 100).toInt().coerceIn(50, 250)
        val variables = JSONObject()
            .put("paper", colors.paper).put("ink", colors.ink).put("accent", colors.accent).put("rule", colors.rule)
            .put("highlight", if (colors.dark) "#654736" else "#FAD9A1")
            .put("size", "${preferences.fontSize}px").put("spacing", preferences.lineHeight / 100.0)
            .put("margin", "${preferences.margin}px")
            .put("paragraph-gap", "${preferences.paragraphSpacing / 100.0}em")
            .put("first-indent", if (preferences.firstLineIndent) "2em" else "0em")
            .put("measure", "${preferences.readingWidth.columns}em")
            .put("scheme", if (colors.dark) "dark" else "light")
            .put("face", if (preferences.serif) "'Noto Serif CJK SC','Noto Serif SC',serif" else "sans-serif")
        script("typography($variables,$keepPosition)") { if (keepPosition) reportPosition() }
    }

    private fun previewFootnote(index: Int) {
        script("previewFootnote($index)") { raw ->
            parseObject(raw)?.let {
                onFootnote(ReaderFootnote(index, it.optString("label").take(60), it.optString("text").take(7500),
                    it.optBoolean("shortened")))
            }
        }
    }

    fun jumpToFootnote(index: Int) = script("jumpToFootnote($index)") { reportPosition() }

    fun returnFromFootnote() = script("returnFromFootnote()") { reportPosition() }

    fun retryImages() = script("reloadImages()")

    fun close() {
        ready = false
        removeCallbacks(readScroll)
        stopLoading()
        destroy()
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        return super.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (!callback.onCreateActionMode(mode, menu)) return false
                if (type == ActionMode.TYPE_FLOATING) {
                    menu.add(0, highlightId, 10, "高亮")
                    menu.add(0, noteId, 11, "添加笔记")
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId !in setOf(highlightId, noteId)) return callback.onActionItemClicked(mode, item)
                selection { result ->
                    if (result != null) onSelection(result, item.itemId == noteId)
                    mode.finish()
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }, type)
    }

    private fun script(action: String, callback: ((String) -> Unit)? = null) {
        if (!ready) {
            callback?.invoke("")
            return
        }
        evaluateJavascript("window.archiveReader && window.archiveReader.$action") {
            if (ready) callback?.invoke(it.orEmpty())
        }
    }

    private fun parseObject(value: String): JSONObject? = runCatching { JSONObject(value) }.getOrNull()

    companion object {
        const val Host = "appassets.androidplatform.net"
        private const val Index = "https://$Host/reader/index.html"

        private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found",
            mapOf("X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(ByteArray(0)))

        private fun document(title: String, html: String): ByteArray = """
            <!doctype html><html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'">
            <title>${TextUtils.htmlEncode(title)}</title>
            <link rel="stylesheet" href="/reader/layout.css">
            </head><body>$html<script src="/reader/notes.js"></script>
            <script src="/reader/layout.js"></script><script src="/reader/runtime.js"></script></body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
