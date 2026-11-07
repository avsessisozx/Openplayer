package org.openplayer.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.audio.AudioProcessor // MPV-REMOVE
import androidx.media3.common.audio.BaseAudioProcessor // MPV-REMOVE
import androidx.media3.common.util.UnstableApi // MPV-REMOVE
import java.nio.ByteBuffer // MPV-REMOVE

/** Ses/görüntü eşitleme: pozitif gecikme sesi geciktirir, negatif öne alır (mpv `audio-delay`). */
object AudioDelay {

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

    /** Ayar değiştiğinde çağrılır; sarma gerektirmez, oynarken etkili olur. */
    fun apply(context: Context) = MpvAudio.apply(context)

    @Volatile var current: AudioDelayProcessor? = null // MPV-REMOVE
}

@UnstableApi // MPV-REMOVE
class AudioDelayProcessor : BaseAudioProcessor() { // MPV-REMOVE
    var delayMs = 0L // MPV-REMOVE
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = AudioProcessor.AudioFormat.NOT_SET // MPV-REMOVE
    override fun queueInput(inputBuffer: ByteBuffer) = Unit // MPV-REMOVE
} // MPV-REMOVE
