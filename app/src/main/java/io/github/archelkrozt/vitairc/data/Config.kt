package io.github.archelkrozt.vitairc.data

import io.github.archelkrozt.vitairc.irc.ServerCfg
import java.io.File
import kotlin.random.Random

/** Settings and servers, stored as config.ini with the same keys as VitaIRC for PS Vita. */
object Config {
    const val TRP_GOOGLE = 0
    const val TRP_OPENAI = 1
    const val IMG_LITTERBOX = 0
    const val IMG_IMGBB = 1
    const val IMG_FILEHOST = 2
    const val IMG_IMGUR = 3
    const val UPDATE_REPO = "ArchelKrozt/VitaIRC-Android"

    lateinit var dir: File
        private set

    val servers = ArrayList<ServerCfg>()

    var trProvider = TRP_GOOGLE
    var openaiKey = ""
    var openaiModel = "gpt-4o-mini"
    var langIn = "es"
    var langOut = "en"

    var imgHost = IMG_LITTERBOX
    var imgurId = ""
    var imgbbKey = ""
    var imgbbExpire = 0              // 0 never, 1 1h, 2 1 day, 3 1 week, 4 1 month
    var litterTime = 3               // 0 1h, 1 12h, 2 24h, 3 72h

    var updateCheck = true
    var updateLast = ""

    var linkPreviews = true
    var searchAdult = false
    var searchEngine = 0
    var sankakuUser = ""
    var sankakuPassword = ""

    var showJoins = true
    var keepConnected = true         // background service (Vita: keep_awake)
    var notifications = true
    var fontPct = 100
    var uiLang = 0                   // 0 system, 1 Spanish, 2 English
    var sound = true
    var ircColors = true
    var highlight = ""
    var ignore = ""

    val file get() = File(dir, "config.ini")

    fun init(dataDir: File) {
        dir = dataDir
        dir.mkdirs()
        load()
    }

    fun defaultServer() = ServerCfg(nick = "Vita%04d".format(Random.nextInt(10000)))

    private fun bool(v: String) = v.trim() == "1"
    private fun b(v: Boolean) = if (v) "1" else "0"

    fun load() {
        servers.clear()
        if (!file.exists()) {
            // first start: translate into the phone's language
            val sys = java.util.Locale.getDefault().language
            if (sys in langCodes) {
                langIn = sys
                langOut = if (sys == "en") "es" else "en"
            }
            servers.add(defaultServer())
            save()
            return
        }
        var cur: ServerCfg? = null
        var hadTranslator = false
        var hadImageHost = false
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line[0] == ';' || line[0] == '#') return@forEachLine
            if (line.startsWith("[")) {
                cur = if (line.startsWith("[server]")) ServerCfg(nick = "", channels = "").also { servers.add(it) } else null
                return@forEachLine
            }
            val eq = line.indexOf('=')
            if (eq < 0) return@forEachLine
            val k = line.substring(0, eq).trim()
            // values may carry an inline "; comment" only when written by hand
            val v = line.substring(eq + 1).trim()
            val s = cur
            if (s != null) {
                when (k) {
                    "name" -> s.name = v
                    "host" -> s.host = v
                    "port" -> s.port = v.toIntOrNull() ?: 6697
                    "ssl" -> s.ssl = bool(v)
                    "nick" -> s.nick = v
                    "user" -> s.user = v
                    "realname" -> s.realname = v
                    "password" -> s.password = v
                    "auth" -> s.auth = v.toIntOrNull() ?: 0
                    "channels" -> s.channels = v
                    "autoconnect" -> s.autoconnect = bool(v)
                }
            } else when (k) {
                "translator" -> { trProvider = v.toIntOrNull() ?: 0; hadTranslator = true }
                "openai_key" -> openaiKey = v
                "openai_model" -> openaiModel = v
                "lang_in" -> langIn = v
                "lang_out" -> langOut = v
                "image_host" -> { imgHost = v.toIntOrNull() ?: 0; hadImageHost = true }
                "imgur_client_id" -> imgurId = v
                "imgbb_key" -> imgbbKey = v
                "imgbb_expire" -> imgbbExpire = v.toIntOrNull() ?: 0
                "litterbox_time" -> litterTime = v.toIntOrNull() ?: 3
                "update_check" -> updateCheck = bool(v)
                "update_last" -> updateLast = v
                "link_previews" -> linkPreviews = bool(v)
                "search_adult" -> searchAdult = bool(v)
                "search_engine" -> searchEngine = v.toIntOrNull() ?: 0
                "sankaku_user" -> sankakuUser = v
                "sankaku_password" -> sankakuPassword = v
                "show_joins" -> showJoins = bool(v)
                "keep_awake", "keep_connected" -> keepConnected = bool(v)
                "notifications" -> notifications = bool(v)
                "font_pct" -> fontPct = v.toIntOrNull() ?: 100
                "ui_lang" -> uiLang = v.toIntOrNull() ?: 0
                "sound" -> sound = bool(v)
                "irc_colors" -> ircColors = bool(v)
                "highlight" -> highlight = v
                "ignore" -> ignore = v
            }
        }
        // a config.ini copied from VitaIRC 1.0 keeps ChatGPT / Imgur
        if (!hadTranslator) trProvider = if (openaiKey.isNotEmpty()) TRP_OPENAI else TRP_GOOGLE
        if (!hadImageHost) imgHost = if (imgurId.isNotEmpty()) IMG_IMGUR else IMG_LITTERBOX
        trProvider = trProvider.coerceIn(0, 1)
        imgHost = imgHost.coerceIn(0, 3)
        imgbbExpire = imgbbExpire.coerceIn(0, 4)
        litterTime = litterTime.coerceIn(0, 3)
        searchEngine = searchEngine.coerceIn(0, 2)
        fontPct = fontPct.coerceIn(70, 150)
        uiLang = uiLang.coerceIn(0, 2)
        for (s in servers) if (s.nick.isEmpty()) s.nick = defaultServer().nick
    }

    @Synchronized
    fun save() {
        val sb = StringBuilder()
        sb.append("; VitaIRC configuration (same format as VitaIRC for PS Vita).\n[general]\n")
        fun kv(k: String, v: Any) = sb.append(k).append('=').append(v).append('\n')
        kv("translator", trProvider)
        kv("openai_key", openaiKey)
        kv("openai_model", openaiModel)
        kv("lang_in", langIn)
        kv("lang_out", langOut)
        kv("image_host", imgHost)
        kv("imgur_client_id", imgurId)
        kv("imgbb_key", imgbbKey)
        kv("imgbb_expire", imgbbExpire)
        kv("litterbox_time", litterTime)
        kv("update_check", b(updateCheck))
        kv("update_last", updateLast)
        kv("link_previews", b(linkPreviews))
        kv("search_adult", b(searchAdult))
        kv("search_engine", searchEngine)
        kv("sankaku_user", sankakuUser)
        kv("sankaku_password", sankakuPassword)
        kv("show_joins", b(showJoins))
        kv("keep_connected", b(keepConnected))
        kv("notifications", b(notifications))
        kv("font_pct", fontPct)
        kv("ui_lang", uiLang)
        kv("sound", b(sound))
        kv("irc_colors", b(ircColors))
        kv("highlight", highlight)
        kv("ignore", ignore)
        for (s in servers) {
            sb.append("\n[server]\n")
            kv("name", s.name); kv("host", s.host); kv("port", s.port); kv("ssl", b(s.ssl))
            kv("nick", s.nick); kv("user", s.user); kv("realname", s.realname); kv("password", s.password)
            kv("auth", s.auth); kv("channels", s.channels); kv("autoconnect", b(s.autoconnect))
        }
        val tmp = File(dir, "config.ini.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(file)
    }

    val langCodes = listOf("es", "en", "pt", "fr", "de", "it", "ja", "ru", "zh", "ko")
    val langNames = listOf("Español", "English", "Português", "Français", "Deutsch", "Italiano", "日本語", "Русский", "中文", "한국어")
    fun langName(code: String) = langNames.getOrElse(langCodes.indexOf(code)) { code }
}
