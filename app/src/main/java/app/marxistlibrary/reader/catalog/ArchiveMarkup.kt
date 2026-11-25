package app.marxistlibrary.reader.catalog

import app.marxistlibrary.reader.AuthorLengthLimit

import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ArchiveRef
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.MainSource
import app.marxistlibrary.reader.sources.Source
import app.marxistlibrary.reader.sources.WebReply
import app.marxistlibrary.reader.sources.digest
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.Charset

enum class PageFormat(val label: String) {
    DIRECTORY("目录"), ARTICLE("HTML 正文"), PDF("PDF"), EPUB("EPUB");

    companion object {
        fun fromPath(path: String): PageFormat {
            val bare = path.substringBefore('?')
            return when {
                bare.endsWith(".pdf", ignoreCase = true) -> PDF
                bare.endsWith(".epub", ignoreCase = true) -> EPUB
                isIndexPath(bare) -> DIRECTORY
                else -> ARTICLE
            }
        }
    }
}

private fun isIndexPath(path: String): Boolean {
    val bare = path.substringBefore('?')
    val last = bare.substringAfterLast('/')
    return bare.endsWith('/') || last.startsWith("index.") || last.startsWith("index-")
}

@Serializable
data class Chapter(val ref: ArchiveRef, val title: String)

private val PrintedPageRange = Regex("(?:…{2,}|[.．·]{4,})\\s*[0-9０-９]+(?:\\s*[-—–―−－]\\s*[0-9０-９]+)?\\s*$")

internal fun String.chapterTitle(): String = replace(PrintedPageRange, "").trim()

private val NavigationLabels = setOf(
    "中文马克思主义文库", "返回", "返回目录", "回目录", "回到目录", "返回首页", "回首页", "主页",
    "上一篇", "下一篇", "上一章", "下一章", "上一页", "下一页", "上一卷", "下一卷", "Back", "Home"
)

internal fun String.isNavigationLabel(): Boolean = this in NavigationLabels

@Serializable
data class Illustration(val path: String, val key: String, val extension: String, val caption: String)

@Serializable
data class Heading(val id: String, val title: String, val level: Int)

private val FootnoteAnchor = Regex("(?i)^_?ftn(?:ref)?[0-9]+$")
private val FootnoteLabel = Regex("[\\[［〔【(（]\\s*(?:注\\s*)?[0-9０-９]+\\s*[\\]］〕】)）]")
private val FootnoteSuffix = Regex("\\s*${FootnoteLabel.pattern}$")

private fun Heading.cleaned(): Heading? {
    val label = title.trim()
    if (FootnoteAnchor.matches(id) || FootnoteLabel.matches(label) || label.isNavigationLabel()) return null
    return label.replace(FootnoteSuffix, "").trim().takeIf(String::isNotEmpty)?.let { copy(title = it) }
}

@Serializable
data class PageDetails(
    val chapters: List<Chapter> = emptyList(),
    val illustrations: List<Illustration> = emptyList(),
    val headings: List<Heading> = emptyList(),
    val attribution: String = "",
    val rights: String = "",
    val downloadsAllowed: Boolean = true,
    val cover: Illustration? = null,
    val markupVersion: Int = 0
) {
    val imageKeys: List<String> get() = illustrations.map { it.key } + listOfNotNull(cover?.key)
}

data class ParsedPage(
    val title: String, val format: PageFormat, val html: String, val details: PageDetails,
    val parents: List<Chapter> = emptyList()
)

// Old MIA pages mix navigation, directories and text. Preserve each block's original position.
object ArchiveMarkup {
    private const val MarkupVersion = 1
    private const val HeadingTags = "h1,h2,h3,h4,h5,h6"
    private val cleaner = Cleaner(
        Safelist.none()
            .addTags(
                "a", "abbr", "article", "aside", "b", "blockquote", "br", "caption", "cite", "code", "dd",
                "del", "div", "dl", "dt", "em", "figcaption", "figure", "h1", "h2", "h3", "h4",
                "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "section", "small",
                "span", "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul"
            )
            .addAttributes(":all", "id", "title", "lang", "role", "epub:type")
            .addAttributes("p", "align")
            .addAttributes("div", "align")
            .addAttributes("a", "href", "name", "rel")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan", "scope")
            .addAttributes("ol", "start")
            .addProtocols("a", "href", "https", "http", "#")
            .addProtocols("img", "src", "https", "http")
            .preserveRelativeLinks(true)
    )

    @JvmOverloads
    fun parse(ref: ArchiveRef, source: Source, reply: WebReply, knownDirectory: Boolean = false): ParsedPage {
        val document = decode(reply)
        val body = document.body()
        val text = body.text()
        val title = title(document)
        if (text.length < 16 || title.length < 2 || TroubleTitle.containsMatchIn(title)) {
            throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        }
        val resolved = source.reference(reply.url) ?: throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        if (resolved.path == "/chinese/" && ref.path != "/chinese/") {
            throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        }

        val breadcrumbs = leadingBreadcrumbs(body, source, reply)
        val parentTrail = breadcrumbs.take(1) + breadcrumbs.drop(1).takeWhile(::followsBreadcrumbSeparator)
        val parents = parentTrail.mapNotNull { link ->
            val destination = reply.url.resolve(link.attr("href")) ?: return@mapNotNull null
            val parent = (source.reference(destination) ?: MainSource.reference(destination)) ?: return@mapNotNull null
            val label = link.text().take(180)
            if (parent.path == ref.path || parent.path == resolved.path || label.isBlank()) null
            else Chapter(parent.copy(anchor = ""), label)
        }.distinctBy { it.ref.path }
        val chapters = body.select("a[href]").asSequence().mapNotNull { link ->
            if (link in breadcrumbs || link.parents().any { it.tagName() == "nav" || it.attr("role") == "navigation" }) {
                return@mapNotNull null
            }
            val raw = link.attr("href").trim()
            if (raw.isEmpty() || raw.startsWith('#')) return@mapNotNull null
            val target = reply.url.resolve(raw) ?: return@mapNotNull null
            val linked = source.reference(target) ?: MainSource.reference(target) ?: return@mapNotNull null
            val label = link.text().chapterTitle().take(180)
            if (label.length < 2 || label.isNavigationLabel() || linked.path == ref.path || linked.path == resolved.path ||
                !supportedPath(linked.path) || isBreadcrumb(ref.path, linked.path)) return@mapNotNull null
            Chapter(linked, label)
        }.distinctBy { it.ref.key }.take(1_500).toList()

        val substantial = body.select("p, blockquote, li").count { it.text().length >= 100 }
        val directory = isListedDirectory(ref.path, chapters, knownDirectory) || (chapters.isNotEmpty() &&
            (isIndexPath(ref.path) && substantial < 5 || chapters.size >= 5 && substantial == 0 && text.length < 4_000))
        val section = body.selectFirst("main, article, [role=main]") ?: body
        val rights = body.select("footer, [class*=copyright], [id*=copyright]").text().take(800)
        val attribution = document.selectFirst("meta[name=author]")?.attr("content").orEmpty().take(AuthorLengthLimit)
        fun illustration(raw: String, caption: String = ""): Illustration? {
            if (raw.isBlank()) return null
            val address = reply.url.resolve(raw) ?: return null
            val path = (source.reference(address) ?: MainSource.reference(address))?.path ?: return null
            val ext = path.substringBefore('?').substringAfterLast('.', "").lowercase()
            if (ext !in ImageTypes || address.username.isNotEmpty() || address.password.isNotEmpty()) return null
            return Illustration(path, digest("${source.id}:$path"), ext, caption.take(180))
        }
        val declaredCover = ArchiveCover.declared(document).firstNotNullOfOrNull { raw ->
            illustration(raw)?.takeIf { ArchiveCover.eligible(it.path) }
        }
        section.select("script, style, form, nav, iframe, noscript, [class*=navigation], [id*=navbar]").remove()
        // Legacy named anchors must survive cleaning so footnotes and source URLs still work.
        section.select("a[name]").forEach { if (!it.hasAttr("id")) it.attr("id", it.attr("name")) }
        section.select("center").forEach { it.tagName("div").attr("align", "center") }
        val clean = cleaner.clean(Jsoup.parseBodyFragment(section.html(), reply.url.toString())).body()
        val images = mutableListOf<Illustration>()
        clean.select("img").forEach { image ->
            val item = illustration(image.attr("src"), image.attr("alt"))
            if (item == null || images.size >= 100) {
                image.after(TextNode(image.attr("alt")))
                image.remove()
            } else {
                images += item
                image.attr("src", "/image/${item.key}.${item.extension}").attr("loading", "lazy")
            }
        }
        clean.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.startsWith('#')) return@forEach
            val resolvedUrl = reply.url.resolve(href)
            val linked = resolvedUrl?.let { source.reference(it) ?: MainSource.reference(it) }
            when {
                resolvedUrl != null && linked != null && (linked.path == ref.path || linked.path == resolved.path) && linked.anchor.isNotEmpty() ->
                    anchor.attr("href", "#${resolvedUrl.encodedFragment}")
                linked != null -> anchor.attr("href", linked.original)
                resolvedUrl != null -> anchor.attr("href", resolvedUrl.toString())
                else -> anchor.removeAttr("href")
            }
        }
        indexBlocks(clean)
        val illustrations = images.distinctBy { it.key }
        val details = PageDetails(chapters, illustrations, contents(clean), attribution, rights,
            !(rights.contains("禁止下载") || rights.contains("不得下载")),
            declaredCover ?: ArchiveCover.find(clean, illustrations), MarkupVersion)
        return ParsedPage(title, if (directory) PageFormat.DIRECTORY else PageFormat.ARTICLE, clean.html(), details, parents)
    }

    internal fun isListedDirectory(path: String, chapters: List<Chapter>, knownDirectory: Boolean): Boolean {
        val namedIndex = path.substringBefore('?').substringAfterLast('/').startsWith("index-")
        return path == LibraryDirectory.ref.path ||
            chapters.isNotEmpty() && (knownDirectory || namedIndex && chapters.size >= 8)
    }

    // Stored bodies already contain stable reading anchors. Reuse them when upgrading metadata.
    fun upgradeDetails(details: PageDetails, html: () -> String): PageDetails {
        if (details.markupVersion >= MarkupVersion) return details
        val body = Jsoup.parseBodyFragment(html()).body()
        return details.copy(headings = contents(body), cover = ArchiveCover.find(body, details.illustrations),
            markupVersion = MarkupVersion)
    }

    fun contents(body: Element): List<Heading> {
        val nodes = body.select("[id],a[name]")
        val byId = nodes.filter { it.id().isNotEmpty() }.associateBy { it.id() }
        val names = nodes.filter { it.hasAttr("name") }.associateBy { it.attr("name") }
        val sections = HashMap<Element, Heading>()
        nodes.forEach { node ->
            if (node.`is`(HeadingTags) && node.id().length in 1..500) {
                Heading(node.id(), node.text().take(250), node.tagName().last().digitToInt()).cleaned()
                    ?.let { sections[node] = it }
            }
        }
        body.select("a[href^=\"#\"]:not(sup a):not([role=doc-noteref])").forEach { link ->
            val name = link.text()
            if (name.length !in 2..180) return@forEach
            val raw = link.attr("href").substring(1)
            val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) }
                .getOrDefault(raw)
            val node = byId[decoded] ?: byId[raw] ?: names[decoded] ?: names[raw] ?: return@forEach
            val id = node.id().ifEmpty { node.attr("name") }
            if (id.length !in 1..500 || FootnoteAnchor.matches(id)) return@forEach
            val section = node.closest(HeadingTags) ?: headingAfter(node) ?: node
            if (section !in sections) {
                Heading(id, name, 2).cleaned()?.let { sections[section] = it }
            }
        }
        return nodes.mapNotNull(sections::get).take(400)
    }

    private fun headingAfter(marker: Element): Element? {
        if (marker.hasText() || !marker.`is`("a, span")) return null
        val container = marker.parent()?.takeIf { it.`is`("p, div") && !it.hasText() } ?: marker
        val next = generateSequence(container.nextSibling()) { it.nextSibling() }.firstOrNull { sibling ->
            when (sibling) {
                is TextNode -> !sibling.isBlank
                is Element -> sibling.hasText() || sibling.`is`("img, table") || sibling.selectFirst("img") != null
                else -> false
            }
        }
        return (next as? Element)?.takeIf { it.`is`(HeadingTags) }
    }

    fun cleanBookPage(html: String, base: String): Document {
        val document = Jsoup.parse(html, base)
        document.select("script, style, form, iframe, noscript").remove()
        val safe = cleaner.clean(document)
        safe.select("a[name]:not([id])").forEach { it.attr("id", it.attr("name")) }
        indexBlocks(safe.body())
        return safe
    }

    fun supportedPath(path: String): Boolean = path.substringBefore('?').let {
        it.endsWith('/') || it.substringAfterLast('/').let { filename ->
            '.' !in filename || filename.substringAfterLast('.').lowercase() in DocumentTypes
        }
    }

    private fun decode(reply: WebReply): Document {
        val hint = reply.charset?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
        fun parse(charset: String?) = Jsoup.parse(ByteArrayInputStream(reply.bytes), charset, reply.url.toString())
        val decoded = parse(hint)
        if (decoded.body().text().count { it == '\uFFFD' } < 3) return decoded
        return listOf(parse(null), parse("GB18030"), parse("Big5"))
            .minBy { it.body().text().count { char -> char == '\uFFFD' } }
    }

    private fun title(document: Document): String {
        val heading = document.select("h1").firstNotNullOfOrNull { node ->
            node.text().takeIf { it.length in 2..130 && !it.isNavigationLabel() }
        }
        if (heading != null) return heading
        val title = document.title().ifBlank { document.selectFirst("h2, h3, center")?.text().orEmpty() }
        return title.removePrefix("中文马克思主义文库·").removePrefix("中文马克思主义文库 · ").trim().take(180)
    }

    private fun isBreadcrumb(owner: String, link: String): Boolean {
        val parent = owner.substringBeforeLast('/') + "/"
        val destination = link.substringBeforeLast('/') + "/"
        return link == "/chinese/" || parent.startsWith(destination) && parent != destination
    }

    private fun leadingBreadcrumbs(body: Element, source: Source, reply: WebReply): List<Element> {
        val separator = body.selectFirst("hr") ?: return emptyList()
        val links = generateSequence(separator.previousElementSibling()) { it.previousElementSibling() }
            .takeWhile { it.tagName() in BreadcrumbTags }
            .toList().asReversed().flatMap { it.select("a[href]") }
        val beginsWithArchive = links.size in 2..8 && links.any {
            val url = reply.url.resolve(it.attr("href"))
            url != null && (source.reference(url) ?: MainSource.reference(url))?.path == "/chinese/"
        }
        return if (beginsWithArchive) links else emptyList()
    }

    private fun followsBreadcrumbSeparator(link: Element): Boolean {
        val separator = generateSequence<Node>(link) { it.parent() }
            .mapNotNull { it.previousSibling() }.firstOrNull { sibling ->
                sibling !is TextNode || !sibling.isBlank
            }
        val text = when (separator) {
            is TextNode -> separator.text()
            is Element -> separator.text()
            else -> ""
        }
        return text.trim() in setOf("->", "→", ">", "＞", "›", "»")
    }

    private fun indexBlocks(body: Element) {
        val ids = mutableSetOf<String>()
        body.select("[id]").forEach { node ->
            val id = node.id()
            if (id.isBlank() || !ids.add(id)) node.removeAttr("id")
        }
        val blocks = body.select("h1, h2, h3, h4, h5, h6, p, blockquote, li, pre, td, dt, dd, div")
        blocks.forEach { node ->
            val text = node.text().take(250)
            if (text.isBlank() || (node.ownText().isBlank() && node.select("p, li, td, div").size > 1)) return@forEach
            if (node.id().isBlank()) {
                val base = "p-" + digest(text).take(14)
                var id = base
                var occurrence = 1
                while (!ids.add(id)) id = "$base-${occurrence++}"
                node.attr("id", id)
            }
            node.attr("data-reading-anchor", node.id())
        }
    }

    private val TroubleTitle = Regex("access denied|forbidden|not found|bad gateway|just a moment|登录|验证访问", RegexOption.IGNORE_CASE)
    private val DocumentTypes = setOf("htm", "html", "shtml", "pdf", "epub")
    private val ImageTypes = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")
    private val BreadcrumbTags = setOf("a", "b", "br", "i", "small", "span", "strong")
}
