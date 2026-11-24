package app.marxistlibrary.reader.reader

import android.net.Uri
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.LibraryModel
import app.marxistlibrary.reader.ReadingPosition
import app.marxistlibrary.reader.catalog.Chapter
import app.marxistlibrary.reader.catalog.Heading
import app.marxistlibrary.reader.catalog.OpenPage
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.shareText
import app.marxistlibrary.reader.ui.AppearanceOptions
import app.marxistlibrary.reader.ui.EntryRow
import app.marxistlibrary.reader.ui.Glyph
import app.marxistlibrary.reader.ui.IconAction
import app.marxistlibrary.reader.ui.LoadingPanel
import app.marxistlibrary.reader.ui.LocalReaderColors
import app.marxistlibrary.reader.ui.SectionTitle
import app.marxistlibrary.reader.ui.SliderSetting
import app.marxistlibrary.reader.ui.Toolbar
import app.marxistlibrary.reader.ui.TypographyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ArticleScreen(
    model: LibraryModel, page: OpenPage, route: DocumentRoute, siblings: List<Chapter>,
    back: () -> Unit, replace: (Chapter) -> Unit, open: (DocumentRoute) -> Unit, download: () -> Unit,
    favorite: () -> Unit, saved: Boolean
) {
    val context = LocalContext.current
    val html by key(page.ref.path, page.digest) {
        produceState<String?>(null) {
            value = withContext(Dispatchers.IO) {
                val name = TextUtils.htmlEncode(page.title)
                val address = TextUtils.htmlEncode(page.ref.original)
                val attribution = TextUtils.htmlEncode(page.details.attribution)
                val rights = TextUtils.htmlEncode(page.details.rights)
                page.file?.takeIf { it.isFile }?.readText()?.plus(
                    """<div class="source-credit"><p>来源 <a href="$address">Marxists Internet Archive</a></p><p>$name $attribution $rights</p></div>"""
                )
            }
        }
    }
    val illustrations = remember(page.digest) { page.details.illustrations.associateBy { "${it.key}.${it.extension}" } }
    var recoveredImages by remember(page.digest) { mutableStateOf(false) }
    LaunchedEffect(page.digest) { recoveredImages = model.archive.warmIllustrations(page) }
    val currentIndex = siblings.indexOfFirst { it.ref.key == route.ref.key }.takeIf { it >= 0 }
        ?: siblings.indexOfFirst { it.ref.path == route.path }
    val content = html
    if (content == null) {
        Column {
            Toolbar(route.title.ifBlank { page.title }, back)
            LoadingPanel("正在排版正文")
        }
        return
    }
    TextReader(
        model, page, route, content, page.details.headings, "",
        asset = { name ->
            illustrations[name]?.let { image ->
                val file = model.archive.files.image(image.key, image.extension)
                if (file.isFile) {
                    val type = if (image.extension in setOf("jpg", "jpeg")) "jpeg" else image.extension
                    runCatching { ReaderAsset("image/$type", file.inputStream()) }.getOrNull()
                } else null
            }
        },
        reloadImages = recoveredImages,
        siblings = siblings, previous = siblings.getOrNull(currentIndex - 1)?.let { { replace(it) } },
        next = if (currentIndex >= 0) siblings.getOrNull(currentIndex + 1)?.let { { replace(it) } } else null,
        openChapter = replace,
        onLink = { uri ->
            model.action {
                val ref = model.mirrors.fromSharedText(uri.toString())
                if (ref == null) openWebsite(context, uri.toString())
                else open(DocumentRoute(ref.path, ref.anchor, if (ref.path == route.path) route.edition else ref.path))
            }
        },
        back = back, download = download, favorite = favorite, saved = saved
    )
}

// The same text reader presents safe archive HTML and EPUB chapters.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReader(
    model: LibraryModel, page: OpenPage, route: DocumentRoute, html: String, headings: List<Heading>,
    anchorPrefix: String, asset: (String) -> ReaderAsset?, reloadImages: Boolean = false,
    siblings: List<Chapter> = emptyList(), previous: (() -> Unit)? = null, next: (() -> Unit)? = null,
    openChapter: (Chapter) -> Unit = {}, onLink: (Uri) -> Unit,
    back: () -> Unit, download: () -> Unit, favorite: () -> Unit, saved: Boolean
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val colors = LocalReaderColors.current
    val fontScale = LocalDensity.current.fontScale
    val currentSettings by rememberUpdatedState(preferences)
    val currentColors by rememberUpdatedState(colors)
    val currentLink by rememberUpdatedState(onLink)
    val contents = headings
    val marks by remember(model, route.path) { model.library.marks(route.path) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val localMarks = remember(marks, anchorPrefix) {
        marks.filter { it.anchor.startsWith(anchorPrefix) }.map { it.copy(anchor = it.anchor.removePrefix(anchorPrefix)) }
    }
    val title = route.title.ifBlank { page.title }
    var initial by remember(route, page.digest, anchorPrefix) { mutableStateOf<TextAnchor?>(null) }
    LaunchedEffect(route, page.digest, anchorPrefix) {
        val previousLocation = model.library.mostRecent(route.edition)?.takeIf { it.path == route.path && it.anchor.startsWith(anchorPrefix) }
        initial = if (route.anchor.isNotBlank() || route.quote.isNotBlank())
            TextAnchor(route.anchor.removePrefix(anchorPrefix), route.quote, route.offset)
        else previousLocation?.let { TextAnchor(it.anchor.removePrefix(anchorPrefix), it.quote, it.offset) } ?: TextAnchor()
    }
    var browser by remember(route, page.digest, anchorPrefix) { mutableStateOf<ReaderWeb?>(null) }
    var sheet by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var match by remember { mutableStateOf(0 to 0) }
    var note by remember { mutableStateOf<ReaderSelection?>(null) }
    var footnote by remember(route, page.digest, anchorPrefix) { mutableStateOf<ReaderFootnote?>(null) }
    var location by remember(route, page.digest, anchorPrefix) { mutableStateOf<ReaderLocation?>(null) }
    var leaving by remember(route, page.digest, anchorPrefix) { mutableStateOf(false) }
    var speaker by remember { mutableStateOf<Speech?>(null) }
    val activeSection = contents.indexOfFirst { it.id == location?.section }
    val activeChapter = siblings.indexOfFirst {
        if (anchorPrefix.isEmpty()) it.ref.key == route.ref.key else it.ref.anchor == anchorPrefix
    }.takeIf { it >= 0 } ?: siblings.indexOfFirst { anchorPrefix.isEmpty() && it.ref.path == route.path }
    val currentLabel = contents.getOrNull(activeSection)?.title ?: siblings.getOrNull(activeChapter)?.title
        ?: if (contents.isEmpty()) "正文" else "正文开头"

    fun record(anchor: TextAnchor, at: Long = System.currentTimeMillis()) =
        ReadingPosition(route.edition, route.path, title, page.digest, anchorPrefix + anchor.id, anchor.quote, anchor.offset, at)

    fun mark(selection: ReaderSelection?, withNote: Boolean) {
        if (selection == null) model.notice("先长按选取正文")
        else if (withNote) note = selection
        else model.action {
            model.library.addMark(record(selection.anchor), selection.text)
            model.notice("已添加高亮")
        }
    }

    fun capture(nextAction: () -> Unit) {
        val web = browser
        if (web == null) {
            nextAction()
            return
        }
        if (leaving) return
        leaving = true
        val at = System.currentTimeMillis()
        web.capture { anchor ->
            val savedAnchor = anchor ?: location?.anchor
            model.action {
                try {
                    savedAnchor?.let { model.library.save(record(it, at)) }
                } finally {
                    leaving = false
                    nextAction()
                }
            }
        }
    }

    fun jump(heading: Heading) {
        val web = browser ?: return
        web.go(TextAnchor(heading.id)) { found ->
            if (found) sheet = "" else model.notice("找不到此目录项对应的正文位置")
        }
    }
    BackHandler {
        when {
            location?.atFootnote == true -> browser?.returnFromFootnote()
            searching -> searching = false
            immersive -> immersive = false
            else -> capture(back)
        }
    }
    val currentCapture by rememberUpdatedState(browser)
    DisposableEffect(lifecycle, route, anchorPrefix) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val at = System.currentTimeMillis()
                currentCapture?.capture { anchor -> anchor?.let { model.action { model.library.save(record(it, at)) } } }
                speaker?.pause()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            speaker?.close()
        }
    }
    LaunchedEffect(browser, colors, preferences, fontScale) { browser?.appearance(colors, preferences, fontScale) }
    LaunchedEffect(browser, localMarks) { browser?.updateMarks(localMarks) }
    LaunchedEffect(browser, reloadImages) { if (reloadImages) browser?.retryImages() }
    val settledAnchor = location?.anchor
    LaunchedEffect(route, page.digest, anchorPrefix, settledAnchor) {
        if (settledAnchor != null) {
            val position = record(settledAnchor)
            delay(450)
            model.library.save(position)
        }
    }
    LaunchedEffect(speaker, preferences.speechRate) { speaker?.rate(preferences.speechRate) }
    LaunchedEffect(browser, query, searching) {
        if (!searching || query.isBlank()) {
            match = 0 to 0
            browser?.clearMatches()
        } else {
            delay(130)
            browser?.findAllAsync(query)
        }
    }

    Column(Modifier.fillMaxSize().then(if (immersive) Modifier.statusBarsPadding() else Modifier)) {
        if (!immersive) Toolbar(title, back = { capture(back) }) {
            IconAction(Glyph.MARK, "添加书签") {
                browser?.capture { anchor ->
                    if (anchor != null) model.action {
                        model.library.addMark(record(anchor))
                        model.notice("已添加书签")
                    }
                }
            }
            IconAction(Glyph.MORE, "阅读操作") { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                listOf<Pair<String, () -> Unit>>(
                    (if (saved) "取消收藏" else "收藏文献") to favorite,
                    (if (page.downloaded) "管理离线下载" else "离线下载") to download,
                    "篇内查找" to { searching = !searching },
                    "高亮选中文字" to { browser?.selection { mark(it, false) } },
                    "为选中文字添加笔记" to { browser?.selection { mark(it, true) } },
                    "沉浸阅读" to { immersive = true },
                    "分享原文链接" to { shareText(context, page.ref.original, "分享文献") },
                    "打开原网页" to { openWebsite(context, page.ref.original) }
                ).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() })
                }
            }
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it.take(160) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("篇内查找") }, supportingText = { if (query.isNotBlank()) Text("${match.first} / ${match.second}") })
                IconAction(Glyph.BACK, "上一个匹配", match.second > 0) { browser?.findNext(false) }
                IconAction(Glyph.NEXT, "下一个匹配", match.second > 0) { browser?.findNext(true) }
                IconAction(Glyph.CLOSE, "关闭查找") { searching = false }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 840.dp
            val start = initial
            if (start == null) LoadingPanel("正在恢复阅读位置")
            else Row(Modifier.fillMaxSize()) {
                if (!immersive && wide && (contents.isNotEmpty() || siblings.isNotEmpty())) {
                    Surface(Modifier.width(265.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        ReaderContents(contents, siblings, activeSection, activeChapter, PaddingValues(13.dp),
                            ::jump) { capture { openChapter(it) } }
                    }
                }
                key(route, page.digest, anchorPrefix) {
                    AndroidView(
                        factory = { ctx ->
                            ReaderWeb(ctx, title, html, asset,
                                onLink = { uri -> capture { currentLink(uri) } },
                                onPosition = { location = it },
                                onFootnote = { footnote = it },
                                onSelection = ::mark,
                                onReady = { opened ->
                                    opened.appearance(currentColors, currentSettings, fontScale, keepPosition = false)
                                    opened.contents(contents.map { it.id })
                                    opened.go(start) { ok ->
                                        if (!ok && (start.id.isNotBlank() || start.quote.isNotBlank())) {
                                            model.notice("找不到原定位，已打开正文开头")
                                        }
                                    }
                                    browser = opened
                                },
                                onFind = { index, count -> match = index to count }
                            )
                        },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onRelease = { web -> web.close() }
                    )
                }
            }
        }
        val readingLocation = location
        if (!immersive && readingLocation != null) {
            LinearProgressIndicator(progress = { readingLocation.progress / 100f }, modifier = Modifier.fillMaxWidth())
        } else HorizontalDivider()
        if (readingLocation?.atFootnote == true) {
            TextButton(onClick = { browser?.returnFromFootnote() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("返回注号")
            }
        }
        if (immersive) {
            TextButton(onClick = { immersive = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("显示阅读工具") }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(currentLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                readingLocation?.let { Text("${it.progress}%", style = MaterialTheme.typography.labelMedium) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Glyph.BACK, "上一章", previous != null) { previous?.let { capture(it) } }
                TextButton(onClick = { sheet = "contents" }, enabled = browser != null) { Text("目录") }
                TextButton(onClick = { sheet = "appearance" }) { Text("排版") }
                TextButton(onClick = {
                    if (speaker == null) speaker = Speech(context) { browser?.go(TextAnchor(it)) }
                    sheet = "speech"
                }) { Text("朗读") }
                IconAction(Glyph.NEXT, "下一章", next != null) { next?.let { capture(it) } }
            }
        }
    }
    if (sheet.isNotEmpty()) ModalBottomSheet(onDismissRequest = { sheet = "" }) {
        when (sheet) {
            "contents" -> ReaderContents(contents, siblings, activeSection, activeChapter,
                PaddingValues(start = 19.dp, end = 19.dp, bottom = 30.dp), ::jump) { chapter ->
                sheet = ""
                capture { openChapter(chapter) }
            }
            "appearance" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
                SectionTitle("阅读排版")
                AppearanceOptions(preferences.appearance) { model.action { model.settings.appearance(it) } }
                TypographyOptions(model, preferences)
            }
            else -> speaker?.let { voice ->
                val state = voice.state
                Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
                    SectionTitle("系统朗读")
                    Text(state.problem.ifBlank {
                        if (!state.ready) "正在准备中文语音" else if (state.localVoice) "中文语音可离线使用" else "当前系统语音需要联网"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.size > 0) Text("${(state.index + 1).coerceAtMost(state.size)} / ${state.size} 个段落")
                    SliderSetting("语速", preferences.speechRate, 65..160, "%") { model.action { model.settings.speechRate(it) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.playing) Button(onClick = voice::pause) { Text("暂停") }
                        else Button(onClick = {
                            if (state.index < state.size) voice.resume()
                            else browser?.spoken { if (it.isEmpty()) model.notice("当前页没有可朗读的段落") else voice.start(it) }
                        }, enabled = state.ready && browser != null) { Text(if (state.index < state.size) "继续朗读" else "从当前位置朗读") }
                        TextButton(onClick = voice::skip, enabled = state.index + 1 < state.size) { Text("下一段") }
                    }
                }
            }
        }
    }
    footnote?.let { original ->
        val dismiss = { footnote = null }
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        ModalBottomSheet(onDismissRequest = dismiss) {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 20.dp)) {
                SectionTitle(if (original.label.isBlank()) "原文注释" else "原文注释 ${original.label}")
                val size = preferences.fontSize.coerceIn(16, 26)
                SelectionContainer(
                    Modifier.fillMaxWidth().heightIn(max = windowHeight * .48f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(original.text.ifBlank { "可在文内查看这条注释。" }, fontSize = size.sp,
                        lineHeight = (size * preferences.lineHeight / 100f).sp)
                }
                if (original.shortened) Text("完整内容可在原注位置阅读", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = dismiss) { Text("返回正文") }
                    TextButton(onClick = {
                        dismiss()
                        browser?.jumpToFootnote(original.index)
                    }) { Text("定位到原注") }
                }
            }
        }
    }
    note?.let { selection ->
        var draft by remember(selection) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { note = null }, title = { Text("添加笔记") },
            text = {
                Column {
                    Text(selection.text.take(160))
                    OutlinedTextField(draft, { draft = it.take(10_000) }, label = { Text("笔记") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = {
                model.action { model.library.addMark(record(selection.anchor), selection.text, draft); model.notice("笔记已保存") }
                note = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { note = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ReaderContents(
    headings: List<Heading>, chapters: List<Chapter>, activeSection: Int, activeChapter: Int, padding: PaddingValues,
    jump: (Heading) -> Unit, open: (Chapter) -> Unit
) {
    val selectedRow = when {
        activeSection >= 0 -> 1 + activeSection
        activeChapter >= 0 -> 1 + headings.size + activeChapter
        else -> 0
    }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = (selectedRow - 1).coerceAtLeast(0))
    LaunchedEffect(selectedRow) {
        if (selectedRow > 0 && !scroll.isScrollInProgress && scroll.layoutInfo.visibleItemsInfo.none { it.index == selectedRow }) {
            scroll.scrollToItem(selectedRow - 1)
        }
    }
    LazyColumn(state = scroll, contentPadding = padding) {
        item { SectionTitle("目录") }
        items(headings.size, key = { "section-" + headings[it].id }) { index ->
            val heading = headings[index]
            val active = index == activeSection
            EntryRow(heading.title, if (active) "当前阅读位置" else "", Glyph.TOC, current = active) { jump(heading) }
        }
        items(chapters.size, key = { "chapter-" + chapters[it].ref.key }) { index ->
            val chapter = chapters[index]
            val active = activeSection < 0 && index == activeChapter
            EntryRow(chapter.title, if (active) "当前阅读位置" else "", current = active) { open(chapter) }
        }
        if (headings.isEmpty() && chapters.isEmpty()) item { Text("这一页没有章节标题") }
    }
}
