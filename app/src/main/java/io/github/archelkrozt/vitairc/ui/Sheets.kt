package io.github.archelkrozt.vitairc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val color: Color = C.text,
    val enabled: Boolean = true,
    val header: Boolean = false,
    val onClick: () -> Unit = {},
)

/** Touch-sized list of options in a bottom sheet (the Vita's menus). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(title: String, items: List<MenuItem>, onDismiss: () -> Unit, top: @Composable (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = items.size > 8),
        containerColor = C.panel) {
        Column(Modifier.navigationBarsPadding()) {
            if (title.isNotEmpty()) Text(title, color = C.accent, fontSize = fontSize(15f), fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            top?.invoke()
            LazyColumn {
                items(items) { it ->
                    if (it.header) {
                        HorizontalDivider(color = C.line)
                        Text(it.label, color = C.faint, fontSize = fontSize(12f), modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
                    } else Row(
                        Modifier.fillMaxWidth().clickable(enabled = it.enabled) { onDismiss(); it.onClick() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (it.icon != null) Icon(it.icon, null, tint = if (it.enabled) it.color else C.faint, modifier = Modifier.padding(end = 16.dp).size(22.dp))
                        Text(it.label, color = if (it.enabled) it.color else C.faint, fontSize = fontSize(15f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Reactions offered in the message menu (same as the Vita). */
val REACT_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉", "👀", "✅")

@Composable
fun ReactRow(mine: Set<String>, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (e in REACT_EMOJIS) {
            Text(e, fontSize = fontSize(24f), modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (e in mine) Color(0xFF3A3410) else Color.Transparent)
                .clickable { onPick(e) }.padding(4.dp))
        }
    }
}
