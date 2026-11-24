package app.marxistlibrary.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marxistlibrary.reader.Bookmark
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.DownloadTask
import app.marxistlibrary.reader.LibraryModel
import kotlinx.coroutines.flow.map
import app.marxistlibrary.reader.sources.ArchiveRef
import java.time.Instant

@Composable
fun ShelfScreen(model: LibraryModel, open: (DocumentRoute) -> Unit, task: (Int) -> Unit, settings: () -> Unit) {
    val favorites by model.library.favorites.collectAsStateWithLifecycle(initialValue = null)
    val recent by model.library.history.collectAsStateWithLifecycle(initialValue = null)
    val marks by model.library.bookmarks.collectAsStateWithLifecycle(initialValue = null)
    val downloads by model.downloads.tasks.collectAsStateWithLifecycle(initialValue = null)
    val transfers by model.downloads.transfers.collectAsStateWithLifecycle()
    val tabs = listOf("收藏", "书签与笔记", "最近阅读", "下载")
    var selected by rememberSaveable { mutableStateOf(tabs.first()) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    Column(Modifier.fillMaxSize()) {
        Toolbar("书架", actions = { IconAction(Glyph.SETTINGS, "设置与备份", action = settings) })
        Row(Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { name -> FilterChip(selected == name, { selected = name }, { Text(name) }) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            when (selected) {
                "收藏" -> {
                    val known = favorites
                    if (known == null) item { LoadingPanel("正在读取收藏") }
                    else if (known.isEmpty()) item { MessagePanel("书架还没有收藏", "在目录或阅读器中点按收藏，将作品保存到这里") }
                    items(known.orEmpty(), key = { it.key }) { item ->
                        ShelfRow(item.title, item.author, Glyph.BOOK,
                            open = { open(DocumentRoute(item.path, item.anchor, title = item.title)) },
                            menu = listOf("取消收藏" to {
                                model.action { model.library.favorite(ArchiveRef(item.path, item.anchor), item.title, item.author, false) }
                            }))
                    }
                }
                "书签与笔记" -> {
                    val known = marks
                    if (known == null) item { LoadingPanel("正在读取书签和笔记") }
                    else if (known.isEmpty()) item { MessagePanel("还没有书签", "阅读时可保存书签，也可选中文字添加高亮和笔记") }
                    else item {
                        AssistChip(onClick = settings, label = { Text("导出笔记与备份") }, leadingIcon = { GlyphIcon(Glyph.LINK, size = 16.dp) })
                    }
                    items(known.orEmpty(), key = { it.id }) { item ->
                        ShelfRow(item.title, item.note.ifBlank { item.selection }.ifBlank { item.quote }, Glyph.MARK,
                            open = { open(DocumentRoute.from(item)) },
                            menu = listOf(
                                "编辑笔记" to { editing = item },
                                "删除" to { model.action { model.library.removeMark(item.id) } }
                            ))
                    }
                }
                "最近阅读" -> {
                    val known = recent
                    if (known == null) item { LoadingPanel("正在读取最近阅读") }
                    else if (known.isEmpty()) item { MessagePanel("还没有阅读记录", "从文库打开一篇作品，阅读位置会自动保存") }
                    items(known.orEmpty(), key = { it.edition }) { item ->
                        ShelfRow(item.title, friendlyDate(Instant.ofEpochMilli(item.updatedAt).toString()), Glyph.BOOK,
                            open = { open(DocumentRoute.from(item)) },
                            menu = listOf("移除最近阅读" to { model.action { model.library.removePosition(item.edition) } }))
                    }
                }
                else -> {
                    val known = downloads
                    if (known == null) item { LoadingPanel("正在读取离线下载") }
                    else if (known.isEmpty()) item { MessagePanel("还没有下载", "下载文献后，可在离线时从这里打开") }
                    items(known.orEmpty(), key = { it.id }) { item ->
                        val current = transfers[item.id]?.takeIf { item.state == "RUNNING" && it.generation == item.generation }
                        val percent = current?.archive?.fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
                        val detail = current?.let { it.archive.stage.label + percent } ?: downloadLabel(item)
                        EntryRow(item.title, detail, Glyph.DOWNLOAD) { task(item.id) }
                    }
                }
            }
        }
    }
    editing?.let { bookmark ->
        var draft by remember(bookmark.id) { mutableStateOf(bookmark.note) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("编辑笔记") },
            text = { OutlinedTextField(draft, { draft = it.take(10_000) }, label = { Text("笔记") }, minLines = 3, maxLines = 6) },
            confirmButton = { TextButton(onClick = {
                model.action { model.library.editMark(bookmark, draft) }
                editing = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ShelfRow(title: String, detail: String, icon: Glyph, open: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { EntryRow(title, detail, icon, open) }
        Column {
            IconAction(Glyph.MORE, "更多操作") { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) }
            }
        }
    }
}

@Composable
fun DownloadScreen(model: LibraryModel, id: Int, back: () -> Unit, open: (DocumentRoute) -> Unit) {
    val lookup by remember(model, id) { model.downloads.task(id).map { it to true } }
        .collectAsStateWithLifecycle(initialValue = null to false)
    val items by remember(model, id) { model.downloads.items(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val transfers by model.downloads.transfers.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Toolbar("离线下载", back)
        val (entry, checked) = lookup
        if (entry == null) {
            if (checked) MessagePanel("暂无此下载任务")
            else LoadingPanel("正在读取下载任务")
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(entry.title, style = MaterialTheme.typography.headlineMedium)
                Text(downloadLabel(entry), Modifier.padding(top = 8.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.total > 0) LinearProgressIndicator(
                    progress = { (entry.count.toFloat() / entry.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                val active = transfers[id]?.takeIf { entry.state == "RUNNING" && it.generation == entry.generation }
                if (active != null) ArchiveLoadingPanel(active.archive, item = active.title)
                else if (entry.state == "RUNNING") LoadingPanel("正在准备文献")
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (entry.state) {
                        "RUNNING", "QUEUED", "WAITING" -> {
                            Button(onClick = { model.action { model.downloads.pause(id) } }) { Text("暂停") }
                            TextButton(onClick = { model.action { model.downloads.pause(id, cancel = true) } }) { Text("取消任务") }
                        }
                        "COMPLETED" -> Button(onClick = { open(DocumentRoute(entry.root, title = entry.title)) }) { Text("离线阅读") }
                        else -> Button(onClick = { model.action { model.downloads.resume(id) } }) { Text("继续或重试") }
                    }
                    TextButton(onClick = {
                        model.action { model.downloads.remove(id); model.notice("已移除下载") }
                        back()
                    }) { Text("移除下载") }
                }
            }
            items(items, key = { it.path }) { item ->
                val ready = item.digest.isNotBlank()
                if (ready) {
                    EntryRow(item.title, "已离线保存", Glyph.CHECK) { open(DocumentRoute(item.path, edition = entry.root, title = item.title)) }
                } else {
                    Text(item.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(item.error.ifBlank { "等待下载" }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun downloadLabel(task: DownloadTask): String {
    val state = when (task.state) {
        "QUEUED" -> "排队中"
        "WAITING" -> "等待继续"
        "RUNNING" -> "下载中"
        "PAUSED" -> "已暂停"
        "CANCELLED" -> "已取消"
        "PARTIAL" -> "部分完成"
        "COMPLETED" -> "已完成"
        else -> "下载失败"
    }
    return listOf(state, "${task.count} / ${task.total} 个页面", task.error).filter(String::isNotBlank).joinToString(" · ")
}
