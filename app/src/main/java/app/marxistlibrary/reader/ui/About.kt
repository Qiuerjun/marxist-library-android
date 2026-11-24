package app.marxistlibrary.reader.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.marxistlibrary.reader.R
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.update.ProjectHomepage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class AboutDocument(@StringRes val title: Int) {
    LICENSE(R.string.about_project_license),
    DISCLAIMER(R.string.about_disclaimer)
}

@Composable
internal fun AboutSection(openDocument: (AboutDocument) -> Unit, checkUpdates: () -> Unit) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.about_title))
    Text(stringResource(R.string.about_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
    EntryRow(
        stringResource(R.string.app_update_title),
        stringResource(R.string.app_update_summary),
        Glyph.REFRESH,
        onClick = checkUpdates
    )
    EntryRow(
        stringResource(AboutDocument.LICENSE.title),
        stringResource(R.string.about_project_license_summary),
        Glyph.NOTES
    ) { openDocument(AboutDocument.LICENSE) }
    EntryRow(
        stringResource(AboutDocument.DISCLAIMER.title),
        stringResource(R.string.about_disclaimer_summary),
        Glyph.TOC
    ) { openDocument(AboutDocument.DISCLAIMER) }
    EntryRow(stringResource(R.string.about_project_home), stringResource(R.string.about_project_home_summary), Glyph.LINK) {
        openWebsite(context, ProjectHomepage)
    }
    EntryRow("Marxists Internet Archive", icon = Glyph.LINK) {
        openWebsite(context, MainSource.address("/chinese/").toString())
    }
    EntryRow(stringResource(R.string.about_archive_terms), icon = Glyph.LINK) {
        openWebsite(context, "https://www.marxists.org/admin/legal/terms-of-use.htm")
    }
}

@Composable
internal fun AboutDocumentDialog(document: AboutDocument, onDismiss: () -> Unit) {
    val text = when (document) {
        AboutDocument.DISCLAIMER -> stringResource(R.string.about_disclaimer_text)
        AboutDocument.LICENSE -> {
            val assets = LocalContext.current.assets
            val unavailable = stringResource(R.string.about_license_unavailable)
            val license by produceState<String?>(initialValue = null, assets, unavailable) {
                value = try {
                    withContext(Dispatchers.IO) { assets.open("LICENSE").bufferedReader().use { it.readText() } }
                } catch (_: IOException) {
                    unavailable
                }
            }
            license
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(document.title)) },
        text = {
            if (text == null) {
                LoadingPanel(stringResource(R.string.about_license_loading))
            } else {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) } }
    )
}
