package io.github.archelkrozt.vitairc.net

import io.github.archelkrozt.vitairc.BuildConfig
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/** Looks for a newer release on GitHub (once a day, or when asked). */
object Updates {
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
                Config.updateLast = today
                Config.save()
                if (versionCompare(tag, BuildConfig.VERSION_NAME) > 0) {
                    AppEvents.update.value = UpdateInfo(tag, js.optString("html_url"))
                    AppEvents.toast(t("Nueva versión %s disponible (Ajustes → Buscar actualizaciones)").format(tag))
                } else if (manual) {
                    AppEvents.toast(t("Ya tienes la última versión (%s)").format(BuildConfig.VERSION_NAME))
                }
            } catch (e: Exception) {
                if (manual) AppEvents.toast(t("Actualizaciones: %s").format(e.message ?: ""))
            }
        }
    }
}
