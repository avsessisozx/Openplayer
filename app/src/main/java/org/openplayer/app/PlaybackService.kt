package org.openplayer.app

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Arka planda oynatma ve bildirim denetimleri için medya oturumu. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    private var videoActivity: PendingIntent? = null
    private var musicActivity: PendingIntent? = null

    private val itemListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateSessionActivity()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = updateSessionActivity()
    }

    private val rebuildListener: (ExoPlayer) -> Unit = { fresh ->
        session?.player = fresh
        fresh.addListener(itemListener)
        updateSessionActivity()
    }

    /** Bildirime dokununca müzikte "Çalıyor" ekranı, videoda oynatıcı açılır. */
    private fun updateSessionActivity() {
        val music = MusicController.isMusic(PlayerHolder.player?.currentMediaItem)
        val target = if (music) musicActivity else videoActivity
        if (target != null) session?.setSessionActivity(target)
    }

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        videoActivity = openApp
        musicActivity = PendingIntent.getActivity(
            this, 1,
            Intent(this, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val player = PlayerHolder.get(this)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()
        player.addListener(itemListener)
        updateSessionActivity()
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
        PlayerHolder.player?.removeListener(itemListener)
        session?.release()
        session = null
        super.onDestroy()
    }
}
