package app.marxistlibrary.reader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.marxistlibrary.reader.settings.PdfAnimation
import app.marxistlibrary.reader.settings.PdfReadingMode
import app.marxistlibrary.reader.settings.PdfSettings

// PdfScreen can edit these preferences even while the WebView is starting or recovering.
@Composable
internal fun PdfSettingsDialog(settings: PdfSettings, onClose: () -> Unit, onSave: (PdfSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("阅读设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("页面排列", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    Choice("纵向连续滚动", draft.mode == PdfReadingMode.CONTINUOUS) {
                        draft = draft.copy(mode = PdfReadingMode.CONTINUOUS)
                    }
                    Choice("横向滑动翻页", draft.mode == PdfReadingMode.PAGED) {
                        draft = draft.copy(mode = PdfReadingMode.PAGED)
                    }
                }
                Text("翻页动画", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    Choice("滑动", draft.animation == PdfAnimation.SLIDE) { draft = draft.copy(animation = PdfAnimation.SLIDE) }
                    Choice("淡入", draft.animation == PdfAnimation.FADE) { draft = draft.copy(animation = PdfAnimation.FADE) }
                    Choice("无动画", draft.animation == PdfAnimation.NONE) { draft = draft.copy(animation = PdfAnimation.NONE) }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(draft.tapEdges, role = Role.Switch) { draft = draft.copy(tapEdges = it) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("横向模式下点击左右边缘翻页", Modifier.weight(1f))
                    Switch(draft.tapEdges, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, select: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = select),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, onClick = null)
        Text(label)
    }
}
