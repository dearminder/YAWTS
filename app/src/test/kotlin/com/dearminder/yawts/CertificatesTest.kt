package com.dearminder.yawts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class CertificatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A self-signed PKCS#12 made with the JDK's keytool, like users make theirs. */
    private fun makeP12(password: String): ByteArray {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        assumeTrue("keytool not available", keytool.canExecute())
        val out = File(tmp.root, "cert.p12")
        val process = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", "yawts", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=phone.local, O=Test", "-validity", "30", "-storetype", "PKCS12",
            "-keystore", out.path, "-storepass", password, "-keypass", password
        ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        return out.readBytes()
    }

    @Test
    fun loadsAndDescribesACertificate() {
        val keyStore = Certificates.loadPkcs12(makeP12("changeit"), "changeit")
        val info = Certificates.describe(keyStore)!!
        assertEquals("phone.local", info.subject)
        assertTrue(info.notAfter.after(Date()))
        assertNotNull(Certificates.sslFactory(keyStore, "changeit".toCharArray()))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val p12 = makeP12("changeit")
        assertThrows(Exception::class.java) { Certificates.loadPkcs12(p12, "wrong") }
    }

    @Test
    fun otherFilesAreRejected() {
        assertThrows(Exception::class.java) { Certificates.loadPkcs12("not a certificate".toByteArray(), "") }
    }
}
