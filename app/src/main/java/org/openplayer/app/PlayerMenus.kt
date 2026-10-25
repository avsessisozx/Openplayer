package org.openplayer.app

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Oynatıcı ekranındaki tüm menü ve iletişim kutuları. */
class PlayerMenus(private val a: PlayerActivity) {

    // ------------------------------------------------------------ yardımcılar

    private fun mimeLabel(mime: String?): String = when (mime) {
        null -> ""
        "audio/ac3" -> "AC3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC3"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS-HD"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/true-hd" -> "TrueHD"
        else -> mime.substringAfter('/').uppercase()
    }

    private fun trackLabel(f: Format, index: Int): String {
        val lang = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("tr")) }?.takeIf { it.isNotBlank() }
        val name = f.label ?: lang ?: Tx.s(R.string.cuesheet_001, index + 1)
        val details = buildList {
            if (f.label != null && lang != null) add(lang)
            mimeLabel(f.sampleMimeType).takeIf { it.isNotEmpty() }?.let { add(it) }
            if (f.channelCount > 0) add("${f.channelCount} kanal")
        }
        return if (details.isEmpty()) name else "$name (${details.joinToString(", ")})"
    }

    private fun timeLabel(ms: Long) = formatDuration(ms)

    // ------------------------------------------------------------ ses

    fun showAudioTracks() {
        val groups = a.player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            a.toast(Tx.s(R.string.playermenu_001))
            return
        }
        val labels = groups.mapIndexed { i, g ->
            trackLabel(g.getTrackFormat(0), i) + if (!g.isSupported) " (desteklenmiyor)" else ""
        }
        val selected = groups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_002))
            .setSingleChoiceItems(labels.toTypedArray(), selected) { d, which ->
                val p = a.player
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[which].mediaTrackGroup, 0))
                    .build()
                val u = a.currentUri
                if (u != null && a.settings.perVideoMemory && !a.settings.incognito) VideoMemory.setAudio(a, u, which)
                d.dismiss()
            }
            .setPositiveButton(Tx.s(R.string.playermenu_003)) { _, _ -> showAudioDelay() }
            .setNeutralButton(Tx.s(R.string.musiccontr_002)) { _, _ -> showEqualizer() }
            .setNegativeButton(Tx.s(R.string.nowplaying_009), null)
            .show()
    }

    /** Ses gecikmesi: + sesi geciktirir, − öne alır. Bluetooth için ek gecikme ayrı tutulur. */
    fun showAudioDelay() {
        val s = a.settings
        fun fmt(ms: Long) = "${if (ms >= 0) "+" else ""}$ms ms"
        val delayLabel = a.label("", 16f)
        val btLabel = a.label("", 16f)
        fun refresh() {
            delayLabel.text = Tx.s(R.string.playermenu_004, fmt(s.audioDelayMs))
            btLabel.text = Tx.s(R.string.playermenu_005, fmt(s.bluetoothDelayMs))
        }
        refresh()
        fun row(onStep: (Long) -> Unit): LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((t, ms) in listOf("-100" to -100L, "-10" to -10L, "+10" to 10L, "+100" to 100L)) {
                addView(Button(a).apply {
                    text = t
                    setOnClickListener {
                        onStep(ms)
                        refresh()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val root = a.vertical {
            addView(delayLabel)
            addView(row { s.audioDelayMs += it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
            addView(btLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            addView(row { s.bluetoothDelayMs += it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        }
        PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_003))
            .setView(root)
            .setPositiveButton(Tx.s(R.string.mediainfo_022)) { _, _ -> AudioDelay.apply(a) }
            .setNeutralButton(Tx.s(R.string.musiccontr_018)) { _, _ ->
                s.audioDelayMs = 0L
                s.bluetoothDelayMs = 0L
                AudioDelay.apply(a)
            }
            .setOnCancelListener { AudioDelay.apply(a) }
            .show()
    }

    fun showEqualizer() {
        val fx = a.audioFx
        val root = a.vertical {}
        fun rowButton(text: String, onClick: () -> Unit) = TextView(a).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, a.dp(10), 0, a.dp(10))
            setOnClickListener { onClick() }
        }
        root.addView(rowButton(Tx.s(R.string.playermenu_006, a.settings.stereoMode.label)) { showStereoMode() })
        root.addView(rowButton(Tx.s(R.string.playermenu_007, AudioFx.reverbLabels[a.settings.reverbPreset])) { showReverb() })
        root.addView(rowButton(Tx.s(R.string.playermenu_008)) { showGraphicEq() })
        val boostLabel = a.label(Tx.s(R.string.musiccontr_001, a.settings.volumeBoostPercent))
        root.addView(boostLabel)
        root.addView(a.seekBar(100, 200, a.settings.volumeBoostPercent) { v ->
            fx.setBoostPercent(v)
            boostLabel.text = Tx.s(R.string.musiccontr_001, v)
        })
        if (fx.available && fx.bandCount > 0) {
            val sw = SwitchCompat(a).apply {
                text = Tx.s(R.string.musiccontr_002)
                setTextColor(Color.WHITE)
                isChecked = a.settings.eqEnabled
                setOnCheckedChangeListener { _, on -> fx.setEnabled(on) }
            }
            root.addView(sw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            val (min, max) = fx.levelRange
            for (band in 0 until fx.bandCount) {
                val freq = fx.centerFreqHz(band)
                val title = a.label(if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz", 12f)
                root.addView(title)
                root.addView(a.seekBar(min, max, fx.level(band)) { v -> fx.setLevel(band, v) })
            }
        } else {
            root.addView(a.label(Tx.s(R.string.playermenu_009), 13f, 0xFF9AA3AD.toInt()),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        }
        PanelBuilder(a)
            .setTitle(Tx.s(R.string.musiccontr_017))
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
            .setNeutralButton(Tx.s(R.string.musiccontr_018)) { _, _ ->
                fx.reset()
                fx.setBoostPercent(100)
            }
            .show()
    }

    /** Cihazdan bağımsız 10 bantlı grafik EQ (biquad; [AudioEq] işlemcisi üzerinden). */
    fun showGraphicEq() {
        val s = a.settings
        val n = AudioEq.FREQS.size
        fun fmtDb(v: Int) = (if (v > 0) "+$v" else "$v") + " dB"
        fun freqLabel(hz: Int) = if (hz >= 1000) "${"%.1f".format(hz / 1000f)} kHz" else "$hz Hz"

        val gains = ArrayList<Int>().apply {
            val saved = s.graphicEqBands
            for (i in 0 until n) add((saved.getOrNull(i) ?: 0).coerceIn(-AudioEq.MAX_DB, AudioEq.MAX_DB))
        }
        val freqs = ArrayList<Int>().apply {
            val saved = s.graphicEqFreqs
            for (i in 0 until n) add((saved.getOrNull(i) ?: AudioEq.FREQS[i]).coerceIn(AudioEq.MIN_FREQ, AudioEq.MAX_FREQ))
        }
        val qMin = (AudioEq.MIN_Q * 100).toInt()
        val qMax = (AudioEq.MAX_Q * 100).toInt()
        val qs = ArrayList<Int>().apply {
            val saved = s.graphicEqQs
            for (i in 0 until n) add((saved.getOrNull(i) ?: (AudioEq.DEFAULT_Q * 100).toInt()).coerceIn(qMin, qMax))
        }

        // Frekans ↔ kaydırıcı (0..1000) logaritmik.
        val logMin = Math.log10(AudioEq.MIN_FREQ.toDouble())
        val logMax = Math.log10(AudioEq.MAX_FREQ.toDouble())
        fun freqToBar(hz: Int) = (((Math.log10(hz.toDouble()) - logMin) / (logMax - logMin)) * 1000).toInt().coerceIn(0, 1000)
        fun barToFreq(bar: Int) = Math.pow(10.0, logMin + (bar / 1000.0) * (logMax - logMin)).toInt().coerceIn(AudioEq.MIN_FREQ, AudioEq.MAX_FREQ)

        val root = a.vertical {}
        root.addView(SwitchCompat(a).apply {
            text = Tx.s(R.string.playermenu_010)
            setTextColor(Color.WHITE)
            isChecked = s.graphicEqEnabled
            setOnCheckedChangeListener { _, on -> s.graphicEqEnabled = on; AudioEq.current?.enabled = on }
        })
        val extraRows = ArrayList<View>()
        root.addView(SwitchCompat(a).apply {
            text = Tx.s(R.string.playermenu_011)
            setTextColor(Color.WHITE)
            isChecked = s.graphicEqParametric
            setOnCheckedChangeListener { _, on ->
                s.graphicEqParametric = on
                extraRows.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
                AudioEq.update(s)
            }
        })

        for (i in 0 until n) {
            val gLab = a.label("${freqLabel(freqs[i])}:  ${fmtDb(gains[i])}", 12f)
            root.addView(gLab)
            root.addView(a.seekBar(-AudioEq.MAX_DB, AudioEq.MAX_DB, gains[i]) { v ->
                gains[i] = v
                gLab.text = "${freqLabel(freqs[i])}:  ${fmtDb(v)}"
                s.graphicEqBands = gains
                AudioEq.current?.setGain(i, v)
            })
            val fLab = a.label("   Frekans: ${freqLabel(freqs[i])}", 11f)
            val fBar = a.seekBar(0, 1000, freqToBar(freqs[i])) { bar ->
                val hz = barToFreq(bar)
                freqs[i] = hz
                fLab.text = "   Frekans: ${freqLabel(hz)}"
                gLab.text = "${freqLabel(hz)}:  ${fmtDb(gains[i])}"
                s.graphicEqFreqs = freqs
                AudioEq.current?.setBand(i, hz.toFloat(), qs[i] / 100f)
            }
            val qLab = a.label("   Q: ${"%.2f".format(qs[i] / 100f)}", 11f)
            val qBar = a.seekBar(qMin, qMax, qs[i]) { v ->
                qs[i] = v
                qLab.text = "   Q: ${"%.2f".format(v / 100f)}"
                s.graphicEqQs = qs
                AudioEq.current?.setBand(i, freqs[i].toFloat(), v / 100f)
            }
            for (v in listOf(fLab, fBar, qLab, qBar)) {
                v.visibility = if (s.graphicEqParametric) View.VISIBLE else View.GONE
                root.addView(v)
                extraRows += v
            }
        }

        PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_012))
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
            .setNeutralButton(Tx.s(R.string.musiccontr_018)) { _, _ ->
                for (i in 0 until n) {
                    gains[i] = 0
                    freqs[i] = AudioEq.FREQS[i]
                    qs[i] = (AudioEq.DEFAULT_Q * 100).toInt()
                }
                s.graphicEqBands = gains
                s.graphicEqFreqs = freqs
                s.graphicEqQs = qs
                AudioEq.current?.setGains(gains)
                AudioEq.update(s)
            }
            .show()
    }

    // ------------------------------------------------------------ altyazı

    fun showSubtitleMenu() {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        val sub = a.subtitles
        entries += ((if (sub.mode == SubtitleController.Mode.OFF) "● " else "○ ") + Tx.s(R.string.playermenu_013)) to { sub.setOff() }

        val groups = a.player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        groups.forEachIndexed { i, g ->
            val selected = sub.mode == SubtitleController.Mode.EMBEDDED && g.isSelected
            entries += ((if (selected) "● " else "○ ") + Tx.s(R.string.playermenu_014) + trackLabel(g.getTrackFormat(0), i)) to { sub.useEmbedded(g) }
        }
        for (f in a.sidecars) {
            val selected = sub.mode == SubtitleController.Mode.EXTERNAL && sub.externalName == f.name
            entries += ((if (selected) "● " else "○ ") + Tx.s(R.string.playermenu_129) + f.name) to { a.loadExternalSubtitle(f) }
        }
        if (sub.mode == SubtitleController.Mode.EXTERNAL && a.sidecars.none { it.name == sub.externalName } && sub.externalName != null) {
            entries += "● Eklenen: ${sub.externalName}" to {}
        }
        entries += "＋  Dosyadan ekle…" to { a.pickSubtitleFile() }
        entries += Tx.s(R.string.playermenu_015) to { showOnlineSearch() }
        entries += Tx.s(R.string.playermenu_016) to { showTranslate() }
        entries += Tx.s(R.string.playermenu_017) to { showSubtitleSync() }
        entries += "Aa  Stil ve boyut…" to { showSubtitleStyle() }
        entries += Tx.s(R.string.playermenu_018) to { showCharsetMenu() }

        PanelBuilder(a)
            .setTitle(Tx.s(R.string.mediainfo_014))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton(Tx.s(R.string.nowplaying_009), null)
            .show()
    }

    fun showSubtitleSync() {
        val label = a.label("", 16f)
        fun refresh() {
            val d = a.subtitles.delayMs
            label.text = Tx.s(R.string.playermenu_019, if (d >= 0) "+" else "", "%.1f".format(d / 1000f))
            if (d < 0 && a.subtitles.mode == SubtitleController.Mode.EMBEDDED) {
                label.text = label.text.toString() + Tx.s(R.string.playermenu_020)
            }
        }
        refresh()
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((t, ms) in listOf("-1 sn" to -1000L, "-0.1" to -100L, "+0.1" to 100L, "+1 sn" to 1000L)) {
                addView(Button(a).apply {
                    text = t
                    setOnClickListener {
                        a.subtitles.delayMs += ms
                        refresh()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val speedLabel = a.label("", 16f)
        fun refreshSpeed() {
            speedLabel.text = Tx.s(R.string.playermenu_021, "%.4f".format(a.subtitles.speed))
        }
        refreshSpeed()
        val presets = listOf("23.976→25" to 23.976 / 25.0, "25→23.976" to 25.0 / 23.976, "24→25" to 24.0 / 25.0, "25→24" to 25.0 / 24.0)
        val speedRow1 = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((t, f) in presets) {
                addView(Button(a).apply {
                    text = t
                    textSize = 11f
                    setOnClickListener {
                        a.subtitles.speed = f
                        refreshSpeed()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val speedRow2 = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((t, d) in listOf("-0.1%" to -0.001, "+0.1%" to 0.001)) {
                addView(Button(a).apply {
                    text = t
                    setOnClickListener {
                        a.subtitles.speed = (a.subtitles.speed + d).coerceIn(0.5, 2.0)
                        refreshSpeed()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(Button(a).apply {
                text = "1.0"
                setOnClickListener {
                    a.subtitles.speed = 1.0
                    refreshSpeed()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val root = a.vertical {
            addView(label)
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
            addView(speedLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(20) })
            addView(speedRow1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
            addView(speedRow2, LinearLayout.LayoutParams(-1, -2))
        }
        PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_022))
            .setView(root)
            .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
            .setNeutralButton(Tx.s(R.string.musiccontr_018)) { _, _ ->
                a.subtitles.delayMs = 0L
                a.subtitles.speed = 1.0
            }
            .show()
    }

    fun showSubtitleStyle() {
        val s = a.settings
        val colors = listOf(Tx.s(R.string.playermenu_130) to 0xFFFFFFFF.toInt(), Tx.s(R.string.playermenu_025) to 0xFFFFE600.toInt(), Tx.s(R.string.playermenu_024) to 0xFF7CFF7C.toInt(), Tx.s(R.string.playermenu_023) to 0xFF66E5FF.toInt())
        val backgrounds = listOf(Tx.s(R.string.playermenu_027) to 0x00000000, Tx.s(R.string.playermenu_026) to 0x99000000.toInt(), Tx.s(R.string.playermenu_131) to 0xFF000000.toInt())
        val sizeLabel = a.label(Tx.s(R.string.playermenu_028, s.subtitleSizeSp.toInt()))
        val posLabel = a.label(Tx.s(R.string.playermenu_029, (s.subtitleBottomFraction * 100).toInt()))
        val colorBtn = Button(a).apply { text = Tx.s(R.string.playermenu_030, colors.firstOrNull { it.second == s.subtitleColor }?.first ?: Tx.s(R.string.settingsac_156)) }
        val bgBtn = Button(a).apply { text = Tx.s(R.string.playermenu_031, backgrounds.firstOrNull { it.second == s.subtitleBackground }?.first ?: Tx.s(R.string.settingsac_156)) }
        colorBtn.setOnClickListener {
            a.showChoice(Tx.s(R.string.playermenu_032), colors.map { it.first }, colors.indexOfFirst { it.second == s.subtitleColor }) { i ->
                s.subtitleColor = colors[i].second
                colorBtn.text = Tx.s(R.string.playermenu_030, colors[i].first)
                a.subtitles.applyStyle()
            }
        }
        bgBtn.setOnClickListener {
            a.showChoice(Tx.s(R.string.playermenu_132), backgrounds.map { it.first }, backgrounds.indexOfFirst { it.second == s.subtitleBackground }) { i ->
                s.subtitleBackground = backgrounds[i].second
                bgBtn.text = Tx.s(R.string.playermenu_031, backgrounds[i].first)
                a.subtitles.applyStyle()
            }
        }
        val outline = SwitchCompat(a).apply {
            text = Tx.s(R.string.playermenu_033)
            setTextColor(Color.WHITE)
            isChecked = s.subtitleOutline
            setOnCheckedChangeListener { _, on ->
                s.subtitleOutline = on
                a.subtitles.applyStyle()
            }
        }
        val bold = SwitchCompat(a).apply {
            text = Tx.s(R.string.playermenu_034)
            setTextColor(Color.WHITE)
            isChecked = s.subtitleBold
            setOnCheckedChangeListener { _, on ->
                s.subtitleBold = on
                a.subtitles.applyStyle()
            }
        }
        val root = a.vertical {
            addView(sizeLabel)
            addView(a.seekBar(10, 48, s.subtitleSizeSp.toInt()) { v ->
                s.subtitleSizeSp = v.toFloat()
                sizeLabel.text = Tx.s(R.string.playermenu_028, v)
                a.subtitles.applyStyle()
            })
            addView(posLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
            addView(a.seekBar(0, 50, (s.subtitleBottomFraction * 100).toInt()) { v ->
                s.subtitleBottomFraction = v / 100f
                posLabel.text = Tx.s(R.string.playermenu_029, v)
                a.subtitles.applyStyle()
            })
            addView(colorBtn)
            addView(bgBtn)
            addView(outline)
            addView(bold)
        }
        PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_035))
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
            .show()
    }

    fun showCharsetMenu() {
        val options = listOf(
            Tx.s(R.string.playermenu_133) to "auto",
            "UTF-8" to "UTF-8",
            Tx.s(R.string.playermenu_036) to "windows-1254",
            Tx.s(R.string.playermenu_037) to "ISO-8859-9",
            Tx.s(R.string.playermenu_038) to "windows-1252",
            Tx.s(R.string.playermenu_134) to "windows-1250",
            Tx.s(R.string.playermenu_135) to "windows-1251",
            Tx.s(R.string.playermenu_136) to "windows-1253",
            Tx.s(R.string.playermenu_039) to "windows-1255",
            Tx.s(R.string.playermenu_040) to "windows-1256",
            Tx.s(R.string.playermenu_041) to "GBK",
            Tx.s(R.string.playermenu_042) to "Big5",
            Tx.s(R.string.playermenu_137) to "Shift_JIS",
            Tx.s(R.string.playermenu_138) to "EUC-KR",
            "UTF-16" to "UTF-16",
        )
        a.showChoice(Tx.s(R.string.playermenu_043), options.map { it.first }, options.indexOfFirst { it.second == a.settings.subtitleCharset }) { i ->
            a.settings.subtitleCharset = options[i].second
            val name = a.subtitles.externalName
            val file = a.sidecars.firstOrNull { it.name == name }
                ?: name?.let { File(SubtitleFiles.downloadDir(a), it) }?.takeIf { it.exists() }
            if (file != null) a.loadExternalSubtitle(file, remember = false)
        }
    }

    // ------------------------------------------------------------ altyazı çevirisi

    fun showTranslate() {
        val cues = a.subtitles.externalCues
        if (cues.isEmpty()) {
            a.showMessage(
                Tx.s(R.string.playermenu_044),
                Tx.s(R.string.playermenu_045) +
                    Tx.s(R.string.playermenu_046)
            )
            return
        }
        val s = a.settings
        val cfg = s.translateConfig()
        if (cfg.provider != TrProvider.MYMEMORY && cfg.apiUrl.isBlank() ||
            cfg.provider == TrProvider.OPENAI && cfg.model.isBlank()
        ) {
            PanelBuilder(a)
                .setTitle(Tx.s(R.string.playermenu_047))
                .setMessage(Tx.s(R.string.playermenu_048, cfg.provider.label, if (cfg.provider == TrProvider.OPENAI) " ve model" else "") +
                    Tx.s(R.string.playermenu_049))
                .setPositiveButton(Tx.s(R.string.mainactivi_032)) { _, _ -> a.openSettings("TRANSLATE") }
                .setNegativeButton(Tx.s(R.string.fileops_004), null)
                .show()
            return
        }
        val langs = TrLangs.all
        val labels = langs.map { it.name }
        a.showChoice(Tx.s(R.string.playermenu_050), labels, langs.indexOfFirst { it.code == cfg.target }) { i ->
            s.trTarget = langs[i].code
            confirmQuota(cues, s.translateConfig())
        }
    }

    private fun confirmQuota(cues: List<SubCue>, cfg: TrConfig) {
        val chars = cues.sumOf { it.text.length.toLong() }
        if (cfg.provider == TrProvider.MYMEMORY && cfg.email.isBlank() && chars > 5000) {
            PanelBuilder(a)
                .setTitle(Tx.s(R.string.playermenu_051))
                .setMessage(Tx.s(R.string.playermenu_052, chars) +
                    Tx.s(R.string.playermenu_053))
                .setPositiveButton(Tx.s(R.string.playermenu_054)) { _, _ -> runTranslate(cues, cfg) }
                .setNeutralButton(Tx.s(R.string.mainactivi_032)) { _, _ -> a.openSettings("TRANSLATE") }
                .setNegativeButton(Tx.s(R.string.fileops_004), null)
                .show()
            return
        }
        runTranslate(cues, cfg)
    }

    /** Yarıda kalan çeviriyi sürdürmek için önbellek dosyası (sağlayıcı + model + dil çiftine özel). */
    private fun cacheFile(cfg: TrConfig): File {
        val dir = File(a.filesDir, "trcache").apply { mkdirs() }
        val id = "${cfg.provider.id}|${cfg.model}|${cfg.source}|${cfg.target}".hashCode().toUInt().toString(16)
        return File(dir, "tr_$id.json")
    }

    private fun loadCache(f: File): HashMap<String, String> {
        val m = HashMap<String, String>()
        runCatching {
            if (f.exists() && f.length() < 8L * 1024 * 1024) {
                val o = org.json.JSONObject(f.readText(Charsets.UTF_8))
                for (k in o.keys()) m[k] = o.getString(k)
            }
        }
        return m
    }

    private fun saveCache(f: File, m: Map<String, String>) {
        runCatching {
            val snapshot = HashMap(m)
            if (snapshot.size > 40_000) { f.delete(); return }
            f.writeText(org.json.JSONObject(snapshot as Map<*, *>).toString(), Charsets.UTF_8)
        }
    }

    private fun runTranslate(cues: List<SubCue>, cfg: TrConfig) {
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_055))
            .setMessage(Tx.s(R.string.playermenu_056))
            .setCancelable(false)
            .setNegativeButton(Tx.s(R.string.fileops_004)) { _, _ -> cancel.set(true) }
            .show()
        val uri = a.currentUri
        Thread {
            val tr = SubtitleTranslator(cfg)
            val cFile = cacheFile(cfg)
            val cache = loadCache(cFile)
            var lastDone = 0
            var lastTotal = 0
            val header = Tx.s(R.string.playermenu_057, cfg.provider.id, cfg.model, cfg.source, cfg.target) +
                Tx.s(R.string.playermenu_058, runCatching { java.net.URI(cfg.apiUrl).host }.getOrNull() ?: cfg.apiUrl.take(40), cues.size)
            try {
                val out = tr.translateCues(
                    cues,
                    cancelled = { cancel.get() },
                    progress = { done, total ->
                        lastDone = done; lastTotal = total
                        a.runOnUiThread { if (!cancel.get()) dialog.setMessage(Tx.s(R.string.playermenu_059, done, total)) }
                    },
                    cache = cache,
                    checkpoint = { saveCache(cFile, cache) },
                )
                val base = uri?.let { SubtitleFiles.displayName(a, it).substringBeforeLast('.') } ?: "altyazi"
                val file = File(SubtitleFiles.downloadDir(a), UriGuard.safeFileName(base, 70) + "." + cfg.target + ".srt")
                file.writeText(SrtWriter.write(out), Charsets.UTF_8)
                SubtitleFiles.pruneDownloads(a)
                BugReport.noteTranslate(a, Tx.s(R.string.playermenu_060, lastDone, lastTotal, header) + tr.log.takeLast(40).joinToString("\n"))
                a.runOnUiThread {
                    dialog.dismiss()
                    a.loadExternalSubtitle(file)
                    a.toast(Tx.s(R.string.playermenu_061, out.size))
                }
            } catch (e: TranslateCancelled) {
                saveCache(cFile, cache)
                BugReport.noteTranslate(a, Tx.s(R.string.playermenu_062, lastDone, lastTotal, header) + tr.log.takeLast(40).joinToString("\n"))
                a.runOnUiThread { dialog.dismiss(); a.toast(Tx.s(R.string.playermenu_063)) }
            } catch (e: Exception) {
                saveCache(cFile, cache)
                BugReport.noteTranslate(
                    a,
                    Tx.s(R.string.playermenu_064, lastDone, lastTotal, e.javaClass.simpleName, e.message, header) + tr.log.takeLast(60).joinToString("\n")
                )
                a.runOnUiThread {
                    dialog.dismiss()
                    val saved = if (lastTotal > 0) Tx.s(R.string.playermenu_065, lastDone, lastTotal) else ""
                    PanelBuilder(a)
                        .setTitle(Tx.s(R.string.playermenu_066))
                        .setMessage((e.message ?: Tx.s(R.string.playermenu_067)) + saved)
                        .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
                        .setNeutralButton(Tx.s(R.string.playermenu_068)) { _, _ ->
                            BugReport.copyToClipboard(a) { n -> a.toast(Tx.s(R.string.playermenu_069, n)) }
                        }
                        .show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ çevrimiçi altyazı

    fun showOnlineSearch() {
        if (a.settings.osApiKey.isEmpty()) {
            PanelBuilder(a)
                .setTitle(Tx.s(R.string.playermenu_070))
                .setMessage(Tx.s(R.string.playermenu_071))
                .setPositiveButton(Tx.s(R.string.mainactivi_032)) { _, _ -> a.openSettings() }
                .setNegativeButton(Tx.s(R.string.fileops_004), null)
                .show()
            return
        }
        val uri = a.currentUri ?: return
        val title = OsHash.cleanTitle(SubtitleFiles.displayName(a, uri))
        a.showInput(Tx.s(R.string.mainactivi_068), Tx.s(R.string.playermenu_072), title) { q -> runSearch(q) }
    }

    private fun busyDialog(message: String): AlertDialog =
        PanelBuilder(a).setMessage(message).setCancelable(false).show()

    private fun runSearch(query: String) {
        val uri = a.currentUri ?: return
        val busy = busyDialog(Tx.s(R.string.musicactiv_066))
        val s = a.settings
        Thread {
            try {
                val client = OpenSubtitlesClient(s.osApiKey, s.osUser, s.osPass)
                val hash = OsHash.compute(a, uri)
                val results = client.search(query, s.subtitleLanguages, hash)
                a.runOnUiThread {
                    busy.dismiss()
                    showResults(results, client, query)
                }
            } catch (e: Exception) {
                a.runOnUiThread {
                    busy.dismiss()
                    a.showMessage(Tx.s(R.string.playermenu_073), e.message ?: Tx.s(R.string.playermenu_067))
                }
            }
        }.start()
    }

    private fun showResults(results: List<OsResult>, client: OpenSubtitlesClient, query: String) {
        if (results.isEmpty()) {
            a.showMessage(Tx.s(R.string.playermenu_075), Tx.s(R.string.playermenu_074, query))
            return
        }
        val shown = results.take(50)
        val labels = shown.map { (if (it.hashMatch) "★ " else "") + "[${it.language}] ${it.release} (↓${it.downloads})" }
        a.showChoice(Tx.s(R.string.playermenu_076), labels, -1) { i -> download(shown[i], client) }
    }

    private fun download(r: OsResult, client: OpenSubtitlesClient) {
        val busy = busyDialog(Tx.s(R.string.playermenu_077))
        Thread {
            try {
                val bytes = client.download(r.fileId)
                val base = OsHash.cleanTitle(SubtitleFiles.displayName(a, a.currentUri ?: return@Thread))
                    .replace(Regex("""[^\p{L}\p{N}]+"""), "_").take(40)
                val lang = r.language.replace(Regex("[^A-Za-z-]"), "").take(8)
                val file = File(SubtitleFiles.downloadDir(a), "${UriGuard.safeFileName(base)}_${lang}_${r.fileId}.srt")
                file.writeBytes(bytes)
                SubtitleFiles.pruneDownloads(a)
                a.runOnUiThread {
                    busy.dismiss()
                    a.loadExternalSubtitle(file)
                }
            } catch (e: Exception) {
                a.runOnUiThread {
                    busy.dismiss()
                    a.showMessage(Tx.s(R.string.playermenu_078), e.message ?: Tx.s(R.string.playermenu_067))
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ oynatma

    fun showSpeed() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
        val cur = a.player.playbackParameters.speed
        val sel = speeds.indexOfFirst { abs(it - cur) < 0.01f }
        val labels = speeds.map { "${it}x" } + Tx.s(R.string.playermenu_079)
        a.showChoice(Tx.s(R.string.gesturecon_003), labels, sel) { i ->
            if (i < speeds.size) a.setSpeed(speeds[i]) else showCustomSpeed()
        }
    }

    /** 0.25x – 4.00x arası, 0.05 adımlı serbest hız. */
    fun showCustomSpeed() {
        val cur = (a.player.playbackParameters.speed * 100).toInt().coerceIn(25, 400)
        var value = cur - cur % 5
        val root = a.vertical {}
        val label = a.label("${"%.2f".format(value / 100f)}x", 22f)
        label.gravity = Gravity.CENTER
        root.addView(label, LinearLayout.LayoutParams(-1, -2))
        root.addView(a.seekBar(5, 80, value / 5) { v ->
            value = v * 5
            label.text = "${"%.2f".format(value / 100f)}x"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_080))
            .setView(root)
            .setPositiveButton(Tx.s(R.string.mainactivi_064)) { _, _ -> a.setSpeed(value / 100f) }
            .setNeutralButton("1.0x") { _, _ -> a.setSpeed(1f) }
            .setNegativeButton(Tx.s(R.string.playermenu_081), null)
            .show()
    }

    fun showSleepTimer() {
        val options = listOf(Tx.s(R.string.audiofx_004), "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika", "120 dakika", Tx.s(R.string.playermenu_139), Tx.s(R.string.playermenu_082))
        a.showChoice(Tx.s(R.string.nowplaying_004), options, -1) { i ->
            when (i) {
                0 -> a.setSleepTimer(0, false)
                7 -> a.setSleepTimer(0, true)
                8 -> a.showInput(Tx.s(R.string.playermenu_083), Tx.s(R.string.playermenu_140)) { txt ->
                    val m = txt.trim().toIntOrNull()
                    if (m == null || m !in 1..600) a.toast(Tx.s(R.string.playermenu_084)) else a.setSleepTimer(m, false)
                }
                else -> a.setSleepTimer(listOf(0, 15, 30, 45, 60, 90, 120)[i], false)
            }
        }
    }

    fun showStereoMode() {
        val modes = StereoMode.entries
        a.showChoice(Tx.s(R.string.playermenu_085), modes.map { it.label }, modes.indexOf(a.settings.stereoMode)) { i ->
            a.settings.stereoMode = modes[i]
            AudioBalance.update(a.settings)
        }
    }

    fun showReverb() {
        a.showChoice(Tx.s(R.string.playermenu_086), AudioFx.reverbLabels, a.settings.reverbPreset) { i ->
            a.audioFx.setReverb(i)
            a.applyAuxEffect()
            if (i > 0 && a.audioFx.reverbId == 0) a.toast(Tx.s(R.string.playermenu_087))
        }
    }

    fun showDecoderMenu() {
        val modes = DecoderMode.entries
        val labels = listOf(
            Tx.s(R.string.playermenu_088),
            Tx.s(R.string.playermenu_089),
            Tx.s(R.string.playermenu_090),
        )
        a.showChoice(Tx.s(R.string.playermenu_091), labels, modes.indexOf(a.settings.decoder)) { i ->
            if (modes[i] != a.settings.decoder) a.changeDecoder(modes[i])
        }
    }

    fun showAspectMenu() {
        val modes = AspectMode.entries
        a.showChoice(Tx.s(R.string.playermenu_092), modes.map { it.label }, a.aspect.ordinal) { a.setAspect(modes[it]) }
    }

    fun showCustomizeShortcuts() {
        val all = Shortcut.entries
        val enabled = a.settings.shortcuts
        a.showMultiChoice(Tx.s(R.string.playermenu_093), all.map { it.label }, BooleanArray(all.size) { all[it] in enabled }) { state ->
            a.settings.shortcuts = all.filterIndexed { i, _ -> state[i] }
            a.refreshShortcuts()
        }
    }

    fun showStreamDialog() {
        a.showInput(Tx.s(R.string.mainactivi_034), "http://, https:// veya rtsp:// adresi") { url -> a.openStream(url) }
    }

    fun showPlaylist() {
        val p = a.player
        val titles = List(p.mediaItemCount) { i ->
            p.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Video ${i + 1}"
        }
        if (titles.isEmpty()) return
        a.showChoice(Tx.s(R.string.playermenu_141), titles, p.currentMediaItemIndex) { i ->
            p.seekTo(i, 0L)
            p.play()
        }
    }

    fun showBookmarks() {
        val uri = a.currentUri ?: return
        val marks = ResumeStore.bookmarks(a, uri)
        val labels = listOf("＋ Bu konuma yer imi ekle (${timeLabel(a.player.currentPosition)})") + marks.map { timeLabel(it) }
        val dialog = PanelBuilder(a)
            .setTitle(Tx.s(R.string.playermenu_094))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    ResumeStore.setBookmarks(a, uri, marks + a.player.currentPosition)
                    a.toast(Tx.s(R.string.playermenu_095))
                } else {
                    a.player.seekTo(marks[which - 1])
                }
            }
            .setNegativeButton(Tx.s(R.string.nowplaying_009), null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                if (position > 0) {
                    ResumeStore.setBookmarks(a, uri, marks.filterIndexed { i, _ -> i != position - 1 })
                    a.toast(Tx.s(R.string.playermenu_096))
                    dialog.dismiss()
                }
                true
            }
        }
        dialog.show()
    }

    fun showInfo() {
        val uri = a.currentUri ?: return
        val p = a.player
        val v = p.videoFormat
        val au = p.audioFormat
        val size = SubtitleFiles.sizeOf(a, uri)
        val text = buildString {
            appendLine(Tx.s(R.string.playermenu_ad, SubtitleFiles.displayName(a, uri)))
            SubtitleFiles.pathOf(a, uri)?.let { appendLine(Tx.s(R.string.playermenu_142, it)) }
            if (size > 0) appendLine(Tx.s(R.string.playermenu_boyut_mb, "%.1f".format(size / 1048576.0)))
            if (p.duration > 0) appendLine(Tx.s(R.string.playermenu_097, timeLabel(p.duration)))
            if (v != null) {
                appendLine(Tx.s(R.string.playermenu_098, v.width, v.height) +
                        (if (v.frameRate > 0) " @ ${"%.2f".format(v.frameRate)} fps" else "") +
                        (if (v.codecs != null) ", ${v.codecs}" else ", ${mimeLabel(v.sampleMimeType)}"))
                if (v.bitrate > 0) appendLine(Tx.s(R.string.playermenu_099, v.bitrate / 1000))
            }
            if (au != null) {
                appendLine(Tx.s(R.string.playermenu_143, mimeLabel(au.sampleMimeType), au.channelCount, au.sampleRate))
            }
            appendLine(Tx.s(R.string.playermenu_100, a.settings.decoder.label))
        }
        a.showMessage(Tx.s(R.string.playermenu_101), text.trim())
    }

    // ------------------------------------------------------------ üç nokta paneli

    fun showTrim() {
        val uri = a.currentUri
        if (uri == null || uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "rtsp") {
            a.toast(Tx.s(R.string.playermenu_102))
            return
        }
        val title = a.player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: SubtitleFiles.displayName(a, uri)
        a.showTrimDialog(uri, title, a.player.duration.coerceAtLeast(0L), { a.player.currentPosition })
    }

    fun showChapters() {
        val list = a.chapters
        if (list.isEmpty()) {
            a.toast(Tx.s(R.string.playermenu_103))
            return
        }
        val pos = a.player.currentPosition
        val current = list.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
        val labels = list.map { "${formatDuration(it.startMs)}  ${it.title}" }
        a.showChoice(Tx.s(R.string.playermenu_104), labels, current) { a.player.seekTo(list[it].startMs) }
    }

    /** 360° lens türü, 3B biçimi ve bakış ayarları. */
    fun showVrMenu() {
        val cur = a.vr
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, act: () -> Unit) { labels += label; acts += act }
        val stereoNames = listOf(Tx.s(R.string.playermenu_106), Tx.s(R.string.playermenu_105), Tx.s(R.string.playermenu_144))
        if (cur == VrMode.NONE) {
            add("360° olarak oynat") { pickVrLens(VrLens.SPHERE360, 0) }
            add("180° olarak oynat") { pickVrLens(VrLens.SPHERE180, 0) }
            add(Tx.s(R.string.playermenu_107)) { pickVrLens(VrLens.FISHEYE, 0) }
            add(Tx.s(R.string.playermenu_145)) { pickVrLens(VrLens.NORMAL, 0) }
        } else {
            add(Tx.s(R.string.playermenu_108, cur.lens.label)) { pickVrLens(null, cur.stereo) }
            add(Tx.s(R.string.playermenu_109, stereoNames[cur.stereo])) { pickVrStereo(cur) }
            add(if (a.vrSensorEnabled()) Tx.s(R.string.playermenu_111) else Tx.s(R.string.playermenu_110)) { a.vrSetSensor(!a.vrSensorEnabled()) }
            add(if (a.vrCardboardEnabled()) Tx.s(R.string.playermenu_113) else Tx.s(R.string.playermenu_112)) { a.vrSetCardboard(!a.vrCardboardEnabled()) }
            add(if (a.vrAutoRotating()) Tx.s(R.string.playermenu_115) else Tx.s(R.string.playermenu_114)) { a.vrSetAutoRotate(!a.vrAutoRotating()) }
            add(Tx.s(R.string.playermenu_116)) { a.vrLevelHorizon() }
            add(Tx.s(R.string.playermenu_117)) { a.vrReset() }
            add(Tx.s(R.string.playermenu_118)) { a.openSettings("VR360") }
            add(Tx.s(R.string.playermenu_119)) { a.setVr(VrMode.NONE, manual = true) }
        }
        AlertDialog.Builder(a)
            .setTitle("360° oynatma")
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    /** Lens türünü seçtirir ([fixed] verilirse sormadan o lensi uygular). */
    private fun pickVrLens(fixed: VrLens?, stereo: Int) {
        if (fixed != null) {
            a.setVr(VrMode.of(fixed, stereo), manual = true)
            return
        }
        val lenses = VrLens.entries
        val labels = lenses.map { (if (a.vr.lens == it) "✓ " else "") + it.label }
        AlertDialog.Builder(a)
            .setTitle(Tx.s(R.string.playermenu_120))
            .setItems(labels.toTypedArray()) { _, i -> a.setVr(VrMode.of(lenses[i], stereo), manual = true) }
            .show()
    }

    private fun pickVrStereo(cur: VrMode) {
        val names = listOf(Tx.s(R.string.playermenu_106), Tx.s(R.string.playermenu_105), Tx.s(R.string.playermenu_144))
        val labels = names.mapIndexed { i, n -> (if (cur.stereo == i) "✓ " else "") + n }
        AlertDialog.Builder(a)
            .setTitle(Tx.s(R.string.playermenu_121))
            .setItems(labels.toTypedArray()) { _, i -> a.setVr(VrMode.of(cur.lens, i), manual = true) }
            .show()
    }

    fun showOverflow() {
        val dialog = android.app.Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        a.keepControls()
        fun item(icon: Int, label: String, action: () -> Unit): View =
            a.actionButton(icon, null, label, false, scale = 0.95f, bgOpacity = 0f, ring = true) {
                dialog.dismiss()
                action()
            }

        // MX gibi: videonun üstünde, sağa yaslı, arka plansız, ince çerçeveli düğmeler.
        val grid = GridLayout(a).apply {
            columnCount = 4
            val items = listOf(
                item(R.drawable.ic_list, Tx.s(R.string.musicactiv_013)) { showPlaylist() },
                item(R.drawable.ic_aspect, Tx.s(R.string.playermenu_092)) { showAspectMenu() },
                item(R.drawable.ic_subtitles, Tx.s(R.string.playermenu_122)) { showSubtitleStyle() },
                item(R.drawable.ic_bookmark, Tx.s(R.string.playermenu_123)) { showBookmarks() },
                item(R.drawable.ic_info, Tx.s(R.string.playermenu_101)) { showInfo() },
                item(R.drawable.ic_list, Tx.s(R.string.playermenu_104)) { showChapters() },
                item(R.drawable.ic_cut, Tx.s(R.string.mainactivi_047)) { showTrim() },
                item(R.drawable.ic_share, Tx.s(R.string.mainactivi_045)) { a.shareCurrent() },
                item(R.drawable.ic_public, Tx.s(R.string.mainactivi_034)) { showStreamDialog() },
                item(R.drawable.ic_lock, Tx.s(R.string.playermenu_124)) { a.startKidsLock() },
                item(R.drawable.ic_tune, Tx.s(R.string.musiccontr_017)) { showEqualizer() },
                item(R.drawable.ic_headphones, Tx.s(R.string.playermenu_003)) { showAudioDelay() },
                item(R.drawable.ic_music_note, Tx.s(R.string.playermenu_125)) { a.pickExternalAudio() },
                item(R.drawable.ic_timer, Tx.s(R.string.nowplaying_004)) { showSleepTimer() },
                item(R.drawable.ic_timer, Tx.s(R.string.playermenu_022)) { showSubtitleSync() },
                item(R.drawable.ic_settings, Tx.s(R.string.mainactivi_032)) { a.openSettings() },
            )
            items.forEach { addView(it) }
        }
        val shortcutsSwitch = SwitchCompat(a).apply {
            text = Tx.s(R.string.playermenu_126)
            setTextColor(Color.WHITE)
            setShadowLayer(a.dp(4).toFloat(), 0f, 0f, Color.BLACK)
            isChecked = a.settings.showShortcuts
            setOnCheckedChangeListener { _, on ->
                a.settings.showShortcuts = on
                a.refreshShortcuts()
            }
        }
        val remainingSwitch = SwitchCompat(a).apply {
            text = Tx.s(R.string.playermenu_127)
            setTextColor(Color.WHITE)
            setShadowLayer(a.dp(4).toFloat(), 0f, 0f, Color.BLACK)
            isChecked = a.settings.showRemainingTime
            setOnCheckedChangeListener { _, on -> a.settings.showRemainingTime = on }
        }
        val vrRow = TextView(a).apply {
            text = if (a.vr == VrMode.NONE) "360 olarak oynat" else Tx.s(R.string.playermenu_128)
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setShadowLayer(a.dp(4).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(0, a.dp(10), 0, a.dp(6))
            setOnClickListener {
                dialog.dismiss()
                showVrMenu()
            }
        }
        val root = a.vertical(12) {
            addView(grid)
            addView(shortcutsSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            addView(remainingSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
            addView(vrRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        }
        dialog.setContentView(ScrollView(a).apply { addView(root) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            setLayout(android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.35f)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    @Suppress("unused")
    private fun spacer(): View = TextView(a).apply { gravity = Gravity.CENTER }
}
