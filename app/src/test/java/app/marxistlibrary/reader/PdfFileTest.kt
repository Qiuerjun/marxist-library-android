package app.marxistlibrary.reader

import android.content.ContextWrapper
import app.marxistlibrary.reader.catalog.ArchiveFiles
import app.marxistlibrary.reader.reader.PdfPosition
import app.marxistlibrary.reader.reader.PdfSlice
import app.marxistlibrary.reader.catalog.ArchiveProgress
import app.marxistlibrary.reader.catalog.ArchiveStage
import app.marxistlibrary.reader.catalog.PageFormat
import app.marxistlibrary.reader.sources.ArchiveHttp
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.digest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfFileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun rangeReadsOnlyItsOwnBytes() {
        val sample = temp.newFile().apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4)) }
        val slice = requireNotNull(PdfSlice.within(sample.length(), 1, 4))
        assertEquals(3L, slice.length)
        slice.open(sample).use {
            assertEquals(1, it.read())
            assertArrayEquals(byteArrayOf(2, 3), it.readBytes())
            assertEquals(-1, it.read())
        }
    }

    @Test
    fun rejectsInvalidFileRanges() {
        assertNull(PdfSlice.within(9, null, 4))
        assertNull(PdfSlice.within(9, -1, 4))
        assertNull(PdfSlice.within(9, 4, 4))
        assertNull(PdfSlice.within(9, 8, 10))
    }

    @Test
    fun keepsLegacyBookmarksAndPageOffsets() {
        assertEquals(PdfPosition(1, 0), PdfPosition.from("", 0))
        assertEquals(PdfPosition(543, 4112), PdfPosition.from("pdf:542", 4112))
        assertEquals("pdf:542", PdfPosition(543).anchor)
        assertEquals(10_000, PdfPosition.from("pdf:4", Int.MAX_VALUE).offset)
    }

    @Test
    fun showsTransferPercentageOnlyForAKnownSize() {
        assertNull(ArchiveProgress(ArchiveStage.DOWNLOAD, 300).fraction)
        assertNull(ArchiveProgress(ArchiveStage.VERIFY).fraction)
        assertEquals(.75f, requireNotNull(ArchiveProgress(ArchiveStage.DOWNLOAD, 750, 1_000).fraction), 0f)
    }

    @Test
    fun resumedAttachmentCountsPreviouslySavedBytes() = runBlocking {
        val path = "/chinese/test.pdf"
        val full = "%PDF-1.4\nTest attachment body\n".toByteArray()
        val alreadyRead = 11
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = temp.root
        }
        val staging = File(temp.root, "archive/staging").apply { mkdirs() }
        val key = digest("${MainSource.id}:$path")
        File(staging, "$key.part").writeBytes(full.copyOfRange(0, alreadyRead))
        File(staging, "$key.etag").writeText("\"same-document\"")
        val http = ArchiveHttp(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=$alreadyRead-", chain.request().header("Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(206).message("Partial Content")
                .header("Content-Range", "bytes $alreadyRead-${full.lastIndex}/${full.size}")
                .header("ETag", "\"same-document\"")
                .body(full.copyOfRange(alreadyRead, full.size).toResponseBody("application/pdf".toMediaType()))
                .build()
        }.build())
        val stages = mutableListOf<ArchiveProgress>()
        val saved = ArchiveFiles(context).fetchAttachment(http, MainSource, path, PageFormat.PDF, null) { stages += it }
        assertArrayEquals(full, saved.file.readBytes())
        assertEquals(ArchiveProgress(ArchiveStage.DOWNLOAD, alreadyRead.toLong(), full.size.toLong()), stages.first())
        assertEquals(ArchiveStage.VERIFY, stages.last().stage)
    }
}
