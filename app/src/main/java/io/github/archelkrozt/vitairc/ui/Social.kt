package io.github.archelkrozt.vitairc.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.MyProfile
import io.github.archelkrozt.vitairc.irc.Profile
import io.github.archelkrozt.vitairc.irc.ProfileFav
import io.github.archelkrozt.vitairc.irc.WhoisInfo
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.AppScope
import io.github.archelkrozt.vitairc.net.BPost
import io.github.archelkrozt.vitairc.net.Booru
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Received profiles and WHOIS cards (filled from IrcHooks on the main thread). */
object Social {
    val profiles = mutableStateMapOf<String, Profile>()
    val failed = mutableStateMapOf<String, Boolean>()
    val whois = mutableStateMapOf<String, WhoisInfo>()
    var card by mutableStateOf<Pair<Int, String>?>(null)        // user card on screen (sidx, nick)
    var miniVideo by mutableStateOf<String?>(null)              // floating player over the chat
    var reply by mutableStateOf<ReplyTarget?>(null)              // message answered from the input bar
}

/** Key of a user on a server, for profiles and WHOIS cards */
fun userKey(sidx: Int, nick: String) = "$sidx/${nick.lowercase()}"

data class ReplyTarget(val sidx: Int, val uid: Long, val msgid: String?, val nick: String, val text: String)

/** Starts answering a message from the input bar. */
fun startReply(c: ChanUi, m: MsgUi) {
    if (!m.chatty || m.nick.isEmpty()) return
    Social.reply = ReplyTarget(c.sidx, c.uid, m.msgid, m.nick, m.text)
}

// ---------------------------------------------------------------- avatars

@Composable
fun AvatarView(data: ByteArray?, nick: String, size: Int = 72) {
    val bmp = remember(data) { data?.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.size(size.dp).clip(CircleShape).border(2.dp, C.nick(nick).copy(alpha = 0.6f), CircleShape)
        .background(C.nick(nick).copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(nick.take(1).uppercase(), color = C.nick(nick), fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Square center crop, scaled to `side` px, as JPEG (the 64 px one stays small enough for IRC). */
fun avatarJpeg(src: Bitmap, side: Int, maxBytes: Int): ByteArray {
    val s = minOf(src.width, src.height)
    val crop = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
    val scaled = Bitmap.createScaledBitmap(crop, side, side, true)
    var q = 80
    var out: ByteArray
    do {
        out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
        q -= 10
    } while (out.size > maxBytes && q >= 20)
    return out
}

// ---------------------------------------------------------------- user card

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserCardSheet(sidx: Int, nick: String, chan: ChanUi?, close: () -> Unit) {
    val key = userKey(sidx, nick)
    val w = Social.whois[key]
    val p = Social.profiles[key]
    val failed = Social.failed[key] == true
    fun ask() {
        Social.failed.remove(key); Social.profiles.remove(key); Social.whois.remove(key)
        Irc.whois(sidx, nick); Irc.requestProfile(sidx, nick)
    }
    LaunchedEffect(key) { if (w == null || p == null) ask() }
    ModalBottomSheet(onDismissRequest = close, containerColor = C.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AvatarView(p?.avatar, nick, 96)
                Text(p?.name?.ifEmpty { null } ?: nick, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("@$nick", color = C.nick(nick))
                    if (!w?.account.isNullOrEmpty()) { Icon(Icons.Filled.Verified, null, tint = C.ok, modifier = Modifier.size(16.dp)); Text(w!!.account, color = C.ok, fontSize = 14.sp) }
                    if (w?.secure == true) Icon(Icons.Filled.Lock, null, tint = C.ok, modifier = Modifier.size(15.dp))
                    IconButton({ ask() }, Modifier.size(28.dp)) { Icon(Icons.Filled.Refresh, null, tint = C.dim, modifier = Modifier.size(18.dp)) }
                }
                if (!w?.away.isNullOrEmpty()) Text("🌙 " + t("Ausente: %s").format(w!!.away), color = C.accent, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(t("Privado"), Icons.AutoMirrored.Filled.Chat, Modifier.weight(1f)) { Irc.userInput(sidx, chan?.uid ?: 0, "/query $nick"); close() }
                if (chan != null && chan.type != io.github.archelkrozt.vitairc.irc.ChanType.STATUS)
                    BigButton(t("Mencionar"), Icons.Filled.AlternateEmail, Modifier.weight(1f)) { close(); appendToInput(chan.sidx, chan.uid, "$nick: ") }
                val ign = Irc.isIgnored(nick)
                BigButton(if (ign) t("Dejar de ignorar") else t("Ignorar"), Icons.Filled.Block, Modifier.weight(1f)) {
                    Irc.userInput(sidx, chan?.uid ?: 0, (if (ign) "/unignore " else "/ignore ") + nick)
                }
            }
            CardBox(t("Perfil VitaIRC")) {
                when {
                    p?.none == true -> Text(t("No comparte su perfil."), color = C.dim)
                    p != null && p.bio.isEmpty() -> Text(t("Sin biografía."), color = C.dim)
                    p != null -> Text(p.bio, color = C.text)
                    failed -> Text(t("No usa VitaIRC o no está conectado."), color = C.dim)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(t("Pidiendo su perfil..."), color = C.dim) }
                }
            }
            if (!p?.favs.isNullOrEmpty()) CardBox(t("Imágenes favoritas")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in p!!.favs) FavThumb(f)
                }
            }
            CardBox("WHOIS") {
                when {
                    w == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(t("Consultando WHOIS..."), color = C.dim) }
                    !w.found -> Text(t("No está conectado."), color = C.dim)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoRow(t("Nombre real"), w.realname)
                        InfoRow(t("Dirección"), "${w.user}@${w.host}")
                        if (w.actualHost.isNotEmpty()) InfoRow(t("Conecta desde"), w.actualHost)
                        InfoRow(t("Servidor"), w.server + if (w.serverInfo.isNotEmpty()) " · ${w.serverInfo}" else "")
                        if (w.idleSecs >= 0) InfoRow(t("Inactivo"), idleText(w.idleSecs))
                        if (w.signon > 0) InfoRow(t("Conectado desde"), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(w.signon)))
                        if (w.oper) InfoRow(t("Operador de IRC"), t("Sí"))
                        if (w.channels.isNotEmpty()) {
                            Text(t("Canales"), color = C.faint, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (ch in w.channels) Text(ch, color = C.chan, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(C.sel)
                                    .clickable {
                                        val name = ch.dropWhile { it in "~&@%+" }
                                        Irc.userInput(sidx, Irc.locked { Irc.servers.getOrNull(sidx)?.status?.uid ?: 0 }, "/join $name"); close()
                                    }.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun idleText(s: Int) = when {
    s < 60 -> t("%d s").format(s)
    s < 3600 -> t("%d min").format(s / 60)
    s < 86400 -> t("%d h %d min").format(s / 3600, s % 3600 / 60)
    else -> t("%d días").format(s / 86400)
}

@Composable
private fun BigButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(C.panel).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = C.accent)
        Text(label, color = C.accent, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun CardBox(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = C.faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row { Text(k, color = C.faint, fontSize = 13.sp, modifier = Modifier.width(110.dp)); Text(v, color = C.text, fontSize = 13.sp) }
}

/** A public favorite of someone's profile: its thumbnail (looked up when not sent). */
@Composable
private fun FavThumb(f: ProfileFav) {
    var post by remember(f.key) { mutableStateOf<BPost?>(null) }
    var loading by remember(f.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun fetch(open: Boolean) {
        if (loading) return
        loading = true
        scope.launch {
            val p = withContext(Dispatchers.IO) { try { Booru.lookup(f.engine, f.id) } catch (e: Exception) { null } }
            loading = false
            post = p
            if (open) { if (p != null) Nav.go(Screen.Image(p.viewUrl, Booru.pageUrl(p), p)) else AppEvents.toast(t("Esa imagen ya no está disponible")) }
        }
    }
    LaunchedEffect(f.key) { if (f.thumb.isEmpty()) fetch(false) }
    val url = post?.thumbUrl ?: f.thumb
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(6.dp)).background(C.sel).clickable {
        val p = post
        if (p != null) Nav.go(Screen.Image(p.viewUrl, Booru.pageUrl(p), p)) else fetch(true)
    }, contentAlignment = Alignment.Center) {
        if (url.isEmpty()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else AsyncImage(thumbRequest(post ?: BPost(f.engine, f.id, 'g', 0, 0, "", 0, url, url, url, ""), url), null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------- my profile

@Composable
fun MyProfileScreen() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(MyProfile.name) }
    var bio by remember { mutableStateOf(MyProfile.bio) }
    var share by remember { mutableStateOf(MyProfile.share) }
    var avatar by remember { mutableStateOf(try { MyProfile.avatarBigFile.readBytes() } catch (e: Exception) { MyProfile.avatar }) }
    var tick by remember { mutableIntStateOf(0) }
    val favs by Booru.favorites.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        AppScope.launch {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@launch
            val big = avatarJpeg(bmp, 256, 60_000)
            val small = avatarJpeg(bmp, 64, 3_600)
            MyProfile.avatarBigFile.writeBytes(big)
            MyProfile.avatarFile.writeBytes(small)
            withContext(Dispatchers.Main) { avatar = big }
        }
    }
    fun save() {
        MyProfile.name = name.trim(); MyProfile.bio = bio.trim(); MyProfile.share = share
        MyProfile.save()
    }
    DisposableEffect(Unit) { onDispose { save() } }
    ScreenScaffold(t("Mi perfil"), t("Foto, biografía y favoritos públicos")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarView(avatar, io.github.archelkrozt.vitairc.data.Config.servers.firstOrNull()?.nick ?: "?", 110)
                Row {
                    TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(t("Cambiar foto")) }
                    if (avatar.isNotEmpty()) TextButton({ MyProfile.avatarFile.delete(); MyProfile.avatarBigFile.delete(); avatar = ByteArray(0) }) { Text(t("Quitar foto"), color = C.err) }
                }
            }
            OutlinedTextField(name, { name = it.take(MyProfile.MAX_NAME) }, Modifier.fillMaxWidth(), label = { Text(t("Nombre para mostrar")) }, singleLine = true)
            OutlinedTextField(bio, { bio = it.take(MyProfile.MAX_BIO) }, Modifier.fillMaxWidth(), label = { Text(t("Biografía")) }, minLines = 3, maxLines = 8,
                supportingText = { Text("${bio.length}/${MyProfile.MAX_BIO}") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Compartir mi perfil"), color = C.text, modifier = Modifier.weight(1f)); Switch(share, { share = it })
            }
            Text(t("Tu perfil se guarda solo en este teléfono. Quien use VitaIRC puede pedírtelo por IRC mientras estés conectado; si no lo compartes, solo verán tu nick."),
                color = C.dim, fontSize = 12.sp)
            key(tick) { Text(t("Favoritos públicos (%d/%d)").format(MyProfile.publicFavs.size, MyProfile.MAX_FAVS), color = C.accent, fontWeight = FontWeight.SemiBold) }
            if (favs.isEmpty()) Text(t("Aún no tienes favoritos. En el buscador de imágenes, mantén pulsada una imagen para guardarla."), color = C.dim)
            for (p in favs) key(p.key, tick) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val local = Booru.thumbFile(p)
                    AsyncImage(if (local.exists()) local else thumbRequest(p, p.thumbUrl), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(if (p.artist.isEmpty()) Booru.names[p.engine] else "🎨 " + p.artist, color = C.text, maxLines = 1)
                        Text(p.tags, color = C.faint, fontSize = 12.sp, maxLines = 1)
                    }
                    Switch(MyProfile.isPublic(p.key), { on ->
                        if (!MyProfile.setPublic(p.key, on)) AppEvents.toast(t("Puedes tener hasta %d favoritos públicos").format(MyProfile.MAX_FAVS))
                        tick++
                    })
                }
            }
            Text(t("Los favoritos públicos aparecen en tu perfil; los demás nunca salen del teléfono."), color = C.dim, fontSize = 12.sp)
            Button({ save(); AppEvents.toast(t("Perfil guardado")); Nav.back() }, Modifier.fillMaxWidth()) { Text(t("Guardar")) }
        }
    }
}

// ---------------------------------------------------------------- videos in the chat

/** Muted, looping video in the chat (Imgur's "GIFs" are MP4s) */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_ONE; volume = 0f; prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, modifier)
}

/** Floating player over the chat: drag it to a corner, ⤢ for full screen. */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(url: String) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val player = remember(url) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var corner by remember { mutableIntStateOf(3) }          // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
    var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
        val wPx = with(density) { minOf(area.width * 0.62f, 300.dp.toPx()) }
        val hPx = wPx * 9 / 16
        val pad = with(density) { 12.dp.toPx() }
        val top = with(density) { 70.dp.toPx() }
        val bottom = with(density) { 90.dp.toPx() }
        val x = if (corner % 2 == 0) pad else area.width - wPx - pad
        val y = if (corner < 2) top else area.height - hPx - bottom
        val target = IntOffset((x + drag.x).roundToInt(), (y + drag.y).roundToInt())
        val anim by animateIntOffsetAsState(target, label = "mini")
        Box(Modifier.offset { if (drag == androidx.compose.ui.geometry.Offset.Zero) anim else target }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .shadow(12.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .pointerInput(area) {
                detectDragGestures(onDragEnd = {
                    val cx = x + drag.x + wPx / 2; val cy = y + drag.y + hPx / 2
                    corner = (if (cy < area.height / 2) 0 else 2) + (if (cx < area.width / 2) 0 else 1)
                    drag = androidx.compose.ui.geometry.Offset.Zero
                }) { ch, d -> ch.consume(); drag += d }
            }) {
            AndroidView({ PlayerView(it).apply { this.player = player; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0x8C000000))) {
                IconButton({ val u = url; Social.miniVideo = null; Nav.go(Screen.Video(u, u)) }, Modifier.size(30.dp)) {
                    Icon(Icons.Filled.OpenInFull, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                IconButton({ Social.miniVideo = null }, Modifier.size(30.dp)) { Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}
