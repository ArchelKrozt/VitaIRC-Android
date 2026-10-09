package io.github.archelkrozt.vitairc.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Link (or error) of an upload, to put into the input of a window. */
class UploadResult(val sidx: Int, val uid: Long, val ok: Boolean, val text: String)

class UpdateInfo(val tag: String, val url: String, val apk: String = "")

/** Things the background work tells the UI. */
object AppEvents {
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 32)
    fun toast(s: String) { toasts.tryEmit(s) }

    val uploads = MutableSharedFlow<UploadResult>(extraBufferCapacity = 8)
    val update = MutableStateFlow<UpdateInfo?>(null)
}

/** Status line shown while something runs in the background ("" when idle). */
object Busy {
    val text = MutableStateFlow("")
    fun set(s: String) { text.value = s }
}
