package com.dearminder.yawts

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory

/** A user-supplied HTTPS certificate: a PKCS#12 (.p12 / .pfx) bundle with the private key. */
object Certificates {

    class Info(val subject: String, val notAfter: Date)

    class InvalidCertificateException(message: String) : Exception(message)

    /**
     * Opens a PKCS#12 bundle, throwing if the password is wrong, the file is something else,
     * or it holds no private key (a certificate alone can't serve HTTPS).
     */
    fun loadPkcs12(bytes: ByteArray, password: String): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(ByteArrayInputStream(bytes), password.toCharArray())
        if (keyStore.aliases().toList().none { keyStore.isKeyEntry(it) }) {
            throw InvalidCertificateException("The file has no private key")
        }
        return keyStore
    }

    /** Subject and expiry of the certificate that goes with the key. */
    fun describe(keyStore: KeyStore): Info? {
        val alias = keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) } ?: return null
        val cert = keyStore.getCertificate(alias) as? X509Certificate ?: return null
        val name = cert.subjectX500Principal.name
        val cn = Regex("(?:^|,)\\s*CN=([^,]+)").find(name)?.groupValues?.get(1) ?: name
        return Info(cn, cert.notAfter)
    }

    fun sslFactory(keyStore: KeyStore, password: CharArray?): SSLServerSocketFactory {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }.trustManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }.serverSocketFactory
    }
}
