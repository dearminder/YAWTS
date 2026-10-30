package com.dearminder.yawts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileServerHelpersTest {

    @Test
    fun sanitizeNameRejectsPathsAndDots() {
        assertEquals("photo.jpg", FileServer.sanitizeName("  photo.jpg "))
        assertEquals("ab", FileServer.sanitizeName("a/b"))
        assertEquals("ab", FileServer.sanitizeName("a\\b"))
        assertEquals("ab", FileServer.sanitizeName("a\u0000\nb"))
        assertNull(FileServer.sanitizeName(""))
        assertNull(FileServer.sanitizeName("   "))
        assertNull(FileServer.sanitizeName("."))
        assertNull(FileServer.sanitizeName(".."))
    }

    @Test
    fun sanitizeRelativeNameKeepsFoldersButNoTraversal() {
        assertEquals("sub/folder/file.txt", FileServer.sanitizeRelativeName("sub/folder/file.txt"))
        assertEquals("sub/file.txt", FileServer.sanitizeRelativeName("\\sub\\\\file.txt"))
        assertEquals("a/b", FileServer.sanitizeRelativeName("/a//b/"))
        assertNull(FileServer.sanitizeRelativeName("../etc/passwd"))
        assertNull(FileServer.sanitizeRelativeName("a/../../b"))
        assertNull(FileServer.sanitizeRelativeName("a/./b"))
        assertNull(FileServer.sanitizeRelativeName("///"))
    }

    @Test
    fun mimeTypeByExtension() {
        assertEquals("image/jpeg", FileServer.mimeType("IMG_001.JPG"))
        assertEquals("video/mp4", FileServer.mimeType("clip.mp4"))
        assertEquals("text/plain; charset=utf-8", FileServer.mimeType("notes.txt"))
        assertEquals("application/octet-stream", FileServer.mimeType("archive.unknownext"))
        assertEquals("application/octet-stream", FileServer.mimeType("no-extension"))
    }

    @Test
    fun jsonStringEscapes() {
        assertEquals("\"plain\"", FileServer.jsonString("plain"))
        assertEquals("\"a\\\"b\\\\c\"", FileServer.jsonString("a\"b\\c"))
        assertEquals("\"line\\u000anext\\u0009\"", FileServer.jsonString("line\nnext\t"))
        assertEquals("\"naïve café\"", FileServer.jsonString("naïve café"))
    }

    @Test
    fun attachmentHeaderHasAsciiFallbackAndUtf8Name() {
        assertEquals(
            "attachment; filename=\"r_sum_ _x_.txt\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20%22x%22.txt",
            FileServer.attachmentHeader("résumé \"x\".txt")
        )
    }

    @Test
    fun htmlEscape() {
        assertEquals("&lt;b&gt;Tom &amp; &quot;Jerry&quot;&lt;/b&gt;", FileServer.htmlEscape("<b>Tom & \"Jerry\"</b>"))
    }

    @Test
    fun md5() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileServer.md5(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", FileServer.md5("abc"))
    }
}
