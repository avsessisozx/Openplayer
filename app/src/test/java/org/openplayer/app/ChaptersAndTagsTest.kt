package org.openplayer.app

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    private fun parse(json: String?) = Chapters.parse(json) { "Bölüm $it" }

    @Test
    fun parsesMpvChapterList() {
        val list = parse("""[{"title":"Giriş","time":0.0},{"title":"Orta","time":125.5},{"time":300.25}]""")
        assertEquals(listOf(Chapter(0, "Giriş"), Chapter(125_500, "Orta"), Chapter(300_250, "Bölüm 3")), list)
    }

    @Test
    fun sortsAndSkipsBrokenEntries() {
        val list = parse("""[{"title":"B","time":20},{"title":"x"},{"title":null,"time":5},{"title":"  ","time":-0.001},7]""")
        assertEquals(listOf(Chapter(0, "Bölüm 1"), Chapter(5_000, "Bölüm 2"), Chapter(20_000, "B")), list)
    }

    @Test
    fun emptyOrInvalid() {
        assertTrue(parse(null).isEmpty())
        assertTrue(parse("").isEmpty())
        assertTrue(parse("[]").isEmpty())
        assertTrue(parse("not json").isEmpty())
    }

    @Test
    fun indexAtPosition() {
        val list = listOf(Chapter(1_000, "a"), Chapter(5_000, "b"), Chapter(9_000, "c"))
        assertEquals(-1, Chapters.indexAt(list, 500))
        assertEquals(0, Chapters.indexAt(list, 1_000))
        assertEquals(1, Chapters.indexAt(list, 8_999))
        assertEquals(2, Chapters.indexAt(list, 60_000))
    }

    @Test
    fun readsAndSeeksThroughEngine() {
        val e = FakeEngine()
        e.props["chapter-list"] = """[{"title":"A","time":0},{"title":"B","time":10}]"""
        e.props["chapter"] = "1"
        assertEquals(2, Chapters.read(e).size)
        assertEquals(1, Chapters.currentIndex(e))
        Chapters.seekTo(e, 0)
        assertEquals("0", e.props["chapter"])
    }
}

class MediaTagsTest {

    @Test
    fun replayGainFromTrackListFirst() {
        val tracks = """[{"type":"video","selected":true},{"type":"audio","selected":true,"replaygain-track-gain":-7.5,"replaygain-album-gain":-6.0}]"""
        val meta = """{"REPLAYGAIN_TRACK_GAIN":"-1.00 dB"}"""
        assertEquals(-7.5f, MediaTags.replayGainDb(tracks, meta, 1)!!, 0.001f)
        assertEquals(-6.0f, MediaTags.replayGainDb(tracks, meta, 2)!!, 0.001f)
    }

    @Test
    fun replayGainFallsBackToTagsAnyCase() {
        val meta = """{"replaygain_track_gain":"-3.21 dB","Title":"x"}"""
        assertEquals(-3.21f, MediaTags.replayGainDb("[]", meta, 1)!!, 0.001f)
        // Albüm değeri yoksa parça değeri kullanılır.
        assertEquals(-3.21f, MediaTags.replayGainDb(null, meta, 2)!!, 0.001f)
        assertNull(MediaTags.replayGainDb(null, "{}", 1))
        assertNull(MediaTags.replayGainDb("broken", "broken", 1))
    }

    @Test
    fun parseGain() {
        assertEquals(2.5f, MediaTags.parseGain("+2.50 dB")!!, 0.001f)
        assertEquals(-12f, MediaTags.parseGain("-12 dB")!!, 0.001f)
        assertNull(MediaTags.parseGain("yok"))
        assertEquals("x", MediaTags.metadata("""{"Title":"x"}""")["TITLE"])
    }
}

class AudioFocusPolicyTest {

    @Test
    fun transientLossResumesOnGain() {
        val p = AudioFocusPolicy(30)
        assertEquals(FocusStep(pause = true), p.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true))
        assertTrue(p.resumeOnGain)
        assertEquals(FocusStep(resume = true), p.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
        assertFalse(p.resumeOnGain)
    }

    @Test
    fun transientLossWhilePausedDoesNotResume() {
        val p = AudioFocusPolicy(30)
        assertEquals(FocusStep(), p.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = false))
        assertEquals(FocusStep(), p.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
    }

    @Test
    fun permanentLossPausesForGood() {
        val p = AudioFocusPolicy(30)
        p.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true)
        assertEquals(FocusStep(pause = true), p.onChange(AudioManager.AUDIOFOCUS_LOSS, playing = false))
        assertEquals(FocusStep(), p.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
    }

    @Test
    fun duckingOnlyBelowApi26() {
        assertEquals(FocusStep(), AudioFocusPolicy(26).onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true))
        val old = AudioFocusPolicy(24)
        assertEquals(FocusStep(duck = AudioFocusPolicy.DUCK_FACTOR), old.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true))
        assertEquals(FocusStep(duck = 1f), old.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = true))
    }
}
