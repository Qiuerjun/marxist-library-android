package app.marxistlibrary.reader

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.marxistlibrary.reader.catalog.Archive
import app.marxistlibrary.reader.catalog.ArchiveFiles
import app.marxistlibrary.reader.catalog.SeedCatalog
import app.marxistlibrary.reader.download.Downloads
import app.marxistlibrary.reader.library.ReadingLibrary
import app.marxistlibrary.reader.search.ChineseText
import app.marxistlibrary.reader.settings.ReadingSettings
import app.marxistlibrary.reader.settings.Settings
import app.marxistlibrary.reader.sources.ArchiveHttp
import app.marxistlibrary.reader.sources.Mirrors
import app.marxistlibrary.reader.update.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

class LibraryApp : Application(), Configuration.Provider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "archive.db").build() }
    val settings by lazy { Settings(this) }
    val chinese by lazy { assets.open("chinese-variants.tsv").bufferedReader().useLines(ChineseText::fromLines) }
    val mirrors by lazy { Mirrors(this, database.library(), settings, http) }
    val archive by lazy { Archive(database, SeedCatalog(this, chinese), ArchiveFiles(this), chinese, settings, mirrors, http) }
    val downloads by lazy { Downloads(this, database, archive, mirrors, settings) }
    val library by lazy { ReadingLibrary(database) }
    private val http by lazy { ArchiveHttp(useSystemProxy = { settings.current().useSystemProxy }) }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(10_000, 29_999).build()

    override fun onCreate() {
        super.onCreate()
        if (Process.myUid() != applicationInfo.uid) return
        scope.launch {
            archive.installSeed()
            runCatching { archive.trimCache() }
            if (mirrors.online() && database.library().page("/chinese/") == null) {
                runCatching { archive.refreshCatalog() }
            }
        }
    }

    suspend fun setPeriodicRefresh(enabled: Boolean) {
        settings.autoRefresh(enabled)
        val work = WorkManager.getInstance(this)
        if (enabled) {
            val update = PeriodicWorkRequestBuilder<RefreshWork>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork("archive-feed", ExistingPeriodicWorkPolicy.UPDATE, update)
        } else {
            work.cancelUniqueWork("archive-feed")
        }
    }
}

class RefreshWork(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LibraryApp
        if (!app.settings.current().autoRefresh) return Result.success()
        return try {
            app.archive.refreshUpdates()
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}

class LibraryModel(application: Application) : AndroidViewModel(application) {
    val app = application as LibraryApp
    val archive = app.archive
    val library = app.library
    val mirrors = app.mirrors
    val downloads = app.downloads
    val settings = app.settings
    val preferences = settings.values.stateIn(viewModelScope, SharingStarted.Eagerly, ReadingSettings())
    val catalog = app.database.library().homeCatalog()
    val updates = app.database.library().updates()
    internal val appUpdates by lazy { AppUpdates(application, viewModelScope) }
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()

    fun notice(message: String) {
        notices.trySend(message)
    }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notices.send(error.message?.take(140) ?: "操作未完成，请重试")
            }
        }
    }
}
