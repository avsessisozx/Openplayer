package org.openplayer.app

import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Motoru taklit eder: çağrıları kaydeder, olayları test elle verir. */
class FakeEngine : MpvEngine {
    val calls = ArrayList<String>()
    val props = HashMap<String, String>()
    var pos = 0L
    var dur = 0L
    var paused = true
    var open = false

    override val events: SharedFlow<MpvEvent> = MutableSharedFlow()
    override fun addListener(listener: (MpvEvent) -> Unit) {}
    override fun removeListener(listener: (MpvEvent) -> Unit) {}
    override val isReleased = false
    override val isPlaying: Boolean get() = open && !paused
    override val playWhenReady: Boolean get() = open && !paused
    override val positionMs: Long get() = pos
    override val durationMs: Long get() = dur
    override val speed = 1f

    override fun open(uri: String, startMs: Long, paused: Boolean) {
        calls += "open $uri $startMs ${if (paused) "paused" else "play"}"
        this.paused = paused
        pos = startMs
        open = false
    }
    override fun stop() { calls += "stop" }
    override fun play() { calls += "play"; paused = false }
    override fun pause() { calls += "pause"; paused = true }
    override fun seekTo(positionMs: Long, exact: Boolean) { calls += "seek $positionMs"; pos = positionMs }
    override fun setSpeed(speed: Float) {}
    override fun setVolume(percent: Int) { calls += "volume $percent" }
    override fun command(vararg args: String) {}
    override fun setProperty(name: String, value: String) { props[name] = value }
    override fun getProperty(name: String): String? = props[name]
    override fun getPropertyJson(name: String): String? = props[name]
    override fun observe(property: String, callback: (String?) -> Unit) = AutoCloseable {}
    override fun tracks(): List<MpvTrack> = emptyList()
    override fun attachSurface(surface: Surface, width: Int, height: Int) {}
    override fun detachSurface() {}
    override fun setHardwareDecoding(value: String) {}
    override fun setVideoFilter(filter: String) {}
    override fun release() {}
}

class MusicDeckTest {

    private val engine = FakeEngine()
    private val deck = MusicDeck<DeckEntry>({ engine }, { it }, MusicQueue(Random(1)))

    /** Motorun dosyayı açtığını bildirir. */
    private fun loaded() {
        deck.onEvent(MpvEvent.FileStarted(null))
        engine.open = true
        deck.onEvent(MpvEvent.FileLoaded)
        deck.onEvent(MpvEvent.PlaybackRestarted)
    }

    private val a = DeckEntry("a", "file:///a.mp3")
    private val b = DeckEntry("b", "file:///b.mp3")
    private val c = DeckEntry("c", "file:///c.mp3")

    @Test
    fun eofAdvancesAndStopsAtEnd() {
        deck.start(listOf(a, b), 0)
        loaded()
        assertEquals("open file:///a.mp3 0 play", engine.calls.last())
        deck.onEvent(MpvEvent.FileEnded(EndReason.EOF))
        assertEquals("open file:///b.mp3 0 play", engine.calls.last())
        assertEquals(b, deck.current)
        loaded()
        deck.onEvent(MpvEvent.FileEnded(EndReason.EOF))
        assertTrue(deck.ended)
        assertFalse(deck.playIntent)
        // Sıra bitince play baştan değil, çalan (son) parçayı yeniden açar.
        deck.play()
        assertEquals("open file:///b.mp3 0 play", engine.calls.last())
    }

    @Test
    fun staleEndOfPreviousFileIsIgnored() {
        deck.start(listOf(a, b, c), 0)
        loaded()
        deck.next()
        // b henüz başlamadı; a'nın geç gelen bitişi c'ye atlatmamalı.
        deck.onEvent(MpvEvent.FileEnded(EndReason.EOF))
        assertEquals(b, deck.current)
        deck.onEvent(MpvEvent.FileEnded(EndReason.STOP))
        assertEquals(b, deck.current)
    }

    @Test
    fun repeatOneReopensSameTrack() {
        deck.queue.repeatMode = MusicQueue.REPEAT_ONE
        deck.start(listOf(a, b), 0)
        loaded()
        deck.onEvent(MpvEvent.FileEnded(EndReason.EOF))
        assertEquals(a, deck.current)
        assertEquals(2, engine.calls.count { it.startsWith("open file:///a.mp3") })
    }

    @Test
    fun failedTrackIsSkippedOnceAndAllFailedEnds() {
        deck.start(listOf(a, b), 0)
        deck.onEvent(MpvEvent.FileStarted(null))
        deck.onEvent(MpvEvent.FileEnded(EndReason.ERROR))
        deck.onEvent(MpvEvent.Error("broken"))
        assertEquals(b, deck.current)
        // b açılamadı (adres çözülemedi: FileStarted gelmeden Error).
        deck.onEvent(MpvEvent.Error("cannot open"))
        assertTrue(deck.ended)
    }

    @Test
    fun pauseAtEndOpensNextPaused() {
        var fired = false
        deck.onPausedAtEnd = { fired = true }
        deck.start(listOf(a, b), 0)
        loaded()
        deck.pauseAtEnd = true
        deck.onEvent(MpvEvent.FileEnded(EndReason.EOF))
        assertTrue(fired)
        assertFalse(deck.pauseAtEnd)
        assertEquals("open file:///b.mp3 0 paused", engine.calls.last())
        assertFalse(deck.playIntent)
    }

    @Test
    fun previousRestartsAfterThreeSeconds() {
        deck.start(listOf(a, b), 1)
        loaded()
        engine.pos = 10_000
        deck.onEvent(MpvEvent.Position(10_000))
        deck.previous()
        assertEquals("seek 0", engine.calls.last())
        assertEquals(b, deck.current)
        engine.pos = 1_000
        deck.onEvent(MpvEvent.Position(1_000))
        deck.previous()
        assertEquals(a, deck.current)
    }

    @Test
    fun cueTracksPlayContinuouslyInsideOneFile() {
        val t1 = DeckEntry("1", "file:///album.flac", 0, 60_000)
        val t2 = DeckEntry("2", "file:///album.flac", 60_000, 120_000)
        val t3 = DeckEntry("3", "file:///album.flac", 120_000, 0)
        var changes = 0
        deck.onTrackChanged = { changes++ }
        deck.start(listOf(t1, t2, t3), 0)
        loaded()
        val opens = engine.calls.count { it.startsWith("open") }
        engine.pos = 60_100
        deck.onEvent(MpvEvent.Position(60_100))
        assertEquals(t2, deck.current)
        assertEquals(100L, deck.positionMs)
        assertEquals(60_000L, deck.durationMs)
        // Bitişik parça: motor yeniden açılmaz, sarılmaz.
        assertEquals(opens, engine.calls.count { it.startsWith("open") })
        assertFalse(engine.calls.last().startsWith("seek"))
        assertEquals(2, changes)
        // Parçaya göre sarma, dosyada aralığın başına eklenir.
        deck.seekTo(5_000)
        assertEquals("seek 65000", engine.calls.last())
    }

    @Test
    fun jumpingToCueTrackSeeksInsteadOfReopening() {
        val t1 = DeckEntry("1", "file:///album.flac", 0, 60_000)
        val t2 = DeckEntry("2", "file:///album.flac", 60_000, 120_000)
        deck.start(listOf(t1, t2), 0)
        loaded()
        deck.playAt(1)
        assertEquals("play", engine.calls.last())
        assertEquals("seek 60000", engine.calls[engine.calls.size - 2])
        // Sarma sürerken eski konum, parça sonunu tetiklememeli.
        deck.onEvent(MpvEvent.Position(130_000))
        assertEquals(t2, deck.current)
    }

    @Test
    fun cueEndWithoutNextPauses() {
        val t1 = DeckEntry("1", "file:///album.flac", 0, 60_000)
        deck.start(listOf(t1), 0)
        loaded()
        engine.pos = 60_050
        deck.onEvent(MpvEvent.Position(60_050))
        assertTrue(deck.ended)
        assertEquals("pause", engine.calls.last())
        assertEquals(60_000L, deck.positionMs)
    }

    @Test
    fun volumeFormulaIsClamped() {
        assertEquals(100, musicVolumePercent(100, 1f, 1f, 1f))
        assertEquals(200, musicVolumePercent(200, 1f, 1f, 1f))
        assertEquals(20, musicVolumePercent(100, 1f, 1f, 0.2f))
        assertEquals(0, musicVolumePercent(150, 0.5f, 0f, 1f))
        assertEquals(200, musicVolumePercent(400, 1f, 1f, 1f))
    }

    @Test
    fun emptyDeckIsSafe() {
        deck.play()
        deck.next()
        deck.previous()
        deck.seekTo(1000)
        assertNull(deck.current)
        assertEquals(0L, deck.positionMs)
        assertTrue(engine.calls.isEmpty())
    }
}
