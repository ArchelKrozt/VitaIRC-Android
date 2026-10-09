package io.github.archelkrozt.vitairc.net

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.archelkrozt.vitairc.App
import io.github.archelkrozt.vitairc.BuildConfig
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Looks for a newer release on GitHub (once a day, or when asked), downloads its APK and opens the installer. */
object Updates {
    /** Download progress 0..100, or -1 when not downloading */
    val progress = MutableStateFlow(-1)

    fun versionCompare(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(x.size, y.size, 1).coerceAtMost(4)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun check(manual: Boolean) {
        val today = LocalDate.now().toString()
        if (!manual && (!Config.updateCheck || Config.updateLast == today)) return
        AppScope.launch {
            try {
                val r = Http.call("https://api.github.com/repos/${Config.UPDATE_REPO}/releases/latest",
                    mapOf("Accept" to "application/vnd.github+json"))
                if (r.code == 404) throw HttpError(t("Todavía no hay versiones publicadas"))
                if (!r.ok) throw HttpError("GitHub HTTP ${r.code}")
                val js = JSONObject(r.text)
                val tag = js.optString("tag_name")
                val assets = js.optJSONArray("assets")
                var apk = ""
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
                }
                Config.updateLast = today
                Config.save()
                if (versionCompare(tag, BuildConfig.VERSION_NAME) > 0) {
                    AppEvents.update.value = UpdateInfo(tag, js.optString("html_url"), apk)
                } else if (manual) {
                    AppEvents.toast(t("Ya tienes la última versión (%s)").format(BuildConfig.VERSION_NAME))
                }
            } catch (e: Exception) {
                if (manual) AppEvents.toast(t("Actualizaciones: %s").format(e.message ?: ""))
            }
        }
    }

    /** Downloads the release's APK and opens Android's installer (it asks the user to confirm). */
    fun install(u: UpdateInfo) {
        if (u.apk.isEmpty()) { io.github.archelkrozt.vitairc.ui.Actions.browser(u.url); return }
        if (progress.value >= 0) return
        val ctx = App.ctx
        // Android 8+: the user allows this app to install updates once
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            AppEvents.toast(t("Permite a VitaIRC instalar actualizaciones y vuelve a pulsar Actualizar"))
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        progress.value = 0
        AppScope.launch {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, "VitaIRC-${u.tag}.apk")
            try {
                Http.client.newCall(Request.Builder().url(u.apk).header("User-Agent", Http.userAgent).build()).execute().use { r ->
                    if (!r.isSuccessful) throw HttpError("HTTP ${r.code}")
                    val body = r.body ?: throw HttpError(t("respuesta inválida"))
                    val total = body.contentLength()
                    var got = 0L
                    body.byteStream().use { input ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (total > 0) {
                                    val p = (got * 100 / total).toInt()
                                    if (p != progress.value) { progress.value = p; Busy.set(t("Descargando actualización... %d%%").format(p)) }
                                }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                f.delete()
                AppEvents.toast(t("No se pudo descargar la actualización: %s").format(e.message ?: ""))
            } finally {
                progress.value = -1
                Busy.set("")
            }
        }
    }
}
