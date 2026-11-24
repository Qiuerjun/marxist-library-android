package app.marxistlibrary.reader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.LibraryModel
import app.marxistlibrary.reader.library.LibraryBackup
import app.marxistlibrary.reader.settings.Appearance
import app.marxistlibrary.reader.settings.CoverStyle
import app.marxistlibrary.reader.settings.ReadingSettings
import app.marxistlibrary.reader.settings.ReadingWidth
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.ProbeResult
import app.marxistlibrary.reader.sources.ProbeStatus
import app.marxistlibrary.reader.sources.Source
import app.marxistlibrary.reader.sources.probeStatus
import app.marxistlibrary.reader.sources.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(model: LibraryModel, back: () -> Unit, open: (DocumentRoute) -> Unit) {
    val context = LocalContext.current
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val sources by model.mirrors.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val checks by model.mirrors.checks.collectAsStateWithLifecycle()
    val network by model.mirrors.networkIds.collectAsStateWithLifecycle(initialValue = model.mirrors.networkId())
    val checking by model.mirrors.checking.collectAsStateWithLifecycle(initialValue = emptySet())
    var scanning by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var opening by rememberSaveable { mutableStateOf(false) }
    var aboutDocument by rememberSaveable { mutableStateOf<AboutDocument?>(null) }
    var showAppUpdate by rememberSaveable { mutableStateOf(false) }
    var size by remember { mutableStateOf<Long?>(null) }
    var backup by remember { mutableStateOf<LibraryBackup?>(null) }
    var backupWork by remember { mutableStateOf<String?>(null) }
    var cleaning by remember { mutableStateOf(false) }

    LaunchedEffect(model) { size = model.archive.cacheSize() }

    fun write(uri: Uri, contents: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(contents.toByteArray(Charsets.UTF_8)) }
            ?: throw java.io.IOException("无法写入所选文件")
    }

    fun workOnBackup(label: String, action: suspend () -> Unit) {
        if (backupWork != null) return
        backupWork = label
        model.action {
            try { action() } finally { backupWork = null }
        }
    }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) workOnBackup("正在导出书架备份") {
            val contents = model.library.export()
            withContext(Dispatchers.IO) { write(uri, contents) }
            model.notice("已导出备份")
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) workOnBackup("正在检查备份文件") {
            val data = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { readBounded(it, 20 * 1024 * 1024) }
                    ?: throw java.io.IOException("无法读取所选文件")
            }
            backup = model.library.extract(data.toString(Charsets.UTF_8))
        }
    }
    val exportNotes = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) workOnBackup("正在导出阅读笔记") {
            val notes = model.library.exportNotes()
            withContext(Dispatchers.IO) { write(uri, notes) }
            model.notice("已导出阅读笔记")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Toolbar("设置", back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 30.dp)) {
            item("theme") {
                SectionTitle("外观与排版")
                AppearanceOptions(prefs.appearance) { value -> model.action { model.settings.appearance(value) } }
                Text("封面样式", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverStyle.entries.forEach { style ->
                        FilterChip(selected = prefs.coverStyle == style,
                            onClick = { model.action { model.settings.coverStyle(style) } },
                            label = { Text(style.label) })
                    }
                }
                TypographyOptions(model, prefs)
            }
            item("mirror") {
                SectionTitle("内容来源", if (scanning) "检测中" else "检测全部") {
                    if (!scanning) {
                        scanning = true
                        model.action {
                            try { model.mirrors.inspectAll() } finally { scanning = false }
                        }
                    }
                }
                PreferenceRow("自动选源", "优先主站，超时或缺页时尝试其他来源") {
                    RadioButton(prefs.fixedSource.isEmpty(), { model.action { model.settings.fixedSource("") } })
                }
                Text("响应时间取中文首页。达到 2.5 秒时标为较慢，样本文献另行检测。",
                    Modifier.padding(top = 10.dp, bottom = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            items(sources, key = { it.id }) { source ->
                val observed = checks[source.id]?.takeIf { it.network == network }
                val stale = observed?.takeIf { it.problem == null }?.latestYear?.let { year ->
                    checks[MainSource.id]?.takeIf { it.network == network && it.problem == null }?.latestYear?.let { year < it }
                } == true
                val status = probeStatus(observed, (source.id to network) in checking, network != 0L)
                MirrorOption(source, observed, status, stale, prefs.fixedSource == source.id,
                    choose = { model.action { model.settings.fixedSource(source.id) } },
                    probe = { model.action { model.mirrors.probe(source) } },
                    remove = { model.action { model.mirrors.remove(source.id) } }
                )
            }
            item("extra-sources") {
                TextButton(onClick = { adding = true }) { Text("添加自定义 HTTPS 镜像") }
                TextButton(onClick = { opening = true }) { Text("粘贴文库链接") }
            }
            item("network") {
                SectionTitle("网络与存储")
                PreferenceRow("仅 Wi-Fi 下载", "限制后台下载使用的网络") {
                    Switch(prefs.wifiOnly, { value -> model.action { model.settings.wifiOnly(value) } })
                }
                PreferenceRow("定时检查文库更新", "联网时每天尝试刷新站方 RSS") {
                    Switch(prefs.autoRefresh, { value -> model.action { model.app.setPeriodicRefresh(value) } })
                }
                PreferenceRow("已占用空间", size?.let { android.text.format.Formatter.formatShortFileSize(context, it) }.orEmpty()) {
                    TextButton(enabled = size != null && !cleaning, onClick = {
                        cleaning = true
                        model.action {
                            try {
                                val freed = model.archive.trimCache(clear = true)
                                size = model.archive.cacheSize()
                                model.notice("已清理 ${android.text.format.Formatter.formatShortFileSize(context, freed)} 缓存")
                            } finally { cleaning = false }
                        }
                    }) { Text("清理缓存") }
                }
                if (cleaning) LoadingPanel("正在清理缓存")
                else if (size == null) LoadingPanel("正在统计存储占用")
            }
            item("backup") {
                SectionTitle("备份与导出")
                backupWork?.let { LoadingPanel(it) }
                EntryRow("导出书架备份", "收藏、书签、笔记和阅读进度", Glyph.DOWNLOAD) {
                    if (backupWork == null) exportBackup.launch("文库书架备份.json")
                }
                EntryRow("恢复书架备份", "选择备份文件后预览可导入的项目", Glyph.SHELF) {
                    if (backupWork == null) importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                EntryRow("导出阅读笔记", "将选中段落、笔记和原文链接保存为 Markdown", Glyph.NOTES) {
                    if (backupWork == null) exportNotes.launch("文库阅读笔记.md")
                }
            }
            item("about") {
                AboutSection(
                    openDocument = { aboutDocument = it },
                    checkUpdates = { showAppUpdate = true }
                )
            }
        }
    }

    aboutDocument?.let { document ->
        AboutDocumentDialog(document, onDismiss = { aboutDocument = null })
    }
    if (showAppUpdate) AppUpdateDialog(model.appUpdates, onDismiss = { showAppUpdate = false })
    if (adding) SourceDialog(dismiss = { adding = false }) { title, url ->
        adding = false
        model.action {
            val new = model.mirrors.add(title, url)
            model.notice("已添加 ${new.title}")
        }
    }
    if (opening) {
        var input by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { opening = false }, title = { Text("打开文库链接") },
            text = { OutlinedTextField(input, { input = it.take(3_000) }, label = { Text("链接地址") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                model.action {
                    val ref = model.mirrors.fromSharedText(input)
                    if (ref == null) model.notice("无法识别文库链接")
                    else {
                        opening = false
                        open(DocumentRoute(ref.path, ref.anchor))
                    }
                }
            }, enabled = input.isNotBlank()) { Text("打开") } },
            dismissButton = { TextButton(onClick = { opening = false }) { Text("取消") } }
        )
    }
    backup?.let { incoming ->
        AlertDialog(
            onDismissRequest = { backup = null }, title = { Text("恢复书架") },
            text = { Text("备份含 ${incoming.favorites.size} 条收藏、${incoming.bookmarks.size} 条书签和 ${incoming.positions.size} 条阅读进度。同一条记录采用较新的内容。") },
            confirmButton = { TextButton(onClick = {
                backup = null
                workOnBackup("正在恢复阅读记录") { model.library.merge(incoming); model.notice("备份恢复完成") }
            }) { Text("合并记录") } },
            dismissButton = { TextButton(onClick = { backup = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SourceDialog(dismiss: () -> Unit, submit: (String, String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("添加镜像") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(url, { url = it.take(600) }, label = { Text("HTTPS 基地址") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { submit(title, url) }, enabled = url.isNotBlank()) { Text("检测并添加") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun MirrorOption(
    source: Source, result: ProbeResult?, status: ProbeStatus, stale: Boolean, selected: Boolean,
    choose: () -> Unit, probe: () -> Unit, remove: () -> Unit
) {
    val latency = result?.homeMillis?.let { " · $it ms" }.orEmpty()
    val homeLatency = result?.homeMillis?.let { " · 首页 $it ms" }.orEmpty()
    val label = when (status) {
        ProbeStatus.UNTESTED -> "待检测"
        ProbeStatus.CHECKING -> "检测中"
        ProbeStatus.AVAILABLE -> if (stale) "可能较旧$latency" else "可用$latency"
        ProbeStatus.SLOW -> "响应较慢$latency"
        ProbeStatus.TIMEOUT -> "首页连接超时"
        ProbeStatus.OFFLINE -> "设备未联网"
        ProbeStatus.SAMPLE_MISSING -> "缺少样本文献$homeLatency"
        ProbeStatus.SAMPLE_TIMEOUT -> "样本检测超时$homeLatency"
        ProbeStatus.WRONG_CONTENT -> if (result?.problem != null) "首页内容异常" else "样本文献内容异常$homeLatency"
        ProbeStatus.INCOMPLETE -> when {
            result?.sampleProblem != null -> "样本未确认 · ${result.sampleProblem.label}$homeLatency"
            result?.problem == ConnectionIssue.MISSING -> "缺少中文首页"
            else -> result?.problem?.label ?: "检测未完成"
        }
        ProbeStatus.FAILED -> result?.problem?.label ?: "检测失败"
    }
    val checked = if (status == ProbeStatus.CHECKING || result == null) "" else
        "检测于 " + friendlyDate(Instant.ofEpochMilli(result.checkedAt).toString())
    val detail = if (status == ProbeStatus.SLOW && stale) "$checked · 内容可能较旧" else checked
    PreferenceRow(source.title, detail, detail = { MirrorStatus(status, label, stale) }) {
        IconAction(Glyph.REFRESH, "检测 ${source.title}", status != ProbeStatus.CHECKING && status != ProbeStatus.OFFLINE, probe)
        if (source.custom) IconAction(Glyph.CLOSE, "移除 ${source.title}", status != ProbeStatus.CHECKING, remove)
        RadioButton(selected, choose)
    }
}

@Composable
private fun MirrorStatus(status: ProbeStatus, label: String, stale: Boolean) {
    val glyph = when (status) {
        ProbeStatus.AVAILABLE -> if (stale) Glyph.WARNING else Glyph.CHECK
        ProbeStatus.SLOW, ProbeStatus.SAMPLE_TIMEOUT -> Glyph.CLOCK
        ProbeStatus.TIMEOUT, ProbeStatus.FAILED -> Glyph.CLOSE
        ProbeStatus.SAMPLE_MISSING -> Glyph.FIND
        ProbeStatus.OFFLINE -> Glyph.OFFLINE
        ProbeStatus.WRONG_CONTENT, ProbeStatus.INCOMPLETE -> Glyph.WARNING
        ProbeStatus.UNTESTED, ProbeStatus.CHECKING -> Glyph.REFRESH
    }
    val dark = LocalReaderColors.current.dark
    val colors = when {
        status in setOf(ProbeStatus.TIMEOUT, ProbeStatus.FAILED, ProbeStatus.WRONG_CONTENT) ->
            if (dark) Color(0xFFFFB5AC) to Color(0xFF4A2927) else Color(0xFF923831) to Color(0xFFFAE2DF)
        status in setOf(ProbeStatus.SLOW, ProbeStatus.INCOMPLETE, ProbeStatus.SAMPLE_MISSING, ProbeStatus.SAMPLE_TIMEOUT) || stale ->
            if (dark) Color(0xFFF0C78C) to Color(0xFF443525) else Color(0xFF81521F) to Color(0xFFF9E9CC)
        status == ProbeStatus.AVAILABLE ->
            if (dark) Color(0xFFA7DDB8) to Color(0xFF263B30) else Color(0xFF286142) to Color(0xFFDFEFE2)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(Modifier.padding(vertical = 4.dp), shape = MaterialTheme.shapes.small, color = colors.second, contentColor = colors.first) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (status == ProbeStatus.CHECKING) {
                CircularProgressIndicator(Modifier.size(14.dp), color = colors.first, strokeWidth = 2.dp)
            } else GlyphIcon(glyph, size = 15.dp, color = colors.first)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PreferenceRow(
    title: String, description: String, detail: @Composable (() -> Unit)? = null, actions: @Composable () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.invoke()
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
    HorizontalDivider()
}

@Composable
fun AppearanceOptions(selected: Appearance, onSelect: (Appearance) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Appearance.entries.forEach { option -> FilterChip(selected == option, { onSelect(option) }, { Text(option.label) }) }
    }
}

@Composable
fun TypographyOptions(model: LibraryModel, preferences: ReadingSettings) {
    SliderSetting("字号", preferences.fontSize, 12..34, "sp") { model.action { model.settings.fontSize(it) } }
    SliderSetting("行距", preferences.lineHeight, 130..230, "%") { model.action { model.settings.lineHeight(it) } }
    SliderSetting("段距", preferences.paragraphSpacing, 25..140, "%") { model.action { model.settings.paragraphSpacing(it) } }
    SliderSetting("页边距", preferences.margin, 10..42, "dp") { model.action { model.settings.margin(it) } }
    PreferenceRow("首行缩进", "正文段落缩进两个字") {
        Switch(preferences.firstLineIndent, { value -> model.action { model.settings.firstLineIndent(value) } })
    }
    PreferenceRow("宋体优先", "使用设备提供的衬线字体") {
        Switch(preferences.serif, { value -> model.action { model.settings.serif(value) } })
    }
    Text("大屏行宽", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingWidth.entries.forEach { width ->
            FilterChip(preferences.readingWidth == width, { model.action { model.settings.readingWidth(width) } }, { Text(width.label) })
        }
    }
}

@Composable
fun SliderSetting(title: String, current: Int, range: IntRange, unit: String, update: (Int) -> Unit) {
    var draft by remember(current) { mutableStateOf(current.toFloat()) }
    Text("$title  ${draft.roundToInt()} $unit", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = draft, onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(), valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChangeFinished = { update(draft.roundToInt()) }
    )
}
