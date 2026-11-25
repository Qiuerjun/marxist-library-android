package app.marxistlibrary.reader.reader

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.os.BundleCompat
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private const val Open = 1
private const val Draw = 2
private const val Close = 3
private const val Descriptor = "descriptor"
private const val FrameWidth = 1320
private const val FrameHeight = 2300
private const val RenderTimeout = 25_000L

// Only this isolated process parses PDFs. Each reader owns a separate document session.
class PdfRenderService : Service() {
    private val documents = mutableMapOf<IBinder, PdfRenderer>()
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("PDF pages").apply { start() }
        handler = Handler(thread.looper) { request ->
            receive(request)
            true
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        handler.post(::closeDocuments)
        return false
    }

    override fun onDestroy() {
        handler.post(::closeDocuments)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun closeDocuments() {
        documents.values.forEach { runCatching { it.close() } }
        documents.clear()
    }

    private fun receive(request: Message) {
        val fd = BundleCompat.getParcelable(request.data, Descriptor, ParcelFileDescriptor::class.java)
        val reply = request.replyTo
        if (reply == null) {
            fd?.close()
            return
        }
        val result = try {
            when (request.what) {
                Open -> open(reply.binder, requireNotNull(fd))
                Draw -> {
                    DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(fd))).use {
                        draw(requireNotNull(documents[reply.binder]), request.arg1, it)
                    }
                    0
                }
                Close -> {
                    documents.remove(reply.binder)?.close()
                    fd?.close()
                    return
                }
                else -> throw IOException("Unknown PDF renderer request")
            }
        } catch (_: Exception) {
            runCatching { fd?.close() }
            -1
        }
        if (request.what == Open) {
            try {
                reply.send(Message.obtain(null, Open, result, 0))
            } catch (_: RemoteException) {
                documents.remove(reply.binder)?.close()
            }
        }
    }

    private fun open(session: IBinder, descriptor: ParcelFileDescriptor): Int {
        documents.remove(session)?.close()
        val renderer = PdfRenderer(descriptor)
        if (renderer.pageCount < 1) {
            renderer.close()
            throw IOException("PDF has no pages")
        }
        documents[session] = renderer
        return renderer.pageCount
    }

    private fun draw(renderer: PdfRenderer, index: Int, output: DataOutputStream) = renderer.openPage(index).use { page ->
        val ratio = minOf(FrameWidth.toDouble() / page.width, FrameHeight.toDouble() / page.height)
        val width = (page.width * ratio).roundToInt().coerceIn(1, FrameWidth)
        val height = (page.height * ratio).roundToInt().coerceIn(1, FrameHeight)
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val pixels = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixels)
            output.writeInt(width)
            output.writeInt(height)
            output.write(pixels.array())
        } finally {
            bitmap.recycle()
        }
    }
}

internal class IsolatedPdfBook(private val context: Context) {
    private val connected = CompletableDeferred<Messenger>()
    private val pageCount = CompletableDeferred<Int>()
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == Open) pageCount.complete(message.arg1)
        true
    })
    private var service: Messenger? = null
    private var binding = false
    var count: Int = 0
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!binding) return
            val remote = Messenger(binder)
            service = remote
            connected.complete(remote)
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    private fun disconnected() {
        service = null
        val failure = IOException("PDF 渲染进程已中断")
        connected.completeExceptionally(failure)
        pageCount.completeExceptionally(failure)
    }

    suspend fun open(file: File) {
        withContext(Dispatchers.Main.immediate) {
            binding = context.bindService(Intent(context, PdfRenderService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!binding) throw IOException("无法启动 PDF 渲染进程")
        }
        count = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { send(Open, it) }
            withTimeoutOrNull(RenderTimeout) { pageCount.await() } ?: throw IOException("打开 PDF 等待超时")
        }
        if (count < 1) throw IOException("无法打开 PDF")
    }

    suspend fun render(index: Int): Bitmap = withContext(Dispatchers.IO) {
        // The pipe carries bounded raw pixels. The isolated process gets no writable file.
        val (read, write) = ParcelFileDescriptor.createPipe()
        read.use { input ->
            write.use { send(Draw, it, index) }
            val deadline = SystemClock.elapsedRealtime() + RenderTimeout
            val poll = arrayOf(StructPollfd().apply {
                fd = input.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            })
            val dimensions = ByteBuffer.wrap(readFrameBytes(poll, 8, deadline))
            val width = dimensions.int
            val height = dimensions.int
            if (width !in 1..FrameWidth || height !in 1..FrameHeight) throw IOException("无效的 PDF 页面尺寸")
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(readFrameBytes(poll, bitmap.byteCount, deadline)))
                bitmap
            } catch (failure: Exception) {
                bitmap.recycle()
                throw failure
            }
        }
    }

    private suspend fun readFrameBytes(poll: Array<StructPollfd>, size: Int, deadline: Long): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) throw IOException("PDF 渲染等待超时")
            if (Os.poll(poll, minOf(500L, left).toInt()) == 0) continue
            val count = Os.read(poll[0].fd, data, offset, size - offset)
            if (count <= 0) throw IOException("PDF 页面数据不完整")
            offset += count
        }
        return data
    }

    private suspend fun send(action: Int, fd: ParcelFileDescriptor, page: Int = 0) =
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(RenderTimeout) {
                connected.await()
                val remote = service ?: throw IOException("PDF 渲染进程已断开")
                remote.send(Message.obtain(null, action, page, 0).apply {
                    replyTo = replies
                    data = Bundle().apply { putParcelable(Descriptor, fd) }
                })
            } ?: throw IOException("PDF 渲染等待超时")
        }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        if (binding) {
            runCatching { service?.send(Message.obtain(null, Close).apply { replyTo = replies }) }
            context.unbindService(connection)
            binding = false
            disconnected()
        }
    }
}
