package org.openplayer.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Ayarlar: ana sayfada kategoriler, her kategori kendi sayfasında. */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "org.openplayer.app.SETTINGS_PAGE"
    }

    private enum class Page(val title: String, val summary: String, val icon: Int) {
        PLAYER(Tx.s(R.string.settingsac_002), Tx.s(R.string.settingsac_001), R.drawable.ic_play),
        GESTURES(Tx.s(R.string.settingsac_274), Tx.s(R.string.settingsac_003), R.drawable.ic_aspect),
        VIDEO(Tx.s(R.string.settingsac_005), Tx.s(R.string.settingsac_004), R.drawable.ic_tune),
        AUDIO(Tx.s(R.string.gesturecon_015), Tx.s(R.string.settingsac_006), R.drawable.ic_volume_up),
        SUBTITLE(Tx.s(R.string.mediainfo_014), Tx.s(R.string.settingsac_007), R.drawable.ic_subtitles),
        ONLINE(Tx.s(R.string.settingsac_009), Tx.s(R.string.settingsac_008), R.drawable.ic_cloud_download),
        TRANSLATE(Tx.s(R.string.settingsac_011), Tx.s(R.string.settingsac_010), R.drawable.ic_subtitles),
        VR360("360° / VR", Tx.s(R.string.settingsac_012), R.drawable.ic_public),
        MUSIC(Tx.s(R.string.settingsac_014), Tx.s(R.string.settingsac_013), R.drawable.ic_music_note),
        THEME(Tx.s(R.string.settingsac_016), Tx.s(R.string.settingsac_015), R.drawable.ic_tune),
        LIST(Tx.s(R.string.settingsac_275), Tx.s(R.string.settingsac_017), R.drawable.ic_view_list),
    }

    private val exportTheme = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(ThemeManager.exportJson().toByteArray()) }
            android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_018), android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_019), android.widget.Toast.LENGTH_SHORT).show() }
    }

    private val importTheme = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes(4097).toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null && ThemeManager.importJson(this, text)) {
                ThemeManager.reload(); recreate()
            } else {
                android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_020), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportAll = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(SettingsBackup.export(this).toByteArray()) }
            android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_021), android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_019), android.widget.Toast.LENGTH_SHORT).show() }
    }

    private val importAll = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes(262_145).toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null && SettingsBackup.import(this, text)) {
                android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_022), android.widget.Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_023), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importFont = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val app = applicationContext
            Thread {
                val value = SubtitleLook.importFont(app, uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (value == null) {
                        android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_024), android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        s.subtitleFont = value
                        android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_025), android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }.start()
        }
    }

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private lateinit var s: AppSettings
    private lateinit var content: LinearLayout
    private var page: Page? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        // AppCompat kendi yapılandırmasıyla yoğunluk ölçeğini ezmesin.
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = AppSettings.get(this)
        ThemeManager.applyWindow(this)
        page = intent.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name } }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(UiColors.background)
                addView(content)
            }
        )
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(titleBar(page?.title ?: Tx.s(R.string.mainactivi_032)))
        when (page) {
            null -> home()
            Page.PLAYER -> playerPage()
            Page.GESTURES -> gesturesPage()
            Page.VIDEO -> videoPage()
            Page.AUDIO -> audioPage()
            Page.SUBTITLE -> subtitlePage()
            Page.ONLINE -> onlinePage()
            Page.TRANSLATE -> translatePage()
            Page.VR360 -> vrPage()
            Page.LIST -> listPage()
            Page.THEME -> themePage()
            Page.MUSIC -> musicPage()
        }
    }

    private fun home() {
        Page.entries.forEach { p ->
            categoryRow(p.icon, p.title, p.summary) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, p.name))
            }
        }
        section(Tx.s(R.string.settingsac_026))
        action(Tx.s(R.string.settingsac_028), Tx.s(R.string.settingsac_027)) {
            exportAll.launch("openplayer-ayarlar.json")
        }
        action(Tx.s(R.string.settingsac_030), Tx.s(R.string.settingsac_029)) {
            importAll.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        action(Tx.s(R.string.settingsac_032), Tx.s(R.string.settingsac_031)) {
            PanelBuilder(this)
                .setTitle(Tx.s(R.string.settingsac_033))
                .setMessage(Tx.s(R.string.settingsac_034))
                .setPositiveButton(Tx.s(R.string.musiccontr_018)) { _, _ ->
                    s.resetAll(this)
                    ThemeManager.reload()
                    android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_035), android.widget.Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton(Tx.s(R.string.playermenu_081), null)
                .show()
        }
        section(Tx.s(R.string.settingsac_036))
        action(Tx.s(R.string.settingsac_276), Tx.s(R.string.settingsac_037)) {
            BugReport.copyToClipboard(this) { n ->
                android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_038, n), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        section(Tx.s(R.string.settingsac_039))
        action("OpenPlayer ${versionName()}", Tx.s(R.string.settingsac_040)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avsessisozx/Openplayer")))
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun vrPage() {
        section(Tx.s(R.string.settingsac_041))
        toggle(Tx.s(R.string.settingsac_043), Tx.s(R.string.settingsac_042), s.vrTwoFingerRotate) { s.vrTwoFingerRotate = it }
        toggle(Tx.s(R.string.settingsac_045), Tx.s(R.string.settingsac_044), s.vrHorizonLevel) { s.vrHorizonLevel = it }
        toggle(Tx.s(R.string.settingsac_047), Tx.s(R.string.settingsac_046), s.vrLockRotation) { s.vrLockRotation = it }
        toggle(Tx.s(R.string.settingsac_049), Tx.s(R.string.settingsac_048), s.vrSensorAlt) { s.vrSensorAlt = it }
        section(Tx.s(R.string.settingsac_050))
        toggle(Tx.s(R.string.settingsac_052), Tx.s(R.string.settingsac_051), s.vrAutoRotate) { s.vrAutoRotate = it }
        toggle(Tx.s(R.string.settingsac_053), null, s.vrTapStopsAutoRotate) { s.vrTapStopsAutoRotate = it }
        toggle(Tx.s(R.string.settingsac_055), Tx.s(R.string.settingsac_054), s.vrTapStopsSensor) { s.vrTapStopsSensor = it }
        toggle(Tx.s(R.string.settingsac_057), Tx.s(R.string.settingsac_056), s.vrHoldWheel) { s.vrHoldWheel = it }
    }

    private fun playerPage() {
        section(Tx.s(R.string.settingsac_058))
        choice(Tx.s(R.string.settingsac_059), s.resumeMode.label, ResumeMode.entries.map { it.label }, s.resumeMode.ordinal) {
            s.resumeMode = ResumeMode.entries[it]
        }
        toggle(Tx.s(R.string.settingsac_061), Tx.s(R.string.settingsac_060), s.resumeOnlyFirst) { s.resumeOnlyFirst = it }
        section(Tx.s(R.string.playeracti_037))
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        choice(Tx.s(R.string.settingsac_062), "${s.playbackSpeed}x", speeds.map { "${it}x" }, speeds.indexOfFirst { kotlin.math.abs(it - s.playbackSpeed) < 0.01f }) {
            s.playbackSpeed = speeds[it]
        }
        toggle(Tx.s(R.string.settingsac_064), Tx.s(R.string.settingsac_063), s.longPressSpeed) { s.longPressSpeed = it }
        val fast = listOf(1.5f, 2f, 2.5f, 3f, 4f)
        choice(Tx.s(R.string.settingsac_065), "${s.longPressSpeedValue}x", fast.map { "${it}x" }, fast.indexOfFirst { kotlin.math.abs(it - s.longPressSpeedValue) < 0.01f }) {
            s.longPressSpeedValue = fast[it]
        }
        section(Tx.s(R.string.settingsac_066))
        toggle(Tx.s(R.string.settingsac_068), Tx.s(R.string.settingsac_067), s.perVideoMemory) { s.perVideoMemory = it }
        toggle(Tx.s(R.string.settingsac_070), Tx.s(R.string.settingsac_069), s.backToList) { s.backToList = it }
        toggle(Tx.s(R.string.settingsac_072), Tx.s(R.string.settingsac_071), s.skipOnError) { s.skipOnError = it }
        toggle(Tx.s(R.string.settingsac_073), null, s.doubleBackToExit) { s.doubleBackToExit = it }
        toggle(Tx.s(R.string.settingsac_277), Tx.s(R.string.settingsac_074), s.skipSilence) { s.skipSilence = it }
        val intro = listOf(30, 60, 85, 90, 120, 180)
        choice(Tx.s(R.string.settingsac_076), Tx.s(R.string.settingsac_075, s.skipIntroSec), intro.map { "$it sn" }, intro.indexOf(s.skipIntroSec)) {
            s.skipIntroSec = intro[it]
        }
        val edges = listOf(0, 12, 24, 36, 48)
        choice(Tx.s(R.string.settingsac_077), if (s.edgeGuardDp == 0) Tx.s(R.string.audiofx_004) else "${s.edgeGuardDp} dp", edges.map { if (it == 0) Tx.s(R.string.audiofx_004) else "$it dp" }, edges.indexOf(s.edgeGuardDp)) {
            s.edgeGuardDp = edges[it]
        }
        toggle(Tx.s(R.string.settings_039), Tx.s(R.string.settingsac_078), s.backgroundPlay) { s.backgroundPlay = it }
        toggle(Tx.s(R.string.settingsac_080), Tx.s(R.string.settingsac_079), s.autoPip) { s.autoPip = it }
        choice(Tx.s(R.string.settingsac_082), if (s.pipSeekButtons) Tx.s(R.string.settingsac_278) else Tx.s(R.string.settingsac_081),
            listOf(Tx.s(R.string.settingsac_081), Tx.s(R.string.settingsac_278)), if (s.pipSeekButtons) 1 else 0) { s.pipSeekButtons = it == 1 }
        section(Tx.s(R.string.settingsac_083))
        toggle(Tx.s(R.string.settingsac_085), Tx.s(R.string.settingsac_084), s.keepScreenOn) { s.keepScreenOn = it }
        toggle(Tx.s(R.string.settingsac_086), null, s.showControlsAtStart) { s.showControlsAtStart = it }
        toggle(Tx.s(R.string.settingsac_088), Tx.s(R.string.settingsac_087), s.rememberBrightness) { s.rememberBrightness = it }
        choice(Tx.s(R.string.settingsac_089), s.osdInfo.label, OsdInfo.entries.map { it.label }, s.osdInfo.ordinal) {
            s.osdInfo = OsdInfo.entries[it]
        }
        toggle(Tx.s(R.string.settingsac_091), Tx.s(R.string.settingsac_090), s.topBarClock) { s.topBarClock = it }
        toggle(Tx.s(R.string.settingsac_092), null, s.lockRotation) { s.lockRotation = it }
        section(Tx.s(R.string.settingsac_093))
        toggle(Tx.s(R.string.settingsac_094), null, s.showPrevNext) { s.showPrevNext = it }
        toggle(Tx.s(R.string.settingsac_096), Tx.s(R.string.settingsac_095), s.showSeekButtons) { s.showSeekButtons = it }
        val steps = listOf(5, 10, 15, 20, 30, 60)
        choice(Tx.s(R.string.settingsac_097), "${s.seekButtonSec} sn", steps.map { "$it sn" }, steps.indexOf(s.seekButtonSec)) {
            s.seekButtonSec = steps[it]
        }
        section(Tx.s(R.string.settingsac_098))
        toggle(Tx.s(R.string.playermenu_127), Tx.s(R.string.settingsac_099), s.showRemainingTime) { s.showRemainingTime = it }
        toggle(Tx.s(R.string.settingsac_101), Tx.s(R.string.settingsac_100), s.showShortcuts) { s.showShortcuts = it }
        val sizes = listOf(50, 60, 70, 80, 90, 100, 120, 150)
        choice(Tx.s(R.string.settingsac_102), "%${s.shortcutSizePercent}", sizes.map { "%$it" }, sizes.indexOf(s.shortcutSizePercent)) {
            s.shortcutSizePercent = sizes[it]
        }
        val ops = listOf(0, 10, 20, 35, 50, 70, 100)
        choice(Tx.s(R.string.settingsac_104), Tx.s(R.string.settingsac_103, s.shortcutOpacityPercent), ops.map { "%$it" }, ops.indexOf(s.shortcutOpacityPercent)) {
            s.shortcutOpacityPercent = ops[it]
        }
        val scrims = listOf(0, 10, 20, 30, 45, 60, 80)
        choice(Tx.s(R.string.settingsac_105), "%${s.scrimPercent}", scrims.map { "%$it" }, scrims.indexOf(s.scrimPercent)) {
            s.scrimPercent = scrims[it]
        }
        val hudMs = listOf(0, 100, 200, 300, 500, 700, 1000, 1500, 2000, 3000)
        choice(Tx.s(R.string.settingsac_106), "${s.hudHideMs} ms", hudMs.map { "$it ms" }, hudMs.indexOf(s.hudHideMs)) {
            s.hudHideMs = hudMs[it]
        }
        choice(Tx.s(R.string.settingsac_107), s.orientation.label, OrientationMode.entries.map { it.label }, s.orientation.ordinal) {
            s.orientation = OrientationMode.entries[it]
        }
        val timeouts = listOf(2, 3, 4, 5, 8, 10)
        choice(Tx.s(R.string.settingsac_108), "${s.controlsTimeoutSec} sn", timeouts.map { "$it sn" }, timeouts.indexOf(s.controlsTimeoutSec)) {
            s.controlsTimeoutSec = timeouts[it]
        }
    }

    private fun gesturesPage() {
        section(Tx.s(R.string.settingsac_109))
        val prefs = GesturePrefs(this)
        note(Tx.s(R.string.settingsac_110))
        content.addView(ZoneEditor(this, prefs), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(8)) })
        for (zone in GesturePrefs.zones) {
            choice(Tx.s(R.string.settingsac_111, GesturePrefs.zoneLabel(zone)), prefs.get(zone).label,
                GestureAction.entries.map { it.label }, prefs.get(zone).ordinal) {
                prefs.set(zone, GestureAction.entries[it])
            }
        }
        action(Tx.s(R.string.settingsac_113), Tx.s(R.string.settingsac_112)) {
            prefs.set("left", GestureAction.BRIGHTNESS)
            prefs.set("mid", GestureAction.NONE)
            prefs.set("right", GestureAction.VOLUME)
            prefs.split1 = 0.5f
            prefs.split2 = 0.5f
            render()
        }
        section(Tx.s(R.string.settingsac_114))
        toggle(Tx.s(R.string.settingsac_116), Tx.s(R.string.settingsac_115), s.liveScrub) { s.liveScrub = it }
        toggle(Tx.s(R.string.settingsac_118), Tx.s(R.string.settingsac_117), s.exactSeekOnRelease) { s.exactSeekOnRelease = it }
        toggle(Tx.s(R.string.settingsac_119), null, s.horizontalSeekEnabled) { s.horizontalSeekEnabled = it }
        val swipeOptions = listOf(30, 60, 90, 120, 180, 300)
        choice(Tx.s(R.string.settingsac_120), "${s.swipeSeekSec} sn", swipeOptions.map { "$it sn" }, swipeOptions.indexOf(s.swipeSeekSec)) {
            s.swipeSeekSec = swipeOptions[it]
        }
        section(Tx.s(R.string.settingsac_050))
        choice(Tx.s(R.string.settingsac_121), s.tapAction.label, TapAction.entries.map { it.label }, s.tapAction.ordinal) {
            s.tapAction = TapAction.entries[it]
        }
        choice(Tx.s(R.string.settingsac_122), s.doubleTapAction.label, DoubleTapAction.entries.map { it.label }, s.doubleTapAction.ordinal) {
            s.doubleTapAction = DoubleTapAction.entries[it]
        }
        val tapOptions = listOf(5, 10, 15, 20, 30)
        choice(Tx.s(R.string.settingsac_123), "${s.doubleTapSeekSec} sn", tapOptions.map { "$it sn" }, tapOptions.indexOf(s.doubleTapSeekSec)) {
            s.doubleTapSeekSec = tapOptions[it]
        }
        section(Tx.s(R.string.settingsac_124))
        toggle(Tx.s(R.string.settingsac_125), null, s.pinchZoomEnabled) { s.pinchZoomEnabled = it }
        toggle(Tx.s(R.string.settingsac_127), Tx.s(R.string.settingsac_126), s.quickZoom) { s.quickZoom = it }
        toggle(Tx.s(R.string.settingsac_129), Tx.s(R.string.settingsac_128), s.subtitleGestures) { s.subtitleGestures = it }
    }

    private fun videoPage() {
        section(Tx.s(R.string.playermenu_091))
        choice(Tx.s(R.string.settingsac_130), s.decoder.label, DecoderMode.entries.map { it.label }, DecoderMode.entries.indexOf(s.decoder)) {
            s.decoder = DecoderMode.entries[it]
        }
        choice(Tx.s(R.string.settingsac_131), s.playbackEngine.label, PlaybackEngine.entries.map { it.label }, s.playbackEngine.ordinal) {
            s.playbackEngine = PlaybackEngine.entries[it]
        }
        toggle(Tx.s(R.string.settingsac_133), Tx.s(R.string.settingsac_132), s.autoDecoderFallback) { s.autoDecoderFallback = it }
        note(Tx.s(R.string.settingsac_134))
        note(Tx.s(R.string.settingsac_135))
    }

    private fun audioPage() {
        section(Tx.s(R.string.settingsac_136))
        val boosts = listOf(100, 125, 150, 175, 200)
        choice(Tx.s(R.string.settingsac_137), "%${s.volumeBoostPercent}", boosts.map { "%$it" }, boosts.indexOf(s.volumeBoostPercent)) {
            s.volumeBoostPercent = boosts[it]
        }
        note(Tx.s(R.string.settingsac_138))
        section(Tx.s(R.string.settingsac_139))
        choice(Tx.s(R.string.playermenu_085), s.stereoMode.label, StereoMode.entries.map { it.label }, s.stereoMode.ordinal) {
            s.stereoMode = StereoMode.entries[it]
            AudioBalance.update(s)
        }
        choice(Tx.s(R.string.playermenu_086), AudioFx.reverbLabels[s.reverbPreset], AudioFx.reverbLabels, s.reverbPreset) {
            s.reverbPreset = it
        }
        toggle(Tx.s(R.string.settingsac_141), Tx.s(R.string.settingsac_140), s.audioFade) { s.audioFade = it }
        section(Tx.s(R.string.settingsac_142))
        toggle(Tx.s(R.string.settingsac_144), Tx.s(R.string.settingsac_143), s.audioFocus) { s.audioFocus = it }
        toggle(Tx.s(R.string.settingsac_146), Tx.s(R.string.settingsac_145), s.pauseOnNoisy) { s.pauseOnNoisy = it }
        section(Tx.s(R.string.musiccontr_002))
        toggle(Tx.s(R.string.settingsac_148), Tx.s(R.string.settingsac_147), s.eqEnabled) { s.eqEnabled = it }
        section(Tx.s(R.string.playermenu_002))
        text(Tx.s(R.string.settingsac_279), s.audioLanguages.ifEmpty { Tx.s(R.string.settingsac_150) }, Tx.s(R.string.settingsac_149)) { s.audioLanguages = it }
    }

    private fun subtitlePage() {
        section(Tx.s(R.string.mainactivi_053))
        toggle(Tx.s(R.string.settingsac_152), Tx.s(R.string.settingsac_151), s.autoEnableSubtitle) { s.autoEnableSubtitle = it }
        text(Tx.s(R.string.settingsac_153), s.subtitleLanguages, Tx.s(R.string.settingsac_149)) { s.subtitleLanguages = it.ifBlank { "tr,en" } }
        section(Tx.s(R.string.settingsac_098))
        val sizes = listOf(12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 42, 48)
        choice(Tx.s(R.string.settingsac_154), "${s.subtitleSizeSp.toInt()}", sizes.map { "$it" }, sizes.indexOf(s.subtitleSizeSp.toInt())) {
            s.subtitleSizeSp = sizes[it].toFloat()
        }
        val colors = listOf(Tx.s(R.string.playermenu_130) to 0xFFFFFFFF.toInt(), Tx.s(R.string.playermenu_025) to 0xFFFFE600.toInt(), Tx.s(R.string.playermenu_024) to 0xFF7CFF7C.toInt(), Tx.s(R.string.playermenu_023) to 0xFF66E5FF.toInt(),
            Tx.s(R.string.settingsac_281) to 0xFFFFB347.toInt(), Tx.s(R.string.settingsac_280) to 0xFFFF8AD8.toInt(), Tx.s(R.string.settingsac_155) to 0xFFD0D0D0.toInt())
        choice(Tx.s(R.string.playermenu_032), colors.firstOrNull { it.second == s.subtitleColor }?.first ?: Tx.s(R.string.settingsac_156), colors.map { it.first },
            colors.indexOfFirst { it.second == s.subtitleColor }) { s.subtitleColor = colors[it].second }
        val bgs = listOf(Tx.s(R.string.playermenu_027) to 0x00000000, Tx.s(R.string.playermenu_026) to 0x99000000.toInt(), Tx.s(R.string.playermenu_131) to 0xFF000000.toInt())
        choice(Tx.s(R.string.playermenu_132), bgs.firstOrNull { it.second == s.subtitleBackground }?.first ?: Tx.s(R.string.settingsac_156), bgs.map { it.first },
            bgs.indexOfFirst { it.second == s.subtitleBackground }) { s.subtitleBackground = bgs[it].second }
        val fonts = SubtitleLook.builtInFonts + SubtitleLook.importedFonts(this).map { "file:$it" to it }
        choice(Tx.s(R.string.settingsac_158), SubtitleLook.fontLabel(this, s.subtitleFont), fonts.map { it.second } + Tx.s(R.string.settingsac_157),
            fonts.indexOfFirst { it.first == s.subtitleFont }) { i ->
            if (i < fonts.size) s.subtitleFont = fonts[i].first
            else importFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }
        if (s.subtitleFont.startsWith("file:")) {
            action(Tx.s(R.string.settingsac_159), SubtitleLook.fontLabel(this, s.subtitleFont)) {
                SubtitleLook.deleteFont(this, s.subtitleFont.removePrefix("file:"))
                s.subtitleFont = "default"
                render()
            }
        }
        toggle(Tx.s(R.string.settingsac_160), null, s.subtitleBold) { s.subtitleBold = it }
        val edges = SubtitleLook.edges
        choice(Tx.s(R.string.settingsac_282), edges.firstOrNull { it.first == s.subtitleEdge }?.second ?: Tx.s(R.string.playermenu_033), edges.map { it.second },
            edges.indexOfFirst { it.first == s.subtitleEdge }) { s.subtitleEdge = edges[it].first }
        val edgeColors = listOf(Tx.s(R.string.playermenu_131) to 0xFF000000.toInt(), Tx.s(R.string.settingsac_283) to 0xFF333333.toInt(), Tx.s(R.string.playermenu_130) to 0xFFFFFFFF.toInt(),
            Tx.s(R.string.settingsac_284) to 0xFF1E40AF.toInt(), Tx.s(R.string.settingsac_161) to 0xFFB91C1C.toInt())
        choice(Tx.s(R.string.settingsac_285), edgeColors.firstOrNull { it.second == s.subtitleEdgeColor }?.first ?: Tx.s(R.string.settingsac_156), edgeColors.map { it.first },
            edgeColors.indexOfFirst { it.second == s.subtitleEdgeColor }) { s.subtitleEdgeColor = edgeColors[it].second }
        choice(Tx.s(R.string.settingsac_286), SubtitleLook.alignLabels[s.subtitleAlign], SubtitleLook.alignLabels, s.subtitleAlign) { s.subtitleAlign = it }
        toggle(Tx.s(R.string.settingsac_163), Tx.s(R.string.settingsac_162), s.subtitleItalicSlash) { s.subtitleItalicSlash = it }
        section(Tx.s(R.string.settingsac_164))
        val delays = listOf(-2000L, -1000L, -500L, -250L, 0L, 250L, 500L, 1000L, 2000L)
        choice(Tx.s(R.string.settingsac_165), "${s.subtitleDefaultDelayMs} ms", delays.map { (if (it > 0) "+" else "") + "$it ms" }, delays.indexOf(s.subtitleDefaultDelayMs)) {
            s.subtitleDefaultDelayMs = delays[it]
        }
        note(Tx.s(R.string.settingsac_166))
        section(Tx.s(R.string.playermenu_043))
        val charsets = listOf("auto", "UTF-8", "windows-1254", "ISO-8859-9", "windows-1252", "windows-1250", "windows-1251")
        choice(Tx.s(R.string.playermenu_043), s.subtitleCharset, charsets, charsets.indexOf(s.subtitleCharset)) {
            s.subtitleCharset = charsets[it]
        }
        val fallbacks = charsets.drop(2)
        choice(Tx.s(R.string.settingsac_167), s.subtitleFallbackCharset, fallbacks, fallbacks.indexOf(s.subtitleFallbackCharset)) {
            s.subtitleFallbackCharset = fallbacks[it]
        }
        note(Tx.s(R.string.settingsac_168))
    }

    private fun translatePage() {
        section(Tx.s(R.string.settingsac_169))
        val provs = TrProvider.entries
        choice(Tx.s(R.string.settingsac_170), s.trProvider.label, provs.map { it.label }, s.trProvider.ordinal) { s.trProvider = provs[it] }
        section(Tx.s(R.string.settingsac_171))
        val langs = TrLangs.all
        choice(Tx.s(R.string.settingsac_172), TrLangs.label(s.trTarget), langs.map { it.name }, langs.indexOfFirst { it.code == s.trTarget }) { s.trTarget = langs[it].code }
        val sources = listOf("auto") + langs.map { it.code }
        choice(Tx.s(R.string.settingsac_173), TrLangs.label(s.trSource), sources.map { TrLangs.label(it) }, sources.indexOf(s.trSource)) { s.trSource = sources[it] }
        note(Tx.s(R.string.settingsac_174))
        when (s.trProvider) {
            TrProvider.MYMEMORY -> {
                section("MyMemory")
                text(Tx.s(R.string.settingsac_176), s.trEmail.ifEmpty { Tx.s(R.string.settingsac_175) }, "ornek@eposta.com") { s.trEmail = it }
                note(Tx.s(R.string.settingsac_177) +
                    Tx.s(R.string.settingsac_178))
            }
            TrProvider.LIBRE -> {
                section("LibreTranslate")
                text(Tx.s(R.string.listpanel_021), s.trApiUrl.ifEmpty { Tx.s(R.string.settingsac_287) }, "https://sunucunuz.example.com") { s.trApiUrl = it }
                text(Tx.s(R.string.settingsac_179), if (s.trApiKey.isEmpty()) Tx.s(R.string.settingsac_287) else "••••••••", Tx.s(R.string.settingsac_288), password = true) { s.trApiKey = it }
                note(Tx.s(R.string.settingsac_289))
            }
            TrProvider.OPENAI -> {
                section(Tx.s(R.string.settingsac_180))
                action("Groq", Tx.s(R.string.settingsac_181)) { preset("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile") }
                action("OpenRouter", Tx.s(R.string.settingsac_182)) { preset("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free") }
                action("Google Gemini", Tx.s(R.string.settingsac_183)) { preset("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash") }
                action(Tx.s(R.string.settingsac_185), Tx.s(R.string.settingsac_184)) { preset("http://192.168.1.10:11434/v1", "llama3.1") }
                section(Tx.s(R.string.settingsac_186))
                text(Tx.s(R.string.listpanel_021), s.trApiUrl.ifEmpty { Tx.s(R.string.settingsac_287) }, "https://api.ornek.com/v1") { s.trApiUrl = it }
                text(Tx.s(R.string.settingsac_188), if (s.trApiKey.isEmpty()) Tx.s(R.string.settingsac_287) else "••••••••", Tx.s(R.string.settingsac_187), password = true) { s.trApiKey = it }
                text("Model", s.trModel.ifEmpty { Tx.s(R.string.settingsac_287) }, Tx.s(R.string.settingsac_189)) { s.trModel = it }
                action(Tx.s(R.string.settingsac_290), Tx.s(R.string.settingsac_190)) { pickModel() }
                note(Tx.s(R.string.settingsac_191))
            }
        }
        section(Tx.s(R.string.settingsac_192))
        action(Tx.s(R.string.settingsac_194), Tx.s(R.string.settingsac_193)) { testTranslate() }
        note(Tx.s(R.string.settingsac_195))
    }

    private fun preset(url: String, model: String) {
        s.trApiUrl = url
        s.trModel = model
        android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_196), android.widget.Toast.LENGTH_LONG).show()
        render()
    }

    private fun pickModel() {
        val cfg = s.translateConfig()
        Thread {
            val result = runCatching { SubtitleTranslator(cfg).listModels() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { ids ->
                    if (ids.isEmpty()) showMessage(Tx.s(R.string.settingsac_198), Tx.s(R.string.settingsac_197))
                    else {
                        val shown = ids.take(200)
                        showChoice(Tx.s(R.string.settingsac_199), shown, shown.indexOf(s.trModel)) { i ->
                            s.trModel = shown[i]
                            render()
                        }
                    }
                }.onFailure { showMessage(Tx.s(R.string.settingsac_200), it.message ?: Tx.s(R.string.playermenu_067)) }
            }
        }.start()
    }

    private fun testTranslate() {
        val cfg = s.translateConfig()
        val sample = listOf("Hello, how are you?", "I'll be right back.")
        Thread {
            val result = runCatching { SubtitleTranslator(cfg).translate(sample) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { out ->
                    showMessage(Tx.s(R.string.settingsac_201), sample.indices.joinToString("\n\n") { "${sample[it]}\n→ ${out[it]}" })
                }.onFailure { showMessage(Tx.s(R.string.settingsac_202), it.message ?: Tx.s(R.string.playermenu_067)) }
            }
        }.start()
    }

    private fun onlinePage() {
        section(Tx.s(R.string.settingsac_203))
        text(Tx.s(R.string.settingsac_188), if (s.osApiKey.isEmpty()) Tx.s(R.string.settingsac_287) else "••••••••", Tx.s(R.string.settingsac_204)) { s.osApiKey = it }
        text(Tx.s(R.string.settingsac_207), s.osUser.ifEmpty { Tx.s(R.string.settingsac_206) }, Tx.s(R.string.settingsac_205)) { s.osUser = it }
        text(Tx.s(R.string.networkact_016), if (s.osPass.isEmpty()) Tx.s(R.string.settingsac_206) else "••••••••", Tx.s(R.string.networkact_016), password = true) { s.osPass = it }
        note(Tx.s(R.string.settingsac_208))
    }

    private fun musicPage() {
        section(Tx.s(R.string.settingsac_209))
        toggle(Tx.s(R.string.settingsac_211), Tx.s(R.string.settingsac_210), s.musicMode) { s.musicMode = it }
        toggle(Tx.s(R.string.settingsac_213), Tx.s(R.string.settingsac_212), s.musicStartHome) { s.musicStartHome = it }
        val sorts = MusicSort.entries
        choice(Tx.s(R.string.settingsac_214), s.musicSort.label, sorts.map { it.label }, sorts.indexOf(s.musicSort)) {
            s.musicSort = sorts[it]
        }
        note(Tx.s(R.string.settingsac_215))
    }

    private fun themeApplied() {
        ThemeManager.reload()
        recreate()
    }

    private fun themePage() {
        section(Tx.s(R.string.settingsac_216))
        val all = ThemeManager.presets
        all.chunked(3).forEach { rowThemes ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(4), dp(14), dp(4))
            }
            for (i in 0 until 3) {
                val th = rowThemes.getOrNull(i)
                if (th == null) {
                    line.addView(View(this), LinearLayout.LayoutParams(0, dp(72), 1f))
                    continue
                }
                val sel = s.themeId == th.id
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(th.surface)
                        setStroke(dp(if (sel) 3 else 1), if (sel) th.accent else 0x33FFFFFF)
                    }
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(th.accent)
                        }
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(TextView(context).apply {
                        text = th.name
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    })
                    setOnClickListener { s.themeId = th.id; themeApplied() }
                }
                line.addView(card, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            content.addView(line)
        }
        if (s.themeId == "custom") note(Tx.s(R.string.settingsac_217))
        section(Tx.s(R.string.settingsac_218))
        val accents = ThemeManager.accentChoices
        val accIdx = accents.indexOfFirst { it.second == s.accentOverride }.coerceAtLeast(0)
        choice(Tx.s(R.string.settingsac_218), accents[accIdx].first, accents.map { it.first }, accIdx) {
            s.accentOverride = accents[it].second
            themeApplied()
        }
        choice(Tx.s(R.string.settingsac_219), s.uiMode.label, UiMode.entries.map { it.label }, s.uiMode.ordinal) {
            s.uiMode = UiMode.entries[it]
            recreate()
        }
        note(Tx.s(R.string.settingsac_220))
        section(Tx.s(R.string.settingsac_221))
        choice(Tx.s(R.string.settingsac_222), s.buttonShape.label, ButtonShape.entries.map { it.label }, s.buttonShape.ordinal) {
            s.buttonShape = ButtonShape.entries[it]
        }
        val pan = listOf(40, 50, 60, 70, 80, 90, 100)
        choice(Tx.s(R.string.settingsac_223), Tx.s(R.string.settingsac_103, s.panelOpacityPercent), pan.map { "%$it" }, pan.indexOf(s.panelOpacityPercent)) {
            s.panelOpacityPercent = pan[it]
        }
        section(Tx.s(R.string.settingsac_224))
        val sizes = listOf(50, 60, 70, 80, 90, 100, 120, 150)
        choice(Tx.s(R.string.settingsac_102), "%${s.shortcutSizePercent}", sizes.map { "%$it" }, sizes.indexOf(s.shortcutSizePercent)) {
            s.shortcutSizePercent = sizes[it]
        }
        val ops = listOf(0, 10, 20, 35, 50, 70, 100)
        choice(Tx.s(R.string.settingsac_104), "%${s.shortcutOpacityPercent}", ops.map { "%$it" }, ops.indexOf(s.shortcutOpacityPercent)) {
            s.shortcutOpacityPercent = ops[it]
        }
        val scrims = listOf(0, 10, 20, 30, 45, 60, 80)
        choice(Tx.s(R.string.settingsac_105), "%${s.scrimPercent}", scrims.map { "%$it" }, scrims.indexOf(s.scrimPercent)) {
            s.scrimPercent = scrims[it]
        }
        section(Tx.s(R.string.settingsac_225))
        action(Tx.s(R.string.settingsac_227), Tx.s(R.string.settingsac_226)) {
            exportTheme.launch("openplayer-tema.json")
        }
        action(Tx.s(R.string.settingsac_229), Tx.s(R.string.settingsac_228)) {
            importTheme.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        action(Tx.s(R.string.settingsac_231), Tx.s(R.string.settingsac_230)) {
            s.themeId = "dark"; s.accentOverride = 0; s.buttonShape = ButtonShape.CIRCLE; s.panelOpacityPercent = 80
            themeApplied()
        }
    }

    private fun listPage() {
        section(Tx.s(R.string.listpanel_012))
        toggle(Tx.s(R.string.settingsac_233), Tx.s(R.string.settingsac_232), s.useTrash) { s.useTrash = it }
        toggle(Tx.s(R.string.settingsac_235), Tx.s(R.string.settingsac_234), s.deleteSubtitlesToo) { s.deleteSubtitlesToo = it }
        toggle(Tx.s(R.string.settingsac_237), Tx.s(R.string.settingsac_236), s.exitButton) { s.exitButton = it }
        toggle(Tx.s(R.string.settingsac_239), Tx.s(R.string.settingsac_238), s.naturalSort) { s.naturalSort = it }
        val days = listOf(1, 3, 7, 14, 30)
        choice(Tx.s(R.string.settingsac_241), Tx.s(R.string.settingsac_240, s.newVideoDays), days.map { Tx.s(R.string.settingsac_240, it) }, days.indexOf(s.newVideoDays)) {
            s.newVideoDays = days[it]
        }
        toggle(Tx.s(R.string.settingsac_243), Tx.s(R.string.settingsac_242), s.show360) { s.show360 = it }
        val tsizes = listOf(70, 85, 100, 125, 160)
        val tnames = listOf(Tx.s(R.string.settingsac_247), Tx.s(R.string.settingsac_246), Tx.s(R.string.settingsac_291), Tx.s(R.string.settingsac_245), Tx.s(R.string.settingsac_244))
        choice(Tx.s(R.string.settingsac_248), tnames.getOrElse(tsizes.indexOf(s.listTextScale)) { "%${s.listTextScale}" }, tnames, tsizes.indexOf(s.listTextScale).coerceAtLeast(0)) {
            s.listTextScale = tsizes[it]
        }
        action(Tx.s(R.string.settingsac_250), Tx.s(R.string.settingsac_249)) {
            s.listScale = 100; s.gridScale = 100; s.folderScale = 100; s.listTextScale = 100; s.musicScale = 100
            android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_251), android.widget.Toast.LENGTH_SHORT).show()
        }
        toggle(Tx.s(R.string.listpanel_018), Tx.s(R.string.settingsac_252), s.showHiddenFolders) { s.showHiddenFolders = it }
        section(Tx.s(R.string.settingsac_253))
        toggle(Tx.s(R.string.settingsac_255), Tx.s(R.string.settingsac_254), s.markLastInFolder) { s.markLastInFolder = it }
        toggle(Tx.s(R.string.settingsac_256), null, s.scrollToLast) { s.scrollToLast = it }
        toggle(Tx.s(R.string.settingsac_258), Tx.s(R.string.settingsac_257), s.showFab) { s.showFab = it }
        toggle(Tx.s(R.string.settingsac_260), Tx.s(R.string.settingsac_259), s.swipeRefresh) { s.swipeRefresh = it }
        section(Tx.s(R.string.settingsac_261))
        toggle(Tx.s(R.string.settingsac_292), Tx.s(R.string.settingsac_262), s.incognito) { s.incognito = it }
        action(Tx.s(R.string.settingsac_264), Tx.s(R.string.settingsac_263)) {
            ResumeStore.clearAll(this)
            android.widget.Toast.makeText(this, Tx.s(R.string.settingsac_265), android.widget.Toast.LENGTH_SHORT).show()
        }
        section(Tx.s(R.string.settingsac_266))
        text("HTTP User-Agent", s.httpUserAgent.ifEmpty { Tx.s(R.string.settingsac_268) }, Tx.s(R.string.settingsac_267)) { s.httpUserAgent = it }
        note(Tx.s(R.string.settingsac_269))
        section(Tx.s(R.string.settingsac_270))
        action(Tx.s(R.string.settingsac_272), Tx.s(R.string.settingsac_271)) { openAllFilesAccess() }
    }

    private fun categoryRow(icon: Int, title: String, summary: String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        box.addView(iconView(icon, 44, 10).apply { setColorFilter(UiColors.accent) })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            addView(TextView(context).apply {
                text = summary
                setTextColor(UiColors.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        box.addView(iconView(R.drawable.ic_chevron, 32, 4).apply { setColorFilter(UiColors.textSecondary) })
        content.addView(box)
    }

    // ------------------------------------------------------------ satır türleri

    private fun titleBar(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(12), dp(20), dp(8))
        addView(iconView(R.drawable.ic_back).apply { setOnClickListener { finish() } })
        addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            setTextColor(UiColors.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(24), dp(20), dp(6))
        })
    }

    private fun note(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(2), dp(20), dp(6))
        })
    }

    private fun row(title: String, subtitle: String?, onClick: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(TextView(context).apply {
                text = title
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            if (subtitle != null) {
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(UiColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            content.addView(this)
        }

    private fun choice(title: String, current: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        row(title, current) {
            showChoice(title, items, selected) {
                onPick(it)
                render()
            }
        }
    }

    private fun toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            if (subtitle != null) {
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(UiColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
        }
        val sw = SwitchCompat(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(sw)
        box.setOnClickListener { sw.toggle() }
        content.addView(box)
    }

    private fun text(title: String, current: String, hint: String, password: Boolean = false, onSet: (String) -> Unit) {
        row(title, current) {
            showInput(title, hint, "", password) {
                onSet(it)
                render()
            }
        }
    }

    private fun action(title: String, subtitle: String?, onClick: () -> Unit) {
        row(title, subtitle, onClick)
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            showMessage(Tx.s(R.string.playermenu_101), Tx.s(R.string.settingsac_273))
        }
    }
}
