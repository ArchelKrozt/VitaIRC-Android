package io.github.archelkrozt.vitairc.irc

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One IRC connection (TCP or TLS). Like VitaIRC on the Vita, a certificate that can't be
 * verified does not stop the connection (bouncers often use their own); certOk says
 * whether it was trusted so the UI can warn.
 */
class Conn private constructor(private val sock: Socket, val certOk: Boolean) {
    private val input: InputStream = sock.getInputStream()
    private val output: OutputStream = sock.getOutputStream()
    private val buf = ByteArrayOutputStream()
    private val chunk = ByteArray(8192)
    private var pending = ByteArray(0)

    companion object {
        private val systemTrust: X509TrustManager by lazy {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /** Blocking connect (with TLS handshake). Throws with a readable message on failure. */
        fun open(host: String, port: Int, ssl: Boolean): Conn {
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.keepAlive = true
            plain.connect(InetSocketAddress(host, port), 20000)
            if (!ssl) {
                plain.soTimeout = 100
                return Conn(plain, true)
            }
            var trusted = true
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try { systemTrust.checkServerTrusted(chain, authType) } catch (e: CertificateException) { trusted = false }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            s.soTimeout = 20000
            s.startHandshake()
            if (trusted && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) trusted = false
            s.soTimeout = 100
            return Conn(s, trusted)
        }
    }

    @Synchronized
    fun write(line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** One line without CRLF; null when none arrived within the read timeout. Throws when closed. */
    fun readLine(): String? {
        while (true) {
            val nl = pending.indexOf('\n'.code.toByte())
            if (nl >= 0) {
                var end = nl
                if (end > 0 && pending[end - 1] == '\r'.code.toByte()) end--
                val line = pending.copyOfRange(0, end)
                pending = pending.copyOfRange(nl + 1, pending.size)
                return decode(line)
            }
            if (pending.size > 16384) pending = ByteArray(0)       // overlong line: drop it
            val n = try { input.read(chunk) } catch (e: SocketTimeoutException) { return null }
            if (n < 0) throw java.io.EOFException("closed")
            buf.reset()
            buf.write(pending)
            buf.write(chunk, 0, n)
            pending = buf.toByteArray()
        }
    }

    /** UTF-8, or Latin-1 for old clients that don't send UTF-8. */
    private fun decode(b: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (e: Exception) { String(b, Charsets.ISO_8859_1) }
    }

    fun close() {
        try { sock.close() } catch (_: Exception) {}
    }
}
