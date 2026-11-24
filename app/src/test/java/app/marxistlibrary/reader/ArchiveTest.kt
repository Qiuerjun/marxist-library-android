package app.marxistlibrary.reader

import app.marxistlibrary.reader.catalog.ArchiveMarkup
import app.marxistlibrary.reader.catalog.PageFormat
import app.marxistlibrary.reader.catalog.directoryAncestors
import app.marxistlibrary.reader.search.ChineseText
import app.marxistlibrary.reader.sources.ArchiveHttp
import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ArchiveRef
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.ProbeResult
import app.marxistlibrary.reader.sources.ProbeStatus
import app.marxistlibrary.reader.sources.Source
import app.marxistlibrary.reader.sources.WebReply
import app.marxistlibrary.reader.sources.probeStatus
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class ArchiveTest {
    @Test
    fun prefixedMirrorAndOriginalHaveTheSameReaderAddress() {
        val source = requireNotNull(Source.custom("镜像", "https://books.example.org/mia/chinese/"))
        val original = ArchiveRef.fromUrl("http://marxists.org/chinese/lenin/doc.htm?q=1#注")
        val mirrored = ArchiveRef.fromUrl("https://books.example.org/mia/chinese/lenin/doc.htm?q=1#注", listOf(source))
        assertEquals(original, mirrored)
        assertEquals("/chinese/lenin/doc.htm?q=1#注", mirrored?.key)
        assertEquals("/chinese/", ArchiveRef.parse("/chinese/index.html")?.path)
    }

    @Test
    fun sourcePathsLimitRedirectsAndNormalizeDotSegments() {
        assertNull(ArchiveRef.parse("/chinese/%2e%2e/admin"))
        assertNull(ArchiveRef.parse("//books.example.org/chinese/"))
        assertNull(ArchiveRef.fromUrl("https://www.marxists.org.evil.example/chinese/"))
        assertFalse(MainSource.accepts(requireNotNull(MainSource.base.resolve("/download/"))))
        assertNull(Source.custom("凭据", "https://alice:p@mirror.example.org/"))
        assertNull(Source.custom("明文", "http://mirror.example.org/"))
    }

    @Test
    fun legacyArticleKeepsFootnotesAndLocalImagesWithoutSourceScripts() {
        val article = """
            <html><head><title>排版测试文献</title></head><body>
            <h1>排版测试文献</h1>
            <p>这里有足够长的独立段落，用来验证古旧编码和正文内容。</p>
            <p><a href="#sub">正文小节标题</a></p>
            <a name="sub"></a><p>这里是第一个小节。<sup><a href="#note">[1]</a></sup></p>
            <p><a name="note"></a>这是注释。<a href="#sub">返回正文</a></p>
            <img src="a.jpg" alt="图版"><img src="https://other.example.org/beacon.jpg" alt="站外插图">
            <script>alert(1)</script><a href="javascript:alert(2)">跳转</a>
            </body></html>
        """.trimIndent()
        val ref = ArchiveRef("/chinese/samples/a.htm")
        val result = ArchiveMarkup.parse(ref, MainSource, response(ref, article, "GB18030"))
        assertEquals(PageFormat.ARTICLE, result.format)
        assertEquals("排版测试文献", result.title)
        assertTrue(result.html.contains("id=\"note\""))
        assertTrue(result.html.contains("href=\"#note\""))
        assertTrue(result.details.headings.any { it.id == "sub" })
        assertTrue(result.html.contains("data-reading-anchor"))
        assertTrue(result.html.contains("/image/"))
        assertFalse(result.html.contains("javascript:"))
        assertFalse(result.html.contains("<script"))
        assertFalse(result.html.contains("beacon.jpg"))
        assertEquals("/chinese/samples/a.jpg", result.details.illustrations.single().path)
    }

    @Test
    fun directoryCollapsesIdenticalLinksAndKeepsSeparateAnchors() {
        val index = """
            <html><head><title>阅读测试目录</title></head><body>
            <a href="/chinese/">中文马克思主义文库</a> → <a href="/chinese/author/index.htm">作者</a>
            相关链接：<a href="/chinese/other/index.htm">相关作品</a>
            <hr><h1>阅读测试目录</h1>
            <a href="1.htm">第一章……47―101</a><a href="1.htm">热键重复链接</a>
            <a href="2.htm#sub">第二章甲</a><a href="2.htm#next">第二章乙</a>
            <a href="index-next.htm">下一章</a><a href="contents.htm">回目录</a>
            </body></html>
        """.trimIndent()
        val ref = ArchiveRef("/chinese/sample/index.htm")
        val result = ArchiveMarkup.parse(ref, MainSource, response(ref, index))
        assertEquals(PageFormat.DIRECTORY, result.format)
        assertEquals(listOf("/chinese/", "/chinese/author/index.htm"), result.parents.map { it.ref.path })
        assertEquals(listOf("/chinese/sample/1.htm", "/chinese/sample/2.htm#sub", "/chinese/sample/2.htm#next"),
            result.details.chapters.map { it.ref.key })
        assertEquals("第一章", result.details.chapters.first().title)
    }

    @Test
    fun directoryTrailKeepsTheOpenedBranchAndStopsAtCycles() {
        val root = "/chinese/"
        val topic = "/chinese/topic.htm"
        val related = "/chinese/related.htm"
        val book = "/chinese/book/index.htm"
        val chapter = "/chinese/book/part/index.htm"
        val links = listOf(
            DirectoryLink(chapter, book, "文集"),
            DirectoryLink(book, topic, "专题"),
            DirectoryLink(book, related, "参考文献"),
            DirectoryLink(topic, root, "文库"),
            DirectoryLink(related, root, "文库"),
            DirectoryLink(topic, book, "文集")
        ).groupBy { it.path }
        fun parents(path: String) = links[path].orEmpty()
        assertEquals(listOf(root, related, book),
            directoryAncestors(chapter, listOf(root, related, book), ::parents).map { it.parent })
        assertEquals(listOf(root, topic, book), directoryAncestors(chapter, emptyList(), ::parents).map { it.parent })
    }

    @Test
    fun anIndexWithLongIntroductionsStillOpensAsADirectory() {
        val ref = ArchiveRef("/chinese/sample/index-topic.htm")
        val page = "<title>专题索引页</title>" +
            (1..8).joinToString("") { """<p><a href="$it.htm">专题条目 $it</a></p>""" } +
            (1..6).joinToString("") { "<p>${"本段是目录中每个主题的导言。".repeat(25)}</p>" }
        val parsed = ArchiveMarkup.parse(ref, MainSource, response(ref, page))
        assertEquals(PageFormat.DIRECTORY, parsed.format)
        assertEquals(8, parsed.details.chapters.size)
    }

    @Test
    fun contentsFollowTheTextAndKeepRepeatedHeadingsAtDifferentLocations() {
        val ref = ArchiveRef("/chinese/test.htm")
        val result = ArchiveMarkup.parse(ref, MainSource, response(ref, """
            <title>章节测试</title>
            <a href="#late">另一处小节</a><a href="#%E4%B8%AD%E9%97%B4">中间一节</a><a href="#alias">带日期的小节链接</a>
            <a name="alias"></a><br><hr><h2>正文小节<sup><a href="#_ftn13">[13]</a></sup></h2>
            <p>第一处正文内容，用来核对页面内链接的位置。</p>
            <p><a id="中间"></a>中间段落的实际正文。</p>
            <h2 id="late">正文小节</h2>
            <a name="_ftn13"></a><p>这里是正文的脚注。</p>
        """.trimIndent()))
        assertEquals(listOf("正文小节", "中间一节", "正文小节"), result.details.headings.map { it.title })
        assertEquals(listOf("中间", "late"), result.details.headings.drop(1).map { it.id })
        assertTrue(result.html.contains("id=\"alias\""))
        val old = result.details.copy(headings = emptyList(), markupVersion = 0)
        assertEquals(result.details, ArchiveMarkup.upgradeDetails(old) { result.html })
    }

    @Test
    fun usesCoverCaptionFromLegacyHtmlAndIgnoresOutsidePreviewImages() {
        val ref = ArchiveRef("/chinese/samples/book.htm")
        val parsed = ArchiveMarkup.parse(ref, MainSource, response(ref, """
            <title>历史文献</title>
            <meta property="og:image" content="https://example.net/chinese/fake.jpg">
            <img src="site-logo.png" width="200" height="250">
            <center><img src="1.jpg"><br>书籍封面</center>
            <center><img src="cover.jpg"><br>封底</center>
            <p>这是一篇带有原书封面图和其它插图的文献。</p>
        """.trimIndent()))
        assertEquals("/chinese/samples/1.jpg", parsed.details.cover?.path)
        assertEquals(parsed.details.cover, ArchiveMarkup.upgradeDetails(parsed.details.copy(cover = null, markupVersion = 0)) {
            parsed.html
        }.cover)
    }

    @Test
    fun keepsDeclaredSameSourceCoversSeparateFromOrdinaryIllustrations() {
        val ref = ArchiveRef("/chinese/samples/story.htm")
        val body = "<title>一篇文章</title><p>这里有一段用来验证图文识别的正文。</p><img src='chart.jpg' width='500' height='220'>"
        val plain = ArchiveMarkup.parse(ref, MainSource, response(ref, body))
        assertNull(plain.details.cover)
        val described = ArchiveMarkup.parse(ref, MainSource, response(ref,
            "<meta property='og:image' content='scan.jpg'>$body"))
        assertEquals("/chinese/samples/scan.jpg", described.details.cover?.path)
        assertEquals("/chinese/samples/chart.jpg", described.details.illustrations.single().path)
        assertEquals(2, described.details.imageKeys.size)
    }

    @Test
    fun mirrorStatusUsesLatencyOnlyForSuccessfulChecks() {
        val fast = ProbeResult(1, 100, 2499)
        val slow = fast.copy(duration = 2500)
        assertEquals(ProbeStatus.AVAILABLE, probeStatus(fast, checking = false, online = true))
        assertEquals(ProbeStatus.SLOW, probeStatus(slow, checking = false, online = true))
        assertEquals(ProbeStatus.CHECKING, probeStatus(fast, checking = true, online = true))
        assertEquals(ProbeStatus.OFFLINE, probeStatus(fast, checking = false, online = false))
        assertEquals(ProbeStatus.UNTESTED, probeStatus(null, checking = false, online = true))
        assertEquals(ProbeStatus.TIMEOUT, probeStatus(slow.copy(problem = ConnectionIssue.TIMEOUT), false, true))
        assertEquals(ProbeStatus.INCOMPLETE, probeStatus(slow.copy(problem = ConnectionIssue.MISSING), false, true))
        assertEquals(ProbeStatus.FAILED, probeStatus(fast.copy(problem = ConnectionIssue.CERTIFICATE), false, true))
    }

    @Test
    fun aDoorPageDoesNotReplaceTheRequestedDocument() {
        val html = "<html><title>Just a moment</title><body>Verifying the connection with this host</body></html>"
        val ref = ArchiveRef("/chinese/sample/index.htm")
        val failure = runCatching { ArchiveMarkup.parse(ref, MainSource, response(ref, html)) }.exceptionOrNull() as? ArchiveProblem
        assertEquals(ConnectionIssue.WRONG_CONTENT, failure?.issue)
    }

    @Test
    fun chineseBigramQueryUsesNormalizedUnicodeAndBoundTerms() {
        val chinese = ChineseText.fromLines(sequenceOf("國\t国", "與\t与", "論\t论"))
        val text = chinese.fold("國家與論述　ＡＢＣ（２０２６）")
        assertEquals("国家与论述 abc 2026", text)
        assertEquals("\"国家\" AND \"家与\" AND \"与论\" AND \"论述\" AND \"abc\" AND \"2026\"", chinese.matchQuery(text))
        assertEquals("", chinese.matchQuery(chinese.fold("國")))
    }

    @Test
    fun archiveNeverFollowsRedirectsOutsideTheConfiguredSource() = runBlocking {
        val http = http(302, "Location" to "https://example.net/chinese/elsewhere.htm")
        val result = runCatching { http.get(MainSource, "/chinese/article.htm", 120) }.exceptionOrNull() as? ArchiveProblem
        assertEquals(ConnectionIssue.WRONG_CONTENT, result?.issue)
    }

    @Test
    fun archiveUsesConditionalRequestsAndReportsRateLimits() = runBlocking {
        val limited = http(429, "Retry-After" to "104")
        val error = runCatching { limited.get(MainSource, "/chinese/article.htm", 120) }.exceptionOrNull() as? ArchiveProblem
        assertEquals(ConnectionIssue.LIMITED, error?.issue)
        assertEquals(104L, error?.retrySeconds)
        val unchanged = http(304, "ETag" to "\"revised\"").get(MainSource, "/chinese/article.htm", 120, "\"revised\"")
        assertTrue(unchanged.unchanged)
        assertEquals(0, unchanged.bytes.size)
        assertNotNull(unchanged.tag)
    }

    private fun response(ref: ArchiveRef, html: String, charset: String = "UTF-8") =
        WebReply(MainSource.address(ref.path), html.toByteArray(Charset.forName(charset)), "text/html", charset, null, null, false)

    private fun http(code: Int, header: Pair<String, String>): ArchiveHttp = ArchiveHttp(
        OkHttpClient.Builder().followRedirects(false).addInterceptor { incoming ->
            Response.Builder().request(incoming.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").header(header.first, header.second)
                .body("fixture".toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
    )
}
