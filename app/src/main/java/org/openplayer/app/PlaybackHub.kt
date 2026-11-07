package org.openplayer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Motoru o an kullanan oynatıcı. Motor tektir, aynı anda yalnız bir hedef olur: müzikte
 * [MusicController], videoda video ekranı. Yöntemler ana iş parçacığında çağrılır.
 */
interface PlaybackTarget {
    val isMusic: Boolean get() = false
    val title: String
    val artist: String get() = ""
    val album: String get() = ""
    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    /** Kullanıcı niyeti: tamponda beklerken ve parça geçişinde de true kalır. */
    val playWhenReady: Boolean
    val speed: Float get() = 1f
    val canSkipNext: Boolean get() = false
    val canSkipPrevious: Boolean get() = false

    fun play()
    fun pause()
    fun next() {}
    fun previous() {}
    fun seekTo(ms: Long)

    /** Bildirime dokununca açılacak ekran. */
    fun openIntent(context: Context): Intent =
        Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Kapak değişimini ayırt eden anahtar; aynıysa kapak yeniden okunmaz. */
    val artworkKey: String get() = title
    /** Arka plan iş parçacığında çağrılır. */
    fun artwork(context: Context, sizePx: Int): Bitmap? = null

    /** Geçici ses kısma: 1 = normal. Yalnız API 26 altında çağrılır; üstünde sistem kendisi kısar. */
    fun setDuck(factor: Float) {}

    /** Motor olayları, yalnız etkin hedefe gelir. */
    fun onEngineEvent(event: MpvEvent) {}

    /** Motor başka hedefe geçti ya da hedef kaldırıldı. */
    fun onDetached() {}
}

/** Ses odağı değişiminde yapılacaklar. */
data class FocusStep(val pause: Boolean = false, val resume: Boolean = false, val duck: Float? = null)

/** Ses odağı kararları; Android'den bağımsızdır. */
class AudioFocusPolicy(private val sdk: Int) {
    companion object {
        const val DUCK_FACTOR = 0.2f
    }

    /** Geçici kayıpta duraklatıldı; odak dönünce sürecek. */
    var resumeOnGain = false
        private set
    private var ducked = false

    fun onChange(change: Int, playing: Boolean): FocusStep = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> {
            val resume = resumeOnGain
            val duck = if (ducked) 1f else null
            reset()
            FocusStep(resume = resume, duck = duck)
        }
        AudioManager.AUDIOFOCUS_LOSS -> {
            resumeOnGain = false
            FocusStep(pause = true)
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            if (playing) resumeOnGain = true
            FocusStep(pause = playing)
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            if (sdk >= Build.VERSION_CODES.O || !playing) FocusStep()
            else {
                ducked = true
                FocusStep(duck = DUCK_FACTOR)
            }
        else -> FocusStep()
    }

    fun reset() {
        resumeOnGain = false
        ducked = false
    }
}

/**
 * Tek motorun uygulama genelindeki yöneticisi: etkin hedefi tutar, motor olaylarını ona iletir,
 * ses odağı ve kulaklık çıkınca duraklatmayı yönetir. Servis gerekmez, böylece ön plandaki video da
 * odak alır; [PlaybackService] bildirim ve medya oturumunu buradan besler.
 * Yalnız ana iş parçacığından kullanılır.
 */
object PlaybackHub {

    private const val ABANDON_DELAY_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val policy = AudioFocusPolicy(Build.VERSION.SDK_INT)

    private var app: Context? = null
    private var bound: MpvEngine? = null
    private var focusHeld = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    var target: PlaybackTarget? = null
        private set

    /** Hedef ya da durumu değişince çalışır. Kaydı kaldırmak çağıranın işidir. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** [next] motoru devralır; önceki hedefe [PlaybackTarget.onDetached] gider. */
    fun setTarget(context: Context, next: PlaybackTarget) {
        val c = context.applicationContext
        app = c
        bind(MpvEngines.get(c))
        if (target === next) {
            notifyChanged()
            return
        }
        val old = target
        target = next
        policy.reset()
        old?.onDetached()
        notifyChanged()
    }

    /** [t] etkin hedefse kaldırır; hedef kalmayınca servis kendini kapatır. */
    fun clearTarget(t: PlaybackTarget) {
        if (target !== t) return
        target = null
        bound?.removeListener(engineListener)
        bound = null
        policy.reset()
        t.onDetached()
        notifyChanged()
    }

    /** Hedefin durumu motor olayı olmadan değiştiğinde çağrılır (duraklatma, parça değişimi). */
    fun notifyChanged() {
        val t = target
        main.removeCallbacks(abandonRunnable)
        if (t != null && t.playWhenReady) {
            requestFocus(t)
            registerNoisy()
        } else {
            unregisterNoisy()
            // Parça geçişinde odak bırakılıp alınmasın diye gecikmeli; geçici kayıpta odak dönüşü beklenir.
            if (t == null) abandonFocus()
            else if (!policy.resumeOnGain) main.postDelayed(abandonRunnable, ABANDON_DELAY_MS)
        }
        listeners.forEach { it() }
    }

    private fun bind(engine: MpvEngine) {
        if (bound === engine) return
        bound?.removeListener(engineListener)
        engine.addListener(engineListener)
        bound = engine
    }

    private val engineListener: (MpvEvent) -> Unit = { e ->
        target?.onEngineEvent(e)
        when (e) {
            is MpvEvent.PlayingChanged, is MpvEvent.Buffering, is MpvEvent.Duration,
            MpvEvent.FileLoaded, MpvEvent.PlaybackRestarted -> notifyChanged()
            else -> Unit
        }
    }

    // ------------------------------------------------------------ ses odağı

    private val abandonRunnable = Runnable { abandonFocus() }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Eski sürümler dinleyiciyi başka iş parçacığında çağırabilir.
        main.post { onFocusChange(change) }
    }

    private fun onFocusChange(change: Int) {
        val t = target ?: return
        val step = policy.onChange(change, t.playWhenReady)
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> focusHeld = true
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusHeld = false
        }
        step.duck?.let { t.setDuck(it) }
        if (step.pause) t.pause()
        if (step.resume) t.play()
    }

    private fun audioManager(): AudioManager? =
        app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun requestFocus(t: PlaybackTarget) {
        val c = app ?: return
        if (!AppSettings.get(c).audioFocus) {
            abandonFocus()
            return
        }
        if (focusHeld) return
        val am = audioManager() ?: return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (t.isMusic) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusHeld = true
            policy.reset()
        } else {
            // Örneğin telefon görüşmesi sürüyor.
            t.pause()
        }
    }

    private fun abandonFocus() {
        main.removeCallbacks(abandonRunnable)
        focusHeld = false
        val am = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    // ------------------------------------------------------------ kulaklık çıkınca duraklat

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (AppSettings.get(context).pauseOnNoisy) target?.pause()
        }
    }

    private fun registerNoisy() {
        val c = app ?: return
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            c, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        noisyRegistered = false
        runCatching { app?.unregisterReceiver(noisyReceiver) }
    }
}
