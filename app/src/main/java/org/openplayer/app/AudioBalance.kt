package org.openplayer.app

/** Stereo denge, mono ve ters stereo: `audioBalance`, `monoMix`, `reverseStereo` ayarlarını [MpvAudio] uygular. */
object AudioBalance {
    /** Ayarlar değişince çağrılır. */
    fun update(settings: AppSettings) = MpvAudio.apply(settings)
}

