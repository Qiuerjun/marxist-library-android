package app.marxistlibrary.reader

import android.content.ContextWrapper
import app.marxistlibrary.reader.catalog.ArchiveFiles
import app.marxistlibrary.reader.catalog.ArchiveProgress
import app.marxistlibrary.reader.catalog.ArchiveStage
import app.marxistlibrary.reader.catalog.PageFormat
import app.marxistlibrary.reader.sources.ArchiveHttp
import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.digest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveDownloadTest {
    @get:Rule val workspace = TemporaryFolder()
    private val path = "/chinese/sample/test.pdf"
    private val pdf = "%PDF-1.4\nfixture data\n%%EOF\n".toByteArray()

    @Test
    fun matchingRangeResumesAtTheSavedOffsetAndPublishesTheCompleteFile() = runBlocking {
        val saved = 11
        val files = archive()
        val (part, tag) = partial(pdf.copyOfRange(0, saved))
        val progress = mutableListOf<ArchiveProgress>()
        val http = transport { request ->
            assertEquals("bytes=$saved-", request.header("Range"))
            assertEquals("\"first\"", request.header("If-Range"))
            reply(request, 206, pdf.copyOfRange(saved, pdf.size))
                .header("Content-Range", "bytes $saved-${pdf.lastIndex}/${pdf.size}")
                .header("ETag", "\"first\"")
        }

        val complete = files.fetchAttachment(http, MainSource, path, PageFormat.PDF, null, progress::add)
        assertArrayEquals(pdf, complete.file.readBytes())
        assertEquals(digest(pdf), complete.digest)
        assertEquals(ArchiveProgress(ArchiveStage.DOWNLOAD, saved.toLong(), pdf.size.toLong()), progress.first())
        assertEquals(ArchiveStage.VERIFY, progress.last().stage)
        assertFalse(part.exists())
        assertFalse(tag.exists())
    }

    @Test
    fun rejectedRangeReopensTheDownloadWithoutTheOldValidator() = runBlocking {
        val files = archive()
        partial("previous bytes".toByteArray())
        val requested = mutableListOf<String?>()
        val http = transport { request ->
            val range = request.header("Range")
            requested += range
            if (range != null) reply(request, 416, ByteArray(0))
            else {
                assertNull(request.header("If-Range"))
                reply(request, 200, pdf).header("ETag", "\"updated\"")
            }
        }

        val complete = files.fetchAttachment(http, MainSource, path, PageFormat.PDF, null) {}
        assertEquals(listOf("bytes=14-", null), requested)
        assertArrayEquals(pdf, complete.file.readBytes())
        assertEquals("\"updated\"", complete.tag)
    }

    @Test
    fun anHtmlErrorBodyCannotBePublishedAsAnOfflinePdf() = runBlocking {
        val files = archive()
        val body = "<html>Not a PDF</html>".toByteArray()
        val http = transport { reply(it, 200, body) }

        val failure = runCatching {
            files.fetchAttachment(http, MainSource, path, PageFormat.PDF, null) {}
        }.exceptionOrNull() as ArchiveProblem
        assertEquals(ConnectionIssue.WRONG_CONTENT, failure.issue)
        assertFalse(files.exists(PageFormat.PDF, digest(body)))
        assertEquals(0L, files.ownedBytes())
    }

    private fun archive() = ArchiveFiles(object : ContextWrapper(null) {
        override fun getFilesDir(): File = workspace.root
    })

    private fun partial(bytes: ByteArray): Pair<File, File> {
        val stage = File(workspace.root, "archive/staging").apply { mkdirs() }
        val key = digest("${MainSource.id}:$path")
        val part = File(stage, "$key.part").apply { writeBytes(bytes) }
        val tag = File(stage, "$key.etag").apply { writeText("\"first\"") }
        return part to tag
    }

    private fun transport(responses: (Request) -> Response.Builder) = ArchiveHttp(
        OkHttpClient.Builder().addInterceptor { chain -> responses(chain.request()).build() }.build()
    )

    private fun reply(request: Request, code: Int, data: ByteArray) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(data.toResponseBody("application/pdf".toMediaType()))
}
