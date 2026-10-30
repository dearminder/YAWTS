package com.dearminder.yawts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Talks to a real [HttpServer] over a socket, the way browsers and WebDAV clients do. */
class HttpServerTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun start(handler: (Request) -> Response): Int {
        val port = ServerSocket(0).use { it.localPort }
        server = HttpServer(port, null, handler).apply { start() }
        return port
    }

    private fun connect(port: Int) = Socket("127.0.0.1", port).apply { soTimeout = 5000 }

    private fun Socket.send(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.UTF_8))
        getOutputStream().flush()
    }

    private fun readReply(input: InputStream, method: String = "GET"): Reply {
        val status = readLine(input)!!.split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input)!!
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase(Locale.ROOT)] = line.substringAfter(':').trim()
        }
        val body = ByteArrayOutputStream()
        if (method != "HEAD") {
            if (headers["transfer-encoding"] == "chunked") {
                while (true) {
                    val size = readLine(input)!!.toInt(16)
                    if (size == 0) { readLine(input); break }
                    repeat(size) { body.write(input.read()) }
                    readLine(input)
                }
            } else {
                repeat(headers["content-length"]?.toInt() ?: 0) { body.write(input.read()) }
            }
        }
        return Reply(status, headers, String(body.toByteArray(), Charsets.UTF_8))
    }

    private val echo: (Request) -> Response = { req ->
        Response.text(200, "${req.method} ${req.path} ${req.query} ${req.bodyText()}")
    }

    @Test
    fun answersAndKeepsTheConnectionAlive() {
        val port = start(echo)
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("GET /a%20b?x=1 HTTP/1.1\r\nHost: test\r\n\r\n")
            val first = readReply(input)
            assertEquals(200, first.status)
            assertEquals("GET /a b {x=[1]} ", first.body)
            assertEquals("keep-alive", first.headers["connection"])
            assertEquals("YAWTS", first.headers["server"])

            socket.send("PROPFIND / HTTP/1.1\r\nHost: test\r\nContent-Length: 5\r\n\r\nhello")
            assertEquals("PROPFIND / {} hello", readReply(input).body)

            socket.send("GET /last HTTP/1.1\r\nConnection: close\r\n\r\n")
            assertEquals("close", readReply(input).headers["connection"])
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun skipsUnreadBodiesBeforeTheNextRequest() {
        val port = start { Response.text(200, it.path) }
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("PUT /one HTTP/1.1\r\nContent-Length: 4\r\n\r\nbodyGET /two HTTP/1.1\r\n\r\n")
            assertEquals("/one", readReply(input).body)
            assertEquals("/two", readReply(input).body)
        }
    }

    @Test
    fun readsChunkedBodies() {
        val port = start(echo)
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("PUT /f HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n")
            assertEquals("PUT /f {} hello world", readReply(input).body)
        }
    }

    @Test
    fun sendsContinueOnlyWhenTheBodyIsWanted() {
        val port = start(echo)
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("PUT /f HTTP/1.1\r\nContent-Length: 3\r\nExpect: 100-continue\r\n\r\n")
            assertEquals("HTTP/1.1 100 Continue", readLine(input))
            assertEquals("", readLine(input))
            socket.send("abc")
            assertEquals("PUT /f {} abc", readReply(input).body)
        }
    }

    @Test
    fun streamsUnknownLengthResponsesChunked() {
        val port = start { Response.stream("text/plain") { out -> repeat(3) { out.write("part$it ".toByteArray()) } } }
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("GET / HTTP/1.1\r\n\r\n")
            val reply = readReply(input)
            assertEquals("chunked", reply.headers["transfer-encoding"])
            assertEquals("part0 part1 part2 ", reply.body)
        }
    }

    @Test
    fun headHasNoBody() {
        val port = start { Response.text(200, "hello") }
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("HEAD / HTTP/1.1\r\n\r\n")
            val reply = readReply(input, "HEAD")
            assertEquals("5", reply.headers["content-length"])
            socket.send("GET / HTTP/1.1\r\n\r\n")
            assertEquals("hello", readReply(input).body)
        }
    }

    @Test
    fun handlerErrorsBecomeResponses() {
        val port = start { req ->
            when (req.path) {
                "/conflict" -> throw HttpException(409, "nope")
                else -> throw IllegalStateException("bug")
            }
        }
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("GET /conflict HTTP/1.1\r\n\r\n")
            val conflict = readReply(input)
            assertEquals(409, conflict.status)
            assertEquals("nope", conflict.body)
            socket.send("GET /bug HTTP/1.1\r\n\r\n")
            assertEquals(500, readReply(input).status)
        }
        // The server is still up.
        connect(port).use { socket ->
            socket.send("GET /conflict HTTP/1.1\r\n\r\n")
            assertEquals(409, readReply(socket.getInputStream().buffered()).status)
        }
    }

    @Test
    fun rejectsMalformedRequestLines() {
        val port = start(echo)
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("GARBAGE\r\n")
            val reply = readReply(input)
            assertEquals(400, reply.status)
            assertEquals("close", reply.headers["connection"])
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun http10ClosesUnlessAskedToKeepAlive() {
        val port = start { Response.text(200, "ok") }
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("GET / HTTP/1.0\r\n\r\n")
            assertEquals("close", readReply(input).headers["connection"])
        }
        connect(port).use { socket ->
            val input = socket.getInputStream().buffered()
            socket.send("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")
            assertEquals("keep-alive", readReply(input).headers["connection"])
        }
    }

    @Test
    fun absoluteUrlsAreReducedToTheirPath() {
        val seen = AtomicReference<String>()
        val port = start { seen.set(it.path); Response.empty(204) }
        connect(port).use { socket ->
            socket.send("GET http://phone:8080/x/y HTTP/1.1\r\n\r\n")
            val reply = readReply(socket.getInputStream().buffered())
            assertEquals(204, reply.status)
            assertNull(reply.headers["content-length"])
        }
        assertEquals("/x/y", seen.get())
    }

    @Test
    fun stopClosesTheListeningSocket() {
        val port = start(echo)
        server!!.stop()
        server = null
        val refused = try { Socket("127.0.0.1", port).close(); false } catch (e: java.io.IOException) { true }
        assertTrue(refused)
    }
}
