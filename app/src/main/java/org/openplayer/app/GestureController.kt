package org.openplayer.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Dikey kaydırmada bir bölgeye atanabilecek işlevler. */
enum class GestureAction { NONE, BRIGHTNESS, VOLUME, SPEED }

/** Ekranı 2x2 böler: sol/sağ x üst/alt. Her bölgenin işlevi ayarlanabilir. */
class GesturePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("gestures", Context.MODE_PRIVATE)

    private val defaults = mapOf(
        "left_top" to GestureAction.BRIGHTNESS,
        "left_bottom" to GestureAction.BRIGHTNESS,
        "right_top" to GestureAction.VOLUME,
        "right_bottom" to GestureAction.VOLUME,
    )

    fun get(zone: String): GestureAction =
        prefs.getString(zone, null)
            ?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() }
            ?: defaults.getValue(zone)

    fun set(zone: String, action: GestureAction) {
        prefs.edit().putString(zone, action.name).apply()
    }
}

/**
 * Yatay kaydırma: ileri/geri sarma.
 * Dikey kaydırma: dokunulan bölgeye atanan işlev (parlaklık, ses, hız).
 * Tek dokunma: kontrolleri göster/gizle. Çift dokunma: oynat/duraklat.
 */
class GestureController(
    private val activity: Activity,
    private val player: ExoPlayer,
    private val prefs: GesturePrefs,
    private val onHud: (String) -> Unit,
    private val onToggleControls: () -> Unit,
) : View.OnTouchListener {

    private enum class Mode { NONE, HORIZONTAL, VERTICAL }

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mode = Mode.NONE
    private var zone = "left_top"
    private var startPos = 0L
    private var startVolume = 0
    private var startBrightness = 0.5f
    private var startSpeed = 1f
    private var seekTarget = 0L
    private var width = 1
    private var height = 1

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            mode = Mode.NONE
            zone = (if (e.x < width / 2f) "left" else "right") +
                    "_" + (if (e.y < height / 2f) "top" else "bottom")
            startPos = player.currentPosition
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val b = activity.window.attributes.screenBrightness
            startBrightness = if (b < 0f) 0.5f else b
            startSpeed = player.playbackParameters.speed
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e1 == null) return false
            val totalX = e2.x - e1.x
            val totalY = e2.y - e1.y
            if (mode == Mode.NONE) {
                if (abs(totalX) < 24 && abs(totalY) < 24) return true
                mode = if (abs(totalX) > abs(totalY)) Mode.HORIZONTAL else Mode.VERTICAL
            }
            when (mode) {
                Mode.HORIZONTAL -> {
                    val delta = (totalX / width * 90_000f).toLong()
                    val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    seekTarget = (startPos + delta).coerceIn(0L, duration)
                    onHud("${format(seekTarget)}  (${if (delta >= 0) "+" else "-"}${abs(delta) / 1000} sn)")
                }
                Mode.VERTICAL -> applyVertical(-totalY / height)
                Mode.NONE -> Unit
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onToggleControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            player.playWhenReady = !player.playWhenReady
            return true
        }
    })

    private fun applyVertical(fraction: Float) {
        when (prefs.get(zone)) {
            GestureAction.BRIGHTNESS -> {
                val value = (startBrightness + fraction).coerceIn(0.01f, 1f)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                onHud("Parlaklık ${(value * 100).roundToInt()}%")
            }
            GestureAction.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val value = (startVolume + fraction * max).roundToInt().coerceIn(0, max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                onHud("Ses $value/$max")
            }
            GestureAction.SPEED -> {
                val value = ((startSpeed + fraction * 2f) * 20).roundToInt() / 20f
                player.setPlaybackSpeed(value.coerceIn(0.25f, 4f))
                onHud("Hız ${"%.2f".format(player.playbackParameters.speed)}x")
            }
            GestureAction.NONE -> Unit
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        width = v.width.coerceAtLeast(1)
        height = v.height.coerceAtLeast(1)
        val handled = detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode == Mode.HORIZONTAL) player.seekTo(seekTarget)
            mode = Mode.NONE
            onHud("")
        }
        return handled
    }

    private fun format(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
