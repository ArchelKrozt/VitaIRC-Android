package io.github.archelkrozt.vitairc.irc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formatting run inside a message whose control codes were removed. */
data class FmtSpan(
    val start: Int,
    val fg: Int = -1,          // mIRC colour index, -1 = default
    val bg: Int = -1,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val italic: Boolean = false,
)

/** Text without mIRC control codes plus where the formatting changes. */
class Formatted(val text: String, val spans: List<FmtSpan>)

object IrcText {
    /** Strips colours/bold/underline/italic/reverse codes and records them as spans. */
    fun parse(raw: String): Formatted {
        val out = StringBuilder(raw.length)
        val spans = ArrayList<FmtSpan>()
        var cur = FmtSpan(0)
        var changed = false
        var i = 0
        fun digits(max: Int): Int {
            var v = -1
            var n = 0
            while (n < max && i < raw.length && raw[i].isDigit()) {
                v = (if (v < 0) 0 else v) * 10 + (raw[i] - '0')
                i++
                n++
            }
            return v
        }
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\u0003' -> {
                    i++
                    val fg = digits(2)
                    if (fg >= 0) {
                        var bg = cur.bg
                        if (i + 1 < raw.length && raw[i] == ',' && raw[i + 1].isDigit()) {
                            i++
                            bg = digits(2).let { if (it in 0..15) it else -1 }
                        }
                        cur = cur.copy(fg = if (fg in 0..15) fg else -1, bg = bg)
                    } else {
                        cur = cur.copy(fg = -1, bg = -1)
                    }
                    changed = true
                }
                c == '\u0004' -> {            // hex colours: dropped
                    i++
                    var n = 0
                    while (n < 6 && i < raw.length && raw[i].isLetterOrDigit()) { i++; n++ }
                    if (i + 1 < raw.length && raw[i] == ',') {
                        i++
                        n = 0
                        while (n < 6 && i < raw.length && raw[i].isLetterOrDigit()) { i++; n++ }
                    }
                }
                c == '\u0002' -> { cur = cur.copy(bold = !cur.bold); changed = true; i++ }
                c == '\u001f' -> { cur = cur.copy(underline = !cur.underline); changed = true; i++ }
                c == '\u001d' -> { cur = cur.copy(italic = !cur.italic); changed = true; i++ }
                c == '\u000f' -> { cur = FmtSpan(0); changed = true; i++ }
                c == '\u0016' || c == '\u001e' || c == '\u0011' || (c < ' ' && c != '\t') -> i++
                else -> {
                    if (changed) {
                        val s = cur.copy(start = out.length)
                        if (spans.isNotEmpty() && spans.last().start == s.start) spans[spans.size - 1] = s else spans.add(s)
                        changed = false
                    }
                    out.append(c)
                    i++
                }
            }
        }
        if (spans.size == 1 && spans[0].let { it.fg < 0 && it.bg < 0 && !it.bold && !it.underline && !it.italic })
            spans.clear()
        return Formatted(out.toString(), spans)
    }

    fun strip(raw: String): String = parse(raw).text

    private fun isNickChar(c: Char) = c.isLetterOrDigit() || "[]\\`_^{|}-".indexOf(c) >= 0

    /** Case-insensitive whole-word match (nicks and highlight words). */
    fun containsWord(hay: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val p = hay.indexOf(needle, from, ignoreCase = true)
            if (p < 0) return false
            val left = p == 0 || !isNickChar(hay[p - 1])
            val end = p + needle.length
            val right = end >= hay.length || !isNickChar(hay[end])
            if (left && right) return true
            from = p + 1
        }
    }

    /** Case-insensitive glob with * and ? */
    fun globMatch(pattern: String, s: String): Boolean {
        fun m(p: Int, i: Int): Boolean {
            var pi = p
            var si = i
            while (pi < pattern.length) {
                val pc = pattern[pi]
                if (pc == '*') {
                    while (pi < pattern.length && pattern[pi] == '*') pi++
                    if (pi == pattern.length) return true
                    for (k in si..s.length) if (m(pi, k)) return true
                    return false
                }
                if (si >= s.length) return false
                if (pc != '?' && pc.lowercaseChar() != s[si].lowercaseChar()) return false
                pi++
                si++
            }
            return si == s.length
        }
        return m(0, 0)
    }

    /** URLs in a message (http/https), trailing punctuation removed, no duplicates. */
    fun urls(text: String, max: Int = 12): List<String> {
        val out = ArrayList<String>()
        var p = 0
        while (out.size < max) {
            p = text.indexOf("http", p)
            if (p < 0) break
            if (!text.startsWith("http://", p) && !text.startsWith("https://", p)) { p += 4; continue }
            var e = p
            while (e < text.length && " \t\"'<>".indexOf(text[e]) < 0) e++
            while (e > p && ".,;:!?)]".indexOf(text[e - 1]) >= 0) e--
            if (e - p in 11..298) {
                val u = text.substring(p, e)
                if (u !in out) out.add(u)
            }
            p = if (e > p) e else p + 4
        }
        return out
    }

    fun shouldSkipTranslation(t: String): Boolean {
        val s = t.trimStart(' ')
        if (s.length < 2) return true
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return true
        if (s[0] == '/' || s[0] == '!') return true
        return s.none { it.isLetter() || it.code >= 0x80 }
    }
}

object TimeUtil {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    fun now(): Long = System.currentTimeMillis()
    fun parseIso(iso: String): Long? = try { Instant.parse(iso).toEpochMilli() } catch (e: Exception) {
        // "2026-10-08T21:30Z" (no seconds)
        try { Instant.parse(iso.replace(Regex("T(\\d\\d):(\\d\\d)Z$"), "T$1:$2:00Z")).toEpochMilli() } catch (e2: Exception) { null }
    }
    fun iso(ms: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(ms))
    fun hhmm(ms: Long): String = hhmm.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    fun ymd(ms: Long): Int = localDate(ms).let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
}
