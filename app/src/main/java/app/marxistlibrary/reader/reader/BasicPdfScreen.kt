package app.marxistlibrary.reader.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.marxistlibrary.reader.ui.Glyph
import app.marxistlibrary.reader.ui.IconAction
import app.marxistlibrary.reader.ui.LoadingPanel
import app.marxistlibrary.reader.ui.MessagePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

// PdfScreen supplies the current position when switching to the Android PDF renderer.
@Composable
internal fun BasicPdfScreen(
    file: File, start: PdfPosition, onPosition: (PdfPosition) -> Unit, external: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    var retry by remember(file.path) { mutableIntStateOf(0) }
    val publication by produceState<Result<IsolatedPdfBook>?>(null, file, retry) {
        val book = IsolatedPdfBook(context)
        try {
            value = null
            value = runCatching { book.apply { open(file) } }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { book.close() }
        }
    }
    var page by rememberSaveable(file.path) { mutableIntStateOf(start.page) }
    var offset by rememberSaveable(file.path) { mutableIntStateOf(start.offset) }
    var scale by rememberSaveable { mutableStateOf(1f) }
    var jumping by rememberSaveable { mutableStateOf(false) }
    val book = publication?.getOrNull()
    val shownPage = book?.let { page.coerceIn(1, it.count) } ?: page

    fun go(number: Int) {
        if (number == shownPage) return
        page = number
        offset = 0
        onPosition(PdfPosition(number))
    }
    val bitmap by key(book, shownPage) {
        produceState<Result<Bitmap>?>(null) {
            if (book != null) value = withContext(Dispatchers.IO) { runCatching { book.render(shownPage - 1) } }
        }
    }
    LaunchedEffect(book, shownPage, bitmap) {
        if (bitmap?.isSuccess == true) {
            onPosition(PdfPosition(shownPage, if (shownPage == page) offset else 0))
        }
    }
    Column(Modifier.fillMaxSize()) {
        val rendered = bitmap?.getOrNull()
        BoxWithConstraints(Modifier.weight(1f)) {
            when {
                publication == null -> LoadingPanel("正在打开 PDF")
                bitmap == null && book != null -> LoadingPanel("正在渲染第 $shownPage 页")
                rendered == null -> MessagePanel("暂时无法显示 PDF", "可重试或用系统阅读应用打开原件",
                    "重试", { retry++ }, "打开附件", external)
                else -> {
                    val width = maxWidth * scale
                    val height = width * (rendered.height.toFloat() / rendered.width)
                    val image = remember(rendered) { rendered.asImageBitmap() }
                    key(shownPage) {
                        val initialScroll = with(LocalDensity.current) { (height * (offset / 10_000f)).roundToPx() }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(initialScroll))
                            .horizontalScroll(rememberScrollState())) {
                            Image(image, "第 $shownPage 页", Modifier.requiredSize(width, height))
                        }
                    }
                }
            }
        }
        if (book != null) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                IconAction(Glyph.BACK, "上一页", shownPage > 1) { go(shownPage - 1) }
                TextButton(onClick = { jumping = true }) { Text("$shownPage / ${book.count} 页") }
                IconAction(Glyph.NEXT, "下一页", shownPage < book.count) { go(shownPage + 1) }
                TextButton(onClick = { scale = (scale - 0.25f).coerceAtLeast(1f) }, enabled = scale > 1f) { Text("缩小") }
                TextButton(onClick = { scale = (scale + 0.25f).coerceAtMost(2.5f) }, enabled = scale < 2.5f) { Text("放大") }
            }
        }
    }
    if (jumping && book != null) {
        var number by remember { mutableStateOf(shownPage.toString()) }
        val requested = number.toIntOrNull()?.takeIf { it in 1..book.count }
        AlertDialog(
            onDismissRequest = { jumping = false }, title = { Text("跳到指定页") },
            text = { OutlinedTextField(number, { number = it.filter(Char::isDigit).take(8) }, label = { Text("页码（1–${book.count}）") }) },
            confirmButton = { TextButton(onClick = { requested?.let(::go); jumping = false },
                enabled = requested != null) { Text("跳转") } },
            dismissButton = { TextButton(onClick = { jumping = false }) { Text("取消") } }
        )
    }
}
