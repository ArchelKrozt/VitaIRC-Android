package io.github.archelkrozt.vitairc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.archelkrozt.vitairc.App
import io.github.archelkrozt.vitairc.BuildConfig
import io.github.archelkrozt.vitairc.IrcService
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.ServerCfg
import io.github.archelkrozt.vitairc.irc.ServerState
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.Translator
import io.github.archelkrozt.vitairc.net.Updates
import io.github.archelkrozt.vitairc.net.Uploader

/** Top bar with a back arrow, used by every screen but the chat. */
@Composable
fun ScreenScaffold(title: String, sub: String = "", actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Surface(color = C.panel) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.text) }
                Column(Modifier.weight(1f)) {
                    Text(title, color = C.text, fontSize = fontSize(17f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotEmpty()) Text(sub, color = C.dim, fontSize = fontSize(12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                actions()
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), content = content)
    }
}

// ------------------------------------------------------------------
// Servers
// ------------------------------------------------------------------

private class Preset(val name: String, val host: String, val port: Int, val ssl: Boolean)

private val PRESETS = listOf(
    Preset("Libera.Chat", "irc.libera.chat", 6697, true), Preset("OFTC", "irc.oftc.net", 6697, true),
    Preset("EFnet", "irc.efnet.org", 6697, true), Preset("Rizon", "irc.rizon.net", 6697, true),
    Preset("DALnet", "irc.dal.net", 6697, true), Preset("hackint", "irc.hackint.org", 6697, true),
    Preset("IRCnet", "open.ircnet.net", 6667, false), Preset("Undernet", "irc.undernet.org", 6667, false),
    Preset("QuakeNet", "irc.quakenet.org", 6667, false),
)

@Composable
fun ServersScreen(snap: Snapshot) {
    var presets by remember { mutableStateOf(false) }
    ScreenScaffold(t("Servidores"), actions = {
        IconButton({ presets = true }) { Icon(Icons.Filled.Add, t("Añadir servidor"), tint = C.accent) }
    }) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(Config.servers.indices.toList()) { i ->
                val cfg = Config.servers[i]
                val st = snap.windows.firstOrNull { it.sidx == i }?.serverState ?: ServerState.OFF
                Row(Modifier.fillMaxWidth().clickable { Nav.go(Screen.EditServer(i, null)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(st)))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(cfg.name, color = C.text, fontSize = fontSize(16f), fontWeight = FontWeight.SemiBold)
                        Text("${cfg.host}:${cfg.port}${if (cfg.ssl) " SSL" else ""} · ${cfg.nick}", color = C.dim, fontSize = fontSize(13f))
                        Text(stateText(st), color = stateColor(st), fontSize = fontSize(12f))
                    }
                    val on = st != ServerState.OFF
                    Switch(on, {
                        if (on) Irc.disconnect(i) else Irc.connect(i)
                        IrcService.update(App.ctx)
                    })
                }
                HorizontalDivider(color = C.line)
            }
            item {
                TextButton({ presets = true }, Modifier.padding(12.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(t("Añadir servidor")) }
            }
        }
    }
    if (presets) MenuSheet(t("Añadir servidor"),
        PRESETS.map { p -> MenuItem("${p.name}  (${p.host}:${p.port}${if (p.ssl) " SSL" else ""})", Icons.Filled.Dns) {
            Nav.go(Screen.EditServer(-1, Config.defaultServer().copy(name = p.name, host = p.host, port = p.port, ssl = p.ssl, channels = "")))
        } } + MenuItem(t("Otro servidor (manual)..."), Icons.Filled.Edit) {
            Nav.go(Screen.EditServer(-1, Config.defaultServer().copy(name = "", host = "", channels = "")))
        }, { presets = false })
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, number: Boolean = false, hint: String = "") {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, singleLine = true,
        placeholder = if (hint.isNotEmpty()) ({ Text(hint, color = C.faint) }) else null,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }))
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
fun EditServerScreen(idx: Int, preset: ServerCfg?) {
    val orig = if (idx >= 0) Config.servers[idx] else preset ?: Config.defaultServer()
    var cfg by remember { mutableStateOf(orig.copy()) }
    var authMenu by remember { mutableStateOf(false) }
    ScreenScaffold(if (idx < 0) t("Nuevo servidor") else t("Editar servidor"), cfg.name, actions = {
        if (idx >= 0) IconButton({
            Nav.dialog = Dlg.Confirm(t("¿Eliminar el servidor \"%s\"?").format(cfg.name)) {
                Irc.disconnect(idx)
                Config.servers.removeAt(idx)
                Config.save()
                Irc.syncFromConfig()
                Irc.locked { if (Irc.viewSidx >= Config.servers.size) { Irc.viewSidx = -1 } }
                Nav.back()
            }
        }) { Icon(Icons.Filled.Delete, null, tint = C.err) }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Field(t("Nombre"), cfg.name, { cfg = cfg.copy(name = it) })
            Field(t("Servidor"), cfg.host, { cfg = cfg.copy(host = it.trim()) }, hint = "irc.libera.chat")
            Field(t("Puerto"), cfg.port.toString(), { cfg = cfg.copy(port = it.filter { c -> c.isDigit() }.take(5).toIntOrNull() ?: 0) }, number = true)
            SwitchRow("SSL/TLS", cfg.ssl) { cfg = cfg.copy(ssl = it) }
            Field("Nick", cfg.nick, { cfg = cfg.copy(nick = it.trim()) })
            Field(t("Cuenta (usuario)"), cfg.user, { cfg = cfg.copy(user = it.trim()) }, hint = t("(el nick)"))
            Field(t("Nombre real"), cfg.realname, { cfg = cfg.copy(realname = it) })
            Field(t("Contraseña"), cfg.password, { cfg = cfg.copy(password = it) }, password = true)
            Row(Modifier.fillMaxWidth().clickable { authMenu = true }.padding(vertical = 12.dp)) {
                Text(t("Autenticación"), color = C.dim, modifier = Modifier.weight(1f))
                Text(ServerCfg.authName(cfg.auth) ?: t("Automática"), color = C.text)
            }
            Field(t("Canales al conectar"), cfg.channels, { cfg = cfg.copy(channels = it) }, hint = "#vitasdk,#henkaku")
            SwitchRow(t("Conectar al iniciar"), cfg.autoconnect) { cfg = cfg.copy(autoconnect = it) }
            Spacer(Modifier.height(12.dp))
            Button({
                if (cfg.host.isBlank() || cfg.nick.isBlank()) { AppEvents.toast(t("Servidor y nick son obligatorios")); return@Button }
                if (cfg.name.isBlank()) cfg = cfg.copy(name = cfg.host)
                if (cfg.port <= 0) cfg = cfg.copy(port = if (cfg.ssl) 6697 else 6667)
                val i = if (idx >= 0) { Config.servers[idx] = cfg; idx } else { Config.servers.add(cfg); Config.servers.size - 1 }
                Config.save()
                Irc.syncFromConfig()
                if (idx < 0 || cfg.autoconnect) Irc.connect(i)
                IrcService.update(App.ctx)
                Nav.back()
            }, Modifier.fillMaxWidth()) { Text(t("Guardar")) }
            Text(t("Bouncer (soju/ZNC): agrégalo como servidor; los mensajes perdidos llegan con su hora real."), color = C.faint,
                fontSize = fontSize(12f), modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (authMenu) MenuSheet(t("Autenticación"), (0..3).map { a ->
        MenuItem(ServerCfg.authName(a) ?: t("Automática")) { cfg = cfg.copy(auth = a) }
    }, { authMenu = false })
}

// ------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------

@Composable
private fun Section(title: String) {
    Text(title, color = C.accent, fontSize = fontSize(13f), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))
}

@Composable
private fun Row2(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(label, color = C.text, fontSize = fontSize(15f))
        if (value.isNotEmpty()) Text(value, color = C.dim, fontSize = fontSize(13f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, sub: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = fontSize(15f))
            if (sub.isNotEmpty()) Text(sub, color = C.dim, fontSize = fontSize(12f))
        }
        Switch(value, onChange)
    }
}

private fun masked(k: String) = if (k.isEmpty()) t("(sin configurar)") else k.take(3) + "..." + k.takeLast(4)

@Composable
fun SettingsScreen() {
    var tick by remember { mutableIntStateOf(0) }          // redraw after a change
    fun changed() { Config.save(); tick++ }
    val usage by Translator.usage.collectAsState()
    val update by AppEvents.update.collectAsState()
    var choose by remember { mutableStateOf<Dlg.Choose?>(null) }
    key(tick) { ScreenScaffold(t("Ajustes"), t("se guardan en %s").format("config.ini")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clickable { Nav.go(Screen.MyProfile) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarView(try { io.github.archelkrozt.vitairc.irc.MyProfile.avatarBigFile.readBytes() } catch (e: Exception) { io.github.archelkrozt.vitairc.irc.MyProfile.avatar },
                    Config.servers.firstOrNull()?.nick ?: "?", 48)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(io.github.archelkrozt.vitairc.irc.MyProfile.name.ifEmpty { t("Mi perfil") }, color = C.text, fontWeight = FontWeight.SemiBold)
                    Text(t("Foto, biografía y favoritos públicos"), color = C.dim, fontSize = fontSize(12f))
                }
            }
            Section(t("Traducción"))
            Row2(t("Traductor"), Translator.providerName(Config.trProvider)) {
                choose = Dlg.Choose(t("Traductor"), listOf(Translator.providerName(0), Translator.providerName(1))) { i ->
                    Config.trProvider = i
                    if (i == Config.TRP_OPENAI && Config.openaiKey.isEmpty()) AppEvents.toast(t("ChatGPT necesita tu API key de OpenAI (fila siguiente)"))
                    changed()
                }
            }
            Row2(t("API key de OpenAI (ChatGPT)"), masked(Config.openaiKey)) {
                Nav.dialog = Dlg.Input(t("API key de OpenAI (sk-...)"), Config.openaiKey) { Config.openaiKey = it.trim(); changed() }
            }
            Row2(t("Modelo"), Config.openaiModel) { Nav.dialog = Dlg.Input(t("Modelo (ej: gpt-4o-mini)"), Config.openaiModel) { Config.openaiModel = it.trim(); changed() } }
            Row2(t("Traducir recibidos a"), Config.langName(Config.langIn)) {
                choose = Dlg.Choose(t("Traducir recibidos a"), Config.langNames) { Config.langIn = Config.langCodes[it]; changed() }
            }
            Row2(t("Traducir mis mensajes a"), Config.langName(Config.langOut)) {
                choose = Dlg.Choose(t("Traducir mis mensajes a"), Config.langNames) { Config.langOut = Config.langCodes[it]; changed() }
            }
            Row2(t("Uso de OpenAI"), t("hoy %ld tokens  ·  total %ld tokens (%ld pedidos)").replace("%ld", "%d").format(usage.first, usage.second, usage.third)) {}

            Section(t("Imágenes"))
            Row2(t("Servicio de imágenes"), Uploader.hostName(Config.imgHost)) {
                choose = Dlg.Choose(t("Servicio de imágenes"), Uploader.hostNames) { Config.imgHost = it; changed() }
            }
            val litter = listOf("1 hora", "12 horas", "24 horas", "72 horas").map { t(it) }
            val imgbbExp = listOf("nunca", "1 hora", "1 día", "1 semana", "1 mes").map { t(it) }
            Row2(t("Las imágenes se borran tras"), when (Config.imgHost) {
                Config.IMG_LITTERBOX -> litter[Config.litterTime]
                Config.IMG_IMGBB -> imgbbExp[Config.imgbbExpire]
                else -> t("(lo decide el servicio)")
            }) {
                when (Config.imgHost) {
                    Config.IMG_LITTERBOX -> choose = Dlg.Choose(t("Las imágenes se borran tras"), litter) { Config.litterTime = it; changed() }
                    Config.IMG_IMGBB -> choose = Dlg.Choose(t("Las imágenes se borran tras"), imgbbExp) { Config.imgbbExpire = it; changed() }
                }
            }
            Row2(t("API key de ImgBB"), masked(Config.imgbbKey)) {
                Nav.dialog = Dlg.Input(t("API key de ImgBB (api.imgbb.com)"), Config.imgbbKey) { Config.imgbbKey = it.trim(); changed() }
            }
            Row2(t("Client-ID de Imgur"), Config.imgurId.ifEmpty { t("(sin configurar)") }) {
                Nav.dialog = Dlg.Input(t("Client-ID de Imgur"), Config.imgurId) { Config.imgurId = it.trim(); changed() }
            }
            Toggle(t("Vista previa de enlaces"), Config.linkPreviews, if (Config.linkPreviews) t("Sí (imágenes, videos y páginas)") else "") { Config.linkPreviews = it; changed() }

            Section(t("Búsqueda de imágenes"))
            Toggle(t("Búsqueda: contenido adulto"), Config.searchAdult, if (Config.searchAdult) t("Sí (mayor de 18)") else t("No (solo general)")) { on ->
                if (!on) { Config.searchAdult = false; changed() }
                else Nav.dialog = Dlg.Confirm(t("¿Mostrar contenido adulto en búsquedas? Solo si eres mayor de 18.")) {
                    Config.searchAdult = true; changed()
                    AppEvents.toast(t("Contenido para adultos activado (las búsquedas nuevas lo incluyen)"))
                }
            }
            Row2(t("Usuario de Sankaku"), Config.sankakuUser.ifEmpty { t("(opcional)") }) {
                Nav.dialog = Dlg.Input(t("Usuario de Sankaku"), Config.sankakuUser) { Config.sankakuUser = it.trim(); changed() }
            }
            Row2(t("Contraseña de Sankaku"), if (Config.sankakuPassword.isNotEmpty()) "********" else t("(opcional)")) {
                Nav.dialog = Dlg.Input(t("Contraseña de Sankaku"), "", password = true) { Config.sankakuPassword = it.trim(); changed() }
            }

            Section(t("Chat"))
            Row2(t("Palabras que te avisan"), Config.highlight.ifEmpty { t("(solo tu nick)") }) {
                Nav.dialog = Dlg.Input(t("Palabras separadas por coma (ej: vita,henkaku)"), Config.highlight) { Config.highlight = it.trim(); changed() }
            }
            Row2(t("Nicks ignorados"), Config.ignore.ifEmpty { t("(ninguno)") }) {
                Nav.dialog = Dlg.Input(t("Nicks separados por coma (se permite *)"), Config.ignore) { Config.ignore = it.trim(); changed() }
            }
            Toggle(t("Colores y negritas de IRC"), Config.ircColors) { Config.ircColors = it; changed() }
            Toggle(t("Mostrar entradas/salidas"), Config.showJoins) { Config.showJoins = it; changed() }
            Row2(t("Tamaño de texto"), "${Config.fontPct}%") {
                val pcts = listOf(85, 100, 115, 130)
                choose = Dlg.Choose(t("Tamaño de texto"), pcts.map { "$it%" }) { Config.fontPct = pcts[it]; changed() }
            }
            Row2(t("Idioma de la interfaz"), when (Config.uiLang) { 1 -> "Español"; 2 -> "English"; else -> t("Automático") }) {
                choose = Dlg.Choose(t("Idioma de la interfaz"), listOf(t("Automático"), "Español", "English")) {
                    Config.uiLang = it; App.applyLanguage(); changed(); Irc.dirty()
                }
            }

            Section(t("Notificaciones y conexión"))
            Toggle(t("Sonido en privados y menciones"), Config.sound) { Config.sound = it; changed() }
            Toggle(t("Notificaciones de privados y menciones"), Config.notifications) { Config.notifications = it; changed() }
            Toggle(t("Seguir conectado en segundo plano"), Config.keepConnected, t("Notificación fija mientras haya servidores conectados")) {
                Config.keepConnected = it; changed(); IrcService.update(App.ctx)
            }

            Section(t("Acerca de"))
            Row2(t("Buscar actualizaciones"), update?.let { t("¡%s disponible! Pulsa para actualizar").format(it.tag) }
                ?: t("automático: %s  ·  pulsa para buscar ahora").format(if (Config.updateCheck) t("Sí") else "No")) {
                val u = update
                if (u != null) Updates.install(u) else { AppEvents.toast(t("Buscando actualizaciones...")); Updates.check(true) }
            }
            Toggle(t("Buscar actualizaciones automáticamente"), Config.updateCheck) { Config.updateCheck = it; changed() }
            Row2(t("Acerca de"), t("VitaIRC %s  ·  código abierto").format(BuildConfig.VERSION_NAME + " Android")) { Actions.browser("https://github.com/${Config.UPDATE_REPO}") }
            Spacer(Modifier.height(24.dp))
        }
    } }
    choose?.let { d -> MenuSheet(d.title, d.items.mapIndexed { i, s -> MenuItem(s) { d.onPick(i) } }, { choose = null }) }
}

// ------------------------------------------------------------------
// Help
// ------------------------------------------------------------------

@Composable
fun HelpScreen() {
    val lines = listOf(
        "Toca la barra de abajo para escribir. Los mensajes que empiezan con / son comandos.",
        "El botón ☰ (o deslizar desde la izquierda) abre la lista de servidores, canales y privados.",
        "Mantén pulsado un canal en la lista para silenciarlo o cerrarlo.",
        "Toca un mensaje para responder, abrir un privado, reaccionar, traducirlo, ignorar o copiar.",
        "Escribe @ y el comienzo de un nick: toca la sugerencia para completarlo.",
        "",
        "Comandos: /join #canal  /part  /msg nick texto  /query nick  /me acción  /nick nuevo",
        "/topic texto  /whois nick  /list  /notice  /quote RAW  /connect  /disconnect  /clear  /help",
        "",
        "Traducción: gratis con Google, o con ChatGPT si pones tu API key de OpenAI en Ajustes.",
        "\"Traducir mis mensajes\" envía lo que escribes traducido al idioma de salida.",
        "Imágenes: Litterbox funciona sin cuenta; ImgBB, Imgur o tu soju (FILEHOST) se eligen en Ajustes.",
        "Bouncer (soju/ZNC): agrégalo como servidor; los mensajes perdidos llegan con su hora real.",
        "Usuarios: toca un nick para abrir opciones (privado, ignorar y, si eres operador, op, voz, kick y ban).",
        "",
        "Segundo plano: con \"Seguir conectado en segundo plano\", VitaIRC sigue conectado con la pantalla apagada.",
    )
    ScreenScaffold(t("Ayuda"), t("controles y comandos")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            for ((i, l) in lines.withIndex()) Text(t(l), color = if (i < 5) C.text else C.dim, fontSize = fontSize(14f), modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}
