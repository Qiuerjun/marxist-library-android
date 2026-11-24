package app.marxistlibrary.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import app.marxistlibrary.reader.catalog.ArchiveProgress

enum class Glyph {
    BOOK, SHELF, FIND, SETTINGS, BACK, NEXT, DOWNLOAD, MARK, MORE, TOC, TYPE, NOTES, REFRESH, LINK, SPEAK, CLOSE,
    CHECK, CLOCK, WARNING, OFFLINE
}

@Composable
fun GlyphIcon(
    glyph: Glyph, label: String? = null, size: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val semantics = if (label == null) Modifier else Modifier.semantics { contentDescription = label }
    Canvas(Modifier.size(size).then(semantics)) {
        val unit = this.size.width / 24
        val stroke = 1.65f * unit
        fun line(x: Int, y: Int, a: Int, b: Int) =
            drawLine(color, Offset(unit * x, unit * y), Offset(unit * a, unit * b), stroke, StrokeCap.Round)
        fun outline(vararg points: Pair<Int, Int>) {
            val path = Path().apply {
                moveTo(points.first().first * unit, points.first().second * unit)
                points.drop(1).forEach { lineTo(it.first * unit, it.second * unit) }
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun dot(x: Int, y: Int) = drawCircle(color, 1.15f * unit, Offset(unit * x, unit * y))
        when (glyph) {
            Glyph.BOOK -> {
                outline(12 to 6, 7 to 3, 3 to 4, 3 to 18, 7 to 17, 12 to 20, 17 to 17, 21 to 18, 21 to 4, 17 to 3, 12 to 6, 12 to 20)
                line(6, 8, 9, 9); line(15, 9, 18, 8)
            }
            Glyph.SHELF -> {
                outline(3 to 18, 3 to 4, 8 to 4, 8 to 18, 8 to 7, 13 to 7, 13 to 18)
                outline(16 to 18, 14 to 4, 18 to 3, 22 to 17); line(2, 20, 22, 20)
            }
            Glyph.FIND -> {
                drawCircle(color, 6.5f * unit, Offset(10 * unit, 10 * unit), style = Stroke(stroke))
                line(15, 15, 21, 21)
            }
            Glyph.SETTINGS -> {
                line(3, 6, 21, 6); line(3, 12, 21, 12); line(3, 18, 21, 18)
                line(8, 3, 8, 9); line(16, 9, 16, 15); line(10, 15, 10, 21)
            }
            Glyph.BACK -> outline(14 to 5, 7 to 12, 14 to 19)
            Glyph.NEXT -> outline(9 to 5, 16 to 12, 9 to 19)
            Glyph.DOWNLOAD -> {
                line(12, 3, 12, 15); outline(7 to 11, 12 to 16, 17 to 11)
                outline(4 to 16, 4 to 21, 20 to 21, 20 to 16)
            }
            Glyph.MARK -> outline(6 to 21, 6 to 3, 18 to 3, 18 to 21, 12 to 16, 6 to 21)
            Glyph.MORE -> { dot(5, 12); dot(12, 12); dot(19, 12) }
            Glyph.TOC -> {
                dot(4, 6); dot(4, 12); dot(4, 18)
                line(8, 6, 21, 6); line(8, 12, 21, 12); line(8, 18, 21, 18)
            }
            Glyph.TYPE -> { outline(3 to 19, 10 to 5, 17 to 19); line(6, 14, 14, 14); line(19, 12, 21, 19) }
            Glyph.NOTES -> {
                outline(18 to 13, 18 to 3, 4 to 3, 4 to 21, 16 to 21)
                outline(10 to 17, 20 to 7, 22 to 9, 12 to 19, 9 to 20, 10 to 17)
            }
            Glyph.REFRESH -> {
                drawArc(color, 10f, 280f, false, Offset(4 * unit, 4 * unit), Size(16 * unit, 16 * unit), style = Stroke(stroke, cap = StrokeCap.Round))
                outline(15 to 7, 21 to 7, 21 to 2)
            }
            Glyph.LINK -> {
                outline(12 to 4, 4 to 4, 4 to 20, 20 to 20, 20 to 12)
                outline(15 to 3, 21 to 3, 21 to 9); line(12, 12, 21, 3)
            }
            Glyph.SPEAK -> { outline(3 to 9, 7 to 9, 12 to 5, 12 to 19, 7 to 15, 3 to 15, 3 to 9); outline(16 to 8, 19 to 12, 16 to 16); outline(19 to 4, 23 to 12, 19 to 20) }
            Glyph.CLOSE -> { line(6, 6, 18, 18); line(6, 18, 18, 6) }
            Glyph.CHECK -> outline(5 to 12, 10 to 17, 20 to 7)
            Glyph.CLOCK -> {
                drawCircle(color, 8.5f * unit, Offset(12 * unit, 12 * unit), style = Stroke(stroke))
                outline(12 to 7, 12 to 12, 16 to 14)
            }
            Glyph.WARNING -> {
                outline(12 to 3, 22 to 21, 2 to 21, 12 to 3)
                line(12, 9, 12, 14)
                dot(12, 17)
            }
            Glyph.OFFLINE -> {
                outline(4 to 8, 8 to 6, 12 to 5, 16 to 6, 20 to 8)
                outline(7 to 12, 12 to 10, 17 to 12)
                dot(12, 17)
                line(4, 3, 21, 20)
            }
        }
    }
}

@Composable
fun IconAction(glyph: Glyph, description: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) {
        GlyphIcon(glyph, description, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Toolbar(title: String, back: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { if (back != null) IconAction(Glyph.BACK, "返回", action = back) },
        actions = { actions() }
    )
}

@Composable
fun SectionTitle(title: String, actionLabel: String? = null, action: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null) TextButton(onClick = action) { Text(actionLabel) }
    }
}

@Composable
fun EntryRow(title: String, description: String = "", icon: Glyph = Glyph.BOOK, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlyphIcon(icon, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            GlyphIcon(Glyph.NEXT, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun BookCover(title: String, modifier: Modifier = Modifier, warm: Boolean = false) {
    val color = MaterialTheme.colorScheme
    Surface(
        modifier = modifier, shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp, topStart = 7.dp, bottomStart = 7.dp),
        color = if (warm) color.secondaryContainer else color.primary,
        contentColor = if (warm) color.onSecondaryContainer else color.onPrimary
    ) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                val line = if (warm) color.onSecondaryContainer else color.onPrimary
                drawLine(line.copy(alpha = 0.7f), Offset(size.width * 0.16f, size.height * 0.13f),
                    Offset(size.width * 0.16f, size.height * 0.87f), strokeWidth = 1.dp.toPx())
                (0..5).forEach { index ->
                    val y = size.height * (0.70f + index * 0.028f)
                    drawLine(line.copy(alpha = 0.29f), Offset(size.width * 0.28f, y), Offset(size.width * 0.82f, y), strokeWidth = 1.dp.toPx())
                }
            }
            Text(title, Modifier.align(Alignment.TopStart).padding(start = 25.dp, top = 18.dp, end = 9.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MessagePanel(message: String, detail: String = "", action: String? = null, onAction: () -> Unit = {},
                 alternate: String? = null, onAlternate: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction) { Text(action) }
        if (alternate != null) TextButton(onClick = onAlternate) { Text(alternate) }
    }
}

@Composable
fun LoadingPanel(label: String = "正在载入", detail: String = "", progress: Float? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress != null) Text("${(progress * 100).toInt()}%")
        }
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ArchiveLoadingPanel(progress: ArchiveProgress, label: String = progress.stage.label, item: String = "") {
    val context = LocalContext.current
    val bytes = if (progress.received > 0 || progress.total != null) {
        val read = Formatter.formatShortFileSize(context, progress.received)
        progress.total?.let { "$read / ${Formatter.formatShortFileSize(context, it)}" } ?: read
    } else ""
    LoadingPanel(label, listOf(item, bytes).filter(String::isNotBlank).joinToString(" · "), progress.fraction)
}
