package com.qshlab.shelf.reader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps read-aloud going with the screen off or the app in the background, and shows the
 * lock-screen / notification player. The TTS navigator itself stays in ReaderActivity; this
 * service only wraps its Media3 player in a MediaSession (Media3 handles the foreground
 * notification while it plays).
 */
@OptIn(UnstableApi::class)
class ReadAloudService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attach()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun attach() {
        val player = player ?: return
        if (session?.player === player) return
        session?.let { removeSession(it); it.release() }
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val activity = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away: stop reading.
        onStopRequested?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }

    companion object {
        private var player: Player? = null
        private var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, player: Player, onStop: () -> Unit) {
            this.player = player
            onStopRequested = onStop
            context.startService(Intent(context, ReadAloudService::class.java))
        }

        fun stop(context: Context) {
            player = null
            onStopRequested = null
            context.stopService(Intent(context, ReadAloudService::class.java))
        }
    }
}
