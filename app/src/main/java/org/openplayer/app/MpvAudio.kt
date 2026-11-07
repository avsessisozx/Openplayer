package org.openplayer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/** Ses zincirini belirleyen her şey; video ve müzik aynı tanımı kullanır. */
data class MpvAudioSpec(
    /** -12..12 yarım ton; hız değişmez. */
    val pitchSemitones: Int = 0,
    /** -100 (sol) .. 100 (sağ). */
    val balance: Int = 0,
    val mono: Boolean = false,
    val reverseStereo: Boolean = false,
    /** 0..1000: stereo genişliği. */
    val virtualizer: Int = 0,
    val deviceEqEnabled: Boolean = false,
    /** Beş bant, milibel. Bant sayısı tutmazsa yok sayılır. */
    val deviceEqLevels: List<Int> = emptyList(),
    /** 0..1000. */
    val bass: Int = 0,
    val graphicEqEnabled: Boolean = false,
    /** On bant, dB. */
    val graphicEqBands: List<Int> = emptyList(),
    val graphicEqParametric: Boolean = false,
    /** Hz; boş ya da eksik = [AudioEq.FREQS]. */
    val graphicEqFreqs: List<Int> = emptyList(),
    /** Q×100; boş ya da eksik = [AudioEq.DEFAULT_Q]. */
    val graphicEqQs: List<Int> = emptyList(),
    val nightMode: Boolean = false,
    /** 0 = kapalı, 1..6 = [MpvAudio.REVERB]. */
    val reverbPreset: Int = 0,
    /** 100..200. */
    val boostPercent: Int = 100,
    /** Pozitif = ses geride. */
    val delayMs: Long = 0L,
)

/**
 * Ses efektlerinin tek giriş noktası: bütün ayarlar [apply] ile motora yazılır. Video ve müzik
 * ayrımı yoktur, ayar değişince çağırmak yeterlidir; değişmeyen özellik yeniden yazılmaz.
 *
 * Zincir, her biri kendi `lavfi` grafiğine sahip etiketli girdilerden oluşur. mpv `af` değişince
 * ayarı aynı kalan girdiyi yeniden kurmaz; böylece EQ sürgüsü yankının kuyruğunu kesmez.
 *
 * Kurallar:
 * - `volume` özelliğine yalnız bu sınıf yazar (kısma ve geçişler için [setGainFactor]). mpv'nin
 *   ses eğrisi kübiktir, bu yüzden çarpan ve artırma burada dönüştürülür.
 * - Hız `speed` özelliğindedir ve zincire girmez: `scaletempo2`yi mpv zincirin sonuna kendisi
 *   ekler, perde zinciri onunla çakışmaz. `audio-pitch-correction` açık kalmalıdır.
 */
object MpvAudio {

    /** Mevcut donanım EQ ayarıyla uyumlu beş bandın orta frekansları (Hz). */
    val DEVICE_EQ_FREQS = intArrayOf(60, 230, 910, 3600, 14000)
    const val DEVICE_EQ_MIN_MB = -1500
    const val DEVICE_EQ_MAX_MB = 1500

    /** Hazır ayarlar (dB); eskiden cihazın sunduğu AOSP listesi. */
    val DEVICE_EQ_PRESETS: List<Pair<String, IntArray>> = listOf(
        "Normal" to intArrayOf(3, 0, 0, 0, 3),
        "Classical" to intArrayOf(5, 3, -2, 4, 4),
        "Dance" to intArrayOf(6, 0, 2, 4, 1),
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Folk" to intArrayOf(3, 0, 0, 2, -1),
        "Heavy Metal" to intArrayOf(4, 1, 9, 3, 0),
        "Hip Hop" to intArrayOf(5, 3, 0, 1, 3),
        "Jazz" to intArrayOf(4, 2, -2, 2, 5),
        "Pop" to intArrayOf(-1, 2, 5, 1, -2),
        "Rock" to intArrayOf(5, 3, -1, 3, 5),
    )

    /** [MpvAudioSpec.reverbPreset] sırası: `aecho` gecikmeleri (ms) ve sönümleri. */
    val REVERB: List<Pair<List<Int>, List<Double>>> = listOf(
        emptyList<Int>() to emptyList(),
        listOf(12, 23, 37) to listOf(0.30, 0.22, 0.15),
        listOf(20, 37, 59) to listOf(0.34, 0.26, 0.18),
        listOf(31, 59, 89, 127) to listOf(0.36, 0.28, 0.21, 0.14),
        listOf(47, 89, 137, 199) to listOf(0.40, 0.31, 0.24, 0.16),
        listOf(61, 127, 211, 311) to listOf(0.44, 0.35, 0.26, 0.18),
        listOf(7, 17, 29, 43, 61) to listOf(0.35, 0.30, 0.25, 0.20, 0.15),
    )

    const val MIN_PITCH = -12
    const val MAX_PITCH = 12
    const val PREF_NIGHT = "audio_night"

    /** Perde zincirinin çalıştığı örnekleme hızı; asetrate dosyanın hızını bilmeden çalışsın diye sabit. */
    private const val PITCH_RATE = 48000
    private const val BASS_FREQ = 80
    private const val BASS_MAX_DB = 12.0
    private const val VIRTUALIZER_MAX_WIDTH = 0.5
    /** %200 artırma: eski LoudnessEnhancer ile aynı +15 dB. */
    private const val BOOST_MAX_DB = 15.0
    private const val AF_MIN_INTERVAL_MS = 120L
    private const val NIGHT_FILTER = "acompressor=threshold=0.1:ratio=4:attack=5:release=200:knee=2.8:makeup=2"
    private const val EQ_RATES = "aformat=sample_rates=44100|48000|88200|96000|176400|192000"

    // --- Saf zincir kurucu ---

    /** `af` özelliğinin değeri; bütün efektler kapalıysa boş metin. */
    fun buildChain(spec: MpvAudioSpec): String = chainEntries(spec).joinToString(",")

    /** Sıra: perde, stereo, EQ, gece modu, yankı. */
    internal fun chainEntries(spec: MpvAudioSpec): List<String> = listOfNotNull(
        pitchEntry(spec.pitchSemitones),
        stereoEntry(spec),
        eqEntry(spec),
        if (spec.nightMode) entry("opnight", NIGHT_FILTER) else null,
        reverbEntry(spec.reverbPreset),
    )

    private fun entry(label: String, graph: String, fixPts: Boolean = false): String =
        "@$label:lavfi=[$graph]" + if (fixPts) ":fix-pts=yes" else ""

    /**
     * asetrate hem perdeyi hem hızı değiştirir, atempo hızı geri alır. Baştaki aresample dosyanın
     * hızını sabitler. asetrate zaman damgasını da ölçeklediği için `fix-pts` şart: yoksa sarmadan
     * sonra ses konumu kayar.
     */
    private fun pitchEntry(semitones: Int): String? {
        val st = semitones.coerceIn(MIN_PITCH, MAX_PITCH)
        if (st == 0) return null
        val rate = (PITCH_RATE * 2.0.pow(st / 12.0)).roundToInt()
        val tempo = PITCH_RATE.toDouble() / rate
        val graph = "aresample=$PITCH_RATE,asetrate=$rate,aresample=$PITCH_RATE,atempo=${num(tempo, 6)}"
        return entry("oppitch", graph, fixPts = true)
    }

    /** Çıkış matrisi: [sol çıkış, sağ çıkış], her biri (c0, c1) katsayıları. */
    private fun stereoMatrix(spec: MpvAudioSpec): Array<DoubleArray>? {
        val balance = spec.balance.coerceIn(-100, 100) / 100.0
        val width = spec.virtualizer.coerceIn(0, 1000) / 1000.0 * VIRTUALIZER_MAX_WIDTH
        if (balance == 0.0 && width == 0.0 && !spec.mono && !spec.reverseStereo) return null
        val leftGain = if (balance > 0) 1 - balance else 1.0
        val rightGain = if (balance < 0) 1 + balance else 1.0
        // Sırası eski işlemciyle aynı: yer değiştirme, mono, sonra denge.
        var l = doubleArrayOf(1.0, 0.0)
        var r = doubleArrayOf(0.0, 1.0)
        if (spec.reverseStereo) {
            val t = l
            l = r
            r = t
        }
        if (spec.mono) {
            l = doubleArrayOf(0.5, 0.5)
            r = doubleArrayOf(0.5, 0.5)
        } else if (width > 0) {
            val wideL = DoubleArray(2) { (1 + width) * l[it] - width * r[it] }
            val wideR = DoubleArray(2) { (1 + width) * r[it] - width * l[it] }
            l = wideL
            r = wideR
        }
        return arrayOf(DoubleArray(2) { l[it] * leftGain }, DoubleArray(2) { r[it] * rightGain })
    }

    /** aformat önce indirger: pan yalnız c0/c1 okur, 5.1'de orta kanal (diyalog) kaybolurdu. */
    private fun stereoEntry(spec: MpvAudioSpec): String? {
        val m = stereoMatrix(spec) ?: return null
        return entry("opstereo", "aformat=channel_layouts=stereo,pan=stereo|c0=${panTerm(m[0])}|c1=${panTerm(m[1])}")
    }

    private fun panTerm(row: DoubleArray): String {
        val channels = row.indices.filter { row[it] != 0.0 }
        if (channels.isEmpty()) return "0*c0"
        val sb = StringBuilder()
        for ((i, ch) in channels.withIndex()) {
            val v = row[ch]
            if (i > 0) sb.append(if (v < 0) '-' else '+')
            sb.append(num(if (i > 0) abs(v) else v, 4)).append("*c").append(ch)
        }
        return sb.toString()
    }

    private fun equalizer(freq: Int, q: Double, gainDb: Double) =
        "equalizer=f=$freq:t=q:w=${num(q, 2)}:g=${num(gainDb, 2)}"

    private fun eqEntry(spec: MpvAudioSpec): String? {
        val filters = ArrayList<String>()
        if (spec.deviceEqEnabled && spec.deviceEqLevels.size == DEVICE_EQ_FREQS.size) {
            for (i in DEVICE_EQ_FREQS.indices) {
                val mb = spec.deviceEqLevels[i].coerceIn(DEVICE_EQ_MIN_MB, DEVICE_EQ_MAX_MB)
                if (mb != 0) filters += equalizer(DEVICE_EQ_FREQS[i], AudioEq.DEFAULT_Q.toDouble(), mb / 100.0)
            }
        }
        val bass = spec.bass.coerceIn(0, 1000)
        if (bass > 0) filters += "equalizer=f=$BASS_FREQ:t=o:w=2:g=${num(bass / 1000.0 * BASS_MAX_DB, 2)}"
        if (spec.graphicEqEnabled) {
            for (i in AudioEq.FREQS.indices) {
                val db = (spec.graphicEqBands.getOrNull(i) ?: 0).coerceIn(-AudioEq.MAX_DB, AudioEq.MAX_DB)
                if (db == 0) continue
                val freq = (spec.graphicEqFreqs.getOrNull(i).takeIf { spec.graphicEqParametric } ?: AudioEq.FREQS[i])
                    .coerceIn(AudioEq.MIN_FREQ, AudioEq.MAX_FREQ)
                val q = (spec.graphicEqQs.getOrNull(i).takeIf { spec.graphicEqParametric }?.let { it / 100.0 } ?: AudioEq.DEFAULT_Q.toDouble())
                    .coerceIn(AudioEq.MIN_Q.toDouble(), AudioEq.MAX_Q.toDouble())
                filters += equalizer(freq, q, db.toDouble())
            }
        }
        if (filters.isEmpty()) return null
        // Bant frekansı Nyquist'i aşarsa equalizer açılmaz; düşük hızlı dosya yalnız burada yükseltilir.
        return entry("opeq", (listOf(EQ_RATES) + filters).joinToString(","))
    }

    private fun reverbEntry(preset: Int): String? {
        val (delays, decays) = REVERB.getOrNull(preset) ?: return null
        if (delays.isEmpty()) return null
        return entry("opreverb", "aecho=0.9:0.9:${delays.joinToString("|")}:${decays.joinToString("|") { num(it, 2) }}")
    }

    /** Yerel ayardan bağımsız sayı metni: Türkçe `0,5` filtreyi bozar. */
    private fun num(v: Double, decimals: Int): String {
        val text = BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }

    /** `volume` değeri. mpv kazancı (değer/100)³ olduğundan istenen doğrusal kazanç dönüştürülür. */
    internal fun volumeFor(boostPercent: Int, gainFactor: Float): String {
        val db = (boostPercent.coerceIn(100, 200) - 100) / 100.0 * BOOST_MAX_DB
        val value = 100.0 * 10.0.pow(db / 60.0) * cbrt(gainFactor.coerceIn(0f, 1f).toDouble())
        return num(value, 2)
    }

    internal fun delayFor(delayMs: Long): String = num(delayMs / 1000.0, 3)

    fun specFrom(settings: AppSettings, bluetooth: Boolean = lastBluetooth): MpvAudioSpec = MpvAudioSpec(
        pitchSemitones = pitchSemitones,
        balance = settings.audioBalance,
        mono = settings.monoMix,
        reverseStereo = settings.reverseStereo,
        virtualizer = settings.virtualizerStrength,
        deviceEqEnabled = settings.eqEnabled,
        deviceEqLevels = settings.eqLevels,
        bass = settings.bassStrength,
        graphicEqEnabled = settings.graphicEqEnabled,
        graphicEqBands = settings.graphicEqBands,
        graphicEqParametric = settings.graphicEqParametric,
        graphicEqFreqs = settings.graphicEqFreqs,
        graphicEqQs = settings.graphicEqQs,
        nightMode = nightMode(settings),
        reverbPreset = settings.reverbPreset,
        boostPercent = settings.volumeBoostPercent,
        delayMs = settings.audioDelayMs + if (bluetooth) settings.bluetoothDelayMs else 0L,
    )

    // --- Motora yazma ---

    @Volatile var pitchSemitones = 0
        private set

    @Volatile private var lastBluetooth = false
    private var lastSettings: AppSettings? = null

    private var target: MpvEngine? = null
    private var writtenAf = ""
    private var writtenVolume: String? = null
    private var writtenDelay: String? = null
    private var pendingAf: String? = null
    private var lastAfWriteMs = Long.MIN_VALUE / 2
    private var gainFactor = 1f
    private var boostPercent = 100

    internal var clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
    internal var schedule: (Long, Runnable) -> Unit = { delay, task -> mainHandler.postDelayed(task, delay) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Ses çıkışını da yeniden denetler; ayar ekranları ve motor açılışı için. */
    fun apply(context: Context) {
        lastBluetooth = AudioDelay.isBluetoothOutput(context)
        apply(AppSettings.get(context))
    }

    /** Motor yoksa bir şey yapmaz; motor kurulunca yeniden çağrılmalıdır. */
    fun apply(settings: AppSettings) {
        synchronized(this) { lastSettings = settings }
        val engine = MpvEngines.current ?: return
        apply(engine, specFrom(settings))
    }

    @Synchronized
    fun apply(engine: MpvEngine, spec: MpvAudioSpec) {
        if (engine.isReleased) return
        if (engine !== target) bind(engine)
        boostPercent = spec.boostPercent
        writeVolume(engine)
        writeDelay(engine, spec.delayMs)
        writeChain(engine, buildChain(spec))
    }

    private fun bind(engine: MpvEngine) {
        target = engine
        writtenAf = ""
        writtenVolume = null
        writtenDelay = "0"
        pendingAf = null
        lastAfWriteMs = Long.MIN_VALUE / 2
        engine.setProperty("volume-max", "200")
        engine.setProperty("audio-pitch-correction", "yes")
    }

    private fun writeVolume(engine: MpvEngine) {
        val value = volumeFor(boostPercent, gainFactor)
        if (value == writtenVolume) return
        writtenVolume = value
        engine.setProperty("volume", value)
    }

    private fun writeDelay(engine: MpvEngine, delayMs: Long) {
        val value = delayFor(delayMs)
        if (value == writtenDelay) return
        writtenDelay = value
        engine.setProperty("audio-delay", value)
    }

    /** Sürgü sürüklenirken art arda gelen zincirler birleştirilir: son değer gecikmeli yazılır. */
    private fun writeChain(engine: MpvEngine, chain: String) {
        if (pendingAf != null) {
            pendingAf = chain
            return
        }
        if (chain == writtenAf) return
        val wait = lastAfWriteMs + AF_MIN_INTERVAL_MS - clockMs()
        if (wait <= 0) {
            commitChain(engine, chain)
            return
        }
        pendingAf = chain
        schedule(wait, Runnable { flushChain(engine) })
    }

    @Synchronized
    private fun flushChain(engine: MpvEngine) {
        val chain = pendingAf ?: return
        pendingAf = null
        if (engine === target && !engine.isReleased && chain != writtenAf) commitChain(engine, chain)
    }

    private fun commitChain(engine: MpvEngine, chain: String) {
        writtenAf = chain
        lastAfWriteMs = clockMs()
        engine.setProperty("af", chain)
    }

    /**
     * Çıkış kazancı, 0..1 doğrusal (`player.volume` karşılığı): kısma, yumuşak başlangıç, parça
     * geçişi, ReplayGain. Ses artırmayı bozmamak için `setVolume` yerine bunu kullanın.
     */
    @Synchronized
    fun setGainFactor(factor: Float) {
        gainFactor = factor.coerceIn(0f, 1f)
        val engine = target?.takeIf { !it.isReleased } ?: return
        writeVolume(engine)
    }

    /** Hız değişmeden perde; `speed` ve uzun bas hızlı oynatmadan bağımsızdır. */
    fun setPitchSemitones(semitones: Int) {
        pitchSemitones = semitones.coerceIn(MIN_PITCH, MAX_PITCH)
        synchronized(this) { lastSettings }?.let { apply(it) }
    }

    fun nightMode(settings: AppSettings): Boolean = settings.prefs.getBoolean(PREF_NIGHT, false)

    fun setNightMode(settings: AppSettings, on: Boolean) {
        settings.prefs.edit().putBoolean(PREF_NIGHT, on).apply()
        apply(settings)
    }

    // --- Parçalar ve kare adımı ---

    fun audioTracks(engine: MpvEngine): List<MpvTrack> = engine.tracks().filter { it.type == MpvTrackType.AUDIO }

    /** [trackId]: [MpvTrack.id]; null sesi kapatır. */
    fun selectAudioTrack(engine: MpvEngine, trackId: Int?) {
        engine.setProperty("aid", trackId?.toString() ?: "no")
    }

    /**
     * mpv'nin açabileceği adrese çevirir. `content://` için [openFd] mpv'nin devralacağı dosya
     * tanıtıcısını açar. Açılamıyorsa null.
     */
    internal fun audioAddTarget(uri: String, openFd: (String) -> Int?): String? = when {
        uri.startsWith("content://") -> openFd(uri)?.let { "fdclose://$it" }
        uri.startsWith("file://") -> runCatching { java.net.URI(uri).path }.getOrNull() ?: uri.removePrefix("file://")
        else -> uri
    }

    /** Harici ses dosyasını ekler ve seçer. Dosya kapanınca mpv parçayı kendisi bırakır. */
    fun addExternalAudio(context: Context, engine: MpvEngine, uri: String, title: String? = null): Boolean {
        val path = audioAddTarget(uri) { u ->
            runCatching {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(u), "r")?.detachFd()
            }.getOrNull()
        } ?: return false
        if (title.isNullOrBlank()) engine.command("audio-add", path, "select")
        else engine.command("audio-add", path, "select", title)
        return true
    }

    /** Bir kare ileri ya da geri; mpv duraklatır. Geri adım hassas sarma olduğundan yavaş olabilir. */
    fun stepFrame(engine: MpvEngine, forward: Boolean) {
        if (engine.isReleased) return
        engine.command(if (forward) "frame-step" else "frame-back-step")
    }

    internal fun resetForTest() {
        synchronized(this) {
            target = null
            writtenAf = ""
            writtenVolume = null
            writtenDelay = null
            pendingAf = null
            lastAfWriteMs = Long.MIN_VALUE / 2
            gainFactor = 1f
            boostPercent = 100
            lastSettings = null
        }
        pitchSemitones = 0
    }
}
