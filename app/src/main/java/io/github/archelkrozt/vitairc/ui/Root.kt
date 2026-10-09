package io.github.archelkrozt.vitairc.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.archelkrozt.vitairc.BuildConfig
import io.github.archelkrozt.vitairc.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.Uploader
import kotlinx.coroutines.delay

/** The whole app: the screen on top of the stack, dialogs and pop-up messages. */
@Composable
fun AppRoot() {
    val snap by UiModel.snapshot.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { AppEvents.toasts.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        AppEvents.uploads.collect { r ->
            if (r.ok) appendToInput(r.sidx, r.uid, r.text) else AppEvents.toast(t("Error al subir: %s").format(r.text))
        }
    }
    // requests from the network side: show a window, answer an invitation
    LaunchedEffect(snap) {
        Irc.locked {
            Irc.focusReq?.let { (s, u) -> Irc.focusReq = null; selectWindow(s, u); Nav.home() }
            Irc.invite?.let { (s, ch) ->
                Irc.invite = null
                Nav.dialog = Dlg.Confirm(t("Te invitaron a %s. ¿Unirte?").format(ch)) { Irc.userInput(s, Irc.servers.getOrNull(s)?.status?.uid ?: 0, "/join $ch") }
            }
            // keep a valid window on screen
            if (snap.windows.none { it.sidx == Irc.viewSidx && it.uid == Irc.viewUid }) snap.windows.firstOrNull()?.let { selectWindow(it.sidx, it.uid) }
        }
    }
    VitaTheme {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            when (val sc = Nav.current) {
                Screen.Chat -> ChatScreen(snap)
                Screen.Servers -> ServersScreen(snap)
                is Screen.EditServer -> EditServerScreen(sc.idx, sc.preset)
                Screen.Settings -> SettingsScreen()
                Screen.ChanList -> ChanListScreen(snap)
                Screen.Booru -> BooruScreen()
                is Screen.Image -> ImageScreen(sc)
                is Screen.Video -> VideoScreen(sc)
                Screen.Help -> HelpScreen()
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)) { d ->
                Snackbar(containerColor = Color(0xF0222234), contentColor = C.text, shape = RoundedCornerShape(8.dp)) { Text(d.visuals.message, maxLines = 3) }
            }
            Nav.dialog?.let { DialogHost(it) }
            val upd by AppEvents.update.collectAsState()
            var asked by rememberSaveable { mutableStateOf("") }
            upd?.let { u ->
                if (asked != u.tag && !Splash.show) AlertDialog(onDismissRequest = { asked = u.tag }, containerColor = C.panel,
                    title = { Text(t("Nueva versión %s").format(u.tag)) },
                    text = { Text(t("Hay una versión nueva de VitaIRC. Se descarga y Android te pedirá confirmar la instalación; tus servidores, ajustes e historial se conservan.")) },
                    confirmButton = { TextButton({ asked = u.tag; io.github.archelkrozt.vitairc.net.Updates.install(u) }) { Text(t("Actualizar")) } },
                    dismissButton = { TextButton({ asked = u.tag }) { Text(t("Más tarde")) } })
            }
            if (Splash.show) SplashScreen()
        }
    }
    LaunchedEffect(Unit) { delay(1500); Splash.show = false }
}

/** Shown once when the app starts (not when it comes back from the background). */
object Splash { var show by mutableStateOf(true) }

@Composable
private fun SplashScreen() {
    Column(Modifier.fillMaxSize().background(C.bg), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher_src), null,
            Modifier.size(112.dp).clip(RoundedCornerShape(24.dp)))
        Spacer(Modifier.height(20.dp))
        Text("VitaIRC for Android", color = C.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(t("versión %s").format(BuildConfig.VERSION_NAME), color = C.dim, fontSize = 14.sp)
    }
}

@Composable
private fun DialogHost(d: Dlg) {
    val close = { Nav.dialog = null }
    when (d) {
        is Dlg.Input -> {
            var v by remember(d) { mutableStateOf(d.initial) }
            val focus = remember { FocusRequester() }
            AlertDialog(onDismissRequest = close, containerColor = C.panel, title = { Text(d.title, fontSize = fontSize(16f)) },
                text = {
                    OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        visualTransformation = if (d.password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (d.password) KeyboardType.Password else KeyboardType.Text))
                    LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
                },
                confirmButton = { TextButton({ close(); d.onOk(v) }) { Text("OK") } },
                dismissButton = { TextButton(close) { Text(t("Cancelar")) } })
        }
        is Dlg.Confirm -> AlertDialog(onDismissRequest = close, containerColor = C.panel, text = { Text(d.text) },
            confirmButton = { TextButton({ close(); d.onYes() }) { Text(d.yes.ifEmpty { t("Sí") }) } },
            dismissButton = { TextButton(close) { Text("No") } })
        is Dlg.Choose -> MenuSheet(d.title, d.items.mapIndexed { i, s -> MenuItem(s) { d.onPick(i) } }, close)
        is Dlg.UploadPreview -> {
            val ctx = LocalContext.current
            AlertDialog(onDismissRequest = close, containerColor = C.panel, title = { Text(t("Vista previa")) },
                text = { AsyncImage(d.uri, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) },
                confirmButton = {
                    TextButton({
                        close()
                        Uploader.upload(d.sidx, d.uid, Actions.sourceOf(ctx, d.uri))
                        AppEvents.toast(t("Subiendo imagen..."))
                    }) { Text(t("Subir a %s").format(Uploader.hostName(io.github.archelkrozt.vitairc.data.Config.imgHost))) }
                },
                dismissButton = { TextButton(close) { Text(t("Cancelar")) } })
        }
    }
}

/** Plays a video link with Media3 (MP4, WebM, ...) */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(sc: Screen.Video) {
    val ctx = LocalContext.current
    val player = remember(sc.url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(sc.url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    ScreenScaffold(t("Video"), sc.page, actions = {
        IconButton({ Actions.browser(sc.page) }) { Icon(Icons.Filled.OpenInBrowser, null, tint = C.text) }
    }) {
        AndroidView({ PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false); keepScreenOn = true } },
            Modifier.fillMaxSize().background(Color.Black))
    }
}
