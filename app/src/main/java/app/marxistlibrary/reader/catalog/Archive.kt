package app.marxistlibrary.reader.catalog

import android.net.Network
import android.util.Xml
import androidx.room.withTransaction
import app.marxistlibrary.reader.CatalogEntry
import app.marxistlibrary.reader.DirectoryLink
import app.marxistlibrary.reader.LibraryDatabase
import app.marxistlibrary.reader.Paragraph
import app.marxistlibrary.reader.ParagraphMatch
import app.marxistlibrary.reader.SavedPage
import app.marxistlibrary.reader.UpdateEntry
import app.marxistlibrary.reader.search.ChineseText
import app.marxistlibrary.reader.settings.CoverStyle
import app.marxistlibrary.reader.settings.Settings
import app.marxistlibrary.reader.sources.ArchiveHttp
import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ArchiveRef
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.Mirrors
import app.marxistlibrary.reader.sources.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class OpenPage(
    val ref: ArchiveRef,
    val title: String,
    val format: PageFormat,
    val details: PageDetails,
    val source: String = "",
    val digest: String = "",
    val file: File? = null,
    val downloaded: Boolean = false
) {
    val chapters: List<Chapter> = details.chapters.map { it.copy(title = it.title.chapterTitle()) }
}

// Browse and download both resolve the same canonical page and local file.
class Archive(
    private val database: LibraryDatabase,
    private val seeds: SeedCatalog,
    val files: ArchiveFiles,
    private val chinese: ChineseText,
    private val settings: Settings,
    private val mirrors: Mirrors,
    private val http: ArchiveHttp
) {
    private val dao = database.library()
    private val pages = Array(48) { Mutex() }
    private val imageSlots = Semaphore(3)
    private val cacheTrim = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun installSeed() = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.putSeedCatalog(seeds.entries())
            if (dao.directoryLinkCount() == 0) {
                dao.directoryPages().forEach { page ->
                    recordDirectoryLinks(page.path, page.title, decode(page.metadata).chapters)
                }
            }
            dao.putDirectoryLinks(seeds.directoryLinks())
        }
    }

    fun seedEntries() = seeds.entries()

    fun seedPreview(ref: ArchiveRef): OpenPage? = seedPage(ref)

    suspend fun documentFormats(paths: Set<String>): Map<String, PageFormat> = withContext(Dispatchers.IO) {
        buildMap {
            paths.chunked(400).forEach { group ->
                dao.knownDirectories(group).forEach { path ->
                    val inferred = PageFormat.fromPath(path)
                    put(path, if (inferred == PageFormat.ARTICLE) PageFormat.DIRECTORY else inferred)
                }
                dao.savedFormats(group).forEach { item ->
                    val stored = PageFormat.valueOf(item.format)
                    put(item.path, if (stored == PageFormat.ARTICLE && seeds.isDirectory(item.path)) PageFormat.DIRECTORY else stored)
                }
            }
        }
    }

    suspend fun directoryTrail(path: String, visitedFrom: List<String>): List<Chapter> = withContext(Dispatchers.IO) {
        val ancestors = directoryAncestors(path, visitedFrom) { child ->
            val recorded = dao.directoryParents(child).sortedBy { it.parent == LibraryDirectory.ref.path }
            (listOfNotNull(seeds.parent(child)) + recorded).distinctBy { it.parent }
        }.mapNotNull { link ->
            if (link.parent == LibraryDirectory.ref.path) return@mapNotNull LibraryDirectory
            val title = seeds.title(link.parent) ?: dao.entry(link.parent)?.title?.takeUnless { it.isNavigationLabel() }
                ?: dao.page(link.parent)?.title ?: link.parentTitle
            title.takeIf(String::isNotBlank)?.let { Chapter(ArchiveRef(link.parent), it) }
        }
        if (path == LibraryDirectory.ref.path || ancestors.firstOrNull() == LibraryDirectory) ancestors
        else listOf(LibraryDirectory) + ancestors
    }

    suspend fun open(
        ref: ArchiveRef,
        force: Boolean = false,
        forDownload: Boolean = false,
        network: Network? = null,
        progress: (ArchiveProgress) -> Unit = {}
    ): OpenPage = withContext(Dispatchers.IO) {
        progress(ArchiveProgress(ArchiveStage.CACHE))
        val key = (ref.path.hashCode() and Int.MAX_VALUE) % pages.size
        pages[key].withLock {
            val old = dao.page(ref.path)
            val cached = old?.let { fromLocal(ref, it) }
            if ((!force || cached?.downloaded == true) && cached != null) {
                dao.touchPage(ref.path, System.currentTimeMillis())
                if (forDownload) ensureIllustrations(cached, network)
                return@withLock cached
            }
            val page = try {
                val format = PageFormat.fromPath(ref.path).takeIf { it == PageFormat.PDF || it == PageFormat.EPUB }
                if (format == null) withTimeout(16_000) {
                    fromSources(ref.path) {
                        progress(ArchiveProgress(ArchiveStage.CONNECT))
                        fetchHtml(ref, it, old, network, progress)
                    }
                } else fromSources(ref.path) {
                    progress(ArchiveProgress(ArchiveStage.CONNECT))
                    fetchAttachment(ref, it, format, network, progress)
                }
            } catch (problem: TimeoutCancellationException) {
                if (force || forDownload) throw ArchiveProblem(ConnectionIssue.TIMEOUT)
                return@withLock cached ?: seedPage(ref) ?: throw ArchiveProblem(ConnectionIssue.TIMEOUT)
            } catch (problem: ArchiveProblem) {
                if (force || forDownload) throw problem
                return@withLock cached ?: seedPage(ref) ?: throw problem
            }
            if (forDownload) ensureIllustrations(page, network)
            page
        }
    }

    suspend fun warmIllustrations(page: OpenPage): Boolean = withContext(Dispatchers.IO) {
        val wanted = page.details.illustrations.filterNot { files.image(it.key, it.extension).isFile }
        if (wanted.isEmpty() || !mirrors.online()) return@withContext false
        val source = mirrors.all().find { it.id == page.source } ?: MainSource
        coroutineScope {
            wanted.map { illustration ->
                async {
                    withTimeoutOrNull(9000) {
                        try {
                            fetchImage(source, illustration, null)
                            true
                        } catch (_: ArchiveProblem) {
                            false
                        }
                    } ?: false
                }
            }.awaitAll().any { it }
        }
    }

    suspend fun coverFile(path: String): File? = withContext(Dispatchers.IO) {
        if (settings.current().coverStyle == CoverStyle.TEXT) return@withContext null
        val ref = ArchiveRef.parse(path) ?: return@withContext null
        val extension = ref.path.substringBefore('?').substringAfterLast('.').lowercase()
        if (extension in setOf("pdf", "epub") || !ArchiveMarkup.supportedPath(ref.path)) return@withContext null
        val page = withTimeoutOrNull(6500) {
            try {
                open(ref)
            } catch (_: ArchiveProblem) {
                null
            }
        } ?: return@withContext null
        coverFile(page)
    }

    suspend fun coverFile(page: OpenPage, network: Network? = null): File? = withContext(Dispatchers.IO) {
        val cover = page.details.cover ?: return@withContext null
        val file = files.image(cover.key, cover.extension)
        if (file.isFile) return@withContext file
        if (!mirrors.online()) return@withContext null
        val source = mirrors.all().find { it.id == page.source } ?: MainSource
        withTimeoutOrNull(6500) {
            try {
                fetchImage(source, cover, network)
                file
            } catch (_: ArchiveProblem) {
                null
            }
        }
    }

    suspend fun paragraphs(page: OpenPage): List<Paragraph>? = withContext(Dispatchers.IO) {
        if (page.format != PageFormat.ARTICLE || page.file == null) return@withContext null
        val nodes = Jsoup.parseBodyFragment(page.file.readText()).select("[data-reading-anchor]")
        nodes.take(2500).mapNotNull { node ->
            val text = node.text().trim().take(8_000)
            val normalized = chinese.fold(text)
            if (normalized.length < 2) return@mapNotNull null
            Paragraph(path = page.ref.path, digest = page.digest, anchor = node.id(), text = text,
                normalized = normalized, terms = chinese.terms(normalized).joinToString(" "))
        }
    }

    suspend fun findCatalog(query: String): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val keyword = chinese.fold(query).take(120)
        if (keyword.isBlank()) return@withContext emptyList()
        dao.findCatalog(keyword).filterNot { it.title.isNavigationLabel() }.sortedBy {
            when {
                chinese.fold(it.title) == keyword -> 0
                chinese.fold(it.title).contains(keyword) -> 1
                else -> 2
            }
        }
    }

    suspend fun findParagraphs(query: String): List<ParagraphMatch> = withContext(Dispatchers.IO) {
        val keyword = chinese.fold(query).take(120)
        val terms = chinese.matchQuery(keyword)
        if (terms.isBlank()) emptyList() else dao.findParagraphs(terms, keyword)
    }

    suspend fun entry(path: String) = withContext(Dispatchers.IO) {
        dao.entry(path)?.takeUnless { it.title.isNavigationLabel() }
    }

    suspend fun refreshCatalog(progress: (ArchiveProgress) -> Unit = {}): Int =
        open(ArchiveRef("/chinese/"), force = true, progress = progress).chapters.size

    suspend fun refreshUpdates(): Int = withContext(Dispatchers.IO) {
        val entries = withTimeout(16_000) {
            fromSources("/chinese/feed.xml") { source ->
                val reply = http.get(source, "/chinese/feed.xml", 900 * 1024)
                parseRss(reply.bytes, source)
            }
        }
        dao.putUpdates(entries)
        settings.lastUpdate(Instant.now().toString())
        entries.size
    }

    suspend fun cacheSize() = withContext(Dispatchers.IO) { files.ownedBytes() }

    suspend fun trimCache(clear: Boolean = false, releasedPaths: Set<String> = emptySet()): Long = withContext(Dispatchers.IO) {
        cacheTrim.withLock {
            val startedAt = System.currentTimeMillis()
            val (saved, protectedPaths) = database.withTransaction { dao.pages() to dao.cacheProtectedPaths().toSet() }
            val filesByPath = saved.associate { it.path to resources(it) }
            val protectedFiles = protectedPaths.flatMapTo(mutableSetOf()) { filesByPath[it].orEmpty() }
            val removable = saved.filterNot { it.path in protectedPaths }
            val budget = CacheBudget(removable.map { filesByPath.getValue(it.path) }, protectedFiles)
            val retired = mutableSetOf<File>()
            val cap = 128L * 1024 * 1024
            for (page in removable) {
                val requested = clear || page.path in releasedPaths
                if (!requested && budget.bytes <= cap) continue
                if (evictPage(page, requested)) {
                    val removed = filesByPath.getValue(page.path)
                    budget.remove(removed)
                    retired += removed
                }
            }
            // Recheck references before deleting files shared by multiple pages.
            database.withTransaction {
                val kept = dao.pages().flatMapTo(mutableSetOf(), ::resources)
                files.sweep(kept, retired, startedAt)
            }
        }
    }

    private suspend fun evictPage(page: SavedPage, requested: Boolean): Boolean {
        val key = (page.path.hashCode() and Int.MAX_VALUE) % pages.size
        return pages[key].withLock {
            database.withTransaction {
                val current = dao.page(page.path) ?: return@withTransaction false
                if (current.digest != page.digest || current.metadata != page.metadata ||
                    !requested && current.accessedAt != page.accessedAt || dao.isCacheProtected(page.path)) {
                    return@withTransaction false
                }
                dao.removePage(page.path, page.digest)
                dao.removeParagraphs(page.path)
                true
            }
        }
    }

    private fun resources(page: SavedPage): Set<File> =
        files.resources(PageFormat.valueOf(page.format), page.digest, decode(page.metadata))

    private fun fromLocal(ref: ArchiveRef, saved: SavedPage): OpenPage? {
        val storedFormat = PageFormat.valueOf(saved.format)
        if (!files.exists(storedFormat, saved.digest)) {
            val affected = dao.relatedTasks(saved.path, saved.digest)
            dao.invalidateItems(saved.path, saved.digest, "文件缺失，点按重试")
            affected.mapNotNull(dao::task).forEach { task ->
                dao.putTask(task.copy(state = "PARTIAL", count = dao.items(task.id).count { it.digest.isNotBlank() },
                    error = "文件缺失，点按重试"))
            }
            dao.removeParagraphs(saved.path)
            return null
        }
        val attachment = storedFormat == PageFormat.PDF || storedFormat == PageFormat.EPUB
        val file = files.document(storedFormat, saved.digest)
        val stored = decode(saved.metadata)
        val details = if (attachment) stored else ArchiveMarkup.upgradeDetails(stored, file::readText)
        val format = if (storedFormat == PageFormat.ARTICLE &&
            ArchiveMarkup.isListedDirectory(ref.path, details.chapters, seeds.isDirectory(ref.path))) PageFormat.DIRECTORY else storedFormat
        if (details !== stored || format != storedFormat) {
            if (format != storedFormat) recordDirectoryLinks(ref.path, saved.title, details.chapters)
            dao.putPage(saved.copy(format = format.name, metadata = json.encodeToString(details)))
        }
        return OpenPage(ref, saved.title, format, details, saved.origin, saved.digest, file,
            dao.isPinned(saved.path, saved.digest))
    }

    private fun seedPage(ref: ArchiveRef): OpenPage? {
        val children = seeds.children(ref.path)
        if (children.isEmpty()) return null
        return OpenPage(ref, seeds.title(ref.path) ?: "中文马克思主义文库", PageFormat.DIRECTORY,
            PageDetails(chapters = children))
    }

    private suspend fun fetchHtml(
        ref: ArchiveRef, source: Source, old: SavedPage?, network: Network?, progress: (ArchiveProgress) -> Unit
    ): OpenPage {
        val previous = old?.takeIf { it.origin == source.id && files.exists(PageFormat.valueOf(it.format), it.digest) }
        val reply = withTimeoutOrNull(6500) {
            http.get(source, ref.path, 7 * 1024 * 1024, previous?.tag, previous?.modified, network)
        } ?: throw ArchiveProblem(ConnectionIssue.TIMEOUT)
        if (reply.unchanged) return previous?.let { fromLocal(ref, it) }
            ?: throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        progress(ArchiveProgress(ArchiveStage.PARSE))
        val parsed = ArchiveMarkup.parse(ref, source, reply, seeds.isDirectory(ref.path))
        val entry = dao.entry(ref.path)
        val expected = entry?.takeIf { it.kind == "document" }?.title?.let(chinese::fold)
        val actual = chinese.fold(parsed.title)
        if (source != MainSource && expected != null && expected.length >= 5 && actual.length >= 5 &&
            !actual.contains(expected) && !expected.contains(actual) && !actual.contains(expected.take(5))) {
            throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        }
        progress(ArchiveProgress(ArchiveStage.SAVE))
        val digest = files.putBody(parsed.html)
        val file = files.body(digest)
        val now = System.currentTimeMillis()
        val row = SavedPage(ref.path, parsed.title, parsed.format.name, digest, json.encodeToString(parsed.details),
            source.id, reply.tag, reply.modified, now, now, file.length())
        database.withTransaction {
            dao.putPage(row)
            val ancestry = parsed.parents.zipWithNext { parent, child ->
                DirectoryLink(child.ref.path, parent.ref.path, parent.title)
            } + listOfNotNull(parsed.parents.lastOrNull()?.let { parent ->
                DirectoryLink(ref.path, parent.ref.path, parent.title)
            })
            if (ancestry.isNotEmpty()) dao.putDirectoryLinks(ancestry)
            if (parsed.format == PageFormat.DIRECTORY) {
                recordDirectoryLinks(ref.path, entry?.title ?: parsed.title, parsed.details.chapters)
            }
            if (parsed.details.chapters.isNotEmpty()) saveLinks(ref, parsed, entry)
        }
        return OpenPage(ref, parsed.title, parsed.format, parsed.details, source.id, digest, file)
    }

    private fun recordDirectoryLinks(path: String, title: String, chapters: List<Chapter>) {
        val links = chapters.map { it.ref.path }.distinct()
            .filter { it != path && it != LibraryDirectory.ref.path }
            .map { DirectoryLink(it, path, title) }
        if (links.isNotEmpty()) dao.putDirectoryLinks(links)
    }

    private fun saveLinks(ref: ArchiveRef, page: ParsedPage, parent: CatalogEntry?) {
        val links = page.details.chapters.mapIndexed { order, chapter ->
            val current = dao.entry(chapter.ref.path)
            if (current?.key == chapter.ref.key && !current.title.isNavigationLabel()) return@mapIndexed current
            val category = if (ref.path == "/chinese/") classify(chapter) else "作品"
            val author = parent?.takeIf { it.category == "作者" }?.title ?: parent?.author.orEmpty()
            val path = chapter.ref.path.substringBefore('?')
            val kind = if (ref.path == "/chinese/" || path.endsWith('/') || path.substringAfterLast('/').startsWith("index.")) {
                "directory"
            } else "document"
            CatalogEntry(chapter.ref.key, chapter.ref.path, chapter.ref.anchor, chapter.title, author, category, kind,
                order, chinese.fold("${chapter.title} $author $category"))
        }
        dao.putCatalog(links)
    }

    private fun classify(chapter: Chapter): String {
        seeds.category(chapter.ref.path)?.let { return it }
        return when {
            chapter.title.contains("词典") -> "词典"
            chapter.title.contains("全集") || chapter.title.contains("文集") -> "全集"
            chapter.title.contains("国际") || chapter.ref.path.contains("/update/") ||
                chapter.title.length > 9 -> "专题"
            else -> "作者"
        }
    }

    private suspend fun fetchAttachment(
        ref: ArchiveRef, source: Source, format: PageFormat, network: Network?, progress: (ArchiveProgress) -> Unit
    ): OpenPage {
        val title = dao.entry(ref.path)?.title ?: ref.path.substringBefore('?').substringAfterLast('/')
        val attachment = files.fetchAttachment(http, source, ref.path, format, network, progress)
        progress(ArchiveProgress(ArchiveStage.SAVE))
        val now = System.currentTimeMillis()
        val details = PageDetails()
        val row = SavedPage(ref.path, title, format.name, attachment.digest, json.encodeToString(details), source.id,
            attachment.tag, attachment.modified, now, now, attachment.file.length())
        dao.putPage(row)
        return OpenPage(ref, title, format, details, source.id, attachment.digest, attachment.file)
    }

    private suspend fun ensureIllustrations(page: OpenPage, network: Network?) {
        if (!page.details.downloadsAllowed) throw ArchiveProblem(ConnectionIssue.REJECTED)
        val source = mirrors.all().find { it.id == page.source } ?: MainSource
        coroutineScope {
            page.details.illustrations.filterNot { it.key == page.details.cover?.key }.map { illustration ->
                async { fetchImage(source, illustration, network) }
            }.awaitAll()
        }
        coverFile(page, network)
    }

    private suspend fun fetchImage(source: Source, illustration: Illustration, network: Network?) = imageSlots.withPermit {
        if (files.image(illustration.key, illustration.extension).isFile) return@withPermit
        val reply = http.get(source, illustration.path, 15 * 1024 * 1024, network = network)
        files.putImage(illustration, reply.bytes)
    }

    private suspend fun <T> fromSources(path: String, load: suspend (Source) -> T): T {
        var last = ArchiveProblem(ConnectionIssue.MISSING)
        for (source in mirrors.candidates(path)) {
            try {
                return load(source)
            } catch (problem: ArchiveProblem) {
                last = problem
                mirrors.record(source, path, problem)
                if (problem.issue in setOf(ConnectionIssue.LIMITED, ConnectionIssue.TOO_LARGE, ConnectionIssue.STORAGE)) throw problem
            }
        }
        throw last
    }

    private fun decode(metadata: String): PageDetails = json.decodeFromString(metadata)

    private fun parseRss(bytes: ByteArray, source: Source): List<UpdateEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(ByteArrayInputStream(bytes), null)
        val entries = ArrayList<UpdateEntry>()
        var fields: MutableMap<String, String>? = null
        var rss = false
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name.lowercase() in setOf("rss", "feed", "rdf") -> rss = true
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "item" -> fields = mutableMapOf()
                    parser.eventType == XmlPullParser.START_TAG && fields != null &&
                        parser.name in setOf("link", "title", "guid", "pubDate") -> {
                        val name = parser.name
                        fields[name] = parser.nextText()
                    }
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "item" && fields != null -> {
                        val link = fields["link"]?.let { ArchiveRef.fromUrl(it, listOf(source, MainSource)) }
                        val title = Jsoup.parse(fields["title"].orEmpty()).text().take(180)
                        if (link != null && title.isNotBlank()) {
                            val timestamp = fields["pubDate"]?.let {
                                runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                            } ?: 0L
                            entries += UpdateEntry(link.key, link.path, link.anchor, title, timestamp)
                        }
                        fields = null
                    }
                }
                if (entries.size >= 300) break
                parser.next()
            }
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        }
        if (!rss) throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        return entries.distinctBy { it.key }
    }
}
