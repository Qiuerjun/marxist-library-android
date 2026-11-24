package app.marxistlibrary.reader

import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.MirrorCandidate
import app.marxistlibrary.reader.sources.OfficialSources
import app.marxistlibrary.reader.sources.ProbeResult
import app.marxistlibrary.reader.sources.ProbeStatus
import app.marxistlibrary.reader.sources.Source
import app.marxistlibrary.reader.sources.WebReply
import app.marxistlibrary.reader.sources.checkMirror
import app.marxistlibrary.reader.sources.probeStatus
import app.marxistlibrary.reader.sources.rankMirrors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorsTest {
    private val home = "/chinese/"
    private val first = "/chinese/marx/marxist.org-chinese-marx-1875-4.htm"
    private val backup = "/chinese/marx/01.htm"
    private val source = requireNotNull(Source.custom("测试镜像", "https://mirror.example.org/collections"))
    private val overview = """
        <title>中文資料</title>
        <h1>中文馬克思主義文庫</h1>
        <p>2006-2025历年更新列表</p>
        <a href="marx/index.htm">馬克思</a>
        <a href="engels/index.htm">恩格斯</a>
        <a href="lenin/index.htm">列寧</a>
    """.trimIndent()

    @Test
    fun missingFirstSampleUsesTheBackupOnTheSameMirror() = runBlocking {
        val requests = mutableListOf<String>()
        val result = checkMirror(source, 1) { path, _ ->
            requests += path
            when (path) {
                home -> reply(path, overview)
                first -> throw ArchiveProblem(ConnectionIssue.MISSING)
                backup -> reply(path, "<title>選集</title><h1>共產黨宣言</h1>")
                else -> error("Unexpected probe path")
            }
        }

        assertEquals(listOf(home, first, backup), requests)
        assertNull(result.problem)
        assertNull(result.sampleProblem)
        assertNotNull(result.homeMillis)
        assertEquals(2025, result.latestYear)
    }

    @Test
    fun aReachableHomepageKeepsItsLatencyWhenSamplesAreMissing() = runBlocking {
        val result = checkMirror(source, 1) { path, _ ->
            if (path == home) reply(path, overview) else throw ArchiveProblem(ConnectionIssue.MISSING)
        }

        assertNull(result.problem)
        assertNotNull(result.homeMillis)
        assertEquals(ProbeStatus.SAMPLE_MISSING, probeStatus(result, checking = false, online = true))
    }

    @Test
    fun homepageTimeoutAndSampleTimeoutHaveDifferentStatuses() = runBlocking {
        val homeTimeout = checkMirror(source, 1) { _, _ -> throw ArchiveProblem(ConnectionIssue.TIMEOUT) }
        val sampleTimeout = checkMirror(source, 1) { path, _ ->
            if (path == home) reply(path, overview) else throw ArchiveProblem(ConnectionIssue.TIMEOUT)
        }

        assertNull(homeTimeout.homeMillis)
        assertEquals(ProbeStatus.TIMEOUT, probeStatus(homeTimeout, false, true))
        assertNotNull(sampleTimeout.homeMillis)
        assertEquals(ProbeStatus.SAMPLE_TIMEOUT, probeStatus(sampleTimeout, false, true))
    }

    @Test
    fun aBrandedPageNeedsArchiveLinksBeforeTheSampleIsRequested() = runBlocking {
        val requests = mutableListOf<String>()
        val result = checkMirror(source, 1) { path, _ ->
            requests += path
            reply(path, "<title>中文马克思主义文库</title><a href='https://other.example.org/help'>帮助</a>")
        }

        assertEquals(listOf(home), requests)
        assertEquals(ConnectionIssue.WRONG_CONTENT, result.problem)
        assertEquals(ProbeStatus.WRONG_CONTENT, probeStatus(result, false, true))
    }

    @Test
    fun redirectingBothSamplesToTheHomepageDoesNotVerifyTheDocuments() = runBlocking {
        val result = checkMirror(source, 1) { path, _ ->
            if (path == home) reply(path, overview) else reply(home, "<title>哥达纲领批判 · 共产党宣言</title>")
        }
        assertNull(result.problem)
        assertEquals(ConnectionIssue.WRONG_CONTENT, result.sampleProblem)
        assertEquals(ProbeStatus.WRONG_CONTENT, probeStatus(result, false, true))
    }

    @Test
    fun mirrorRankingReadsEachSourcesStateOnlyOnceAndKeepsReachableFallbacks() {
        val confirmed = OfficialSources[1]
        val incomplete = OfficialSources[2]
        val untested = OfficialSources[3]
        val sources = listOf(untested, MainSource, incomplete, confirmed)
        val checks = mutableMapOf<String, Int>()
        val ranked = rankMirrors(sources) { candidate ->
            checks.merge(candidate.id, 1, Int::plus)
            val probe = when (candidate) {
                confirmed -> ProbeResult(1, 100, 3_500, 2024)
                incomplete -> ProbeResult(1, 100, 250, 2025, sampleProblem = ConnectionIssue.MISSING)
                else -> null
            }
            MirrorCandidate(candidate, missing = candidate == MainSource, failed = false, probe = probe)
        }

        assertEquals(listOf(confirmed, incomplete, untested), ranked)
        assertEquals(sources.associate { it.id to 1 }, checks)
    }

    private fun reply(path: String, html: String) =
        WebReply(source.address(path), html.toByteArray(), "text/html", "UTF-8", null, null, false)
}
