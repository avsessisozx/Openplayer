package org.openplayer.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** "Çalıyor" ekranı: kapak, ilerleme, denetimler, kuyruk, uyku zamanlayıcısı, ses ayarları. */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var art: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var wave: WaveSeekView
    private lateinit var favButton: ImageView
    private lateinit var infoView: TextView
    private lateinit var rootView: LinearLayout
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var playButton: ImageView
    private lateinit var shuffleButton: LinearLayout
    private lateinit var repeatButton: LinearLayout
    private lateinit var sleepLabel: TextView
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsBox: LinearLayout
    private lateinit var artFrame: android.widget.FrameLayout
    private var library: Map<String, MusicTrack> = emptyMap()
    private var lyricLines: List<LyricLine>? = null
    private var lyricViews: List<TextView> = emptyList()
    private var lyricIndex = -1
    private var showLyrics = false
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var lastMediaId: String? = null

    private val player: Player? get() = PlayerHolder.player

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 400)
        }
    }

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
        ThemeManager.applyWindow(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        val p = PlayerHolder.player
        if (p == null || !MusicController.isMusic(p.currentMediaItem)) {
            finish()
            return
        }
        p.addListener(listener)
        refresh()
        handler.post(ticker)
        val app = applicationContext
        Thread {
            val map = MusicRepository.loadAll(app).associateBy { it.id.toString() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    library = map
                    lastMediaId = null
                    refresh()
                }
            }
        }.start()
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        PlayerHolder.player?.removeListener(listener)
        super.onStop()
    }

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }

    private fun chipBg(color: Int, radiusDp: Int = 22) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun chip(size: Float, bold: Boolean): TextView = text(size, Color.WHITE, bold).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16), dp(7), dp(16), dp(7))
        background = chipBg(0x4D000000)
    }

    /** Yarı saydam, yuvarlak köşeli düğme. */
    private fun pill(icon: Int, widthDp: Int = 72, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = chipBg(0x4D000000, 24)
        addView(iconView(icon, 48, 12))
        minimumWidth = dp(widthDp)
        setOnClickListener { onClick() }
    }

    private fun circle(icon: Int, sizeDp: Int, padDp: Int, bg: Int, tint: Int = Color.WHITE, onClick: () -> Unit) =
        iconView(icon, sizeDp, padDp).apply {
            setColorFilter(tint)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            setOnClickListener { onClick() }
        }

    private fun buildUi(): View {
        val metrics = resources.displayMetrics
        val artSize = minOf(metrics.widthPixels - dp(32), (metrics.heightPixels * 0.5f).toInt())

        art = ImageView(this)
        ArtLoader.roundCorners(art, 24)
        lyricsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        lyricsScroll = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            background = chipBg(0x99000000.toInt(), 24)
            addView(lyricsBox)
        }
        favButton = circle(R.drawable.ic_favorite_border, 48, 12, 0x66000000) { toggleFavorite() }
        val queueButton = circle(R.drawable.ic_view_list, 48, 12, 0x66000000) { showQueue() }
        val moreButton = circle(R.drawable.ic_more_vert, 48, 12, 0x66000000) { showMoreSheet() }
        artFrame = android.widget.FrameLayout(this).apply {
            addView(art, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(lyricsScroll, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(favButton, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(8)) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(queueButton)
                addView(moreButton)
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
            setOnClickListener { toggleLyrics() }
        }
        titleView = chip(22f, true)
        artistView = chip(15f, true)

        wave = WaveSeekView(this).apply {
            onScrub = { f ->
                val d = player?.duration?.coerceAtLeast(0) ?: 0L
                posView.text = formatDuration((f * d).toLong())
            }
            onSeek = { f ->
                val p = player
                if (p != null && p.duration > 0) p.seekTo((f * p.duration).toLong())
            }
            onDragChanged = { dragging = it }
        }
        posView = text(13f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = chipBg(0xCC000000.toInt(), 16)
        }
        durView = text(13f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = chipBg(0xCC000000.toInt(), 16)
        }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(posView, LinearLayout.LayoutParams(-2, -2))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(durView, LinearLayout.LayoutParams(-2, -2))
        }

        shuffleButton = pill(R.drawable.ic_shuffle) {
            MusicController.setShuffle(this, !(player?.shuffleModeEnabled ?: false))
        }
        repeatButton = pill(R.drawable.ic_repeat) {
            val cur = AppSettings.get(this).musicRepeat
            // kapalı -> tüm liste -> tek şarkı -> kapalı
            val nextMode = when (cur) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            MusicController.setRepeat(this, nextMode)
        }
        sleepLabel = text(12f, Color.WHITE).apply { setPadding(0, 0, dp(12), 0); visibility = View.GONE }
        val fxPill = pill(R.drawable.ic_tune) { MusicController.showFxPanel(this) }
        val sleepPill = pill(R.drawable.ic_timer) { showSleep() }.apply { addView(sleepLabel) }
        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fxPill)
            addView(sleepPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(repeatButton)
            addView(shuffleButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }

        val back10 = circle(R.drawable.ic_replay10, 52, 14, 0x4D000000) {
            player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
        }
        val prev = circle(R.drawable.ic_prev, 64, 18, Color.BLACK) { player?.seekToPrevious() }
        playButton = circle(R.drawable.ic_play, 88, 24, Color.BLACK) {
            val p = player ?: return@circle
            if (p.isPlaying) p.pause() else p.play()
        }
        val next = circle(R.drawable.ic_next, 64, 18, Color.BLACK) { player?.seekToNextMediaItem() }
        val fwd10 = circle(R.drawable.ic_forward10, 52, 14, 0x4D000000) {
            player?.let { p ->
                val d = p.duration
                val t = p.currentPosition + 10_000
                p.seekTo(if (d > 0) t.coerceAtMost(d) else t)
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(back10)
            addView(prev, LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(8), 0, dp(4), 0) })
            addView(playButton, LinearLayout.LayoutParams(dp(88), dp(88)).apply { setMargins(dp(4), 0, dp(4), 0) })
            addView(next, LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(4), 0, dp(8), 0) })
            addView(fwd10)
        }
        infoView = text(12f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = chipBg(0x4D000000, 14)
        }

        val back = iconView(R.drawable.ic_back).apply { setOnClickListener { finish() } }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(text(16f, Color.WHITE).apply { text = Tx.s(R.string.nowplaying_001) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(artFrame, LinearLayout.LayoutParams(artSize, artSize).apply { topMargin = dp(4) })
            addView(titleView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); gravity = Gravity.START })
            addView(artistView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); gravity = Gravity.START })
            addView(pills, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(wave, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })
            addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(infoView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(UiColors.background, UiColors.background))
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@NowPlayingActivity).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return rootView
    }

    // ------------------------------------------------------------ arka plan rengi, favori, bilgi

    private fun applyBackground(bmp: android.graphics.Bitmap) {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 12, 12, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until 12) for (y in 0 until 12) {
            val c = small.getPixel(x, y)
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            // Gri/çok koyu/çok açık pikseller rengi sulandırır; doygun olanlar daha ağır sayılır.
            val w = (1 + (hsv[1] * 6).toInt()).toLong()
            r += Color.red(c) * w; g += Color.green(c) * w; b += Color.blue(c) * w; n += w
        }
        if (n == 0L) return
        val avg = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        val hsv = FloatArray(3)
        Color.colorToHSV(avg, hsv)
        val topHsv = floatArrayOf(hsv[0], (hsv[1] * 0.9f).coerceIn(0f, 0.8f), 0.5f)
        val botHsv = floatArrayOf(hsv[0], (hsv[1] * 0.9f).coerceIn(0f, 0.8f), 0.1f)
        rootView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.HSVToColor(topHsv), Color.HSVToColor(botHsv))
        )
    }

    private fun currentUri(): String? = player?.currentMediaItem?.localConfiguration?.uri?.toString()

    private fun toggleFavorite() {
        val u = currentUri() ?: return
        PlaylistStore.toggleFavorite(this, PlaylistStore.MUSIC, u)
        updateFavorite()
    }

    private fun updateFavorite() {
        val u = currentUri()
        val fav = u != null && PlaylistStore.isFavorite(this, PlaylistStore.MUSIC, u)
        favButton.setImageResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        favButton.setColorFilter(if (fav) 0xFFFF5C7A.toInt() else Color.WHITE)
    }

    private fun kbpsText(): String {
        val p = PlayerHolder.player ?: return ""
        val fmt = p.audioFormat
        val parts = ArrayList<String>()
        val rate = fmt?.sampleRate ?: 0
        if (rate > 0) {
            val khz = rate / 1000.0
            parts += (if (khz == khz.toInt().toDouble()) khz.toInt().toString() else "%.1f".format(khz)) + " kHz"
        }
        val track = library[p.currentMediaItem?.mediaId ?: ""]
        var bitrate = fmt?.bitrate ?: -1
        if (bitrate <= 0 && track != null && track.durationMs > 0) bitrate = (track.sizeBytes * 8 * 1000 / track.durationMs).toInt()
        if (bitrate > 0) parts += "${bitrate / 1000} kbps"
        val ext = track?.path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: fmt?.sampleMimeType?.substringAfterLast('/')
        if (!ext.isNullOrEmpty()) parts += ext
        return parts.joinToString("  ").uppercase()
    }

    private fun showMoreSheet() {
        val u = currentUri() ?: return
        val fav = PlaylistStore.isFavorite(this, PlaylistStore.MUSIC, u)
        val e = ArrayList<SheetEntry>()
        e += SheetEntry(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border, if (fav) Tx.s(R.string.mainactivi_042) else Tx.s(R.string.mainactivi_077)) { toggleFavorite() }
        e += SheetEntry(R.drawable.ic_playlist_add, Tx.s(R.string.mainactivi_067)) { showAddToPlaylist(PlaylistStore.MUSIC, listOf(u)) }
        e += SheetEntry(R.drawable.ic_lyrics, Tx.s(R.string.nowplaying_002)) { toggleLyrics() }
        e += SheetEntry(R.drawable.ic_repeat, Tx.s(R.string.nowplaying_003)) { showSpeed() }
        e += SheetEntry(R.drawable.ic_timer, Tx.s(R.string.nowplaying_004)) { showSleep() }
        e += SheetEntry(R.drawable.ic_tune, Tx.s(R.string.musiccontr_017)) { MusicController.showFxPanel(this) }
        e += SheetEntry(R.drawable.ic_view_list, Tx.s(R.string.nowplaying_010)) { showQueue() }
        showSheet(titleView.text?.toString() ?: "", e)
    }

    private fun refresh() {
        val p = player ?: return
        val item = p.currentMediaItem
        if (!MusicController.isMusic(item)) {
            finish()
            return
        }
        val meta = item?.mediaMetadata
        titleView.text = meta?.title ?: ""
        artistView.text = listOfNotNull(meta?.artist, meta?.albumTitle).joinToString(" • ")
        if (item?.mediaId != lastMediaId) {
            lastMediaId = item?.mediaId
            val id = item?.mediaId?.toLongOrNull()
            val albumUri = meta?.artworkUri
            val albumId = albumUri?.lastPathSegment?.toLongOrNull() ?: 0L
            val uri = item?.localConfiguration?.uri
            val track = if (id != null && uri != null) MusicTrack(
                id = id, uri = uri, title = "", artist = "", album = "",
                albumId = albumId, durationMs = 0, trackNo = 0, dateAddedSec = 0, folderPath = ""
            ) else null
            val full = library[item?.mediaId ?: ""] ?: track
            ArtLoader.load(art, full, 800) { applyBackground(it) }
            wave.setSeed(item?.mediaId?.toLongOrNull() ?: 0L)
            loadLyrics(library[item?.mediaId ?: ""])
        }
        playButton.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        (shuffleButton.getChildAt(0) as ImageView).setColorFilter(if (p.shuffleModeEnabled) UiColors.accent else Color.WHITE)
        (repeatButton.getChildAt(0) as ImageView).apply {
            setImageResource(if (p.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
            setColorFilter(if (p.repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else UiColors.accent)
        }
        updateFavorite()
        infoView.text = kbpsText()
        infoView.visibility = if (infoView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        tick()
    }

    private fun tick() {
        val p = player ?: return
        val dur = p.duration
        durView.text = if (dur > 0) formatDuration(dur) else "--:--"
        if (!dragging) {
            posView.text = formatDuration(p.currentPosition)
            wave.progress = if (dur > 0) (p.currentPosition.toFloat() / dur) else 0f
        }
        val end = MusicController.sleepEndsAtMs
        val sleepText = when {
            MusicController.sleepAtEnd -> Tx.s(R.string.nowplaying_005)
            end > System.currentTimeMillis() -> "${((end - System.currentTimeMillis()) / 60_000L).toInt() + 1} min"
            else -> ""
        }
        sleepLabel.text = sleepText
        sleepLabel.visibility = if (sleepText.isEmpty()) View.GONE else View.VISIBLE
        updateLyricHighlight(p.currentPosition)
    }

    // ------------------------------------------------------------ sözler

    private fun toggleLyrics() {
        showLyrics = !showLyrics
        applyLyricsVisibility()
    }

    private fun applyLyricsVisibility() {
        val has = !lyricLines.isNullOrEmpty()
        if (showLyrics && !has) {
            showLyrics = false
            android.widget.Toast.makeText(
                this, Tx.s(R.string.nowplaying_006),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        lyricsScroll.visibility = if (showLyrics) View.VISIBLE else View.GONE
        art.visibility = if (showLyrics) View.INVISIBLE else View.VISIBLE
    }

    private fun loadLyrics(track: MusicTrack?) {
        lyricsBox.removeAllViews()
        lyricIndex = -1
        lyricViews = emptyList()
        lyricLines = null
        if (track != null) {
            val app = applicationContext
            Thread {
                val lines = Lyrics.load(app, track)
                runOnUiThread {
                    if (isFinishing || isDestroyed || lastMediaId != track.id.toString()) return@runOnUiThread
                    lyricLines = lines
                    val views = ArrayList<TextView>()
                    lines?.forEach { l ->
                        val tv = TextView(this).apply {
                            text = l.text
                            gravity = Gravity.CENTER
                            setTextColor(UiColors.textSecondary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                            setPadding(0, dp(6), 0, dp(6))
                            if (l.timeMs >= 0) setOnClickListener { player?.seekTo(l.timeMs.coerceAtLeast(0)) }
                        }
                        lyricsBox.addView(tv, LinearLayout.LayoutParams(-1, -2))
                        views += tv
                    }
                    lyricViews = views
                    if (lines == null) {
                        showLyrics = false
                    }
                    applyLyricsVisibility()
                }
            }.start()
        } else {
            showLyrics = false
            applyLyricsVisibility()
        }
    }

    private fun updateLyricHighlight(posMs: Long) {
        val lines = lyricLines ?: return
        if (lines.isEmpty() || lines[0].timeMs < 0 || lyricViews.size != lines.size) return
        var idx = -1
        for (i in lines.indices) if (lines[i].timeMs <= posMs) idx = i else break
        if (idx == lyricIndex) return
        lyricIndex = idx
        lyricViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == idx) Color.WHITE else UiColors.textSecondary)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == idx) 20f else 17f)
        }
        if (showLyrics && idx >= 0) {
            val tv = lyricViews[idx]
            lyricsScroll.post {
                lyricsScroll.smoothScrollTo(0, (tv.top - lyricsScroll.height / 2 + tv.height / 2).coerceAtLeast(0))
            }
        }
    }

    private fun showSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val cur = player?.playbackParameters?.speed ?: 1f
        val sel = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - cur) } ?: 2
        showChoice(Tx.s(R.string.nowplaying_003), speeds.map { "${it}x" }, sel) { player?.setPlaybackSpeed(speeds[it]) }
    }

    private fun showSleep() {
        val options = listOf(Tx.s(R.string.audiofx_004), Tx.s(R.string.nowplaying_007), "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika")
        val minutes = listOf(0, -1, 15, 30, 45, 60, 90)
        showChoice(Tx.s(R.string.nowplaying_004), options, -1) {
            when (minutes[it]) {
                0 -> { MusicController.startSleep(0); MusicController.sleepAtEndOfTrack(false) }
                -1 -> MusicController.sleepAtEndOfTrack(true)
                else -> MusicController.startSleep(minutes[it])
            }
            tick()
        }
    }

    private fun showQueue() {
        val p = player ?: return
        val titles = List(p.mediaItemCount) { i ->
            val m = p.getMediaItemAt(i).mediaMetadata
            "${m.title ?: ""} — ${m.artist ?: ""}"
        }
        PanelBuilder(this)
            .setTitle(Tx.s(R.string.nowplaying_008, titles.size))
            .setSingleChoiceItems(titles.toTypedArray(), p.currentMediaItemIndex) { d, which ->
                p.seekToDefaultPosition(which)
                p.play()
                d.dismiss()
            }
            .setNegativeButton(Tx.s(R.string.nowplaying_009), null)
            .show()
    }
}
