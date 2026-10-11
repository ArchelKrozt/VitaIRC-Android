package io.github.archelkrozt.vitairc.irc

enum class MsgType { MSG, ACTION, NOTICE, JOIN, PART, QUIT, KICK, TOPIC, SERVER, ERROR, INFO, NICK, MODE, DATE }

enum class ChanType { STATUS, CHANNEL, QUERY }

enum class TransState { NONE, PENDING, DONE, SKIP }

enum class ServerState { OFF, CONNECTING, REGISTERING, ONLINE, WAITING }

/** IRCv3 chathistory request in flight for a window */
enum class HistReq { NONE, LATEST, BEFORE }

/** A reaction (IRCv3 +draft/react) shown under a message. */
class React(val emoji: String) {
    var count = 0
    var mine = false
    val who = HashSet<String>()         // lowercase nicks that reacted
}

class Msg(
    var id: Long,
    val type: MsgType,
    val nick: String,
    val text: String,                   // without mIRC control codes
    val spans: List<FmtSpan>,
    val self: Boolean,
    var ts: Long,                       // ms since 1970 (UTC)
    val msgid: String?,
) {
    var time: String = TimeUtil.hhmm(ts)
    var highlight = false
    var pending = false                 // typed offline, waiting to be sent
    var trans: String? = null
    var transState = TransState.NONE
    var reacts: MutableList<React>? = null
    var replyTo: String? = null         // msgid this message answers (IRCv3 +draft/reply)

    val chatty get() = type == MsgType.MSG || type == MsgType.ACTION || type == MsgType.NOTICE
}

class ChanUser(var nick: String, var prefix: Char = 0.toChar(), var away: Boolean = false)

class Chan(val uid: Long, val type: ChanType, var name: String) {
    val msgs = ArrayList<Msg>()         // oldest first, at most Irc.MAX_MSGS
    var nextId = 0L
    var topic = ""
    var joined = false
    var unread = 0
    var mention = 0
    val users = ArrayList<ChanUser>()
    var namesBuilding = false
    var transIn = false
    var transOut = false
    var transLang = ""                  // per-channel translation language ("" = global)
    var muted = false
    var lastYmd = 0
    var histReq = HistReq.NONE
    var histReqAt = 0L
    var histSince = 0L
    var histEnd = false
    var draft = ""
    var replyOut: String? = null        // reply of a message waiting for its translation
    var log: java.io.Writer? = null

    fun findMsg(id: Long): Msg? = msgs.firstOrNull { it.id == id }
    fun findMsgId(msgid: String?): Msg? = if (msgid.isNullOrEmpty()) null else msgs.lastOrNull { it.msgid == msgid }
}

class ListedChan(val name: String, val users: Int, val topic: String)

class OfflineMsg(val chanUid: Long, val msgId: Long, val action: Boolean, val text: String)

data class ServerCfg(
    var name: String = "Libera.Chat",
    var host: String = "irc.libera.chat",
    var port: Int = 6697,
    var ssl: Boolean = true,
    var nick: String = "",
    var user: String = "",              // account / auth username (defaults to nick)
    var realname: String = "VitaIRC user",
    var password: String = "",
    var auth: Int = AUTH_AUTO,
    var channels: String = "#vitasdk",
    var autoconnect: Boolean = true,
) {
    companion object {
        const val AUTH_AUTO = 0
        const val AUTH_NICKSERV = 1
        const val AUTH_SASL = 2
        const val AUTH_PASS = 3
        fun authName(a: Int) = when (a) {
            AUTH_NICKSERV -> "NickServ IDENTIFY"
            AUTH_SASL -> "SASL PLAIN"
            AUTH_PASS -> "Server PASS"
            else -> null
        }
    }
}
