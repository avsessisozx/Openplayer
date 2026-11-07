package org.openplayer.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Size
import android.widget.RemoteViews

/** Ana ekran müzik widget'ı: şarkı adı, kapak ve önceki/oynat/sonraki düğmeleri. */
class MusicWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_PREV = "org.openplayer.app.widget.PREV"
        private const val ACTION_PLAY = "org.openplayer.app.widget.PLAY"
        private const val ACTION_NEXT = "org.openplayer.app.widget.NEXT"
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

        private fun pending(context: Context, action: String, code: Int): PendingIntent {
            val i = Intent(context, MusicWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun openApp(context: Context): PendingIntent {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, 10, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Çalan şarkıya göre tüm widget'ları günceller. */
        fun update(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MusicWidget::class.java))
            if (ids.isEmpty()) return
            // Oynatma durumu yalnız ana iş parçacığında okunur; kapak işi arka plana verilir.
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { update(app) }
                return
            }
            val track = MusicController.current
            val music = track != null
            val playing = PlaybackHub.target?.isPlaying == true
            worker.execute {
                val views = RemoteViews(app.packageName, R.layout.widget_music)
                views.setTextViewText(R.id.widget_title, if (music) track?.title ?: "" else "OpenPlayer")
                views.setTextViewText(R.id.widget_artist, if (music) track?.artist ?: "" else Tx.s(R.string.musicwidge_001))
                views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                var bmp: android.graphics.Bitmap? = null
                if (music && Build.VERSION.SDK_INT >= 29) {
                    val uri = track?.uri
                    if (uri != null) bmp = runCatching { app.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
                }
                if (bmp != null) views.setImageViewBitmap(R.id.widget_art, bmp)
                else views.setImageViewResource(R.id.widget_art, R.drawable.ic_music_note)
                views.setOnClickPendingIntent(R.id.widget_prev, pending(app, ACTION_PREV, 1))
                views.setOnClickPendingIntent(R.id.widget_play, pending(app, ACTION_PLAY, 2))
                views.setOnClickPendingIntent(R.id.widget_next, pending(app, ACTION_NEXT, 3))
                views.setOnClickPendingIntent(R.id.widget_root, openApp(app))
                runCatching { mgr.updateAppWidget(ids, views) }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Widget, motoru o an kullanan oynatıcıyı denetler (eskiden de ortak oynatıcıyı denetliyordu).
        val t = PlaybackHub.target
        when (intent.action) {
            ACTION_PLAY -> t?.let { if (it.playWhenReady) it.pause() else it.play() }
            ACTION_NEXT -> t?.next()
            ACTION_PREV -> t?.previous()
            else -> return
        }
        update(context)
    }
}
