package io.github.archelkrozt.vitairc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.ListedChan
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.AppScope
import io.github.archelkrozt.vitairc.net.BPost
import io.github.archelkrozt.vitairc.net.Booru
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ------------------------------------------------------------------
// Channel browser (/list) with search
// ------------------------------------------------------------------

@Composable
fun ChanListScreen(snap: Snapshot) {
    val c = snap.chan
    val sidx = c?.sidx ?: -1
    val rev by Irc.revision.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    data class L(val rows: List<ListedChan>, val listing: Int, val mask: Boolean, val online: Boolean, val total: Int)
    val data = remember(rev, filter, sidx) {
        Irc.locked {
            val s = Irc.servers.getOrNull(sidx) ?: return@locked L(emptyList(), 0, false, false, 0)
            val exact = (if (filter.startsWith("#")) "" else "#") + filter
            val rows = s.listed.filter { filter.isEmpty() || it.name.contains(filter, true) || it.topic.contains(filter, true) }
                .sortedWith(compareBy<ListedChan> { !it.name.equals(exact, true) }.thenByDescending { it.users })
            L(rows, s.listing, s.listMask, s.state == io.github.archelkrozt.vitairc.irc.ServerState.ONLINE, s.listed.size)
        }
    }
    fun search(q: String) {
        filter = q.trim()
        if (c == null) return
        // the list keeps only the biggest channels: ask the server for the matches
        val mask = filter.removePrefix("#").substringBefore(' ').substringBefore(',')
        Irc.userInput(c.sidx, c.uid, if (filter.isNotEmpty() && data.mask) "/list *$mask*" else "/list")
    }
    LaunchedEffect(sidx) { if (data.listing == 0 || data.total == 0) search("") }
    ScreenScaffold(t("Canales"), if (filter.isNotEmpty()) t("%s  ·  \"%s\": %d canales%s").format(c?.serverName ?: "", filter, data.rows.size,
        if (data.listing == 1) t("  ·  cargando...") else "")
    else t("%s  ·  %d canales%s").format(c?.serverName ?: "", data.total, if (data.listing == 1) t("  ·  cargando...") else ""), actions = {
        IconButton({ search(filter) }) { Icon(Icons.Filled.Refresh, t("Actualizar"), tint = C.text) }
    }) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
            placeholder = { Text(t("Buscar canal (nombre o tema)")) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(query) }))
        if (filter.isNotEmpty() && !data.mask && data.listing == 2)
            Text(t("Este servidor no busca en toda su lista: se filtran solo los canales más grandes."), color = C.faint,
                fontSize = fontSize(12f), modifier = Modifier.padding(horizontal = 16.dp))
        if (data.rows.isEmpty()) {
            val guess = ((if (filter.startsWith("#")) "" else "#") + filter).substringBefore(' ').substringBefore(',')
            val msg = when {
                !data.online -> t("Conéctate primero al servidor.")
                data.listing != 2 -> if (filter.isNotEmpty()) t("Buscando en el servidor...") else t("Esperando la lista del servidor...")
                filter.isNotEmpty() -> t("No hay canales con \"%s\". Pulsa ✕ para entrar a %s (si no existe, se crea).").format(filter, guess).replace("Pulsa ✕", t("Toca aquí"))
                else -> t("El servidor no devolvió canales.")
            }
            Text(msg, color = C.dim, modifier = Modifier.padding(16.dp).clickable(enabled = filter.isNotEmpty() && data.listing == 2 && c != null) {
                Irc.userInput(c!!.sidx, c.uid, "/join $guess"); Nav.back()
            })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(data.rows, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable { if (c != null) { Irc.userInput(c.sidx, c.uid, "/join ${e.name}"); Nav.back() } }
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = C.chan, fontSize = fontSize(15f), fontWeight = FontWeight.SemiBold)
                        if (e.topic.isNotEmpty()) Text(e.topic, color = C.dim, fontSize = fontSize(13f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${e.users}", color = C.accent, fontSize = fontSize(14f), modifier = Modifier.padding(start = 10.dp))
                }
                HorizontalDivider(color = C.line)
            }
        }
    }
}

// ------------------------------------------------------------------
// Image search (Safebooru, Danbooru, Sankaku) and favorites
// ------------------------------------------------------------------

@Composable
fun thumbRequest(p: BPost, url: String) = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true)
    .apply { Booru.imageHeaders(p.engine).forEach { (k, v) -> addHeader(k, v) } }.build()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BooruScreen() {
    val st by Booru.state.collectAsState()
    val favs by Booru.favorites.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(st.query) }
    val list = if (tab == 0) st.results else favs
    val grid = rememberLazyGridState()
    LaunchedEffect(tab) {
        // close to the end of the results: fetch the next page
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            if (tab == 0 && last >= Booru.state.value.results.size - 8) Booru.more()
        }
    }
    ScreenScaffold(if (tab == 0) t("Buscar imágenes · %s").format(Booru.names[st.engine]) else t("Favoritos"),
        if (tab == 0) (if (st.query.isNotEmpty()) "\"${st.query}\"  ·  " else "") + (if (Config.searchAdult) t("incluye adulto") else t("solo general"))
        else t("%d guardados en este dispositivo").format(favs.size)) {
        TabRow(tab, containerColor = C.panel, contentColor = C.accent) {
            Tab(tab == 0, { tab = 0 }, text = { Text(t("Resultados")) })
            Tab(tab == 1, { tab = 1 }, text = { Text(t("Favoritos") + if (favs.isNotEmpty()) " (${favs.size})" else "") })
        }
        if (tab == 0) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text(t("Buscar imágenes (etiquetas en inglés, ej: scenery cat)")) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { Booru.search(Config.searchEngine, query) }))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, n) in Booru.names.withIndex()) FilterChip(st.engine == i, {
                    Config.searchEngine = i
                    Config.save()
                    if (st.query.isNotEmpty() || st.results.isNotEmpty()) Booru.search(i, query) else Booru.state.value = st.copy(engine = i)
                }, label = { Text(n) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(list, key = { _, p -> p.key }) { _, p ->
                    val fav = favs.any { it.key == p.key }
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(C.panel)
                        .combinedClickable(onClick = { Nav.go(Screen.Image(p.viewUrl, Booru.pageUrl(p), p)) }, onLongClick = { toggleFav(p) })) {
                        val local = Booru.thumbFile(p)
                        AsyncImage(if (tab == 1 && local.exists()) local else thumbRequest(p, p.thumbUrl), null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (fav) Text("★", color = C.accent, fontSize = fontSize(18f), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                        if (p.rating == 'q' || p.rating == 'e') Text(if (p.rating == 'e') "E" else "Q", color = C.text, fontSize = fontSize(12f),
                            modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(3.dp)).background(C.err).padding(horizontal = 5.dp))
                    }
                }
            }
            val msg = when {
                list.isNotEmpty() -> ""
                tab == 1 -> t("Aún no tienes favoritos. En los resultados, mantén pulsada una imagen para guardarla.")
                st.loading -> t("Buscando...")
                st.error.isNotEmpty() -> st.error
                st.query.isNotEmpty() -> t("Sin resultados.")
                else -> t("Escribe etiquetas en inglés y pulsa buscar (ej: scenery cat).")
            }
            if (msg.isNotEmpty()) Text(msg, color = if (st.error.isNotEmpty() && tab == 0) C.err else C.dim, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            if (tab == 0 && st.loading && list.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = C.accent)
        }
    }
}

/** Adds or removes a favorite, keeping a small thumbnail on the card. */
fun toggleFav(p: BPost) {
    if (Booru.isFav(p)) {
        Booru.toggleFav(p, null)
        AppEvents.toast(t("Quitada de favoritos"))
        return
    }
    AppScope.launch {
        val thumb = try { Booru.download(Booru.fresh(p), p.thumbUrl, 4 * 1024 * 1024).body.let { shrinkJpeg(it) } } catch (e: Exception) { null }
        if (Booru.toggleFav(p, thumb)) AppEvents.toast(t("Agregada a favoritos"))
    }
}

/** Small JPEG for a favorite's card (Sankaku's thumbnails are big WebPs whose links expire). */
fun shrinkJpeg(data: ByteArray): ByteArray? = try {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, o)
    var sample = 1
    while (o.outWidth / (sample * 2) >= 240 && o.outHeight / (sample * 2) >= 240) sample *= 2
    val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
} catch (e: Exception) { null }

// ------------------------------------------------------------------
// Image viewer
// ------------------------------------------------------------------

@Composable
fun ImageScreen(sc: Screen.Image) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val tf = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 8f)
        offset = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    val p = sc.post
    val favs by Booru.favorites.collectAsState()
    var post by remember { mutableStateOf(p) }
    val model = if (post != null) thumbRequest(post!!, post!!.viewUrl) else ImageRequest.Builder(LocalContext.current).data(sc.url).crossfade(true).build()
    // Sankaku's links expire within the hour: refresh a favorite's before showing it
    LaunchedEffect(p) { if (p != null && Booru.expired(p)) post = try { Booru.refresh(p) } catch (e: Exception) { p } }
    ScreenScaffold(t("Imagen"), sc.page, actions = {
        IconButton({ Actions.browser(sc.page) }) { Icon(Icons.Filled.OpenInBrowser, t("Abrir en el navegador"), tint = C.text) }
        if (p == null) IconButton({ Actions.share(sc.page) }) { Icon(Icons.Filled.Share, null, tint = C.text) }
    }) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF050508)).transformable(tf)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = androidx.compose.ui.geometry.Offset.Zero }) },
            contentAlignment = Alignment.Center) {
            AsyncImage(model, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
        }
        if (p != null) {
            val fav = favs.any { it.key == p.key }
            Surface(color = C.panel) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton({
                        val target = Actions.uploadTarget()
                        if (target == null) AppEvents.toast(t("Abre un canal o privado antes de buscar para poder enviar"))
                        else {
                            Booru.send(post ?: p, target.first, target.second)
                            AppEvents.toast(t("Preparando el enlace..."))
                            Nav.home()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, null); Spacer(Modifier.width(6.dp)); Text(t("Enviar al chat")) }
                    TextButton({ toggleFav(post ?: p) }) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, null)
                        Spacer(Modifier.width(6.dp)); Text(if (fav) t("Quitar favorito") else t("Favorito"))
                    }
                    TextButton({ Actions.saveBooru(post ?: p) }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text(t("Guardar")) }
                }
            }
        }
    }
}
