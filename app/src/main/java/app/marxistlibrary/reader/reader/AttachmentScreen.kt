package app.marxistlibrary.reader.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.marxistlibrary.reader.DocumentRoute
import app.marxistlibrary.reader.LibraryModel
import app.marxistlibrary.reader.catalog.Chapter
import app.marxistlibrary.reader.catalog.OpenPage
import app.marxistlibrary.reader.catalog.PageFormat
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.sources.ArchiveRef
import app.marxistlibrary.reader.ui.LoadingPanel
import app.marxistlibrary.reader.ui.MessagePanel
import app.marxistlibrary.reader.ui.Toolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AttachmentScreen(
    model: LibraryModel, page: OpenPage, route: DocumentRoute, back: () -> Unit, download: () -> Unit,
    favorite: () -> Unit, saved: Boolean, openOriginal: () -> Unit
) {
    val file = page.file
    if (file == null) {
        Column {
            Toolbar(page.title, back)
            MessagePanel("找不到附件", action = "打开原网页", onAction = openOriginal)
        }
        return
    }
    val context = LocalContext.current
    fun external() {
        if (!openAttachment(context, file, page.format)) model.notice("找不到可以打开此格式的应用")
    }
    if (page.format == PageFormat.PDF) PdfScreen(model, page, route, file, back, download, favorite, saved, ::external, openOriginal)
    else EpubScreen(model, page, route, file, back, download, favorite, saved, ::external, openOriginal)
}

@Composable
private fun EpubScreen(
    model: LibraryModel, page: OpenPage, route: DocumentRoute, file: File, back: () -> Unit, download: () -> Unit,
    favorite: () -> Unit, saved: Boolean, external: () -> Unit, openOriginal: () -> Unit
) {
    val context = LocalContext.current
    val publication by produceState<Result<EpubBook>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { EpubBook.open(file) } }
    }
    val book = publication?.getOrNull()
    var index by rememberSaveable(file.path) { mutableStateOf(-1) }
    var wanted by rememberSaveable(file.path) { mutableStateOf("") }
    var quote by rememberSaveable(file.path) { mutableStateOf("") }
    var offset by rememberSaveable(file.path) { mutableStateOf(0) }
    LaunchedEffect(book, route) {
        if (book != null && index < 0) {
            val recent = model.library.mostRecent(route.edition)?.takeIf { it.path == route.path }
            wanted = route.anchor.ifBlank { recent?.anchor.orEmpty() }
            quote = route.quote.ifBlank { recent?.quote.orEmpty() }
            offset = if (route.anchor.isNotBlank()) route.offset else recent?.offset ?: 0
            index = pageIndex(wanted.substringBeforeLast(':'), "epub:", book.chapters.size)
        }
    }
    val section by produceState<Result<EpubPage>?>(null, book, index) {
        value = null
        if (book != null && index >= 0) value = withContext(Dispatchers.IO) { runCatching { book.page(index) } }
    }
    val content = section?.getOrNull()
    if (book == null || content == null || index < 0) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(route.title.ifBlank { page.title }, back)
            if (publication?.isFailure == true || section?.isFailure == true) {
                MessagePanel("暂时无法显示 EPUB", "可用系统阅读应用打开原件", "打开附件", external, "打开原网页", openOriginal)
            } else LoadingPanel("正在准备 EPUB")
        }
        return
    }
    val chapters = remember(book) {
        book.chapters.mapIndexed { chapterNumber, chapter -> Chapter(ArchiveRef(page.ref.path, "epub:$chapterNumber:"), chapter.title) }
    }
    val branch = "epub:$index:"
    fun change(target: Int, anchor: String = "") {
        if (target in book.chapters.indices) {
            quote = ""
            offset = 0
            wanted = "epub:$target:$anchor"
            index = target
        }
    }
    key(index, wanted) {
        TextReader(
            model, page, route.copy(anchor = wanted, quote = quote, offset = offset,
                title = "${page.title} · ${book.chapters[index].title}"),
            content.html, content.headings, branch, asset = { book.image(content, it) }, siblings = chapters,
            previous = if (index > 0) ({ change(index - 1) }) else null,
            next = if (index < book.chapters.lastIndex) ({ change(index + 1) }) else null,
            openChapter = { chapter -> change(pageIndex(chapter.ref.anchor.substringBeforeLast(':'), "epub:", chapters.size)) },
            onLink = { uri ->
                if (uri.host == ReaderWeb.Host && uri.pathSegments.firstOrNull() == "epub") {
                    uri.lastPathSegment?.toIntOrNull()?.let { change(it, uri.fragment.orEmpty()) }
                } else if (uri.host != "epub.invalid") openWebsite(context, uri.toString())
            },
            back = back, download = download, favorite = favorite, saved = saved, openOriginal = openOriginal
        )
    }
}

private fun pageIndex(anchor: String, prefix: String, size: Int): Int =
    (anchor.removePrefix(prefix).toIntOrNull() ?: 0).coerceIn(0, (size - 1).coerceAtLeast(0))

private fun openAttachment(context: Context, file: File, format: PageFormat): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type = if (format == PageFormat.PDF) "application/pdf" else "application/epub+zip"
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
