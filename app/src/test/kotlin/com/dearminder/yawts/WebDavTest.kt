package com.dearminder.yawts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebDavTest {

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

    private fun dav(method: String, path: String, body: String = "", vararg headers: Pair<String, String>) =
        t.handle(request(method, path, headers = headers.toMap(), body = body))

    @Test
    fun optionsAdvertisesDavOnlyWhenEnabled() {
        val on = dav("OPTIONS", "/")
        assertEquals("1, 2", on.headerValue("DAV"))
        assertTrue(on.headerValue("Allow")!!.contains("PROPFIND"))

        t.cfg = config(webDav = false)
        val off = dav("OPTIONS", "/")
        assertNull(off.headerValue("DAV"))
        assertEquals("OPTIONS, GET, HEAD", off.headerValue("Allow"))
        assertEquals(405, dav("PUT", "/a.txt", "x").status)
        assertFalse(File(root, "a.txt").exists())
    }

    @Test
    fun optionsNeedsNoPasswordButEverythingElseDoes() {
        t.cfg = config(password = "secret")
        assertEquals(200, dav("OPTIONS", "/").status)
        assertEquals(401, dav("PROPFIND", "/", "", "Depth" to "1").status)
    }

    @Test
    fun propfindListsAFolder() {
        file("a b.txt", "12345")
        File(root, "dir").mkdir()
        val res = dav("PROPFIND", "/", "", "Depth" to "1")
        assertEquals(207, res.status)
        val xml = res.bodyText()
        assertTrue(xml.contains("<D:href>/</D:href>"))
        assertTrue(xml.contains("<D:href>/a%20b.txt</D:href>"))
        assertTrue(xml.contains("<D:href>/dir/</D:href>"))
        assertTrue(xml.contains("<D:getcontentlength>5</D:getcontentlength>"))
        assertTrue(xml.contains("<D:quota-available-bytes>"))

        val self = dav("PROPFIND", "/", "", "Depth" to "0").bodyText()
        assertFalse(self.contains("a%20b.txt"))

        assertEquals(404, dav("PROPFIND", "/missing", "", "Depth" to "0").status)
    }

    @Test
    fun propfindUnderTheNamedShareUsesItsPrefix() {
        File(root, "dir").mkdir()
        val xml = dav("PROPFIND", "/webdav", "", "Depth" to "1").bodyText()
        assertTrue(xml.contains("<D:href>/webdav/</D:href>"))
        assertTrue(xml.contains("<D:href>/webdav/dir/</D:href>"))
    }

    @Test
    fun mkcol() {
        assertEquals(201, dav("MKCOL", "/new").status)
        assertTrue(File(root, "new").isDirectory)
        assertEquals(405, dav("MKCOL", "/new").status)
        assertEquals(409, dav("MKCOL", "/missing/new").status)
        assertEquals(415, dav("MKCOL", "/body", "<x/>").status)
    }

    @Test
    fun putCreatesAndReplaces() {
        assertEquals(201, dav("PUT", "/a.txt", "one").status)
        assertEquals(204, dav("PUT", "/a.txt", "two").status)
        assertEquals("two", File(root, "a.txt").readText())
        assertEquals(409, dav("PUT", "/missing/a.txt", "x").status)

        val res = dav("PUT", "/b.txt", "b", "X-OC-MTime" to "1600000000")
        assertEquals("accepted", res.headerValue("X-OC-MTime"))
        assertEquals(1_600_000_000_000L, File(root, "b.txt").lastModified())
    }

    @Test
    fun copyHonorsOverwrite() {
        file("a.txt", "a")
        assertEquals(201, dav("COPY", "/a.txt", "", "Destination" to "http://phone:8080/b.txt").status)
        assertEquals("a", File(root, "b.txt").readText())
        assertTrue(File(root, "a.txt").exists())

        assertEquals(412, dav("COPY", "/a.txt", "", "Destination" to "/b.txt", "Overwrite" to "F").status)
        File(root, "a.txt").writeText("changed")
        assertEquals(204, dav("COPY", "/a.txt", "", "Destination" to "/b.txt").status)
        assertEquals("changed", File(root, "b.txt").readText())
        assertEquals(400, dav("COPY", "/a.txt").status)
    }

    @Test
    fun moveWorksUnderTheNamedShare() {
        file("dir/a.txt", "a")
        val res = dav("MOVE", "/webdav/dir/a.txt", "", "Destination" to "http://phone:8080/webdav/dir/caf%C3%A9.txt")
        assertEquals(201, res.status)
        assertFalse(File(root, "dir/a.txt").exists())
        assertEquals("a", File(root, "dir/café.txt").readText())
    }

    @Test
    fun moveRefusesTheRootAndIntoItself() {
        File(root, "dir/sub").mkdirs()
        assertEquals(403, dav("MOVE", "/dir", "", "Destination" to "/dir/sub/dir").status)
        assertEquals(403, dav("MOVE", "/", "", "Destination" to "/elsewhere").status)
        assertEquals(403, dav("MOVE", "/dir", "", "Destination" to "/../outside").status)
        assertEquals(409, dav("MOVE", "/dir", "", "Destination" to "/missing/dir").status)
        assertTrue(File(root, "dir/sub").isDirectory)
    }

    @Test
    fun deleteFollowsTheSetting() {
        file("dir/a.txt")
        assertEquals(403, dav("DELETE", "/dir").status)
        t.cfg = config(allowDelete = true)
        assertEquals(204, dav("DELETE", "/dir").status)
        assertFalse(File(root, "dir").exists())
        assertEquals(404, dav("DELETE", "/dir").status)
        assertEquals(403, dav("DELETE", "/").status)
    }

    @Test
    fun lockCreatesAMissingFileAndRefreshKeepsTheToken() {
        val res = dav("LOCK", "/new.txt", "<D:lockinfo xmlns:D=\"DAV:\"/>")
        assertEquals(201, res.status)
        assertTrue(File(root, "new.txt").isFile)
        val token = res.headerValue("Lock-Token")!!
        assertTrue(token.startsWith("<opaquelocktoken:"))

        val refresh = dav("LOCK", "/new.txt", "", "If" to "(${token})")
        assertEquals(200, refresh.status)
        assertEquals(token, refresh.headerValue("Lock-Token"))
        assertEquals(204, dav("UNLOCK", "/new.txt").status)
    }

    @Test
    fun proppatchAppliesTheWindowsModificationTime() {
        file("a.txt")
        val body = """<?xml version="1.0"?>
            |<D:propertyupdate xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:"><D:set><D:prop>
            |<Z:Win32LastModifiedTime>Tue, 14 Nov 2023 22:13:20 GMT</Z:Win32LastModifiedTime>
            |</D:prop></D:set></D:propertyupdate>""".trimMargin()
        val res = dav("PROPPATCH", "/a.txt", body)
        assertEquals(207, res.status)
        assertTrue(res.bodyText().contains("<x:Win32LastModifiedTime xmlns:x=\"urn:schemas-microsoft-com:\"/>"))
        assertEquals(1_700_000_000_000L, File(root, "a.txt").lastModified())

        assertEquals(207, dav("PROPPATCH", "/a.txt", "not xml").status)
    }

    @Test
    fun unknownMethodsAre405() {
        assertEquals(405, dav("PATCH", "/a.txt").status)
    }
}
