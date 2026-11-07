package org.openplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Arka planda oynatma, bildirim, kilit ekranı ve medya tuşları. Video ve müzik aynı motoru
 * kullandığından servis yalnız [PlaybackHub.target] ile konuşur; hedef kalmayınca kendini kapatır.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "openplayer_playback"
        private const val NOTIFICATION_ID = 7301
        private const val ART_SIZE_PX = 512
        private const val SEEK_STEP_MS = 10_000L
        private const val ACTION_TOGGLE = "org.openplayer.app.playback.TOGGLE"
        private const val ACTION_NEXT = "org.openplayer.app.playback.NEXT"
        private const val ACTION_PREVIOUS = "org.openplayer.app.playback.PREVIOUS"
        private const val ACTION_DISMISS = "org.openplayer.app.playback.DISMISS"

        /** Servis ayakta mı. */
        @Volatile var running = false
            private set

        /**
         * Servisi ön planda başlatır; önce [PlaybackHub.setTarget] çağrılmış olmalıdır. Uygulama
         * arka plandayken sistem reddedebilir (API 31+), o zaman false döner.
         */
        fun start(context: Context): Boolean = try {
            val app = context.applicationContext
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, PlaybackService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var session: MediaSession? = null
    private var foreground = false
    private var metadataKey: String? = null
    private var shownKey: String? = null
    private var activityIsMusic: Boolean? = null
    private var artKey: String? = null
    private var art: Bitmap? = null

    private val hubListener: () -> Unit = { render() }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() { PlaybackHub.target?.play() }
        override fun onPause() { PlaybackHub.target?.pause() }
        override fun onStop() { PlaybackHub.target?.pause() }
        override fun onSkipToNext() { PlaybackHub.target?.next() }
        override fun onSkipToPrevious() { PlaybackHub.target?.previous() }
        override fun onSeekTo(pos: Long) { PlaybackHub.target?.seekTo(pos) }
        override fun onFastForward() { PlaybackHub.target?.let { it.seekTo(it.positionMs + SEEK_STEP_MS) } }
        override fun onRewind() { PlaybackHub.target?.let { it.seekTo((it.positionMs - SEEK_STEP_MS).coerceAtLeast(0L)) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        session = MediaSession(this, "OpenPlayer").apply {
            setCallback(callback, main)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            }
            isActive = true
        }
        PlaybackHub.addListener(hubListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val t = PlaybackHub.target
        when (intent?.action) {
            ACTION_TOGGLE -> t?.let { if (it.playWhenReady) it.pause() else it.play() }
            ACTION_NEXT -> t?.next()
            ACTION_PREVIOUS -> t?.previous()
            ACTION_DISMISS -> if (t == null || !t.playWhenReady) {
                shutDown()
                return START_NOT_STICKY
            }
            // startForegroundService ile gelindi: startForeground hemen çağrılmazsa sistem uygulamayı kapatır.
            null -> enterForeground(build(t, t?.playWhenReady == true))
        }
        render()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val t = PlaybackHub.target
        if (t == null || !t.playWhenReady) shutDown()
    }

    override fun onDestroy() {
        running = false
        PlaybackHub.removeListener(hubListener)
        worker.shutdownNow()
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun shutDown() {
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun render() {
        val t = PlaybackHub.target
        if (t == null) {
            shutDown()
            return
        }
        updateSessionActivity(t)
        updateMetadata(t)
        updateState(t)
        val playing = t.playWhenReady
        val key = listOf(t.title, t.artist, t.album, playing, t.canSkipNext, t.canSkipPrevious, art?.generationId).joinToString("|")
        val fresh = key != shownKey
        shownKey = key
        when {
            playing && !foreground -> enterForeground(build(t, true))
            playing && fresh -> notifySafely(build(t, true))
            !playing && foreground -> {
                // Duraklayınca bildirim kaydırılabilir olur; ön plan bırakılsa da bildirim kalır.
                foreground = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                notifySafely(build(t, false))
            }
            !playing && fresh -> notifySafely(build(t, false))
        }
    }

    private fun enterForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foreground = true
        } catch (_: IllegalStateException) {
            // API 31+: uygulama arka plandayken izin yok; oynatma sürer, bildirim düz gösterilir.
            notifySafely(notification)
        } catch (_: SecurityException) {
            notifySafely(notification)
        }
    }

    private fun notifySafely(notification: Notification) {
        // POST_NOTIFICATIONS reddedildiyse bildirim görünmez; oynatma etkilenmez.
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun updateSessionActivity(t: PlaybackTarget) {
        if (activityIsMusic == t.isMusic) return
        activityIsMusic = t.isMusic
        session?.setSessionActivity(contentIntent(t))
    }

    private fun contentIntent(t: PlaybackTarget): PendingIntent = PendingIntent.getActivity(
        this, if (t.isMusic) 1 else 0, t.openIntent(this),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun updateMetadata(t: PlaybackTarget) {
        loadArt(t)
        val key = listOf(t.title, t.artist, t.album, t.durationMs, art?.generationId).joinToString("|")
        if (key == metadataKey) return
        metadataKey = key
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
        art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        session?.setMetadata(b.build())
    }

    private fun loadArt(t: PlaybackTarget) {
        val key = t.artworkKey
        if (key == artKey) return
        artKey = key
        art = null
        val app = applicationContext
        runCatching {
            worker.execute {
                val bmp = runCatching { t.artwork(app, ART_SIZE_PX) }.getOrNull()
                main.post {
                    if (artKey == key && session != null) {
                        art = bmp
                        render()
                    }
                }
            }
        }
    }

    private fun updateState(t: PlaybackTarget) {
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (t.canSkipNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (t.canSkipPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val state = when {
            t.isPlaying -> PlaybackState.STATE_PLAYING
            t.playWhenReady -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, t.positionMs, if (state == PlaybackState.STATE_PLAYING) t.speed else 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun servicePending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, title: String, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, servicePending(action, code)).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, Tx.s(R.string.nowplaying_001), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun build(t: PlaybackTarget?, playing: Boolean): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val actions = ArrayList<Notification.Action>()
        if (t?.canSkipPrevious == true) actions += action(R.drawable.ic_prev, Tx.s(R.string.playeracti_048), ACTION_PREVIOUS, 11)
        actions += if (playing) action(R.drawable.ic_pause, Tx.s(R.string.playeracti_066), ACTION_TOGGLE, 12)
        else action(R.drawable.ic_play, Tx.s(R.string.playeracti_065), ACTION_TOGGLE, 12)
        if (t?.canSkipNext == true) actions += action(R.drawable.ic_next, Tx.s(R.string.playeracti_067), ACTION_NEXT, 13)
        actions.forEach { b.addAction(it) }
        val style = Notification.MediaStyle().setShowActionsInCompactView(*actions.indices.toList().toIntArray())
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
            .setSmallIcon(if (t?.isMusic == false) R.drawable.ic_play else R.drawable.ic_music_note)
            .setContentTitle(t?.title ?: "OpenPlayer")
            .setContentText(t?.artist.orEmpty())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setDeleteIntent(servicePending(ACTION_DISMISS, 14))
        t?.let { b.setContentIntent(contentIntent(it)) }
        art?.let { b.setLargeIcon(it) }
        return b.build()
    }
}
