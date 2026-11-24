package app.marxistlibrary.reader

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// Canonical paths identify documents and editions across mirrors.
@Serializable
@Entity(tableName = "catalog", indices = [Index("path"), Index("category")])
data class CatalogEntry(
    @PrimaryKey val key: String,
    val path: String,
    val anchor: String,
    val title: String,
    val author: String = "",
    val category: String = "",
    val kind: String = "document",
    val rank: Int = 0,
    val searchable: String = ""
)

@Entity(tableName = "directory_links", primaryKeys = ["path", "parent"])
data class DirectoryLink(val path: String, val parent: String, val parentTitle: String)

data class StoredFormat(val path: String, val format: String)

@Entity(tableName = "pages")
data class SavedPage(
    @PrimaryKey val path: String,
    val title: String,
    val format: String,
    val digest: String,
    val metadata: String,
    val origin: String,
    val tag: String?,
    val modified: String?,
    val fetchedAt: Long,
    val accessedAt: Long,
    val size: Long
)

@Serializable
@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val key: String,
    val path: String,
    val anchor: String,
    val title: String,
    val author: String,
    val updatedAt: Long
)

@Serializable
@Entity(tableName = "positions")
data class ReadingPosition(
    @PrimaryKey val edition: String,
    val path: String,
    val title: String,
    val digest: String,
    val anchor: String,
    val quote: String,
    val offset: Int,
    val updatedAt: Long
)

@Serializable
@Entity(tableName = "bookmarks", indices = [Index("path")])
data class Bookmark(
    @PrimaryKey val id: String,
    val edition: String,
    val path: String,
    val title: String,
    val digest: String,
    val anchor: String,
    val quote: String,
    val offset: Int,
    val selection: String,
    val note: String,
    val updatedAt: Long
)

@Entity(tableName = "mirrors")
data class CustomMirror(@PrimaryKey val id: String, val title: String, val base: String)

@Entity(tableName = "updates", indices = [Index("publishedAt")])
data class UpdateEntry(
    @PrimaryKey val key: String,
    val path: String,
    val anchor: String,
    val title: String,
    val publishedAt: Long
)

@Entity(tableName = "downloads", indices = [Index(value = ["key"], unique = true)])
data class DownloadTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,
    val root: String,
    val title: String,
    val recursive: Boolean = false,
    val state: String = "QUEUED",
    val error: String = "",
    val total: Int = 0,
    val count: Int = 0,
    val attempts: Int = 0,
    val retryAt: Long = 0,
    val generation: Int = 0,
    val updatedAt: Long
)

@Entity(tableName = "download_items", primaryKeys = ["taskId", "path"], indices = [Index("path")])
data class DownloadItem(
    val taskId: Int,
    val path: String,
    val title: String,
    val discovered: Boolean = false,
    val digest: String = "",
    val error: String = ""
)

@Entity(tableName = "paragraphs", indices = [Index("path")])
data class Paragraph(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val id: Long = 0,
    val path: String,
    val digest: String,
    val anchor: String,
    val text: String,
    val normalized: String,
    val terms: String
)

@Fts4(contentEntity = Paragraph::class, tokenizer = "unicode61")
@Entity(tableName = "paragraph_index")
data class ParagraphIndex(val terms: String)

data class ParagraphMatch(
    val id: Long,
    val path: String,
    val digest: String,
    val anchor: String,
    val text: String,
    val title: String?
)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun putSeedCatalog(entries: List<CatalogEntry>)
    @Upsert fun putCatalog(entries: List<CatalogEntry>)
    @Query("SELECT * FROM catalog WHERE category IN ('作者', '全集', '专题', '词典', '推荐') ORDER BY rank, key")
    fun homeCatalog(): Flow<List<CatalogEntry>>
    @Query("SELECT * FROM catalog WHERE path = :path ORDER BY CASE WHEN anchor = '' THEN 0 ELSE 1 END LIMIT 1")
    fun entry(path: String): CatalogEntry?
    @Query("SELECT * FROM catalog WHERE instr(searchable, :query) > 0 ORDER BY CASE WHEN title = :query THEN 0 ELSE 1 END, rank, key LIMIT 200")
    fun findCatalog(query: String): List<CatalogEntry>
    @Query("SELECT DISTINCT path FROM catalog WHERE kind = 'directory' AND path IN (:paths)")
    fun knownDirectories(paths: List<String>): List<String>

    @Upsert fun putDirectoryLinks(links: List<DirectoryLink>)
    @Query("SELECT count(*) FROM directory_links") fun directoryLinkCount(): Int
    @Query("SELECT * FROM directory_links WHERE path = :path ORDER BY parent") fun directoryParents(path: String): List<DirectoryLink>
    @Query("SELECT * FROM pages WHERE format = 'DIRECTORY'") fun directoryPages(): List<SavedPage>

    @Upsert fun putPage(page: SavedPage)
    @Query("SELECT * FROM pages WHERE path = :path") fun page(path: String): SavedPage?
    @Query("UPDATE pages SET accessedAt = :time WHERE path = :path") fun touchPage(path: String, time: Long)
    @Query("DELETE FROM pages WHERE path = :path AND digest = :digest") fun removePage(path: String, digest: String)
    @Query("SELECT * FROM pages ORDER BY accessedAt ASC") fun pages(): List<SavedPage>
    @Query("SELECT path, format FROM pages WHERE path IN (:paths)") fun savedFormats(paths: List<String>): List<StoredFormat>
    @Query("SELECT DISTINCT path FROM download_items WHERE digest != ''") fun pinnedPaths(): List<String>

    @Upsert fun putFavorite(favorite: Favorite)
    @Query("SELECT * FROM favorites ORDER BY updatedAt DESC") fun favorites(): Flow<List<Favorite>>
    @Query("SELECT * FROM favorites") fun allFavorites(): List<Favorite>
    @Query("DELETE FROM favorites WHERE `key` = :key") fun removeFavorite(key: String)

    @Upsert fun putBookmark(bookmark: Bookmark)
    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC") fun bookmarks(): Flow<List<Bookmark>>
    @Query("SELECT * FROM bookmarks WHERE path = :path ORDER BY updatedAt DESC") fun bookmarks(path: String): Flow<List<Bookmark>>
    @Query("SELECT * FROM bookmarks") fun allBookmarks(): List<Bookmark>
    @Query("DELETE FROM bookmarks WHERE id = :id") fun removeBookmark(id: String)

    @Upsert fun putPosition(position: ReadingPosition)
    @Query("SELECT * FROM positions WHERE edition = :edition") fun position(edition: String): ReadingPosition?
    @Query("SELECT * FROM positions ORDER BY updatedAt DESC LIMIT 100") fun positions(): Flow<List<ReadingPosition>>
    @Query("SELECT * FROM positions") fun allPositions(): List<ReadingPosition>
    @Query("DELETE FROM positions WHERE edition = :edition") fun removePosition(edition: String)

    @Upsert fun putMirror(mirror: CustomMirror)
    @Query("SELECT * FROM mirrors ORDER BY title") fun mirrors(): Flow<List<CustomMirror>>
    @Query("SELECT * FROM mirrors ORDER BY title") fun customMirrors(): List<CustomMirror>
    @Query("DELETE FROM mirrors WHERE id = :id") fun removeMirror(id: String)

    @Upsert fun putUpdates(entries: List<UpdateEntry>)
    @Query("SELECT * FROM updates ORDER BY publishedAt DESC LIMIT 120") fun updates(): Flow<List<UpdateEntry>>

    @Insert fun createTask(task: DownloadTask): Long
    @Upsert fun putTask(task: DownloadTask)
    @Query("SELECT * FROM downloads WHERE id = :id") fun task(id: Int): DownloadTask?
    @Query("SELECT * FROM downloads WHERE `key` = :key") fun task(key: String): DownloadTask?
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC") fun tasks(): Flow<List<DownloadTask>>
    @Query("SELECT * FROM downloads WHERE id = :id") fun observeTask(id: Int): Flow<DownloadTask?>
    @Query("DELETE FROM downloads WHERE id = :id") fun removeTask(id: Int)

    @Upsert fun putDownloadItems(items: List<DownloadItem>)
    @Query("SELECT * FROM download_items WHERE taskId = :taskId ORDER BY rowid") fun items(taskId: Int): List<DownloadItem>
    @Query("SELECT * FROM download_items WHERE taskId = :taskId ORDER BY rowid") fun observeItems(taskId: Int): Flow<List<DownloadItem>>
    @Query("DELETE FROM download_items WHERE taskId = :taskId") fun removeItems(taskId: Int)
    @Query("SELECT EXISTS(SELECT 1 FROM download_items WHERE path = :path AND digest = :digest)") fun isPinned(path: String, digest: String): Boolean
    @Query("UPDATE download_items SET digest = '', error = :error WHERE path = :path AND digest = :digest") fun invalidateItems(path: String, digest: String, error: String)
    @Query("SELECT DISTINCT taskId FROM download_items WHERE path = :path AND digest = :digest") fun relatedTasks(path: String, digest: String): List<Int>

    @Insert fun indexParagraphs(paragraphs: List<Paragraph>)
    @Query("DELETE FROM paragraphs WHERE path = :path") fun removeParagraphs(path: String)
    @Query(
        "SELECT paragraphs.rowid AS id, paragraphs.path, paragraphs.digest, paragraphs.anchor, " +
            "paragraphs.text, pages.title FROM paragraphs " +
            "JOIN paragraph_index ON paragraphs.rowid = paragraph_index.rowid " +
            "LEFT JOIN pages ON paragraphs.path = pages.path " +
            "WHERE paragraph_index MATCH :terms AND instr(paragraphs.normalized, :query) > 0 LIMIT 160"
    )
    fun findParagraphs(terms: String, query: String): List<ParagraphMatch>
}

@Database(
    entities = [
        CatalogEntry::class, DirectoryLink::class, SavedPage::class, Favorite::class, ReadingPosition::class,
        Bookmark::class, CustomMirror::class, UpdateEntry::class, DownloadTask::class,
        DownloadItem::class, Paragraph::class, ParagraphIndex::class
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

