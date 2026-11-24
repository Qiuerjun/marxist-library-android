package app.marxistlibrary.reader.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marxistlibrary.reader.CatalogEntry
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.LibraryModel
import app.marxistlibrary.reader.catalog.Chapter
import app.marxistlibrary.reader.catalog.ArchiveProgress
import app.marxistlibrary.reader.catalog.ArchiveStage
import app.marxistlibrary.reader.catalog.OpenPage
import app.marxistlibrary.reader.catalog.PageFormat
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.reader.AttachmentScreen
import app.marxistlibrary.reader.reader.ArticleScreen
import app.marxistlibrary.reader.shareText
import app.marxistlibrary.reader.sources.ArchiveRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun DocumentScreen(
    model: LibraryModel,
    route: DocumentRoute,
    back: () -> Unit,
    open: (DocumentRoute) -> Unit,
    replace: (DocumentRoute) -> Unit,
    settings: () -> Unit,
    download: (Int) -> Unit
) {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    var page by remember(route) { mutableStateOf(if (route.textOnly) null else model.archive.seedPreview(route.ref)) }
    var entry by remember(route) { mutableStateOf<CatalogEntry?>(null) }
    var siblings by remember(route.parent) { mutableStateOf(emptyList<Chapter>()) }
    var problem by remember(route) { mutableStateOf<String?>(null) }
    val opening = remember(route, reload) { MutableStateFlow<ArchiveProgress?>(ArchiveProgress(ArchiveStage.CACHE)) }
    val progress by opening.collectAsStateWithLifecycle()
    val title = page?.title ?: route.title.ifBlank { "文献" }
    val favoriteFlow = model.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorite = favoriteFlow.value.any { it.path == route.path }
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(route, reload) {
        problem = null
        try {
            entry = model.archive.entry(route.path)
            page = model.archive.open(route.ref, force = reload > 0, progress = { opening.value = it })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            problem = error.message ?: "这个页面暂时无法打开"
        } finally {
            opening.value = null
        }
    }
    LaunchedEffect(route.parent) {
        if (route.parent.isNotEmpty()) {
            try {
                siblings = model.archive.open(ArchiveRef(route.parent)).chapters
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                siblings = emptyList()
            }
        }
    }

    fun addDownloads(current: OpenPage, chapters: List<Chapter> = emptyList(), recursive: Boolean = false) {
        if (!current.details.downloadsAllowed) {
            model.notice("站方对此文献设置了下载限制")
            return
        }
        if (!notificationAsked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationAsked = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        model.action {
            val id = model.downloads.add(ArchiveRef(current.ref.path), entry?.title ?: current.title, chapters, recursive)
            download(id)
        }
    }

    fun toggleFavorite(current: OpenPage) {
        model.action {
            val key = if (favorite) favoriteFlow.value.first { it.path == current.ref.path }.anchor else ""
            model.library.favorite(ArchiveRef(current.ref.path, key), entry?.title ?: current.title,
                entry?.author.orEmpty(), !favorite)
            model.notice(if (favorite) "已取消收藏" else "已收藏")
        }
    }

    fun sibling(chapter: Chapter) {
        replace(route.copy(path = chapter.ref.path, anchor = chapter.ref.anchor, title = chapter.title, quote = "",
            offset = 0, digest = ""))
    }

    val current = page
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(title, back)
            val stage = progress
            if (stage != null) ArchiveLoadingPanel(stage)
            else {
                MessagePanel("暂时无法打开", problem.orEmpty(), "重试", { reload++ }, "打开原网页",
                    { openWebsite(context, route.ref.original) })
                TextButton(onClick = settings, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("检查或切换镜像") }
            }
        }
        return
    }

    when {
        current.format == PageFormat.PDF || current.format == PageFormat.EPUB ->
            AttachmentScreen(model, current, route, back, { addDownloads(current) }, { toggleFavorite(current) }, favorite)
        current.format == PageFormat.ARTICLE || route.textOnly && current.file != null ->
            ArticleScreen(model, current, route, siblings, back, ::sibling, open, { addDownloads(current) },
                { toggleFavorite(current) }, favorite)
        else -> {
            val independent = entry?.category == "作者" || route.path == "/chinese/" || entry?.category == "专题"
            DirectoryPage(
                page = current, title = entry?.title ?: current.title, author = entry?.author.orEmpty(),
                back = back, favorite = favorite, toggleFavorite = { toggleFavorite(current) },
                refresh = { reload++ }, progress = progress, problem = problem,
                openChapter = { chapter ->
                    open(DocumentRoute(chapter.ref.path, chapter.ref.anchor, if (independent) chapter.ref.path else route.edition,
                        current.ref.path, chapter.title))
                },
                openLatest = {
                    model.action {
                        val latest = model.library.mostRecent(route.edition)
                        if (latest != null && latest.path != current.ref.path) {
                            open(DocumentRoute.from(latest).copy(parent = current.ref.path))
                        } else {
                            current.chapters.firstOrNull()?.let {
                                open(DocumentRoute(it.ref.path, it.ref.anchor, if (independent) it.ref.path else route.edition,
                                    current.ref.path, it.title))
                            }
                        }
                    }
                },
                readPage = { open(route.copy(textOnly = true)) },
                download = { selection -> addDownloads(current, selection, recursive = true) }
            )
        }
    }
}

@Composable
private fun DirectoryPage(
    page: OpenPage, title: String, author: String, back: () -> Unit, favorite: Boolean, toggleFavorite: () -> Unit,
    refresh: () -> Unit, progress: ArchiveProgress?, problem: String?, openChapter: (Chapter) -> Unit,
    openLatest: () -> Unit, readPage: () -> Unit, download: (List<Chapter>) -> Unit
) {
    val context = LocalContext.current
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = page.chapters.filter { it.title.contains(filter.trim(), ignoreCase = true) }
    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(title, Modifier.size(width = 89.dp, height = 110.dp), warm = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (author.isNotBlank()) Text(author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (page.digest.isBlank()) "本机目录 · ${page.chapters.size} 个条目" else "${page.chapters.size} 个条目",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page.chapters.isNotEmpty()) Button(onClick = openLatest) { Text("开始或继续阅读") }
                TextButton(onClick = toggleFavorite) { Text(if (favorite) "取消收藏" else "收藏") }
                if (page.file != null) TextButton(onClick = readPage) { Text("阅读本页") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TextButton(onClick = { download(emptyList()) }, enabled = page.details.downloadsAllowed) { Text("下载全部") }
                TextButton(onClick = { selectionMode = !selectionMode }, enabled = page.chapters.isNotEmpty()) {
                    Text(if (selectionMode) "结束选择" else "选择章节")
                }
                TextButton(onClick = { openWebsite(context, page.ref.original) }) { Text("原网页") }
            }
            if (selectionMode) {
                Button(onClick = { download(page.chapters.filter { it.ref.key in selected }) }, enabled = selected.isNotEmpty()) {
                    Text("下载所选（${selected.size}）")
                }
            }
            if (progress != null) ArchiveLoadingPanel(progress)
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
        }
    }
    val search: @Composable () -> Unit = {
        SectionTitle("目录")
        if (page.chapters.size >= 8) OutlinedTextField(
            filter, { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("筛选此目录") }, singleLine = true
        )
    }

    Column(Modifier.fillMaxSize()) {
        Toolbar(title, back) {
            IconAction(Glyph.REFRESH, "刷新目录", enabled = progress == null, action = refresh)
            IconAction(Glyph.LINK, "分享原文") { shareText(context, page.ref.original, "分享文献") }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (wide) {
                    LazyColumn(Modifier.width(335.dp), contentPadding = PaddingValues(20.dp)) { item { details() } }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!wide) item("details") { details() }
                    item("filter") { search() }
                    items(shown, key = { it.ref.key }) { chapter ->
                        if (selectionMode) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = chapter.ref.key in selected, onCheckedChange = { checked ->
                                    selected = if (checked) selected + chapter.ref.key else selected - chapter.ref.key
                                })
                                Box(Modifier.weight(1f)) {
                                    EntryRow(chapter.title) { openChapter(chapter) }
                                }
                            }
                        } else EntryRow(chapter.title) { openChapter(chapter) }
                    }
                    if (shown.isEmpty()) item {
                        HorizontalDivider()
                        Text(if (filter.isBlank()) "站方未提供可识别的子目录" else "目录中没有匹配项")
                    }
                }
            }
        }
    }
}
