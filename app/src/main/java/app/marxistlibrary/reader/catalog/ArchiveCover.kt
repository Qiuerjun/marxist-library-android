package app.marxistlibrary.reader.catalog

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

// Older editions often identify a cover only by its filename or nearby printed caption.
internal object ArchiveCover {
    private val coverLabel = Regex("封面|封[一1]|[书書][影封]|front[ _-]?cover|book[ _-]?cover", RegexOption.IGNORE_CASE)
    private val otherSide = Regex("封底|封[二三四234]|[裏里裡]封|back[ _-]?cover|rear[ _-]?cover", RegexOption.IGNORE_CASE)
    private val coverFile = Regex("^(?:bookf|book|front|(?:[a-z0-9]+[-_])?(?:front[-_]?)?cover(?:[-_]?0?1)?|封面|[書书]影)$")
    private val decoration = Regex("(?:^|[\\W_])(?:logo|banner|avatar|icon|qrcode|spacer|pixel|nav|flag)(?:[\\W_]|[0-9]|$)", RegexOption.IGNORE_CASE)

    fun declared(document: Document): List<String> =
        document.select("img.cover, img#cover, img.book-cover, .book-cover img, .front-cover img").map { it.attr("src") } +
            document.select("meta[property=og:image], meta[name=twitter:image], link[rel=image_src]").map {
                if (it.tagName() == "link") it.attr("href") else it.attr("content")
            }

    fun eligible(path: String): Boolean {
        val name = filename(path)
        return !decoration.containsMatchIn(name) && !otherSide.containsMatchIn(name)
    }

    fun find(body: Element, illustrations: List<Illustration>): Illustration? {
        if (illustrations.isEmpty()) return null
        val local = illustrations.associateBy { "/image/${it.key}.${it.extension}" }
        val leading = body.select("img, p, blockquote, li").asSequence()
            .takeWhile { it.tagName() == "img" || it.text().length < 130 }
            .filter { it.tagName() == "img" }.take(3).toSet()
        return body.select("img[src]").mapNotNull { image ->
            val item = local[image.attr("src")]?.takeIf { eligible(it.path) } ?: return@mapNotNull null
            val caption = caption(image)
            if (otherSide.containsMatchIn(caption)) return@mapNotNull null
            val score = when {
                coverLabel.containsMatchIn(caption) -> 3
                coverFile.matches(filename(item.path)) -> 2
                image in leading && isPortrait(image) -> 1
                else -> return@mapNotNull null
            }
            item to score
        }.maxByOrNull { it.second }?.first
    }

    private fun filename(path: String): String {
        val name = path.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return runCatching { URLDecoder.decode(name, Charsets.UTF_8.name()) }.getOrDefault(name).lowercase()
    }

    private fun caption(image: Element): String {
        val container = image.parent()?.takeIf { it.`is`("figure, p, div, td") && it.select("img").size == 1 }
        val adjacent = (container ?: image).nextElementSibling()?.takeIf { it.`is`("figcaption, p[align=center]") }
        return listOfNotNull(image.attr("alt"), image.attr("title"),
            container?.text()?.takeIf { it.length <= 90 }, adjacent?.text()?.takeIf { it.length <= 90 }).joinToString(" ")
    }

    private fun isPortrait(image: Element): Boolean {
        val width = image.attr("width").toIntOrNull()?.takeIf { it >= 150 } ?: return false
        val height = image.attr("height").toIntOrNull() ?: return false
        return height.toDouble() / width in 1.12..2.35
    }
}
