package io.github.archelkrozt.vitairc.net

import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.Chan
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.Msg
import io.github.archelkrozt.vitairc.irc.Server
import io.github.archelkrozt.vitairc.irc.TransState
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingDeque

/** Translation with Google's free endpoint or ChatGPT, cached on disk like VitaIRC for PS Vita. */
object Translator {
    private const val CACHE_MAX = 256

    /** OpenAI usage: today, total, requests */
    val usage = MutableStateFlow(Triple(0L, 0L, 0L))
    private var usageDate = ""

    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX
    }

    private class Job(val outgoing: Boolean, val sidx: Int, val uid: Long, val mid: Long, val text: String, val lang: String)

    private val jobs = LinkedBlockingDeque<Job>()
    private val cacheFile get() = File(Config.dir, "trcache.txt")
    private val usageFile get() = File(Config.dir, "usage.txt")

    fun ready() = Config.trProvider != Config.TRP_OPENAI || Config.openaiKey.isNotEmpty()
    fun providerName(p: Int) = if (p == Config.TRP_OPENAI) "ChatGPT (OpenAI)" else t("Google (gratis)")

    fun start() {
        loadCache()
        loadUsage()
        Thread({
            while (true) {
                val j = jobs.take()
                run(j)
            }
        }, "translator").apply { isDaemon = true; start() }
    }

    /** Incoming message: the result goes under it. */
    fun requestIn(s: Server, c: Chan, m: Msg, onDemand: Boolean = false) {
        val j = Job(false, s.idx, c.uid, m.id, m.text, Irc.chanLang(c))
        if (onDemand) jobs.offerFirst(j) else {
            // background translations: drop the oldest when too many are waiting
            if (jobs.size > 64) jobs.pollLast()
            jobs.offerLast(j)
        }
    }

    /** My message: sent translated once ready (or as it was, on error). */
    fun requestOut(s: Server, c: Chan, text: String) {
        jobs.offerFirst(Job(true, s.idx, c.uid, 0, text, Config.langOut))
    }

    private fun run(j: Job) {
        val key = j.lang + "\t" + j.text
        if (!j.outgoing) synchronized(cache) { cache[key] }?.let { finishIn(j, it); return }
        Busy.set(if (j.outgoing) t("Traduciendo mensaje...") else t("Traduciendo..."))
        try {
            val r = translate(j.text, j.lang, j.outgoing)
            if (j.outgoing) {
                Irc.sendPrivmsg(j.sidx, j.uid, r.ifEmpty { j.text })
            } else {
                putCache(key, r)
                finishIn(j, r)
            }
        } catch (e: Exception) {
            if (j.outgoing) {
                AppEvents.toast(t("No se pudo traducir (%s). Enviado sin traducir.").format(e.message ?: t("vacío")))
                Irc.sendPrivmsg(j.sidx, j.uid, j.text)
            } else {
                finishIn(j, null)
                AppEvents.toast(t("Traducción: %s").format(e.message ?: ""))
            }
        } finally {
            Busy.set("")
        }
    }

    private fun finishIn(j: Job, res: String?) = Irc.locked {
        val s = Irc.servers.getOrNull(j.sidx) ?: return@locked
        val m = Irc.findChanUid(s, j.uid)?.findMsg(j.mid) ?: return@locked
        // show only when it actually differs from the original
        if (!res.isNullOrEmpty() && res != m.text) { m.trans = res; m.transState = TransState.DONE }
        else m.transState = TransState.SKIP
        Irc.dirty()
    }

    fun translate(text: String, lang: String, outgoing: Boolean): String =
        if (Config.trProvider == Config.TRP_OPENAI) openai(text, lang, outgoing) else google(text, lang, outgoing)

    /** Google Translate's free web endpoint: no key, detects the source language. */
    private fun google(text: String, lang: String, outgoing: Boolean): String {
        val tl = if (lang == "zh") "zh-CN" else lang
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tl&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val r = Http.call(url)
        if (r.code == 429) throw HttpError(t("Google: demasiadas traducciones, espera un momento"))
        if (!r.ok) throw HttpError("Google HTTP ${r.code}")
        val root = JSONArray(r.text)
        val segs = root.optJSONArray(0) ?: throw HttpError("Google: " + t("respuesta inválida"))
        val sb = StringBuilder()
        for (i in 0 until segs.length()) segs.optJSONArray(i)?.optString(0)?.let { if (it != "null") sb.append(it) }
        val src = root.optString(2)
        if (src.startsWith(lang.take(2), true)) return if (outgoing) text else ""   // already in that language
        return sb.toString().trim()
    }

    private fun openai(text: String, lang: String, outgoing: Boolean): String {
        if (Config.openaiKey.isEmpty()) throw HttpError(t("Falta la API key de OpenAI (Ajustes)"))
        val ln = mapOf("es" to "Spanish", "en" to "English", "pt" to "Portuguese", "fr" to "French", "de" to "German",
            "it" to "Italian", "ja" to "Japanese", "ru" to "Russian", "zh" to "Chinese", "ko" to "Korean")[lang] ?: "Spanish"
        val sys = if (outgoing)
            "You are a translator. Translate the user's IRC chat message to $ln. Rules:\n" +
                "- Reply ONLY with the translation, no quotes or explanations.\n" +
                "- Keep nicknames, URLs, emoticons and IRC slang natural.\n" +
                "- If the message is already in $ln, reply with the exact original text."
        else
            "You are a translator. Translate IRC chat messages to $ln. Rules:\n" +
                "- Reply ONLY with the translation, no quotes or explanations.\n" +
                "- If the message is already in $ln, reply with the exact original text.\n" +
                "- If the message is a number, URL, IRC command, or completely untranslatable, reply exactly: [SKIP]"
        val body = JSONObject()
            .put("model", Config.openaiModel.ifEmpty { "gpt-4o-mini" })
            .put("max_completion_tokens", 400)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", text)))
        val r = Http.call("https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer ${Config.openaiKey}"),
            body.toString().toRequestBody("application/json".toMediaType()))
        if (r.code == 401) throw HttpError(t("OpenAI: API key inválida"))
        if (r.code == 429) throw HttpError(t("OpenAI: límite de uso alcanzado"))
        val js = try { JSONObject(r.text) } catch (e: Exception) { throw HttpError("OpenAI ${r.code}: " + t("respuesta inválida")) }
        js.optJSONObject("usage")?.optLong("total_tokens")?.let { addUsage(it) }
        val content = js.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw HttpError("OpenAI ${r.code}: ${js.optJSONObject("error")?.optString("message") ?: t("respuesta inválida")}".take(140))
        val res = content.trim()
        return if (res == "[SKIP]") "" else res
    }

    // ---------------- cache and usage on disk ----------------

    private fun putCache(key: String, value: String) {
        synchronized(cache) { cache[key] = value }
        try {
            val (lang, src) = key.split('\t', limit = 2)
            fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            cacheFile.appendText("$lang\t${flat(src)}\t${flat(value)}\n")
        } catch (_: Exception) {}
    }

    private fun loadCache() {
        val f = cacheFile
        if (!f.exists()) return
        try {
            val lines = f.readLines()
            for (l in lines.takeLast(CACHE_MAX)) {
                val p = l.split('\t')
                if (p.size >= 3) cache[p[0] + "\t" + p[1]] = p[2]
            }
            // keep the file bounded
            if (lines.size > CACHE_MAX * 4) f.writeText(lines.takeLast(CACHE_MAX).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
    }

    private fun loadUsage() {
        try {
            val p = usageFile.readText().trim().split(' ')
            usageDate = p[0]
            val today = if (usageDate == LocalDate.now().toString()) p[1].toLong() else 0L
            usage.value = Triple(today, p[2].toLong(), p.getOrNull(3)?.toLong() ?: 0L)
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun addUsage(tokens: Long) {
        val now = LocalDate.now().toString()
        val (today, total, req) = usage.value
        val t2 = if (now != usageDate) 0L else today
        usageDate = now
        usage.value = Triple(t2 + tokens, total + tokens, req + 1)
        try { usageFile.writeText("$now ${usage.value.first} ${usage.value.second} ${usage.value.third}\n") } catch (_: Exception) {}
    }
}
