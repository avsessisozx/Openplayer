package org.openplayer.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cihazın donanım ekolayzırından bağımsız, 10 bantlı grafik EQ. Her bant bir "peaking"
 * biquad IIR süzgecidir (RBJ cookbook); bantlar seri bağlanır. 16-bit PCM üzerinde,
 * kanal sayısından bağımsız çalışır; kazançlar canlı değişir. ExoPlayer'ın ses hattına
 * [PlayerHolder] içinden [AudioProcessor] olarak eklenir; hem video hem müziğe uygulanır.
 */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {

    @Volatile var enabled = false
    private val gains = FloatArray(AudioEq.FREQS.size) // bant kazançları (dB)
    @Volatile private var dirty = true

    private var channels = 0
    private var sampleRate = 0
    private var coeff = Array(AudioEq.FREQS.size) { FloatArray(5) } // b0,b1,b2,a1,a2 (a0'a normalize)
    private var z1 = Array(0) { FloatArray(0) } // [kanal][bant] TDF2 durumu
    private var z2 = Array(0) { FloatArray(0) }

    fun setGains(db: List<Int>) {
        for (i in gains.indices) gains[i] = (db.getOrNull(i) ?: 0).toFloat()
        dirty = true
    }

    fun setGain(band: Int, db: Int) {
        if (band in gains.indices) {
            gains[band] = db.toFloat()
            dirty = true
        }
    }

    private fun active(): Boolean = enabled && gains.any { it != 0f }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            sampleRate = inputAudioFormat.sampleRate
            dirty = true
            val b = AudioEq.FREQS.size
            z1 = Array(channels) { FloatArray(b) }
            z2 = Array(channels) { FloatArray(b) }
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun recompute() {
        val fs = sampleRate.toFloat().coerceAtLeast(1f)
        val q = 1.41f // ~1 oktav bant genişliği
        for (i in AudioEq.FREQS.indices) {
            val f0 = AudioEq.FREQS[i].toFloat()
            val a = 10.0.pow(gains[i] / 40.0).toFloat() // dB -> genlik (peaking)
            val w0 = (2.0 * PI * f0 / fs).toFloat()
            val cw = cos(w0)
            val alpha = sin(w0) / (2f * q)
            val b0 = 1f + alpha * a
            val b1 = -2f * cw
            val b2 = 1f - alpha * a
            val a0 = 1f + alpha / a
            val a1 = -2f * cw
            val a2 = 1f - alpha / a
            coeff[i][0] = b0 / a0
            coeff[i][1] = b1 / a0
            coeff[i][2] = b2 / a0
            coeff[i][3] = a1 / a0
            coeff[i][4] = a2 / a0
        }
        dirty = false
    }

    override fun onFlush() = clearState()
    override fun onReset() = clearState()

    private fun clearState() {
        for (c in z1.indices) {
            z1[c].fill(0f)
            z2[c].fill(0f)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        if (!active() || channels <= 0) {
            // Kapalı veya düz: dokunmadan aktar.
            replaceOutputBuffer(n).put(inputBuffer).flip()
            return
        }
        if (dirty) recompute()
        val out = replaceOutputBuffer(n)
        val bands = AudioEq.FREQS.size
        var ch = 0
        // Tamponlar kare hizalıdır; ch her tamponda 0'dan başlar, süzgeç durumu kalıcıdır.
        while (inputBuffer.remaining() >= 2) {
            var x = inputBuffer.short.toFloat()
            val zc1 = z1[ch]
            val zc2 = z2[ch]
            for (bnd in 0 until bands) {
                val co = coeff[bnd]
                val y = co[0] * x + zc1[bnd]
                zc1[bnd] = co[1] * x - co[3] * y + zc2[bnd]
                zc2[bnd] = co[2] * x - co[4] * y
                x = y
            }
            out.putShort(x.toInt().coerceIn(-32768, 32767).toShort())
            ch++
            if (ch >= channels) ch = 0
        }
        if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        out.flip()
    }
}

@UnstableApi
object AudioEq {
    /** 10 bant orta frekansları (Hz). */
    val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** dB kazanç sınırı (± bu değer). */
    const val MAX_DB = 15

    @Volatile var current: EqProcessor? = null

    fun attach(p: EqProcessor, settings: AppSettings) {
        p.enabled = settings.graphicEqEnabled
        p.setGains(settings.graphicEqBands)
        current = p
    }

    fun update(settings: AppSettings) {
        current?.let {
            it.enabled = settings.graphicEqEnabled
            it.setGains(settings.graphicEqBands)
        }
    }
}
