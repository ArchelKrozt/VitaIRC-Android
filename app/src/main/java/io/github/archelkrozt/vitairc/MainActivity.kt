package io.github.archelkrozt.vitairc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.archelkrozt.vitairc.irc.Irc
import io.github.archelkrozt.vitairc.ui.AppRoot
import io.github.archelkrozt.vitairc.ui.Exit
import io.github.archelkrozt.vitairc.ui.Nav
import io.github.archelkrozt.vitairc.ui.selectWindow

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Nav.dialog != null) Nav.dialog = null
                else if (!Nav.back()) moveTaskToBack(true)    // the chat stays connected in the background
            }
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
        setContent {
            AppRoot()
            LaunchedEffect(Unit) {
                snapshotFlow { Exit.request }.collect { if (it) quit() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A notification opens its window. */
    private fun handle(i: Intent?) {
        val sidx = i?.getIntExtra(Notifier.EXTRA_SIDX, -1) ?: -1
        val uid = i?.getLongExtra(Notifier.EXTRA_UID, 0) ?: 0
        if (sidx >= 0 && uid > 0) { selectWindow(sidx, uid); Nav.home() }
    }

    override fun onStart() {
        super.onStart()
        Irc.appVisible = true
        Irc.locked {
            Irc.servers.getOrNull(Irc.viewSidx)?.let { s -> Irc.findChanUid(s, Irc.viewUid)?.let { it.unread = 0; it.mention = 0 } }
            Irc.dirty()
        }
    }

    override fun onStop() {
        Irc.appVisible = false
        IrcService.update(this)
        super.onStop()
    }

    private fun quit() {
        Irc.quitAll()
        stopService(Intent(this, IrcService::class.java))
        finishAndRemoveTask()
    }
}
