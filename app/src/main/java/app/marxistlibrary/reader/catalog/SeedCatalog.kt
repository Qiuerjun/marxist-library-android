package app.marxistlibrary.reader.catalog

import android.content.Context
import app.marxistlibrary.reader.CatalogEntry
import app.marxistlibrary.reader.DirectoryLink
import app.marxistlibrary.reader.search.ChineseText
import app.marxistlibrary.reader.sources.ArchiveRef

// Public metadata for the first offline launch. Text is read from the selected archive source.
class SeedCatalog(context: Context, private val chinese: ChineseText) {
    private data class Item(val owner: String, val entry: CatalogEntry)

    private val items by lazy {
        context.assets.open("catalog.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }
                .mapIndexedNotNull { order, row ->
                    val fields = row.split('\t')
                    if (fields.size < 4) return@mapIndexedNotNull null
                    val ref = ArchiveRef.parse(fields[1]) ?: return@mapIndexedNotNull null
                    val author = fields.getOrNull(4).orEmpty()
                    val category = fields[3]
                    val title = fields[2]
                    val kind = if (category in listOf("作者", "全集", "专题", "词典", "目录")) "directory" else "document"
                    val entry = CatalogEntry(ref.key, ref.path, ref.anchor, title, author, category, kind, order,
                        chinese.fold("$title $author $category"))
                    Item(fields[0], entry)
                }.toList()
        }
    }

    private val links by lazy {
        items.mapNotNull { item ->
            ArchiveRef.parse(item.owner)?.let { DirectoryLink(item.entry.path, it.path, title(it.path).orEmpty()) }
        }
    }

    fun entries() = items.map(Item::entry).distinctBy(CatalogEntry::key)

    fun directoryLinks() = links

    fun parent(path: String): DirectoryLink? = links.firstOrNull { it.path == path }

    fun children(path: String): List<Chapter> = items.filter { it.owner == path }.map {
        Chapter(ArchiveRef(it.entry.path, it.entry.anchor), it.entry.title)
    }

    fun category(path: String) = items.firstOrNull { it.entry.path == path }?.entry?.category

    fun isDirectory(path: String) = items.any { it.entry.path == path && it.entry.kind == "directory" }

    fun title(path: String) = items.firstOrNull { it.entry.path == path }?.entry?.title
}
