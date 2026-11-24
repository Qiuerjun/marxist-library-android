package app.marxistlibrary.reader.reader

import android.util.Xml
import app.marxistlibrary.reader.catalog.ArchiveMarkup
import app.marxistlibrary.reader.catalog.Heading
import app.marxistlibrary.reader.sources.digest
import app.marxistlibrary.reader.sources.readBounded
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile

data class EpubChapter(val title: String, val entry: String)
data class EpubPage(val html: String, val headings: List<Heading>, val images: Map<String, String>)

// Resolve reading order from the EPUB spine and serve approved entries directly from the package.
class EpubBook private constructor(private val file: File, val chapters: List<EpubChapter>) {
    fun page(index: Int): EpubPage {
        val chapter = chapters[index]
        val bytes = ZipFile(file).use { zip -> required(zip, chapter.entry, MaxText) }
        val safe = ArchiveMarkup.cleanBookPage(bytes.toString(Charsets.UTF_8), base(chapter.entry).toString())
        val images = linkedMapOf<String, String>()
        safe.select("img").forEach { node ->
            val included = resolve(chapter.entry, node.attr("src"))?.entry
            if (included != null && mime(included) != null && images.size < 150) {
                val token = digest(included)
                images[token] = included
                node.attr("src", "/image/$token").attr("loading", "lazy")
            } else {
                node.after(TextNode(node.attr("alt")))
                node.remove()
            }
        }
        safe.select("a[href]").forEach { node ->
            val href = node.attr("href")
            if (href.startsWith('#')) return@forEach
            val link = resolve(chapter.entry, href)
            val sibling = chapters.indexOfFirst { it.entry == link?.entry }
            when {
                link?.entry == chapter.entry && link.fragment != null -> node.attr("href", "#${link.fragment}")
                sibling >= 0 -> {
                    val location = "https://${ReaderWeb.Host}/epub/$sibling"
                    node.attr("href", location + (link?.fragment?.let { "#$it" } ?: ""))
                }
                link != null -> node.removeAttr("href")
                else -> Unit
            }
        }
        val headings = safe.select("h1[id],h2[id],h3[id],h4[id],h5[id],h6[id]").map {
            Heading(it.id(), it.text().take(180), it.tagName().last().digitToInt())
        }.take(300)
        return EpubPage(safe.body().html(), headings, images)
    }

    fun image(page: EpubPage, token: String): ReaderAsset? {
        val name = page.images[token] ?: return null
        val type = mime(name) ?: return null
        return runCatching {
            val bytes = ZipFile(file).use { zip -> required(zip, name, MaxImage) }
            ReaderAsset(type, ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    companion object {
        private const val MaxText = 7 * 1024 * 1024
        private const val MaxImage = 18 * 1024 * 1024
        private const val MaxMetadata = 2 * 1024 * 1024
        private const val Host = "epub.invalid"

        private data class PackageItem(val name: String, val mime: String, val properties: String)
        private data class Link(val entry: String, val fragment: String?)

        fun open(file: File): EpubBook = ZipFile(file).use { zip ->
            if (zip.size() > 30_000) throw IOException("电子书文件过大")
            var packagePath: String? = null
            visit(required(zip, "META-INF/container.xml", MaxMetadata)) { parser ->
                if (parser.name == "rootfile" && packagePath == null) {
                    packagePath = resolve("", parser.getAttributeValue(null, "full-path").orEmpty())?.entry
                }
            }
            val book = packagePath ?: throw IOException("没有找到 EPUB 书目")
            val items = linkedMapOf<String, PackageItem>()
            val order = mutableListOf<String>()
            visit(required(zip, book, MaxMetadata)) { parser ->
                when (parser.name) {
                    "item" -> {
                        val name = resolve(book, parser.getAttributeValue(null, "href").orEmpty())?.entry
                        val id = parser.getAttributeValue(null, "id")
                        if (name != null && id != null && items.size < 6_000) {
                            items[id] = PackageItem(name, parser.getAttributeValue(null, "media-type").orEmpty(),
                                parser.getAttributeValue(null, "properties").orEmpty())
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { if (order.size < 2_000) order += it }
                }
            }
            val titles = chapterTitles(zip, items.values)
            val chapters = order.mapNotNull(items::get)
                .filter { it.mime in setOf("application/xhtml+xml", "text/html") && zip.getEntry(it.name) != null }
                .mapIndexed { index, item -> EpubChapter(titles[item.name] ?: "第 ${index + 1} 节", item.name) }
                .distinctBy { it.entry }
            if (chapters.isEmpty()) throw IOException("电子书中没有可阅读的章节")
            EpubBook(file, chapters)
        }

        private fun chapterTitles(zip: ZipFile, items: Collection<PackageItem>): Map<String, String> {
            val nav = items.firstOrNull { "nav" in it.properties.split(' ') } ?: return emptyMap()
            return runCatching {
                val html = required(zip, nav.name, MaxMetadata).toString(Charsets.UTF_8)
                Jsoup.parse(html).select("nav a[href]").mapNotNull { link ->
                    val name = resolve(nav.name, link.attr("href"))?.entry ?: return@mapNotNull null
                    val label = link.text().take(180).ifBlank { return@mapNotNull null }
                    name to label
                }.toMap()
            }.getOrDefault(emptyMap())
        }

        private fun visit(bytes: ByteArray, tag: (XmlPullParser) -> Unit) {
            val xml = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(ByteArrayInputStream(bytes), null)
            }
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.DOCDECL) throw IOException("电子书声明格式不受支持")
                if (xml.eventType == XmlPullParser.START_TAG) tag(xml)
                xml.nextToken()
            }
        }

        private fun required(zip: ZipFile, name: String, limit: Int): ByteArray {
            val entry = zip.getEntry(name) ?: throw IOException("电子书缺少章节文件")
            if (entry.isDirectory || entry.size > limit) throw IOException("章节资源超过大小限制")
            return zip.getInputStream(entry).use { readBounded(it, limit) }
        }

        private fun base(name: String) = URI("https", Host, "/$name", null)

        private fun resolve(from: String, href: String): Link? {
            if (href.isBlank() || href.length > 1000 || '\\' in href) return null
            return runCatching {
                val uri = base(from).resolve(href.replace(" ", "%20")).normalize()
                val name = uri.path.orEmpty().removePrefix("/")
                if (uri.host != Host || uri.scheme != "https" || uri.port != -1 || uri.userInfo != null ||
                    name.isBlank() || name.split('/').any { it in setOf("..", ".", "") }) return@runCatching null
                Link(name, uri.rawFragment)
            }.getOrNull()
        }

        private fun mime(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            else -> null
        }
    }
}
