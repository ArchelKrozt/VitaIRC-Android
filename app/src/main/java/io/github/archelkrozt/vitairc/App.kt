package io.github.archelkrozt.vitairc

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import io.github.archelkrozt.vitairc.ui.UiModel
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.data.History
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.IrcHooks
import io.github.archelkrozt.vitairc.irc.Server
import io.github.archelkrozt.vitairc.net.AppEvents
import io.github.archelkrozt.vitairc.net.Booru
import io.github.archelkrozt.vitairc.net.Http
import io.github.archelkrozt.vitairc.net.Translator
import io.github.archelkrozt.vitairc.net.Updates
import java.util.Locale

class App : Application(), ImageLoaderFactory {
    companion object {
        lateinit var ctx: Context
            private set

        /** Interface language: 0 follows the system, 1 Spanish, 2 English. */
        fun applyLanguage() {
            L.english = when (Config.uiLang) {
                1 -> false
                2 -> true
                else -> Locale.getDefault().language != "es"
            }
        }
    }

    /** Images in the chat and the viewer, animated GIFs included. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // some sites (Wikimedia...) refuse downloads without a real User-Agent
        .okHttpClient {
            Http.client.newBuilder().addInterceptor { ch ->
                val r = ch.request()
                ch.proceed(if (r.header("User-Agent") == null) r.newBuilder().header("User-Agent", Http.userAgent).build() else r)
            }.build()
        }
        .components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        ctx = this
        Config.init(filesDir)
        applyLanguage()
        Notifier.init(this)

        IrcHooks.translateIn = { s, c, m -> Translator.requestIn(s, c, m) }
        IrcHooks.translateOut = { s, c, text -> Translator.requestOut(s, c, text) }
        IrcHooks.translationReady = { Translator.ready() }
        IrcHooks.notify = { s: Server, c, m -> Notifier.message(s, c, m) }
        IrcHooks.toast = { AppEvents.toast(it) }
        IrcHooks.beep = { Notifier.beep() }

        UiModel.start()
        Translator.start()
        Booru.loadFavorites()
        Irc.syncFromConfig()
        Irc.locked { History.restoreSession() }
        Config.servers.forEachIndexed { i, s -> if (s.autoconnect) Irc.connect(i) }
        IrcService.update(this)
        Updates.check(false)

        // back on the network (or a new one): check that the links are alive
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = Irc.resumeCheck()
        })
    }
}
