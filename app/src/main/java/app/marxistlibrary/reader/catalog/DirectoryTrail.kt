package app.marxistlibrary.reader.catalog

import app.marxistlibrary.reader.DirectoryLink
import app.marxistlibrary.reader.sources.ArchiveRef

val LibraryDirectory = Chapter(ArchiveRef("/chinese/"), "文库")

// One document can be linked from several collections. Give the path the reader opened precedence.
internal fun directoryAncestors(
    path: String, visitedFrom: List<String>, parents: (String) -> List<DirectoryLink>
): List<DirectoryLink> {
    var child = path
    val visited = mutableSetOf(child)
    val trail = mutableListOf<DirectoryLink>()
    val opened = visitedFrom.filter(String::isNotBlank).distinct().asReversed().iterator()
    while (child != LibraryDirectory.ref.path && trail.size < 12) {
        val known = parents(child).filter { it.parent !in visited }
        val preferred = if (opened.hasNext()) opened.next().takeUnless { it in visited } else null
        val link = if (preferred != null) known.firstOrNull { it.parent == preferred } ?: DirectoryLink(child, preferred, "")
            else known.firstOrNull()
        if (link == null) break
        trail += link
        child = link.parent
        visited += child
    }
    return trail.asReversed()
}
