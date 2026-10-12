package org.openplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.util.concurrent.Executors

/** Albüm kapaklarını arka planda yükler ve önbellekte tutar. */
object ArtLoader {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun key(t: MusicTrack, size: Int) = (if (t.albumId > 0) "a${t.albumId}" else "t${t.id}") + "_$size"

    fun roundCorners(view: View, radiusDp: Int) {
        val r = view.context.dp(radiusDp).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        view.clipToOutline = true
    }

    private fun placeholder(iv: ImageView) {
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = iv.context.dp(10)
        iv.setPadding(pad, pad, pad, pad)
        iv.setImageResource(R.drawable.ic_music_note)
        iv.setColorFilter(UiColors.textSecondary)
        iv.setBackgroundColor(UiColors.surface)
    }

    fun load(iv: ImageView, track: MusicTrack?, sizePx: Int) {
        if (track == null) {
            iv.tag = null
            placeholder(iv)
            return
        }
        val k = key(track, sizePx)
        iv.tag = k
        val hit = cache.get(k)
        if (hit != null) {
            show(iv, hit)
            return
        }
        placeholder(iv)
        if (k in missing) return
        val app = iv.context.applicationContext
        executor.execute {
            val bmp = fetch(app, track, sizePx)
            if (bmp == null) {
                missing += k
                return@execute
            }
            cache.put(k, bmp)
            main.post { if (iv.tag == k) show(iv, bmp) }
        }
    }

    private fun show(iv: ImageView, bmp: Bitmap) {
        iv.clearColorFilter()
        iv.setPadding(0, 0, 0, 0)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(0)
        iv.setImageBitmap(bmp)
    }

    private fun fetch(app: Context, t: MusicTrack, sizePx: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            app.contentResolver.loadThumbnail(t.uri, Size(sizePx, sizePx), null)
        } else {
            app.contentResolver.openInputStream(t.artworkUri)?.use { s ->
                val bytes = s.readBytes()
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= sizePx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()
}

/** Ortak küçük yardımcılar. */
object AppArt {
    fun round(view: View, radiusDp: Int) = ArtLoader.roundCorners(view, radiusDp)
}
