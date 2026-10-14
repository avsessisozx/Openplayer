package org.openplayer.app

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb

/** Ekolayzır ve yazılımsal ses artırma (%200'e kadar). */
class AudioFx(context: Context) {

    private val settings = AppSettings.get(context)
    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var reverb: PresetReverb? = null

    companion object {
        val reverbLabels = listOf("Kapalı", "Küçük oda", "Orta oda", "Büyük oda", "Orta salon", "Büyük salon", "Sahne (plate)")
    }

    /** Oynatıcıya bağlanacak yardımcı efekt (yankı) kimliği; 0 = yok. */
    val reverbId: Int get() = if (settings.reverbPreset > 0) runCatching { reverb?.id ?: 0 }.getOrDefault(0) else 0

    fun setReverb(preset: Int): Int {
        settings.reverbPreset = preset
        applyReverb()
        return reverbId
    }

    private fun applyReverb() {
        val preset = settings.reverbPreset
        if (preset == 0) {
            runCatching { reverb?.enabled = false }
            return
        }
        if (reverb == null) reverb = runCatching { PresetReverb(1, 0) }.getOrNull()
        runCatching {
            reverb?.preset = preset.toShort()
            reverb?.enabled = true
        }
    }

    val available: Boolean get() = eq != null

    val bandCount: Int get() = eq?.numberOfBands?.toInt() ?: 0

    /** Bant seviyesi aralığı (milibel): [min, max]. */
    val levelRange: Pair<Int, Int>
        get() = eq?.bandLevelRange?.let { it[0].toInt() to it[1].toInt() } ?: (-1500 to 1500)

    fun centerFreqHz(band: Int): Int = (eq?.getCenterFreq(band.toShort()) ?: 0) / 1000

    fun level(band: Int): Int = eq?.getBandLevel(band.toShort())?.toInt() ?: 0

    fun attach(sessionId: Int) {
        release()
        if (sessionId == 0) return
        runCatching {
            eq = Equalizer(0, sessionId).also { e ->
                val saved = settings.eqLevels
                if (saved.size == e.numberOfBands.toInt()) {
                    saved.forEachIndexed { i, v -> e.setBandLevel(i.toShort(), v.toShort()) }
                }
                e.enabled = settings.eqEnabled
            }
        }
        runCatching {
            loud = LoudnessEnhancer(sessionId).also { applyBoost(it) }
        }
        runCatching {
            bass = BassBoost(0, sessionId).also { b ->
                b.setStrength(settings.bassStrength.toShort())
                b.enabled = settings.bassStrength > 0
            }
        }
        runCatching {
            virt = Virtualizer(0, sessionId).also { v ->
                v.setStrength(settings.virtualizerStrength.toShort())
                v.enabled = settings.virtualizerStrength > 0
            }
        }
        applyReverb()
    }

    val bassAvailable: Boolean get() = bass != null
    val virtualizerAvailable: Boolean get() = virt != null

    fun setBass(strength: Int) {
        settings.bassStrength = strength
        runCatching {
            bass?.setStrength(settings.bassStrength.toShort())
            bass?.enabled = settings.bassStrength > 0
        }
    }

    fun setVirtualizer(strength: Int) {
        settings.virtualizerStrength = strength
        runCatching {
            virt?.setStrength(settings.virtualizerStrength.toShort())
            virt?.enabled = settings.virtualizerStrength > 0
        }
    }

    val presetNames: List<String>
        get() = eq?.let { e -> List(e.numberOfPresets.toInt()) { e.getPresetName(it.toShort()) } } ?: emptyList()

    fun usePreset(index: Int) {
        val e = eq ?: return
        runCatching {
            e.usePreset(index.toShort())
            settings.eqLevels = List(bandCount) { level(it) }
            settings.eqEnabled = true
            e.enabled = true
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.eqEnabled = enabled
        eq?.enabled = enabled
    }

    fun setLevel(band: Int, millibel: Int) {
        eq?.setBandLevel(band.toShort(), millibel.toShort())
        settings.eqLevels = List(bandCount) { level(it) }
    }

    fun reset() {
        for (i in 0 until bandCount) eq?.setBandLevel(i.toShort(), 0)
        settings.eqLevels = List(bandCount) { 0 }
        setBass(0)
        setVirtualizer(0)
    }

    fun setBoostPercent(percent: Int) {
        settings.volumeBoostPercent = percent.coerceIn(100, 200)
        loud?.let { applyBoost(it) }
    }

    private fun applyBoost(l: LoudnessEnhancer) {
        val percent = settings.volumeBoostPercent
        l.setTargetGain((percent - 100) * 15) // %200 ≈ +1500 mB
        l.enabled = percent > 100
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { loud?.release() }
        runCatching { bass?.release() }
        runCatching { virt?.release() }
        runCatching { reverb?.release() }
        reverb = null
        eq = null
        loud = null
        bass = null
        virt = null
    }
}
