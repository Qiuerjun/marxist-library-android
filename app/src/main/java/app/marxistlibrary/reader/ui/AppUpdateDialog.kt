package app.marxistlibrary.reader.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marxistlibrary.reader.R
import app.marxistlibrary.reader.openWebsite
import app.marxistlibrary.reader.update.AppUpdateStatus
import app.marxistlibrary.reader.update.AppUpdates
import app.marxistlibrary.reader.update.ProjectReleases

@Composable
internal fun AppUpdateDialog(updates: AppUpdates, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status by updates.status.collectAsStateWithLifecycle()
    var linkFailed by remember { mutableStateOf(false) }
    LaunchedEffect(updates) { updates.check() }

    val page = when (val current = status) {
        is AppUpdateStatus.Current -> current.release.page
        is AppUpdateStatus.Available -> current.release.page
        is AppUpdateStatus.Downloading -> current.release.page
        is AppUpdateStatus.Ready -> current.release.page
        else -> ProjectReleases
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_update_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.app_update_current_version, updates.currentVersion),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (val current = status) {
                    AppUpdateStatus.Idle, AppUpdateStatus.Checking -> LoadingPanel(stringResource(R.string.app_update_checking))
                    AppUpdateStatus.NoRelease -> Text(stringResource(R.string.app_update_no_release))
                    is AppUpdateStatus.Current -> {
                        Text(stringResource(R.string.app_update_up_to_date))
                        Text(stringResource(R.string.app_update_github_version, current.release.version.toString()))
                    }
                    is AppUpdateStatus.Failed -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = updates::check) { Text(stringResource(R.string.app_update_retry)) }
                    }
                    is AppUpdateStatus.Available -> {
                        Text(
                            stringResource(R.string.app_update_available, current.release.version.toString()),
                            style = MaterialTheme.typography.titleMedium
                        )
                        current.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        when {
                            !updates.allowsDownload -> Text(stringResource(R.string.app_update_debug))
                            current.release.apk == null -> Text(stringResource(R.string.app_update_missing_apk))
                            else -> Button(onClick = updates::download) { Text(stringResource(R.string.app_update_download)) }
                        }
                        if (current.release.notes.isNotBlank()) Text(current.release.notes)
                    }
                    is AppUpdateStatus.Downloading -> {
                        val progress by current.progress.collectAsStateWithLifecycle()
                        if (progress.verifying) {
                            LoadingPanel(stringResource(R.string.app_update_verifying))
                        } else {
                            val total = requireNotNull(current.release.apk).size
                            val detail = stringResource(
                                R.string.app_update_download_size,
                                Formatter.formatShortFileSize(context, progress.received),
                                Formatter.formatShortFileSize(context, total)
                            )
                            LoadingPanel(
                                stringResource(R.string.app_update_downloading),
                                detail,
                                (progress.received.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            )
                            TextButton(onClick = updates::cancelDownload) { Text(stringResource(R.string.app_update_cancel)) }
                        }
                    }
                    is AppUpdateStatus.Ready -> {
                        Text(stringResource(R.string.app_update_ready, current.release.version.toString()))
                        current.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = { updates.install(context) }) { Text(stringResource(R.string.app_update_install)) }
                    }
                }
                if (linkFailed) Text(stringResource(R.string.app_update_link_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { linkFailed = !openWebsite(context, page) }) {
                Text(stringResource(R.string.app_update_open_github))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) }
        }
    )
}
