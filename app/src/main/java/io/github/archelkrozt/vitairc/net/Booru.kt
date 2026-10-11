package io.github.archelkrozt.vitairc.net

import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** One search result (Safebooru, Danbooru or Sankaku). */
data class BPost(
    val engine: Int,
    val id: String,
    val rating: Char,               // 'g' general, 's' sensitive, 'q' questionable, 'e' explicit
    val w: Int, val h: Int,
    val ext: String,
    val fileSize: Long,
    val thumbUrl: String,
    val viewUrl: String,            // sample shown in the viewer
    val fileUrl: String,            // original
    val tags: String,
    val artist: String = "",            // "" when the service doesn't say
) {
    val key get() = "$engine/$id"
}

data class BooruState(
    val engine: Int = 0,
    val query: String = "",
    val results: List<BPost> = emptyList(),
    val loading: Boolean = false,
    val more: Boolean = false,
    val error: String = "",
)

object Booru {
    const val SAFEBOORU = 0
    const val DANBOORU = 1
    const val SANKAKU = 2
    const val MAX_RESULTS = 240
    const val MAX_FAVS = 300
    private const val PAGE = 40

    val names = listOf("Safebooru", "Danbooru", "Sankaku")
    val state = MutableStateFlow(BooruState())
    val favorites = MutableStateFlow<List<BPost>>(emptyList())

    private var page = 0
    private var cursor = ""
    private var job: Job? = null
    private var skToken = ""
    private var skTokenUser = ""
    private var skTokenUntil = 0L

    private val favFile get() = File(Config.dir, "favorites.txt")
    val favThumbDir get() = File(Config.dir, "favthumbs")

    // ---------------- content filter ----------------

    /** Never shown, whatever the settings. */
    private val blockAlways = setOf("loli", "shota", "lolicon", "shotacon", "toddlercon", "child_abuse", "underage")
    /** Also hidden on questionable/explicit posts. */
    private val blockMature = setOf("child", "children", "female_child", "male_child", "kid", "kids", "toddler", "baby",
        "young", "younger", "aged_down", "teenage", "elementary_school_student", "middle_school_student", "school_child")

    fun allowed(rating: Char, tags: String, adult: Boolean): Boolean {
        if (!adult && rating != 'g') return false
        val set = tags.split(' ').toSet()
        if (set.any { it in blockAlways }) return false
        if ((rating == 'q' || rating == 'e') && set.any { it in blockMature }) return false
        return true
    }

    private fun ratingCode(r: String) = when (r) {
        "general", "safe", "g" -> 'g'
        "sensitive", "s" -> 's'
        "questionable", "q" -> 'q'
        "" -> 'q'
        else -> 'e'
    }

    /** "artist_name" tags -> "artist name, other" ("" when none or only placeholders) */
    fun artistName(tags: List<String>): String {
        val skip = setOf("banned_artist", "unknown_artist", "anonymous_artist", "artist_request", "third-party_edit")
        return tags.filter { it.isNotEmpty() && it !in skip }.take(3).joinToString(", ") { it.replace('_', ' ') }
    }

    /** The message for the chat: link and, when known, the author ("<url> 🎨 <artist>"). */
    fun chatText(link: String, p: BPost) = if (p.artist.isEmpty()) link else "$link 🎨 ${p.artist}"

    /** One post by its number (a public favorite of someone else's profile). */
    fun lookup(engine: Int, id: String): BPost {
        val safeId = id.filter { it.isLetterOrDigit() }
        val r = when (engine) {
            SAFEBOORU -> Http.call("https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&id=$safeId")
            DANBOORU -> Http.call("https://danbooru.donmai.us/posts/$safeId.json")
            else -> { skLogin(); Http.call("https://sankakuapi.com/v2/posts/keyset?lang=en&limit=1&tags=" + URLEncoder.encode("id:$safeId", "UTF-8"), skHeaders(), ua = "Mozilla/5.0") }
        }
        if (!r.ok) throw HttpError("HTTP ${r.code}")
        // Danbooru answers one object; the parser reads lists
        val body = if (engine == DANBOORU) "[" + r.text + "]" else r.text
        return parse(engine, body, Config.searchAdult).first.firstOrNull() ?: throw HttpError(t("Sin resultados."))
    }

    private fun badExt(e: String) = e.isEmpty() || e in setOf("mp4", "webm", "zip", "swf", "avif")

    private fun urlExt(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('/', "")
        .let { if (it.contains('.')) it.substringAfterLast('.').lowercase() else "" }

    // ---------------- Sankaku ----------------

    fun imageHeaders(engine: Int): Map<String, String> =
        if (engine == SANKAKU) mapOf("Referer" to "https://sankaku.app/", "User-Agent" to "Mozilla/5.0") else emptyMap()

    fun skHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/vnd.sankaku.api+json;v=2")
        put("Origin", "https://sankaku.app")
        if (skToken.isNotEmpty()) put("Authorization", skToken)
    }

    /** Logs in when an account is set (the token lasts about an hour). */
    @Synchronized
    fun skLogin() {
        val user = Config.sankakuUser
        val pass = Config.sankakuPassword
        if (user.isEmpty() || pass.isEmpty()) { skToken = ""; return }
        if (skToken.isNotEmpty() && System.currentTimeMillis() < skTokenUntil && user == skTokenUser) return
        skTokenUser = user
        skToken = ""
        try {
            val body = JSONObject().put("login", user).put("password", pass).toString()
            val r = Http.call("https://sankakuapi.com/auth/token", skHeaders(), body.toRequestBody("application/json".toMediaType()), ua = "Mozilla/5.0")
            val tok = runCatching { JSONObject(r.text).optString("access_token") }.getOrNull().orEmpty()
            if (tok.isNotEmpty()) { skToken = "Bearer $tok"; skTokenUntil = System.currentTimeMillis() + 55 * 60_000L }
        } catch (_: Exception) {}
        if (skToken.isEmpty()) AppEvents.toast(t("Sankaku: no se pudo iniciar sesión, se busca sin cuenta"))
    }

    /** Sankaku links expire after about an hour. */
    fun expired(p: BPost): Boolean {
        if (p.engine != SANKAKU) return false
        val e = Regex("[?&]expires=(\\d+)").find(p.viewUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return e < System.currentTimeMillis() / 1000 + 120
    }

    /** Looks the post up again for fresh links (and keeps them in the lists). */
    fun refresh(p: BPost): BPost {
        skLogin()
        val r = Http.call("https://sankakuapi.com/v2/posts/keyset?lang=en&limit=1&tags=" + URLEncoder.encode("id:${p.id}", "UTF-8"),
            skHeaders(), ua = "Mozilla/5.0")
        val found = if (r.ok) parse(SANKAKU, r.text, true).first.firstOrNull() else null
        found ?: throw HttpError(if (r.ok) t("Sankaku: la imagen ya no está disponible") else "HTTP ${r.code}")
        val np = p.copy(thumbUrl = found.thumbUrl, viewUrl = found.viewUrl, fileUrl = found.fileUrl)
        state.value = state.value.let { s -> s.copy(results = s.results.map { if (it.key == p.key) np else it }) }
        if (favorites.value.any { it.key == p.key }) {
            favorites.value = favorites.value.map { if (it.key == p.key) np else it }
            saveFavorites()
        }
        return np
    }

    fun fresh(p: BPost) = if (expired(p)) refresh(p) else p

    // ---------------- search ----------------

    /** Parses one page: kept posts, how many the service sent, next cursor (Sankaku). */
    fun parse(engine: Int, json: String, adult: Boolean): Triple<List<BPost>, Int, String> {
        val out = ArrayList<BPost>()
        var raw = 0
        var next = ""
        val arr: JSONArray = when (engine) {
            SANKAKU -> {
                val o = JSONObject(json)
                next = o.optJSONObject("meta")?.optString("next")?.takeIf { it != "null" }.orEmpty()
                o.optJSONArray("data") ?: JSONArray()
            }
            else -> if (json.isBlank()) JSONArray() else JSONArray(json)
        }
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            raw++
            val p: BPost
            val tags: String
            when (engine) {
                SAFEBOORU -> {
                    val file = it.optString("file_url")
                    tags = it.optString("tags").trim()
                    p = BPost(engine, it.optString("id"), ratingCode(it.optString("rating")), it.optInt("width"), it.optInt("height"),
                        urlExt(file), 0, it.optString("preview_url"),
                        if (it.optString("sample").equals("true", true)) it.optString("sample_url") else file, file, tags)
                }
                DANBOORU -> {
                    tags = it.optString("tag_string")
                    p = BPost(engine, it.optString("id"), ratingCode(it.optString("rating")), it.optInt("image_width"),
                        it.optInt("image_height"), it.optString("file_ext").lowercase(), it.optLong("file_size"),
                        it.optString("preview_file_url"), it.optString("large_file_url"), it.optString("file_url"), tags,
                        artistName(it.optString("tag_string_artist").split(' ')))
                }
                else -> {
                    if (it.optBoolean("is_premium") || it.optBoolean("redirect_to_signup")) continue
                    val ta = it.optJSONArray("tags") ?: JSONArray()
                    tags = (0 until ta.length()).mapNotNull { k ->
                        ta.optJSONObject(k)?.let { o -> o.optString("tagName").ifEmpty { o.optString("name_en").ifEmpty { o.optString("name") } } }
                            ?.takeIf { s -> s.isNotEmpty() }?.replace(' ', '_')
                    }.joinToString(" ")
                    val artists = (0 until ta.length()).mapNotNull { k ->
                        ta.optJSONObject(k)?.takeIf { o -> o.optInt("type") == 1 }
                            ?.let { o -> o.optString("tagName").ifEmpty { o.optString("name_en").ifEmpty { o.optString("name") } } }
                    }
                    val sample = it.optString("sample_url")
                    val r = it.optString("rating")
                    p = BPost(engine, it.optString("id"), if (r == "s") 'g' else ratingCode(r), it.optInt("width"), it.optInt("height"),
                        it.optString("file_ext").lowercase(), it.optLong("file_size"), sample, sample, it.optString("file_url"), tags,
                        artistName(artists))
                }
            }
            val fixed = if (p.viewUrl.isEmpty() || p.viewUrl == "null") p.copy(viewUrl = p.fileUrl) else p
            if (fixed.id.isNotEmpty() && fixed.fileUrl.isNotEmpty() && fixed.fileUrl != "null" && fixed.thumbUrl.isNotEmpty() &&
                fixed.thumbUrl != "null" && !badExt(fixed.ext) && allowed(fixed.rating, tags, adult))
                out.add(fixed.copy(tags = tags.take(300)))
        }
        return Triple(out, raw, next)
    }

    fun searchUrl(engine: Int, query: String, adult: Boolean, page: Int, cursor: String): String {
        val safe = when (engine) { SAFEBOORU -> " rating:general"; DANBOORU -> " rating:g"; else -> " rating:safe" }
        val q = URLEncoder.encode((query + if (adult) "" else safe).trim(), "UTF-8").replace("+", "%20")
        return when (engine) {
            SAFEBOORU -> "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&limit=$PAGE&pid=$page&tags=$q"
            DANBOORU -> "https://danbooru.donmai.us/posts.json?limit=$PAGE&page=${page + 1}&tags=$q"
            else -> "https://sankakuapi.com/v2/posts/keyset?lang=en&limit=$PAGE&tags=$q" + (if (cursor.isNotEmpty()) "&next=$cursor" else "")
        }
    }

    fun search(engine: Int, query: String) {
        job?.cancel()
        page = 0
        cursor = ""
        state.value = BooruState(engine, query.trim(), loading = true)
        fetchPage()
    }

    fun more() {
        val s = state.value
        if (!s.more || s.loading) return
        state.value = s.copy(loading = true)
        fetchPage()
    }

    private fun fetchPage() {
        val s0 = state.value
        job = AppScope.launch {
            var s = s0
            try {
                // a page can come back empty after filtering: keep paging a little
                repeat(4) {
                    if (s.engine == SANKAKU) skLogin()
                    val r = Http.call(searchUrl(s.engine, s.query, Config.searchAdult, page, cursor),
                        if (s.engine == SANKAKU) skHeaders() else emptyMap(), ua = if (s.engine == SANKAKU) "Mozilla/5.0" else Http.userAgent)
                    if (!r.ok) {
                        val msg = runCatching { JSONObject(r.text).let { o -> o.optString("message").ifEmpty { o.optString("error") } } }.getOrNull().orEmpty()
                        throw HttpError(if (msg.isNotEmpty()) "${names[s.engine]}: ${msg.take(120)}" else "HTTP ${r.code}")
                    }
                    val (posts, raw, next) = parse(s.engine, r.text, Config.searchAdult)
                    page++
                    cursor = next
                    val all = (s.results + posts.filter { p -> s.results.none { it.key == p.key } }).take(MAX_RESULTS)
                    val more = raw > 0 && all.size < MAX_RESULTS && (if (s.engine == SANKAKU) next.isNotEmpty() else raw >= PAGE)
                    s = s.copy(results = all, more = more, error = "")
                    if (posts.isNotEmpty() || !more) return@repeat
                }
                state.value = s.copy(loading = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = s.copy(loading = false, more = false, error = e.message ?: "")
            }
        }
    }

    // ---------------- favorites ----------------

    fun isFav(p: BPost) = favorites.value.any { it.key == p.key }

    fun thumbFile(p: BPost) = File(favThumbDir, "${p.engine}_${p.id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")}.jpg")

    /** Adds or removes a favorite; [thumb] is a small JPEG kept on the card. Returns true when added. */
    fun toggleFav(p: BPost, thumb: ByteArray?): Boolean {
        val cur = favorites.value
        if (cur.any { it.key == p.key }) {
            favorites.value = cur.filter { it.key != p.key }
            thumbFile(p).delete()
            saveFavorites()
            return false
        }
        if (cur.size >= MAX_FAVS) {
            AppEvents.toast(t("Tienes demasiados favoritos (máximo %d)").format(MAX_FAVS))
            return false
        }
        favorites.value = listOf(p) + cur          // newest first
        if (thumb != null) try { favThumbDir.mkdirs(); thumbFile(p).writeBytes(thumb) } catch (_: Exception) {}
        saveFavorites()
        return true
    }

    /** engine, id, rating, w, h, ext, size, thumb, view, file, tags (same as VitaIRC for PS Vita) */
    @Synchronized
    private fun saveFavorites() {
        val f = favorites.value
        if (f.isEmpty()) { favFile.delete(); return }
        favFile.writeText(f.joinToString("") { p ->
            "${p.engine}\t${p.id}\t${p.rating}\t${p.w}\t${p.h}\t${p.ext}\t${p.fileSize}\t${p.thumbUrl}\t${p.viewUrl}\t${p.fileUrl}\t${p.tags.replace('\t', ' ')}\t${p.artist.replace('\t', ' ')}\n"
        })
    }

    fun loadFavorites() {
        val list = ArrayList<BPost>()
        try {
            favFile.forEachLine { l ->
                val f = l.split('\t')
                if (f.size < 10) return@forEachLine
                val e = f[0].toIntOrNull() ?: return@forEachLine
                if (e !in 0..2) return@forEachLine
                list.add(BPost(e, f[1], f[2].firstOrNull() ?: 'g', f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0, f[5],
                    f[6].toLongOrNull() ?: 0, f[7], f[8], f[9], f.getOrElse(10) { "" }, f.getOrElse(11) { "" }))
            }
        } catch (_: Exception) {}
        favorites.value = list.take(MAX_FAVS)
        state.value = state.value.copy(engine = Config.searchEngine)
    }

    fun pageUrl(p: BPost) = when (p.engine) {
        DANBOORU -> "https://danbooru.donmai.us/posts/${p.id}"
        SANKAKU -> "https://sankaku.app/posts/${p.id}"
        else -> "https://safebooru.org/index.php?page=post&s=view&id=${p.id}"
    }

    /** Downloads an image of a post (Sankaku needs a Referer). */
    fun download(p: BPost, url: String, max: Int = 40 * 1024 * 1024): HttpResult {
        val r = Http.call(url, imageHeaders(p.engine), max = max, ua = if (p.engine == SANKAKU) "Mozilla/5.0" else Http.userAgent)
        if (!r.ok) throw HttpError("HTTP ${r.code}")
        return r
    }

    /**
     * Link for the chat: Safebooru/Danbooru links are permanent and go directly; Sankaku's expire
     * within the hour, so the image is re-uploaded with the image service. The result arrives
     * through AppEvents.uploads.
     */
    fun send(p0: BPost, sidx: Int, uid: Long) {
        AppScope.launch {
            val res = try {
                if (p0.engine != SANKAKU) UploadResult(sidx, uid, true, chatText(p0.fileUrl, p0)) else {
                    val p = fresh(p0)
                    val original = p.fileSize in 1..8L * 1024 * 1024
                    val url = if (original) p.fileUrl else p.viewUrl
                    Busy.set(t("Descargando imagen..."))
                    val data = download(p, url).body
                    val ext = urlExt(url).ifEmpty { "jpg" }
                    val mime = when (ext) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
                    UploadResult(sidx, uid, true, chatText(Uploader.uploadNow(sidx, UploadSource("sankaku_${p.id}.$ext", mime, data.size.toLong()) { data.inputStream() }), p))
                }
            } catch (e: Exception) {
                UploadResult(sidx, uid, false, e.message ?: "")
            } finally {
                Busy.set("")
            }
            AppEvents.uploads.emit(res)
        }
    }
}
