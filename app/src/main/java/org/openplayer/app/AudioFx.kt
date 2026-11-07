package org.openplayer.app

import android.content.Context

/**
 * Ses paneli denetimi: beş bantlı EQ, bas, genişlik, yankı ve ses artırma. Değerler eski ayar
 * anahtarlarında saklanır, sesi [MpvAudio] zinciri işler; cihazın ses efektlerine ve ses
 * oturumuna bağlı değildir, bu yüzden her cihazda çalışır.
 */
class AudioFx(context: Context) {

    private val settings = AppSettings.get(context)

    companion object {
        val reverbLabels = listOf(Tx.s(R.string.audiofx_004), Tx.s(R.string.audiofx_003), Tx.s(R.string.audiofx_007), Tx.s(R.string.audiofx_002), Tx.s(R.string.audiofx_006), Tx.s(R.string.audiofx_001), Tx.s(R.string.audiofx_005))
    }

    private fun refresh() = MpvAudio.apply(settings)

    /** Kayıtlı değerler; bant sayısı tutmuyorsa düz başlar. */
    private fun levels(): List<Int> =
        settings.eqLevels.takeIf { it.size == bandCount } ?: List(bandCount) { 0 }

    val reverbId: Int get() = settings.reverbPreset // MPV-REMOVE

    fun setReverb(preset: Int) {
        settings.reverbPreset = preset
        refresh()
    }

    val available: Boolean get() = true

    val bandCount: Int get() = MpvAudio.DEVICE_EQ_FREQS.size

    /** Bant seviyesi aralığı (milibel): [min, max]. */
    val levelRange: Pair<Int, Int> get() = MpvAudio.DEVICE_EQ_MIN_MB to MpvAudio.DEVICE_EQ_MAX_MB

    fun centerFreqHz(band: Int): Int = MpvAudio.DEVICE_EQ_FREQS.getOrElse(band) { 0 }

    fun level(band: Int): Int = levels().getOrElse(band) { 0 }

    fun attach(sessionId: Int) = Unit // MPV-REMOVE

    val bassAvailable: Boolean get() = true
    val virtualizerAvailable: Boolean get() = true

    fun setBass(strength: Int) {
        settings.bassStrength = strength
        refresh()
    }

    fun setVirtualizer(strength: Int) {
        settings.virtualizerStrength = strength
        refresh()
    }

    val presetNames: List<String> get() = MpvAudio.DEVICE_EQ_PRESETS.map { it.first }

    fun usePreset(index: Int) {
        val preset = MpvAudio.DEVICE_EQ_PRESETS.getOrNull(index)?.second ?: return
        settings.eqLevels = preset.map { it * 100 }
        settings.eqEnabled = true
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        settings.eqEnabled = enabled
        refresh()
    }

    fun setLevel(band: Int, millibel: Int) {
        if (band !in 0 until bandCount) return
        settings.eqLevels = levels().toMutableList().also { it[band] = millibel }
        refresh()
    }

    fun reset() {
        settings.eqLevels = List(bandCount) { 0 }
        settings.bassStrength = 0
        settings.virtualizerStrength = 0
        refresh()
    }

    fun setBoostPercent(percent: Int) {
        settings.volumeBoostPercent = percent.coerceIn(100, 200)
        refresh()
    }

    fun release() = Unit
}
