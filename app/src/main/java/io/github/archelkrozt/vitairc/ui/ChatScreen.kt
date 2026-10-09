package io.github.archelkrozt.vitairc.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.data.History
import io.github.archelkrozt.vitairc.irc.ChanType
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.IrcText
import io.github.archelkrozt.vitairc.irc.MsgType
import io.github.archelkrozt.vitairc.irc.ServerState
import io.github.archelkrozt.vitairc.irc.TransState
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.Busy
import io.github.archelkrozt.vitairc.net.Translator
import io.github.archelkrozt.vitairc.net.Uploader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What I sent (newest first), for quick replies. */
object Sent {
    val list = mutableStateListOf<String>()
    fun add(s: String) { list.remove(s); list.add(0, s); while (list.size > 15) list.removeAt(list.size - 1) }
}

/** Shows a window (and clears its counters and notification). */
fun selectWindow(sidx: Int, uid: Long) {
    Irc.locked {
        Irc.viewSidx = sidx
        Irc.viewUid = uid
        Irc.servers.getOrNull(sidx)?.let { s -> Irc.findChanUid(s, uid)?.let { it.unread = 0; it.mention = 0 } }
        Irc.dirty()
    }
    io.github.archelkrozt.vitairc.Notifier.clear(sidx, uid)
}

fun stateText(s: ServerState) = when (s) {
    ServerState.CONNECTING -> t("conectando")
    ServerState.REGISTERING -> t("registrando")
    ServerState.ONLINE -> t("en línea")
    ServerState.WAITING -> t("reintentando")
    ServerState.OFF -> t("desconectado")
}

fun stateColor(s: ServerState) = when (s) {
    ServerState.ONLINE -> C.ok
    ServerState.OFF -> C.faint
    ServerState.WAITING -> C.err
    else -> C.accent
}

/** Jump request: window uid and message id to show and flash. */
object Jump { var target by mutableStateOf<Pair<Long, Long>?>(null) }

@Composable
fun ChatScreen(snap: Snapshot) {
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // another window shown (a notification, a new query...): the list closes
    LaunchedEffect(snap.chan?.sidx, snap.chan?.uid) { drawer.close() }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(260.dp).fillMaxHeight().background(C.side)) { Sidebar(snap) {} }
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
            ChatPane(snap, null)
        }
    } else {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(drawerContainerColor = C.side, modifier = Modifier.width(290.dp)) {
                Sidebar(snap) { scope.launch { drawer.close() } }
            }
        }) {
            ChatPane(snap) { scope.launch { drawer.open() } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(snap: Snapshot, onPicked: () -> Unit) {
    var winMenu by remember { mutableStateOf<WinUi?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("VitaIRC", color = C.accent, fontSize = fontSize(18f), fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(snap.windows, key = { "${it.sidx}/${it.uid}" }) { w ->
                val selected = w.sidx == Irc.viewSidx && w.uid == Irc.viewUid
                Row(
                    Modifier.fillMaxWidth().background(if (selected) C.sel else Color.Transparent)
                        .combinedClickable(onClick = { selectWindow(w.sidx, w.uid); onPicked() }, onLongClick = { winMenu = w })
                        .padding(start = if (w.type == ChanType.STATUS) 12.dp else 26.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) Box(Modifier.width(3.dp).height(20.dp).background(if (w.type == ChanType.QUERY) C.pm else C.accent))
                    if (w.type == ChanType.STATUS) Box(Modifier.padding(horizontal = 6.dp).size(9.dp).clip(CircleShape).background(stateColor(w.serverState)))
                    val col = when {
                        selected -> if (w.type == ChanType.QUERY) C.pm else C.text
                        w.type == ChanType.STATUS -> Color(0xFFB0B0C0)
                        w.muted || (w.type == ChanType.CHANNEL && !w.joined) -> C.faint
                        w.type == ChanType.QUERY -> C.pm
                        else -> C.chan
                    }
                    Text((if (w.type == ChanType.QUERY) "@ " else "") + w.name, color = col, fontSize = fontSize(15f),
                        fontWeight = if (w.type == ChanType.STATUS) FontWeight.SemiBold else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    if (w.unread > 0 && !selected) Text(if (w.unread > 99) "99" else "${w.unread}", color = C.text, fontSize = fontSize(12f),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (w.mention > 0) C.err else Color(0xFF2A3A5A))
                            .padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
        }
        if (snap.windows.isEmpty()) Text(t("Sin servidores"), color = C.faint, modifier = Modifier.padding(16.dp))
        HorizontalDivider(color = C.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            TextButton({ Nav.go(Screen.Servers); onPicked() }, Modifier.weight(1f)) { Icon(Icons.Filled.Dns, null); Spacer(Modifier.width(6.dp)); Text(t("Servidores")) }
            TextButton({ Nav.go(Screen.Settings); onPicked() }, Modifier.weight(1f)) { Icon(Icons.Filled.Settings, null); Spacer(Modifier.width(6.dp)); Text(t("Ajustes")) }
        }
    }
    winMenu?.let { w ->
        val items = ArrayList<MenuItem>()
        if (w.type == ChanType.STATUS) {
            val on = w.serverState != ServerState.OFF
            items.add(MenuItem(if (on) t("Desconectar de %s").format(w.name) else t("Conectar a %s").format(w.name), Icons.Filled.PowerSettingsNew) {
                if (on) Irc.disconnect(w.sidx) else Irc.connect(w.sidx)
                io.github.archelkrozt.vitairc.IrcService.update(io.github.archelkrozt.vitairc.App.ctx)
            })
        } else {
            items.add(MenuItem(if (w.muted) t("Quitar silencio") else t("Silenciar (sin sonido ni avisos)"), Icons.Filled.NotificationsOff) {
                Irc.locked { Irc.servers.getOrNull(w.sidx)?.let { s -> Irc.findChanUid(s, w.uid)?.let { it.muted = !it.muted } }; History.saveSession(); Irc.dirty() }
            })
            items.add(MenuItem(t("Cerrar ventana"), Icons.Filled.Close, C.err) { Irc.userInput(w.sidx, w.uid, "/close") })
        }
        MenuSheet(w.name, items, { winMenu = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPane(snap: Snapshot, openDrawer: (() -> Unit)?) {
    val c = snap.chan
    var menu by remember { mutableStateOf<String?>(null) }        // which menu sheet is open
    var msgSheet by remember { mutableStateOf<MsgUi?>(null) }
    val busy by Busy.text.collectAsState()
    Column(Modifier.fillMaxSize().background(C.bg).imePadding()) {
        // ---- top bar ----
        Surface(color = C.panel) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (openDrawer != null) IconButton(openDrawer) {
                    BadgedBox(badge = {
                        val pending = snap.windows.sumOf { if (it.mention > 0) 1L else 0L }
                        if (pending > 0) Badge(containerColor = C.err) { Text("$pending") }
                    }) { Icon(Icons.Filled.Menu, null, tint = C.text) }
                } else Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val title = when (c?.type) {
                        null -> "VitaIRC"
                        ChanType.STATUS -> c.serverName
                        ChanType.QUERY -> "@" + c.name
                        else -> c.name + if (c.muted) t(" (silenciado)") else ""
                    }
                    Text(title, color = C.text, fontSize = fontSize(17f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        busy.isNotEmpty() -> busy
                        c == null -> ""
                        c.type == ChanType.STATUS -> "${c.host} · ${c.nick} · ${stateText(c.state)}${if (c.away) t("  ·  ausente") else ""}"
                        c.type == ChanType.CHANNEL -> t("%d usuarios%s%s").format(c.users.size, if (c.topic.isNotEmpty()) "  ·  " else "", c.topic)
                        else -> t("privado en %s").format(c.serverName)
                    }
                    if (sub.isNotEmpty()) Text(sub, color = if (busy.isNotEmpty()) C.trans else if (c != null && c.state != ServerState.ONLINE) stateColor(c.state) else C.dim,
                        fontSize = fontSize(12f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = c?.type == ChanType.CHANNEL && c.topic.isNotEmpty()) { menu = "topic" })
                }
                if (c != null && c.type != ChanType.STATUS) {
                    IconButton({ toggleTranslateIn(c) }) { Icon(Icons.Filled.Translate, t("Traducción..."), tint = if (c.transIn) C.trans else C.dim) }
                }
                if (c != null && c.type == ChanType.CHANNEL) IconButton({ menu = "users" }) { Icon(Icons.Filled.People, null, tint = C.text) }
                IconButton({ menu = "main" }) { Icon(Icons.Filled.MoreVert, null, tint = C.text) }
            }
        }
        if (c == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(t("Agrega un servidor desde el menú (△ → Servidores).").replace("(△ → ", "(⋮ → "), color = C.dim)
            }
            return@Column
        }
        // ---- messages ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MessageList(c) { msgSheet = it }
        }
        InputBar(c) { menu = it }
    }

    // ---- sheets ----
    if (c != null) {
        msgSheet?.let { m -> MessageSheet(c, m) { msgSheet = null } }
        when (menu) {
            "main" -> MainMenu(c) { menu = it }
            "trans" -> TranslationMenu(c) { menu = it }
            "images" -> ImagesMenu(c) { menu = it }
            "chan" -> ChannelMenu(c) { menu = it }
            "users" -> UsersSheet(c) { menu = null }
            "quick" -> QuickSheet(c) { menu = null }
            "uploads" -> UploadsSheet { menu = null }
            "links" -> LinksSheet(c) { menu = null }
            "lang" -> MenuSheet(t("Idioma de traducción de este canal"),
                listOf(MenuItem(t("El de Ajustes (global)")) { setChanLang(c, "") }) +
                    Config.langCodes.mapIndexed { i, code -> MenuItem(Config.langNames[i]) { setChanLang(c, code) } }, { menu = null })
            "topic" -> AlertDialog(onDismissRequest = { menu = null }, confirmButton = { TextButton({ menu = null }) { Text("OK") } },
                title = { Text(c.name) }, text = { Text(c.topic) }, containerColor = C.panel)
        }
    }
}

private fun setChanLang(c: ChanUi, code: String) {
    Irc.locked {
        Irc.servers.getOrNull(c.sidx)?.let { s -> Irc.findChanUid(s, c.uid)?.let { it.transLang = code } }
        History.saveSession()
        Irc.dirty()
    }
    AppEvents.toast(t("%s se traducirá a %s").format(c.name, Config.langName(code.ifEmpty { Config.langIn })))
}

fun toggleTranslateIn(c: ChanUi) {
    if (!Translator.ready()) { AppEvents.toast(t("Configura tu API key de OpenAI en Ajustes (o elige el traductor gratis)")); return }
    Irc.locked {
        val s = Irc.servers.getOrNull(c.sidx) ?: return@locked
        val ch = Irc.findChanUid(s, c.uid) ?: return@locked
        ch.transIn = !ch.transIn
        if (ch.transIn) {
            // translate the last messages already on screen
            var done = 0
            for (m in ch.msgs.asReversed()) {
                if (done >= 12) break
                if (m.chatty && !m.self && m.transState == TransState.NONE && !IrcText.shouldSkipTranslation(m.text)) {
                    m.transState = TransState.PENDING
                    Translator.requestIn(s, ch, m)
                    done++
                }
            }
        }
        History.saveSession()
        Irc.dirty()
        AppEvents.toast(t("Traducción de recibidos: %s").format(if (ch.transIn) t("activada") else t("desactivada")))
    }
}

@Composable
private fun MessageList(c: ChanUi, onTap: (MsgUi) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rev = c.msgs.asReversed()
    var flash by remember { mutableStateOf(0L) }
    // new window: start at the newest message
    LaunchedEffect(c.sidx, c.uid) { state.scrollToItem(0) }
    // follow new messages unless the user scrolled up by hand (then the ▼ button shows)
    var follow by remember(c.sidx, c.uid) { mutableStateOf(true) }
    LaunchedEffect(c.sidx, c.uid) {
        snapshotFlow { state.isScrollInProgress }.collect { moving ->
            if (!moving) follow = state.firstVisibleItemIndex <= 1
        }
    }
    val newest = rev.firstOrNull()?.id
    LaunchedEffect(newest) { if (follow) state.scrollToItem(0) }
    // reaching the top asks the server for older messages (chathistory)
    LaunchedEffect(c.sidx, c.uid) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            if (last >= state.layoutInfo.totalItemsCount - 2 && state.layoutInfo.totalItemsCount > 0)
                Irc.locked { Irc.servers.getOrNull(c.sidx)?.let { s -> Irc.findChanUid(s, c.uid)?.let { Irc.requestOlder(s, it) } } }
        }
    }
    // search / last mention: scroll there and flash it
    val jump = Jump.target
    LaunchedEffect(jump) {
        if (jump != null && jump.first == c.uid) {
            val i = rev.indexOfFirst { it.id == jump.second }
            if (i >= 0) { state.animateScrollToItem(i); flash = jump.second; delay(2500); flash = 0 }
            Jump.target = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
            items(rev, key = { it.id }) { m ->
                MessageRow(m, flash == m.id, { onTap(m) }) { e -> react(c, m, e) }
            }
            if (c.loadingOlder) item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(t("Cargando mensajes anteriores..."), color = C.trans, fontSize = fontSize(13f))
                }
            }
        }
        if (c.msgs.isEmpty()) Text(t("Sin mensajes todavía."), color = C.faint, modifier = Modifier.padding(16.dp))
        val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 2 } }
        if (scrolled) {
            SmallFloatingActionButton({ scope.launch { state.animateScrollToItem(0) } }, Modifier.align(Alignment.BottomEnd).padding(14.dp),
                containerColor = C.sel, contentColor = C.accent) {
                Icon(Icons.Filled.KeyboardArrowDown, t("▼ mensajes nuevos abajo"))
            }
        }
    }
}

private fun react(c: ChanUi, m: MsgUi, emoji: String) = Irc.locked {
    val s = Irc.servers.getOrNull(c.sidx) ?: return@locked
    val ch = Irc.findChanUid(s, c.uid) ?: return@locked
    ch.findMsg(m.id)?.let { Irc.sendReact(s, ch, it, emoji) }
}

/** Nicks matching "@ab" at the end of the input. */
private fun completions(c: ChanUi, text: String): List<String> {
    val w = text.substringAfterLast(' ')
    if (!w.startsWith("@") || w.length < 2) return emptyList()
    val frag = w.substring(1)
    val pool = if (c.type == ChanType.QUERY) listOf(c.name) else c.users.map { it.nick }
    return pool.filter { it.startsWith(frag, true) }.take(12)
}

@Composable
private fun InputBar(c: ChanUi, openMenu: (String) -> Unit) {
    var field by remember(c.sidx, c.uid) { mutableStateOf(TextFieldValue(c.draft, TextRange(c.draft.length))) }
    // the draft follows the window (and gets the link of an upload)
    LaunchedEffect(c.draft) { if (c.draft != field.text) field = TextFieldValue(c.draft, TextRange(c.draft.length)) }
    DisposableEffect(c.sidx, c.uid) {
        onDispose { saveDraft(c, field.text) }
    }
    val sugg = completions(c, field.text)
    Surface(color = C.panel) {
        Column(Modifier.navigationBarsPadding()) {
            if (sugg.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sugg) { n ->
                    AssistChip({
                        val base = field.text.substringBeforeLast(' ', "")
                        val whole = !field.text.contains(' ')
                        val nt = (if (whole) "" else "$base ") + n + if (whole) ": " else " "
                        field = TextFieldValue(nt, TextRange(nt.length))
                    }, label = { Text(n, color = C.nick(n)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.type != ChanType.STATUS) {
                    IconButton({ openMenu("images") }) { Icon(Icons.Filled.AddPhotoAlternate, t("Imágenes"), tint = C.dim) }
                    IconButton({ openMenu("quick") }) { Icon(Icons.AutoMirrored.Filled.Reply, t("Rápido"), tint = C.dim) }
                }
                val hint = when {
                    c.type == ChanType.STATUS -> t("Comando (/join #canal)")
                    c.transOut -> t("Escribir (se envía en %s)").format(Config.langOut)
                    else -> t("Escribir mensaje...")
                }
                val send = {
                    // pasted text with several lines goes out one line at a time
                    val lines = field.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    for (l in lines) {
                        Sent.add(l)
                        Irc.userInput(c.sidx, c.uid, l)
                    }
                    if (lines.isNotEmpty()) {
                        field = TextFieldValue("")
                        saveDraft(c, "")
                    }
                }
                TextField(field, { field = it }, Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // a hardware keyboard: Enter sends, Shift+Enter breaks the line
                    if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                        if (e.type == KeyEventType.KeyDown) send()
                        true
                    } else false
                }, placeholder = { Text(hint, color = C.faint) }, maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                    // the keyboard's key sends (IRC messages are one line)
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = C.bg, unfocusedContainerColor = C.bg,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = C.accent))
                IconButton({ send() }) { Icon(Icons.AutoMirrored.Filled.Send, t("Enviar"), tint = C.accent) }
            }
        }
    }
}

private fun saveDraft(c: ChanUi, text: String) = Irc.locked {
    Irc.servers.getOrNull(c.sidx)?.let { s -> Irc.findChanUid(s, c.uid)?.draft = text }
}

/** Puts text into a window's input (a quick reply, a mention, an uploaded link). */
fun appendToInput(sidx: Int, uid: Long, text: String, replace: Boolean = false) {
    Irc.locked {
        Irc.servers.getOrNull(sidx)?.let { s ->
            Irc.findChanUid(s, uid)?.let { ch -> ch.draft = if (replace || ch.draft.isBlank()) text else ch.draft.trimEnd() + " " + text }
        }
        Irc.dirty()
    }
    selectWindow(sidx, uid)
    Nav.home()
}

// ------------------------------------------------------------------
// Menus
// ------------------------------------------------------------------

@Composable
private fun MainMenu(c: ChanUi, open: (String?) -> Unit) {
    val isChan = c.type == ChanType.CHANNEL
    val isWin = c.type != ChanType.STATUS
    val items = ArrayList<MenuItem>()
    if (isWin) items.add(MenuItem(t("Respuestas rápidas (nicks y enviados)"), Icons.AutoMirrored.Filled.Reply) { open("quick") })
    items.add(MenuItem(t("Unirse a un canal..."), Icons.Filled.Tag) {
        Nav.dialog = Dlg.Input(t("Unirse a canal (ej: #vitasdk)"), "#") { v -> if (v.isNotBlank() && v != "#") Irc.userInput(c.sidx, c.uid, "/join ${v.trim()}") }
    })
    items.add(MenuItem(t("Mensaje privado..."), Icons.Filled.Person) {
        Nav.dialog = Dlg.Input(t("Nick para mensaje privado")) { v -> if (v.isNotBlank()) Irc.userInput(c.sidx, c.uid, "/query ${v.trim()}") }
    })
    if (isChan) items.add(MenuItem(t("Usuarios del canal (%d)").format(c.users.size), Icons.Filled.People) { open("users") })
    if (isWin) {
        items.add(MenuItem(t("Traducción..."), Icons.Filled.Translate) { open("trans") })
        items.add(MenuItem(t("Imágenes (subir, cámara, enlaces)..."), Icons.Filled.Image) { open("images") })
        items.add(MenuItem(t("Buscar en el canal..."), Icons.Filled.Search) {
            Nav.dialog = Dlg.Input(t("Buscar en el canal")) { q -> searchChannel(c, q.trim()) }
        })
        items.add(MenuItem(t("Ir a la última mención"), Icons.Filled.AlternateEmail) {
            val m = c.msgs.lastOrNull { it.highlight }
            if (m != null) Jump.target = c.uid to m.id else AppEvents.toast(t("No hay menciones en este canal"))
        })
    }
    items.add(MenuItem(t("Explorar canales del servidor (/list)"), Icons.Filled.TravelExplore) { Nav.go(Screen.ChanList) })
    if (isWin) items.add(MenuItem(t("Canal (tema, salir, cerrar)..."), Icons.Filled.Forum) { open("chan") })
    else items.add(MenuItem(t("Limpiar ventana"), Icons.Filled.ClearAll) { Irc.userInput(c.sidx, c.uid, "/clear") })
    items.add(MenuItem(t("Cambiar nick..."), Icons.Filled.Badge) {
        Nav.dialog = Dlg.Input(t("Nuevo nick"), c.nick) { v -> if (v.isNotBlank()) Irc.userInput(c.sidx, c.uid, "/nick ${v.trim()}") }
    })
    if (c.away) items.add(MenuItem(t("Volver (quitar ausente)"), Icons.Filled.Visibility) { Irc.userInput(c.sidx, c.uid, "/back") })
    else items.add(MenuItem(t("Marcar como ausente..."), Icons.Filled.VisibilityOff) {
        Nav.dialog = Dlg.Input(t("Motivo de ausencia"), t("Ausente")) { v -> Irc.userInput(c.sidx, c.uid, "/away ${v.ifBlank { t("Ausente") }}") }
    })
    val on = c.state != ServerState.OFF
    items.add(MenuItem(if (on) t("Desconectar de %s").format(c.serverName) else t("Conectar a %s").format(c.serverName), Icons.Filled.PowerSettingsNew) {
        if (on) Irc.disconnect(c.sidx) else Irc.connect(c.sidx)
        io.github.archelkrozt.vitairc.IrcService.update(io.github.archelkrozt.vitairc.App.ctx)
    })
    items.add(MenuItem(t("Servidores..."), Icons.Filled.Dns) { Nav.go(Screen.Servers) })
    items.add(MenuItem(t("Ajustes (traducción, imágenes...)"), Icons.Filled.Settings) { Nav.go(Screen.Settings) })
    items.add(MenuItem(t("Ayuda y controles"), Icons.AutoMirrored.Filled.Help) { Nav.go(Screen.Help) })
    items.add(MenuItem(t("Salir de VitaIRC"), Icons.AutoMirrored.Filled.ExitToApp, C.err) {
        Nav.dialog = Dlg.Confirm(t("¿Salir de VitaIRC? Se cerrarán las conexiones.")) { Exit.request = true }
    })
    MenuSheet(t("Menú"), items, { open(null) })
}

/** Set by the menu; MainActivity closes the connections and finishes. */
object Exit { var request by mutableStateOf(false) }

private fun searchChannel(c: ChanUi, q: String) {
    if (q.isEmpty()) return
    val hits = c.msgs.filter { it.type != MsgType.DATE && (it.text.contains(q, true) || it.trans?.contains(q, true) == true) }.takeLast(30).reversed()
    if (hits.isEmpty()) { AppEvents.toast(t("Sin resultados para \"%s\"").format(q)); return }
    Nav.dialog = Dlg.Choose(t("Resultados para \"%s\"").format(q), hits.map { "${it.time} ${it.nick}${if (it.nick.isNotEmpty()) ": " else ""}${it.text}" }) { i ->
        Jump.target = c.uid to hits[i].id
    }
}

@Composable
private fun TranslationMenu(c: ChanUi, open: (String?) -> Unit) {
    MenuSheet(t("Traducción"), listOf(
        MenuItem(t("Traducir mensajes recibidos → %s: %s").format(c.transLang.ifEmpty { Config.langIn }, if (c.transIn) "ON" else "OFF"), Icons.Filled.Translate) { toggleTranslateIn(c) },
        MenuItem(t("Traducir mis mensajes → %s: %s").format(Config.langOut, if (c.transOut) "ON" else "OFF"), Icons.Filled.Outbound) {
            if (!Translator.ready()) { AppEvents.toast(t("Configura tu API key de OpenAI en Ajustes (o elige el traductor gratis)")); return@MenuItem }
            Irc.locked {
                Irc.servers.getOrNull(c.sidx)?.let { s -> Irc.findChanUid(s, c.uid)?.let { it.transOut = !it.transOut
                    AppEvents.toast(t("Tus mensajes %s").format(if (it.transOut) t("se traducirán antes de enviarse") else t("se envían sin traducir"))) } }
                History.saveSession(); Irc.dirty()
            }
        },
        MenuItem(t("Idioma de traducción de este canal: %s").format(if (c.transLang.isNotEmpty()) Config.langName(c.transLang) else t("el de Ajustes")), Icons.Filled.Language) {
            open("lang")
        },
    ), { open(null) })
}

@Composable
private fun ImagesMenu(c: ChanUi, open: (String?) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) Nav.dialog = Dlg.UploadPreview(uri, c.sidx, c.uid)
    }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo?.let { Nav.dialog = Dlg.UploadPreview(it, c.sidx, c.uid) }
    }
    fun ready(): Boolean {
        val why = Uploader.notReady(c.sidx)
        if (why != null) AppEvents.toast(why)
        return why == null
    }
    MenuSheet(t("Imágenes"), listOf(
        MenuItem(t("Subir imagen (%s)...").format(Uploader.hostName(Config.imgHost)), Icons.Filled.Upload) {
            if (ready()) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        MenuItem(t("Tomar foto con la cámara y subirla"), Icons.Filled.PhotoCamera) {
            if (ready()) {
                val f = Actions.newPhotoFile(ctx)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                photo = uri
                camera.launch(uri)
            }
        },
        MenuItem(t("Mis subidas recientes"), Icons.Filled.History) { open("uploads") },
        MenuItem(t("Buscar imágenes (Safebooru, Danbooru, Sankaku)"), Icons.Filled.ImageSearch) { Nav.go(Screen.Booru) },
        MenuItem(t("Enlaces e imágenes del canal"), Icons.Filled.Link) { open("links") },
    ), { open(null) })
}

@Composable
private fun ChannelMenu(c: ChanUi, open: (String?) -> Unit) {
    val items = ArrayList<MenuItem>()
    if (c.type == ChanType.CHANNEL) items.add(MenuItem(t("Cambiar tema del canal..."), Icons.Filled.Title) {
        Nav.dialog = Dlg.Input(t("Nuevo tema del canal"), c.topic) { v -> Irc.userInput(c.sidx, c.uid, "/topic $v") }
    })
    if (c.type == ChanType.CHANNEL && c.joined) items.add(MenuItem(t("Salir del canal"), Icons.AutoMirrored.Filled.Logout) { Irc.userInput(c.sidx, c.uid, "/part") })
    items.add(MenuItem(if (c.muted) t("Quitar silencio") else t("Silenciar (sin sonido ni avisos)"), Icons.Filled.NotificationsOff) {
        Irc.locked {
            Irc.servers.getOrNull(c.sidx)?.let { s -> Irc.findChanUid(s, c.uid)?.let { it.muted = !it.muted
                AppEvents.toast(if (it.muted) t("%s silenciado").format(c.name) else t("%s ya no está silenciado").format(c.name)) } }
            History.saveSession(); Irc.dirty()
        }
    })
    items.add(MenuItem(t("Cerrar ventana"), Icons.Filled.Close, C.err) { Irc.userInput(c.sidx, c.uid, "/close") })
    items.add(MenuItem(t("Limpiar ventana"), Icons.Filled.ClearAll) { Irc.userInput(c.sidx, c.uid, "/clear") })
    MenuSheet(c.name, items, { open(null) })
}

@Composable
private fun MessageSheet(c: ChanUi, m: MsgUi, close: () -> Unit) {
    val clip = LocalClipboardManager.current
    val items = ArrayList<MenuItem>()
    val chatty = m.type == MsgType.MSG || m.type == MsgType.ACTION || m.type == MsgType.NOTICE
    for (u in IrcText.urls(m.text, 4)) {
        val img = io.github.archelkrozt.vitairc.net.Previews.kindOf(u) != io.github.archelkrozt.vitairc.net.LinkKind.PAGE
        items.add(MenuItem((if (img) t("Ver imagen:") else t("Abrir en el navegador:")) + " " + u, Icons.Filled.Link) { Actions.openLink(u) })
    }
    if (chatty && m.nick.isNotEmpty() && !m.self) {
        items.add(MenuItem(t("Responder a %s").format(m.nick), Icons.AutoMirrored.Filled.Reply) { appendToInput(c.sidx, c.uid, "${m.nick}: ") })
        items.add(MenuItem(t("Mensaje privado a %s").format(m.nick), Icons.Filled.Person) { Irc.userInput(c.sidx, c.uid, "/query ${m.nick}") })
        val ign = Irc.isIgnored(m.nick)
        items.add(MenuItem((if (ign) t("Dejar de ignorar a %s") else t("Ignorar a %s")).format(m.nick), Icons.Filled.Block) {
            Irc.userInput(c.sidx, c.uid, (if (ign) "/unignore " else "/ignore ") + m.nick)
        })
    }
    if (chatty && m.transState != TransState.DONE && !IrcText.shouldSkipTranslation(m.text)) items.add(MenuItem(t("Traducir este mensaje"), Icons.Filled.Translate) {
        if (!Translator.ready()) { AppEvents.toast(t("Configura tu API key de OpenAI en Ajustes (o elige el traductor gratis)")); return@MenuItem }
        Irc.locked {
            val s = Irc.servers.getOrNull(c.sidx) ?: return@locked
            val ch = Irc.findChanUid(s, c.uid) ?: return@locked
            ch.findMsg(m.id)?.let { it.transState = TransState.PENDING; Translator.requestIn(s, ch, it, onDemand = true); Irc.dirty() }
        }
    })
    items.add(MenuItem(t("Copiar texto"), Icons.Filled.ContentCopy) { clip.setText(AnnotatedString(m.text)) })
    MenuSheet(if (m.nick.isNotEmpty()) "${m.nick}: ${m.text}" else m.text, items, close,
        top = if (m.canReact) ({ ReactRow(m.reacts.filter { it.mine }.map { it.emoji }.toSet()) { e -> react(c, m, e); close() } }) else null)
}

@Composable
private fun QuickSheet(c: ChanUi, close: () -> Unit) {
    val items = ArrayList<MenuItem>()
    val nicks = c.msgs.asReversed().filter { (it.type == MsgType.MSG || it.type == MsgType.ACTION) && !it.self && it.nick.isNotEmpty() }
        .map { it.nick }.distinctBy { it.lowercase() }.take(6)
    for (n in nicks) items.add(MenuItem(t("Responder a %s").format(n), Icons.AutoMirrored.Filled.Reply, C.nick(n)) { appendToInput(c.sidx, c.uid, "$n: ", true) })
    if (Sent.list.isNotEmpty()) {
        items.add(MenuItem(t("— Enviados recientemente —"), header = true))
        for (s in Sent.list.take(8)) items.add(MenuItem(s, Icons.Filled.History) { appendToInput(c.sidx, c.uid, s, true) })
    }
    if (items.isEmpty()) items.add(MenuItem(t("Sin mensajes todavía."), enabled = false))
    MenuSheet(t("Respuestas rápidas"), items, close)
}

@Composable
private fun LinksSheet(c: ChanUi, close: () -> Unit) {
    val items = ArrayList<MenuItem>()
    for (m in c.msgs.asReversed()) {
        for (u in IrcText.urls(m.text)) {
            if (items.size >= 30) break
            val img = io.github.archelkrozt.vitairc.net.Previews.kindOf(u) != io.github.archelkrozt.vitairc.net.LinkKind.PAGE
            items.add(MenuItem("${if (img) "[img]" else "[web]"} ${m.nick.ifEmpty { "*" }}: $u", Icons.Filled.Link) { Actions.openLink(u) })
        }
    }
    if (items.isEmpty()) { AppEvents.toast(t("No hay enlaces en los mensajes recientes")); close(); return }
    MenuSheet(t("Enlaces e imágenes del canal"), items, close)
}

@Composable
private fun UploadsSheet(close: () -> Unit) {
    val list = remember { Uploader.recent() }
    val target = Actions.uploadTarget()
    val items = ArrayList<MenuItem>()
    for (u in list) {
        items.add(MenuItem("${u.date}  [${u.host}]  ${u.link}", Icons.Filled.Link) {
            if (target != null) appendToInput(target.first, target.second, u.link) else Actions.share(u.link)
        })
        if (u.deleteUrl.isNotEmpty()) items.add(MenuItem(t("      borrar esta imagen (se abre en el navegador)"), Icons.Filled.Delete, C.err) { Actions.browser(u.deleteUrl) })
    }
    if (items.isEmpty()) { AppEvents.toast(t("Todavía no has subido imágenes")); close(); return }
    MenuSheet(t("Mis subidas recientes"), items, close)
}

@Composable
private fun UsersSheet(c: ChanUi, close: () -> Unit) {
    var user by remember { mutableStateOf<UserUi?>(null) }
    val u = user
    if (u == null) {
        MenuSheet(t("Usuarios del canal (%d)").format(c.users.size), c.users.map { x ->
            MenuItem((if (x.prefix != 0.toChar()) x.prefix.toString() else "") + x.nick + if (x.away) "  (" + t("ausente") + ")" else "",
                color = if (x.away) C.dim else C.nick(x.nick)) { user = x }
        }, close)
        return
    }
    val items = ArrayList<MenuItem>()
    items.add(MenuItem(t("Mensaje privado"), Icons.Filled.Person) { Irc.userInput(c.sidx, c.uid, "/query ${u.nick}") })
    items.add(MenuItem(t("Mencionar"), Icons.Filled.AlternateEmail) { appendToInput(c.sidx, c.uid, "${u.nick}: ") })
    items.add(MenuItem("WHOIS", Icons.Filled.Info) { Irc.userInput(c.sidx, c.uid, "/whois ${u.nick}") })
    val ign = Irc.isIgnored(u.nick)
    items.add(MenuItem(if (ign) t("Dejar de ignorar") else t("Ignorar"), Icons.Filled.Block) { Irc.userInput(c.sidx, c.uid, (if (ign) "/unignore " else "/ignore ") + u.nick) })
    if (c.myPrefix != 0.toChar() && c.myPrefix in "~&@%") {
        val hasOp = u.prefix != 0.toChar() && u.prefix in "~&@"
        val hasVoice = u.prefix == '+'
        items.add(MenuItem((if (hasOp) t("Quitar op a %s") else t("Dar op a %s")).format(u.nick), Icons.Filled.Shield) {
            Irc.userInput(c.sidx, c.uid, (if (hasOp) "/deop " else "/op ") + u.nick) })
        items.add(MenuItem((if (hasVoice) t("Quitar voz a %s") else t("Dar voz a %s")).format(u.nick), Icons.Filled.RecordVoiceOver) {
            Irc.userInput(c.sidx, c.uid, (if (hasVoice) "/devoice " else "/voice ") + u.nick) })
        items.add(MenuItem(t("Expulsar (kick)..."), Icons.AutoMirrored.Filled.Logout, C.err) {
            Nav.dialog = Dlg.Input(t("Motivo para expulsar a %s").format(u.nick)) { r -> Irc.userInput(c.sidx, c.uid, "/kick ${u.nick} $r") }
        })
        items.add(MenuItem(t("Banear y expulsar"), Icons.Filled.Gavel, C.err) { Irc.userInput(c.sidx, c.uid, "/kickban ${u.nick}") })
    }
    MenuSheet(u.nick, items, close)
}
