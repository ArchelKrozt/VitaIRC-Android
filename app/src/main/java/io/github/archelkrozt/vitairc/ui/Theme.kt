package io.github.archelkrozt.vitairc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.MsgType

/** Same palette as VitaIRC for PS Vita. */
object C {
    val bg = Color(0xFF0B0B10)
    val panel = Color(0xFF13131C)
    val side = Color(0xFF0E0E15)
    val line = Color(0xFF242432)
    val sel = Color(0xFF1D1D2B)
    val accent = Color(0xFFF5E642)
    val text = Color(0xFFD6D6E0)
    val dim = Color(0xFF707084)
    val faint = Color(0xFF484858)
    val chan = Color(0xFF7AAADE)
    val pm = Color(0xFFE8974C)
    val err = Color(0xFFFF5A6A)
    val ok = Color(0xFF5FC87F)
    val trans = Color(0xFF63C5DA)
    val notice = Color(0xFFC08AE8)
    val hlBg = Color(0xFF3A300C)
    val part = Color(0xFFB08060)

    private val nickColors = listOf(
        0xFFFF7B7B, 0xFF7BD88F, 0xFF7BB4FF, 0xFFFFC86B, 0xFFD08BFF, 0xFF6BE0D8,
        0xFFFF9BD2, 0xFFB8E06B, 0xFFFFA05A, 0xFF9AA8FF, 0xFF5AD0A0, 0xFFE8D070,
    ).map { Color(it) }

    /** Same hash as the Vita (FNV-1a on the lowercase nick, ASCII only), so a nick keeps its colour. */
    fun nick(n: String): Color {
        var h = 2166136261L
        for (ch in n.take(39)) {
            val c = if (ch in 'A'..'Z') ch + 32 else ch
            for (b in c.toString().toByteArray()) {
                h = h xor (b.toLong() and 0xFF)
                h = (h * 16777619L) and 0xFFFFFFFFL
            }
        }
        return nickColors[(h % nickColors.size).toInt()]
    }

    val mirc = listOf(
        0xFFFFFFFF, 0xFF8A8A8A, 0xFF6A7EFF, 0xFF3FBF3F, 0xFFFF5555, 0xFFC0703A, 0xFFC060E0, 0xFFFFA030,
        0xFFFFFF55, 0xFF70FF70, 0xFF30C0C0, 0xFF70FFFF, 0xFF7090FF, 0xFFFF70FF, 0xFF909090, 0xFFD0D0D0,
    ).map { Color(it) }

    fun forType(t: MsgType) = when (t) {
        MsgType.MSG, MsgType.ACTION -> text
        MsgType.NOTICE -> notice
        MsgType.JOIN -> ok
        MsgType.PART, MsgType.QUIT, MsgType.KICK -> part
        MsgType.ERROR -> err
        MsgType.TOPIC -> accent
        else -> dim
    }
}

/** Base text size, scaled by the "text size" setting. */
fun fontSize(base: Float = 15f) = (base * Config.fontPct / 100f).sp

@Composable
fun VitaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.Black, secondary = C.chan, background = C.bg, surface = C.panel,
            surfaceVariant = C.sel, onSurface = C.text, onBackground = C.text, onSurfaceVariant = C.dim,
            outline = C.line, error = C.err, surfaceContainer = C.panel, surfaceContainerHigh = C.sel,
            surfaceContainerHighest = C.sel, surfaceContainerLow = C.side,
        ),
        typography = Typography(bodyMedium = TextStyle(fontSize = fontSize()), bodyLarge = TextStyle(fontSize = fontSize(16f))),
        content = content,
    )
}
