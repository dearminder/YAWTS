package com.dearminder.yawts

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV class 1 and 2 on top of [FileServer]'s storage root (GET/HEAD are served by FileServer).
 *
 * Locks are accepted but not enforced: Windows Explorer and macOS Finder only mount a share
 * read-write when LOCK works, but a single-user phone share doesn't need real locking.
 * Likewise PROPPATCH always succeeds, and the modification time Windows sends with it is applied,
 * so copied files keep their original dates.
 */
class WebDav(private val server: FileServer) {

    fun options(enabled: Boolean): Response {
        val res = Response.empty(200).header("Allow", if (enabled) METHODS else "OPTIONS, GET, HEAD")
        if (enabled) res.header("DAV", "1, 2").header("MS-Author-Via", "DAV")
        return res
    }

    fun handle(req: Request, cfg: ServerConfig): Response = when (req.method) {
        "PROPFIND" -> propfind(req)
        "PROPPATCH" -> proppatch(req)
        "MKCOL" -> mkcol(req)
        "PUT" -> put(req)
        "DELETE" -> delete(req, cfg)
        "COPY" -> copyOrMove(req, move = false)
        "MOVE" -> copyOrMove(req, move = true)
        "LOCK" -> lock(req)
        "UNLOCK" -> Response.empty(204)
        else -> Response.text(405, "Method not allowed").header("Allow", METHODS)
    }

    private fun propfind(req: Request): Response {
        val file = server.resolve(req.path) ?: return forbidden()
        if (!file.exists()) return notFound()
        // Written straight to UTF-8 bytes: a folder with tens of thousands of files (e.g. .thumbnails)
        // makes a response of many megabytes, and building it as a String first takes several times that.
        val entry = FileEntry.of(file)
        // Lets Windows show free space for the mapped drive. Asked once: everything listed is on the same volume.
        val free = file.usableSpace
        val quota = "<D:quota-available-bytes>$free</D:quota-available-bytes>" +
            "<D:quota-used-bytes>${maxOf(0L, file.totalSpace - free)}</D:quota-used-bytes>"
        val buffer = ByteArrayOutputStream(16 * 1024)
        buffer.bufferedWriter(Charsets.UTF_8).use { sb ->
            sb.append(XML_HEADER).append("<D:multistatus xmlns:D=\"DAV:\">")
            appendProps(sb, entry, req.mount, quota)
            // "infinity" is answered like "1": walking a whole phone would take ages.
            if (req.header("depth")?.trim() != "0" && entry.isDirectory) {
                FileEntry.list(file).forEach { appendProps(sb, it, req.mount, quota) }
            }
            sb.append("</D:multistatus>")
        }
        return Response(207, XML_TYPE, buffer.size().toLong()) { buffer.writeTo(it) }
    }

    private fun appendProps(sb: Appendable, entry: FileEntry, mount: String, quota: String) {
        val file = entry.file
        val modified = entry.lastModified
        sb.append("<D:response><D:href>").append(xmlEscape(href(file, mount, entry.isDirectory))).append("</D:href>")
        sb.append("<D:propstat><D:prop>")
        sb.append("<D:displayname>").append(xmlEscape(file.name)).append("</D:displayname>")
        sb.append("<D:getlastmodified>").append(httpDate(modified)).append("</D:getlastmodified>")
        sb.append("<D:creationdate>").append(ISO_DATE.format(Instant.ofEpochMilli(modified))).append("</D:creationdate>")
        if (entry.isDirectory) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
            sb.append(quota)
        } else {
            sb.append("<D:resourcetype/>")
            sb.append("<D:getcontentlength>").append(entry.length.toString()).append("</D:getcontentlength>")
            sb.append("<D:getcontenttype>").append(xmlEscape(FileServer.mimeType(file.name))).append("</D:getcontenttype>")
            sb.append("<D:getetag>\"").append(java.lang.Long.toHexString(modified)).append('-')
                .append(java.lang.Long.toHexString(entry.length)).append("\"</D:getetag>")
        }
        sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
            .append("<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock><D:lockdiscovery/>")
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    private fun proppatch(req: Request): Response {
        val file = server.resolve(req.path) ?: return forbidden()
        if (!file.exists()) return notFound()
        val props = ArrayList<Pair<String, String>>() // namespace to local name
        try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) { }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(req.bodyText(64 * 1024).toByteArray(Charsets.UTF_8)))
            val propElements = doc.getElementsByTagNameNS("DAV:", "prop")
            for (i in 0 until propElements.length) {
                val children = propElements.item(i).childNodes
                for (j in 0 until children.length) {
                    val node = children.item(j)
                    if (node.nodeType != Node.ELEMENT_NODE) continue
                    val element = node as Element
                    val local = element.localName ?: element.nodeName
                    props += (element.namespaceURI ?: "") to local
                    if (local == "Win32LastModifiedTime" || local == "getlastmodified") {
                        parseHttpDate(element.textContent)?.let { file.setLastModified(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // Unparseable body: still report success, there's nothing we'd store anyway.
        }
        val sb = StringBuilder(XML_HEADER).append("<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>")
            .append(xmlEscape(href(file, req.mount))).append("</D:href><D:propstat><D:prop>")
        for ((ns, local) in props) {
            sb.append("<x:").append(local).append(" xmlns:x=\"").append(xmlEscape(ns)).append("\"/>")
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
        return Response.text(207, sb.toString(), XML_TYPE)
    }

    private fun mkcol(req: Request): Response {
        val dir = server.resolve(req.path) ?: return forbidden()
        if (req.contentLength > 0) return Response.text(415, "MKCOL with a body is not supported")
        if (dir.exists()) return Response.text(405, "Already exists")
        if (dir.parentFile?.isDirectory != true) return conflict()
        return if (dir.mkdir()) Response.empty(201) else Response.text(500, "Could not create the folder")
    }

    private fun put(req: Request): Response {
        val file = server.resolve(req.path) ?: return forbidden()
        if (file.isDirectory) return Response.text(405, "Is a folder")
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) return conflict()
        if (req.contentLength > parent.usableSpace) return Response.text(507, "Not enough free space")
        val existed = file.exists() && file.length() > 0 // an empty file was likely just made by LOCK
        if (!server.receiveFile(req.body, file)) return Response.text(500, "Could not write the file")
        val res = Response.empty(if (existed) 204 else 201)
        // rclone/ownCloud clients send the original modification time this way.
        req.header("x-oc-mtime")?.toLongOrNull()?.let {
            if (file.setLastModified(it * 1000)) res.header("X-OC-MTime", "accepted")
        }
        return res
    }

    private fun delete(req: Request, cfg: ServerConfig): Response {
        if (!cfg.allowDelete) return Response.text(403, "Deleting is turned off in the YAWTS app settings.")
        val file = server.resolve(req.path) ?: return forbidden()
        if (file == server.root) return forbidden()
        if (!file.exists()) return notFound()
        return if (file.deleteRecursively()) Response.empty(204) else Response.text(500, "Could not delete")
    }

    private fun copyOrMove(req: Request, move: Boolean): Response {
        val source = server.resolve(req.path) ?: return forbidden()
        if (!source.exists()) return notFound()
        if (move && source == server.root) return forbidden()
        val dest = destination(req.header("destination") ?: return Response.text(400, "Missing Destination"), req.mount)
            ?: return forbidden()
        if (dest == server.root || dest == source || dest.path.startsWith(source.path + File.separator)) return forbidden()
        if (dest.parentFile?.isDirectory != true) return conflict()

        val overwrite = !req.header("overwrite").equals("F", ignoreCase = true)
        val existed = dest.exists()
        if (existed) {
            if (!overwrite) return Response.text(412, "Destination exists")
            // Windows renames "a.txt" to "A.txt" this way - on case-insensitive storage that's the same file.
            val caseRename = move && dest.path.equals(source.path, ignoreCase = true)
            if (!caseRename && !dest.deleteRecursively()) return Response.text(500, "Could not replace the destination")
        }
        val ok = try {
            if (move) source.renameTo(dest) || (source.copyRecursively(dest, overwrite = true) && source.deleteRecursively())
            else source.copyRecursively(dest, overwrite = true)
        } catch (e: IOException) {
            false
        }
        return if (ok) Response.empty(if (existed) 204 else 201) else Response.text(500, "Could not complete the operation")
    }

    private fun lock(req: Request): Response {
        val file = server.resolve(req.path) ?: return forbidden()
        var status = 200
        if (!file.exists()) {
            // RFC 4918 9.10.4: locking an unmapped URL creates an empty file (Finder relies on this).
            if (file.parentFile?.isDirectory != true) return conflict()
            try { if (!file.createNewFile()) return Response.text(500, "Could not create the file") } catch (e: IOException) {
                return Response.text(500, "Could not create the file")
            }
            status = 201
        }
        // A refresh carries the existing token in the If header; hand the same one back.
        val token = LOCK_TOKEN.find(req.header("if") ?: "")?.groupValues?.get(1) ?: "opaquelocktoken:${UUID.randomUUID()}"
        val depth = if (req.header("depth")?.trim() == "0") "0" else "infinity"
        val xml = XML_HEADER + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>$depth</D:depth><D:timeout>Second-3600</D:timeout>" +
            "<D:locktoken><D:href>$token</D:href></D:locktoken>" +
            "<D:lockroot><D:href>${xmlEscape(href(file, req.mount))}</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>"
        return Response.text(status, xml, XML_TYPE).header("Lock-Token", "<$token>")
    }

    /** "http://host:port/a/b%20c" (or just "/a/b%20c") -> the file it names under the root. */
    private fun destination(header: String, mount: String): File? {
        var path = header.trim()
        if (path.contains("://")) path = "/" + path.substringAfter("://").substringAfter('/', "")
        path = percentDecode(path.substringBefore('?'))
        if (mount.isNotEmpty() && (path == mount || path.startsWith("$mount/"))) path = path.removePrefix(mount)
        return server.resolve(path)
    }

    private fun href(file: File, mount: String, isDirectory: Boolean = file.isDirectory): String {
        val rel = server.relativePath(file)
        if (rel.isEmpty()) return "$mount/"
        return mount + "/" + encodePath(rel) + if (isDirectory) "/" else ""
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val METHODS = "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK"
        const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        const val XML_TYPE = "application/xml; charset=utf-8"
        val LOCK_TOKEN = Regex("<(opaquelocktoken:[^>]+)>")
        val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        fun forbidden() = Response.text(403, "Forbidden")
        fun notFound() = Response.text(404, "Not found")
        fun conflict() = Response.text(409, "The parent folder doesn't exist")
    }
}
