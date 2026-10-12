package org.openplayer.app

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Arka planda oynatma ve bildirim denetimleri için medya oturumu. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    private val rebuildListener: (ExoPlayer) -> Unit = { fresh -> session?.player = fresh }

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, PlayerHolder.get(this))
            .setSessionActivity(openApp)
            .build()
        PlayerHolder.addRebuildListener(rebuildListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        // Yalnızca kendi uygulamamız ve sistem (bildirim, kilit ekranı, Bluetooth) denetleyebilir.
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerHolder.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PlayerHolder.removeRebuildListener(rebuildListener)
        session?.release()
        session = null
        super.onDestroy()
    }
}
