package app.marxistlibrary.reader.catalog

import android.content.Context
import android.net.Network
import app.marxistlibrary.reader.sources.ArchiveHttp
import app.marxistlibrary.reader.sources.ArchiveProblem
import app.marxistlibrary.reader.sources.ConnectionIssue
import app.marxistlibrary.reader.sources.Source
import app.marxistlibrary.reader.sources.digest
import app.marxistlibrary.reader.sources.hex
import okhttp3.Headers
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

data class Attachment(val digest: String, val file: File, val tag: String?, val modified: String?)

// Bodies and resources are written beside their final destination before they are published.
class ArchiveFiles(context: Context) {
    private val root = File(context.filesDir, "archive")
    private val bodies = File(root, "bodies")
    private val images = File(root, "images")
    private val attachments = File(root, "attachments")
    private val staging = File(root, "staging")
    private val writes = Array(32) { Any() }

    fun body(key: String) = File(bodies, "$key.html")

    fun image(key: String, extension: String) = File(images, "$key.$extension")

    fun attachment(key: String, format: PageFormat) = File(attachments, "$key.${format.name.lowercase()}")

    fun putBody(html: String): String {
        val content = html.toByteArray(Charsets.UTF_8)
        val key = digest(content)
        put(body(key), content)
        return key
    }

    fun putImage(image: Illustration, bytes: ByteArray) {
        if (!validImage(bytes)) throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        put(image(image.key, image.extension), bytes)
    }

    fun exists(format: PageFormat, digest: String): Boolean = when (format) {
        PageFormat.DIRECTORY, PageFormat.ARTICLE -> body(digest).let { it.isFile && it.length() > 0 }
        else -> attachment(digest, format).let { it.isFile && it.length() > 0 }
    }

    fun ownedBytes(): Long = root.walkTopDown().filter(File::isFile).sumOf(File::length)

    fun sweep(keepBodies: Set<String>, keepImages: Set<String>, keepAttachments: Set<String>, retired: Set<String>) {
        // A recently moved file may still be awaiting its Room commit.
        val publishedBefore = System.currentTimeMillis() - 120_000L
        fun obsolete(file: File, kept: Set<String>) = file.nameWithoutExtension !in kept &&
            (file.nameWithoutExtension in retired || file.lastModified() < publishedBefore)
        bodies.listFiles()?.filter { obsolete(it, keepBodies) }?.forEach(File::delete)
        images.listFiles()?.filter { obsolete(it, keepImages) }?.forEach(File::delete)
        attachments.listFiles()?.filter { obsolete(it, keepAttachments) }?.forEach(File::delete)
        val expired = System.currentTimeMillis() - 7 * 86_400_000L
        staging.listFiles()?.filter { it.lastModified() < expired }?.forEach(File::delete)
    }

    suspend fun fetchAttachment(
        http: ArchiveHttp,
        source: Source,
        path: String,
        format: PageFormat,
        network: Network?,
        progress: (ArchiveProgress) -> Unit
    ): Attachment {
        val key = digest("${source.id}:$path")
        val partial = File(staging, "$key.part")
        val validator = File(staging, "$key.etag")
        staging.mkdirs()
        val tag = validator.takeIf(File::isFile)?.readText()?.takeIf { it.startsWith('"') && it.endsWith('"') }
        val offset = if (tag == null) 0 else partial.length()
        val headers = Headers.Builder().add("Accept-Encoding", "identity").apply {
            if (offset > 0) {
                add("Range", "bytes=$offset-")
                add("If-Range", requireNotNull(tag))
            }
        }.build()
        val stored = http.fetch(source, path, headers, network) { response ->
            synchronized(writes[(key.hashCode() and Int.MAX_VALUE) % writes.size]) {
                if (response.code == 416) return@synchronized null
                val nextTag = response.header("ETag")
                val range = response.header("Content-Range")
                val append = response.code == 206 && offset > 0 && range?.startsWith("bytes $offset-") == true &&
                    (nextTag == null || nextTag == tag) && partial.length() == offset
                if (response.code == 206 && !append || response.code !in setOf(200, 206)) {
                    return@synchronized null
                }
                if (!append) {
                    validator.delete()
                    partial.delete()
                }
                if (nextTag?.startsWith('"') == true && nextTag.endsWith('"')) writeValidator(validator, nextTag)
                val body = response.body ?: throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
                val startingSize = if (append) offset else 0L
                val expected = if (append) range?.substringAfterLast('/')?.toLongOrNull()
                    else body.contentLength().takeIf { it >= 0L }
                if (append && expected == null) throw ArchiveProblem(ConnectionIssue.SERVER)
                if (expected != null && expected > MaxAttachment) throw ArchiveProblem(ConnectionIssue.TOO_LARGE)
                progress(ArchiveProgress(ArchiveStage.DOWNLOAD, startingSize, expected))
                val output = try {
                    FileOutputStream(partial, append)
                } catch (_: IOException) {
                    throw ArchiveProblem(ConnectionIssue.STORAGE)
                }
                output.use {
                    val input = body.byteStream()
                    val buffer = ByteArray(32 * 1024)
                    var total = startingSize
                    var lastUpdate = System.nanoTime()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MaxAttachment) throw ArchiveProblem(ConnectionIssue.TOO_LARGE)
                        try {
                            output.write(buffer, 0, count)
                        } catch (_: IOException) {
                            throw ArchiveProblem(ConnectionIssue.STORAGE)
                        }
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 120_000_000L) {
                            progress(ArchiveProgress(ArchiveStage.DOWNLOAD, total, expected))
                            lastUpdate = now
                        }
                    }
                    if (expected != null && expected != total) {
                        throw ArchiveProblem(ConnectionIssue.SERVER)
                    }
                }
                progress(ArchiveProgress(ArchiveStage.VERIFY))
                publishAttachment(partial, validator, format, nextTag ?: tag, response.header("Last-Modified"))
            }
        }
        if (stored != null) return stored
        partial.delete()
        validator.delete()
        if (offset > 0) return fetchAttachment(http, source, path, format, network, progress)
        throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
    }

    private fun writeValidator(file: File, tag: String) {
        try {
            file.writeText(tag)
        } catch (_: IOException) {
            throw ArchiveProblem(ConnectionIssue.STORAGE)
        }
    }

    private fun publishAttachment(
        partial: File,
        validator: File,
        format: PageFormat,
        tag: String?,
        modified: String?
    ): Attachment {
        val valid = when (format) {
            PageFormat.PDF -> partial.inputStream().use { input ->
                val header = ByteArray(1024)
                val size = input.read(header)
                size > 0 && String(header, 0, size, Charsets.ISO_8859_1).contains("%PDF-")
            }
            PageFormat.EPUB -> runCatching {
                ZipFile(partial).use { it.getEntry("META-INF/container.xml") != null }
            }.getOrDefault(false)
            else -> false
        }
        if (!valid) {
            partial.delete()
            validator.delete()
            throw ArchiveProblem(ConnectionIssue.WRONG_CONTENT)
        }
        val hash = MessageDigest.getInstance("SHA-256")
        partial.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        val fingerprint = hex(hash.digest())
        val file = attachment(fingerprint, format)
        file.parentFile?.mkdirs()
        try {
            move(partial, file)
        } catch (_: IOException) {
            throw ArchiveProblem(ConnectionIssue.STORAGE)
        }
        validator.delete()
        return Attachment(fingerprint, file, tag, modified)
    }

    private fun put(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        var temporary: File? = null
        try {
            temporary = File.createTempFile("write-", ".pending", target.parentFile)
            temporary.writeBytes(bytes)
            move(temporary, target)
        } catch (_: IOException) {
            throw ArchiveProblem(ConnectionIssue.STORAGE)
        } finally {
            temporary?.delete()
        }
    }

    private fun move(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        destination.setLastModified(System.currentTimeMillis())
    }

    private fun validImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val prefix = bytes.copyOfRange(0, 12).toString(Charsets.ISO_8859_1)
        return prefix.startsWith("\u0089PNG") || prefix.startsWith("\u00FF\u00D8") ||
            prefix.startsWith("GIF8") || prefix.startsWith("BM") ||
            prefix.startsWith("RIFF") && prefix.substring(8) == "WEBP" ||
            prefix.substring(4, 8) == "ftyp"
    }

    companion object {
        const val MaxAttachment = 256L * 1024 * 1024
    }
}

