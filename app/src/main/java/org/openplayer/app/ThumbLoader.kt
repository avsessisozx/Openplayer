package org.openplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Video küçük resimlerini yükler. Önce sistemin küçük resim önbelleğini (galeri uygulamalarının
 * kullandığı) dener, olmazsa videodan kare çıkarır. Ekrandan çıkan satırların istekleri atlanır.
 */
object ThumbLoader {

    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, video: VideoItem, target: ImageView) {
        target.tag = video.id
        val cached = cache.get(video.id)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageDrawable(null)
        val app = context.applicationContext
        pool.execute {
            if (target.tag != video.id) return@execute // satır başka videoya geçti
            val bitmap = decode(app, video) ?: return@execute
            cache.put(video.id, bitmap)
            main.post { if (target.tag == video.id) target.setImageBitmap(bitmap) }
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(context: Context, video: VideoItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { return context.contentResolver.loadThumbnail(video.uri, Size(384, 216), null) }
        } else {
            runCatching {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )?.let { return it }
            }
        }
        return frameFallback(context, video)
    }

    private fun frameFallback(context: Context, video: VideoItem): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, video.uri)
            val atUs = (video.durationMs * 1000 / 10).coerceIn(1_000_000L, 30_000_000L)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216)
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}
