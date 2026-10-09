package io.github.archelkrozt.vitairc.data

import io.github.archelkrozt.vitairc.irc.Chan
import io.github.archelkrozt.vitairc.irc.ChanType
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.Msg
import io.github.archelkrozt.vitairc.irc.MsgType
import io.github.archelkrozt.vitairc.irc.Server
import io.github.archelkrozt.vitairc.irc.TimeUtil
import io.github.archelkrozt.vitairc.L.t
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Chat logs and the open-window session, in the same files as VitaIRC for PS Vita:
 * logs/<server>/<channel>.log with "date\ttime\ttype\tself\tnick\ttext\tms\tmsgid" lines.
 */
object History {
    const val LOAD_LINES = 150
    private const val TAIL_BYTES = 48 * 1024
    private const val MAX_LOG_BYTES = 512 * 1024

    /** Set while a log is replayed into a window: no logging, no unread counters, no translations. */
    @Volatile var loading = false
    private var restoring = false

    private val types = MsgType.entries

    private fun safeName(s: String): String {
        val b = StringBuilder()
        for (c in s) b.append(if (c.isLetterOrDigit() && c.code < 128 || c == '#' || c == '-' || c == '_' || c == '.') c.lowercaseChar() else '_')
        return if (b.isEmpty()) "_" else b.toString()
    }

    private fun serverDir(s: Server) = File(Config.dir, "logs/" + safeName(s.cfg.host.ifEmpty { "server" }))
    private fun logFile(s: Server, c: Chan) = File(serverDir(s), safeName(c.name) + ".log")

    private fun loggable(t: MsgType) = t == MsgType.MSG || t == MsgType.ACTION || t == MsgType.NOTICE ||
        t == MsgType.TOPIC || t == MsgType.KICK || t == MsgType.NICK

    fun log(s: Server, c: Chan, m: Msg) {
        if (loading || c.type == ChanType.STATUS || !loggable(m.type)) return
        try {
            val w = c.log ?: run {
                val f = logFile(s, c)
                f.parentFile?.mkdirs()
                OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8).also { c.log = it }
            }
            val d = TimeUtil.localDate(m.ts)
            val text = m.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            w.write("%04d-%02d-%02d\t%s\t%d\t%d\t%s\t%s\t%d\t%s\n".format(
                d.year, d.monthValue, d.dayOfMonth, m.time, types.indexOf(m.type), if (m.self) 1 else 0,
                m.nick, text, m.ts, m.msgid ?: ""))
            w.flush()
        } catch (_: Exception) {
        }
    }

    fun close(c: Chan) {
        try { c.log?.close() } catch (_: Exception) {}
        c.log = null
    }

    /** Keeps log files bounded: rewrites only the newest half when too big. */
    private fun trim(f: File) {
        val size = f.length()
        if (size <= MAX_LOG_BYTES) return
        try {
            val keep = MAX_LOG_BYTES / 2
            val buf = ByteArray(keep)
            RandomAccessFile(f, "r").use { it.seek(size - keep); it.readFully(buf) }
            val nl = buf.indexOf('\n'.code.toByte())
            f.writeBytes(buf.copyOfRange(if (nl >= 0) nl + 1 else 0, buf.size))
        } catch (_: Exception) {
        }
    }

    fun load(s: Server, c: Chan) {
        if (c.type == ChanType.STATUS) return
        val f = logFile(s, c)
        if (!f.exists()) return
        val bytes = try {
            RandomAccessFile(f, "r").use { r ->
                val size = r.length()
                val from = if (size > TAIL_BYTES) size - TAIL_BYTES else 0
                val b = ByteArray((size - from).toInt())
                r.seek(from)
                r.readFully(b)
                if (from > 0) { val nl = b.indexOf('\n'.code.toByte()); b.copyOfRange(nl + 1, b.size) } else b
            }
        } catch (e: Exception) { return }
        trim(f)
        val lines = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.takeLast(LOAD_LINES)
        var lastDate = ""
        var lastTime = ""
        var lastTs = 0L
        loading = true
        try {
            for (line in lines) {
                val f_ = line.split('\t', limit = 8)
                if (f_.size < 6) continue
                var ts = if (f_.size > 6) f_[6].toLongOrNull() ?: 0L else 0L
                if (ts <= 0) {   // logs written before 1.1 keep only the date and the time
                    ts = try {
                        val d = LocalDate.parse(f_[0])
                        val hm = f_[1].split(':')
                        LocalDateTime.of(d.year, d.month, d.dayOfMonth, hm[0].toInt(), hm[1].toInt())
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (e: Exception) { continue }
                }
                val type = types.getOrNull(f_[2].toIntOrNull() ?: -1) ?: continue
                Irc.addMsgAt(s, c, type, f_[4], f_[5], f_[3] == "1", ts, f_.getOrNull(7)?.ifEmpty { null })
                lastDate = f_[0]
                lastTime = f_[1]
                lastTs = ts
            }
            if (lastTs > 0) {
                Irc.addMsgAt(s, c, MsgType.INFO, "",
                    t("--- historial hasta %.2s/%.2s %s ---").format(lastDate.substring(8), lastDate.substring(5, 7), lastTime),
                    false, lastTs, null)
            }
        } finally {
            loading = false
        }
        c.unread = 0
        c.mention = 0
    }

    private val sessionFile get() = File(Config.dir, "session.txt")

    fun saveSession() {
        if (restoring) return
        val sb = StringBuilder()
        for ((i, s) in Irc.servers.withIndex()) {
            if (i >= Config.servers.size) continue
            for (c in s.chans) {
                if (c.type == ChanType.STATUS) continue
                sb.append("${Config.servers[i].host}\t${if (c.type == ChanType.CHANNEL) 1 else 2}\t${if (c.transIn) 1 else 0}\t" +
                    "${if (c.transOut) 1 else 0}\t${c.name}\t${c.transLang}\t${if (c.muted) 1 else 0}\n")
            }
        }
        try { sessionFile.writeText(sb.toString()) } catch (_: Exception) {}
    }

    fun restoreSession() {
        if (!sessionFile.exists()) return
        restoring = true
        try {
            sessionFile.forEachLine { line ->
                val f = line.split('\t')
                if (f.size < 5 || f[4].isEmpty()) return@forEachLine
                val sidx = Config.servers.indexOfFirst { it.host.equals(f[0], true) }
                if (sidx < 0 || sidx >= Irc.servers.size) return@forEachLine
                val type = when (f[1]) { "1" -> ChanType.CHANNEL; "2" -> ChanType.QUERY; else -> return@forEachLine }
                val c = Irc.getChan(Irc.servers[sidx], f[4], type)
                c.transIn = f[2] == "1"
                c.transOut = f[3] == "1"
                if (f.size > 5) c.transLang = f[5]
                if (f.size > 6) c.muted = f[6] == "1"
            }
        } finally {
            restoring = false
        }
    }
}
