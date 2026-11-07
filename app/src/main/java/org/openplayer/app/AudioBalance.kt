package org.openplayer.app

import androidx.media3.common.audio.AudioProcessor // MPV-REMOVE
import androidx.media3.common.audio.BaseAudioProcessor // MPV-REMOVE
import androidx.media3.common.util.UnstableApi // MPV-REMOVE
import java.nio.ByteBuffer // MPV-REMOVE

/** Stereo denge, mono ve ters stereo: `audioBalance`, `monoMix`, `reverseStereo` ayarlarını [MpvAudio] uygular. */
object AudioBalance {
    /** Ayarlar değişince çağrılır. */
    fun update(settings: AppSettings) = MpvAudio.apply(settings)

    @UnstableApi fun attach(p: BalanceProcessor, settings: AppSettings) = Unit // MPV-REMOVE
}

@UnstableApi // MPV-REMOVE
class BalanceProcessor : BaseAudioProcessor() { // MPV-REMOVE
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = AudioProcessor.AudioFormat.NOT_SET // MPV-REMOVE
    override fun queueInput(inputBuffer: ByteBuffer) = Unit // MPV-REMOVE
} // MPV-REMOVE
