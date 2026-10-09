package io.github.archelkrozt.vitairc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.Chan
import io.github.archelkrozt.vitairc.irc.ChanType
import io.github.archelkrozt.vitairc.irc.Msg
import io.github.archelkrozt.vitairc.irc.Server
import io.github.archelkrozt.vitairc.net.AppEvents

/** Notifications for mentions and private messages, and the chime. */
object Notifier {
    const val CH_SOUND = "messages"
    const val CH_SILENT = "messages_silent"
    const val CH_SERVICE = "connection"
    const val EXTRA_SIDX = "sidx"
    const val EXTRA_UID = "uid"
    private lateinit var app: Context
    private var tone: ToneGenerator? = null

    fun init(c: Context) {
        app = c
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SOUND, t("Menciones y privados"), NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_SILENT, t("Menciones y privados (sin sonido)"), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, t("Conexión en segundo plano"), NotificationManager.IMPORTANCE_MIN))
        }
    }

    fun openIntent(c: Context, sidx: Int, uid: Long): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SIDX, sidx)
            putExtra(EXTRA_UID, uid)
        }
        return PendingIntent.getActivity(c, (sidx * 100000 + uid).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** PM or mention in a window that isn't on screen (sound only when the app is on screen). */
    fun message(s: Server, c: Chan, m: Msg) {
        if (io.github.archelkrozt.vitairc.irc.Irc.appVisible) {
            AppEvents.toast(if (c.type == ChanType.QUERY) t("PM de %s: %s").format(m.nick, m.text) else t("%s en %s: %s").format(m.nick, c.name, m.text))
            beep()
            return
        }
        if (!Config.notifications) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = if (c.type == ChanType.QUERY) m.nick else "${m.nick} · ${c.name}"
        val n = NotificationCompat.Builder(app, if (Config.sound) CH_SOUND else CH_SILENT)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setSubText(Config.servers.getOrNull(s.idx)?.name)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(app, s.idx, c.uid))
            .build()
        try { NotificationManagerCompat.from(app).notify((s.idx * 100000 + c.uid).toInt(), n) } catch (_: SecurityException) {}
    }

    fun clear(sidx: Int, uid: Long) = NotificationManagerCompat.from(app).cancel((sidx * 100000 + uid).toInt())

    /** Short chime for mentions while the app is open (like the Vita). */
    fun beep() {
        if (!Config.sound) return
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).also { tone = it }
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 160)
        } catch (_: Exception) {}
    }
}
