package org.openplayer.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Stereo denge (sol/sağ) ve mono karıştırma. Yalnızca 16-bit stereo PCM üzerinde çalışır; canlı değişir. */
@UnstableApi
class BalanceProcessor : BaseAudioProcessor() {

    /** -1 (tam sol) .. 1 (tam sağ). */
    @Volatile var balance = 0f
    @Volatile var mono = false

    /** Sol ve sağ kanalı yer değiştirir (ters stereo). */
    @Volatile var swap = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val b = balance
        val lg = if (b > 0f) 1f - b else 1f
        val rg = if (b < 0f) 1f + b else 1f
        val m = mono
        val sw = swap
        while (inputBuffer.remaining() >= 4) {
            val first = inputBuffer.short.toInt()
            val second = inputBuffer.short.toInt()
            val l = if (sw) second else first
            val r = if (sw) first else second
            val outL: Float
            val outR: Float
            if (m) {
                val mid = (l + r) * 0.5f
                outL = mid * lg
                outR = mid * rg
            } else {
                outL = l * lg
                outR = r * rg
            }
            out.putShort(outL.toInt().coerceIn(-32768, 32767).toShort())
            out.putShort(outR.toInt().coerceIn(-32768, 32767).toShort())
        }
        // Artakalan (tam kare olmayan) baytlar aynen aktarılır.
        if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        out.flip()
    }
}

@UnstableApi
object AudioBalance {
    @Volatile var current: BalanceProcessor? = null

    fun attach(p: BalanceProcessor, settings: AppSettings) {
        p.balance = settings.audioBalance / 100f
        p.mono = settings.monoMix
        p.swap = settings.reverseStereo
        current = p
    }

    fun update(settings: AppSettings) {
        current?.let {
            it.balance = settings.audioBalance / 100f
            it.mono = settings.monoMix
            it.swap = settings.reverseStereo
        }
    }
}
