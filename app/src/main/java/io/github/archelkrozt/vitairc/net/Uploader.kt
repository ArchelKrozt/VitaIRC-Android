package io.github.archelkrozt.vitairc.net

import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.Irc
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Something to upload: a picked photo, a camera shot or a downloaded image. */
class UploadSource(val name: String, val mime: String, val size: Long, val open: () -> InputStream)

/** One entry of uploads.txt */
class UploadEntry(val date: String, val host: String, val link: String, val deleteUrl: String)

object Uploader {
    val hostNames = listOf("Litterbox", "ImgBB", "soju FILEHOST", "Imgur")
    fun hostName(h: Int) = hostNames.getOrElse(h) { "Litterbox" }
    private val imgbbSecs = intArrayOf(0, 3600, 86400, 7 * 86400, 30 * 86400)
    private val litterTimes = listOf("1h", "12h", "24h", "72h")
    private val logFile get() = File(Config.dir, "uploads.txt")

    /** null when uploads can work from this server's windows; otherwise why. */
    fun notReady(sidx: Int): String? = when (Config.imgHost) {
        Config.IMG_IMGUR -> if (Config.imgurId.isEmpty()) t("Configura tu Client-ID de Imgur en Ajustes") else null
        Config.IMG_IMGBB -> if (Config.imgbbKey.isEmpty()) t("Configura tu API key de ImgBB en Ajustes") else null
        Config.IMG_FILEHOST -> if (Irc.locked { Irc.servers.getOrNull(sidx)?.filehost.isNullOrEmpty() })
            t("Este servidor no ofrece FILEHOST (hace falta soju con file-upload)") else null
        else -> null
    }

    /** Uploads in the background; the link (or the error) arrives through AppEvents.uploads. */
    fun upload(sidx: Int, uid: Long, src: UploadSource) {
        AppScope.launch {
            val res = try {
                UploadResult(sidx, uid, true, uploadNow(sidx, src))
            } catch (e: Exception) {
                UploadResult(sidx, uid, false, e.message ?: e.javaClass.simpleName)
            } finally {
                Busy.set("")
            }
            AppEvents.uploads.emit(res)
        }
    }

    private class Streamed(private val src: UploadSource, private val host: String) : RequestBody() {
        override fun contentType(): MediaType = src.mime.toMediaType()
        override fun contentLength() = src.size
        override fun writeTo(sink: BufferedSink) {
            src.open().use { input ->
                val buf = ByteArray(32768)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    val pct = if (src.size > 0) (sent * 100 / src.size).toInt() else 0
                    if (pct != lastPct) { lastPct = pct; Busy.set(t("Subiendo a %s: %d%%").format(host, pct)) }
                }
            }
        }
    }

    fun uploadNow(sidx: Int, src: UploadSource): String {
        val host = Config.imgHost
        val maxMb = when (host) { Config.IMG_IMGUR -> 10L; Config.IMG_IMGBB -> 32L; else -> 200L }
        if (src.size > maxMb * 1024 * 1024) throw HttpError(t("%s acepta imágenes de hasta %ld MB").replace("%ld", "%d").format(hostName(host), maxMb))
        Busy.set(t("Subiendo imagen..."))
        var deleteUrl = ""
        val body = Streamed(src, hostName(host))
        val link = when (host) {
            Config.IMG_IMGUR -> {
                if (Config.imgurId.isEmpty()) throw HttpError(t("Falta el Client-ID de Imgur (Ajustes)"))
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("image", src.name, body).addFormDataPart("type", "file").build()
                val r = Http.call("https://api.imgur.com/3/image", mapOf("Authorization" to "Client-ID ${Config.imgurId}"), form)
                val d = runCatching { JSONObject(r.text).optJSONObject("data") }.getOrNull()
                val l = d?.optString("link").orEmpty()
                if (r.ok && l.isNotEmpty()) l else {
                    val err = d?.opt("error")
                    throw HttpError("Imgur ${r.code}: " + ((err as? String) ?: (err as? JSONObject)?.optString("message") ?: t("error desconocido")).take(120))
                }
            }
            Config.IMG_IMGBB -> {
                if (Config.imgbbKey.isEmpty()) throw HttpError(t("Configura tu API key de ImgBB en Ajustes"))
                val exp = imgbbSecs[Config.imgbbExpire]
                val url = "https://api.imgbb.com/1/upload?key=" + java.net.URLEncoder.encode(Config.imgbbKey, "UTF-8") +
                    (if (exp > 0) "&expiration=$exp" else "")
                val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", src.name, body).build()
                val r = Http.call(url, body = form)
                val js = runCatching { JSONObject(r.text) }.getOrNull()
                val d = js?.optJSONObject("data")
                val l = d?.optString("url").orEmpty()
                if (r.ok && l.isNotEmpty()) { deleteUrl = d?.optString("delete_url").orEmpty(); l } else
                    throw HttpError("ImgBB ${r.code}: " + (js?.optJSONObject("error")?.optString("message") ?: js?.optString("status_txt") ?: t("error desconocido")).take(120))
            }
            Config.IMG_FILEHOST -> filehost(sidx, src, body)
            else -> {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("reqtype", "fileupload").addFormDataPart("time", litterTimes[Config.litterTime])
                    .addFormDataPart("fileToUpload", src.name, body).build()
                val r = Http.call("https://litterbox.catbox.moe/resources/internals/api.php", body = form)
                val l = r.text.trim()
                if (r.ok && l.startsWith("https://") && !l.contains(' ')) l else
                    throw HttpError("Litterbox ${r.code}: " + l.ifEmpty { t("error desconocido") }.take(120))
            }
        }
        logUpload(hostName(host), link, deleteUrl)
        return link
    }

    /** soju.im/FILEHOST: raw POST with the IRC account (HTTP Basic), answer 201 + Location. */
    private fun filehost(sidx: Int, src: UploadSource, body: RequestBody): String {
        data class Fh(val url: String, val user: String, val pass: String, val host: String, val ssl: Boolean, val certOk: Boolean)
        val fh = Irc.locked {
            val s = Irc.servers.getOrNull(sidx) ?: return@locked null
            Fh(s.filehost, s.cfg.user.ifEmpty { s.cfg.nick }, s.cfg.password, s.cfg.host, s.cfg.ssl, s.certOk)
        } ?: throw HttpError(t("Este servidor no ofrece FILEHOST (hace falta soju con file-upload)"))
        if (fh.url.isEmpty()) throw HttpError(t("Este servidor no ofrece FILEHOST (hace falta soju con file-upload)"))
        if (fh.ssl && !fh.url.startsWith("https://")) throw HttpError(t("FILEHOST sin cifrar rechazado: la conexión IRC usa SSL"))
        if (fh.pass.isEmpty()) throw HttpError(t("FILEHOST necesita tu contraseña del bouncer (Servidores)"))
        val user = fh.user.substringBefore('/').substringBefore('@')     // soju: "user/network@client" -> "user"
        val safe = src.name.map { if (it == '"' || it == '\\' || it < ' ') '_' else it }.joinToString("")
        val urlHost = fh.url.toHttpUrlOrNull()?.host ?: ""
        // a bouncer with a self-signed certificate (already accepted for IRC)
        val client = if (!fh.certOk && urlHost.equals(fh.host, true)) Http.insecureFor(urlHost) else Http.client
        val req = Request.Builder().url(fh.url)
            .header("User-Agent", Http.userAgent)
            .header("Authorization", Credentials.basic(user, fh.pass))
            .header("Content-Disposition", "attachment; filename=\"$safe\"")
            .post(body).build()
        val (code, location) = try {
            client.newCall(req).execute().use { it.code to (it.header("Location") ?: "") }
        } catch (e: java.io.IOException) { throw HttpError(t("Red: %s").format(e.message ?: "")) }
        if (code == 401 || code == 403) throw HttpError(t("FILEHOST: usuario o contraseña incorrectos"))
        if (code == 413) throw HttpError(t("FILEHOST: la imagen es demasiado grande para el servidor"))
        if (code >= 300 || location.isEmpty()) throw HttpError("FILEHOST HTTP $code")
        return fh.url.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
    }

    private fun logUpload(host: String, link: String, del: String) {
        val d = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        try { logFile.appendText("$d\t$host\t$link\t$del\n") } catch (_: Exception) {}
    }

    /** Newest uploads first */
    fun recent(max: Int = 30): List<UploadEntry> = try {
        logFile.readLines().mapNotNull { l ->
            val f = l.split('\t')
            if (f.size >= 3) UploadEntry(f[0], f[1], f[2], f.getOrElse(3) { "" }) else null
        }.takeLast(max).reversed()
    } catch (_: Exception) { emptyList() }
}
