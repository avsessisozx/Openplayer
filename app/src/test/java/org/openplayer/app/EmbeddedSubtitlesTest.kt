package org.openplayer.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

class EmbeddedSubtitlesTest {
    @Before
    fun strings() = TestStrings.installTurkish()

    @After
    fun clear() = TestStrings.uninstall()

    private fun fixture(name: String): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            File(d, "app/src/test/resources/subtitles/$name").takeIf { it.isFile }?.let { return it }
            File(d, "src/test/resources/subtitles/$name").takeIf { it.isFile }?.let { return it }
            d = d.parentFile
        }
        error("fixture $name")
    }

    private fun mkv(file: File, track: Int): List<SubCue> =
        MatroskaSubtitles.extract({ file.inputStream() }, track, file.length(), "windows-1254", { false }, {})

    // ffmpeg ile üretildi; beklenen zamanlar ffprobe paket çıktısıyla aynı.
    @Test
    fun srtTrackFromFfmpegMkvHasExactEndTimes() {
        val cues = mkv(fixture("two_tracks.mkv"), 2)
        assertEquals(listOf(1000L to 2500L, 4250L to 4900L, 6000L to 9750L, 12000L to 12400L), cues.map { it.startMs to it.endMs })
        assertEquals("First line one", cues[0].text)
        assertEquals("Şğıİ second 中文字幕 日本語 العربية", cues[1].text)
        assertEquals("Third long\ntwo rows", cues[2].text)
    }

    @Test
    fun assTrackKeepsOnlyDialogueText() {
        val cues = mkv(fixture("two_tracks.mkv"), 3)
        assertEquals(listOf(1500L to 3200L, 5000L to 7000L), cues.map { it.startMs to it.endMs })
        assertEquals("ASS top\nsecond row", cues[0].text)
        assertEquals("Positioned", cues[1].text)
    }

    @Test
    fun webvttInWebm() {
        val cues = mkv(fixture("webvtt.webm"), 2)
        assertEquals(4, cues.size)
        assertEquals(6000L to 9750L, cues[2].startMs to cues[2].endMs)
        assertEquals("First line one", cues[0].text)
    }

    @Test
    fun thirtyFourTracksPicksTheRightOne() {
        val f = fixture("34_tracks.mkv")
        for (n in listOf(1, 17, 34)) {
            val cues = mkv(f, n + 1)
            assertEquals("Track$n line one", cues[0].text)
            assertEquals(4, cues.size)
            assertEquals(12400L, cues[3].endMs)
        }
    }

    @Test
    fun containerSniff() {
        assertEquals(Container.MATROSKA, Container.sniff(fixture("two_tracks.mkv").inputStream()))
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray()
        assertEquals(Container.MP4, Container.sniff(ByteArrayInputStream(mp4)))
        assertEquals(Container.OTHER, Container.sniff(ByteArrayInputStream("RIFF....AVI ".toByteArray())))
    }

    // ---------------------------------------------------------------- elle kurulan EBML

    private fun id(v: Long): ByteArray {
        val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }

    /** 4 baytlık EBML boyutu. */
    private fun size(v: Long) = byteArrayOf(0x10, (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun el(i: Long, body: ByteArray) = id(i) + size(body.size.toLong()) + body
    private fun unknown(i: Long) = id(i) + byteArrayOf(0xFF.toByte())
    private fun uint(i: Long, v: Long) = el(i, byteArrayOf((v shr 8).toByte(), v.toByte()))
    private fun str(i: Long, s: String) = el(i, s.toByteArray())
    private fun block(track: Int, rel: Int, data: ByteArray) =
        byteArrayOf((0x80 or track).toByte(), (rel shr 8).toByte(), rel.toByte(), 0) + data

    private fun deflate(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        DeflaterOutputStream(o).use { it.write(b) }
        return o.toByteArray()
    }

    private fun tracks(vararg entries: ByteArray) = el(0x1654AE6B, entries.fold(ByteArray(0)) { a, b -> a + b })

    private fun entry(num: Int, codec: String, vararg extra: ByteArray) =
        el(0xAE, uint(0xD7, num.toLong()) + str(0x86, codec) + extra.fold(ByteArray(0)) { a, b -> a + b })

    private fun header() = el(0x1A45DFA3, str(0x4282, "matroska"))

    private fun run(bytes: ByteArray, track: Int) =
        MatroskaSubtitles.extract({ ByteArrayInputStream(bytes) }, track, bytes.size.toLong(), "windows-1254", { false }, {})

    @Test
    fun zlibAndHeaderStripUnknownSizeClusterAndMissingDuration() {
        val zlib = el(0x6D80, el(0x6240, el(0x5034, uint(0x4254, 0))))
        val strip = el(0x6D80, el(0x6240, el(0x5034, uint(0x4254, 3) + el(0x4255, "Hel".toByteArray()))))
        val bytes = header() + unknown(0x18538067) +
            el(0x1549A966, el(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40))) + // 1 ms
            tracks(entry(1, "V_TEST"), entry(2, "S_TEXT/UTF8", zlib), entry(3, "S_TEXT/UTF8", strip)) +
            unknown(0x1F43B675) + uint(0xE7, 1000) +
            el(0xA3, block(1, 0, ByteArray(500))) +
            el(0xA0, el(0xA1, block(2, 0, deflate("<i>Merhaba</i>".toByteArray()))) + uint(0x9B, 1500)) +
            el(0xA3, block(3, 200, "lo".toByteArray())) +   // süresiz: sonraki satıra kadar
            el(0xA3, block(3, 2200, "lo again".toByteArray())) +
            unknown(0x1F43B675) + uint(0xE7, 10000) +
            el(0xA0, el(0xA1, block(2, 0, deflate("Second".toByteArray()))) + uint(0x9B, 800))
        assertEquals(listOf(Triple(1000L, 2500L, "Merhaba"), Triple(10000L, 10800L, "Second")),
            run(bytes, 2).map { Triple(it.startMs, it.endMs, it.text.toString()) })
        assertEquals(listOf(Triple(1200L, 3200L, "Hello"), Triple(3200L, 6200L, "Hello again")),
            run(bytes, 3).map { Triple(it.startMs, it.endMs, it.text.toString()) })
    }

    @Test
    fun tracksAfterClustersUsesSecondPass() {
        val bytes = header() + unknown(0x18538067) +
            el(0x1F43B675, uint(0xE7, 2000) + el(0xA0, el(0xA1, block(4, 10, "late tracks".toByteArray())) + uint(0x9B, 990))) +
            tracks(entry(4, "S_TEXT/UTF8"))
        val cues = run(bytes, 4)
        assertEquals(1, cues.size)
        assertEquals(2010L to 3000L, cues[0].startMs to cues[0].endMs)
    }

    @Test(expected = UnsupportedSubtitleException::class)
    fun imageCodecIsRejected() {
        val bytes = header() + unknown(0x18538067) + tracks(entry(2, "S_HDMV/PGS")) +
            el(0x1F43B675, uint(0xE7, 0) + el(0xA3, block(2, 0, ByteArray(10))))
        run(bytes, 2)
    }

    @Test
    fun truncatedFileKeepsWhatWasRead() {
        val full = header() + unknown(0x18538067) + tracks(entry(2, "S_TEXT/UTF8")) +
            unknown(0x1F43B675) + uint(0xE7, 0) +
            el(0xA0, el(0xA1, block(2, 100, "one".toByteArray())) + uint(0x9B, 900)) +
            el(0xA0, el(0xA1, block(2, 3000, "two is cut".toByteArray())) + uint(0x9B, 900))
        val cues = run(full.copyOf(full.size - 6), 2)
        assertEquals(listOf("one"), cues.map { it.text.toString() })
    }

    // ---------------------------------------------------------------- yardımcılar

    @Test
    fun tx3gSamplesUseEmptySamplesAsEnds() {
        fun s(t: String) = byteArrayOf((t.toByteArray().size shr 8).toByte(), t.toByteArray().size.toByte()) + t.toByteArray()
        assertEquals("Merhaba", Tx3g.text(s("Merhaba") + byteArrayOf(0, 0, 0, 12) + "styl".toByteArray()))
        val samples = listOf(0L to "", 1_000_000L to "a", 2_500_000L to "", 4_250_000L to "b", 4_900_000L to "")
        assertEquals(listOf(1000L to 2500L, 4250L to 4900L), Tx3g.cues(samples, 12_400_000).map { it.startMs to it.endMs })
        assertEquals(listOf(1000L to 12400L), Tx3g.cues(listOf(1_000_000L to "last"), 12_400_000).map { it.startMs to it.endMs })
    }

    @Test
    fun trackListParsing() {
        val json = """[{"id":1,"type":"video","src-id":1,"codec":"h264"},
            {"id":1,"type":"sub","src-id":3,"codec":"subrip","lang":"eng","title":"English","default":true},
            {"id":2,"type":"sub","src-id":4,"codec":"hdmv_pgs_subtitle","lang":"tur"},
            {"id":3,"type":"sub","src-id":5,"codec":"subrip","lang":"ger","forced":true},
            {"id":4,"type":"sub","codec":"subrip","external":true}]"""
        val t = EmbeddedSubtitles.parseTrackList(json)
        assertEquals(listOf(1, 2, 3), t.map { it.id })
        assertEquals(listOf(3, 4, 5), t.map { it.srcId })
        assertTrue(t[0].isText)
        assertTrue(t[1].isImage)
        assertEquals(listOf(0, 0, 1), t.map { it.ordinal })
        assertEquals("en", t[0].languageCode)
        assertEquals("tr", t[1].languageCode)
        assertEquals("de", t[2].languageCode)
        assertTrue(t[2].label().contains("Forced"))
        assertTrue(EmbeddedSubtitles.parseTrackList(null).isEmpty())
        assertNull(EmbeddedSubtitles.iso1("und"))
    }

    @Test
    fun plainTextHelpers() {
        assertEquals("Hi & bye\nnext", SubtitleParser.plainText("{\\an8}<i>Hi</i> &amp; <font color=\"#fff\">bye</font><br>next"))
        assertEquals("a\nb c", SubtitleParser.assEventText("12,0,Default,,0,0,0,,{\\b1}a\\Nb\\hc"))
        assertNull(SubtitleParser.assEventText("1,0,Default,,0,0,0,,{\\p1}m 0 0 l 10 10"))
        assertEquals("1\n<i>italic</i>\nplain", SubtitleParser.italicSlashMarkup("1\n/italic\nplain"))
    }
}
