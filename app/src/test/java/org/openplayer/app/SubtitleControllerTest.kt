package org.openplayer.app

import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Sahte motor: yazılan özellikleri ve komutları kaydeder. */
class SubtitleFakeEngine : MpvEngine {
    val props = LinkedHashMap<String, String>()
    val commands = ArrayList<List<String>>()
    val watchers = HashMap<String, (String?) -> Unit>()
    val listeners = ArrayList<(MpvEvent) -> Unit>()
    var trackList: List<MpvTrack> = emptyList()
    override val events: SharedFlow<MpvEvent> = MutableSharedFlow()
    override fun addListener(listener: (MpvEvent) -> Unit) { listeners += listener }
    override fun removeListener(listener: (MpvEvent) -> Unit) { listeners -= listener }
    override val isReleased = false
    override val isPlaying = true
    override val playWhenReady = true
    override var positionMs = 0L
    override val durationMs = 60_000L
    override val speed = 1f
    override fun open(uri: String, startMs: Long, paused: Boolean) = Unit
    override fun stop() = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long, exact: Boolean) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun setVolume(percent: Int) = Unit
    override fun command(vararg args: String) { commands += args.toList() }
    override fun setProperty(name: String, value: String) { props[name] = value }
    override fun getProperty(name: String): String? = props[name]
    override fun getPropertyJson(name: String): String? = null
    override fun observe(property: String, callback: (String?) -> Unit): AutoCloseable {
        watchers[property] = callback
        callback(null)
        return AutoCloseable { watchers.remove(property) }
    }
    override fun tracks(): List<MpvTrack> = trackList
    override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
    override fun detachSurface() = Unit
    override fun setHardwareDecoding(value: String) = Unit
    override fun setVideoFilter(filter: String) = Unit
    override fun release() = Unit
    fun emit(e: MpvEvent) = listeners.toList().forEach { it(e) }
}

class SubtitleControllerTest {
    private lateinit var engine: SubtitleFakeEngine
    private lateinit var dir: File
    private var style = SubtitleStyle()
    private val shown = ArrayList<String>()

    private inner class Ov(val name: String) : SubtitleController.Overlay {
        var line: CharSequence? = null
        var box = 0
        var ghostText = false
        override fun show(line: CharSequence?, style: SubtitleStyle, textPx: Float, box: Int, ghostText: Boolean, assAlignment: Int) {
            this.line = line; this.box = box; this.ghostText = ghostText
            shown += "$name:$line"
        }
        override fun hide() { line = null }
    }

    private val main = Ov("main")
    private val ghost = Ov("ghost")

    private fun controller() = SubtitleController({ engine }, { style }, object : SubtitleController.Env {
        override val heightPx = 1000
        override fun spToPx(sp: Float) = sp * 2f
        override fun font(value: String): Pair<String?, String?> = if (value == "serif") "/fonts" to "Noto Serif" else "/fonts" to null
        override val tempDir: File get() = dir
        override val main: SubtitleController.Overlay = this@SubtitleControllerTest.main
        override val ghost: SubtitleController.Overlay = this@SubtitleControllerTest.ghost
    })

    @Before
    fun setUp() {
        TestStrings.installTurkish()
        engine = SubtitleFakeEngine()
        dir = Files.createTempDirectory("subs").toFile()
    }

    @After
    fun tearDown() {
        TestStrings.uninstall()
        dir.deleteRecursively()
    }

    private val cues = listOf(SubCue(1000, 2000, "one"), SubCue(5000, 6000, "two"), SubCue(9000, 9500, "three"))

    @Test
    fun styleMapsToMpvProperties() {
        style = SubtitleStyle(sizeSp = 20f, color = 0xFFFFE600.toInt(), background = 0x99000000.toInt(), font = "serif", bold = true, align = 0, bottomFraction = 0.1f)
        controller().applyStyle()
        val p = engine.props
        // 40 px, 1000 px yükseklik → 40*720/1000*1,17
        assertEquals("33.70", p["sub-font-size"])
        assertEquals("#FFFFE600", p["sub-color"])
        assertEquals("background-box", p["sub-border-style"])
        assertEquals("#99000000", p["sub-back-color"])
        assertEquals("yes", p["sub-bold"])
        assertEquals("Noto Serif", p["sub-font"])
        assertEquals("/fonts", p["sub-fonts-dir"])
        assertEquals("left", p["sub-align-x"])
        assertEquals("90.00", p["sub-pos"])
        assertEquals("yes", p["sub-ass-override"])
        assertTrue(p.keys.indexOf("sub-pos") > p.keys.indexOf("sub-back-color"))
    }

    @Test
    fun edgeTypes() {
        style = SubtitleStyle(edge = SubtitleLook.EDGE_NONE)
        controller().applyStyle()
        assertEquals("0.00", engine.props["sub-outline-size"])
        assertEquals("0.00", engine.props["sub-shadow-offset"])
        assertEquals("outline-and-shadow", engine.props["sub-border-style"])
        style = SubtitleStyle(edge = SubtitleLook.EDGE_SHADOW)
        controller().applyStyle()
        assertTrue(engine.props["sub-shadow-offset"]!!.toFloat() > 0f)
    }

    @Test
    fun delayAndSpeedGoToMpv() {
        val c = controller()
        c.delayMs = -1500
        c.speed = 25.0 / 23.976
        assertEquals("-1.500", engine.props["sub-delay"])
        assertEquals("0.959040", engine.props["sub-speed"])
    }

    @Test
    fun externalWaitsForFileLoadedThenAddsUtf8Copy() {
        val c = controller()
        c.useExternal(cues, "film.tr.srt", "1\n00:00:01,000 --> 00:00:02,000\nŞç\n")
        assertTrue(engine.commands.none { it[0] == "sub-add" })
        engine.trackList = listOf(MpvTrack(1, MpvTrackType.VIDEO, null, null, "h264", true, false, false, false))
        engine.emit(MpvEvent.FileLoaded)
        val add = engine.commands.single { it[0] == "sub-add" }
        assertEquals("select", add[2])
        assertEquals("film.tr.srt", add[3])
        assertTrue(File(add[1]).readText(Charsets.UTF_8).contains("Şç"))
        assertEquals(SubtitleController.Mode.EXTERNAL, c.mode)
        assertEquals(3, c.externalCues.size)
    }

    @Test
    fun exoticFormatIsConvertedToSrtAndOldExternalRemoved() {
        engine.trackList = listOf(MpvTrack(7, MpvTrackType.SUB, null, null, "subrip", true, false, false, true))
        val c = controller()
        c.useExternal(cues, "film.smi", "<SAMI>...")
        val add = engine.commands.last()
        assertEquals(listOf("sub-remove", "7"), engine.commands.first())
        assertTrue(add[1].endsWith(".srt"))
        assertTrue(File(add[1]).readText().contains("00:00:05,000 --> 00:00:06,000"))
    }

    @Test
    fun embeddedAndOff() {
        engine.trackList = listOf(MpvTrack(3, MpvTrackType.SUB, null, "eng", "hdmv_pgs_subtitle", false, false, false, false))
        val c = controller()
        c.useEmbedded(3)
        assertEquals("3", engine.props["sid"])
        assertTrue(c.showing) // görüntü altyazı: metin yok ama hareket alanı açık
        assertTrue(c.stepEmbedded(true))
        assertEquals(listOf("sub-seek", "1"), engine.commands.last())
        c.setOff()
        assertEquals("no", engine.props["sid"])
        assertFalse(c.showing)
        assertFalse(c.stepEmbedded(false))
    }

    @Test
    fun swipeHidesMpvAndShowsOverlayThenRestores() {
        engine.trackList = listOf(MpvTrack(1, MpvTrackType.VIDEO, null, null, "h264", true, false, false, false))
        val c = controller()
        c.useExternal(cues, "a.srt", null)
        engine.watchers["sub-text"]!!("one")
        assertTrue(c.showing)
        engine.positionMs = 1500
        assertTrue(c.prepareGhost(true))
        assertEquals("two", ghost.line)
        assertEquals("one", main.line)
        assertEquals("no", engine.props["sub-visibility"])
        assertFalse(c.prepareGhost(false)) // öncesinde satır yok
        c.clearGhost()
        assertNull(main.line)
        assertEquals("yes", engine.props["sub-visibility"])
    }

    @Test
    fun grabShowsBoxOnlyAndKeepsMpvVisible() {
        val c = controller()
        c.applyStyle()
        engine.watchers["sub-text"]!!("hello")
        c.setGrab(true)
        assertEquals("hello", main.line)
        assertTrue(main.ghostText)
        assertEquals(SubtitleController.GRAB_BACKGROUND, main.box)
        assertEquals("yes", engine.props["sub-visibility"])
        c.setGrab(false)
        assertNull(main.line)
    }

    @Test
    fun overlayOnlyForVr() {
        val c = controller()
        c.applyStyle()
        engine.watchers["sub-text"]!!("vr line")
        c.overlayOnly = true
        assertEquals("no", engine.props["sub-visibility"])
        assertEquals("vr line", main.line)
        engine.watchers["sub-text"]!!("next")
        assertEquals("next", main.line)
        c.overlayOnly = false
        assertEquals("yes", engine.props["sub-visibility"])
    }

    @Test
    fun nextAndPreviousRespectDelayAndSpeed() {
        val c = controller()
        c.useExternal(cues, "a.srt", null)
        c.delayMs = 1000
        assertEquals(6000L, c.nextCueMs(3000))
        assertEquals(2000L, c.previousCueMs(5000))
        assertNull(c.nextCueMs(20_000))
    }

    @Test
    fun italicSlashRewritesMpvCopy() {
        style = SubtitleStyle(italicSlash = true)
        engine.trackList = listOf(MpvTrack(1, MpvTrackType.VIDEO, null, null, "h264", true, false, false, false))
        val c = controller()
        c.useExternal(cues, "a.srt", "1\n00:00:01,000 --> 00:00:02,000\n/whisper\n")
        assertTrue(File(engine.commands.last()[1]).readText().contains("<i>whisper</i>"))
        style = SubtitleStyle(italicSlash = false)
        c.applyStyle()
        assertTrue(File(engine.commands.last()[1]).readText().contains("/whisper"))
    }
}
