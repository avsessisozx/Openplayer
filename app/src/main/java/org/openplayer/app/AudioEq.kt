package org.openplayer.app

import androidx.media3.common.audio.AudioProcessor // MPV-REMOVE
import androidx.media3.common.audio.BaseAudioProcessor // MPV-REMOVE
import androidx.media3.common.util.UnstableApi // MPV-REMOVE
import java.nio.ByteBuffer // MPV-REMOVE

/**
 * Cihazdan bağımsız 10 bantlı EQ'nun sabitleri. Süzgeçler [MpvAudio] zincirinde `equalizer` ile
 * kurulur; ayarlar `AppSettings.graphicEq*` içinde kalır.
 *
 * Grafik kipte bant frekansları sabittir ([AudioEq.FREQS]), yalnız kazançlar ayarlanır.
 * Parametrik kipte her bandın frekansı ve Q'su da ayarlanabilir.
 */
object AudioEq {
    /** Grafik kipin sabit 10 bant orta frekansları (Hz); parametrik kipin de başlangıcı. */
    val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** dB kazanç sınırı (± bu değer). */
    const val MAX_DB = 15
    const val DEFAULT_Q = 1.41f
    const val MIN_Q = 0.3f
    const val MAX_Q = 10f
    const val MIN_FREQ = 20
    const val MAX_FREQ = 20000

    /** Ayarlar değişince çağrılır. */
    fun update(settings: AppSettings) = MpvAudio.apply(settings)

    @Volatile var current: EqProcessor? = null // MPV-REMOVE
    @UnstableApi fun attach(p: EqProcessor, settings: AppSettings) = Unit // MPV-REMOVE
}

@UnstableApi // MPV-REMOVE
class EqProcessor : BaseAudioProcessor() { // MPV-REMOVE
    var enabled = false // MPV-REMOVE
    fun setGains(db: List<Int>) = Unit // MPV-REMOVE
    fun setGain(band: Int, db: Int) = Unit // MPV-REMOVE
    fun setBand(band: Int, freqHz: Float, q: Float) = Unit // MPV-REMOVE
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = AudioProcessor.AudioFormat.NOT_SET // MPV-REMOVE
    override fun queueInput(inputBuffer: ByteBuffer) = Unit // MPV-REMOVE
} // MPV-REMOVE
