package app.marxistlibrary.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.marxistlibrary.reader.sources.ArchiveRef
import app.marxistlibrary.reader.ui.CatalogScreen
import app.marxistlibrary.reader.ui.DocumentScreen
import app.marxistlibrary.reader.ui.DownloadScreen
import app.marxistlibrary.reader.ui.Glyph
import app.marxistlibrary.reader.ui.GlyphIcon
import app.marxistlibrary.reader.ui.LibraryTheme
import app.marxistlibrary.reader.ui.LocalReaderColors
import app.marxistlibrary.reader.ui.SearchScreen
import app.marxistlibrary.reader.ui.SettingsScreen
import app.marxistlibrary.reader.ui.ShelfScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable private data object CatalogRoute : NavKey
@Serializable private data object SearchRoute : NavKey
@Serializable private data object ShelfRoute : NavKey
@Serializable private data object SettingsRoute : NavKey
@Serializable private data class DownloadRoute(val id: Int) : NavKey

@Serializable
data class DocumentRoute(
    val path: String,
    val anchor: String = "",
    val edition: String = path,
    val parent: String = "",
    val title: String = "",
    val quote: String = "",
    val offset: Int = 0,
    val digest: String = "",
    val textOnly: Boolean = false
) : NavKey {
    val ref get() = ArchiveRef(path, anchor)

    companion object {
        fun from(position: ReadingPosition) = DocumentRoute(position.path, position.anchor, position.edition,
            title = position.title, quote = position.quote, offset = position.offset, digest = position.digest)

        fun from(bookmark: Bookmark) = DocumentRoute(bookmark.path, bookmark.anchor, bookmark.edition,
            title = bookmark.title, quote = bookmark.quote, offset = bookmark.offset, digest = bookmark.digest)
    }
}

class MainActivity : ComponentActivity() {
    private val request = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) request.value = intent
        setContent { LibraryRoot(viewModel()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request.value = intent
    }

    @Composable
    private fun LibraryRoot(model: LibraryModel) {
        val preferences by model.preferences.collectAsStateWithLifecycle()
        val incoming by request.collectAsStateWithLifecycle()
        val stack = rememberNavBackStack(CatalogRoute)
        val notices = remember { SnackbarHostState() }
        val current = stack.lastOrNull()
        val tabs = listOf(Triple(CatalogRoute, "文库", Glyph.BOOK), Triple(SearchRoute, "搜索", Glyph.FIND),
            Triple(ShelfRoute, "书架", Glyph.SHELF))
        fun open(key: NavKey) {
            if (stack.lastOrNull() != key) stack.add(key)
        }
        fun back() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex)
        }
        fun replace(key: DocumentRoute) {
            if (stack.lastOrNull() != key) {
                if (stack.lastOrNull() is DocumentRoute && stack.size > 1) back()
                open(key)
            }
        }
        LaunchedEffect(model) { model.messages.collect { notices.showSnackbar(it) } }
        LaunchedEffect(incoming) {
            val supplied = incoming ?: return@LaunchedEffect
            val task = supplied.getIntExtra("download_task", 0)
            if (task > 0) open(DownloadRoute(task))
            if (supplied.action == Intent.ACTION_SEND) {
                val text = supplied.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                val ref = model.mirrors.fromSharedText(text)
                if (ref == null) model.notice("无法识别文库链接") else open(DocumentRoute(ref.path, ref.anchor))
            }
            // Resolving a shared link reads the mirrors on disk. Clear it only after that work finishes.
            request.compareAndSet(supplied, null)
        }
        LibraryTheme(preferences.appearance) {
            val dark = LocalReaderColors.current.dark
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                snackbarHost = { SnackbarHost(notices) },
                bottomBar = {
                    if (tabs.any { it.first == current }) {
                        NavigationBar {
                            tabs.forEach { (route, label, glyph) ->
                                NavigationBarItem(
                                    selected = current == route,
                                    onClick = {
                                        if (current != route) {
                                            while (stack.size > 1) back()
                                            if (route != CatalogRoute) open(route)
                                        }
                                    },
                                    label = { Text(label) },
                                    icon = { GlyphIcon(glyph) }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                NavDisplay(
                    backStack = stack,
                    modifier = Modifier.padding(padding),
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                    onBack = ::back,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    popTransitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    entryProvider = entryProvider {
                        entry<CatalogRoute> {
                            CatalogScreen(model, ::open, { open(SearchRoute) }, { open(SettingsRoute) })
                        }
                        entry<SearchRoute> { SearchScreen(model, ::open, { open(SettingsRoute) }) }
                        entry<ShelfRoute> {
                            ShelfScreen(model, ::open, { open(DownloadRoute(it)) }, { open(SettingsRoute) })
                        }
                        entry<SettingsRoute> { SettingsScreen(model, ::back, ::open) }
                        entry<DownloadRoute> { route -> DownloadScreen(model, route.id, ::back, ::open) }
                        entry<DocumentRoute> { route ->
                            DocumentScreen(model, route, ::back, ::open, ::replace, { open(SettingsRoute) },
                                { open(DownloadRoute(it)) })
                        }
                    }
                )
            }
        }
    }
}

fun openWebsite(context: Context, address: String): Boolean {
    val uri = Uri.parse(address)
    if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

fun shareText(context: Context, text: String, title: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, title))
}
