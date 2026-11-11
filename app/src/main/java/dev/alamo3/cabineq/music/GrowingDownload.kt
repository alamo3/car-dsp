package dev.alamo3.cabineq.music

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * One song being downloaded to a file that can be read while it grows.
 *
 * The player used to have two separate paths: the current song streamed through the platform's
 * own network reader (no control over buffering, no resume after a signal drop, a blocking open
 * that could not be interrupted), and upcoming songs downloaded whole (useless until complete).
 * This is the one path that replaces both: a single connection writes the song to disk, and any
 * number of readers take bytes from that file as they arrive.
 *
 * - [run] downloads, and after a dropped connection carries on from the byte it reached.
 * - [readAt] blocks until the bytes it wants are on disk. A read far past the download point
 *   (a seek, or a container whose index sits at the end of the file) is served by a small ranged
 *   request of its own instead of waiting for everything before it.
 * - When the download completes the file is renamed to [target]; readers keep working.
 *
 * Plain JVM on purpose (no Android types), so the unit tests can run it against a local server.
 */
class GrowingDownload(
    @Volatile private var url: String,
    private val part: File,
    private val target: File,
    private val maxBytes: Long,
    private val label: String,
    /** Asked for a fresh URL when the server refuses the current one (expired ticket). */
    private val refreshUrl: (() -> String?)? = null,
    private val log: (String) -> Unit = {},
    /** Give up after this long with no new byte. */
    private val maxStallMs: Long = 120_000L,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
    /** Reads this far past the download point get their own ranged request. */
    private val farAheadBytes: Long = 768L * 1024,
) {
    enum class State { STARTING, RUNNING, DONE, FAILED, CANCELLED }

    private val lock = Object()
    @Volatile var state = State.STARTING
        private set
    /** Bytes on disk, contiguous from the start. */
    @Volatile var written = 0L
        private set
    /** Full size from the server, or -1 while unknown. */
    @Volatile var total = -1L
        private set
    @Volatile var error: String? = null
        private set
    @Volatile private var cancelled = false
    @Volatile private var acceptsRanges = false
    @Volatile private var released = false
    private var channel: FileChannel? = null
    private var raf: RandomAccessFile? = null
    private var side: Side? = null
    private val sideLock = Any()

    /** The link in use (a refreshed one after an expiry). Never log it: it can carry a key. */
    val currentUrl: String get() = url

    val isFinished: Boolean get() = state == State.DONE
    /** Still downloading and wanted. False from the moment it is cancelled, so nobody joins a dying download. */
    val isAlive: Boolean get() = !cancelled && (state == State.STARTING || state == State.RUNNING)

    /** The file to read: the finished one once it exists, else the growing one. */
    val file: File get() = if (state == State.DONE && target.exists()) target else part

    fun cancel() {
        cancelled = true
        synchronized(lock) { lock.notifyAll() }
    }

    /** Blocks until the first reply from the server (true) or a failure (false). */
    fun awaitStart(timeoutMs: Long, isCancelled: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (state == State.STARTING) {
                if (isCancelled()) return false
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(minOf(left, 100L))
            }
        }
        return state == State.RUNNING || state == State.DONE
    }

    /** Downloads until complete, failed or cancelled. Blocking; call on its own thread. */
    fun run() {
        var lastProgressAt = System.currentTimeMillis()
        var attempt = 0
        try {
            part.parentFile?.mkdirs()
            val file = RandomAccessFile(part, "rw")
            synchronized(lock) {
                raf = file
                channel = file.channel
            }
            while (!cancelled) {
                val before = written
                val outcome = runCatching { transfer() }
                if (cancelled) break
                if (written > before) {
                    lastProgressAt = System.currentTimeMillis()
                    attempt = 0
                }
                val failure = outcome.exceptionOrNull()
                if (failure == null && outcome.getOrNull() == true) {
                    finish()
                    return
                }
                if (failure is Refused) {
                    // 4xx: the ticket has expired or the file is gone. A fresh URL may fix it.
                    val fresh = runCatching { refreshUrl?.invoke() }.getOrNull()
                    if (fresh == null || attempt >= 2) {
                        fail(failure.message ?: "refused")
                        return
                    }
                    url = fresh
                } else if (failure is TooLarge) {
                    fail(failure.message ?: "too large")
                    return
                }
                if (System.currentTimeMillis() - lastProgressAt > maxStallMs) {
                    fail("no data for ${maxStallMs / 1000}s" + (failure?.let { " (${it.javaClass.simpleName})" } ?: ""))
                    return
                }
                attempt++
                log("$label: connection lost at ${written / 1024} kB" +
                    (failure?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "") + ", resuming")
                // Short back-off, checked often so a cancel is prompt.
                val wakeAt = System.currentTimeMillis() + minOf(250L shl minOf(attempt, 4), 4_000L)
                while (!cancelled && System.currentTimeMillis() < wakeAt) Thread.sleep(50)
            }
            setState(State.CANCELLED)
            closeFiles()
            part.delete()
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private class Refused(message: String) : IOException(message)
    private class TooLarge(message: String) : IOException(message)

    /** One connection: from [written] to the end. True when the whole file is on disk. */
    private fun transfer(): Boolean {
        val from = written
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CabinEQ")
            // Identity: the byte offsets must be those of the file itself.
            setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && from > 0) {
                // Asked for bytes past the end: everything is already here.
                if (total < 0) total = from
                return true
            }
            if (code in 400..499) throw Refused("HTTP $code")
            if (code !in 200..299) throw IOException("HTTP $code")
            val length = conn.contentLengthLong
            var skip = 0L
            if (code == 206) {
                acceptsRanges = true
                // "bytes 100-999/1000"
                conn.getHeaderField("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { total = it }
                if (total < 0 && length >= 0) total = from + length
            } else {
                // 200: the whole file, even if a range was asked for. Skip what is on disk.
                skip = from
                if (length >= 0) total = length
                if (conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true) acceptsRanges = true
            }
            if (total > maxBytes) throw TooLarge("${total / 1024 / 1024} MB is over the ${maxBytes / 1024 / 1024} MB limit")
            if (state == State.STARTING) setState(State.RUNNING)
            conn.inputStream.use { input ->
                skipFully(input, skip)
                val buf = ByteArray(64 * 1024)
                val wrap = ByteBuffer.wrap(buf)
                val ch = channel ?: throw IOException("closed")
                while (!cancelled) {
                    val n = input.read(buf)
                    if (n < 0) break
                    wrap.clear().limit(n)
                    var pos = written
                    while (wrap.hasRemaining()) pos += ch.write(wrap, pos)
                    synchronized(lock) {
                        written = pos
                        lock.notifyAll()
                    }
                    if (written > maxBytes) throw TooLarge("over the ${maxBytes / 1024 / 1024} MB limit")
                }
            }
            if (cancelled) return false
            // The stream ended. Complete only if it reached the size the server announced.
            return total < 0 || written >= total
        } finally {
            conn.disconnect()
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0 && !cancelled) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw IOException("stream ended while skipping")
            left -= n
        }
    }

    private fun finish() {
        if (total < 0) total = written
        runCatching { channel?.force(false) }
        // Rename under the same name the cache looks for. Open readers keep their handle.
        val renamed = written > 0 && (part.renameTo(target) || (target.exists() && target.length() == written))
        if (!renamed) log("$label: finished but could not be kept in the cache")
        setState(State.DONE)
        closeSide()
        if (released) closeFiles()
    }

    private fun fail(message: String) {
        error = message
        log("$label: download failed: $message")
        setState(State.FAILED)
        closeFiles()
        part.delete()
    }

    private fun setState(s: State) {
        synchronized(lock) {
            state = s
            lock.notifyAll()
        }
    }

    private fun closeFiles() {
        closeSide()
        synchronized(lock) {
            runCatching { raf?.close() }
            raf = null
            channel = null
        }
    }

    /** Readers are done with this download (the file itself stays if it completed). */
    fun release() {
        released = true
        if (isAlive) return          // still being written; closed when the download ends
        closeFiles()
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads up to [size] bytes at [position] into [buffer]. Blocks while those bytes are still on
     * their way. Returns -1 at the end of the file.
     *
     * @throws IOException when the download failed or [isCancelled] turned true while waiting.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int, isCancelled: () -> Boolean = { false }): Int {
        if (size <= 0) return 0
        while (true) {
            val have = written
            if (position < have) return readDisk(position, buffer, offset, minOf(size.toLong(), have - position).toInt())
            when (state) {
                State.DONE -> return -1
                State.FAILED -> throw IOException("download failed: ${error ?: "unknown"}")
                State.CANCELLED -> throw IOException("download cancelled")
                else -> Unit
            }
            val end = total
            if (end in 0..position) return -1
            // Far ahead of the download: fetch just that range instead of waiting for it all.
            if (acceptsRanges && position - have > farAheadBytes) {
                val n = runCatching { readSide(position, buffer, offset, size) }.getOrDefault(0)
                if (n != 0) return n
            }
            synchronized(lock) {
                if (position >= written && isAlive) lock.wait(100)
            }
            if (isCancelled()) throw IOException("read cancelled")
        }
    }

    private fun readDisk(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val ch = synchronized(lock) { channel } ?: reopen()
        val n = ch.read(ByteBuffer.wrap(buffer, offset, size), position)
        return if (n < 0) 0 else n
    }

    /** After the writer closed its handle (finished or failed), read from the finished file. */
    private fun reopen(): FileChannel = synchronized(lock) {
        channel ?: run {
            val f = RandomAccessFile(if (target.exists()) target else part, "r")
            raf = f
            f.channel.also { channel = it }
        }
    }

    /** A second, ranged connection for reads far past the download point. */
    private class Side(val conn: HttpURLConnection, val input: InputStream, var position: Long)

    private fun readSide(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(sideLock) {
        var s = side
        if (s == null || s.position != position) {
            closeSide()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CabinEQ")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=$position-")
            }
            if (conn.responseCode != 206) {
                conn.disconnect()
                acceptsRanges = false          // wait for the main download instead
                return 0
            }
            s = Side(conn, conn.inputStream, position)
            side = s
        }
        val n = try {
            s.input.read(buffer, offset, size)
        } catch (e: IOException) {
            closeSide()
            return 0
        }
        if (n < 0) {
            closeSide()
            return -1
        }
        s.position += n
        n
    }

    private fun closeSide() = synchronized(sideLock) {
        side?.let {
            runCatching { it.input.close() }
            runCatching { it.conn.disconnect() }
        }
        side = null
    }
}
