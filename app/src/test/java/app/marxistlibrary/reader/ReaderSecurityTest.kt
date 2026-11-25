package app.marxistlibrary.reader

import app.marxistlibrary.reader.download.fitsStorage
import app.marxistlibrary.reader.library.LibraryBackup
import app.marxistlibrary.reader.library.checkForRestore
import app.marxistlibrary.reader.reader.EpubImageBudget
import app.marxistlibrary.reader.reader.epubImageFits
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.mirrorHostAllowed
import app.marxistlibrary.reader.sources.originalPageAddress
import app.marxistlibrary.reader.sources.trustedOriginalPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReaderSecurityTest {
    @Test
    fun onlyTrustedOriginalPagesStayInTheWebView() {
        fun trusted(url: String) = originalPageAddress(url)?.trustedOriginalPage()?.toString()
        assertEquals("https://www.marxists.org/chinese/", trusted("http://www.marxists.org/chinese/"))
        assertNotNull(trusted("https://marxists.info/chinese/"))
        assertNull(trusted("https://www.marxists.org.example/chinese/"))
        assertNull(trusted("https://www.marxists.org:8443/chinese/"))
        assertNull(trusted("https://reader@www.marxists.org/chinese/"))
        assertNull(trusted("file:///android_asset/reader.js"))
    }

    @Test
    fun catalogLinksMustFitArchivePathLimit() {
        assertNotNull(MainSource.reference(MainSource.address("/chinese/book.htm?edition=2")))
        assertNull(MainSource.reference(MainSource.address("/chinese/book.htm?q=" + "a".repeat(2500))))
    }

    @Test
    fun restoreAcceptsSmallClockSkewAndBoundsAuthors() {
        val now = Instant.parse("2026-11-25T04:00:00Z").toEpochMilli()
        val path = "/chinese/book.htm"
        val favorite = Favorite(path, path, "", "文献", "作者", now)
        val position = ReadingPosition(path, path, "文献", "a".repeat(64), "", "", 0, now + 30_000)
        val backup = LibraryBackup(exportedAt = Instant.ofEpochMilli(now).toString(),
            favorites = listOf(favorite), bookmarks = emptyList(), positions = listOf(position))

        assertEquals(now, backup.checkForRestore(now).positions.single().updatedAt)
        assertThrows(IllegalArgumentException::class.java) {
            backup.copy(positions = listOf(position.copy(updatedAt = now + 600_000))).checkForRestore(now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            backup.copy(favorites = listOf(favorite.copy(author = "字".repeat(AuthorLengthLimit + 1)))).checkForRestore(now)
        }
    }

    @Test
    fun epubImageBudgetCountsBytesAndChecksDimensions() {
        val budget = EpubImageBudget(32)
        assertTrue(budget.reserve(16))
        assertFalse(budget.reserve(17))
        assertFalse(budget.reserve(-1))
        assertTrue(budget.reserve(16))
        assertFalse(budget.reserve(1))
        assertTrue(epubImageFits(3000, 4000))
        assertFalse(epubImageFits(4000, 4000))
        assertFalse(epubImageFits(1, 0))
    }

    @Test
    fun numericMirrorHostsFollowTheDirectConnectionPolicy() {
        assertTrue(mirrorHostAllowed("8.8.8.8", false))
        assertFalse(mirrorHostAllowed("192.168.1.2", false))
        assertTrue(mirrorHostAllowed("192.168.1.2", true))
        assertFalse(mirrorHostAllowed("fd12::2", false))
        assertTrue(mirrorHostAllowed("fd12::2", true))
        assertFalse(mirrorHostAllowed("127.0.0.1", true))
        assertFalse(mirrorHostAllowed("127.1", true))
    }

    @Test
    fun downloadsLeaveSpaceForTheSystem() {
        val reserve = 128L * 1024 * 1024
        assertTrue(fitsStorage(reserve + 20, 20))
        assertFalse(fitsStorage(reserve + 19, 20))
        assertFalse(fitsStorage(reserve - 1, 0))
    }
}
