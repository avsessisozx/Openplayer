package org.openplayer.app

import android.graphics.Color
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
        val name = f.label ?: lang ?: "Parça ${index + 1}"
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
            a.toast("Ses parçası bulunamadı")
            return
        }
        val labels = groups.mapIndexed { i, g ->
            trackLabel(g.getTrackFormat(0), i) + if (!g.isSupported) " (desteklenmiyor)" else ""
        } + "Ses artırma ve ekolayzır…" + "Ses gecikmesi (eşitleme)…"
        val selected = groups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        a.showChoice("Ses parçası", labels, selected) { which ->
            if (which == groups.size + 1) {
                showAudioDelay()
            } else if (which >= groups.size) {
                showEqualizer()
            } else {
                val p = a.player
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[which].mediaTrackGroup, 0))
                    .build()
            }
        }
    }

    /** Ses gecikmesi: + sesi geciktirir, − öne alır. Bluetooth için ek gecikme ayrı tutulur. */
    fun showAudioDelay() {
        val s = a.settings
        fun fmt(ms: Long) = "${if (ms >= 0) "+" else ""}$ms ms"
        val delayLabel = a.label("", 16f)
        val btLabel = a.label("", 16f)
        fun refresh() {
            delayLabel.text = "Ses gecikmesi: ${fmt(s.audioDelayMs)}\n(+ ses geç gelir, − ses erken gelir)"
            btLabel.text = "Bluetooth ek gecikme: ${fmt(s.bluetoothDelayMs)}\n(yalnızca Bluetooth kulaklık/hoparlör bağlıyken)"
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
        AlertDialog.Builder(a)
            .setTitle("Ses gecikmesi")
            .setView(root)
            .setPositiveButton("Tamam") { _, _ -> AudioDelay.apply(a) }
            .setNeutralButton("Sıfırla") { _, _ ->
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
        val boostLabel = a.label("Ses artırma: %${a.settings.volumeBoostPercent}")
        root.addView(boostLabel)
        root.addView(a.seekBar(100, 200, a.settings.volumeBoostPercent) { v ->
            fx.setBoostPercent(v)
            boostLabel.text = "Ses artırma: %$v"
        })
        if (fx.available && fx.bandCount > 0) {
            val sw = SwitchCompat(a).apply {
                text = "Ekolayzır"
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
            root.addView(a.label("Bu cihazda ekolayzır kullanılamıyor.", 13f, 0xFF9AA3AD.toInt()),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        }
        AlertDialog.Builder(a)
            .setTitle("Ses ayarları")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Sıfırla") { _, _ ->
                fx.reset()
                fx.setBoostPercent(100)
            }
            .show()
    }

    // ------------------------------------------------------------ altyazı

    fun showSubtitleMenu() {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        val sub = a.subtitles
        entries += ((if (sub.mode == SubtitleController.Mode.OFF) "● " else "○ ") + "Altyazıyı kapat") to { sub.setOff() }

        val groups = a.player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        groups.forEachIndexed { i, g ->
            val selected = sub.mode == SubtitleController.Mode.EMBEDDED && g.isSelected
            entries += ((if (selected) "● " else "○ ") + "Gömülü: " + trackLabel(g.getTrackFormat(0), i)) to { sub.useEmbedded(g) }
        }
        for (f in a.sidecars) {
            val selected = sub.mode == SubtitleController.Mode.EXTERNAL && sub.externalName == f.name
            entries += ((if (selected) "● " else "○ ") + "Dosya: " + f.name) to { a.loadExternalSubtitle(f) }
        }
        if (sub.mode == SubtitleController.Mode.EXTERNAL && a.sidecars.none { it.name == sub.externalName } && sub.externalName != null) {
            entries += "● Eklenen: ${sub.externalName}" to {}
        }
        entries += "Dosyadan ekle…" to { a.pickSubtitleFile() }
        entries += "Çevrimiçi altyazı ara…" to { showOnlineSearch() }
        entries += "Senkronizasyon (gecikme)…" to { showSubtitleSync() }
        entries += "Stil ve boyut…" to { showSubtitleStyle() }
        entries += "Karakter kodlaması…" to { showCharsetMenu() }

        AlertDialog.Builder(a)
            .setTitle("Altyazı")
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    fun showSubtitleSync() {
        val label = a.label("", 16f)
        fun refresh() {
            val d = a.subtitles.delayMs
            label.text = "Gecikme: ${if (d >= 0) "+" else ""}${"%.1f".format(d / 1000f)} sn\n(+ altyazı daha geç, − daha erken görünür)"
            if (d < 0 && a.subtitles.mode == SubtitleController.Mode.EMBEDDED) {
                label.text = label.text.toString() + "\nNot: Gömülü altyazılarda sadece + yönü çalışır."
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
            speedLabel.text = "Altyazı hızı: ×${"%.4f".format(a.subtitles.speed)}\n(kare hızı farkı için; 1.0000 = değişmez)"
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
        AlertDialog.Builder(a)
            .setTitle("Altyazı senkronu")
            .setView(root)
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Sıfırla") { _, _ ->
                a.subtitles.delayMs = 0L
                a.subtitles.speed = 1.0
            }
            .show()
    }

    fun showSubtitleStyle() {
        val s = a.settings
        val colors = listOf("Beyaz" to 0xFFFFFFFF.toInt(), "Sarı" to 0xFFFFE600.toInt(), "Yeşil" to 0xFF7CFF7C.toInt(), "Camgöbeği" to 0xFF66E5FF.toInt())
        val backgrounds = listOf("Şeffaf" to 0x00000000, "Yarı saydam" to 0x99000000.toInt(), "Siyah" to 0xFF000000.toInt())
        val sizeLabel = a.label("Boyut: ${s.subtitleSizeSp.toInt()}")
        val posLabel = a.label("Alttan boşluk: %${(s.subtitleBottomFraction * 100).toInt()}")
        val colorBtn = Button(a).apply { text = "Renk: ${colors.firstOrNull { it.second == s.subtitleColor }?.first ?: "Özel"}" }
        val bgBtn = Button(a).apply { text = "Arka plan: ${backgrounds.firstOrNull { it.second == s.subtitleBackground }?.first ?: "Özel"}" }
        colorBtn.setOnClickListener {
            a.showChoice("Yazı rengi", colors.map { it.first }, colors.indexOfFirst { it.second == s.subtitleColor }) { i ->
                s.subtitleColor = colors[i].second
                colorBtn.text = "Renk: ${colors[i].first}"
                a.subtitles.applyStyle()
            }
        }
        bgBtn.setOnClickListener {
            a.showChoice("Arka plan", backgrounds.map { it.first }, backgrounds.indexOfFirst { it.second == s.subtitleBackground }) { i ->
                s.subtitleBackground = backgrounds[i].second
                bgBtn.text = "Arka plan: ${backgrounds[i].first}"
                a.subtitles.applyStyle()
            }
        }
        val outline = SwitchCompat(a).apply {
            text = "Kenar çizgisi"
            setTextColor(Color.WHITE)
            isChecked = s.subtitleOutline
            setOnCheckedChangeListener { _, on ->
                s.subtitleOutline = on
                a.subtitles.applyStyle()
            }
        }
        val bold = SwitchCompat(a).apply {
            text = "Kalın"
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
                sizeLabel.text = "Boyut: $v"
                a.subtitles.applyStyle()
            })
            addView(posLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
            addView(a.seekBar(0, 50, (s.subtitleBottomFraction * 100).toInt()) { v ->
                s.subtitleBottomFraction = v / 100f
                posLabel.text = "Alttan boşluk: %$v"
                a.subtitles.applyStyle()
            })
            addView(colorBtn)
            addView(bgBtn)
            addView(outline)
            addView(bold)
        }
        AlertDialog.Builder(a)
            .setTitle("Altyazı stili")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Tamam", null)
            .show()
    }

    fun showCharsetMenu() {
        val options = listOf(
            "Otomatik" to "auto",
            "UTF-8" to "UTF-8",
            "Türkçe (windows-1254)" to "windows-1254",
            "Türkçe (ISO-8859-9)" to "ISO-8859-9",
            "Batı Avrupa (windows-1252)" to "windows-1252",
            "Orta Avrupa (windows-1250)" to "windows-1250",
            "Kiril (windows-1251)" to "windows-1251",
            "Yunanca (windows-1253)" to "windows-1253",
            "İbranice (windows-1255)" to "windows-1255",
            "Arapça (windows-1256)" to "windows-1256",
            "Çince (GBK)" to "GBK",
            "Çince (Big5)" to "Big5",
            "Japonca (Shift_JIS)" to "Shift_JIS",
            "Korece (EUC-KR)" to "EUC-KR",
            "UTF-16" to "UTF-16",
        )
        a.showChoice("Karakter kodlaması", options.map { it.first }, options.indexOfFirst { it.second == a.settings.subtitleCharset }) { i ->
            a.settings.subtitleCharset = options[i].second
            val name = a.subtitles.externalName
            val file = a.sidecars.firstOrNull { it.name == name }
                ?: name?.let { File(SubtitleFiles.downloadDir(a), it) }?.takeIf { it.exists() }
            if (file != null) a.loadExternalSubtitle(file, remember = false)
        }
    }

    // ------------------------------------------------------------ çevrimiçi altyazı

    fun showOnlineSearch() {
        if (a.settings.osApiKey.isEmpty()) {
            AlertDialog.Builder(a)
                .setTitle("API anahtarı gerekli")
                .setMessage("Çevrimiçi altyazı araması için opensubtitles.com hesabınızdan aldığınız API anahtarını Ayarlar > Altyazı bölümüne girin.")
                .setPositiveButton("Ayarlar") { _, _ -> a.openSettings() }
                .setNegativeButton("İptal", null)
                .show()
            return
        }
        val uri = a.currentUri ?: return
        val title = OsHash.cleanTitle(SubtitleFiles.displayName(a, uri))
        a.showInput("Altyazı ara", "Film veya dizi adı", title) { q -> runSearch(q) }
    }

    private fun busyDialog(message: String): AlertDialog =
        AlertDialog.Builder(a).setMessage(message).setCancelable(false).show()

    private fun runSearch(query: String) {
        val uri = a.currentUri ?: return
        val busy = busyDialog("Aranıyor…")
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
                    a.showMessage("Arama başarısız", e.message ?: "Bilinmeyen hata")
                }
            }
        }.start()
    }

    private fun showResults(results: List<OsResult>, client: OpenSubtitlesClient, query: String) {
        if (results.isEmpty()) {
            a.showMessage("Sonuç yok", "\"$query\" için altyazı bulunamadı. Adı sadeleştirip tekrar deneyin.")
            return
        }
        val shown = results.take(50)
        val labels = shown.map { (if (it.hashMatch) "★ " else "") + "[${it.language}] ${it.release} (↓${it.downloads})" }
        a.showChoice("Altyazı seçin", labels, -1) { i -> download(shown[i], client) }
    }

    private fun download(r: OsResult, client: OpenSubtitlesClient) {
        val busy = busyDialog("İndiriliyor…")
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
                    a.showMessage("İndirme başarısız", e.message ?: "Bilinmeyen hata")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ oynatma

    fun showSpeed() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
        val cur = a.player.playbackParameters.speed
        val sel = speeds.indexOfFirst { abs(it - cur) < 0.01f }
        a.showChoice("Oynatma hızı", speeds.map { "${it}x" }, sel) { a.setSpeed(speeds[it]) }
    }

    fun showSleepTimer() {
        val options = listOf("Kapalı", "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika", "120 dakika", "Video bitince")
        a.showChoice("Uyku zamanlayıcısı", options, -1) { i ->
            when (i) {
                0 -> a.setSleepTimer(0, false)
                7 -> a.setSleepTimer(0, true)
                else -> a.setSleepTimer(listOf(0, 15, 30, 45, 60, 90, 120)[i], false)
            }
        }
    }

    fun showDecoderMenu() {
        val modes = DecoderMode.entries
        val labels = listOf(
            "Donanım (HW): sistemin MediaCodec çözücüsü",
            "Donanım+ (HW+): HW, desteklenmeyen biçimde FFmpeg",
            "Yazılım (SW): önce FFmpeg ile işlemci çözmesi",
        )
        a.showChoice("Çözücü", labels, modes.indexOf(a.settings.decoder)) { i ->
            if (modes[i] != a.settings.decoder) a.changeDecoder(modes[i])
        }
    }

    fun showAspectMenu() {
        val modes = AspectMode.entries
        a.showChoice("En boy oranı", modes.map { it.label }, a.aspect.ordinal) { a.setAspect(modes[it]) }
    }

    fun showCustomizeShortcuts() {
        val all = Shortcut.entries
        val enabled = a.settings.shortcuts
        a.showMultiChoice("Kısayolları seçin", all.map { it.label }, BooleanArray(all.size) { all[it] in enabled }) { state ->
            a.settings.shortcuts = all.filterIndexed { i, _ -> state[i] }
            a.refreshShortcuts()
        }
    }

    fun showStreamDialog() {
        a.showInput("Ağ akışı", "http://, https:// veya rtsp:// adresi") { url -> a.openStream(url) }
    }

    fun showPlaylist() {
        val p = a.player
        val titles = List(p.mediaItemCount) { i ->
            p.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Video ${i + 1}"
        }
        if (titles.isEmpty()) return
        a.showChoice("Oynatma listesi", titles, p.currentMediaItemIndex) { i ->
            p.seekTo(i, 0L)
            p.play()
        }
    }

    fun showBookmarks() {
        val uri = a.currentUri ?: return
        val marks = ResumeStore.bookmarks(a, uri)
        val labels = listOf("＋ Bu konuma yer imi ekle (${timeLabel(a.player.currentPosition)})") + marks.map { timeLabel(it) }
        val dialog = AlertDialog.Builder(a)
            .setTitle("Yer imleri")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    ResumeStore.setBookmarks(a, uri, marks + a.player.currentPosition)
                    a.toast("Yer imi eklendi")
                } else {
                    a.player.seekTo(marks[which - 1])
                }
            }
            .setNegativeButton("Kapat", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                if (position > 0) {
                    ResumeStore.setBookmarks(a, uri, marks.filterIndexed { i, _ -> i != position - 1 })
                    a.toast("Yer imi silindi")
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
            appendLine("Ad: ${SubtitleFiles.displayName(a, uri)}")
            SubtitleFiles.pathOf(a, uri)?.let { appendLine("Konum: $it") }
            if (size > 0) appendLine("Boyut: ${"%.1f".format(size / 1048576.0)} MB")
            if (p.duration > 0) appendLine("Süre: ${timeLabel(p.duration)}")
            if (v != null) {
                appendLine("Görüntü: ${v.width}x${v.height}" +
                        (if (v.frameRate > 0) " @ ${"%.2f".format(v.frameRate)} fps" else "") +
                        (if (v.codecs != null) ", ${v.codecs}" else ", ${mimeLabel(v.sampleMimeType)}"))
                if (v.bitrate > 0) appendLine("Görüntü bit hızı: ${v.bitrate / 1000} kb/sn")
            }
            if (au != null) {
                appendLine("Ses: ${mimeLabel(au.sampleMimeType)}, ${au.channelCount} kanal, ${au.sampleRate} Hz")
            }
            appendLine("Çözücü modu: ${a.settings.decoder.label}")
        }
        a.showMessage("Bilgi", text.trim())
    }

    // ------------------------------------------------------------ üç nokta paneli

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
                item(R.drawable.ic_list, "Listeler") { showPlaylist() },
                item(R.drawable.ic_aspect, "En boy oranı") { showAspectMenu() },
                item(R.drawable.ic_subtitles, "Özel stil") { showSubtitleStyle() },
                item(R.drawable.ic_bookmark, "Yer imi") { showBookmarks() },
                item(R.drawable.ic_info, "Bilgi") { showInfo() },
                item(R.drawable.ic_share, "Paylaş") { a.shareCurrent() },
                item(R.drawable.ic_public, "Ağ akışı") { showStreamDialog() },
                item(R.drawable.ic_lock, "Çocuk kilidi") { a.startKidsLock() },
                item(R.drawable.ic_tune, "Ses ayarları") { showEqualizer() },
                item(R.drawable.ic_headphones, "Ses gecikmesi") { showAudioDelay() },
                item(R.drawable.ic_timer, "Altyazı senkronu") { showSubtitleSync() },
                item(R.drawable.ic_settings, "Ayarlar") { a.openSettings() },
            )
            items.forEach { addView(it) }
        }
        val shortcutsSwitch = SwitchCompat(a).apply {
            text = "Kısayollar"
            setTextColor(Color.WHITE)
            setShadowLayer(a.dp(4).toFloat(), 0f, 0f, Color.BLACK)
            isChecked = a.settings.showShortcuts
            setOnCheckedChangeListener { _, on ->
                a.settings.showShortcuts = on
                a.refreshShortcuts()
            }
        }
        val remainingSwitch = SwitchCompat(a).apply {
            text = "Kalan süreyi göster"
            setTextColor(Color.WHITE)
            setShadowLayer(a.dp(4).toFloat(), 0f, 0f, Color.BLACK)
            isChecked = a.settings.showRemainingTime
            setOnCheckedChangeListener { _, on -> a.settings.showRemainingTime = on }
        }
        val root = a.vertical(12) {
            addView(grid)
            addView(shortcutsSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            addView(remainingSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
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
