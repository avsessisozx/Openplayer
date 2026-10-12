package org.openplayer.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import kotlin.math.abs
import kotlin.math.min

/**
 * Ses/görüntü eşitleme. Pozitif gecikme sesi geciktirir (başa sessizlik + halka tampon);
 * negatif gecikme sesin başındaki kısmı atarak sesi öne alır. Değişiklik bir "flush"
 * (sarma/konum yenileme) ile uygulanır.
 */
@UnstableApi
class AudioDelayProcessor : BaseAudioProcessor() {

    @Volatile
    var delayMs: Long = 0L

    private var ring = ByteArray(0)
    private var ringPos = 0
    private var skipBytes = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (Util.isEncodingLinearPcm(inputAudioFormat.encoding) && inputAudioFormat.encoding != C.ENCODING_INVALID) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush() {
        val fmt = inputAudioFormat
        val frameSize = Util.getPcmFrameSize(fmt.encoding, fmt.channelCount).coerceAtLeast(1)
        val frames = (abs(delayMs) * fmt.sampleRate / 1000L).toInt().coerceAtMost(fmt.sampleRate * 5)
        if (delayMs > 0) {
            ring = ByteArray(frames * frameSize)
            ringPos = 0
            skipBytes = 0
        } else {
            ring = ByteArray(0)
            ringPos = 0
            skipBytes = frames * frameSize
        }
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        if (ring.isNotEmpty()) {
            val out = replaceOutputBuffer(n)
            val tmp = ByteArray(n)
            inputBuffer.get(tmp)
            for (i in 0 until n) {
                out.put(ring[ringPos])
                ring[ringPos] = tmp[i]
                ringPos = (ringPos + 1) % ring.size
            }
            out.flip()
        } else if (skipBytes > 0) {
            val drop = min(skipBytes, n)
            inputBuffer.position(inputBuffer.position() + drop)
            skipBytes -= drop
            val rest = inputBuffer.remaining()
            if (rest > 0) {
                val out = replaceOutputBuffer(rest)
                out.put(inputBuffer)
                out.flip()
            }
        } else {
            val out = replaceOutputBuffer(n)
            out.put(inputBuffer)
            out.flip()
        }
    }

    override fun onQueueEndOfStream() {
        if (ring.isNotEmpty()) {
            val out = replaceOutputBuffer(ring.size)
            for (i in ring.indices) out.put(ring[(ringPos + i) % ring.size])
            out.flip()
            ring = ByteArray(0)
        }
    }

    override fun onReset() {
        ring = ByteArray(0)
        ringPos = 0
        skipBytes = 0
    }
}

@UnstableApi
object AudioDelay {
    @Volatile
    var current: AudioDelayProcessor? = null

    fun isBluetoothOutput(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    fun effectiveDelayMs(context: Context): Long {
        val s = AppSettings.get(context)
        return s.audioDelayMs + if (isBluetoothOutput(context)) s.bluetoothDelayMs else 0L
    }

    /** Ayar değiştiğinde çağrılır: yeni gecikmeyi uygular ve ses hattını yenilemek için aynı konuma sarar. */
    fun apply(context: Context) {
        val p = current ?: return
        val d = effectiveDelayMs(context)
        if (p.delayMs == d) return
        p.delayMs = d
        PlayerHolder.player?.let { it.seekTo(it.currentPosition) }
    }
}
