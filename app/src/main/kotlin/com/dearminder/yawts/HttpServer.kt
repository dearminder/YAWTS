package com.dearminder.yawts

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket

/**
 * A small HTTP/1.1 server: keep-alive, chunked bodies in both directions, Expect: 100-continue
 * and any request method (so WebDAV verbs like PROPFIND work, which NanoHTTPD rejects).
 * Bodies are streamed straight from/to the socket - nothing is buffered in memory or temp files.
 *
 * Nothing thrown while serving a connection may escape its thread: on Android an uncaught exception
 * kills the whole process, taking the web page and the WebDAV share down with it.
 *
 * Deliberately free of Android imports, so it can be exercised on a plain JVM.
 */
class HttpServer(
    private val port: Int,
    private val sslFactory: SSLServerSocketFactory?,
    private val handler: (Request) -> Response,
    private val log: ServerLog = ServerLog.None
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // Each open connection, with when it started waiting for a request and when its current write started.
    private val connections = ConcurrentHashMap<Socket, ConnectionState>()
    private val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "yawts-http").apply { isDaemon = true }
    }
    private var watchdog: ScheduledExecutorService? = null

    /** Binds the port (throws if it's taken or not allowed) and starts accepting connections. */
    fun start() {
        val socket = sslFactory?.createServerSocket(port) ?: ServerSocket(port)
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "yawts-accept").apply { isDaemon = true; start() }
        watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "yawts-watchdog").apply { isDaemon = true } }
            .apply { scheduleWithFixedDelay(::closeStalledWrites, WATCHDOG_INTERVAL_S, WATCHDOG_INTERVAL_S, TimeUnit.SECONDS) }
    }

    /** Stops listening and cuts off every open connection, including transfers in progress. */
    fun stop() {
        serverSocket?.closeQuietly()
        // The port keeps listening until the thread blocked in accept() wakes up and lets go of the
        // socket, so wait for it - otherwise starting again on the same port can fail.
        acceptThread?.join(STOP_WAIT_MS)
        watchdog?.shutdownNow()
        connections.keys.forEach { it.closeQuietly() }
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // E.g. out of file descriptors: back off instead of spinning at full CPU.
                if (!socket.isClosed) Thread.sleep(ACCEPT_RETRY_MS)
                continue
            }
            try {
                startConnection(client)
            } catch (e: RejectedExecutionException) {
                if (!socket.isClosed) log.error("Connection limit ($MAX_CONNECTIONS) reached, refusing ${client.inetAddress?.hostAddress}", null)
                client.closeQuietly()
            } catch (e: Throwable) {
                log.error("Could not start a connection thread", e)
                client.closeQuietly()
            }
        }
    }

    /**
     * Clients like the Windows WebDAV redirector open lots of parallel connections and leave them
     * idle in keep-alive, so the threads can run out while nothing is actually being served. When
     * they do, the connection that has waited longest for its next request is closed to make room -
     * clients expect servers to drop idle keep-alive connections and just open a new one.
     */
    private fun startConnection(client: Socket) {
        repeat(EVICT_ATTEMPTS) {
            try {
                pool.execute { serveConnection(client) }
                return
            } catch (e: RejectedExecutionException) {
                if (pool.isShutdown) throw e
                val idlest = connections.entries
                    .filter { it.value.idleSince != 0L }
                    .minByOrNull { it.value.idleSince }
                    ?: throw e
                idlest.key.closeQuietly()
                // Its thread needs a moment to notice and return to the pool.
                Thread.sleep(EVICT_WAIT_MS)
            }
        }
        pool.execute { serveConnection(client) }
    }

    private fun serveConnection(socket: Socket) {
        val state = ConnectionState()
        connections[socket] = state
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val output = BufferedOutputStream(TimedOutputStream(socket.getOutputStream(), state), BUFFER_SIZE)
            while (true) {
                // Waiting for the next request gets a shorter timeout than reading one.
                state.idleSince = System.currentTimeMillis()
                socket.soTimeout = KEEP_ALIVE_TIMEOUT_MS
                if (!handleRequest(socket, state, input, output)) break
            }
        } catch (e: IOException) {
            // Client went away, timed out or sent garbage - nothing to answer.
        } catch (e: Throwable) {
            log.error("Connection from ${socket.inetAddress?.hostAddress} failed", e)
        } finally {
            connections.remove(socket)
            socket.closeQuietly()
        }
    }

    /**
     * Socket timeouts only cover reads, so a client that stops reading (a paused video, a hung
     * file manager) would otherwise hold its thread forever - and once all of them are taken,
     * nobody can connect any more.
     */
    private fun closeStalledWrites() {
        val now = System.currentTimeMillis()
        for ((socket, state) in connections) {
            val since = state.writingSince
            if (since != 0L && now - since > WRITE_TIMEOUT_MS) socket.closeQuietly()
        }
    }

    /** Reads and answers one request. Returns whether the connection can be reused. */
    private fun handleRequest(socket: Socket, state: ConnectionState, input: InputStream, output: OutputStream): Boolean {
        var requestLine = readLine(input) ?: return false
        if (requestLine.isEmpty()) requestLine = readLine(input) ?: return false // stray CRLF after a body
        state.idleSince = 0L
        socket.soTimeout = IDLE_TIMEOUT_MS
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            writeResponse(output, "GET", Response.text(400, "Bad request"), keepAlive = false, http11 = true)
            return false
        }
        val (method, target, version) = parts
        val started = System.currentTimeMillis()
        val http11 = version == "HTTP/1.1"

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
            if (headers.size > MAX_HEADERS) return false
        }

        val rawBody = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            ChunkedInputStream(input)
        } else {
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            if (length < 0) return false
            LimitedInputStream(input, length)
        }
        val expectContinue = http11 && headers["expect"]?.equals("100-continue", ignoreCase = true) == true
        val body = ContinueInputStream(rawBody, if (expectContinue) output else null)

        val query = target.indexOf('?')
        var rawPath = if (query >= 0) target.substring(0, query) else target
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
            rawPath = "/" + rawPath.substringAfter("://").substringAfter('/', "")
        }
        val request = Request(
            method = method.uppercase(Locale.ROOT),
            path = percentDecode(rawPath),
            query = parseQuery(if (query >= 0) target.substring(query + 1) else ""),
            headers = headers,
            body = body,
            remoteAddress = socket.inetAddress,
            secure = socket is SSLSocket
        )

        val response = try {
            handler(request)
        } catch (e: HttpException) {
            Response.text(e.status, e.message ?: "Error")
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            // Including errors like OutOfMemoryError - one bad request must not end the server.
            log.error("${request.method} $target failed", e)
            Response.text(500, "Internal server error")
        }

        val connection = headers["connection"]
        var keepAlive = if (http11) connection?.contains("close", ignoreCase = true) != true
                        else connection?.contains("keep-alive", ignoreCase = true) == true
        // Whatever part of the body the handler didn't read has to be skipped before the next
        // request can be parsed - unless the client is still waiting for "100 Continue", in which
        // case it may never send the body at all, so the connection just gets closed instead.
        if (expectContinue && !body.continueSent) keepAlive = false
        else if (!body.drain(DRAIN_LIMIT)) keepAlive = false

        val reuse = writeResponse(output, request.method, response, keepAlive, http11)
        log.request(request.remoteAddress, request.method, target, response.status, System.currentTimeMillis() - started)
        return reuse
    }

    private fun writeResponse(out: OutputStream, method: String, res: Response, keepAlive: Boolean, http11: Boolean): Boolean {
        var reuse = keepAlive
        val hasBody = method != "HEAD" && res.status != 204 && res.status != 304
        var chunked = false

        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(res.status).append(' ').append(reasonPhrase(res.status)).append("\r\n")
        head.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n")
        head.append("Server: YAWTS\r\n")
        res.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
        for ((name, value) in res.headers) head.append(name).append(": ").append(value).append("\r\n")
        if (res.status != 204 && res.status != 304) {
            when {
                res.length >= 0 -> head.append("Content-Length: ").append(res.length).append("\r\n")
                !hasBody -> {}
                http11 -> { chunked = true; head.append("Transfer-Encoding: chunked\r\n") }
                else -> reuse = false
            }
        }
        head.append(if (reuse) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))

        val writer = res.writer
        if (hasBody && writer != null) {
            if (chunked) {
                val chunkedOut = ChunkedOutputStream(out)
                val buffered = BufferedOutputStream(chunkedOut, BUFFER_SIZE)
                writer(buffered)
                buffered.flush()
                chunkedOut.finish()
            } else {
                writer(out)
            }
        }
        out.flush()
        return reuse
    }

    companion object {
        const val BUFFER_SIZE = 64 * 1024
        private const val MAX_CONNECTIONS = 64
        private const val MAX_HEADERS = 100
        private const val IDLE_TIMEOUT_MS = 60_000
        private const val KEEP_ALIVE_TIMEOUT_MS = 15_000
        private const val EVICT_ATTEMPTS = 3
        private const val EVICT_WAIT_MS = 20L
        private const val DRAIN_LIMIT = 256 * 1024L
        private const val WRITE_TIMEOUT_MS = 60_000L
        private const val WATCHDOG_INTERVAL_S = 10L
        private const val ACCEPT_RETRY_MS = 200L
        private const val STOP_WAIT_MS = 1000L
    }
}

/** Where the server reports what it does - the app writes it to files when its developer options ask for it. */
interface ServerLog {
    fun request(remote: InetAddress?, method: String, target: String, status: Int, millis: Long) {}
    fun error(message: String, error: Throwable?) {}

    object None : ServerLog
}

class Request(
    val method: String,
    /** The decoded path, e.g. "/Download/My file.txt". */
    val path: String,
    val query: Map<String, List<String>>,
    private val headers: Map<String, String>,
    val body: InputStream,
    val remoteAddress: InetAddress?,
    /** Whether it came in over HTTPS. */
    val secure: Boolean = false,
    /** URL prefix the [path] was found under (e.g. "/webdav"), already stripped from [path]. */
    val mount: String = ""
) {
    /** The same request with [prefix] taken off the front of its path. */
    fun under(prefix: String): Request =
        Request(method, path.removePrefix(prefix).ifEmpty { "/" }, query, headers, body, remoteAddress, secure, prefix)

    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    fun param(name: String): String? = query[name]?.firstOrNull()

    val contentLength: Long get() = header("content-length")?.toLongOrNull() ?: -1L

    fun cookie(name: String): String? = header("cookie")
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

    /** The body as text, refusing anything over [limit] bytes (forms and XML are tiny). */
    fun bodyText(limit: Int = 1 shl 20): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw HttpException(413, "Request body too large")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Parses an application/x-www-form-urlencoded body (always UTF-8). */
    fun form(): Map<String, List<String>> = parseQuery(bodyText())
}

class Response(
    val status: Int,
    val contentType: String? = null,
    /** Exact body size, or -1 when unknown (the body is then sent chunked). */
    val length: Long = 0,
    val writer: ((OutputStream) -> Unit)? = null
) {
    val headers = ArrayList<Pair<String, String>>()

    fun header(name: String, value: String) = apply { headers += name to value }

    companion object {
        fun empty(status: Int) = Response(status)

        fun bytes(status: Int, type: String, data: ByteArray) =
            Response(status, type, data.size.toLong()) { it.write(data) }

        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") =
            bytes(status, type, text.toByteArray(Charsets.UTF_8))

        fun json(status: Int, json: String) = text(status, json, "application/json; charset=utf-8")

        fun redirect(location: String) = empty(302).header("Location", location)

        fun stream(type: String, writer: (OutputStream) -> Unit) = Response(200, type, -1, writer)
    }
}

class HttpException(val status: Int, message: String) : Exception(message)

// ---------- Body streams ----------

/** Exactly [remaining] bytes of a Content-Length body; never closes the socket stream. */
private class LimitedInputStream(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = input.read()
        if (b < 0) throw EOFException("Request body ended early")
        remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("Request body ended early")
        remaining -= n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), remaining).toInt()
    override fun close() {}
}

/** Decodes a "Transfer-Encoding: chunked" body (sent e.g. by macOS Finder and davfs2). */
private class ChunkedInputStream(private val input: InputStream) : InputStream() {
    private var remaining = 0L
    private var started = false
    private var finished = false

    private fun nextChunk(): Boolean {
        if (finished) return false
        if (remaining > 0) return true
        if (started) readLine(input) // the CRLF closing the previous chunk
        started = true
        val sizeLine = readLine(input) ?: throw EOFException("Request body ended early")
        remaining = sizeLine.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("Bad chunk size")
        if (remaining == 0L) {
            while (readLine(input)?.isNotEmpty() == true) { /* skip trailers */ }
            finished = true
            return false
        }
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!nextChunk()) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("Request body ended early")
        remaining -= n
        return n
    }

    override fun close() {}
}

/** Sends "100 Continue" the first time the handler actually wants the body. */
private class ContinueInputStream(private val input: InputStream, private val output: OutputStream?) : InputStream() {
    var continueSent = false
        private set

    private fun sendContinue() {
        if (output != null && !continueSent) {
            continueSent = true
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    override fun read(): Int { sendContinue(); return input.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int { sendContinue(); return input.read(b, off, len) }
    override fun available(): Int = input.available()
    override fun close() {}

    /** Skips the rest of the body; false if it was larger than [limit] or broken. */
    fun drain(limit: Long): Boolean {
        val buf = ByteArray(8192)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) return true
                total += n
                if (total > limit) return false
            }
        } catch (e: IOException) {
            return false
        }
    }
}

private class ConnectionState {
    /** When it started waiting for the next request, or 0 while a request is being served. */
    @Volatile var idleSince = 0L
    /** When its current write started, or 0 when it isn't writing. */
    @Volatile var writingSince = 0L
}

/** Marks when a write to the socket starts and ends, for [HttpServer]'s stalled-write watchdog. */
private class TimedOutputStream(private val out: OutputStream, private val clock: ConnectionState) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        clock.writingSince = System.currentTimeMillis()
        try { out.write(b, off, len) } finally { clock.writingSince = 0L }
    }

    override fun flush() {
        clock.writingSince = System.currentTimeMillis()
        try { out.flush() } finally { clock.writingSince = 0L }
    }

    override fun close() = out.close()
}

private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        out.write(Integer.toHexString(len).toByteArray(Charsets.US_ASCII))
        out.write(CRLF)
        out.write(b, off, len)
        out.write(CRLF)
    }

    fun finish() = out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
    override fun flush() = out.flush()
    override fun close() {}

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

// ---------- Helpers shared by the server, the web app API and WebDAV ----------

internal fun readLine(input: InputStream, maxLength: Int = 64 * 1024): String? {
    val buf = ByteArrayOutputStream(128)
    while (true) {
        val b = input.read()
        if (b < 0) {
            if (buf.size() == 0) return null
            break
        }
        if (b == '\n'.code) break
        if (buf.size() >= maxLength) throw IOException("Line too long")
        buf.write(b)
    }
    val line = String(buf.toByteArray(), Charsets.UTF_8)
    return if (line.endsWith('\r')) line.dropLast(1) else line
}

/** Decodes %XX escapes as UTF-8. Unlike URLDecoder, '+' stays a '+' unless [plusAsSpace]. */
internal fun percentDecode(s: String, plusAsSpace: Boolean = false): String {
    if (s.indexOf('%') < 0 && !(plusAsSpace && s.indexOf('+') >= 0)) return s
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && hexValue(s[i + 1]) >= 0 && hexValue(s[i + 2]) >= 0) {
            out.write(hexValue(s[i + 1]) * 16 + hexValue(s[i + 2]))
            i += 3
        } else if (c == '+' && plusAsSpace) {
            out.write(' '.code)
            i++
        } else {
            var end = i + 1
            while (end < s.length && s[end] != '%' && !(plusAsSpace && s[end] == '+')) end++
            out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** Percent-encodes one path segment, leaving only RFC 3986 unreserved characters as-is. */
internal fun encodePathSegment(segment: String): String {
    val sb = StringBuilder(segment.length + 16)
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A || c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E) {
            sb.append(c.toChar())
        } else {
            sb.append('%').append(HEX_DIGITS[c shr 4]).append(HEX_DIGITS[c and 0x0F])
        }
    }
    return sb.toString()
}

internal fun encodePath(relative: String): String = relative.split('/').joinToString("/") { encodePathSegment(it) }

internal fun parseQuery(query: String): Map<String, List<String>> {
    val result = LinkedHashMap<String, MutableList<String>>()
    if (query.isEmpty()) return result
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val key = percentDecode(if (eq >= 0) pair.substring(0, eq) else pair, plusAsSpace = true)
        val value = if (eq >= 0) percentDecode(pair.substring(eq + 1), plusAsSpace = true) else ""
        result.getOrPut(key) { ArrayList(1) }.add(value)
    }
    return result
}

private val HTTP_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

internal fun httpDate(millis: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(millis))

internal fun parseHttpDate(text: String): Long? = try {
    ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (e: Exception) {
    null
}

internal fun reasonPhrase(status: Int): String = when (status) {
    100 -> "Continue"
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    206 -> "Partial Content"
    207 -> "Multi-Status"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    412 -> "Precondition Failed"
    413 -> "Payload Too Large"
    415 -> "Unsupported Media Type"
    416 -> "Range Not Satisfiable"
    423 -> "Locked"
    500 -> "Internal Server Error"
    507 -> "Insufficient Storage"
    else -> "Unknown"
}

internal fun java.io.Closeable.closeQuietly() {
    try { close() } catch (e: Exception) { }
}
