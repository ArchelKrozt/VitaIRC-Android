package io.github.archelkrozt.vitairc

import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.data.History
import io.github.archelkrozt.vitairc.irc.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IrcTest {
    private lateinit var s: Server
    private lateinit var dir: File

    private fun feed(line: String) = Irc.locked { Irc.handleLine(s, line) }
    private fun outHas(needle: String) = s.outq.any { it.contains(needle) }
    private fun outClear() = s.outq.clear()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vitairc").toFile()
        Config.init(dir)
        Config.servers.clear()
        Config.servers.add(ServerCfg(name = "soju", host = "bnc.example", nick = "me", channels = ""))
        Config.sound = true
        Irc.servers.clear()
        Irc.syncFromConfig()
        s = Irc.servers[0]
        s.nick = "me"
        s.state = ServerState.REGISTERING
        Irc.viewSidx = 0
        Irc.viewUid = s.status.uid
    }

    @Test fun textHelpers() {
        val f = IrcText.parse("\u0002hi\u0002 \u000304,01red\u000f done")
        assertEquals("hi red done", f.text)
        assertTrue(f.spans.isNotEmpty())
        assertTrue(IrcText.containsWord("hola me", "me"))          // mention at the end
        assertFalse(IrcText.containsWord("meme", "me"))
        assertTrue(IrcText.globMatch("spam*", "SpamBot"))
        assertEquals(listOf("https://a.org/x.png"), IrcText.urls("see https://a.org/x.png."))
        assertEquals(1791415800000L, TimeUtil.parseIso("2026-10-07T23:30:00.000Z"))
        assertEquals("2026-10-07T23:30:00.000Z", TimeUtil.iso(1791415800000L))
        assertEquals(listOf("a b"), Irc.splitText("a b"))
        val long = "x".repeat(900)
        assertTrue(Irc.splitText(long).all { it.toByteArray().size <= 380 })
        assertEquals("a;b c", Line.unescape("a\\:b\\sc"))
        assertEquals("x\\sy\\:z", Line.escape("x y;z"))
    }

    @Test fun capsHistoryReactionsQueue() {
        feed(":srv CAP * LS :server-time batch message-tags draft/chathistory sasl multi-prefix")
        assertTrue(outHas("CAP REQ :server-time multi-prefix batch message-tags draft/chathistory"))
        outClear()
        feed(":srv CAP me ACK :server-time multi-prefix batch message-tags draft/chathistory")
        assertTrue(s.capTags && s.capBatch && s.capHistory)
        assertTrue(outHas("CAP END"))
        outClear()
        feed(":srv 005 me CHATHISTORY=1000 ELIST=CMNTU soju.im/FILEHOST=https://bnc.example/uploads\\x3Dx :are supported")
        assertEquals(1000, s.historyMax)
        assertTrue(s.listMask)
        assertEquals("https://bnc.example/uploads=x", s.filehost)

        // offline queue
        s.state = ServerState.WAITING
        val c = Irc.locked { Irc.getChan(s, "#vita", ChanType.CHANNEL) }
        Irc.userInput(0, c.uid, "hola desde el metro")
        Irc.userInput(0, c.uid, "/me saluda")
        assertEquals(2, s.offq.size)
        assertTrue(c.msgs.last().pending)
        s.state = ServerState.REGISTERING
        feed(":srv 001 me :Welcome")
        assertEquals(ServerState.ONLINE, s.state)
        assertTrue(outHas("CHATHISTORY TARGETS timestamp="))
        outClear()
        feed(":me!u@h JOIN #vita")
        assertTrue(outHas("CHATHISTORY LATEST #vita * 100"))
        assertTrue(outHas("PRIVMSG #vita :hola desde el metro"))
        assertTrue(outHas("PRIVMSG #vita :\u0001ACTION saluda\u0001"))
        assertEquals(0, s.offq.size)
        assertTrue(c.msgs.none { it.pending })
        outClear()

        // history batch, out of order, with a duplicate and a CTCP that must not be answered
        feed(":srv BATCH +h1 chathistory #vita")
        feed("@batch=h1;time=2026-10-07T23:50:00.000Z;msgid=a2 :bob!u@h PRIVMSG #vita :segundo")
        feed("@batch=h1;time=2026-10-07T12:00:00.000Z;msgid=a1 :ann!u@h PRIVMSG #vita :primero me")
        feed("@batch=h1;time=2026-10-08T15:00:00.000Z;msgid=a3 :me!u@h PRIVMSG #vita :mío")
        feed("@batch=h1;time=2026-10-08T15:00:00.000Z;msgid=a3 :me!u@h PRIVMSG #vita :mío")
        feed("@batch=h1;time=2026-10-08T15:01:00.000Z :ann!u@h PRIVMSG me :\u0001VERSION\u0001")
        assertTrue(s.outq.isEmpty())
        feed(":srv BATCH -h1")
        assertEquals(HistReq.NONE, c.histReq)
        val real = c.msgs.filter { it.type != MsgType.DATE }
        assertTrue(real.zipWithNext().all { it.first.ts <= it.second.ts })
        assertEquals(c.msgs.size, c.msgs.map { it.id }.toSet().size)
        assertTrue(c.findMsgId("a1")!!.highlight)
        assertTrue(c.findMsgId("a3")!!.self)
        assertEquals(1, c.mention)
        assertEquals(MsgType.DATE, c.msgs.first().type)

        // same batch again: nothing added
        val n = c.msgs.size
        feed(":srv BATCH +h2 chathistory #vita")
        feed("@batch=h2;time=2026-10-07T12:00:00.000Z;msgid=a1 :ann!u@h PRIVMSG #vita :primero me")
        feed(":srv BATCH -h2")
        assertEquals(n, c.msgs.size)

        // older page
        assertTrue(Irc.locked { Irc.requestOlder(s, c) })
        assertTrue(outHas("CHATHISTORY BEFORE #vita timestamp=2026-10-07T12:00:00.000Z 50"))
        assertFalse(Irc.locked { Irc.requestOlder(s, c) })
        outClear()
        val mentions = c.mention
        feed(":srv BATCH +h3 chathistory #vita")
        feed("@batch=h3;time=2026-10-05T10:00:00.000Z;msgid=z1 :old!u@h PRIVMSG #vita :muy viejo me")
        feed(":srv BATCH -h3")
        assertEquals("z1", c.msgs[1].msgid)
        assertEquals(mentions, c.mention)
        Irc.locked { Irc.requestOlder(s, c) }
        feed(":srv BATCH +h4 chathistory #vita")
        feed(":srv BATCH -h4")
        assertTrue(c.histEnd)

        // TARGETS: queries yes, services no
        outClear()
        feed(":srv BATCH +t1 draft/chathistory-targets")
        feed("@batch=t1 :srv CHATHISTORY TARGETS bob 2026-10-08T15:00:00.000Z")
        feed("@batch=t1 :srv CHATHISTORY TARGETS NickServ 2026-10-08T15:00:00.000Z")
        feed(":srv BATCH -t1")
        assertEquals(ChanType.QUERY, Irc.findChan(s, "bob")!!.type)
        assertNull(Irc.findChan(s, "NickServ"))
        assertTrue(outHas("CHATHISTORY LATEST bob * 100"))

        // my message from another client goes to the query
        feed("@time=2026-10-08T16:00:00.000Z;msgid=q1 :me!u@h PRIVMSG carol :hola carol")
        assertTrue(Irc.findChan(s, "carol")!!.msgs.last().self)
        // STATUSMSG
        feed(":op!u@h PRIVMSG @#vita :solo ops")
        assertEquals("solo ops", c.msgs.last().text)

        // reactions
        val m = c.findMsgId("a2")!!
        feed("@+draft/react=👍;+draft/reply=a2 :ann!u@h TAGMSG #vita")
        feed("@+draft/react=👍;+draft/reply=a2 :ann!u@h TAGMSG #vita")
        feed("@+draft/react=👍;+draft/reply=a2 :bob!u@h TAGMSG #vita")
        feed("@+draft/react=❤️;+draft/reply=a2 :bob!u@h TAGMSG #vita")
        assertEquals(2, m.reacts!!.size)
        assertEquals(2, m.reacts!![0].count)
        feed("@+draft/unreact=👍;+draft/reply=a2 :ann!u@h TAGMSG #vita")
        assertEquals(1, m.reacts!![0].count)
        outClear()
        Irc.locked { Irc.sendReact(s, c, m, "🔥") }
        assertTrue(outHas("@+draft/react=🔥;+draft/reply=a2 TAGMSG #vita"))
        Irc.locked { Irc.sendReact(s, c, m, "🔥") }
        assertTrue(outHas("@+draft/unreact=🔥;+draft/reply=a2 TAGMSG #vita"))

        // mute: counted, no notification
        var notified = 0
        IrcHooks.notify = { _, _, _ -> notified++ }
        c.muted = true
        feed("@time=2026-10-08T18:00:00.000Z :ann!u@h PRIVMSG #vita :oye me")
        assertEquals(0, notified)
        c.muted = false
        feed("@time=2026-10-08T18:01:00.000Z :ann!u@h PRIVMSG #vita :oye me otra vez")
        assertEquals(1, notified)
        IrcHooks.notify = { _, _, _ -> }

        // /list with a mask
        outClear()
        Irc.userInput(0, s.status.uid, "/list *vita*")
        assertTrue(outHas("LIST *vita*\r\n"))
        feed(":srv 323 me :End of /LIST")
        // a search sent while the full list still arrives: the old rest is dropped
        Irc.userInput(0, s.status.uid, "/list")
        feed(":srv 322 me #big 900 :big channel")
        Irc.userInput(0, s.status.uid, "/list *vita*")
        feed(":srv 322 me ##uk 11 :an invitation")
        feed(":srv 323 me :End of /LIST")
        assertEquals(1, s.listing)
        feed(":srv 322 me #vitasdk 8 :Vita homebrew")
        feed(":srv 323 me :End of /LIST")
        assertEquals(2, s.listing)
        assertEquals(listOf("#vitasdk"), s.listed.map { it.name })

        // the ring keeps MAX_MSGS
        repeat(Irc.MAX_MSGS + 20) { feed(":ann!u@h PRIVMSG #vita :spam $it") }
        assertEquals(Irc.MAX_MSGS, c.msgs.size)
    }

    @Test fun selfMessageKeepsWholeText() {
        s.state = ServerState.ONLINE
        val url = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3f/Fronalpstock_big.jpg/640px-Fronalpstock_big.jpg"
        Irc.userInput(0, s.status.uid, "/msg me look $url")
        assertTrue(outHas("PRIVMSG me :look $url"))
        // the server delivers my message to myself: shown once
        feed(":me!u@h PRIVMSG me :look $url")
        val q = Irc.findChan(s, "me")!!
        assertEquals(1, q.msgs.count { it.text == "look $url" })
    }

    @Test fun historyRoundTrip() {
        s.state = ServerState.ONLINE
        val logDir = File(dir, "logs/bnc.example").apply { mkdirs() }
        File(logDir, "#vita.log").writeText("2026-10-06\t21:15\t0\t0\tolduser\tlinea de la version 1.0\n")
        val c = Irc.locked { Irc.getChan(s, "#vita", ChanType.CHANNEL) }
        val old = c.msgs.first { it.nick == "olduser" }
        assertEquals("21:15", old.time)
        feed("@time=2026-10-07T23:30:00.000Z;msgid=m-1 :ann!u@h PRIVMSG #vita :mensaje con msgid")
        Irc.locked { Irc.closeChan(s, c) }
        val lastLine = File(logDir, "#vita.log").readLines().last()
        assertTrue(lastLine.endsWith("\tann\tmensaje con msgid\t1791415800000\tm-1"))
        val c2 = Irc.locked { Irc.getChan(s, "#vita", ChanType.CHANNEL) }
        assertEquals(1791415800000L, c2.findMsgId("m-1")!!.ts)
        assertEquals(MsgType.INFO, c2.msgs.last().type)
        c2.muted = true
        History.saveSession()
        assertTrue(File(dir, "session.txt").readText().contains("#vita\t\t1"))
    }
}
