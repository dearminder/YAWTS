package com.dearminder.yawts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** A request as [HttpServer] would hand it to [FileServer.handle]. */
fun request(
    method: String,
    path: String,
    query: Map<String, List<String>> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    body: String = ""
): Request {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val allHeaders = headers.mapKeys { it.key.lowercase(Locale.ROOT) }.toMutableMap()
    if (bytes.isNotEmpty()) allHeaders.putIfAbsent("content-length", bytes.size.toString())
    return Request(method, path, query, allHeaders, ByteArrayInputStream(bytes), null)
}

fun Response.bodyBytes(): ByteArray = ByteArrayOutputStream().also { writer?.invoke(it) }.toByteArray()

fun Response.bodyText(): String = String(bodyBytes(), Charsets.UTF_8)

fun Response.headerValue(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

fun Response.headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

fun config(
    password: String = "",
    allowDelete: Boolean = false,
    webDav: Boolean = true,
    thumbnails: Boolean = false
) = ServerConfig(
    password = password,
    allowDelete = allowDelete,
    webDav = webDav,
    thumbnails = thumbnails,
    dateFormat = "dd-MM-yyyy HH:mm",
    language = "en",
    deviceName = "Test <Phone>",
    deviceId = "00000000-0000-0000-0000-000000000000"
)

/** A [FileServer] over [root] whose settings can be swapped between requests. */
class TestServer(val root: File, var cfg: ServerConfig = config()) {
    val assets = mutableMapOf(
        "index.html" to "<html lang=\"{{LANG}}\"><title>{{NAME}}</title></html>".toByteArray(),
        "favicon.svg" to "<svg/>".toByteArray()
    )
    val server = FileServer(root, { cfg }, { assets[it] })

    fun handle(req: Request): Response = server.handle(req)

    /** Same as a request from the web page, which always sends the X-YAWTS header. */
    fun api(method: String, sub: String, query: Map<String, List<String>> = emptyMap(), body: String = "", headers: Map<String, String> = emptyMap()) =
        handle(request(method, FileServer.INTERNAL_PREFIX + sub, query, headers + ("x-yawts" to "1"), body))
}

fun q(vararg pairs: Pair<String, String>): Map<String, List<String>> =
    pairs.groupBy({ it.first }, { it.second })
