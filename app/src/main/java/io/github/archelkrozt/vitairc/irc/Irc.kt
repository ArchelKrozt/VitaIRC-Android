package io.github.archelkrozt.vitairc.irc

import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.data.History
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Per-server state (one connection thread each). */
class Server(val idx: Int, var cfg: ServerCfg) {
    @Volatile var state = ServerState.OFF
    @Volatile var wantConnect = false
    var nick = ""
    var prefixes = "~&@%+"
    val chans = ArrayList<Chan>()
    val outq = ArrayList<String>()
    var joinAt = 0L
    @Volatile var probe = false
    var pendingFocus = ""
    val capsLs = StringBuilder()
    var capSasl = false
    var saslOk = false
    var away = false
    var capTags = false
    var capBatch = false
    var capHistory = false
    var historyMax = 0
    var listMask = false
    var filehost = ""
    var certOk = true
    val flood = HashMap<String, LongArray>()     // nick -> [count, resetAt, warned]
    val listed = ArrayList<ListedChan>()
    var listing = 0                              // 1 loading, 2 done
    var listSkip = 0                             // older LIST answers still arriving (dropped)
    val batches = HashMap<String, Batch>()
    val offq = ArrayList<OfflineMsg>()
    var thread: Thread? = null
    @Volatile var conn: Conn? = null

    val status get() = chans[0]
}

enum class BatchType { OTHER, HISTORY, TARGETS }

class Batch(val type: BatchType, val chanUid: Long = 0, val mode: HistReq = HistReq.NONE) {
    val msgs = ArrayList<Msg>()
}

/** Hooks to the app (translation, notifications...). The defaults do nothing (tests). */
object IrcHooks {
    var translateIn: (Server, Chan, Msg) -> Unit = { _, _, _ -> }
    var translateOut: (Server, Chan, String) -> Unit = { _, _, _ -> }
    var translationReady: () -> Boolean = { false }
    var notify: (Server, Chan, Msg) -> Unit = { _, _, _ -> }
    var toast: (String) -> Unit = {}
    var beep: () -> Unit = {}
}

/** IRC line: tags, source nick, command and parameters. */
class Line(raw: String) {
    val tags = HashMap<String, String>()
    var nick: String? = null
    var cmd = ""
    val p = ArrayList<String>()
    val last get() = p.lastOrNull() ?: ""

    init {
        var s = raw
        if (s.startsWith("@")) {
            val sp = s.indexOf(' ')
            if (sp > 0) {
                for (tag in s.substring(1, sp).split(';')) {
                    val eq = tag.indexOf('=')
                    if (eq < 0) tags[tag] = "" else tags[tag.substring(0, eq)] = unescape(tag.substring(eq + 1))
                }
                s = s.substring(sp + 1).trimStart(' ')
            } else s = ""
        }
        if (s.startsWith(":")) {
            val sp = s.indexOf(' ')
            val src = if (sp > 0) s.substring(1, sp) else s.substring(1)
            nick = src.substringBefore('!').substringBefore('@')
            s = if (sp > 0) s.substring(sp + 1).trimStart(' ') else ""
        }
        val sp = s.indexOf(' ')
        cmd = if (sp > 0) s.substring(0, sp) else s
        var rest = if (sp > 0) s.substring(sp + 1) else ""
        while (rest.isNotEmpty() && p.size < 16) {
            rest = rest.trimStart(' ')
            if (rest.isEmpty()) break
            if (rest[0] == ':') { p.add(rest.substring(1)); break }
            val e = rest.indexOf(' ')
            if (e < 0) { p.add(rest); break }
            p.add(rest.substring(0, e))
            rest = rest.substring(e + 1)
        }
    }

    companion object {
        fun unescape(v: String): String {
            val b = StringBuilder()
            var i = 0
            while (i < v.length) {
                val c = v[i]
                if (c != '\\') { b.append(c); i++; continue }
                i++
                if (i >= v.length) break
                b.append(when (v[i]) { ':' -> ';'; 's' -> ' '; 'r' -> '\r'; 'n' -> '\n'; else -> v[i] })
                i++
            }
            return b.toString()
        }

        fun escape(v: String): String = buildString {
            for (c in v) append(when (c) { ';' -> "\\:"; ' ' -> "\\s"; '\\' -> "\\\\"; '\r' -> "\\r"; '\n' -> "\\n"; else -> c.toString() })
        }
    }
}

/** All servers and windows. Everything here needs [lock]. */
object Irc {
    const val MAX_MSGS = 500
    const val MAX_CHANS = 48
    const val MAX_OFFLINE = 32
    const val MAX_LISTED = 600
    private const val FLOOD_MAX = 10
    private const val FLOOD_WINDOW = 60_000L
    const val CTCP_VERSION = "VitaIRC for Android"

    val lock = ReentrantLock()
    inline fun <T> locked(block: () -> T): T = lock.withLock(block)

    /** Bumped on every change; the UI redraws when it moves. */
    val revision = MutableStateFlow(0L)
    fun dirty() = revision.update { it + 1 }

    val servers = ArrayList<Server>()
    private var nextUid = 1L

    /** Window on screen (written by the UI) */
    @Volatile var viewSidx = -1
    @Volatile var viewUid = 0L
    @Volatile var appVisible = true

    /** Requests from the network side for the UI */
    var focusReq: Pair<Int, Long>? = null
    var invite: Pair<Int, String>? = null

    private var replaying = false

    // ------------------------------------------------------------------
    // Windows and messages
    // ------------------------------------------------------------------

    fun isChannelName(n: String) = n.isNotEmpty() && n[0] in "#&!+"

    fun findChan(s: Server, name: String) = s.chans.firstOrNull { it.name.equals(name, true) }
    fun findChanUid(s: Server, uid: Long) = s.chans.firstOrNull { it.uid == uid }

    fun getChan(s: Server, name: String, type: ChanType): Chan {
        findChan(s, name)?.let { return it }
        if (s.chans.size >= MAX_CHANS) return s.status
        val c = Chan(nextUid++, type, name)
        s.chans.add(c)
        if (type != ChanType.STATUS) {
            History.load(s, c)
            History.saveSession()
        }
        dirty()
        return c
    }

    fun closeChan(s: Server, c: Chan) {
        if (c.type == ChanType.STATUS) return
        s.chans.remove(c)
        History.close(c)
        History.saveSession()
        dirty()
    }

    fun chanLang(c: Chan) = c.transLang.ifEmpty { Config.langIn }

    fun isIgnored(nick: String?): Boolean {
        if (nick.isNullOrEmpty() || Config.ignore.isBlank()) return false
        return Config.ignore.split(',', ' ').any { it.isNotBlank() && IrcText.globMatch(it.trim(), nick) }
    }

    fun isHighlight(s: Server, text: String): Boolean {
        val me = s.nick.ifEmpty { s.cfg.nick }
        if (me.isNotEmpty() && IrcText.containsWord(text, me)) return true
        return Config.highlight.split(',').any { it.isNotBlank() && IrcText.containsWord(text, it.trim()) }
    }

    fun myPrefix(s: Server, c: Chan): Char = c.users.firstOrNull { it.nick.equals(s.nick, true) }?.prefix ?: 0.toChar()

    private fun pushMsg(c: Chan, m: Msg) {
        if (c.msgs.size >= MAX_MSGS) c.msgs.removeAt(0)
        c.msgs.add(m)
    }

    private fun dateSep(c: Chan, ts: Long) {
        val m = Msg(++c.nextId, MsgType.DATE, "", "", emptyList(), false, ts, null)
        pushMsg(c, m)
        c.lastYmd = TimeUtil.ymd(ts)
    }

    /** Adds a message to a window (now, or at ts when replaying a log or history). */
    fun addMsgAt(s: Server, c: Chan, type: MsgType, nick: String?, text: String, self: Boolean,
                 ts: Long = 0, msgid: String? = null, pending: Boolean = false): Msg {
        val when_ = if (ts > 0) ts else TimeUtil.now()
        if (c.type != ChanType.STATUS && type != MsgType.DATE && TimeUtil.ymd(when_) != c.lastYmd) dateSep(c, when_)
        val f = IrcText.parse(text)
        val body = if (f.text.length > 1000) f.text.substring(0, 1000) else f.text
        val m = Msg(++c.nextId, type, nick ?: "", body, f.spans, self, when_, msgid)
        m.pending = pending
        pushMsg(c, m)
        val visible = appVisible && viewSidx == s.idx && viewUid == c.uid
        if (!pending) History.log(s, c, m)
        if (History.loading) {
            if (m.chatty && !self && c.type != ChanType.STATUS && isHighlight(s, m.text)) m.highlight = true
        } else if (m.chatty && !self) {
            if (c.type != ChanType.STATUS && isHighlight(s, m.text)) m.highlight = true
            val notify = m.highlight || c.type == ChanType.QUERY
            val quiet = c.muted || replaying
            if (!visible) {
                c.unread++
                if (notify) {
                    c.mention++
                    if (!quiet) IrcHooks.notify(s, c, m)
                }
            } else if (notify && !quiet && Config.sound) {
                IrcHooks.beep()
            }
            if (c.transIn && !IrcText.shouldSkipTranslation(m.text)) {
                m.transState = TransState.PENDING
                IrcHooks.translateIn(s, c, m)
            }
        }
        dirty()
        return m
    }

    fun addMsg(s: Server, c: Chan, type: MsgType, nick: String?, text: String, self: Boolean = false, line: Line? = null): Msg {
        val ts = line?.tags?.get("time")?.let { TimeUtil.parseIso(it) } ?: 0L
        return addMsgAt(s, c, type, nick, text, self, ts, line?.tags?.get("msgid")?.ifEmpty { null })
    }

    private fun status(s: Server, type: MsgType, text: String) = addMsg(s, s.status, type, null, text)

    /** Window for replies the user asked for (WHOIS, errors...) */
    private fun replyChan(s: Server): Chan =
        (if (viewSidx == s.idx) findChanUid(s, viewUid) else null) ?: s.status

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    private fun prefixRank(s: Server, p: Char): Int {
        if (p == 0.toChar()) return 99
        val i = s.prefixes.indexOf(p)
        return if (i >= 0) i else 50
    }

    fun sortUsers(s: Server, c: Chan) {
        c.users.sortWith(compareBy<ChanUser> { prefixRank(s, it.prefix) }.thenBy { it.nick.lowercase() })
    }

    private fun findUser(c: Chan, nick: String) = c.users.firstOrNull { it.nick.equals(nick, true) }

    private fun addUser(s: Server, c: Chan, entry: String) {
        var e = entry
        var prefix = 0.toChar()
        while (e.isNotEmpty() && s.prefixes.indexOf(e[0]) >= 0) {   // multi-prefix: keep the highest
            if (prefix == 0.toChar() || prefixRank(s, e[0]) < prefixRank(s, prefix)) prefix = e[0]
            e = e.substring(1)
        }
        val nick = e.substringBefore('!')
        if (nick.isEmpty()) return
        val u = findUser(c, nick)
        if (u != null) u.prefix = prefix else c.users.add(ChanUser(nick, prefix))
    }

    private fun removeUser(c: Chan, nick: String): Boolean = c.users.removeAll { it.nick.equals(nick, true) }

    // ------------------------------------------------------------------
    // Outgoing
    // ------------------------------------------------------------------

    fun sendRaw(s: Server, line: String) {
        if (s.outq.size >= 64) return
        // never allow embedded line breaks to inject extra commands
        var l = line.replace('\r', ' ').replace('\n', ' ')
        val bytes = l.toByteArray(Charsets.UTF_8)
        if (bytes.size > 510) l = String(bytes.copyOf(510), Charsets.UTF_8).trimEnd('�')
        s.outq.add("$l\r\n")
    }

    /** Splits long text at word/UTF-8 boundaries so each PRIVMSG fits in 512 bytes. */
    fun splitText(text: String, max: Int = 380): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.toByteArray(Charsets.UTF_8).size <= max) { out.add(rest); break }
            var n = 0
            var bytes = 0
            while (n < rest.length) {
                val cp = rest.codePointAt(n)
                val cl = Character.charCount(cp)
                val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + b > max) break
                bytes += b
                n += cl
            }
            val sp = rest.lastIndexOf(' ', n)
            if (sp > n - 60 && sp > 0) n = sp
            out.add(rest.substring(0, n))
            rest = rest.substring(n).trimStart(' ')
        }
        return out
    }

    private fun sendText(s: Server, target: String, c: Chan?, text: String, action: Boolean) {
        for (chunk in splitText(text)) {
            sendRaw(s, if (action) "PRIVMSG $target :\u0001ACTION $chunk\u0001" else "PRIVMSG $target :$chunk")
            if (c != null) addMsg(s, c, if (action) MsgType.ACTION else MsgType.MSG, s.nick, chunk, true)
        }
    }

    /** Offline: show the message now (dimmed) and send it after reconnecting. */
    private fun queueOffline(s: Server, c: Chan, text: String, action: Boolean) {
        if (s.offq.size >= MAX_OFFLINE) {
            addMsg(s, c, MsgType.ERROR, null, t("No conectado: el mensaje no se envió."))
            return
        }
        val m = addMsgAt(s, c, if (action) MsgType.ACTION else MsgType.MSG, s.nick.ifEmpty { s.cfg.nick }, text, true, pending = true)
        s.offq.add(OfflineMsg(c.uid, m.id, action, text))
        if (s.offq.size == 1) IrcHooks.toast(t("Sin conexión: se enviará al reconectar"))
    }

    /** Sends what was typed offline in one window (only = null: every query). */
    private fun flushOffline(s: Server, only: Chan?) {
        val keep = ArrayList<OfflineMsg>()
        for (q in s.offq) {
            val c = findChanUid(s, q.chanUid)
            if (c != null && (if (only != null) c != only else c.type != ChanType.QUERY)) { keep.add(q); continue }
            if (c != null) {          // a closed window drops its queue
                sendText(s, c.name, null, q.text, q.action)
                c.findMsg(q.msgId)?.let { m ->
                    // it is said now: it moves after what arrived meanwhile
                    c.msgs.remove(m)
                    m.pending = false
                    m.ts = TimeUtil.now()
                    m.time = TimeUtil.hhmm(m.ts)
                    if (TimeUtil.ymd(m.ts) != c.lastYmd) dateSep(c, m.ts)
                    pushMsg(c, m)
                    History.log(s, c, m)
                }
            }
        }
        s.offq.clear()
        s.offq.addAll(keep)
        dirty()
    }

    fun sendPrivmsg(sidx: Int, chanUid: Long, text: String) = locked {
        val s = servers.getOrNull(sidx) ?: return@locked
        val c = findChanUid(s, chanUid) ?: return@locked
        if (c.type == ChanType.STATUS) return@locked
        if (s.state != ServerState.ONLINE) queueOffline(s, c, text, false) else sendText(s, c.name, c, text, false)
    }

    private val helpLines = listOf(
        "Comandos disponibles:",
        "/join #canal [clave]  /part [motivo]  /close",
        "/msg nick texto  /query nick  /me acción  /notice destino texto",
        "/nick nuevo  /topic [texto]  /whois nick  /list",
        "/connect  /disconnect  /quote COMANDO CRUDO  /clear",
        "/away [motivo]  /back  /ignore [nick]  /unignore nick",
        "/op /deop /voice /devoice nick  /kick nick [motivo]  /ban nick  /kickban nick",
        "Cualquier otro /COMANDO se envía tal cual al servidor.",
    )

    private fun editIgnore(nick: String, add: Boolean) {
        val list = Config.ignore.split(',', ' ').filter { it.isNotBlank() && !it.equals(nick, true) }.toMutableList()
        if (add) list.add(nick)
        Config.ignore = list.joinToString(",")
        Config.save()
    }

    /** Text typed into a window: a command, or a message. */
    fun userInput(sidx: Int, chanUid: Long, input: String) = locked {
        val text = input.trim()
        if (text.isEmpty()) return@locked
        val s = servers.getOrNull(sidx) ?: return@locked
        val c = findChanUid(s, chanUid) ?: s.status

        if (text[0] != '/' || text.startsWith("//")) {
            val body = if (text[0] == '/') text.substring(1) else text
            when {
                c.type == ChanType.STATUS -> addMsg(s, c, MsgType.ERROR, null, t("Esta es la ventana del servidor. Usa /join #canal o /help."))
                s.state != ServerState.ONLINE -> queueOffline(s, c, body, false)
                c.transOut && IrcHooks.translationReady() && !IrcText.shouldSkipTranslation(body) -> IrcHooks.translateOut(s, c, body)
                else -> sendText(s, c.name, c, body, false)
            }
            return@locked
        }
        val sp = text.indexOf(' ')
        val cmd = (if (sp > 0) text.substring(1, sp) else text.substring(1)).uppercase()
        val args = if (sp > 0) text.substring(sp + 1).trimStart(' ') else ""
        val who = args.substringBefore(' ')
        val rest = if (args.contains(' ')) args.substringAfter(' ') else ""

        when (cmd) {
            "CONNECT" -> { connect(sidx); return@locked }
            "DISCONNECT", "QUIT" -> { disconnect(sidx, args.ifEmpty { null }); return@locked }
            "HELP" -> { helpLines.forEach { addMsg(s, c, MsgType.INFO, null, t(it)) }; return@locked }
            "CLEAR" -> { c.msgs.clear(); c.lastYmd = 0; dirty(); return@locked }
            "QUERY" -> {
                if (who.isNotEmpty()) {
                    val q = getChan(s, who, ChanType.QUERY)
                    focusReq = sidx to q.uid
                    if (rest.isNotEmpty() && s.state == ServerState.ONLINE) sendText(s, q.name, q, rest, false)
                    dirty()
                }
                return@locked
            }
            "IGNORE", "UNIGNORE" -> {
                if (who.isNotEmpty()) {
                    editIgnore(who, cmd == "IGNORE")
                    addMsg(s, c, MsgType.INFO, null, (if (cmd == "IGNORE") t("Ignorando a %s") else t("Ya no ignoras a %s")).format(who))
                } else {
                    addMsg(s, c, MsgType.INFO, null, t("Ignorados: %s").format(Config.ignore.ifEmpty { t("(ninguno)") }))
                }
                return@locked
            }
            "CLOSE" -> {
                if (c.type == ChanType.CHANNEL && c.joined && s.state == ServerState.ONLINE) sendRaw(s, "PART ${c.name} :VitaIRC")
                closeChan(s, c)
                return@locked
            }
            "ME" -> if (s.state != ServerState.ONLINE) {
                if (c.type != ChanType.STATUS && args.isNotEmpty()) queueOffline(s, c, args, true)
                return@locked
            }
        }
        if (s.state != ServerState.ONLINE) {
            addMsg(s, c, MsgType.ERROR, null, t("No conectado. Usa /connect."))
            return@locked
        }
        val isChan = c.type == ChanType.CHANNEL
        when (cmd) {
            "AWAY" -> sendRaw(s, if (args.isNotEmpty()) "AWAY :$args" else "AWAY")
            "BACK" -> sendRaw(s, "AWAY")
            "OP", "DEOP", "VOICE", "DEVOICE" -> if (who.isNotEmpty() && isChan) {
                val sign = if (cmd.startsWith("DE")) '-' else '+'
                val mode = if (cmd.endsWith("OP")) 'o' else 'v'
                sendRaw(s, "MODE ${c.name} $sign$mode $who")
            }
            "KICK" -> if (who.isNotEmpty() && isChan) sendRaw(s, "KICK ${c.name} $who :${rest.ifEmpty { "VitaIRC" }}")
            "BAN", "KICKBAN" -> if (who.isNotEmpty() && isChan) {
                sendRaw(s, "MODE ${c.name} +b $who!*@*")
                if (cmd == "KICKBAN") sendRaw(s, "KICK ${c.name} $who :${rest.ifEmpty { "VitaIRC" }}")
            }
            "JOIN", "J" -> if (who.isNotEmpty()) {
                val name = if (isChannelName(who)) who else "#$who"
                sendRaw(s, if (rest.isNotEmpty()) "JOIN $name $rest" else "JOIN $name")
                s.pendingFocus = name
            }
            "PART", "LEAVE" -> if (isChan) sendRaw(s, "PART ${c.name} :${args.ifEmpty { "VitaIRC" }}")
            "ME" -> if (c.type != ChanType.STATUS && args.isNotEmpty()) sendText(s, c.name, c, args, true)
            "MSG", "NOTICE" -> if (who.isNotEmpty() && rest.isNotEmpty()) {
                if (cmd == "NOTICE") {
                    sendRaw(s, "NOTICE $who :$rest")
                    addMsg(s, c, MsgType.NOTICE, s.nick, rest, true)
                } else {
                    val tch = if (isChannelName(who)) findChan(s, who) else getChan(s, who, ChanType.QUERY)
                    if (tch != null) sendText(s, tch.name, tch, rest, false) else sendRaw(s, "PRIVMSG $who :$rest")
                }
            }
            "NICK" -> if (args.isNotEmpty()) sendRaw(s, "NICK $args")
            "TOPIC" -> if (isChan) sendRaw(s, if (args.isNotEmpty()) "TOPIC ${c.name} :$args" else "TOPIC ${c.name}")
            "WHOIS" -> if (args.isNotEmpty()) sendRaw(s, "WHOIS $args")
            "LIST" -> {
                // a list still arriving: its rest is not part of the new one
                if (s.listing == 1 && s.listSkip < 8) s.listSkip++
                s.listed.clear()
                s.listing = 1
                sendRaw(s, if (args.isNotEmpty()) "LIST $args" else "LIST")   // e.g. /list *vita*
            }
            "QUOTE", "RAW" -> if (args.isNotEmpty()) sendRaw(s, args)
            else -> sendRaw(s, if (args.isNotEmpty()) "$cmd $args" else cmd)
        }
        dirty()
    }

    // ------------------------------------------------------------------
    // IRCv3 chathistory
    // ------------------------------------------------------------------

    /** Time of the newest (or oldest) real message in a window, 0 if none. */
    private fun edgeTs(c: Chan, newest: Boolean): Long {
        val seq = if (newest) c.msgs.asReversed() else c.msgs
        return seq.firstOrNull { it.chatty && !it.pending && it.ts > 0 }?.ts ?: 0L
    }

    private fun historyLimit(s: Server, want: Int) = if (s.historyMax in 1 until want) s.historyMax else want
    private fun historyBusy(c: Chan) = c.histReq != HistReq.NONE && TimeUtil.now() - c.histReqAt < 20000

    /** What was said in a window while we were away (after joining it). */
    private fun requestLatest(s: Server, c: Chan) {
        if (!s.capHistory || c.type == ChanType.STATUS || historyBusy(c)) return
        val lim = historyLimit(s, 100)
        val tsv = edgeTs(c, true)
        sendRaw(s, if (tsv > 0) "CHATHISTORY LATEST ${c.name} timestamp=${TimeUtil.iso(tsv)} $lim" else "CHATHISTORY LATEST ${c.name} * $lim")
        c.histReq = HistReq.LATEST
        c.histReqAt = TimeUtil.now()
        c.histSince = tsv
    }

    /** Messages older than the oldest one in the window. Returns true when a request was sent. */
    fun requestOlder(s: Server, c: Chan): Boolean {
        if (!s.capHistory || s.state != ServerState.ONLINE || c.type == ChanType.STATUS || c.histEnd || historyBusy(c)) return false
        val room = MAX_MSGS - c.msgs.size - 20
        if (room < 10) {
            IrcHooks.toast(t("No caben más mensajes en esta ventana"))
            c.histEnd = true
            return false
        }
        val lim = historyLimit(s, minOf(room, 50))
        val tsv = edgeTs(c, false)
        if (tsv > 0) {
            sendRaw(s, "CHATHISTORY BEFORE ${c.name} timestamp=${TimeUtil.iso(tsv)} $lim")
            c.histReq = HistReq.BEFORE
        } else {
            sendRaw(s, "CHATHISTORY LATEST ${c.name} * $lim")
            c.histReq = HistReq.LATEST
            c.histSince = 0
        }
        c.histReqAt = TimeUtil.now()
        dirty()
        return true
    }

    private fun batchOpen(s: Server, ref: String, type: String, param: String) {
        if (s.batches.size >= 8) return
        s.batches[ref] = when {
            type == "chathistory" && param.isNotEmpty() -> {
                val c = if (isChannelName(param)) findChan(s, param) else getChan(s, param, ChanType.QUERY)
                if (c != null && c.type != ChanType.STATUS) {
                    if (c.histReq == HistReq.NONE) c.histSince = edgeTs(c, true)   // not asked for: new is what we lack
                    Batch(BatchType.HISTORY, c.uid, if (c.histReq != HistReq.NONE) c.histReq else HistReq.LATEST)
                } else Batch(BatchType.OTHER)
            }
            type == "draft/chathistory-targets" || type == "chathistory-targets" -> Batch(BatchType.TARGETS)
            else -> Batch(BatchType.OTHER)
        }
    }

    private fun historyBatch(s: Server, l: Line): Batch? =
        l.tags["batch"]?.let { s.batches[it] }?.takeIf { it.type == BatchType.HISTORY }

    /** Messages inside a chathistory batch are collected and merged at its end. */
    private fun historyCollect(s: Server, l: Line, type: MsgType, nick: String, text: String): Boolean {
        val b = historyBatch(s, l) ?: return false
        val f = IrcText.parse(text)
        val ts = l.tags["time"]?.let { TimeUtil.parseIso(it) } ?: TimeUtil.now()
        b.msgs.add(Msg(0, type, nick, f.text.take(1000), f.spans, isMe(s, nick), ts, l.tags["msgid"]?.ifEmpty { null }))
        return true
    }

    private fun sameMsg(a: Msg, b: Msg): Boolean {
        if (a.msgid != null && a.msgid == b.msgid) return true
        val d = kotlin.math.abs(a.ts - b.ts)
        // a bouncer can renumber what it stores; old logs keep minutes only
        val window = if (a.msgid != null && b.msgid != null) 1000 else 90000
        return d < window && a.type == b.type && a.nick.equals(b.nick, true) && a.text == b.text
    }

    /** Merges a chathistory batch into the window in time order, skipping what is already there. */
    private fun chanMerge(s: Server, c: Chan, b: Batch) {
        val add = ArrayList<Msg>()
        for (a in b.msgs) {
            if (isIgnored(a.nick)) continue
            if (c.msgs.any { it.chatty && sameMsg(it, a) } || add.any { sameMsg(it, a) }) continue
            add.add(a)
        }
        b.msgs.clear()
        if (add.isEmpty()) return
        add.sortBy { it.ts }
        val since = if (b.mode == HistReq.BEFORE) Long.MAX_VALUE else c.histSince
        val firstNew = c.nextId + 1
        val merged = ArrayList<Msg>(c.msgs.size + add.size)
        var j = 0
        for (m in c.msgs) {
            if (m.type == MsgType.DATE) continue
            while (j < add.size && add[j].ts < m.ts) merged.add(add[j++].also { it.id = ++c.nextId })
            merged.add(m)
        }
        while (j < add.size) merged.add(add[j++].also { it.id = ++c.nextId })
        // day separators again
        c.msgs.clear()
        var ymd = 0
        for (m in merged) {
            val d = TimeUtil.ymd(m.ts)
            if (d != ymd) {
                ymd = d
                c.msgs.add(Msg(++c.nextId, MsgType.DATE, "", "", emptyList(), false, m.ts, null))
            }
            c.msgs.add(m)
        }
        while (c.msgs.size > MAX_MSGS) c.msgs.removeAt(0)
        c.lastYmd = ymd

        // what is newer than the window had: log it, count it, translate it
        val visible = appVisible && viewSidx == s.idx && viewUid == c.uid
        var mentions = 0
        var translate = 12
        for (m in c.msgs.asReversed()) {
            if (m.id < firstNew || m.type == MsgType.DATE) continue
            m.time = TimeUtil.hhmm(m.ts)
            if (!m.self && c.type != ChanType.STATUS && isHighlight(s, m.text)) m.highlight = true
            if (m.ts <= since || m.self) continue
            if (!visible) {
                c.unread++
                if (m.highlight || c.type == ChanType.QUERY) { c.mention++; mentions++ }
            }
            if (c.transIn && translate > 0 && !IrcText.shouldSkipTranslation(m.text)) {
                m.transState = TransState.PENDING
                IrcHooks.translateIn(s, c, m)
                translate--
            }
        }
        for (m in c.msgs) if (m.id >= firstNew && m.type != MsgType.DATE && m.ts > since) History.log(s, c, m)
        if (mentions > 0 && !c.muted) {
            IrcHooks.toast(t("%d mensajes para ti en %s mientras no estabas").format(mentions, c.name))
            c.msgs.lastOrNull { it.id >= firstNew && it.highlight }?.let { IrcHooks.notify(s, c, it) }
        }
        dirty()
    }

    private fun batchClose(s: Server, ref: String) {
        val b = s.batches.remove(ref) ?: return
        if (b.type == BatchType.HISTORY) {
            findChanUid(s, b.chanUid)?.let { c ->
                if (b.mode == HistReq.BEFORE && b.msgs.isEmpty()) c.histEnd = true
                c.histReq = HistReq.NONE
                chanMerge(s, c, b)
            }
        }
        dirty()
    }

    /** Nicks that talk to everybody: their history is not worth a window. */
    private fun isService(n: String) = n.contains('.') || listOf("NickServ", "ChanServ", "SaslServ", "MemoServ", "HostServ",
        "OperServ", "BotServ", "BouncerServ", "*status", "Global").any { it.equals(n, true) }

    // ------------------------------------------------------------------
    // Reactions (+draft/react)
    // ------------------------------------------------------------------

    private fun reactApply(m: Msg, emoji: String, nick: String, add: Boolean, mine: Boolean) {
        if (emoji.isEmpty()) return
        val who = nick.lowercase()
        val list = m.reacts ?: if (add) ArrayList<React>().also { m.reacts = it } else return
        var e = list.firstOrNull { it.emoji == emoji }
        if (add) {
            if (e == null) {
                if (list.size >= 6) return
                e = React(emoji).also { list.add(it) }
            }
            if (!e.who.add(who)) return            // already counted
            e.count++
            if (mine) e.mine = true
        } else {
            if (e == null || !e.who.remove(who)) return
            e.count--
            if (mine) e.mine = false
            if (e.count <= 0) list.remove(e)
            if (list.isEmpty()) m.reacts = null
        }
        dirty()
    }

    fun canReact(s: Server, m: Msg) = s.capTags && s.state == ServerState.ONLINE && m.msgid != null && m.chatty

    fun sendReact(s: Server, c: Chan, m: Msg, emoji: String) {
        if (!canReact(s, m)) return
        val mine = m.reacts?.any { it.emoji == emoji && it.mine } == true
        sendRaw(s, "@+draft/${if (mine) "unreact" else "react"}=${Line.escape(emoji)};+draft/reply=${Line.escape(m.msgid!!)} TAGMSG ${c.name}")
        reactApply(m, emoji, s.nick, !mine, true)
    }

    // ------------------------------------------------------------------
    // Incoming
    // ------------------------------------------------------------------

    private fun isMe(s: Server, nick: String?) = nick != null && nick.equals(s.nick, true)

    private fun useSasl(s: Server) = s.cfg.password.isNotEmpty() && (s.cfg.auth == ServerCfg.AUTH_SASL || s.cfg.auth == ServerCfg.AUTH_AUTO)
    private fun accountName(s: Server) = s.cfg.user.ifEmpty { s.cfg.nick }

    /** "@#chan" (a message to the channel's operators) -> "#chan" */
    private fun stripStatusmsg(target: String) =
        if (!isChannelName(target) && target.isNotEmpty() && target[0] in "@%~" && isChannelName(target.substring(1))) target.substring(1) else target

    /** Private-message flood protection: true when the message must be dropped. */
    private fun pmFlooding(s: Server, nick: String): Boolean {
        val now = TimeUtil.now()
        val key = nick.lowercase()
        val e = s.flood.getOrPut(key) {
            if (s.flood.size >= 16) s.flood.remove(s.flood.minByOrNull { it.value[1] }!!.key)
            longArrayOf(0, 0, 0)
        }
        if (now > e[1]) { e[0] = 0; e[2] = 0; e[1] = now + FLOOD_WINDOW }
        if (++e[0] <= FLOOD_MAX) return false
        if (e[2] == 0L) {
            e[2] = 1
            addMsg(s, s.status, MsgType.ERROR, null, t("Flood de %s: ignorando sus privados por un minuto").format(nick))
        }
        return true
    }

    private fun doAutojoin(s: Server) {
        for (ch in s.cfg.channels.split(',', ' ')) if (ch.isNotBlank()) sendRaw(s, "JOIN ${ch.trim()}")
        // re-join channels that were open before a reconnect
        for (c in s.chans) if (c.type == ChanType.CHANNEL && !c.joined && !IrcText.containsWord(s.cfg.channels, c.name)) sendRaw(s, "JOIN ${c.name}")
        flushOffline(s, null)      // channels get theirs once joined
    }

    private fun handleCtcp(s: Server, l: Line, target: String, text: String) {
        if (isIgnored(l.nick)) return
        val body = text.substring(1).substringBefore('\u0001')
        val cmd = body.substringBefore(' ')
        val arg = if (body.contains(' ')) body.substringAfter(' ') else ""
        val nick = l.nick ?: return
        if (cmd == "ACTION") {
            val self = isMe(s, nick)
            val chan = isChannelName(target)
            if (historyCollect(s, l, MsgType.ACTION, nick, arg)) return
            val c = getChan(s, if (chan || self) target else nick, if (chan) ChanType.CHANNEL else ChanType.QUERY)
            addMsg(s, c, MsgType.ACTION, nick, arg, self, l)
            return
        }
        if (historyBatch(s, l) != null || isMe(s, nick)) return     // old or my own CTCP requests: no replies
        when (cmd) {
            "VERSION" -> sendRaw(s, "NOTICE $nick :\u0001VERSION $CTCP_VERSION\u0001")
            "PING" -> sendRaw(s, "NOTICE $nick :\u0001PING $arg\u0001")
            "TIME" -> sendRaw(s, "NOTICE $nick :\u0001TIME ${TimeUtil.hhmm(TimeUtil.now())}\u0001")
        }
    }

    fun handleLine(s: Server, raw: String) {
        val l = Line(raw)
        val cmd = l.cmd
        if (cmd.isEmpty()) return
        val last = l.last
        when (cmd) {
            "PING" -> { sendRaw(s, "PONG :$last"); return }
            "PONG" -> return
            "PRIVMSG" -> if (l.p.size >= 2 && l.nick != null) {
                val target = stripStatusmsg(l.p[0])
                val text = l.p[1]
                if (text.startsWith("\u0001")) { handleCtcp(s, l, target, text); return }
                // my own messages come back from a bouncer when sent from another client
                val self = isMe(s, l.nick)
                val chan = isChannelName(target)
                if (isIgnored(l.nick)) return
                if (self && isMe(s, target) && historyBatch(s, l) == null) return   // to myself: already shown when sent
                if (historyCollect(s, l, MsgType.MSG, l.nick!!, text)) return
                if (!chan && !self && pmFlooding(s, l.nick!!)) return
                val c = getChan(s, if (chan || self) target else l.nick!!, if (chan) ChanType.CHANNEL else ChanType.QUERY)
                addMsg(s, c, MsgType.MSG, l.nick, text, self, l)
                return
            }
            "NOTICE" -> if (l.p.size >= 2) {
                val target = stripStatusmsg(l.p[0])
                val nick = l.nick
                if (nick != null && (isIgnored(nick) || (isMe(s, target) && s.state == ServerState.ONLINE && !nick.contains('.') && pmFlooding(s, nick)))) return
                var text = l.p[1]
                if (text.startsWith("\u0001")) text = text.substring(1)     // CTCP replies
                text = text.substringBefore('\u0001')
                if (nick != null && !nick.contains('.') && historyCollect(s, l, MsgType.NOTICE, nick, text)) return
                val c = when {
                    nick == null || nick.contains('.') || s.state != ServerState.ONLINE -> s.status
                    isChannelName(target) -> getChan(s, target, ChanType.CHANNEL)
                    else -> findChan(s, nick) ?: replyChan(s)
                }
                addMsg(s, c, MsgType.NOTICE, nick ?: "", text, false, l)
                if (nick.equals("NickServ", true) && s.cfg.password.isEmpty() &&
                    (text.contains("is registered", true) || text.contains("identify via", true)))
                    IrcHooks.toast(t("Nick registrado: pon tu contraseña en Servidores para identificarte solo"))
                return
            }
            "JOIN" -> if (l.p.isNotEmpty() && l.nick != null) {
                val name = l.p[0]
                val c = getChan(s, name, ChanType.CHANNEL)
                if (isMe(s, l.nick)) {
                    c.joined = true
                    c.users.clear()
                    addMsg(s, c, MsgType.JOIN, null, t("Te uniste al canal"))
                    if (s.pendingFocus.equals(name, true)) {
                        s.pendingFocus = ""
                        focusReq = s.idx to c.uid
                    }
                    requestLatest(s, c)
                    flushOffline(s, c)
                } else {
                    addUser(s, c, l.nick!!)
                    sortUsers(s, c)
                    if (Config.showJoins) addMsg(s, c, MsgType.JOIN, null, t("%s se unió").format(l.nick), line = l)
                }
                return
            }
            "PART" -> if (l.p.isNotEmpty() && l.nick != null) {
                val c = findChan(s, l.p[0]) ?: return
                if (isMe(s, l.nick)) {
                    c.joined = false
                    c.users.clear()
                    addMsg(s, c, MsgType.PART, null, t("Saliste del canal"))
                } else {
                    removeUser(c, l.nick!!)
                    if (Config.showJoins) addMsg(s, c, MsgType.PART, null,
                        t("%s salió%s%s").format(l.nick, if (l.p.size > 1) ": " else "", if (l.p.size > 1) l.p[1] else ""), line = l)
                }
                dirty()
                return
            }
            "QUIT" -> if (l.nick != null) {
                val b = t("%s se desconectó%s%s").format(l.nick, if (l.p.isNotEmpty()) ": " else "", if (l.p.isNotEmpty()) l.p[0] else "")
                for (c in s.chans.toList()) {
                    val had = removeUser(c, l.nick!!)
                    val isQuery = c.type == ChanType.QUERY && c.name.equals(l.nick, true)
                    if ((had && Config.showJoins) || isQuery) addMsg(s, c, MsgType.QUIT, null, b, line = l)
                }
                dirty()
                return
            }
            "KICK" -> if (l.p.size >= 2) {
                val c = findChan(s, l.p[0]) ?: return
                val b = t("%s fue expulsado por %s: %s").format(l.p[1], l.nick ?: "?", if (l.p.size > 2) l.p[2] else "")
                if (isMe(s, l.p[1])) {
                    c.joined = false
                    c.users.clear()
                    addMsg(s, c, MsgType.ERROR, null, b)
                    IrcHooks.toast(t("Fuiste expulsado de %s").format(c.name))
                } else {
                    removeUser(c, l.p[1])
                    addMsg(s, c, MsgType.KICK, null, b, line = l)
                }
                return
            }
            "NICK" -> if (l.p.isNotEmpty() && l.nick != null) {
                val nn = l.p[0]
                val b = t("%s ahora es %s").format(l.nick, nn)
                val me = isMe(s, l.nick)
                if (me) s.nick = nn
                for (c in s.chans) {
                    val u = findUser(c, l.nick!!)
                    if (u != null) { u.nick = nn; addMsg(s, c, MsgType.NICK, null, b, line = l) }
                    else if (c.type == ChanType.QUERY && c.name.equals(l.nick, true)) { c.name = nn; addMsg(s, c, MsgType.NICK, null, b, line = l) }
                }
                if (me) addMsg(s, s.status, MsgType.NICK, null, b)
                return
            }
            "TOPIC" -> if (l.p.size >= 2) {
                val c = findChan(s, l.p[0]) ?: return
                c.topic = IrcText.strip(l.p[1])
                addMsg(s, c, MsgType.TOPIC, null, t("%s cambió el tema: %s").format(l.nick ?: "?", l.p[1]), line = l)
                return
            }
            "MODE" -> if (l.p.size >= 2) {
                val c = if (isChannelName(l.p[0])) findChan(s, l.p[0]) else null
                val modes = l.p.drop(1).joinToString(" ")
                addMsg(s, c ?: s.status, MsgType.MODE, null, t("%s establece modo %s").format(l.nick ?: "?", modes), line = l)
                if (c != null) sendRaw(s, "NAMES ${c.name}")      // refresh prefixes
                return
            }
            "CAP" -> if (l.p.size >= 3) {
                when (l.p[1]) {
                    "LS" -> {
                        s.capsLs.append(last).append(' ')
                        if (l.p.size >= 4 && l.p[2] == "*") return
                        val offered = s.capsLs.split(' ').map { it.substringBefore('=') }.toSet()
                        val wanted = listOf("server-time", "znc.in/server-time-iso", "multi-prefix", "away-notify",
                            "batch", "message-tags", "draft/chathistory", "sasl")
                        val req = ArrayList<String>()
                        for (w in wanted) {
                            if (w == "sasl" && !useSasl(s)) continue
                            if (w == "znc.in/server-time-iso" && "server-time" in req) continue
                            if (w in offered) req.add(w)
                        }
                        sendRaw(s, if (req.isNotEmpty()) "CAP REQ :${req.joinToString(" ")}" else "CAP END")
                    }
                    "ACK" -> {
                        var sasl = false
                        for (tk in last.split(' ')) {
                            if (tk.isEmpty() || tk[0] == '-') continue
                            when (tk) {
                                "sasl" -> sasl = true
                                "message-tags" -> s.capTags = true
                                "batch" -> s.capBatch = true
                                "draft/chathistory" -> s.capHistory = true
                            }
                        }
                        s.capHistory = s.capHistory && s.capBatch
                        if (sasl) { s.capSasl = true; sendRaw(s, "AUTHENTICATE PLAIN") }
                        else if (s.state != ServerState.ONLINE) sendRaw(s, "CAP END")
                    }
                    "NAK" -> if (s.state != ServerState.ONLINE) sendRaw(s, "CAP END")
                }
                return
            }
            "BATCH" -> if (l.p.isNotEmpty()) {
                val r = l.p[0]
                if (r.startsWith("+") && l.p.size >= 2) batchOpen(s, r.substring(1), l.p[1], l.p.getOrElse(2) { "" })
                else if (r.startsWith("-")) batchClose(s, r.substring(1))
                return
            }
            "CHATHISTORY" -> if (l.p.size >= 2 && l.p[0] == "TARGETS") {
                val tg = l.p[1]
                if (!isChannelName(tg) && !isMe(s, tg) && !isService(tg) && !isIgnored(tg)) requestLatest(s, getChan(s, tg, ChanType.QUERY))
                return
            }
            "FAIL" -> if (l.p.isNotEmpty()) {
                if (l.p[0] == "CHATHISTORY") { s.chans.forEach { it.histReq = HistReq.NONE }; dirty() }
                else status(s, MsgType.ERROR, last)
                return
            }
            "TAGMSG" -> if (l.p.isNotEmpty() && l.nick != null) {
                val react = l.tags["+draft/react"].orEmpty()
                val unreact = l.tags["+draft/unreact"].orEmpty()
                val reply = l.tags["+draft/reply"].orEmpty()
                val emoji = react.ifEmpty { unreact }
                if (emoji.isEmpty() || reply.isEmpty() || isIgnored(l.nick)) return
                val self = isMe(s, l.nick)
                val c = findChan(s, if (isChannelName(l.p[0]) || self) l.p[0] else l.nick!!)
                c?.findMsgId(reply)?.let { reactApply(it, emoji, l.nick!!, react.isNotEmpty(), self) }
                return
            }
            "AWAY" -> if (l.nick != null) {
                for (c in s.chans) findUser(c, l.nick!!)?.away = l.p.isNotEmpty() && l.p[0].isNotEmpty()
                dirty()
                return
            }
            "INVITE" -> if (l.p.size >= 2 && l.nick != null) {
                if (isIgnored(l.nick)) return
                invite = s.idx to l.p[1]
                addMsg(s, replyChan(s), MsgType.INFO, null, t("%s te invitó a %s. Usa /join %s").format(l.nick, l.p[1], l.p[1]))
                IrcHooks.toast(t("%s te invitó a %s").format(l.nick, l.p[1]))
                return
            }
            "AUTHENTICATE" -> if (l.p.isNotEmpty() && l.p[0] == "+") {
                val user = accountName(s)
                val token = "$user\u0000$user\u0000${s.cfg.password}".toByteArray(Charsets.UTF_8)
                sendRaw(s, "AUTHENTICATE " + Base64.getEncoder().encodeToString(token))
                return
            }
            "ERROR" -> { status(s, MsgType.ERROR, t("Servidor: %s").format(last)); return }
        }
        if (cmd.length != 3 || !cmd.all { it.isDigit() }) return
        numeric(s, cmd.toInt(), l)
    }

    private fun numeric(s: Server, num: Int, l: Line) {
        val last = l.last
        when (num) {
            1 -> {
                s.state = ServerState.ONLINE
                if (l.p.isNotEmpty()) s.nick = l.p[0]
                status(s, MsgType.SERVER, t("Conectado a %s como %s").format(s.cfg.host, s.nick))
                val pw = s.cfg.password.isNotEmpty()
                if (pw && !s.saslOk && s.cfg.auth != ServerCfg.AUTH_PASS) {
                    sendRaw(s, "PRIVMSG NickServ :IDENTIFY ${accountName(s)} ${s.cfg.password}")
                    s.joinAt = TimeUtil.now() + 2200
                } else {
                    s.joinAt = TimeUtil.now() + 300
                }
                // our nick was still taken by an old session: take it back
                if (pw && s.cfg.auth != ServerCfg.AUTH_PASS && !s.nick.equals(s.cfg.nick, true)) {
                    sendRaw(s, if (s.saslOk) "PRIVMSG NickServ :REGAIN ${s.cfg.nick}" else "PRIVMSG NickServ :REGAIN ${s.cfg.nick} ${s.cfg.password}")
                    status(s, MsgType.INFO, t("Recuperando tu nick %s...").format(s.cfg.nick))
                }
                // private conversations that moved while we were away
                if (s.capHistory) {
                    var since = s.chans.maxOfOrNull { edgeTs(it, true) } ?: 0L
                    if (since <= 0) since = TimeUtil.now() - 7 * 86_400_000L
                    sendRaw(s, "CHATHISTORY TARGETS timestamp=${TimeUtil.iso(since)} timestamp=${TimeUtil.iso(TimeUtil.now() + 86_400_000L)} ${historyLimit(s, 50)}")
                }
                IrcHooks.toast(t("%s: conectado").format(s.cfg.name))
            }
            5 -> for (tk in l.p.drop(1).dropLast(1)) {
                when {
                    tk.startsWith("PREFIX=(") -> s.prefixes = tk.substringAfter(')')
                    tk.startsWith("ELIST=") -> s.listMask = tk.substring(6).contains('M', true)
                    tk.startsWith("CHATHISTORY=") -> s.historyMax = tk.substring(12).toIntOrNull() ?: 0
                    tk.startsWith("soju.im/FILEHOST=") -> {
                        val url = isupportUnescape(tk.substring(17))
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            s.filehost = url
                            status(s, MsgType.INFO, t("Este servidor acepta subidas de imágenes (soju FILEHOST)"))
                        }
                    }
                }
            }
            4 -> {}
            2, 3, 265, 266, 375, 376 -> status(s, MsgType.SERVER, last)
            372 -> status(s, MsgType.SERVER, last.removePrefix("- ").ifEmpty { " " })
            332 -> if (l.p.size >= 3) {
                val c = getChan(s, l.p[1], ChanType.CHANNEL)
                c.topic = IrcText.strip(l.p[2])
                addMsg(s, c, MsgType.TOPIC, null, t("Tema: %s").format(l.p[2]))
            }
            333, 321 -> {}
            353 -> if (l.p.size >= 4) {
                val c = findChan(s, l.p[2]) ?: return
                if (!c.namesBuilding) { c.users.clear(); c.namesBuilding = true }
                for (n in l.p[3].split(' ')) if (n.isNotEmpty()) addUser(s, c, n)
            }
            366 -> if (l.p.size >= 2) findChan(s, l.p[1])?.let { c -> c.namesBuilding = false; sortUsers(s, c); dirty() }
            322 -> if (l.p.size >= 3 && s.listSkip == 0) {
                val users = l.p[2].toIntOrNull() ?: 0
                val e = ListedChan(l.p[1], users, IrcText.strip(l.p.getOrElse(3) { "" }))
                if (s.listed.size < MAX_LISTED) s.listed.add(e)
                else {    // keep the biggest channels
                    val mi = s.listed.indices.minByOrNull { s.listed[it].users }!!
                    if (s.listed[mi].users < users) s.listed[mi] = e
                }
                s.listing = 1
                dirty()
            }
            323 -> if (s.listSkip > 0) s.listSkip-- else { s.listing = 2; s.listed.sortByDescending { it.users }; dirty() }
            433 -> if (s.state != ServerState.ONLINE) {
                if (s.nick.length < 30) s.nick += "_"
                sendRaw(s, "NICK ${s.nick}")
                status(s, MsgType.ERROR, t("Nick en uso, probando %s").format(s.nick))
            } else addMsg(s, replyChan(s), MsgType.ERROR, null, t("Ese nick ya está en uso"))
            900 -> status(s, MsgType.SERVER, last)
            305 -> { s.away = false; addMsg(s, replyChan(s), MsgType.SERVER, null, t("Ya no estás ausente")) }
            306 -> { s.away = true; addMsg(s, replyChan(s), MsgType.SERVER, null, t("Ahora estás marcado como ausente")) }
            903 -> { s.saslOk = true; status(s, MsgType.SERVER, t("Autenticación SASL correcta")); sendRaw(s, "CAP END") }
            902, 904, 905, 906, 907 -> {
                status(s, MsgType.ERROR, t("SASL falló: %s").format(last))
                if (num == 904) status(s, MsgType.ERROR, t("Revisa la cuenta y la contraseña en Servidores. Se intentará con NickServ."))
                sendRaw(s, "CAP END")
            }
            else -> {
                // everything else: show the human-readable part
                val b = l.p.drop(1).joinToString(" ")
                val toView = (num in 300..399 && num != 353) || num in 400..599
                addMsg(s, if (toView) replyChan(s) else s.status, if (num >= 400) MsgType.ERROR else MsgType.SERVER, null, b)
            }
        }
    }

    /** ISUPPORT values escape some bytes as \xHH */
    private fun isupportUnescape(v: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < v.length) {
            if (v.startsWith("\\x", i) && i + 4 <= v.length) {
                val hex = v.substring(i + 2, i + 4).toIntOrNull(16)
                if (hex != null) { b.append(hex.toChar()); i += 4; continue }
            }
            b.append(v[i++])
        }
        return b.toString()
    }

    // ------------------------------------------------------------------
    // Connection threads
    // ------------------------------------------------------------------

    private fun resetForConnect(s: Server, cfg: ServerCfg) {
        s.state = ServerState.REGISTERING
        s.nick = cfg.nick
        s.prefixes = "~&@%+"
        s.joinAt = 0
        s.probe = false
        s.outq.clear()
        s.capsLs.setLength(0)
        s.capSasl = false; s.saslOk = false
        s.capTags = false; s.capBatch = false; s.capHistory = false
        s.historyMax = 0; s.listMask = false; s.filehost = ""
        s.listing = 0; s.listSkip = 0
        s.batches.clear()
        for (c in s.chans) { c.histReq = HistReq.NONE; c.histEnd = false }
    }

    private fun serverLoop(s: Server) {
        val backoff = intArrayOf(5, 10, 20, 30, 60)
        var attempt = 0
        while (true) {
            if (!s.wantConnect) {
                s.state = ServerState.OFF
                dirty()
                synchronized(s) { while (!s.wantConnect) (s as Object).wait() }
                continue
            }
            val cfg = locked {
                s.state = ServerState.CONNECTING
                status(s, MsgType.INFO, t("Conectando a %s:%d%s...").format(s.cfg.host, s.cfg.port, if (s.cfg.ssl) " (SSL)" else ""))
                s.cfg.copy()
            }
            val conn = try {
                Conn.open(cfg.host, cfg.port, cfg.ssl)
            } catch (e: Exception) {
                val wait = backoff[minOf(attempt, 4)]
                attempt++
                locked {
                    s.state = ServerState.WAITING
                    status(s, MsgType.ERROR, t("%s. Reintento en %d s").format(connError(cfg, e), wait))
                }
                waitOrCancel(s, wait)
                continue
            }
            attempt = 0
            s.conn = conn
            locked {
                resetForConnect(s, cfg)
                s.certOk = conn.certOk
                if (cfg.ssl && !conn.certOk) status(s, MsgType.INFO, t("Aviso: el certificado SSL del servidor no se pudo verificar"))
                sendRaw(s, "CAP LS 302")
                if (cfg.auth == ServerCfg.AUTH_PASS && cfg.password.isNotEmpty()) sendRaw(s, "PASS ${cfg.password}")
                sendRaw(s, "NICK ${cfg.nick}")
                val ident = cfg.user.ifEmpty { cfg.nick }.filter { it.isLetterOrDigit() && it.code < 128 }.lowercase().take(10)
                sendRaw(s, "USER ${ident.ifEmpty { "vita" }} 0 * :${cfg.realname.ifEmpty { "VitaIRC" }}")
            }
            var lastRx = TimeUtil.now()
            var pingSent = false
            try {
                while (s.wantConnect) {
                    val out = locked { ArrayList(s.outq).also { s.outq.clear() } }
                    for (o in out) conn.write(o)
                    val now = TimeUtil.now()
                    if (s.joinAt != 0L && now >= s.joinAt) { locked { s.joinAt = 0; doAutojoin(s) }; continue }
                    if (s.probe) {
                        s.probe = false
                        locked { sendRaw(s, "PING :probe") }
                        lastRx = now - 225_000      // expect an answer within ~15 s
                        pingSent = true
                        continue
                    }
                    val line = conn.readLine()
                    if (line != null) {
                        lastRx = TimeUtil.now()
                        pingSent = false
                        locked { handleLine(s, line) }
                        continue
                    }
                    if (now - lastRx > 120_000 && !pingSent) { locked { sendRaw(s, "PING :vitairc") }; pingSent = true }
                    if (now - lastRx > 240_000) break
                }
                // QUIT, if one was queued
                locked { ArrayList(s.outq).also { s.outq.clear() } }.forEach { try { conn.write(it) } catch (_: Exception) {} }
            } catch (_: Exception) {
            }
            conn.close()
            s.conn = null
            locked {
                s.state = if (s.wantConnect) ServerState.WAITING else ServerState.OFF
                s.joinAt = 0
                for (c in s.chans) { c.joined = false; c.users.clear() }
                status(s, MsgType.ERROR, t("Desconectado de %s").format(cfg.host))
            }
            if (s.wantConnect) waitOrCancel(s, 5)
        }
    }

    private fun connError(cfg: ServerCfg, e: Exception): String = when (e) {
        is java.net.UnknownHostException -> t("No se pudo resolver %s").format(cfg.host)
        is javax.net.ssl.SSLException -> t("Error TLS: %s (-0x%04x)").format(e.message ?: "", 0)
        else -> t("No se pudo conectar a %s:%d").format(cfg.host, cfg.port)
    }

    private fun waitOrCancel(s: Server, seconds: Int) {
        val until = TimeUtil.now() + seconds * 1000L
        while (TimeUtil.now() < until && s.wantConnect) Thread.sleep(200)
    }

    // ------------------------------------------------------------------
    // Public control
    // ------------------------------------------------------------------

    /** Rebuilds runtime servers from Config (keeps connections whose index persists). */
    fun syncFromConfig() = locked {
        while (servers.size > Config.servers.size) {
            val s = servers.removeAt(servers.size - 1)
            s.wantConnect = false
            s.conn?.close()
        }
        for ((i, cfg) in Config.servers.withIndex()) {
            val s = servers.getOrNull(i) ?: Server(i, cfg.copy()).also { servers.add(it) }
            if (s.chans.isEmpty()) s.chans.add(Chan(nextUid++, ChanType.STATUS, cfg.name))
            // the live connection keeps its snapshot until reconnect
            if (s.state == ServerState.OFF || s.state == ServerState.WAITING) s.cfg = cfg.copy()
            s.status.name = cfg.name
        }
        dirty()
    }

    fun connect(sidx: Int) = locked {
        val s = servers.getOrNull(sidx) ?: return@locked
        if (s.state == ServerState.OFF) s.cfg = Config.servers[sidx].copy()
        s.wantConnect = true
        synchronized(s) { (s as Object).notifyAll() }
        if (s.thread == null) {
            s.thread = Thread({ serverLoop(s) }, "irc-${s.idx}").apply { isDaemon = true; start() }
        }
        dirty()
    }

    fun disconnect(sidx: Int, reason: String? = null) = locked {
        val s = servers.getOrNull(sidx) ?: return@locked
        if (s.state == ServerState.ONLINE || s.state == ServerState.REGISTERING)
            sendRaw(s, "QUIT :${reason ?: "VitaIRC for Android"}")
        s.wantConnect = false
        dirty()
    }

    fun quitAll() {
        for (i in servers.indices) if (servers[i].wantConnect) disconnect(i)
    }

    /** After the network came back: verify that links are alive. */
    fun resumeCheck() = locked {
        for (s in servers) if (s.state == ServerState.ONLINE || s.state == ServerState.REGISTERING) s.probe = true
    }

    fun anyConnected() = servers.any { it.wantConnect }
}
