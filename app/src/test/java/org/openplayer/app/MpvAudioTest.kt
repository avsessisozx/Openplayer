package org.openplayer.app

import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

class MpvAudioTest {

    private class FakeEngine : MpvEngine {
        val writes = ArrayList<Pair<String, String>>()
        val commands = ArrayList<List<String>>()
        var trackList: List<MpvTrack> = emptyList()
        override val events: SharedFlow<MpvEvent> = MutableSharedFlow()
        override fun addListener(listener: (MpvEvent) -> Unit) = Unit
        override fun removeListener(listener: (MpvEvent) -> Unit) = Unit
        override var isReleased = false
        override val isPlaying = false
        override val playWhenReady = false
        override val positionMs = 0L
        override val durationMs = 0L
        override val speed = 1f
        override fun open(uri: String, startMs: Long, paused: Boolean) = Unit
        override fun stop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long, exact: Boolean) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun command(vararg args: String) { commands += args.toList() }
        override fun setProperty(name: String, value: String) { writes += name to value }
        override fun getProperty(name: String): String? = null
        override fun getPropertyJson(name: String): String? = null
        override fun observe(property: String, callback: (String?) -> Unit) = AutoCloseable {}
        override fun tracks() = trackList
        override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setHardwareDecoding(value: String) = Unit
        override fun setVideoFilter(filter: String) = Unit
        override fun release() = Unit
        fun values(name: String) = writes.filter { it.first == name }.map { it.second }
    }

    private val tasks = ArrayList<Runnable>()
    private var now = 1_000L
    private val savedLocale = Locale.getDefault()

    @Before
    fun setUp() {
        MpvAudio.resetForTest()
        MpvAudio.clockMs = { now }
        MpvAudio.schedule = { _, task -> tasks += task }
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        MpvAudio.resetForTest()
    }

    private val everything = MpvAudioSpec(
        pitchSemitones = 3,
        balance = -40,
        virtualizer = 500,
        deviceEqEnabled = true,
        deviceEqLevels = listOf(300, 0, 0, 0, 300),
        bass = 500,
        graphicEqEnabled = true,
        graphicEqBands = listOf(6, 0, 0, 0, 0, 0, 0, 0, 0, -6),
        nightMode = true,
        reverbPreset = 3,
    )

    @Test
    fun allEffectsOffGiveEmptyChain() {
        assertEquals("", MpvAudio.buildChain(MpvAudioSpec()))
    }

    @Test
    fun flatSettingsAreNotEffects() {
        val spec = MpvAudioSpec(
            deviceEqEnabled = true,
            deviceEqLevels = listOf(0, 0, 0, 0, 0),
            graphicEqEnabled = true,
            graphicEqBands = List(10) { 0 },
            boostPercent = 200,
            delayMs = 500,
        )
        assertEquals("", MpvAudio.buildChain(spec))
    }

    @Test
    fun disabledEqIsIgnored() {
        val spec = MpvAudioSpec(deviceEqLevels = listOf(100, 100, 100, 100, 100), graphicEqBands = List(10) { 5 })
        assertEquals("", MpvAudio.buildChain(spec))
    }

    @Test
    fun filterOrderIsStable() {
        val labels = MpvAudio.chainEntries(everything).map { it.substringBefore(':') }
        assertEquals(listOf("@oppitch", "@opstereo", "@opeq", "@opnight", "@opreverb"), labels)
        val eq = MpvAudio.chainEntries(everything)[2]
        val freqs = Regex("f=(\\d+)").findAll(eq).map { it.groupValues[1].toInt() }.toList()
        assertEquals("cihaz EQ, bas, grafik EQ", listOf(60, 14000, 80, 31, 16000), freqs)
    }

    @Test
    fun changingOneEffectKeepsOtherEntriesIdentical() {
        val before = MpvAudio.chainEntries(everything)
        val after = MpvAudio.chainEntries(everything.copy(graphicEqBands = List(10) { 2 }))
        assertEquals(before.size, after.size)
        for (i in before.indices) {
            if (i == 2) assertNotEquals(before[i], after[i]) else assertEquals(before[i], after[i])
        }
    }

    @Test
    fun pitchKeepsDurationAtEverySemitone() {
        for (st in -12..12) {
            if (st == 0) continue
            val chain = MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = st))
            val m = Regex("""aresample=48000,asetrate=(\d+),aresample=48000,atempo=([0-9.]+)""").find(chain)!!
            val rate = m.groupValues[1].toDouble()
            val tempo = m.groupValues[2].toDouble()
            assertEquals(2.0.pow(st / 12.0), rate / 48000, 1e-4)
            assertEquals("hız değişmemeli", 1.0, rate / 48000 * tempo, 1e-5)
            assertTrue("atempo sınırı: $tempo", tempo in 0.5..2.0)
            assertTrue("zaman damgası düzeltilmeli", chain.endsWith(":fix-pts=yes"))
        }
    }

    @Test
    fun pitchIsClampedAndZeroRemoved() {
        assertEquals(MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = 12)), MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = 40)))
        assertEquals(MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = -12)), MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = -40)))
        assertEquals("", MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = 0)))
    }

    @Test
    fun chainNeverDoesItsOwnSpeedCorrection() {
        assertFalse(MpvAudio.buildChain(everything).contains("scaletempo"))
        assertFalse(MpvAudio.buildChain(everything).contains("rubberband"))
    }

    @Test
    fun stereoModes() {
        fun pan(spec: MpvAudioSpec) = MpvAudio.buildChain(spec).substringAfter("pan=").substringBefore("]")
        assertEquals("stereo|c0=1*c1|c1=1*c0", pan(MpvAudioSpec(reverseStereo = true)))
        assertEquals("stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1", pan(MpvAudioSpec(mono = true)))
        assertEquals("stereo|c0=1*c0|c1=0.5*c1", pan(MpvAudioSpec(balance = -50)))
        assertEquals("stereo|c0=0.5*c0|c1=1*c1", pan(MpvAudioSpec(balance = 50)))
        assertEquals("stereo|c0=1*c0|c1=0*c0", pan(MpvAudioSpec(balance = -500)))
        assertEquals("stereo|c0=0*c0|c1=1*c1", pan(MpvAudioSpec(balance = 500)))
        assertEquals("stereo|c0=1.5*c0-0.5*c1|c1=-0.5*c0+1.5*c1", pan(MpvAudioSpec(virtualizer = 1000)))
    }

    @Test
    fun panAlwaysDownmixesFirst() {
        val entry = MpvAudio.buildChain(MpvAudioSpec(balance = 10))
        assertTrue(entry, entry.contains("[aformat=channel_layouts=stereo,pan="))
    }

    @Test
    fun eqBoundariesAreClamped() {
        val spec = MpvAudioSpec(
            graphicEqEnabled = true,
            graphicEqParametric = true,
            graphicEqBands = listOf(99, -99),
            graphicEqFreqs = listOf(1, 99999),
            graphicEqQs = listOf(1, 99999),
        )
        val chain = MpvAudio.buildChain(spec)
        assertTrue(chain, chain.contains("equalizer=f=20:t=q:w=0.3:g=15"))
        assertTrue(chain, chain.contains("equalizer=f=20000:t=q:w=10:g=-15"))
    }

    @Test
    fun graphicModeIgnoresStoredFrequencies() {
        val chain = MpvAudio.buildChain(
            MpvAudioSpec(graphicEqEnabled = true, graphicEqBands = listOf(3), graphicEqFreqs = listOf(777), graphicEqQs = listOf(500)),
        )
        assertTrue(chain, chain.contains("equalizer=f=31:t=q:w=1.41:g=3"))
    }

    @Test
    fun deviceEqKeepsMillibelStorage() {
        val chain = MpvAudio.buildChain(MpvAudioSpec(deviceEqEnabled = true, deviceEqLevels = listOf(150, 0, 0, 0, 0)))
        assertTrue(chain, chain.contains("equalizer=f=60:t=q:w=1.41:g=1.5"))
        val wrongSize = MpvAudioSpec(deviceEqEnabled = true, deviceEqLevels = listOf(150, 150))
        assertEquals("", MpvAudio.buildChain(wrongSize))
    }

    @Test
    fun lowSampleRateCannotBreakEqualizer() {
        val chain = MpvAudio.buildChain(MpvAudioSpec(graphicEqEnabled = true, graphicEqBands = List(10) { 3 }))
        assertTrue(chain, chain.startsWith("@opeq:lavfi=[aformat=sample_rates=44100|"))
    }

    @Test
    fun reverbPresetsAreValidAndOutOfRangeIsOff() {
        for (i in 1..6) {
            val (delays, decays) = MpvAudio.REVERB[i]
            assertEquals(delays.size, decays.size)
            val chain = MpvAudio.buildChain(MpvAudioSpec(reverbPreset = i))
            assertTrue(chain, chain.startsWith("@opreverb:lavfi=[aecho=0.9:"))
            val outGain = chain.substringAfter("aecho=").split(':')[1].toDouble()
            assertTrue("taşma olmamalı: $chain", outGain * (0.9 + decays.sum()) <= 1.001)
        }
        assertEquals("", MpvAudio.buildChain(MpvAudioSpec(reverbPreset = 99)))
        assertEquals("", MpvAudio.buildChain(MpvAudioSpec(reverbPreset = -1)))
    }

    @Test
    fun presetsMatchFiveBands() {
        for ((_, gains) in MpvAudio.DEVICE_EQ_PRESETS) assertEquals(MpvAudio.DEVICE_EQ_FREQS.size, gains.size)
    }

    @Test
    fun numbersIgnoreLocale() {
        val expected = MpvAudio.buildChain(everything)
        Locale.setDefault(Locale("tr", "TR"))
        assertEquals(expected, MpvAudio.buildChain(everything))
        Locale.setDefault(Locale.GERMANY)
        assertEquals(expected, MpvAudio.buildChain(everything))
        assertFalse(expected, Regex("\\d,\\d").containsMatchIn(expected))
    }

    @Test
    fun chainHasNoCharactersThatBreakMpvQuoting() {
        val chain = MpvAudio.buildChain(everything)
        assertFalse(chain, chain.any { it == ' ' || it == '\'' || it == '"' || it == '\\' })
        for (entry in MpvAudio.chainEntries(everything)) {
            assertEquals(entry, 1, entry.count { it == '[' })
            assertEquals(entry, 1, entry.count { it == ']' })
            assertTrue(entry, entry.endsWith("]") || entry.endsWith("]:fix-pts=yes"))
        }
    }

    @Test
    fun boostMatchesOldLoudnessOnCubicCurve() {
        assertEquals("100", MpvAudio.volumeFor(100, 1f))
        val max = MpvAudio.volumeFor(200, 1f).toDouble()
        assertTrue("volume-max içinde kalmalı", max <= 200.0)
        assertEquals(15.0, 20 * Math.log10((max / 100).pow(3)), 0.01)
        assertEquals(MpvAudio.volumeFor(100, 1f), MpvAudio.volumeFor(50, 1f))
        assertEquals(MpvAudio.volumeFor(200, 1f), MpvAudio.volumeFor(999, 1f))
    }

    @Test
    fun gainFactorIsLinearAmplitude() {
        assertEquals("0", MpvAudio.volumeFor(200, 0f))
        val half = MpvAudio.volumeFor(100, 0.5f).toDouble()
        assertEquals(0.5, (half / 100).pow(3), 0.001)
    }

    @Test
    fun delayIsInSeconds() {
        assertEquals("0.25", MpvAudio.delayFor(250))
        assertEquals("-0.1", MpvAudio.delayFor(-100))
        assertEquals("0", MpvAudio.delayFor(0))
    }

    @Test
    fun firstApplySetsVolumeMaxBeforeVolumeAndKeepsPitchCorrection() {
        val e = FakeEngine()
        MpvAudio.apply(e, MpvAudioSpec(boostPercent = 200))
        assertEquals("volume-max" to "200", e.writes[0])
        assertEquals("audio-pitch-correction" to "yes", e.writes[1])
        assertEquals("volume", e.writes[2].first)
        assertTrue("boş zincir yazılmaz", e.values("af").isEmpty())
        assertTrue(e.values("audio-delay").isEmpty())
    }

    @Test
    fun unchangedSpecWritesNothing() {
        val e = FakeEngine()
        MpvAudio.apply(e, everything.copy(delayMs = 100))
        e.writes.clear()
        now += 10_000
        MpvAudio.apply(e, everything.copy(delayMs = 100))
        assertEquals(emptyList<Pair<String, String>>(), e.writes)
    }

    @Test
    fun speedIsNeverTouched() {
        val e = FakeEngine()
        MpvAudio.apply(e, everything)
        MpvAudio.apply(e, everything.copy(pitchSemitones = -3))
        assertTrue(e.values("speed").isEmpty())
        assertEquals(listOf("yes"), e.values("audio-pitch-correction"))
    }

    @Test
    fun rapidChangesAreCoalescedToLastValue() {
        val e = FakeEngine()
        MpvAudio.apply(e, MpvAudioSpec(balance = 10))
        now += 10
        MpvAudio.apply(e, MpvAudioSpec(balance = 20))
        now += 10
        MpvAudio.apply(e, MpvAudioSpec(balance = 30))
        assertEquals(1, e.values("af").size)
        assertEquals(1, tasks.size)
        tasks.single().run()
        assertEquals(listOf(MpvAudio.buildChain(MpvAudioSpec(balance = 10)), MpvAudio.buildChain(MpvAudioSpec(balance = 30))), e.values("af"))
    }

    @Test
    fun returningToWrittenChainWhilePendingWritesNothingExtra() {
        val e = FakeEngine()
        MpvAudio.apply(e, MpvAudioSpec(balance = 10))
        now += 10
        MpvAudio.apply(e, MpvAudioSpec(balance = 20))
        now += 10
        MpvAudio.apply(e, MpvAudioSpec(balance = 10))
        tasks.single().run()
        assertEquals(1, e.values("af").size)
    }

    @Test
    fun turningEverythingOffClearsChain() {
        val e = FakeEngine()
        MpvAudio.apply(e, everything)
        now += 10_000
        MpvAudio.apply(e, MpvAudioSpec())
        assertEquals("", e.values("af").last())
    }

    @Test
    fun newEngineGetsFullState() {
        val a = FakeEngine()
        MpvAudio.apply(a, everything)
        a.isReleased = true
        val b = FakeEngine()
        MpvAudio.apply(b, everything)
        assertEquals(listOf(MpvAudio.buildChain(everything)), b.values("af"))
        assertEquals(listOf("200"), b.values("volume-max"))
    }

    @Test
    fun pendingChainIsDroppedForReleasedEngine() {
        val e = FakeEngine()
        MpvAudio.apply(e, MpvAudioSpec(balance = 10))
        MpvAudio.apply(e, MpvAudioSpec(balance = 20))
        e.isReleased = true
        tasks.single().run()
        assertEquals(1, e.values("af").size)
        MpvAudio.apply(e, MpvAudioSpec(balance = 30))
        assertEquals(1, e.values("af").size)
    }

    @Test
    fun gainFactorKeepsBoostAndSkipsChain() {
        val e = FakeEngine()
        MpvAudio.apply(e, everything.copy(boostPercent = 200))
        val boosted = e.values("volume").last().toDouble()
        e.writes.clear()
        MpvAudio.setGainFactor(0f)
        assertEquals(listOf("0"), e.values("volume"))
        MpvAudio.setGainFactor(1f)
        assertEquals(boosted, e.values("volume").last().toDouble(), 0.001)
        assertTrue(e.values("af").isEmpty())
    }

    @Test
    fun gainFactorSurvivesApply() {
        val e = FakeEngine()
        MpvAudio.apply(e, MpvAudioSpec())
        MpvAudio.setGainFactor(0f)
        MpvAudio.apply(e, MpvAudioSpec(balance = 10))
        assertEquals("0", e.values("volume").last())
    }

    @Test
    fun musicOwnedVolumeIsNeverWritten() {
        val e = FakeEngine()
        MpvAudio.volumeManagedElsewhere = true
        MpvAudio.apply(e, everything.copy(boostPercent = 200))
        MpvAudio.setGainFactor(0.5f)
        assertTrue(e.values("volume").isEmpty())
        assertEquals(listOf(MpvAudio.buildChain(everything)), e.values("af"))
        MpvAudio.volumeManagedElsewhere = false
        MpvAudio.apply(e, everything.copy(boostPercent = 200))
        assertEquals(1, e.values("volume").size)
    }

    @Test
    fun pitchStateIsClamped() {
        MpvAudio.setPitchSemitones(30)
        assertEquals(12, MpvAudio.pitchSemitones)
        MpvAudio.setPitchSemitones(-30)
        assertEquals(-12, MpvAudio.pitchSemitones)
    }

    @Test
    fun audioTrackSelection() {
        val e = FakeEngine()
        e.trackList = listOf(
            MpvTrack(1, MpvTrackType.VIDEO, null, null, null, true, true, false, false),
            MpvTrack(2, MpvTrackType.AUDIO, "tr", "tur", "aac", true, true, false, false),
            MpvTrack(3, MpvTrackType.AUDIO, "en", "eng", "aac", false, false, false, false),
        )
        assertEquals(listOf(2, 3), MpvAudio.audioTracks(e).map { it.id })
        MpvAudio.selectAudioTrack(e, 3)
        MpvAudio.selectAudioTrack(e, null)
        assertEquals(listOf("3", "no"), e.values("aid"))
    }

    @Test
    fun externalAudioTargets() {
        assertEquals("fdclose://42", MpvUris.target("content://a/b") { 42 })
        assertNull(MpvUris.target("content://a/b") { null })
        assertEquals("/sdcard/a b.mka", MpvUris.target("file:///sdcard/a%20b.mka") { null })
        assertEquals("/sdcard/plain.mka", MpvUris.target("/sdcard/plain.mka") { null })
        assertEquals("https://x/y.m4a", MpvUris.target("https://x/y.m4a") { null })
    }

    @Test
    fun frameStepCommands() {
        val e = FakeEngine()
        MpvAudio.stepFrame(e, true)
        MpvAudio.stepFrame(e, false)
        e.isReleased = true
        MpvAudio.stepFrame(e, true)
        assertEquals(listOf(listOf("frame-step"), listOf("frame-back-step")), e.commands)
    }

    @Test
    fun numberHelpersStayPrecise() {
        val tempo = MpvAudio.buildChain(MpvAudioSpec(pitchSemitones = 12)).substringAfter("atempo=").substringBefore("]")
        assertTrue(tempo, abs(tempo.toDouble() - 0.5) < 1e-9)
    }
}
