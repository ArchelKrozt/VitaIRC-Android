package io.github.archelkrozt.vitairc.net

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

enum class LinkKind { IMAGE, VIDEO, PAGE }

/** What a link in the chat shows under its message. */
data class LinkPreview(
    val url: String,
    val kind: LinkKind,
    val ok: Boolean,
    val title: String = "",
    val desc: String = "",
    val site: String = "",
    val image: String = "",          // picture for the thumbnail (page's og:image, video poster...)
    val media: String = "",          // direct image or video URL
    val size: Long = -1,
    val videoSite: Boolean = false,  // page of a video (YouTube...)
    val gif: Boolean = false,
)

object Previews {
    private const val MAX = 200
    private const val PAGE_MAX = 768 * 1024
    private val ua = "Mozilla/5.0 (compatible; ${Http.userAgent}; link preview)"

    private val cache = object : LinkedHashMap<String, LinkPreview>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreview>?) = size > MAX
    }
    private val inFlight = HashMap<String, Deferred<LinkPreview>>()

    // ---------------- URL helpers ----------------

    private fun ext(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('/', "")
        .let { if (it.contains('.')) it.substringAfterLast('.').lowercase() else "" }

    fun host(url: String) = try { URI(url).host.orEmpty().removePrefix("www.") } catch (e: Exception) { "" }

    /** imgur.com/<id> -> its picture (albums and galleries stay pages) */
    private fun imgurId(url: String): String? {
        val h = host(url)
        if (h != "imgur.com" && h != "m.imgur.com") return null
        val p = url.substringAfter("imgur.com/", "")
        if (p.isEmpty() || p.startsWith("a/") || p.startsWith("gallery/") || p.startsWith("t/") || p.startsWith("user/")) return null
        return p.takeWhile { it !in "/?#." }.ifEmpty { null }
    }

    /** Direct picture for a link the viewer can open. */
    fun imageUrl(url: String): String? {
        val e = ext(url)
        if (e in setOf("png", "jpg", "jpeg", "webp", "gif")) return url
        return imgurId(url)?.let { "https://i.imgur.com/$it.jpg" }
    }

    fun kindOf(url: String): LinkKind {
        val e = ext(url)
        return when {
            e in setOf("png", "jpg", "jpeg", "webp", "gif") || imgurId(url) != null -> LinkKind.IMAGE
            e in setOf("mp4", "m4v", "mov", "webm", "gifv", "mkv") -> LinkKind.VIDEO
            else -> LinkKind.PAGE
        }
    }

    /** Animated "GIF" that is really a short video (Imgur's .gifv): plays inline, muted, looping */
    fun isLoop(url: String) = ext(url) == "gifv"

    /** Imgur's .gifv is an MP4 */
    fun videoUrl(url: String) = if (ext(url) == "gifv") url.substringBeforeLast('.') + ".mp4" else url

    private fun isYoutube(url: String) = host(url) in setOf("youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com")

    // ---------------- cache ----------------

    fun cached(url: String): LinkPreview? = synchronized(cache) { cache[url] }

    /** Cached preview, or fetched once (several messages with one link share the request). */
    suspend fun load(url: String): LinkPreview {
        cached(url)?.let { return it }
        val d = synchronized(cache) {
            inFlight[url] ?: AppScope.async { fetch(url) }.also { inFlight[url] = it }
        }
        val r = try { d.await() } catch (e: Exception) { LinkPreview(url, kindOf(url), false) }
        synchronized(cache) { cache[url] = r; inFlight.remove(url) }
        return r
    }

    // ---------------- fetching ----------------

    fun fetch(url: String): LinkPreview = when (kindOf(url)) {
        LinkKind.IMAGE -> {
            val direct = imageUrl(url) ?: url
            LinkPreview(url, LinkKind.IMAGE, true, image = direct, media = direct, gif = ext(direct) == "gif", site = host(url))
        }
        LinkKind.VIDEO -> video(url, videoUrl(url))
        LinkKind.PAGE -> page(url)
    }

    private fun video(url: String, media: String): LinkPreview {
        var size = -1L
        try { Http.call(media, method = "HEAD", ua = ua).let { if (it.ok) size = it.length } } catch (_: Exception) {}
        val name = media.substringBefore('?').substringAfterLast('/')
        // Imgur keeps a still picture of each video
        val poster = if (host(media) == "i.imgur.com") media.substringBeforeLast('.') + ".jpg" else ""
        return LinkPreview(url, LinkKind.VIDEO, true, title = name, site = host(url), image = poster, media = media, size = size)
    }

    private fun youtube(url: String): LinkPreview {
        val r = Http.call("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), max = 64 * 1024)
        if (!r.ok) return LinkPreview(url, LinkKind.PAGE, false)
        val js = JSONObject(r.text)
        val title = js.optString("title")
        return LinkPreview(url, LinkKind.PAGE, title.isNotEmpty(), title = title, desc = js.optString("author_name"),
            site = "YouTube", image = js.optString("thumbnail_url"), videoSite = true)
    }

    private fun page(url: String): LinkPreview {
        if (isYoutube(url)) return youtube(url)
        // a link without extension may still be a picture or a video
        try {
            val h = Http.call(url, method = "HEAD", ua = ua)
            if (h.ok && h.contentType.startsWith("image/"))
                return LinkPreview(url, LinkKind.IMAGE, true, image = h.finalUrl, media = h.finalUrl, gif = h.contentType == "image/gif", site = host(url))
            if (h.ok && h.contentType.startsWith("video/")) return video(url, h.finalUrl)
        } catch (_: Exception) {}
        val r = Http.call(url, max = PAGE_MAX, ua = ua)
        // servers that refuse HEAD: the answer itself says what the link is
        if (r.ok && r.contentType.startsWith("image/"))
            return LinkPreview(url, LinkKind.IMAGE, true, image = r.finalUrl, media = r.finalUrl, gif = r.contentType == "image/gif", site = host(url))
        if (r.ok && r.contentType.startsWith("video/")) return video(url, r.finalUrl)
        if (!r.ok || (r.contentType.isNotEmpty() && r.contentType != "text/html" && r.contentType != "application/xhtml+xml"))
            return LinkPreview(url, LinkKind.PAGE, false)
        val meta = Html.meta(r.text)
        val img = meta.image.takeIf { it.isNotEmpty() }?.let { resolve(r.finalUrl, it) }.orEmpty()
        val title = meta.title
        return LinkPreview(url, LinkKind.PAGE, title.isNotEmpty() || img.isNotEmpty(), title = title, desc = meta.desc,
            site = meta.site.ifEmpty { host(r.finalUrl) }, image = img, videoSite = meta.video)
    }

    /** Resolves a link found in a page against the page's address. */
    fun resolve(base: String, ref: String): String = try { URI(base).resolve(ref.trim()).toString() } catch (e: Exception) { ref }
}

/** og:/twitter: tags and <title> of a page. */
object Html {
    class Meta(val title: String, val desc: String, val site: String, val image: String, val video: Boolean)

    private val metaTag = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String): String? =
        Regex("\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } } }

    fun meta(html: String): Meta {
        val head = html.substringBefore("</head", html)
        val m = HashMap<String, String>()
        for (t in metaTag.findAll(head)) {
            val key = (attr(t.value, "property") ?: attr(t.value, "name"))?.lowercase() ?: continue
            val v = attr(t.value, "content") ?: continue
            if (key !in m) m[key] = v
        }
        val titleTag = Regex("<title[^>]*>(.*?)</title", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(head)?.groupValues?.get(1)
        return Meta(
            title = text(m["og:title"] ?: m["twitter:title"] ?: titleTag ?: ""),
            desc = text(m["og:description"] ?: m["twitter:description"] ?: m["description"] ?: ""),
            site = text(m["og:site_name"] ?: ""),
            image = text(m["og:image"] ?: m["og:image:secure_url"] ?: m["og:image:url"] ?: m["twitter:image"] ?: m["twitter:image:src"] ?: ""),
            video = (m["og:type"] ?: "").startsWith("video"),
        )
    }

    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»")

    private fun cp(v: Int?): String? = if (v != null && Character.isValidCodePoint(v) && v != 0) String(Character.toChars(v)) else null

    /** Entities decoded, whitespace collapsed. */
    fun text(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { mr ->
        val e = mr.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> cp(e.substring(2).toIntOrNull(16)) ?: mr.value
            e.startsWith("#") -> cp(e.substring(1).toIntOrNull()) ?: mr.value
            else -> entities[e] ?: mr.value
        }
    }.replace(Regex("\\s+"), " ").trim()
}
