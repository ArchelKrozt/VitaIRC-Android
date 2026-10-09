package io.github.archelkrozt.vitairc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.archelkrozt.vitairc.L.t
import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.irc.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Keeps the IRC connections alive with the screen off (Android can; the Vita couldn't). */
class IrcService : Service() {
    private var job: Job? = null

    companion object {
        private const val ID = 1

        /** Starts or stops the service to match the settings and the connections. */
        fun update(c: Context) {
            val want = Config.keepConnected && Irc.anyConnected()
            val i = Intent(c, IrcService::class.java)
            if (want) try { ContextCompat.startForegroundService(c, i) } catch (_: Exception) {}
            else c.stopService(i)
        }
    }

    private fun build(): android.app.Notification {
        val (online, total) = Irc.locked { Irc.servers.count { it.state == ServerState.ONLINE } to Irc.servers.count { it.wantConnect } }
        val text = when {
            online == total && online == 1 -> t("Conectado a %s").format(Irc.locked { Irc.servers.firstOrNull { it.wantConnect }?.cfg?.name ?: "" })
            online == total -> t("Conectado a %d servidores").format(online)
            else -> t("Conectado a %d de %d servidores").format(online, total)
        }
        return NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("VitaIRC")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifier.openIntent(this, -1, 0))
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(ID, build())
        if (job == null) job = CoroutineScope(Dispatchers.Default).launch {
            Irc.revision.collectLatest {
                delay(1500)            // the line in the notification changes rarely
                if (!Config.keepConnected || !Irc.anyConnected()) { stopSelf(); return@collectLatest }
                androidx.core.app.NotificationManagerCompat.from(this@IrcService).let { nm ->
                    try { nm.notify(ID, build()) } catch (_: SecurityException) {}
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
