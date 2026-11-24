package app.marxistlibrary.reader.sources

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.marxistlibrary.reader.CustomMirror
import app.marxistlibrary.reader.LibraryDao
import app.marxistlibrary.reader.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

data class ProbeResult(
    val network: Long,
    val checkedAt: Long,
    val duration: Long,
    val latestYear: Int? = null,
    val problem: ConnectionIssue? = null
)

enum class ProbeStatus { UNTESTED, CHECKING, AVAILABLE, SLOW, TIMEOUT, OFFLINE, INCOMPLETE, FAILED }

fun probeStatus(result: ProbeResult?, checking: Boolean, online: Boolean): ProbeStatus = when {
    !online -> ProbeStatus.OFFLINE
    checking -> ProbeStatus.CHECKING
    result == null -> ProbeStatus.UNTESTED
    result.problem == ConnectionIssue.OFFLINE -> ProbeStatus.OFFLINE
    result.problem == ConnectionIssue.TIMEOUT -> ProbeStatus.TIMEOUT
    result.problem in setOf(ConnectionIssue.MISSING, ConnectionIssue.WRONG_CONTENT, ConnectionIssue.LIMITED) ->
        ProbeStatus.INCOMPLETE
    result.problem != null -> ProbeStatus.FAILED
    result.duration >= 2_500 -> ProbeStatus.SLOW
    else -> ProbeStatus.AVAILABLE
}

// Source-wide failures and a missing individual document are tracked separately.
class Mirrors(context: Context, private val dao: LibraryDao, private val preferences: Settings, private val http: ArchiveHttp) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val results = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    val checks = results.asStateFlow()
    val sources = dao.mirrors().map { OfficialSources + it.mapNotNull(::source) }
    private val probes = MutableStateFlow<Map<Pair<String, Long>, CompletableDeferred<ProbeResult>>>(emptyMap())
    val checking = probes.map { it.keys }
    private val failed = ConcurrentHashMap<String, Long>()
    private val missing = ConcurrentHashMap<Pair<String, String>, Long>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val probeSlots = Semaphore(2)

    fun activeNetwork(): Network? = connectivity.activeNetwork

    fun isWifi(network: Network?): Boolean = network != null &&
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    fun online(): Boolean = activeNetwork() != null

    fun networkId() = activeNetwork()?.networkHandle ?: 0L

    val networkIds = callbackFlow {
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network.networkHandle)
            }

            override fun onLost(network: Network) {
                trySend(networkId().takeUnless { it == network.networkHandle } ?: 0L)
            }
        }
        connectivity.registerDefaultNetworkCallback(listener)
        trySend(networkId())
        awaitClose { connectivity.unregisterNetworkCallback(listener) }
    }.distinctUntilChanged()

    suspend fun all(): List<Source> = withContext(Dispatchers.IO) {
        OfficialSources + dao.customMirrors().mapNotNull(::source)
    }

    suspend fun candidates(path: String): List<Source> {
        if (!online()) throw ArchiveProblem(ConnectionIssue.OFFLINE)
        val sources = all()
        val fixed = preferences.current().fixedSource
        if (fixed.isNotEmpty()) {
            return sources.filter { it.id == fixed }.ifEmpty { listOf(MainSource) }.also {
                val until = blockedUntil[it.first().id] ?: 0
                if (until > System.currentTimeMillis()) throw ArchiveProblem(ConnectionIssue.LIMITED)
            }
        }
        val now = System.currentTimeMillis()
        val network = networkId()
        val available = sources.filter { (blockedUntil[it.id] ?: 0) <= now }.sortedWith(
            compareBy<Source> { (missing[it.id to path] ?: 0) > now }
                .thenBy { (failed[it.id] ?: 0) > now }
                .thenBy { it.id != MainSource.id && currentProbe(it, network, now)?.problem != null }
                .thenBy { it.id != MainSource.id }
                .thenByDescending { currentProbe(it, network, now)?.latestYear ?: 0 }
                .thenBy { currentProbe(it, network, now)?.duration ?: 9000 }
        )
        if (available.isEmpty()) throw ArchiveProblem(ConnectionIssue.LIMITED)
        return available.take(3)
    }

    suspend fun add(name: String, url: String): Source {
        val new = Source.custom(name, url) ?: throw IllegalArgumentException("请输入公开的 HTTPS 镜像地址")
        if (all().any { it.base == new.base }) throw IllegalArgumentException("该镜像已添加")
        val decided = new.copy(title = new.title.ifBlank { new.base.host })
        val tested = probe(decided)
        if (tested.problem != null) throw ArchiveProblem(tested.problem)
        withContext(Dispatchers.IO) { dao.putMirror(CustomMirror(decided.id, decided.title, decided.base.toString())) }
        return decided
    }

    suspend fun remove(id: String) {
        withContext(Dispatchers.IO) { dao.removeMirror(id) }
        if (preferences.current().fixedSource == id) preferences.fixedSource("")
        results.update { it - id }
    }

    suspend fun inspectAll() = coroutineScope { all().map { async { probe(it) } }.awaitAll() }

    suspend fun probe(source: Source): ProbeResult {
        val network = activeNetwork()
        val key = source.id to (network?.networkHandle ?: 0L)
        val task = CompletableDeferred<ProbeResult>()
        val current = probes.updateAndGet { running ->
            if (key in running) running else running + (key to task)
        }.getValue(key)
        if (current !== task) return current.await()
        try {
            val result = probeSlots.withPermit { withContext(Dispatchers.Default) { measure(source, network) } }
            if (key.second == networkId()) results.update { it + (source.id to result) }
            task.complete(result)
            return result
        } catch (error: Exception) {
            task.completeExceptionally(error)
            throw error
        } finally {
            probes.update { it - key }
        }
    }

    private suspend fun measure(source: Source, network: Network?): ProbeResult {
        val timer = TimeSource.Monotonic.markNow()
        var year: Int? = null
        val issue = try {
            if (network == null) throw ArchiveProblem(ConnectionIssue.OFFLINE)
            val available = withTimeoutOrNull(6500) {
                val home = http.get(source, "/chinese/", 512 * 1024, network = network)
                val overview = parse(home)
                val samplePath = "/chinese/marx/marxist.org-chinese-marx-1875-4.htm"
                val sample = parse(http.get(source, samplePath, 700 * 1024, network = network))
                if (overview.select("a[href]").size < 30 ||
                    !overview.title().contains("马克思主义文库") ||
                    !sample.title().contains("哥达纲领批判")) {
                    throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
                }
                year = ArchiveYear.find(overview.text())?.groupValues?.get(1)?.toIntOrNull()
                true
            }
            if (available == null) ConnectionIssue.TIMEOUT else {
                failed.remove(source.id)
                null
            }
        } catch (problem: ArchiveProblem) {
            problem.issue
        }
        return ProbeResult(network?.networkHandle ?: 0L, System.currentTimeMillis(), timer.elapsedNow().inWholeMilliseconds, year, issue)
    }

    fun record(source: Source, path: String, failure: ArchiveProblem) {
        val now = System.currentTimeMillis()
        when (failure.issue) {
            ConnectionIssue.MISSING, ConnectionIssue.WRONG_CONTENT -> {
                if (missing.size > 1024) missing.entries.removeIf { it.value < now }
                missing[source.id to path] = now + 600_000
            }
            ConnectionIssue.LIMITED -> blockedUntil[source.id] = now + (failure.retrySeconds ?: 60) * 1_000
            ConnectionIssue.TIMEOUT, ConnectionIssue.DNS, ConnectionIssue.CERTIFICATE, ConnectionIssue.SERVER ->
                failed[source.id] = now + 180_000
            else -> Unit
        }
    }

    suspend fun fromSharedText(text: String): ArchiveRef? {
        val url = text.trim().take(4000).let { candidate ->
            UrlInText.find(candidate)?.value?.trimEnd('。', '，', '）', ')') ?: candidate
        }
        return ArchiveRef.fromUrl(url, all())
    }

    private fun parse(reply: WebReply) =
        Jsoup.parse(ByteArrayInputStream(reply.bytes), reply.charset, reply.url.toString())

    private fun currentProbe(source: Source, network: Long, now: Long) =
        results.value[source.id]?.takeIf { it.network == network && it.checkedAt + 600_000 > now }

    private fun source(entry: CustomMirror) = Source.custom(entry.title, entry.base)

    companion object {
        private val ArchiveYear = Regex("\\d{4}\\s*[-—–]\\s*(\\d{4})\\s*历年更新")
        private val UrlInText = Regex("https?://[^\\s<>]+")
    }
}
