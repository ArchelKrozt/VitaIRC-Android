package io.github.archelkrozt.vitairc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.FmtSpan
import io.github.archelkrozt.vitairc.irc.IrcText
import io.github.archelkrozt.vitairc.irc.MsgType
import io.github.archelkrozt.vitairc.irc.TimeUtil
import io.github.archelkrozt.vitairc.irc.TransState
import io.github.archelkrozt.vitairc.net.LinkKind
import io.github.archelkrozt.vitairc.net.LinkPreview
import io.github.archelkrozt.vitairc.net.Previews
import java.time.temporal.ChronoUnit

fun prefixOf(m: MsgUi) = when (m.type) {
    MsgType.MSG -> "${m.nick} "
    MsgType.ACTION -> "* ${m.nick} "
    MsgType.NOTICE -> "-${m.nick.ifEmpty { "*" }}- "
    MsgType.JOIN -> "→ "
    MsgType.PART, MsgType.QUIT, MsgType.KICK -> "← "
    MsgType.ERROR -> "! "
    else -> "· "
}

/** "<image url> 🎨 <artist>": the author goes under the image instead of in the line. */
fun splitCaption(m: MsgUi): Pair<Int?, String> {
    if (!Config.linkPreviews) return null to ""
    val i = m.text.indexOf(" 🎨 ")
    val u = IrcText.urls(m.text, 1).firstOrNull()
    if (i < 0 || u == null || (Previews.kindOf(u) != LinkKind.IMAGE && Previews.cached(u)?.kind != LinkKind.IMAGE)) return null to ""
    return i to "🎨 " + m.text.substring(i + 4)
}

/** Message text with mIRC colours/bold/underline and tappable links (cut at `upTo`). */
fun formatText(m: MsgUi, base: Color, upTo: Int? = null): AnnotatedString = buildAnnotatedString {
    val text = if (upTo != null) m.text.take(upTo) else m.text
    append(text)
    if (Config.ircColors) {
        val spans = m.spans
        for ((i, sp) in spans.withIndex()) {
            val end = if (i + 1 < spans.size) spans[i + 1].start else text.length
            if (end <= sp.start) continue
            addStyle(styleOf(sp, base), sp.start, end)
        }
    }
    var from = 0
    for (u in IrcText.urls(text)) {
        val p = text.indexOf(u, from)
        if (p < 0) continue
        addLink(LinkAnnotation.Clickable(u, TextLinkStyles(SpanStyle(color = C.chan, textDecoration = TextDecoration.Underline))) {
            Actions.openLink(u)
        }, p, p + u.length)
        from = p + u.length
    }
}

private fun styleOf(sp: FmtSpan, base: Color): SpanStyle {
    var fg = if (sp.fg >= 0) C.mirc[sp.fg] else Color.Unspecified
    val bg = if (sp.bg >= 0) (if (sp.bg == 1) Color(0xFF101010) else C.mirc[sp.bg]) else Color.Unspecified
    if (sp.bg >= 0 && sp.fg < 0) fg = if (sp.bg in setOf(0, 8, 9, 11, 15)) Color.Black else base
    return SpanStyle(
        color = fg, background = bg,
        fontWeight = if (sp.bold) FontWeight.Bold else null,
        fontStyle = if (sp.italic) FontStyle.Italic else null,
        textDecoration = if (sp.underline) TextDecoration.Underline else null,
    )
}

/** "Today", "Yesterday" or "Thu 08/10/2026" */
fun dateLabel(ts: Long): String {
    val d = TimeUtil.localDate(ts)
    val diff = ChronoUnit.DAYS.between(d, java.time.LocalDate.now())
    val days = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    return when (diff) {
        0L -> t("Hoy")
        1L -> t("Ayer")
        else -> "%s %02d/%02d/%04d".format(t(days[d.dayOfWeek.value - 1]), d.dayOfMonth, d.monthValue, d.year)
    }
}

@Composable
fun DateSeparator(ts: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = C.line)
        Text(dateLabel(ts), color = C.dim, fontSize = fontSize(13f), modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = C.line)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageRow(m: MsgUi, flash: Boolean, onTap: () -> Unit, onReact: (String) -> Unit,
               onReply: (() -> Unit)? = null, onQuote: ((Long) -> Unit)? = null) {
    if (m.type == MsgType.DATE) { DateSeparator(m.ts); return }
    val bg = when {
        flash -> Color(0xFF2A3A5A)
        m.highlight -> C.hlBg
        else -> Color.Transparent
    }
    val chatty = m.type == MsgType.MSG || m.type == MsgType.ACTION
    val nickColor = when {
        m.pending -> C.faint
        chatty -> if (m.self) C.accent else C.nick(m.nick)
        else -> C.forType(m.type)
    }
    val textColor = if (m.pending) C.faint else C.forType(m.type)
    val cap = remember(m.id, m.text) { splitCaption(m) }
    val body = remember(m.id, m.text, m.spans, Config.ircColors, textColor) { formatText(m, textColor, cap.first) }
    // swipe right to reply
    var drag by remember(m.id) { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val dragAnim by animateFloatAsState(drag, label = "swipe")
    Box(Modifier.fillMaxWidth()) {
    if (dragAnim > 4f) Icon(Icons.AutoMirrored.Filled.Reply, null, tint = C.accent.copy(alpha = (dragAnim / 140f).coerceIn(0f, 1f)),
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
    Column(
        Modifier.fillMaxWidth().offset { IntOffset(dragAnim.roundToInt(), 0) }.background(bg)
            .pointerInput(m.id, onReply) {
                if (onReply == null || !m.chatty || m.pending) return@pointerInput
                detectHorizontalDragGestures(onDragEnd = {
                    if (drag > 120f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onReply() }
                    drag = 0f
                }, onDragCancel = { drag = 0f }) { ch, d -> if (drag + d > 0) { ch.consume(); drag = (drag + d * 0.6f).coerceAtMost(170f) } }
            }
            .combinedClickable(onClick = onTap, onLongClick = onTap)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        m.reply?.let { q -> QuoteView(q, Modifier.padding(start = (44 * Config.fontPct / 100).dp, bottom = 2.dp).clickable(enabled = q.id != 0L) { onQuote?.invoke(q.id) }) }
        Row {
            Text(if (m.pending) "···" else m.time, color = C.faint, fontSize = fontSize(12f),
                modifier = Modifier.width((44 * Config.fontPct / 100).dp).padding(top = 2.dp))
            Text(buildAnnotatedString {
                pushStyle(SpanStyle(color = nickColor, fontWeight = if (chatty) FontWeight.SemiBold else null))
                append(prefixOf(m))
                pop()
                append(body)
            }, color = textColor, fontSize = fontSize())
        }
        val indent = Modifier.padding(start = (44 * Config.fontPct / 100).dp)
        when {
            m.transState == TransState.PENDING -> Text(t("» traduciendo..."), color = C.faint, fontSize = fontSize(14f), modifier = indent)
            m.transState == TransState.DONE && m.trans != null ->
                Text("» " + m.trans, color = C.trans, fontSize = fontSize(14f), modifier = indent)
        }
        if (Config.linkPreviews && !m.pending && (m.type == MsgType.MSG || m.type == MsgType.ACTION || m.type == MsgType.NOTICE)) {
            IrcText.urls(m.text, 1).firstOrNull()?.let { LinkPreviewBlock(it, indent, cap.second) }
        }
        if (m.reacts.isNotEmpty()) {
            FlowRow(indent.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in m.reacts) {
                    Text("${r.emoji} ${r.count}", fontSize = fontSize(13f), color = if (r.mine) C.accent else C.text,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (r.mine) Color(0xFF3A3410) else C.sel)
                            .border(1.dp, if (r.mine) C.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = m.canReact) { onReact(r.emoji) }
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
    }
}

/** The message a reply answers, above it */
@Composable
fun QuoteView(q: QuoteUi, modifier: Modifier) {
    Row(modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).background(C.sel.copy(alpha = 0.7f)).height(IntrinsicSize.Min)
        .padding(vertical = 5.dp, horizontal = 8.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (q.nick.isEmpty()) C.faint else C.nick(q.nick)))
        Column(Modifier.padding(start = 8.dp)) {
            if (q.nick.isNotEmpty()) Text(q.nick, color = C.nick(q.nick), fontSize = fontSize(12f), fontWeight = FontWeight.SemiBold)
            Text(q.text, color = C.dim, fontSize = fontSize(13f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LinkPreviewBlock(url: String, modifier: Modifier, caption: String = "") {
    val p by produceState<LinkPreview?>(Previews.cached(url), url) { value = Previews.load(url) }
    val pv = p
    if (pv != null && !pv.ok) return
    val shape = RoundedCornerShape(6.dp)
    var broken by remember(url) { mutableStateOf(false) }
    if (pv != null && pv.kind == LinkKind.IMAGE && !broken) {
        Column(modifier.padding(top = 4.dp)) {
            AsyncImage(
                model = pv.media, contentDescription = null, contentScale = ContentScale.Fit,
                onError = { broken = true },
                modifier = Modifier.heightIn(min = 48.dp, max = 220.dp).widthIn(max = 320.dp).clip(shape)
                    .border(1.dp, C.line, shape).clickable { Actions.openLink(url) },
            )
            if (caption.isNotEmpty()) Text(caption, color = C.dim, fontSize = fontSize(13f), maxLines = 2, modifier = Modifier.padding(top = 3.dp))
        }
        return
    }
    if (pv != null && pv.kind == LinkKind.VIDEO && Previews.isLoop(url)) {
        // Imgur's GIFs are MP4s: they play in the chat, muted and looping
        LoopingVideo(pv.media, modifier.padding(top = 4.dp).widthIn(max = 320.dp).height(180.dp).clip(shape).clickable { Actions.openLink(url) })
        return
    }
    val video = pv != null && (pv.kind == LinkKind.VIDEO || pv.videoSite)
    Row(
        modifier.padding(top = 4.dp).widthIn(max = 520.dp).fillMaxWidth().clip(shape).background(C.panel)
            .clickable { if (broken) Actions.browser(url) else Actions.openLink(url) }.height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(72.dp).background(if (video) C.err else C.accent))
        if (pv != null && pv.image.isNotEmpty() && !broken) {
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(pv.image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(6.dp).size(width = 104.dp, height = 60.dp).clip(RoundedCornerShape(4.dp)))
                if (video) Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(Color(0xA0000000)).padding(3.dp))
            }
        } else Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            val (l1, l2, l3) = when {
                pv == null -> Triple(url, t("cargando vista previa..."), "")
                pv.kind == LinkKind.IMAGE -> Triple(url, t("formato no compatible: toca para abrir en el navegador"), pv.site)
                pv.kind == LinkKind.VIDEO -> Triple("▶ " + pv.title,
                    if (pv.size > 0) t("Video · %.1f MB · toca para reproducir").format(pv.size / 1048576.0) else t("Video · toca para reproducir"), pv.site)
                else -> Triple(pv.title.ifEmpty { url }, pv.desc, pv.site)
            }
            Text(l1, color = C.text, fontSize = fontSize(14f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            if (l2.isNotEmpty()) Text(l2, color = C.dim, fontSize = fontSize(13f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (l3.isNotEmpty()) Text(l3, color = C.faint, fontSize = fontSize(12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
