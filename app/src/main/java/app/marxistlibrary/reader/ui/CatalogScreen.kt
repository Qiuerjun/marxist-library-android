package app.marxistlibrary.reader.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marxistlibrary.reader.CatalogEntry
import app.marxistlibrary.reader.catalog.ArchiveProgress
import app.marxistlibrary.reader.catalog.ArchiveStage
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.LibraryModel
import app.marxistlibrary.reader.ReadingPosition
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.settings.CoverStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CatalogScreen(model: LibraryModel, open: (DocumentRoute) -> Unit, search: () -> Unit, settings: () -> Unit) {
    val entries by model.catalog.collectAsStateWithLifecycle(initialValue = emptyList())
    val updates by model.updates.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by model.library.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val sources by model.mirrors.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val startup = remember(model) { model.archive.seedEntries() }
    val catalog = entries.ifEmpty { startup }
    val groups = listOf("作者", "全集", "专题", "词典")
    var group by rememberSaveable { mutableStateOf(groups.first()) }
    val synchronization = remember { MutableStateFlow<ArchiveProgress?>(null) }
    val progress by synchronization.collectAsStateWithLifecycle()
    val title = sources.find { it.id == preferences.fixedSource }?.title?.substringBefore(" · ") ?: "自动选源"
    val recent = history.firstOrNull()
    val recommendation = catalog.firstOrNull { it.category == "推荐" }
    val shown = catalog.filter { it.category == group }
    ReportDrawnWhen { catalog.isNotEmpty() }

    fun refresh() {
        if (synchronization.value != null) return
        synchronization.value = ArchiveProgress(ArchiveStage.CACHE)
        model.action {
            try {
                val total = model.archive.refreshCatalog { synchronization.value = it }
                model.notice("目录已同步，共 $total 个入口")
                synchronization.value = ArchiveProgress(ArchiveStage.UPDATES)
                runCatching { model.archive.refreshUpdates() }
            } finally {
                synchronization.value = null
            }
        }
    }

    fun refreshUpdates() {
        if (synchronization.value != null) return
        synchronization.value = ArchiveProgress(ArchiveStage.UPDATES)
        model.action {
            try { model.notice("更新列表已同步，共 ${model.archive.refreshUpdates()} 条") }
            finally { synchronization.value = null }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Toolbar("中文马克思主义文库", actions = { IconAction(Glyph.SETTINGS, "设置", action = settings) })
        BoxWithConstraints(Modifier.weight(1f)) {
            val columns = if (maxWidth >= 840.dp) 4 else 2
            val inset = if (maxWidth >= 840.dp) 36.dp else 20.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = inset, end = inset, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item("browse") {
                    Surface(
                        onClick = search, modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp),
                        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.FIND, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("搜索作者、作品与离线正文", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (recent != null || recommendation != null) {
                    item("featured") {
                        Featured(model, recent, recommendation, preferences.coverStyle) {
                            if (recent != null) open(DocumentRoute.from(recent)) else recommendation?.let { open(it.route()) }
                        }
                    }
                }
                item("categories") {
                    SectionTitle("浏览文库", if (progress == null) "同步目录" else "同步中", ::refresh)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        groups.forEach { label -> FilterChip(selected = group == label, onClick = { group = label }, label = { Text(label) }) }
                        Spacer(Modifier.width(4.dp))
                        AssistChip(onClick = settings, label = { Text(title) })
                    }
                    progress?.takeUnless { it.stage == ArchiveStage.UPDATES }?.let { ArchiveLoadingPanel(it) }
                }
                items(shown.chunked(columns)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { entry ->
                            Surface(
                                onClick = { open(entry.route()) },
                                modifier = Modifier.weight(1f).heightIn(min = 70.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(entry.title.take(1), Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                    Text(entry.title, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item("updates") {
                    SectionTitle("最近更新", if (progress?.stage == ArchiveStage.UPDATES) "同步中" else "刷新", ::refreshUpdates)
                    if (progress?.stage == ArchiveStage.UPDATES) LoadingPanel("正在同步文库更新")
                    if (preferences.lastUpdate.isNotBlank()) Text(
                        "上次同步 ${friendlyDate(preferences.lastUpdate)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                    )
                    if (updates.isEmpty()) Text("刷新后查看站方最新收录的文献", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(updates, key = { "update-" + it.key }) { item ->
                    EntryRow(item.title, if (item.publishedAt > 0) friendlyDate(Instant.ofEpochMilli(item.publishedAt).toString()) else "") {
                        open(DocumentRoute(item.path, item.anchor, title = item.title))
                    }
                }
            }
        }
    }
}

@Composable
private fun Featured(
    model: LibraryModel, position: ReadingPosition?, recommendation: CatalogEntry?, coverStyle: CoverStyle, onOpen: () -> Unit
) {
    val title = position?.title ?: recommendation?.title.orEmpty()
    val cover = if (coverStyle == CoverStyle.SOURCE_IMAGE) {
        sourceCover(model.archive, position?.path ?: recommendation?.path.orEmpty())
    } else null
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            BookCover(title, Modifier.size(91.dp, 113.dp), cover = cover)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (position != null) "继续阅读" else "经典文献", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                val descriptor = if (position != null) position.quote.take(50) else recommendation?.author.orEmpty()
                if (descriptor.isNotBlank()) Text(descriptor, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            GlyphIcon(Glyph.NEXT, size = 20.dp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SearchScreen(model: LibraryModel, open: (DocumentRoute) -> Unit, settings: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<CatalogEntry>()) }
    var paragraphs by remember { mutableStateOf(emptyList<app.marxistlibrary.reader.ParagraphMatch>()) }
    var working by remember { mutableStateOf(false) }
    val finished by model.downloads.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(query, body, finished.map { it.count }) {
        val text = query.trim()
        if (text.isBlank()) {
            entries = emptyList()
            paragraphs = emptyList()
            working = false
        } else {
            working = true
            delay(160)
            if (body) paragraphs = model.archive.findParagraphs(text)
            else entries = model.archive.findCatalog(text)
            working = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Toolbar("搜索", actions = { IconAction(Glyph.SETTINGS, "设置", action = settings) })
        OutlinedTextField(query, { query = it.take(180) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text("作者、作品或关键字") }, leadingIcon = { GlyphIcon(Glyph.FIND) },
            trailingIcon = { if (query.isNotBlank()) IconAction(Glyph.CLOSE, "清空搜索") { query = "" } },
            singleLine = true, shape = RoundedCornerShape(15.dp))
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !body, onClick = { body = false }, label = { Text("书目") })
            FilterChip(selected = body, onClick = { body = true }, label = { Text("离线正文") })
            AssistChip(onClick = { openWebsite(context, "https://www.marxists.org/chinese/search/index.html") },
                label = { Text("站内在线搜索") }, trailingIcon = { GlyphIcon(Glyph.LINK, size = 16.dp) })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            when {
                query.isBlank() -> item {
                    MessagePanel("搜索中文文献", if (body) "搜索已下载正文，输入至少两个汉字，也可使用篇内查找" else "支持作品名、作者和简繁通搜")
                }
                working -> item { LoadingPanel(if (body) "正在搜索离线正文" else "正在搜索文库目录") }
                body && paragraphs.isEmpty() -> item { MessagePanel("未找到正文结果", "正文索引覆盖已下载的 HTML 文献。可尝试更短的词或切换到书目") }
                !body && entries.isEmpty() -> item { MessagePanel("未找到书目", "可返回文库同步更多目录，或打开站内在线搜索") }
                body -> items(paragraphs, key = { it.id }) { paragraph ->
                    val title = paragraph.title?.takeIf(String::isNotBlank) ?: paragraph.path.substringAfterLast('/')
                    EntryRow(title, paragraph.text.take(150), Glyph.FIND) {
                        open(DocumentRoute(paragraph.path, paragraph.anchor, title = title,
                            quote = paragraph.text.take(75), digest = paragraph.digest))
                    }
                }
                else -> items(entries, key = { it.key }) { entry ->
                    EntryRow(entry.title, listOf(entry.author, entry.category).filter(String::isNotBlank).joinToString(" · ")) {
                        open(entry.route())
                    }
                }
            }
        }
    }
}

internal fun CatalogEntry.route() = DocumentRoute(path, anchor, title = title)

internal fun friendlyDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
