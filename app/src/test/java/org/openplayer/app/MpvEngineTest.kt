package org.openplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvEngineTest {

    @Test
    fun tracksParsesRealTrackList() {
        // Biçim: Faz 0'da emülatörde alınan gerçek track-list çıktısından.
        val json = """[
          {"id":1,"type":"video","codec":"h264","selected":true,"default":false,"external":false,"demux-w":640,"demux-h":360},
          {"id":1,"type":"audio","codec":"aac","lang":"tur","selected":true,"demux-channel-count":1,"demux-samplerate":44100},
          {"id":1,"type":"sub","codec":"subrip","lang":"tur","title":"Türkçe","default":true,"forced":true},
          {"id":2,"type":"sub","codec":"hdmv_pgs_subtitle","external":true},
          {"id":9,"type":"unknown"}
        ]"""
        val t = MpvJson.tracks(json)
        assertEquals(4, t.size)
        assertEquals(640, t[0].width)
        assertEquals(MpvTrackType.AUDIO, t[1].type)
        assertEquals("tur", t[1].language)
        assertEquals(1, t[1].channels)
        assertEquals(44100, t[1].sampleRate)
        assertTrue(t[2].isDefault && t[2].isForced)
        assertEquals("Türkçe", t[2].title)
        assertFalse(t[2].isImageSubtitle)
        assertTrue(t[3].isImageSubtitle)
        assertTrue(t[3].isExternal)
    }

    @Test
    fun tracksToleratesBadInput() {
        assertTrue(MpvJson.tracks(null).isEmpty())
        assertTrue(MpvJson.tracks("").isEmpty())
        assertTrue(MpvJson.tracks("not json").isEmpty())
    }

    @Test
    fun endReasonMapsMpvNames() {
        assertEquals(EndReason.EOF, MpvJson.endReason("eof"))
        assertEquals(EndReason.ERROR, MpvJson.endReason("error"))
        assertEquals(EndReason.QUIT, MpvJson.endReason("quit"))
        assertEquals(EndReason.REDIRECT, MpvJson.endReason("redirect"))
        assertEquals(EndReason.STOP, MpvJson.endReason("stop"))
        assertEquals(EndReason.STOP, MpvJson.endReason(null))
    }

    @Test
    fun filterForcesCopyInsteadOfZeroCopy() {
        // Doğrudan donanım karesi yazılım filtresine giremez (Faz 0'da ölçüldü).
        assertEquals(MpvPlayer.HWDEC_DEFAULT, MpvPlayer.effectiveHwdec(MpvPlayer.HWDEC_DEFAULT, ""))
        assertEquals("mediacodec-copy,no", MpvPlayer.effectiveHwdec(MpvPlayer.HWDEC_DEFAULT, "lavfi=[hflip]"))
        assertEquals("mediacodec-copy,no", MpvPlayer.effectiveHwdec("mediacodec", "lavfi=[hflip]"))
        assertEquals("mediacodec-copy,no", MpvPlayer.effectiveHwdec("mediacodec-copy,no", "lavfi=[hflip]"))
        assertEquals("no", MpvPlayer.effectiveHwdec("no", "lavfi=[hflip]"))
    }

    @Test
    fun decoderSettingMapsToHwdec() {
        // Varsayılan ayar (HW+) doğrudan donanım çıkışını kullanmalı; kopyalı kip 4K'da pahalı.
        assertEquals("mediacodec,mediacodec-copy,no", MpvPlayer.hwdecFor(DecoderMode.HW_PLUS))
        assertEquals("mediacodec", MpvPlayer.hwdecFor(DecoderMode.HW))
        assertEquals("no", MpvPlayer.hwdecFor(DecoderMode.SW))
    }

    @Test
    fun reportHasAllRequiredFields() {
        val r = PlaybackStats.format(
            mapOf(
                "mpv-version" to "mpv v0.41.0", "ffmpeg-version" to "N-1", "file-format" to "mkv",
                "hwdec" to "mediacodec", "hwdec-current" to "mediacodec", "video-format" to "hevc",
                "video-params/w" to "3840", "video-params/h" to "1600", "video-dec-params/pixelformat" to "yuv420p10",
                "estimated-vf-fps" to "23.976", "decoder-frame-drop-count" to "2", "frame-drop-count" to "5",
            )
        )
        for (part in listOf("mpv v0.41.0", "mkv", "donanım: mediacodec", "hevc 3840x1600", "yuv420p10", "23.976", "çözücü 2", "çıkış 5")) {
            assertTrue("eksik: $part\n$r", part in r)
        }
    }

    @Test
    fun reportSurvivesMissingValues() {
        val r = PlaybackStats.format(emptyMap())
        assertTrue("yazılım" in r)
        assertTrue("?" in r)
    }
}
