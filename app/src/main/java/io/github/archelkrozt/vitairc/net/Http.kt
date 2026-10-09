package io.github.archelkrozt.vitairc.net

import io.github.archelkrozt.vitairc.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Background work of the app (network, files). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class HttpResult(val code: Int, val body: ByteArray, val contentType: String, val finalUrl: String, val length: Long) {
    val ok get() = code in 200..299
    val text get() = String(body, Charsets.UTF_8)
}

class HttpError(msg: String) : IOException(msg)

object Http {
    // no URL in it: some firewalls (w3schools...) refuse user agents that carry one
    val userAgent = "VitaIRC/${BuildConfig.VERSION_NAME} (Android)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** For a bouncer's own (self-signed) certificate, already accepted for IRC on that host. */
    fun insecureFor(host: String): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return client.newBuilder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { h, _ -> h.equals(host, true) }
            .build()
    }

    /**
     * Runs a request and reads at most [max] bytes of the answer (a bigger body is cut,
     * which is enough to tell what a link is). Throws [HttpError] on network errors.
     */
    fun call(url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, method: String? = null,
             max: Int = 4 * 1024 * 1024, ua: String = userAgent, client: OkHttpClient = this.client): HttpResult {
        val req = Request.Builder().url(url)
            .headers(Headers.Builder().apply { add("User-Agent", ua); headers.forEach { (k, v) -> add(k, v) } }.build())
            .method(method ?: if (body != null) "POST" else "GET", body)
            .build()
        try {
            client.newCall(req).execute().use { r ->
                val src = r.body
                val bytes = if (src == null || method == "HEAD") ByteArray(0) else {
                    val input = src.byteStream()
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    while (out.size() < max) {
                        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val ct = (r.header("Content-Type") ?: "").substringBefore(';').trim().lowercase()
                val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                return HttpResult(r.code, bytes, ct, r.request.url.toString(), len)
            }
        } catch (e: IOException) {
            throw HttpError(io.github.archelkrozt.vitairc.L.t("Red: %s").format(e.message ?: e.javaClass.simpleName))
        }
    }
}
