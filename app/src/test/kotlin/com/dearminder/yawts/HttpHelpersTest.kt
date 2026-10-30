package com.dearminder.yawts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class HttpHelpersTest {

    @Test
    fun percentDecodeHandlesUtf8AndKeepsPlusByDefault() {
        assertEquals("/My file.txt", percentDecode("/My%20file.txt"))
        assertEquals("/naïve café", percentDecode("/na%C3%AFve%20caf%C3%A9"))
        assertEquals("a+b", percentDecode("a+b"))
        assertEquals("a b", percentDecode("a+b", plusAsSpace = true))
    }

    @Test
    fun percentDecodeLeavesBrokenEscapesAlone() {
        assertEquals("100%", percentDecode("100%"))
        assertEquals("%zz", percentDecode("%zz"))
        assertEquals("%4", percentDecode("%4"))
    }

    @Test
    fun encodePathSegmentKeepsOnlyUnreservedCharacters() {
        assertEquals("a-b_c.d~e", encodePathSegment("a-b_c.d~e"))
        assertEquals("My%20file%20%281%29.txt", encodePathSegment("My file (1).txt"))
        assertEquals("a%2Fb", encodePathSegment("a/b"))
        assertEquals("%C3%A9", encodePathSegment("é"))
    }

    @Test
    fun encodePathKeepsSlashesAndRoundTrips() {
        val path = "Download/Café photos #1/a&b?.jpg"
        val encoded = encodePath(path)
        assertEquals("Download/Caf%C3%A9%20photos%20%231/a%26b%3F.jpg", encoded)
        assertEquals(path, percentDecode(encoded))
    }

    @Test
    fun parseQueryCollectsRepeatedKeys() {
        val q = parseQuery("p=a%20b&p=c+d&flag&empty=&x=1")
        assertEquals(listOf("a b", "c d"), q["p"])
        assertEquals(listOf(""), q["flag"])
        assertEquals(listOf(""), q["empty"])
        assertEquals(listOf("1"), q["x"])
        assertEquals(emptyMap<String, List<String>>(), parseQuery(""))
    }

    @Test
    fun httpDateRoundTrips() {
        val millis = 1_700_000_000_000L
        val text = httpDate(millis)
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", text)
        assertEquals(millis, parseHttpDate(text))
        assertNull(parseHttpDate("yesterday"))
    }

    @Test
    fun readLineStripsCrLfAndReturnsNullAtEnd() {
        val input = ByteArrayInputStream("first\r\nsecond\nlast".toByteArray())
        assertEquals("first", readLine(input))
        assertEquals("second", readLine(input))
        assertEquals("last", readLine(input))
        assertNull(readLine(input))
    }

    @Test(expected = IOException::class)
    fun readLineRejectsOverlongLines() {
        readLine(ByteArrayInputStream(ByteArray(100) { 'a'.code.toByte() }), maxLength = 10)
    }

    @Test
    fun requestReadsCookiesHeadersAndForms() {
        val req = request(
            "POST", "/", headers = mapOf("Cookie" to "a=1; yawts_session=abc; b=2", "X-Test" to "yes"),
            body = "password=p%40ss+word&name=x"
        )
        assertEquals("abc", req.cookie("yawts_session"))
        assertNull(req.cookie("missing"))
        assertEquals("yes", req.header("x-test"))
        assertEquals("yes", req.header("X-TEST"))
        assertEquals(listOf("p@ss word"), req.form()["password"])
    }

    @Test
    fun requestUnderStripsTheMount() {
        val req = request("GET", "/webdav/a/b").under("/webdav")
        assertEquals("/a/b", req.path)
        assertEquals("/webdav", req.mount)
        assertEquals("/", request("GET", "/webdav").under("/webdav").path)
    }

    @Test
    fun bodyTextEnforcesItsLimit() {
        val req = request("POST", "/", body = "x".repeat(100))
        try {
            req.bodyText(limit = 10)
            throw AssertionError("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(413, e.status)
        }
    }

    @Test
    fun reasonPhrases() {
        assertEquals("OK", reasonPhrase(200))
        assertEquals("Multi-Status", reasonPhrase(207))
        assertEquals("Range Not Satisfiable", reasonPhrase(416))
        assertEquals("Unknown", reasonPhrase(299))
    }
}
