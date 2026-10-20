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
 * Cihazın donanım ekolayzırından bağımsız EQ. Her bant bir "peaking" biquad IIR
 * süzgecidir (RBJ cookbook); bantlar seri bağlanır. 16-bit PCM üzerinde kanal
 * sayısından bağımsız çalışır; kazanç/frekans/Q canlı değişir. ExoPlayer ses hattına
 * [PlayerHolder] içinden [AudioProcessor] olarak eklenir; hem video hem müziğe uygulanır.
 *
 * Grafik kipte bant frekansları sabittir ([AudioEq.FREQS]), yalnız kazançlar ayarlanır.
 * Parametrik kipte her bandın frekansı ve Q'su da ayarlanabilir.
 */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {

    private val n = AudioEq.FREQS.size
    @Volatile var enabled = false
    private val gains = FloatArray(n)                                   // dB
    private val freqs = FloatArray(n) { AudioEq.FREQS[it].toFloat() }   // Hz
    private val qs = FloatArray(n) { AudioEq.DEFAULT_Q }
    @Volatile private var dirty = true

    private var channels = 0
    private var sampleRate = 0
    private var coeff = Array(n) { FloatArray(5) } // b0,b1,b2,a1,a2 (a0'a normalize)
    private var z1 = Array(0) { FloatArray(0) }    // [kanal][bant] TDF2 durumu
    private var z2 = Array(0) { FloatArray(0) }

    fun setGains(db: List<Int>) {
        for (i in 0 until n) gains[i] = (db.getOrNull(i) ?: 0).toFloat()
        dirty = true
    }

    fun setGain(band: Int, db: Int) {
        if (band in 0 until n) {
            gains[band] = db.toFloat()
            dirty = true
        }
    }

    /** Parametrik: bandın orta frekansı (Hz) ve Q'su. */
    fun setBand(band: Int, freqHz: Float, q: Float) {
        if (band in 0 until n) {
            freqs[band] = freqHz.coerceIn(AudioEq.MIN_FREQ.toFloat(), AudioEq.MAX_FREQ.toFloat())
            qs[band] = q.coerceIn(AudioEq.MIN_Q, AudioEq.MAX_Q)
            dirty = true
        }
    }

    /** freqList: Hz; qList: Q×100 (tamsayı saklama). Boş/eksik = varsayılan. */
    fun setBands(freqList: List<Int>, qList: List<Int>) {
        for (i in 0 until n) {
            freqs[i] = (freqList.getOrNull(i)?.toFloat() ?: AudioEq.FREQS[i].toFloat())
                .coerceIn(AudioEq.MIN_FREQ.toFloat(), AudioEq.MAX_FREQ.toFloat())
            qs[i] = ((qList.getOrNull(i)?.let { it / 100f }) ?: AudioEq.DEFAULT_Q)
                .coerceIn(AudioEq.MIN_Q, AudioEq.MAX_Q)
        }
        dirty = true
    }

    private fun active(): Boolean = enabled && gains.any { it != 0f }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            sampleRate = inputAudioFormat.sampleRate
            dirty = true
            z1 = Array(channels) { FloatArray(n) }
            z2 = Array(channels) { FloatArray(n) }
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun recompute() {
        val fs = sampleRate.toFloat().coerceAtLeast(1f)
        val nyquist = fs / 2f
        for (i in 0 until n) {
            val f0 = freqs[i].coerceAtMost(nyquist * 0.95f) // Nyquist yakınında kararlılık
            val q = qs[i].coerceAtLeast(0.1f)
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
        val nBytes = inputBuffer.remaining()
        if (nBytes == 0) return
        if (!active() || channels <= 0) {
            // Kapalı veya düz: dokunmadan aktar.
            replaceOutputBuffer(nBytes).put(inputBuffer).flip()
            return
        }
        if (dirty) recompute()
        val out = replaceOutputBuffer(nBytes)
        var ch = 0
        // Tamponlar kare hizalıdır; ch her tamponda 0'dan başlar, süzgeç durumu kalıcıdır.
        while (inputBuffer.remaining() >= 2) {
            var x = inputBuffer.short.toFloat()
            val zc1 = z1[ch]
            val zc2 = z2[ch]
            for (bnd in 0 until n) {
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
    /** Grafik kipin sabit 10 bant orta frekansları (Hz); parametrik kipin de başlangıcı. */
    val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** dB kazanç sınırı (± bu değer). */
    const val MAX_DB = 15
    const val DEFAULT_Q = 1.41f
    const val MIN_Q = 0.3f
    const val MAX_Q = 10f
    const val MIN_FREQ = 20
    const val MAX_FREQ = 20000

    @Volatile var current: EqProcessor? = null

    fun attach(p: EqProcessor, settings: AppSettings) {
        p.enabled = settings.graphicEqEnabled
        p.setGains(settings.graphicEqBands)
        if (settings.graphicEqParametric) p.setBands(settings.graphicEqFreqs, settings.graphicEqQs)
        current = p
    }

    fun update(settings: AppSettings) {
        current?.let {
            it.enabled = settings.graphicEqEnabled
            it.setGains(settings.graphicEqBands)
            if (settings.graphicEqParametric) it.setBands(settings.graphicEqFreqs, settings.graphicEqQs)
            else it.setBands(emptyList(), emptyList()) // varsayılan frekans/Q'ya dön
        }
    }
}
