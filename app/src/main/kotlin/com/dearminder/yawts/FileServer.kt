package com.dearminder.yawts

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Settings the server re-reads on every request, so changes made in the app apply immediately. */
data class ServerConfig(
    val password: String,
    val allowDelete: Boolean,
    val webDav: Boolean,
    /** Photo previews and song cover art in the web page's file list. */
    val thumbnails: Boolean,
    val dateFormat: String,
    val language: String,
    /** Shown on the web page, to SSDP/UPnP and DNS-SD clients. */
    val deviceName: String,
    /** Stable per install - the UPnP device UUID. */
    val deviceId: String,
    /** CSS overriding the web page's color variables with the phone's theme; empty keeps style.css's own. */
    val themeCss: String = ""
)

/** A small preview image, made by the app (decoding photos and songs needs Android). */
class Thumbnail(val data: ByteArray, val type: String)

/**
 * A file with what the listings show about it, read in a single stat call - File's getters make one
 * each, and on Android 11+ every one of them goes through the (slow) FUSE layer of shared storage.
 */
class FileEntry(val file: File, val isDirectory: Boolean, val length: Long, val lastModified: Long) {
    companion object {
        fun of(file: File): FileEntry = try {
            val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            FileEntry(file, a.isDirectory, if (a.isDirectory) 0L else a.size(), a.lastModifiedTime().toMillis())
        } catch (e: Exception) {
            FileEntry(file, false, 0L, 0L) // gone or unreadable - what File's getters report then
        }

        /** The entries of [dir], or none if it can't be read. */
        fun list(dir: File): List<FileEntry> = dir.listFiles()?.map { of(it) } ?: emptyList()
    }
}

/**
 * Everything the server exposes, all on one port:
 *  - "/"           the web app (a static page whose script talks to the JSON API below)
 *  - "/.yawts/..." the web app's assets and JSON API
 *  - any other path: a file or folder under [root]. Files are served with GET (with Range support,
 *    so videos can seek and downloads can resume) and, when enabled, the whole tree is a WebDAV
 *    share - the same address works in a browser and as a Windows/macOS/Linux network drive.
 *  - "/webdav/...": the same tree again, for clients that need a named share - Windows maps
 *    "\\ip@port\webdav" (a share at the bare root only works through the odd "DavWWWRoot" name).
 *
 * Free of Android imports (assets, settings and thumbnails come in as functions) so it runs on a plain JVM.
 */
class FileServer(
    root: File,
    private val config: () -> ServerConfig,
    private val asset: (String) -> ByteArray?,
    private val thumbnail: (File) -> Thumbnail? = { null }
) {
    val root: File = root.canonicalFile

    // token -> the password it was issued for, so changing the password logs everyone out.
    private val sessions = ConcurrentHashMap<String, String>()
    private val assetCache = ConcurrentHashMap<String, ByteArray>()
    private val random = SecureRandom()
    private val nonceSecret = randomHex(16)
    private val webDav = WebDav(this)

    fun handle(request: Request): Response {
        val cfg = config()
        val path = request.path
        if (path.startsWith(INTERNAL_PREFIX)) return handleInternal(request, cfg, path.substring(INTERNAL_PREFIX.length))
        if (path == "/" && (request.method == "GET" || request.method == "HEAD")) return indexPage(cfg)
        if (path == "/favicon.ico") return assetResponse("icon.png", "image/png")
        val req = if (path == DAV_MOUNT || path.startsWith("$DAV_MOUNT/")) request.under(DAV_MOUNT) else request
        // Windows probes "OPTIONS /" without credentials and gives up unless it sees a DAV header.
        if (req.method == "OPTIONS") return webDav.options(cfg.webDav)
        if (!isAuthorized(req, cfg)) return challenge()
        return when (req.method) {
            "GET", "HEAD" -> serveFile(req)
            else -> if (cfg.webDav) webDav.handle(req, cfg)
                    else Response.text(405, "WebDAV is turned off in the YAWTS app settings.").header("Allow", "GET, HEAD, OPTIONS")
        }
    }

    // ---------- Paths ----------

    /** Maps a path relative to [root] to a File, or null if it would escape [root]. */
    fun resolve(relative: String): File? {
        val clean = relative.replace('\\', '/').trim('/')
        val candidate = if (clean.isEmpty()) root else File(root, clean)
        val canonical = try { candidate.canonicalFile } catch (e: IOException) { return null }
        return if (canonical == root || canonical.path.startsWith(root.path + File.separator)) canonical else null
    }

    fun relativePath(file: File): String = file.path.removePrefix(root.path).trim('/')

    // ---------- Authentication ----------

    private fun isAuthorized(req: Request, cfg: ServerConfig): Boolean {
        if (cfg.password.isEmpty()) return true
        val token = req.cookie(SESSION_COOKIE)
        if (token != null && sessions[token] == cfg.password) return true
        val auth = req.header("authorization") ?: return false
        return when {
            auth.startsWith("Basic ", ignoreCase = true) -> checkBasic(auth.substring(6).trim(), cfg.password)
            auth.startsWith("Digest ", ignoreCase = true) -> checkDigest(req.method, auth.substring(7), cfg.password)
            else -> false
        }
    }

    private fun checkBasic(encoded: String, password: String): Boolean {
        val decoded = try {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return false
        }
        // The user name is ignored - only the password matters.
        return safeEquals(decoded.substringAfter(':', ""), password)
    }

    /**
     * Digest auth matters because Windows refuses Basic auth over plain HTTP by default - without
     * it a password-protected share couldn't be mapped as a drive at all.
     */
    private fun checkDigest(method: String, header: String, password: String): Boolean {
        val p = DIGEST_PARAM.findAll(header).associate {
            it.groupValues[1].lowercase(Locale.ROOT) to (it.groups[2]?.value ?: it.groupValues[3])
        }
        val nonce = p["nonce"] ?: return false
        if (!isValidNonce(nonce)) return false
        val ha1 = md5("${p["username"]}:${p["realm"]}:$password")
        val ha2 = md5("$method:${p["uri"]}")
        val qop = p["qop"]
        val expected = if (qop != null) md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return safeEquals(expected, p["response"]?.lowercase(Locale.ROOT) ?: "")
    }

    private fun newNonce(): String {
        val time = java.lang.Long.toHexString(System.currentTimeMillis())
        return "$time.${md5("$time:$nonceSecret")}"
    }

    private fun isValidNonce(nonce: String): Boolean {
        val time = nonce.substringBefore('.')
        val issued = time.toLongOrNull(16) ?: return false
        return System.currentTimeMillis() - issued < NONCE_LIFETIME_MS && safeEquals(nonce, "$time.${md5("$time:$nonceSecret")}")
    }

    private fun challenge(): Response = Response.text(401, "Authentication required")
        .header("WWW-Authenticate", "Digest realm=\"$REALM\", qop=\"auth\", nonce=\"${newNonce()}\", algorithm=MD5")
        .header("WWW-Authenticate", "Basic realm=\"$REALM\", charset=\"UTF-8\"")

    // ---------- Web app & API ----------

    private fun handleInternal(req: Request, cfg: ServerConfig, sub: String): Response {
        when (sub) {
            "app.js" -> return assetResponse("app.js", "application/javascript; charset=utf-8")
            "style.css" -> return assetResponse("style.css", "text/css; charset=utf-8")
            "favicon.svg" -> return assetResponse("favicon.svg", "image/svg+xml")
            "icon.png" -> return assetResponse("icon.png", "image/png")
            "description.xml" -> return deviceDescription(req, cfg)
            "api/login" -> return login(req, cfg)
        }
        if (!isAuthorized(req, cfg)) return jsonError(401, "auth")
        // Changes require a custom header, which a form or link on another site can't send.
        if ((req.method == "POST" || req.method == "PUT") && sub != "api/zip" && req.header("x-yawts") == null) {
            return jsonError(403, "forbidden")
        }
        return when (sub) {
            "api/list" -> list(req, cfg)
            "api/thumb" -> thumb(req, cfg)
            "api/upload" -> if (req.method == "PUT") upload(req) else methodNotAllowed()
            "api/mkdir" -> if (req.method == "POST") mkdir(req) else methodNotAllowed()
            "api/rename" -> if (req.method == "POST") rename(req) else methodNotAllowed()
            "api/delete" -> if (req.method == "POST") delete(req, cfg) else methodNotAllowed()
            "api/zip" -> zip(req)
            "api/logout" -> logout(req)
            else -> jsonError(404, "notFound")
        }
    }

    private fun indexPage(cfg: ServerConfig): Response {
        val html = loadAsset("index.html") ?: return Response.text(404, "Not found")
        val page = String(html, Charsets.UTF_8)
            .replace("{{LANG}}", cfg.language)
            .replace("{{NAME}}", htmlEscape(cfg.deviceName))
            .replace("{{THEME}}", cfg.themeCss)
        return Response.text(200, page, "text/html; charset=utf-8").header("Cache-Control", "no-cache")
    }

    /**
     * UPnP device description fetched by SSDP clients (Windows lists the phone under "Network"
     * and opens the presentation URL on double-click). With WebDAV on, that URL is the share as a
     * UNC path ("file://192.168.1.5@8080/webdav" = \\192.168.1.5@8080\webdav), so Explorer opens it
     * like a mapped drive; otherwise it's the web page.
     */
    private fun deviceDescription(req: Request, cfg: ServerConfig): Response {
        val name = htmlEscape(cfg.deviceName)
        val host = req.header("host")
        val presentation = if (cfg.webDav && host != null) {
            val ip = host.substringBeforeLast(':')
            val port = host.substringAfterLast(':', if (req.secure) "443" else "80")
            htmlEscape("file://$ip${if (req.secure) "@SSL" else ""}@$port$DAV_MOUNT")
        } else "/"
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            |<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:pnpx="http://schemas.microsoft.com/windows/pnpx/2005/11" xmlns:df="http://schemas.microsoft.com/windows/2008/09/devicefoundation">
            |<specVersion><major>1</major><minor>0</minor></specVersion>
            |<device>
            |<deviceType>${SsdpServer.DEVICE_TYPE}</deviceType>
            |<pnpx:X_deviceCategory>Computers</pnpx:X_deviceCategory>
            |<df:X_deviceCategory>Computers</df:X_deviceCategory>
            |<friendlyName>$name</friendlyName>
            |<manufacturer>YAWTS</manufacturer>
            |<modelName>YAWTS</modelName>
            |<modelDescription>Wi-Fi file sharing</modelDescription>
            |<UDN>uuid:${cfg.deviceId}</UDN>
            |<presentationURL>$presentation</presentationURL>
            |<iconList><icon><mimetype>image/png</mimetype><width>512</width><height>512</height><depth>32</depth><url>${INTERNAL_PREFIX}icon.png</url></icon></iconList>
            |</device>
            |</root>""".trimMargin()
        return Response.text(200, xml, "text/xml; charset=utf-8")
    }

    private fun assetResponse(name: String, type: String): Response {
        val data = loadAsset(name) ?: return Response.text(404, "Not found")
        return Response.bytes(200, type, data).header("Cache-Control", "no-cache")
    }

    private fun loadAsset(name: String): ByteArray? =
        assetCache[name] ?: asset(name)?.also { assetCache[name] = it }

    private fun login(req: Request, cfg: ServerConfig): Response {
        if (req.method != "POST") return methodNotAllowed()
        val submitted = req.form()["password"]?.firstOrNull() ?: ""
        if (cfg.password.isEmpty()) return Response.empty(204)
        if (!safeEquals(submitted, cfg.password)) {
            Thread.sleep(LOGIN_FAILURE_DELAY_MS) // makes guessing slow
            return jsonError(401, "password")
        }
        if (sessions.size > MAX_SESSIONS) sessions.clear()
        val token = randomHex(24)
        sessions[token] = cfg.password
        return Response.empty(204)
            .header("Set-Cookie", "$SESSION_COOKIE=$token; Path=/; Max-Age=$SESSION_SECONDS; HttpOnly; SameSite=Lax")
    }

    private fun logout(req: Request): Response {
        req.cookie(SESSION_COOKIE)?.let { sessions.remove(it) }
        return Response.empty(204).header("Set-Cookie", "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")
    }

    private fun list(req: Request, cfg: ServerConfig): Response {
        val dir = resolve(req.param("path") ?: "")?.takeIf { it.isDirectory } ?: return jsonError(404, "notFound")
        val children = FileEntry.list(dir)
        val sb = StringBuilder(256 + children.size * 80)
        sb.append("{\"path\":").append(jsonString(relativePath(dir)))
            .append(",\"canDelete\":").append(cfg.allowDelete)
            .append(",\"webDav\":").append(cfg.webDav)
            .append(",\"thumbs\":").append(cfg.thumbnails)
            .append(",\"auth\":").append(cfg.password.isNotEmpty())
            .append(",\"dateFormat\":").append(jsonString(cfg.dateFormat))
            .append(",\"free\":").append(dir.usableSpace)
            .append(",\"total\":").append(dir.totalSpace)
            .append(",\"items\":[")
        children.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"n\":").append(jsonString(e.file.name))
                .append(",\"d\":").append(if (e.isDirectory) 1 else 0)
                .append(",\"s\":").append(e.length)
                .append(",\"m\":").append(e.lastModified)
                .append('}')
        }
        sb.append("]}")
        return Response.json(200, sb.toString())
    }

    /** GET /.yawts/api/thumb?path=<file> - a preview image, or 404 if there's none (the page then shows an icon). */
    private fun thumb(req: Request, cfg: ServerConfig): Response {
        if (!cfg.thumbnails) return jsonError(404, "notFound")
        val file = resolve(req.param("path") ?: "")?.takeIf { it.isFile } ?: return jsonError(404, "notFound")
        val etag = "\"t${java.lang.Long.toHexString(file.lastModified())}-${java.lang.Long.toHexString(file.length())}\""
        if (req.header("if-none-match") == etag) return Response.empty(304).header("ETag", etag)
        val thumb = thumbnail(file) ?: return jsonError(404, "notFound")
        // The page asks with the file's mtime in the URL, so a cached preview never goes stale.
        return Response.bytes(200, thumb.type, thumb.data)
            .header("ETag", etag)
            .header("Cache-Control", "private, max-age=604800")
    }

    /** PUT /.yawts/api/upload?path=<folder>&name=<file or sub/folder/file>&mtime=<ms>, raw file as the body. */
    private fun upload(req: Request): Response {
        val dir = resolve(req.param("path") ?: "")?.takeIf { it.isDirectory } ?: return jsonError(404, "notFound")
        val name = sanitizeRelativeName(req.param("name") ?: "") ?: return jsonError(400, "badName")
        val requested = resolve(relativePath(dir) + "/" + name) ?: return jsonError(403, "forbidden")
        if (req.contentLength > dir.usableSpace) return jsonError(507, "noSpace")
        val parent = requested.parentFile ?: return jsonError(403, "forbidden")
        if (!parent.isDirectory && !parent.mkdirs()) return jsonError(500, "writeFailed")
        // Never overwrite silently - a second "photo.jpg" becomes "photo (1).jpg".
        val target = uniqueFile(requested)
        if (!receiveFile(req.body, target)) return jsonError(500, "writeFailed")
        req.param("mtime")?.toLongOrNull()?.let { if (it > 0) target.setLastModified(it) }
        return Response.json(201, "{\"name\":${jsonString(target.name)}}")
    }

    private fun mkdir(req: Request): Response {
        val form = req.form()
        val dir = resolve(form["path"]?.firstOrNull() ?: "")?.takeIf { it.isDirectory } ?: return jsonError(404, "notFound")
        val name = sanitizeName(form["name"]?.firstOrNull() ?: "") ?: return jsonError(400, "badName")
        val target = File(dir, name)
        if (target.exists()) return jsonError(409, "exists")
        return if (target.mkdir()) Response.json(201, "{}") else jsonError(500, "writeFailed")
    }

    private fun rename(req: Request): Response {
        val form = req.form()
        val source = resolve(form["path"]?.firstOrNull() ?: "")
        if (source == null || source == root || !source.exists()) return jsonError(404, "notFound")
        val name = sanitizeName(form["name"]?.firstOrNull() ?: "") ?: return jsonError(400, "badName")
        val target = File(source.parentFile, name)
        if (target.name == source.name) return Response.json(200, "{}")
        // Allow a pure case change ("a.jpg" -> "A.jpg") on case-insensitive storage.
        if (target.exists() && !target.name.equals(source.name, ignoreCase = true)) return jsonError(409, "exists")
        return if (source.renameTo(target)) Response.json(200, "{}") else jsonError(500, "writeFailed")
    }

    private fun delete(req: Request, cfg: ServerConfig): Response {
        if (!cfg.allowDelete) return jsonError(403, "deleteDisabled")
        val failed = ArrayList<String>()
        for (path in req.form()["p"].orEmpty()) {
            val target = resolve(path)
            if (target == null || target == root) { failed += path; continue }
            if (!target.exists()) continue
            if (!target.deleteRecursively()) failed += target.name
        }
        return Response.json(200, "{\"failed\":[${failed.joinToString(",") { jsonString(it) }}]}")
    }

    /** Streams a ZIP of the given items (GET query or POST form, each "p" a relative path). */
    private fun zip(req: Request): Response {
        val paths = if (req.method == "POST") req.form()["p"].orEmpty() else req.query["p"].orEmpty()
        val targets = paths.mapNotNull { resolve(it) }.filter { it.exists() }.distinct()
        if (targets.isEmpty()) return jsonError(404, "notFound")
        val zipName = if (targets.size == 1) targets[0].name.ifEmpty { "storage" } else "yawts-${targets.size}"
        return Response.stream("application/zip") { out ->
            val zos = ZipOutputStream(out)
            for (target in targets) addToZip(zos, target, target.name.ifEmpty { "storage" })
            zos.finish()
        }.header("Content-Disposition", attachmentHeader("$zipName.zip"))
    }

    private fun addToZip(zos: ZipOutputStream, file: File, name: String) {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$name/"))
                zos.closeEntry()
            }
            children?.forEach { addToZip(zos, it, "$name/${it.name}") }
            return
        }
        val input = try { FileInputStream(file) } catch (e: FileNotFoundException) { return } // unreadable: skip it
        input.use {
            // Photos, videos and archives don't shrink - storing them is much faster on a phone.
            zos.setLevel(if (file.extension.lowercase(Locale.ROOT) in INCOMPRESSIBLE) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
            zos.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
            it.copyTo(zos, HttpServer.BUFFER_SIZE)
            zos.closeEntry()
        }
    }

    // ---------- Files ----------

    private fun serveFile(req: Request): Response {
        val file = resolve(req.path) ?: return Response.text(403, "Forbidden")
        if (!file.exists()) return Response.text(404, "Not found")
        // A folder opened in a browser goes to the web app, showing that folder.
        if (file.isDirectory) return Response.redirect("/#/" + encodePath(relativePath(file)))
        return fileResponse(req, file, download = req.param("dl") != null)
    }

    /** A file's content, honoring Range (single range) and If-None-Match. */
    fun fileResponse(req: Request, file: File, download: Boolean): Response {
        val length = file.length()
        val modified = file.lastModified()
        val etag = "\"${java.lang.Long.toHexString(modified)}-${java.lang.Long.toHexString(length)}\""
        if (req.header("if-none-match") == etag) return Response.empty(304).header("ETag", etag)

        var start = 0L
        var end = length - 1
        var partial = false
        val range = req.header("range")
        val ifRange = req.header("if-range")
        if (range != null && range.startsWith("bytes=") && !range.contains(',') && (ifRange == null || ifRange == etag)) {
            val spec = range.substring(6).trim()
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                val suffix = to.toLongOrNull()
                if (suffix != null && suffix > 0) { start = maxOf(0L, length - suffix); partial = true }
            } else {
                val first = from.toLongOrNull()
                if (first != null) {
                    start = first
                    to.toLongOrNull()?.let { end = minOf(it, length - 1) }
                    partial = true
                }
            }
            if (partial && (start >= length || start > end)) {
                return Response.empty(416).header("Content-Range", "bytes */$length")
            }
        }

        val count = if (length == 0L) 0L else end - start + 1
        val type = mimeType(file.name)
        val res = Response(if (partial) 206 else 200, type, count) { out -> copyRange(file, start, count, out) }
            .header("Accept-Ranges", "bytes")
            .header("ETag", etag)
            .header("Last-Modified", httpDate(modified))
            .header("X-Content-Type-Options", "nosniff")
        if (partial) res.header("Content-Range", "bytes $start-$end/$length")
        if (download) {
            res.header("Content-Disposition", attachmentHeader(file.name))
        } else if (type.startsWith("text/html") || type.startsWith("image/svg") || type.contains("xml")) {
            // An HTML/SVG file from the phone must not be able to run scripts as this site.
            res.header("Content-Security-Policy", "sandbox")
        }
        return res
    }

    private fun copyRange(file: File, start: Long, count: Long, out: OutputStream) {
        FileInputStream(file).use { input ->
            if (start > 0) input.channel.position(start)
            val buf = ByteArray(HttpServer.BUFFER_SIZE)
            var left = count
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("File shrank while sending")
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    /**
     * Writes [input] to [target] through a hidden temp file in the same folder, so a broken
     * transfer never leaves a truncated file behind (and never clobbers an existing one).
     */
    fun receiveFile(input: InputStream, target: File): Boolean {
        val temp = File(target.parentFile, ".yawts-${randomHex(6)}.part")
        try {
            FileOutputStream(temp).use { input.copyTo(it, HttpServer.BUFFER_SIZE) }
            if (temp.renameTo(target)) return true
            if (target.exists() && target.delete() && temp.renameTo(target)) return true
        } catch (e: Exception) {
            // fall through - and never leave the temp file behind
        }
        temp.delete()
        return false
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val dot = file.name.lastIndexOf('.')
        val base = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(file.parentFile, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    // ---------- Small helpers ----------

    private fun jsonError(status: Int, code: String) = Response.json(status, "{\"error\":\"$code\"}")

    private fun methodNotAllowed() = jsonError(405, "method")

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val INTERNAL_PREFIX = "/.yawts/"
        const val DAV_MOUNT = "/webdav"
        private const val SESSION_COOKIE = "yawts_session"
        private const val SESSION_SECONDS = 7 * 24 * 3600
        private const val MAX_SESSIONS = 1000
        private const val LOGIN_FAILURE_DELAY_MS = 600L
        private const val NONCE_LIFETIME_MS = 12 * 3600 * 1000L
        private const val REALM = "YAWTS"
        private val DIGEST_PARAM = Regex("(\\w+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,\\s]*))")

        private val INCOMPRESSIBLE = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "mkv", "mov", "webm", "avi", "3gp",
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "7z", "rar", "gz", "xz", "bz2", "apk", "pdf", "docx", "xlsx", "pptx"
        )

        private val MIME_TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
            "txt" to "text/plain; charset=utf-8", "log" to "text/plain; charset=utf-8", "md" to "text/plain; charset=utf-8",
            "csv" to "text/csv; charset=utf-8", "json" to "application/json", "xml" to "application/xml",
            "css" to "text/css", "js" to "text/javascript", "srt" to "text/plain; charset=utf-8", "vtt" to "text/vtt",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "svg" to "image/svg+xml", "ico" to "image/x-icon",
            "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
            "opus" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac",
            "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
            "mov" to "video/quicktime", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
            "pdf" to "application/pdf", "zip" to "application/zip", "apk" to "application/vnd.android.package-archive"
        )

        fun mimeType(name: String): String =
            MIME_TYPES[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

        /** A single file/folder name, or null if it's empty or tries to be "." / ".." / a path. */
        fun sanitizeName(name: String): String? {
            val clean = name.filter { it >= ' ' && it != '/' && it != '\\' }.trim()
            return clean.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        }

        /** "sub/folder/file.txt" from a folder upload - every segment sanitized, ".." rejected. */
        fun sanitizeRelativeName(name: String): String? {
            val segments = name.replace('\\', '/').split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null
            return segments.map { sanitizeName(it) ?: return null }.joinToString("/")
        }

        fun attachmentHeader(name: String): String {
            val ascii = name.map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }.joinToString("")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''${encodePathSegment(name)}"
        }

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        fun htmlEscape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun safeEquals(a: String, b: String) =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
