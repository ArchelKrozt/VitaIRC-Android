package io.github.archelkrozt.vitairc.ui

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.archelkrozt.vitairc.App
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.ServerCfg
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.AppScope
import io.github.archelkrozt.vitairc.net.BPost
import io.github.archelkrozt.vitairc.net.Booru
import io.github.archelkrozt.vitairc.net.LinkKind
import io.github.archelkrozt.vitairc.net.Previews
import io.github.archelkrozt.vitairc.net.UploadSource
import kotlinx.coroutines.launch
import java.io.File

sealed class Screen {
    data object Chat : Screen()
    data object Servers : Screen()
    data class EditServer(val idx: Int, val preset: ServerCfg?) : Screen()
    data object Settings : Screen()
    data object ChanList : Screen()
    data object Booru : Screen()
    data class Image(val url: String, val page: String, val post: BPost? = null) : Screen()
    data class Video(val url: String, val page: String) : Screen()
    data object Help : Screen()
    data object MyProfile : Screen()
}

/** Dialogs any screen can open. */
sealed class Dlg {
    class Input(val title: String, val initial: String = "", val password: Boolean = false, val hint: String = "",
                val onOk: (String) -> Unit) : Dlg()
    class Confirm(val text: String, val yes: String = "", val onYes: () -> Unit) : Dlg()
    class Choose(val title: String, val items: List<String>, val onPick: (Int) -> Unit) : Dlg()
    class UploadPreview(val uri: Uri, val sidx: Int, val uid: Long) : Dlg()
}

object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Chat)
    val current get() = stack.last()
    var dialog by mutableStateOf<Dlg?>(null)

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

object Actions {
    fun browser(url: String) {
        try {
            App.ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            AppEvents.toast(url)
        }
    }

    fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        App.ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens a link the best way the app can: viewer, player or browser. */
    fun openLink(url: String) {
        val p = Previews.cached(url)
        val kind = p?.kind ?: Previews.kindOf(url)
        when {
            kind == LinkKind.IMAGE -> Nav.go(Screen.Image(p?.media?.ifEmpty { null } ?: Previews.imageUrl(url) ?: url, url))
            kind == LinkKind.VIDEO -> Social.miniVideo = p?.media?.ifEmpty { null } ?: Previews.videoUrl(url)
            else -> browser(url)
        }
    }

    /** Window that gets the link of an upload: the open one, unless it's the server window. */
    fun uploadTarget(): Pair<Int, Long>? = Irc.locked {
        val s = Irc.servers.getOrNull(Irc.viewSidx) ?: return@locked null
        val c = Irc.findChanUid(s, Irc.viewUid) ?: return@locked null
        if (c.type == io.github.archelkrozt.vitairc.irc.ChanType.STATUS) null else s.idx to c.uid
    }

    fun sourceOf(ctx: Context, uri: Uri): UploadSource {
        var name = "image.jpg"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        return UploadSource(name, mime, size) { ctx.contentResolver.openInputStream(uri)!! }
    }

    /** File for a camera shot (shared with the camera app through the FileProvider). */
    fun newPhotoFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        return File(dir, "foto_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg")
    }

    /** Saves an image to the gallery (Pictures/VitaIRC). */
    fun saveToGallery(name: String, mime: String, data: ByteArray): Boolean = try {
        val ctx = App.ctx
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VitaIRC")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VitaIRC").apply { mkdirs() }
            File(dir, name).writeBytes(data)
        }
        true
    } catch (e: Exception) { false }

    /** Downloads and saves a search result's original to the gallery. */
    fun saveBooru(p0: BPost) {
        AppEvents.toast(t("Guardando en Imágenes/VitaIRC..."))
        AppScope.launch {
            try {
                val p = Booru.fresh(p0)
                val r = Booru.download(p, p.fileUrl)
                val ext = p.fileUrl.substringBefore('?').substringAfterLast('.', "jpg").lowercase()
                val mime = r.contentType.ifEmpty { "image/$ext" }
                val ok = saveToGallery("${Booru.names[p.engine]}_${p.id}.$ext", mime, r.body)
                AppEvents.toast(if (ok) t("Guardada en %s").format("Imágenes/VitaIRC") else t("No se pudo guardar: %s").format(t("No se pudo guardar el archivo")))
            } catch (e: Exception) {
                AppEvents.toast(t("No se pudo guardar: %s").format(e.message ?: ""))
            }
        }
    }
}
