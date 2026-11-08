package org.openplayer.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

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
}

