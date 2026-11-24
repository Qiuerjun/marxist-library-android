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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

data class ProbeResult(
    val network: Long,
    val checkedAt: Long,
    val homeMillis: Long?,
    val latestYear: Int? = null,
    val problem: ConnectionIssue? = null,
    val sampleProblem: ConnectionIssue? = null
)

enum class ProbeStatus {
    UNTESTED, CHECKING, AVAILABLE, SLOW, TIMEOUT, OFFLINE, SAMPLE_MISSING, SAMPLE_TIMEOUT, WRONG_CONTENT, INCOMPLETE, FAILED
}

fun probeStatus(result: ProbeResult?, checking: Boolean, online: Boolean): ProbeStatus = when {
    !online -> ProbeStatus.OFFLINE
    checking -> ProbeStatus.CHECKING
    result == null -> ProbeStatus.UNTESTED
    result.problem == ConnectionIssue.OFFLINE -> ProbeStatus.OFFLINE
    result.problem == ConnectionIssue.TIMEOUT -> ProbeStatus.TIMEOUT
    result.problem == ConnectionIssue.WRONG_CONTENT -> ProbeStatus.WRONG_CONTENT
    result.problem in setOf(ConnectionIssue.MISSING, ConnectionIssue.LIMITED) -> ProbeStatus.INCOMPLETE
    result.problem != null -> ProbeStatus.FAILED
    result.sampleProblem == ConnectionIssue.MISSING -> ProbeStatus.SAMPLE_MISSING
    result.sampleProblem == ConnectionIssue.TIMEOUT -> ProbeStatus.SAMPLE_TIMEOUT
    result.sampleProblem == ConnectionIssue.WRONG_CONTENT -> ProbeStatus.WRONG_CONTENT
    result.sampleProblem != null -> ProbeStatus.INCOMPLETE
    (result.homeMillis ?: 0L) >= 2_500 -> ProbeStatus.SLOW
    else -> ProbeStatus.AVAILABLE
}

internal data class MirrorCandidate(
    val source: Source, val missing: Boolean, val failed: Boolean, val probe: ProbeResult?
)

internal fun rankMirrors(sources: List<Source>, snapshot: (Source) -> MirrorCandidate): List<Source> =
    sources.map(snapshot).sortedWith(
        compareBy<MirrorCandidate> { it.missing }
            .thenBy { it.failed }
            .thenBy { it.source.id != MainSource.id }
            .thenBy { it.probe?.problem != null }
            .thenBy { it.probe == null }
            .thenBy { it.probe?.sampleProblem != null }
            .thenByDescending { it.probe?.latestYear ?: 0 }
            .thenBy { it.probe?.homeMillis ?: Long.MAX_VALUE }
    ).take(3).map(MirrorCandidate::source)

private const val HomePath = "/chinese/"
private const val ProbeTimeoutMillis = 6_500L
private val ArchiveName = Regex("[马馬]克思主[义義]文[库庫]|Marxists Internet Archive", RegexOption.IGNORE_CASE)
private val ArchiveYear = Regex("\\d{4}\\s*[-—–]\\s*(\\d{4})\\s*历年更新")
private val Samples = listOf(
    "/chinese/marx/marxist.org-chinese-marx-1875-4.htm" to Regex("哥[达達][纲綱][领領]批判"),
    "/chinese/marx/01.htm" to Regex("共[产產][党黨]宣言")
)

// The homepage has its own deadline. A missing document can be checked against a second sample.
internal suspend fun checkMirror(source: Source, network: Long, fetch: suspend (String, Int) -> WebReply): ProbeResult {
    var homeMillis: Long? = null
    val overview = try {
        val timer = TimeSource.Monotonic.markNow()
        val reply = withTimeoutOrNull(ProbeTimeoutMillis) { fetch(HomePath, 512 * 1024) }
            ?: throw ArchiveProblem(ConnectionIssue.TIMEOUT)
        homeMillis = timer.elapsedNow().inWholeMilliseconds
        val page = parseProbePage(reply)
        val archiveName = ArchiveName.containsMatchIn(page.title()) ||
            page.select("h1,h2,img[alt]").any { ArchiveName.containsMatchIn(it.text() + it.attr("alt")) }
        val catalogLinks = page.select("a[href]").asSequence()
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.let(source::reference) }
            .map { it.path.substringBefore('?') }.filterNot { it == HomePath }.distinct().take(3).count()
        if (!archiveName || catalogLinks < 3) throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        page
    } catch (problem: ArchiveProblem) {
        return ProbeResult(network, System.currentTimeMillis(), homeMillis, problem = problem.issue)
    }
    val year = ArchiveYear.find(overview.text())?.groupValues?.get(1)?.toIntOrNull()
    val sampleProblem = checkSample(source, fetch)
    return ProbeResult(network, System.currentTimeMillis(), homeMillis, year, sampleProblem = sampleProblem)
}

private suspend fun checkSample(source: Source, fetch: suspend (String, Int) -> WebReply): ConnectionIssue? {
    var issue = ConnectionIssue.MISSING
    return try {
        val verified = withTimeoutOrNull(ProbeTimeoutMillis) {
            for ((path, title) in Samples) {
                val reply = try {
                    fetch(path, 700 * 1024)
                } catch (problem: ArchiveProblem) {
                    if (problem.issue == ConnectionIssue.MISSING) continue
                    if (problem.issue != ConnectionIssue.WRONG_CONTENT) throw problem
                    issue = problem.issue
                    continue
                }
                val page = parseProbePage(reply)
                val resolved = source.reference(reply.url)?.path?.substringBefore('?')
                if (resolved != null && resolved != HomePath &&
                    (title.containsMatchIn(page.title()) || page.select("h1,h2").any { title.containsMatchIn(it.text()) })) {
                    return@withTimeoutOrNull true
                }
                issue = ConnectionIssue.WRONG_CONTENT
            }
            false
        }
        when (verified) {
            true -> null
            false -> issue
            null -> ConnectionIssue.TIMEOUT
        }
    } catch (problem: ArchiveProblem) {
        problem.issue
    }
}

private fun parseProbePage(reply: WebReply): Document =
    Jsoup.parse(ByteArrayInputStream(reply.bytes), reply.charset, reply.url.toString())

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
        val checks = results.value
        // Sorting only compares values captured once per source, even while a probe completes.
        val available = rankMirrors(sources.filter { (blockedUntil[it.id] ?: 0) <= now }) { source ->
            MirrorCandidate(
                source,
                missing = (missing[source.id to path] ?: 0) > now,
                failed = (failed[source.id] ?: 0) > now,
                probe = checks[source.id]?.takeIf { it.network == network && it.checkedAt + 600_000 > now }
            )
        }
        if (available.isEmpty()) throw ArchiveProblem(ConnectionIssue.LIMITED)
        return available
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
            val priorFailure = failed[source.id]
            val result = probeSlots.withPermit {
                if (network == null) ProbeResult(0, System.currentTimeMillis(), null, problem = ConnectionIssue.OFFLINE)
                else withContext(Dispatchers.Default) {
                    checkMirror(source, key.second) { path, limit -> http.get(source, path, limit, network = network) }
                }
            }
            if (key.second == networkId()) {
                results.update { it + (source.id to result) }
                if (priorFailure != null && result.problem == null && result.sampleProblem == null) {
                    failed.remove(source.id, priorFailure)
                }
            }
            task.complete(result)
            return result
        } catch (error: Exception) {
            task.completeExceptionally(error)
            throw error
        } finally {
            probes.update { it - key }
        }
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

    private fun source(entry: CustomMirror) = Source.custom(entry.title, entry.base)

    companion object {
        private val UrlInText = Regex("https?://[^\\s<>]+")
    }
}
