package app.marxistlibrary.reader.library

import androidx.room.withTransaction
import app.marxistlibrary.reader.Bookmark
import app.marxistlibrary.reader.Favorite
import app.marxistlibrary.reader.LibraryDatabase
import app.marxistlibrary.reader.ReadingPosition
import app.marxistlibrary.reader.sources.ArchiveRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

@Serializable
data class LibraryBackup(
    val schemaVersion: Int = 1,
    val exportedAt: String,
    val favorites: List<Favorite>,
    val bookmarks: List<Bookmark>,
    val positions: List<ReadingPosition>
)

// The device owns reading records. Export and restore operate on the same Room transaction.
class ReadingLibrary(private val database: LibraryDatabase) {
    private val dao = database.library()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    val favorites = dao.favorites()
    val bookmarks = dao.bookmarks()
    val history = dao.positions()
    fun marks(path: String) = dao.bookmarks(path)

    suspend fun favorite(ref: ArchiveRef, title: String, author: String, selected: Boolean) = withContext(Dispatchers.IO) {
        if (selected) dao.putFavorite(Favorite(ref.key, ref.path, ref.anchor, title, author, System.currentTimeMillis()))
        else dao.removeFavorite(ref.key)
    }

    suspend fun mostRecent(edition: String) = withContext(Dispatchers.IO) { dao.position(edition) }

    suspend fun save(position: ReadingPosition) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if ((dao.position(position.edition)?.updatedAt ?: 0L) <= position.updatedAt) dao.putPosition(position)
        }
    }

    suspend fun addMark(position: ReadingPosition, selection: String = "", note: String = "") = withContext(Dispatchers.IO) {
        dao.putBookmark(Bookmark(UUID.randomUUID().toString(), position.edition, position.path, position.title,
            position.digest, position.anchor, position.quote, position.offset, selection.take(10_000),
            note.take(10_000), System.currentTimeMillis()))
    }

    suspend fun editMark(bookmark: Bookmark, note: String) = withContext(Dispatchers.IO) {
        dao.putBookmark(bookmark.copy(note = note.take(10_000), updatedAt = System.currentTimeMillis()))
    }

    suspend fun removeMark(id: String) = withContext(Dispatchers.IO) { dao.removeBookmark(id) }

    suspend fun removePosition(edition: String) = withContext(Dispatchers.IO) { dao.removePosition(edition) }

    suspend fun export(): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            json.encodeToString(LibraryBackup(exportedAt = Instant.now().toString(),
                favorites = dao.allFavorites(), bookmarks = dao.allBookmarks(), positions = dao.allPositions()))
        }
    }

    suspend fun extract(input: String): LibraryBackup = withContext(Dispatchers.Default) {
        val backup = json.decodeFromString<LibraryBackup>(input)
        require(backup.schemaVersion == 1) { "暂未支持这个备份版本" }
        require(runCatching { Instant.parse(backup.exportedAt) }.isSuccess) { "备份时间格式有误" }
        require(backup.favorites.size + backup.bookmarks.size + backup.positions.size <= 60_000) { "备份记录过多" }
        require(backup.favorites.all { ref(it.path, it.anchor)?.key == it.key && text(it.title) && it.updatedAt > 0 })
        require(backup.bookmarks.all {
            ref(it.path, it.anchor) != null && ref(it.edition, "") != null && text(it.title) &&
                it.id.length in 1..100 && it.offset >= 0 && it.updatedAt > 0 &&
                it.selection.length <= 10_000 && it.note.length <= 10_000 && it.quote.length <= 500 && sha(it.digest)
        })
        require(backup.positions.all {
            ref(it.path, it.anchor) != null && ref(it.edition, "") != null && text(it.title) &&
                it.offset >= 0 && it.updatedAt > 0 && it.quote.length <= 500 && sha(it.digest)
        })
        backup
    }

    suspend fun merge(backup: LibraryBackup) = withContext(Dispatchers.IO) {
        database.withTransaction {
            fun <T> latest(existing: List<T>, imported: List<T>, key: (T) -> String, time: (T) -> Long): List<T> {
                val index = existing.associateBy(key).toMutableMap()
                imported.forEach { item ->
                    val id = key(item)
                    val old = index[id]
                    if (old == null || time(item) > time(old)) index[id] = item
                }
                return index.values.toList()
            }
            latest(dao.allFavorites(), backup.favorites, Favorite::key, Favorite::updatedAt).forEach(dao::putFavorite)
            latest(dao.allBookmarks(), backup.bookmarks, Bookmark::id, Bookmark::updatedAt).forEach(dao::putBookmark)
            latest(dao.allPositions(), backup.positions, ReadingPosition::edition, ReadingPosition::updatedAt).forEach(dao::putPosition)
        }
    }

    suspend fun exportNotes(): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("# 阅读笔记")
            dao.allBookmarks().filter { it.note.isNotBlank() || it.selection.isNotBlank() }
                .sortedByDescending { it.updatedAt }.forEach { bookmark ->
                    appendLine()
                    appendLine("## " + bookmark.title.replace('\n', ' ').replace('\r', ' '))
                    bookmark.selection.lineSequence().filter(String::isNotBlank).forEach { appendLine("> $it") }
                    if (bookmark.note.isNotBlank()) appendLine(bookmark.note)
                    appendLine()
                    appendLine("[文献原文](${ArchiveRef(bookmark.path).original})")
                }
        }
    }

    private fun ref(path: String, anchor: String): ArchiveRef? {
        if (anchor.length > 500) return null
        return ArchiveRef.parse(path)?.takeIf { it.path == path && it.anchor.isEmpty() }?.copy(anchor = anchor)
    }

    private fun text(title: String) = title.isNotBlank() && title.length <= 500
    private fun sha(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

