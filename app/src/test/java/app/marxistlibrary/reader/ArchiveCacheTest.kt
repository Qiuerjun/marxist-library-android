package app.marxistlibrary.reader

import android.content.ContextWrapper
import app.marxistlibrary.reader.catalog.ArchiveFiles
import app.marxistlibrary.reader.catalog.CacheBudget
import app.marxistlibrary.reader.catalog.Illustration
import app.marxistlibrary.reader.catalog.PageDetails
import app.marxistlibrary.reader.catalog.PageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveCacheTest {
    @get:Rule val workspace = TemporaryFolder()

    @Test
    fun cacheBudgetCountsPhotosAndCoversOnceAndReleasesThemWithTheLastPage() {
        val files = archive()
        val shared = Illustration("/chinese/art/illustration.png", "illustration", "png", "")
        val cover = Illustration("/chinese/art/cover.jpg", "cover", "jpg", "")
        files.image(shared.key, shared.extension).store(30)
        files.image(cover.key, cover.extension).store(50)
        val first = files.resources(
            PageFormat.ARTICLE, files.putBody("first"), PageDetails(illustrations = listOf(shared), cover = cover)
        )
        val second = files.resources(
            PageFormat.DIRECTORY, files.putBody("second"), PageDetails(illustrations = listOf(shared))
        )
        val budget = CacheBudget(listOf(first, second), emptySet())

        assertEquals(91L, budget.bytes)
        budget.remove(first)
        assertEquals(36L, budget.bytes)
        budget.remove(second)
        assertEquals(0L, budget.bytes)
    }

    @Test
    fun offlineFilesAreExcludedFromTheBudgetEvenWhenSharedByCachedPages() {
        val files = archive()
        val cover = Illustration("/chinese/art/cover.jpg", "cover", "jpg", "")
        val coverFile = files.image(cover.key, cover.extension).store(50)
        val pdf = files.attachment("pdf", PageFormat.PDF).store(40)
        val epub = files.attachment("epub", PageFormat.EPUB).store(20)
        val cache = listOf(
            files.resources(PageFormat.ARTICLE, files.putBody("body"), PageDetails(cover = cover)),
            files.resources(PageFormat.PDF, "pdf", PageDetails()),
            files.resources(PageFormat.EPUB, "epub", PageDetails())
        )
        val budget = CacheBudget(cache, setOf(coverFile, epub))

        assertEquals(44L, budget.bytes)
        budget.remove(cache[0])
        assertEquals(pdf.length(), budget.bytes)
        budget.remove(cache[1])
        assertEquals(0L, budget.bytes)
    }

    @Test
    fun sweepingDeletesRetiredFilesAndKeepsSharedOrNewlyPublishedFiles() {
        val files = archive()
        val startedAt = 1_700_000_000_000L
        val kept = files.image("shared", "png").store(5).apply { setLastModified(startedAt - 1_000) }
        val retired = files.body("retired").store(7).apply { setLastModified(startedAt - 1_000) }
        val fresh = files.attachment("republished", PageFormat.PDF).store(11).apply { setLastModified(startedAt + 1_000) }
        val orphaned = files.image("orphaned", "png").store(13).apply { setLastModified(startedAt - 180_000) }

        assertEquals(20L, files.sweep(setOf(kept), setOf(kept, retired, fresh), startedAt))
        assertTrue(kept.isFile)
        assertTrue(fresh.isFile)
        assertFalse(retired.exists())
        assertFalse(orphaned.exists())
    }

    private fun archive() = ArchiveFiles(object : ContextWrapper(null) {
        override fun getFilesDir(): File = workspace.root
    })

    private fun File.store(bytes: Int) = apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes))
    }
}
