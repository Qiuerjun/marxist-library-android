package app.marxistlibrary.reader.reader

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// PDF.js asks for the parts of the local document needed for the current page.
internal data class PdfSlice(val start: Long, val end: Long) {
    val length get() = end - start

    fun open(file: File): InputStream {
        val source = FileInputStream(file)
        try {
            source.channel.position(start)
            val buffer = BufferedInputStream(source)
            return object : InputStream() {
                private var remaining = length

                override fun read(): Int {
                    if (remaining == 0L) return -1
                    return buffer.read().also { if (it >= 0) remaining-- }
                }

                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    if (count == 0) return 0
                    if (remaining == 0L) return -1
                    return buffer.read(bytes, offset, minOf(count.toLong(), remaining).toInt()).also {
                        if (it > 0) remaining -= it
                    }
                }

                override fun close() = buffer.close()
            }
        } catch (failure: Exception) {
            source.close()
            throw failure
        }
    }

    companion object {
        fun within(fileSize: Long, start: Long?, end: Long?): PdfSlice? {
            if (start == null || end == null || start < 0 || end <= start || end > fileSize) return null
            return PdfSlice(start, end)
        }
    }
}

// Keep the zero-based "pdf:" anchors already saved by the original Android reader.
internal data class PdfPosition(val page: Int = 1, val offset: Int = 0) {
    val anchor get() = "pdf:${page - 1}"

    companion object {
        fun from(anchor: String, offset: Int): PdfPosition {
            val index = anchor.removePrefix("pdf:").toIntOrNull()?.coerceIn(0, Int.MAX_VALUE - 1) ?: 0
            return PdfPosition(index + 1, offset.coerceIn(0, 10_000))
        }
    }
}
