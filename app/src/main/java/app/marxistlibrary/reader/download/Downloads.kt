package app.marxistlibrary.reader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.marxistlibrary.reader.DownloadItem
import app.marxistlibrary.reader.DownloadTask
import app.marxistlibrary.reader.LibraryApp
import app.marxistlibrary.reader.LibraryDatabase
import app.marxistlibrary.reader.MainActivity
import app.marxistlibrary.reader.Paragraph
import app.marxistlibrary.reader.R
import app.marxistlibrary.reader.catalog.Archive
import app.marxistlibrary.reader.catalog.ArchiveProgress
import app.marxistlibrary.reader.catalog.ArchiveStage
import app.marxistlibrary.reader.catalog.Chapter
import app.marxistlibrary.reader.catalog.OpenPage
import app.marxistlibrary.reader.catalog.PageFormat
import app.marxistlibrary.reader.settings.Settings
import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ArchiveRef
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.Mirrors
import app.marxistlibrary.reader.sources.digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class DownloadProgress(val generation: Int, val title: String, val archive: ArchiveProgress, val savedBytes: Long = 0)

// A task has one generation. Pause and cancel invalidate any older worker's state updates.
class Downloads(
    private val context: Context,
    private val database: LibraryDatabase,
    private val archive: Archive,
    private val mirrors: Mirrors,
    private val settings: Settings
) {
    private val dao = database.library()
    private val mutexes = Array(32) { Mutex() }
    private val loading = MutableStateFlow<Map<Int, DownloadProgress>>(emptyMap())
    val transfers = loading.asStateFlow()
    val tasks = dao.tasks()
    fun items(id: Int) = dao.observeItems(id)
    fun task(id: Int) = dao.observeTask(id)

    suspend fun add(root: ArchiveRef, title: String, chapters: List<Chapter> = emptyList(), recursive: Boolean = false): Int {
        val selection = if (chapters.isEmpty()) listOf(Chapter(root, title)) else chapters.distinctBy { it.ref.path }
        require(selection.size <= MaxPages) { "目录较大，请选择卷册或章节下载" }
        val key = digest(root.path + "|" + recursive + "|" + selection.map { it.ref.path }.sorted().joinToString("|"))
        val task = withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.task(key)?.let { return@withTransaction it }
                val entry = DownloadTask(key = key, root = root.path, title = title, recursive = recursive,
                    total = selection.size, updatedAt = System.currentTimeMillis())
                val id = dao.createTask(entry).toInt()
                dao.putDownloadItems(selection.map { DownloadItem(id, it.ref.path, it.title) })
                entry.copy(id = id)
            }
        }
        when (task.state) {
            "PAUSED", "CANCELLED", "FAILED", "PARTIAL" -> resume(task.id)
            "QUEUED", "WAITING" -> schedule(task, immediate = true)
        }
        return task.id
    }

    suspend fun resume(id: Int) {
        val task = change(id, "QUEUED") ?: return
        cancelSchedule(id, task.generation - 1)
        schedule(task, immediate = true)
    }

    suspend fun pause(id: Int, cancel: Boolean = false) {
        val task = change(id, if (cancel) "CANCELLED" else "PAUSED") ?: return
        cancelSchedule(id, task.generation - 1)
    }

    suspend fun remove(id: Int) {
        pause(id, cancel = true)
        val released = withContext(Dispatchers.IO) {
            database.withTransaction {
                val entries = dao.items(id)
                dao.removeItems(id)
                dao.removeTask(id)
                entries.forEach { if (!dao.isPinned(it.path, it.digest)) dao.removeParagraphs(it.path) }
                entries.filter { it.digest.isNotBlank() }.map { it.path }.toSet()
            }
        }
        archive.trimCache(releasedPaths = released)
    }

    private suspend fun change(id: Int, state: String): DownloadTask? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val old = dao.task(id) ?: return@withTransaction null
            val task = old.copy(state = state, generation = old.generation + 1, attempts = 0, error = "",
                retryAt = 0, updatedAt = System.currentTimeMillis())
            dao.putTask(task)
            task
        }
    }

    private fun cancelSchedule(id: Int, generation: Int) {
        context.getSystemService(JobScheduler::class.java).cancel(nativeId(id))
        WorkManager.getInstance(context).cancelUniqueWork(workerName(id, generation))
    }

    private suspend fun schedule(task: DownloadTask, immediate: Boolean) {
        val wifi = settings.current().wifiOnly
        val large = task.recursive || task.total >= 5 || task.root.substringBefore('?').endsWith(".pdf") ||
            task.root.substringBefore('?').endsWith(".epub")
        if (large && immediate && Build.VERSION.SDK_INT >= 34) {
            val info = JobInfo.Builder(nativeId(task.id), ComponentName(context, TransferJob::class.java))
                .setUserInitiated(true)
                .setExtras(PersistableBundle().apply {
                    putInt("id", task.id)
                    putInt("generation", task.generation)
                })
            if (wifi) info.setRequiredNetwork(wifiRequest()) else info.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            val result = runCatching { context.getSystemService(JobScheduler::class.java).schedule(info.build()) }.getOrNull()
            if (result == JobScheduler.RESULT_SUCCESS) return
        }
        scheduleWorker(task, wifi)
    }

    private fun scheduleWorker(task: DownloadTask, wifi: Boolean) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        if (wifi && Build.VERSION.SDK_INT >= 28) {
            constraints.setRequiredNetworkRequest(wifiRequest(), NetworkType.CONNECTED)
        }
        val request = OneTimeWorkRequestBuilder<DownloadWork>()
            .setInputData(workDataOf("id" to task.id, "generation" to task.generation))
            .setConstraints(constraints.build())
            .setInitialDelay((task.retryAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workerName(task.id, task.generation), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun deferToWorker(id: Int, generation: Int) {
        val task = withContext(Dispatchers.IO) { dao.task(id) } ?: return
        if (task.generation == generation && task.state in Active) schedule(task, immediate = false)
    }

    internal suspend fun recordFailure(id: Int, generation: Int, failure: Exception) = withContext(Dispatchers.IO) {
        Log.e("ArchiveDownloads", "Download $id could not finish", failure)
        try {
            val task = dao.task(id) ?: return@withContext
            if (task.generation == generation) status(task, "FAILED", "下载意外中断，可点按重试", activeOnly = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (storageFailure: Exception) {
            // A database failure still needs to release the Android task.
            Log.e("ArchiveDownloads", "Could not record failure for download $id", storageFailure)
        }
    }

    suspend fun run(id: Int, generation: Int, selectedNetwork: Network?, progress: (Int, Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            mutexes[id % mutexes.size].withLock {
                val task = dao.task(id) ?: return@withLock false
                if (task.generation != generation || task.state !in Active) return@withLock false
                if (task.retryAt > System.currentTimeMillis()) return@withLock true
                loading.update { it + (id to DownloadProgress(generation, task.title, ArchiveProgress(ArchiveStage.CACHE))) }
                try {
                    execute(task, selectedNetwork, progress)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { status(task, "WAITING", "等待继续") }
                    throw cancelled
                } catch (failure: java.io.IOException) {
                    status(task, "FAILED", failure.message ?: "下载失败")
                    false
                } finally {
                    loading.update { if (it[id]?.generation == generation) it - id else it }
                }
            }
        }

    private suspend fun execute(task: DownloadTask, selectedNetwork: Network?, progress: (Int, Int) -> Unit): Boolean {
        status(task, "RUNNING")
        val seen = mutableSetOf<String>()
        val directory = task.root.substringBefore('?').substringBeforeLast('/') + "/"
        val savedFiles = archive.downloadedFiles(dao.items(task.id).filter { it.digest.isNotBlank() }).toMutableSet()
        var savedBytes = savedFiles.sumOf { it.length() }
        while (dao.task(task.id)?.generation == task.generation) {
            val current = dao.items(task.id)
            val item = current.firstOrNull { it.digest.isBlank() && it.path !in seen } ?: break
            seen += item.path
            val wifiOnly = settings.current().wifiOnly
            val network = selectedNetwork ?: mirrors.activeNetwork()
            if (!mirrors.online() || wifiOnly && !mirrors.isWifi(network)) {
                status(task, "WAITING", if (wifiOnly) "等待 Wi-Fi" else "等待联网")
                return true
            }
            val page = try {
                archive.files.checkSpace()
                val ref = ArchiveRef.parse(item.path) ?: throw ArchiveProblem(ConnectionIssue.UNSUPPORTED)
                archive.open(ref, forDownload = true, network = if (wifiOnly) network else null) {
                    showProgress(task, item.title, it, savedBytes)
                }
            } catch (failure: ArchiveProblem) {
                if (failure.issue == ConnectionIssue.NO_SPACE) {
                    status(task, "PAUSED", failure.issue.label)
                    return false
                }
                val transient = failure.issue in Transient
                val attempts = dao.task(task.id)?.attempts ?: 0
                if (transient && attempts < 3 || failure.issue == ConnectionIssue.OFFLINE || failure.issue == ConnectionIssue.WIFI) {
                    val retryAt = System.currentTimeMillis() + (failure.retrySeconds ?: 0L) * 1000
                    status(task, "WAITING", failure.issue.label, if (transient) attempts + 1 else attempts, retryAt)
                    return true
                }
                saveItem(task, item.copy(error = failure.issue.label), emptyList())
                continue
            }
            val children = if (task.recursive && page.format == PageFormat.DIRECTORY && !item.discovered) {
                page.chapters.filter { it.ref.path.startsWith(directory) }.distinctBy { it.ref.path }
                    .filterNot { child -> current.any { it.path == child.ref.path } }
            } else emptyList()
            if (current.size + children.size > MaxPages) {
                saveItem(task, item.copy(error = "目录较大，请选择卷册或章节下载"), emptyList())
                continue
            }
            savedBytes += archive.files.resources(page.format, page.digest, page.details)
                .filter(savedFiles::add).sumOf { it.length() }
            if (page.format == PageFormat.ARTICLE) showProgress(task, item.title, ArchiveProgress(ArchiveStage.INDEX), savedBytes)
            val paragraphs = archive.paragraphs(page)
            saveItem(task, item.copy(digest = page.digest, discovered = true, error = ""), children, paragraphs)
            val latest = dao.items(task.id)
            progress(latest.count { it.digest.isNotBlank() }, latest.size)
        }
        val all = dao.items(task.id)
        val failures = all.filter { it.digest.isBlank() }
        val state = when {
            failures.isEmpty() && all.isNotEmpty() -> "COMPLETED"
            all.any { it.digest.isNotBlank() } -> "PARTIAL"
            else -> "FAILED"
        }
        status(task, state, failures.firstOrNull()?.error.orEmpty())
        return false
    }

    private fun showProgress(task: DownloadTask, title: String, progress: ArchiveProgress, savedBytes: Long) {
        // A late callback from a cancelled HTTP request must not restore the old indicator.
        loading.update {
            if (it[task.id]?.generation != task.generation) it
            else it + (task.id to DownloadProgress(task.generation, title, progress, savedBytes))
        }
    }

    private suspend fun saveItem(task: DownloadTask, item: DownloadItem, chapters: List<Chapter>, paragraphs: List<Paragraph>? = null) {
        database.withTransaction {
            val latest = dao.task(task.id) ?: return@withTransaction
            if (latest.generation != task.generation) return@withTransaction
            dao.putDownloadItems(listOf(item) + chapters.map { DownloadItem(task.id, it.ref.path, it.title) })
            if (paragraphs != null) {
                dao.removeParagraphs(item.path)
                dao.indexParagraphs(paragraphs)
            }
            val items = dao.items(task.id)
            dao.putTask(latest.copy(total = items.size, count = items.count { it.digest.isNotBlank() }, updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun status(
        task: DownloadTask, state: String, error: String = "", attempts: Int? = null, retryAt: Long = 0,
        activeOnly: Boolean = false
    ) {
        database.withTransaction {
            val latest = dao.task(task.id) ?: return@withTransaction
            if (latest.generation != task.generation || activeOnly && latest.state !in Active) return@withTransaction
            dao.putTask(latest.copy(state = state, error = error, attempts = attempts ?: latest.attempts,
                retryAt = retryAt, updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        private const val MaxPages = 900
        private val Active = setOf("QUEUED", "WAITING", "RUNNING")
        private val Transient = setOf(ConnectionIssue.TIMEOUT, ConnectionIssue.DNS, ConnectionIssue.SERVER, ConnectionIssue.LIMITED)
        private fun workerName(id: Int, generation: Int) = "archive-download-$id-$generation"
        private fun wifiRequest() = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        private fun nativeId(id: Int) = 35_000 + id
    }
}

class DownloadWork(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LibraryApp
        val id = inputData.getInt("id", 0)
        if (id <= 0) return Result.failure()
        val generation = inputData.getInt("generation", 0)
        val selectedNetwork = if (Build.VERSION.SDK_INT >= 28) network else null
        return try {
            val retry = app.downloads.run(id, generation, selectedNetwork) { _, _ -> }
            if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            app.downloads.recordFailure(id, generation, failure)
            Result.failure()
        }
    }
}

// JobService callbacks and completion use the same main-thread scope.
internal class TransferJobs(private val scope: CoroutineScope) {
    private val running = mutableMapOf<Int, Job>()

    fun start(id: Int, failed: suspend (Exception) -> Unit, finished: () -> Unit, work: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val current = coroutineContext.job
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                current.ensureActive()
                failed(failure)
            } finally {
                // A stopped job belongs to Android again. Its ID may already have been reused.
                if (running[id] === current) {
                    running.remove(id)
                    finished()
                }
            }
        }
        running.put(id, job)?.cancel()
        job.start()
    }

    fun stop(id: Int) {
        running.remove(id)?.cancel()
    }

    fun close() {
        running.clear()
        scope.cancel()
    }
}

class TransferJob : JobService() {
    private val jobs = TransferJobs(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val id = params.extras.getInt("id")
        if (id <= 0) return false
        val app = application as LibraryApp
        val generation = params.extras.getInt("generation")
        jobs.start(
            params.jobId,
            failed = { app.downloads.recordFailure(id, generation, it) },
            finished = { jobFinished(params, false) }
        ) {
            setNotification(params, params.jobId, notice(id, 0, 0), JOB_END_NOTIFICATION_POLICY_REMOVE)
            val retry = app.downloads.run(id, generation, params.network) { complete, total ->
                getSystemService(NotificationManager::class.java).notify(params.jobId, notice(id, complete, total))
            }
            if (retry) app.downloads.deferToWorker(id, generation)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.stop(params.jobId)
        return true
    }

    override fun onDestroy() {
        jobs.close()
        super.onDestroy()
    }

    private fun notice(id: Int, done: Int, total: Int): Notification {
        val channel = "archive-downloads"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "离线下载", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(this, MainActivity::class.java).putExtra("download_task", id)
        val open = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_library)
            .setContentTitle("正在下载文库文献")
            .setContentText(if (total == 0) "正在准备" else "$done / $total 个页面")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0).build()
    }
}
