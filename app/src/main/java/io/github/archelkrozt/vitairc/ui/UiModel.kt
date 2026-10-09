package io.github.archelkrozt.vitairc.ui

import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.ChanType
import io.github.archelkrozt.vitairc.irc.FmtSpan
import io.github.archelkrozt.vitairc.irc.HistReq
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.MsgType
import io.github.archelkrozt.vitairc.irc.ServerState
import io.github.archelkrozt.vitairc.irc.TransState
import io.github.archelkrozt.vitairc.net.AppScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ReactUi(val emoji: String, val count: Int, val mine: Boolean)

data class MsgUi(
    val id: Long, val type: MsgType, val nick: String, val text: String, val spans: List<FmtSpan>,
    val self: Boolean, val time: String, val ts: Long, val highlight: Boolean, val pending: Boolean,
    val trans: String?, val transState: TransState, val reacts: List<ReactUi>, val canReact: Boolean,
)

data class WinUi(
    val sidx: Int, val uid: Long, val type: ChanType, val name: String, val unread: Int, val mention: Int,
    val joined: Boolean, val muted: Boolean, val serverState: ServerState,
)

data class UserUi(val nick: String, val prefix: Char, val away: Boolean)

data class ChanUi(
    val sidx: Int, val uid: Long, val type: ChanType, val name: String, val topic: String, val users: List<UserUi>,
    val joined: Boolean, val transIn: Boolean, val transOut: Boolean, val transLang: String, val muted: Boolean,
    val loadingOlder: Boolean, val msgs: List<MsgUi>, val draft: String,
    val serverName: String, val host: String, val nick: String, val state: ServerState, val away: Boolean,
    val myPrefix: Char, val canHistory: Boolean, val filehost: Boolean, val listMask: Boolean,
)

data class Snapshot(val windows: List<WinUi> = emptyList(), val chan: ChanUi? = null)

/** Immutable copies of the IRC state for the UI, rebuilt after changes (at most ~20 times a second). */
object UiModel {
    val snapshot = MutableStateFlow(Snapshot())

    fun start() {
        AppScope.launch {
            // a StateFlow only keeps the newest value, so this redraws at most every 50 ms
            Irc.revision.collect {
                snapshot.value = build()
                delay(50)
            }
        }
    }

    fun build(): Snapshot = Irc.locked {
        val wins = ArrayList<WinUi>()
        for ((i, s) in Irc.servers.withIndex()) {
            if (i >= Config.servers.size) continue
            for (c in s.chans) wins.add(WinUi(i, c.uid, c.type, if (c.type == ChanType.STATUS) Config.servers[i].name else c.name,
                c.unread, c.mention, c.joined, c.muted, s.state))
        }
        val s = Irc.servers.getOrNull(Irc.viewSidx)
        val c = s?.let { Irc.findChanUid(it, Irc.viewUid) }
        val chan = if (s != null && c != null) ChanUi(
            s.idx, c.uid, c.type, c.name, c.topic, c.users.map { UserUi(it.nick, it.prefix, it.away) },
            c.joined, c.transIn, c.transOut, c.transLang, c.muted, c.histReq == HistReq.BEFORE,
            c.msgs.map { m ->
                MsgUi(m.id, m.type, m.nick, m.text, m.spans, m.self, m.time, m.ts, m.highlight, m.pending, m.trans, m.transState,
                    m.reacts?.map { ReactUi(it.emoji, it.count, it.mine) } ?: emptyList(), Irc.canReact(s, m))
            },
            c.draft, Config.servers.getOrNull(s.idx)?.name ?: s.cfg.name, s.cfg.host, s.nick.ifEmpty { s.cfg.nick }, s.state, s.away,
            Irc.myPrefix(s, c), s.capHistory, s.filehost.isNotEmpty(), s.listMask,
        ) else null
        Snapshot(wins, chan)
    }
}
