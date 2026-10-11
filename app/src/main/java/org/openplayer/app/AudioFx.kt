package org.openplayer.app

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** Ekolayzır ve yazılımsal ses artırma (%200'e kadar). */
class AudioFx(context: Context) {

    private val settings = AppSettings.get(context)
    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null

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
        eq = null
        loud = null
    }
}
