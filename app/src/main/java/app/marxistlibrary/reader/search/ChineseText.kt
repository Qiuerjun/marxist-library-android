package app.marxistlibrary.reader.search

import java.text.Normalizer
import java.util.Locale

// Search folds variants for matching while returning the source's original text.
class ChineseText(private val variants: Map<Int, String>) {
    fun fold(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString {
            normalized.codePoints().forEach { point ->
                when {
                    Character.isLetterOrDigit(point) -> {
                        val variant = variants[point]
                        if (variant == null) appendCodePoint(point) else append(variant)
                    }
                    isNotEmpty() && last() != ' ' -> append(' ')
                }
            }
        }.trim()
    }

    fun terms(folded: String): List<String> {
        return folded.split(' ').flatMap { word ->
            val points = word.codePoints().toArray()
            if (points.any { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) {
                if (points.size == 1) emptyList() else (0 until points.lastIndex).map { String(points, it, 2) }
            } else if (word.isNotEmpty()) {
                listOf(word)
            } else {
                emptyList()
            }
        }
    }

    fun matchQuery(folded: String): String = terms(folded).distinct().take(18).joinToString(" AND ") { "\"$it\"" }

    companion object {
        fun fromLines(lines: Sequence<String>): ChineseText {
            val variants = lines.mapNotNull { line ->
                val columns = line.split('\t', limit = 2)
                if (columns.size != 2 || columns.first().codePointCount(0, columns.first().length) != 1) {
                    return@mapNotNull null
                }
                val simplified = columns.last().substringBefore(' ')
                columns.first().codePointAt(0) to simplified
            }.toMap()
            return ChineseText(variants)
        }
    }
}
