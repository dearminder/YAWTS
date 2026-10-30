package com.dearminder.yawts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

class FileServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var t: TestServer
    private lateinit var root: File

    @Before
    fun setUp() {
        t = TestServer(tmp.newFolder("share"))
        root = t.server.root
    }

    private fun file(path: String, text: String = "") = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    // ---------- Paths ----------

    @Test
    fun resolveStaysInsideTheRoot() {
        tmp.newFolder("share-evil")
        assertEquals(root, t.server.resolve(""))
        assertEquals(root, t.server.resolve("/"))
        assertEquals(File(root, "a/b"), t.server.resolve("/a/b/"))
        assertNull(t.server.resolve("../outside"))
        assertNull(t.server.resolve("a/../../outside"))
        assertNull(t.server.resolve("..\\outside"))
        assertNull(t.server.resolve("../share-evil/x"))
        assertEquals("a/b", t.server.relativePath(File(root, "a/b")))
        assertEquals("", t.server.relativePath(root))
    }

    @Test
    fun cannotReadFilesOutsideTheRoot() {
        File(tmp.root, "secret.txt").writeText("secret")
        assertEquals(403, t.handle(request("GET", "/../secret.txt")).status)
    }

    // ---------- Web app ----------

    @Test
    fun indexPageFillsInLanguageAndEscapedName() {
        val res = t.handle(request("GET", "/"))
        assertEquals(200, res.status)
        assertEquals("<html lang=\"en\"><title>Test &lt;Phone&gt;</title></html>", res.bodyText())
    }

    @Test
    fun servesAssetsAndMissingOnesAre404() {
        assertEquals("<svg/>", t.handle(request("GET", "/.yawts/favicon.svg")).bodyText())
        assertEquals(404, t.handle(request("GET", "/favicon.ico")).status)
    }

    @Test
    fun deviceDescriptionPointsWindowsAtTheShare() {
        val withDav = t.handle(request("GET", "/.yawts/description.xml", headers = mapOf("Host" to "192.168.1.5:8080"))).bodyText()
        assertTrue(withDav.contains("<friendlyName>Test &lt;Phone&gt;</friendlyName>"))
        assertTrue(withDav.contains("<UDN>uuid:00000000-0000-0000-0000-000000000000</UDN>"))
        assertTrue(withDav.contains("<presentationURL>file://192.168.1.5@8080/webdav</presentationURL>"))

        t.cfg = config(webDav = false)
        val withoutDav = t.handle(request("GET", "/.yawts/description.xml", headers = mapOf("Host" to "192.168.1.5:8080"))).bodyText()
        assertTrue(withoutDav.contains("<presentationURL>/</presentationURL>"))
    }

    // ---------- Files ----------

    @Test
    fun servesFilesWithCachingHeaders() {
        file("notes.txt", "0123456789")
        val res = t.handle(request("GET", "/notes.txt"))
        assertEquals(200, res.status)
        assertEquals(10L, res.length)
        assertEquals("0123456789", res.bodyText())
        assertEquals("bytes", res.headerValue("Accept-Ranges"))
        val etag = res.headerValue("ETag")!!

        assertEquals(304, t.handle(request("GET", "/notes.txt", headers = mapOf("If-None-Match" to etag))).status)
    }

    @Test
    fun servesByteRanges() {
        file("notes.txt", "0123456789")
        val range = t.handle(request("GET", "/notes.txt", headers = mapOf("Range" to "bytes=2-5")))
        assertEquals(206, range.status)
        assertEquals("2345", range.bodyText())
        assertEquals("bytes 2-5/10", range.headerValue("Content-Range"))

        val open = t.handle(request("GET", "/notes.txt", headers = mapOf("Range" to "bytes=7-")))
        assertEquals("789", open.bodyText())

        val suffix = t.handle(request("GET", "/notes.txt", headers = mapOf("Range" to "bytes=-3")))
        assertEquals("789", suffix.bodyText())

        val beyond = t.handle(request("GET", "/notes.txt", headers = mapOf("Range" to "bytes=20-")))
        assertEquals(416, beyond.status)
        assertEquals("bytes */10", beyond.headerValue("Content-Range"))

        // A stale If-Range means the whole file.
        val stale = t.handle(request("GET", "/notes.txt", headers = mapOf("Range" to "bytes=2-5", "If-Range" to "\"old\"")))
        assertEquals(200, stale.status)
    }

    @Test
    fun foldersRedirectToTheWebApp() {
        File(root, "Download/Sub dir").mkdirs()
        val res = t.handle(request("GET", "/Download/Sub dir"))
        assertEquals(302, res.status)
        assertEquals("/#/Download/Sub%20dir", res.headerValue("Location"))
    }

    @Test
    fun htmlFromThePhoneIsSandboxedAndDownloadsAreAttachments() {
        file("page.html", "<script>alert(1)</script>")
        assertEquals("sandbox", t.handle(request("GET", "/page.html")).headerValue("Content-Security-Policy"))
        val download = t.handle(request("GET", "/page.html", query = q("dl" to "1")))
        assertTrue(download.headerValue("Content-Disposition")!!.startsWith("attachment;"))
    }

    @Test
    fun receiveFileLeavesNoTempFileBehind() {
        val target = File(root, "out.bin")
        assertTrue(t.server.receiveFile(ByteArrayInputStream(byteArrayOf(1, 2, 3)), target))
        assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())

        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("connection lost")
        }
        assertFalse(t.server.receiveFile(broken, File(root, "broken.bin")))
        assertEquals(listOf("out.bin"), root.list()!!.toList())
    }

    // ---------- Authentication ----------

    @Test
    fun passwordProtectsFilesAndOffersDigestAndBasic() {
        file("f.txt", "hi")
        t.cfg = config(password = "secret")
        val res = t.handle(request("GET", "/f.txt"))
        assertEquals(401, res.status)
        val challenges = res.headerValues("WWW-Authenticate")
        assertTrue(challenges.any { it.startsWith("Digest ") })
        assertTrue(challenges.any { it.startsWith("Basic ") })

        fun basic(credentials: String) = "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
        assertEquals(200, t.handle(request("GET", "/f.txt", headers = mapOf("Authorization" to basic("anyone:secret")))).status)
        assertEquals(401, t.handle(request("GET", "/f.txt", headers = mapOf("Authorization" to basic("anyone:wrong")))).status)
        assertEquals(401, t.handle(request("GET", "/f.txt", headers = mapOf("Authorization" to "Basic !!!"))).status)
    }

    @Test
    fun acceptsDigestAuth() {
        file("f.txt", "hi")
        t.cfg = config(password = "secret")
        val challenge = t.handle(request("GET", "/f.txt")).headerValues("WWW-Authenticate").first { it.startsWith("Digest ") }
        val nonce = Regex("nonce=\"([^\"]+)\"").find(challenge)!!.groupValues[1]

        fun digest(password: String, nonce: String): String {
            val ha1 = FileServer.md5("me:YAWTS:$password")
            val ha2 = FileServer.md5("GET:/f.txt")
            val response = FileServer.md5("$ha1:$nonce:00000001:c0ffee:auth:$ha2")
            return "Digest username=\"me\", realm=\"YAWTS\", nonce=\"$nonce\", uri=\"/f.txt\", qop=auth, nc=00000001, cnonce=\"c0ffee\", response=\"$response\""
        }
        assertEquals(200, t.handle(request("GET", "/f.txt", headers = mapOf("Authorization" to digest("secret", nonce)))).status)
        assertEquals(401, t.handle(request("GET", "/f.txt", headers = mapOf("Authorization" to digest("wrong", nonce)))).status)
        // A nonce the server didn't sign is refused even with the right password.
        val forged = java.lang.Long.toHexString(System.currentTimeMillis()) + ".deadbeef"
        assertEquals(401, t.handle(request("GET", "/f.txt", headers = mapOf("Authorization" to digest("secret", forged)))).status)
    }

    @Test
    fun loginIssuesASessionThatChangingThePasswordEnds() {
        t.cfg = config(password = "secret")
        assertEquals(401, t.api("GET", "api/list").status)

        val failed = t.handle(request("POST", "/.yawts/api/login", body = "password=wrong"))
        assertEquals(401, failed.status)
        assertNull(failed.headerValue("Set-Cookie"))

        val login = t.handle(request("POST", "/.yawts/api/login", body = "password=secret"))
        assertEquals(204, login.status)
        val token = Regex("yawts_session=([0-9a-f]+);").find(login.headerValue("Set-Cookie")!!)!!.groupValues[1]
        val cookie = mapOf("Cookie" to "yawts_session=$token")
        assertEquals(200, t.api("GET", "api/list", headers = cookie).status)

        t.cfg = config(password = "changed")
        assertEquals(401, t.api("GET", "api/list", headers = cookie).status)
    }

    @Test
    fun logoutEndsTheSession() {
        t.cfg = config(password = "secret")
        val login = t.handle(request("POST", "/.yawts/api/login", body = "password=secret"))
        val cookie = mapOf("Cookie" to login.headerValue("Set-Cookie")!!.substringBefore(';'))
        assertEquals(204, t.api("POST", "api/logout", headers = cookie).status)
        assertEquals(401, t.api("GET", "api/list", headers = cookie).status)
    }

    // ---------- JSON API ----------

    @Test
    fun changesRequireTheWebAppHeader() {
        val res = t.handle(request("POST", "/.yawts/api/mkdir", body = "path=&name=x"))
        assertEquals(403, res.status)
        assertFalse(File(root, "x").exists())
    }

    @Test
    fun listsAFolder() {
        file("a \"quoted\".txt", "12345")
        File(root, "dir").mkdir()
        val json = t.api("GET", "api/list", q("path" to "")).bodyText()
        assertTrue(json.startsWith("{\"path\":\"\",\"canDelete\":false,\"webDav\":true,\"thumbs\":false,\"auth\":false,"))
        assertTrue(json.contains("{\"n\":\"a \\\"quoted\\\".txt\",\"d\":0,\"s\":5,"))
        assertTrue(json.contains("{\"n\":\"dir\",\"d\":1,\"s\":0,"))
        assertEquals(404, t.api("GET", "api/list", q("path" to "missing")).status)
        assertEquals(404, t.api("GET", "api/list", q("path" to "../")).status)
    }

    @Test
    fun uploadsNeverOverwrite() {
        val first = t.api("PUT", "api/upload", q("path" to "", "name" to "a.txt", "mtime" to "1600000000000"), "one")
        assertEquals(201, first.status)
        assertEquals("{\"name\":\"a.txt\"}", first.bodyText())
        assertEquals(1_600_000_000_000L, File(root, "a.txt").lastModified())

        val second = t.api("PUT", "api/upload", q("path" to "", "name" to "a.txt"), "two")
        assertEquals("{\"name\":\"a (1).txt\"}", second.bodyText())
        assertEquals("one", File(root, "a.txt").readText())
        assertEquals("two", File(root, "a (1).txt").readText())
    }

    @Test
    fun uploadsFoldersButRejectsTraversal() {
        assertEquals(201, t.api("PUT", "api/upload", q("path" to "", "name" to "sub/dir/x.txt"), "x").status)
        assertEquals("x", File(root, "sub/dir/x.txt").readText())
        assertEquals(400, t.api("PUT", "api/upload", q("path" to "", "name" to "../x.txt"), "x").status)
        assertEquals(404, t.api("PUT", "api/upload", q("path" to "missing", "name" to "x.txt"), "x").status)
        assertFalse(File(tmp.root, "x.txt").exists())
        assertEquals(405, t.api("POST", "api/upload", q("path" to "", "name" to "y.txt"), "y").status)
    }

    @Test
    fun makesAndRenamesFolders() {
        assertEquals(201, t.api("POST", "api/mkdir", body = "path=&name=New+folder").status)
        assertTrue(File(root, "New folder").isDirectory)
        assertEquals(409, t.api("POST", "api/mkdir", body = "path=&name=New+folder").status)
        assertEquals(400, t.api("POST", "api/mkdir", body = "path=&name=..").status)

        assertEquals(200, t.api("POST", "api/rename", body = "path=New+folder&name=Renamed").status)
        assertTrue(File(root, "Renamed").isDirectory)
        assertFalse(File(root, "New folder").exists())

        file("taken.txt")
        assertEquals(409, t.api("POST", "api/rename", body = "path=Renamed&name=taken.txt").status)
        assertEquals(404, t.api("POST", "api/rename", body = "path=&name=root").status)
    }

    @Test
    fun deletingFollowsTheSetting() {
        file("a.txt")
        file("dir/b.txt")
        assertEquals(403, t.api("POST", "api/delete", body = "p=a.txt").status)
        assertTrue(File(root, "a.txt").exists())

        t.cfg = config(allowDelete = true)
        val res = t.api("POST", "api/delete", body = "p=a.txt&p=dir&p=gone.txt")
        assertEquals("{\"failed\":[]}", res.bodyText())
        assertEquals(0, root.list()!!.size)

        // The root itself and paths outside it are never deleted.
        assertEquals("{\"failed\":[\"\",\"../share\"]}", t.api("POST", "api/delete", body = "p=&p=../share").bodyText())
        assertTrue(root.isDirectory)
    }

    @Test
    fun zipsFilesAndFolders() {
        file("a.txt", "aaa")
        file("dir/b.txt", "bbb")
        File(root, "dir/empty").mkdirs()
        val res = t.api("GET", "api/zip", q("p" to "a.txt", "p" to "dir", "p" to "../outside"))
        assertEquals(200, res.status)
        assertEquals(-1L, res.length)
        assertTrue(res.headerValue("Content-Disposition")!!.contains("yawts-2.zip"))

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(res.bodyBytes())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = String(zip.readBytes())
            }
        }
        assertEquals(mapOf("a.txt" to "aaa", "dir/b.txt" to "bbb", "dir/empty/" to ""), entries)
        assertEquals(404, t.api("GET", "api/zip", q("p" to "missing")).status)
    }

    @Test
    fun unknownApiIs404() {
        val res = t.api("GET", "api/nope")
        assertEquals(404, res.status)
        assertEquals("{\"error\":\"notFound\"}", res.bodyText())
    }
}
