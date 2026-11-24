package app.marxistlibrary.reader.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.LibraryModel
import app.marxistlibrary.reader.ReadingPosition
import app.marxistlibrary.reader.catalog.OpenPage
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.ui.Glyph
import app.marxistlibrary.reader.ui.IconAction
import app.marxistlibrary.reader.ui.LoadingPanel
import app.marxistlibrary.reader.ui.LocalReaderColors
import app.marxistlibrary.reader.ui.MessagePanel
import app.marxistlibrary.reader.ui.Toolbar
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val positionSaver = listSaver<PdfPosition?, Int>(
    save = { position -> position?.let { listOf(it.page, it.offset) }.orEmpty() },
    restore = { PdfPosition(it[0], it[1]) }
)

// AttachmentScreen opens the file once. Both PDF engines share its toolbar and reading position.
@Composable
internal fun PdfScreen(
    model: LibraryModel, page: OpenPage, route: DocumentRoute, file: File, back: () -> Unit, download: () -> Unit,
    favorite: () -> Unit, saved: Boolean, external: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalReaderColors.current
    val settings by model.preferences.collectAsStateWithLifecycle()
    val title = route.title.ifBlank { page.title }
    val supportsEnhanced = remember { PdfWeb.supported() }
    var basic by rememberSaveable(file.path, route) { mutableStateOf(!supportsEnhanced) }
    var location by rememberSaveable(file.path, route, stateSaver = positionSaver) {
        mutableStateOf<PdfPosition?>(null)
    }

    val opening by produceState<PdfPosition?>(null, file, route) {
        val previous = model.library.mostRecent(route.edition)?.takeIf { it.path == route.path }
        value = if (route.anchor.startsWith("pdf:")) PdfPosition.from(route.anchor, route.offset)
        else PdfPosition.from(previous?.anchor.orEmpty(), previous?.offset ?: 0)
    }
    var web by remember(file.path) { mutableStateOf<PdfWeb?>(null) }
    var retry by remember(file.path) { mutableIntStateOf(0) }
    var render by remember(file.path, route, retry) { mutableStateOf<PdfRender>(PdfRender.Opening) }
    var problem by remember(file.path, route, retry) { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    fun record(value: PdfPosition, time: Long = System.currentTimeMillis()) =
        ReadingPosition(route.edition, route.path, title, page.digest,
            value.anchor, "第 ${value.page} 页", value.offset, time)

    fun store(value: PdfPosition, time: Long = System.currentTimeMillis()) {
        model.action { model.library.save(record(value, time)) }
    }

    fun moveTo(position: PdfPosition) {
        if (location == position) return
        location = position
        store(position)
    }

    fun resumeAt(): PdfPosition {
        val previous = location ?: opening ?: PdfPosition()
        val pending = (render as? PdfRender.Page)?.takeUnless { it.painted }
        return if (pending == null || pending.number == previous.page) previous else PdfPosition(pending.number)
    }

    fun openBasic() {
        if (switching || leaving) return
        val target = resumeAt()
        val view = web
        switching = true
        scope.launch {
            val exact = if ((render as? PdfRender.Page)?.painted == true) view?.capture() else null
            if (!leaving) {
                moveTo(exact ?: target)
                basic = true
            }
            switching = false
        }
    }

    fun openEnhanced() {
        if (switching || leaving) return
        if (!basic) location = resumeAt()
        retry++ // Recreate the WebView and reload the same local file.
        basic = false
    }

    fun leave() {
        if (leaving) return
        leaving = true
        val view = web
        val time = System.currentTimeMillis()
        scope.launch {
            if (view?.dismiss() == true) {
                leaving = false
            } else {
                val selected = view?.capture()
                (selected ?: location)?.let { store(it, time) }
                back()
            }
        }
    }
    BackHandler { leave() }
    DisposableEffect(web, lifecycle) {
        val view = web
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    val time = System.currentTimeMillis()
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        (view?.capture() ?: location)?.let { store(it, time) }
                    }
                    view?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        Toolbar(title, ::leave) {
            if (basic) {
                if (supportsEnhanced) {
                    TextButton(onClick = ::openEnhanced, enabled = !switching) { Text("增强阅读") }
                }
            } else {
                IconAction(Glyph.SETTINGS, "阅读设置") { showSettings = true }
                IconAction(Glyph.MARK, "添加 PDF 书签", location != null && problem == null) {
                    scope.launch {
                        val mark = web?.capture() ?: location ?: return@launch
                        model.action {
                            model.library.addMark(record(mark))
                            model.notice("已添加第 ${mark.page} 页的书签")
                        }
                    }
                }
            }
            IconAction(Glyph.MORE, "PDF 操作") { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (!basic && (location != null || opening != null)) {
                    DropdownMenuItem(text = { Text("切换到基础阅读") }, onClick = { more = false; openBasic() })
                }
                listOf(
                    (if (saved) "取消收藏" else "收藏") to favorite,
                    "离线下载" to download,
                    "用其他阅读器打开" to external,
                    "打开原网页" to { openWebsite(context, page.ref.original); Unit }
                ).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { more = false; action() })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = problem
            val start = location ?: opening
            when {
                start == null -> LoadingPanel("正在恢复 PDF 阅读位置")
                basic -> BasicPdfScreen(file, start, { if (basic) moveTo(it) }, external)
                error == null -> key(file.path, route, retry) {
                    val attempt = retry
                    AndroidView(
                        factory = { ctx ->
                            PdfWeb(
                                ctx, file, start, settings.pdf, colors,
                                onLocation = { position ->
                                    if (!basic && attempt == retry) moveTo(position)
                                },
                                onRender = {
                                    if (!basic && attempt == retry) render = it
                                },
                                onProblem = {
                                    if (!basic && attempt == retry) problem = it
                                },
                                onLink = { openWebsite(context, it.toString()) }
                            ).also { web = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.configure(settings.pdf, colors) },
                        onRelease = { released ->
                            if (web === released) web = null
                            released.close()
                        }
                    )
                }
            }
            if (!basic && start != null) {
                PdfRenderNotice(
                    render, error, retry, Modifier.align(Alignment.Center),
                    onRetry = ::openEnhanced, onBasic = ::openBasic
                )
            }
        }
    }
    if (showSettings && !basic) {
        PdfSettingsDialog(settings.pdf, onClose = { showSettings = false }) { updated ->
            showSettings = false
            model.action { model.settings.pdf(updated) }
        }
    }
}

private enum class PdfWait { Quiet, Loading, Slow, TimedOut }

// The native timer follows each page until the WebView confirms that a frame can be drawn.
@Composable
private fun PdfRenderNotice(
    render: PdfRender, problem: String?, attempt: Int, modifier: Modifier,
    onRetry: () -> Unit, onBasic: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val page = (render as? PdfRender.Page)?.number
    val waiting = render == PdfRender.Opening || render is PdfRender.Page && !render.painted
    var wait by remember(render, problem, attempt) { mutableStateOf(PdfWait.Quiet) }
    LaunchedEffect(render, problem, attempt, lifecycle) {
        if (!waiting || problem != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (wait == PdfWait.Quiet) {
                delay(250)
                wait = PdfWait.Loading
            }
            if (wait == PdfWait.Loading) {
                delay(7_750)
                wait = PdfWait.Slow
            }
            if (wait == PdfWait.Slow) {
                delay(12_000)
                wait = PdfWait.TimedOut
            }
        }
    }
    if (problem == null && (!waiting || wait == PdfWait.Quiet)) return

    Surface(
        modifier.padding(12.dp).widthIn(max = 400.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large, tonalElevation = 4.dp, shadowElevation = 2.dp
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when {
                problem != null -> MessagePanel("增强阅读无法显示 PDF", problem, "重试", onRetry, "基础阅读", onBasic)
                wait == PdfWait.Slow || wait == PdfWait.TimedOut -> {
                    val label = when {
                        wait == PdfWait.TimedOut && page != null -> "第 $page 页渲染超时"
                        wait == PdfWait.TimedOut -> "PDF 打开超时"
                        page != null -> "第 $page 页渲染较慢"
                        else -> "PDF 打开较慢"
                    }
                    MessagePanel(label, action = if (page == null) "重试" else "重试本页",
                        onAction = onRetry, alternate = "基础阅读", onAlternate = onBasic)
                }
                else -> LoadingPanel(if (page == null) "正在打开 PDF" else "正在渲染第 $page 页")
            }
        }
    }
}
