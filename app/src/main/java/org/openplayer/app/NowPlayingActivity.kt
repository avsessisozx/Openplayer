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
    private lateinit var seek: SeekBar
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var playButton: ImageView
    private lateinit var shuffleButton: ImageView
    private lateinit var repeatButton: ImageView
    private lateinit var sleepLabel: TextView
    private lateinit var speedLabel: TextView
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

    private fun buildUi(): View {
        val metrics = resources.displayMetrics
        val artSize = minOf((metrics.widthPixels * 0.8f).toInt(), (metrics.heightPixels * 0.42f).toInt())

        art = ImageView(this)
        ArtLoader.roundCorners(art, 20)
        lyricsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        lyricsScroll = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            addView(lyricsBox)
        }
        artFrame = android.widget.FrameLayout(this).apply {
            addView(art, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(lyricsScroll, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { toggleLyrics() }
        }
        titleView = text(22f, bold = true).apply { maxLines = 2 }
        artistView = text(15f, UiColors.textSecondary)

        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) posView.text = formatDuration(p.toLong() * (player?.duration?.coerceAtLeast(0) ?: 0L) / 1000)
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    val p = player
                    if (p != null && p.duration > 0) p.seekTo(s.progress.toLong() * p.duration / 1000)
                    dragging = false
                }
            })
        }
        posView = text(12f, UiColors.textSecondary).apply { gravity = Gravity.START }
        durView = text(12f, UiColors.textSecondary).apply { gravity = Gravity.END }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(posView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(durView, LinearLayout.LayoutParams(0, -2, 1f))
        }

        shuffleButton = iconView(R.drawable.ic_shuffle, 52, 14).apply {
            setOnClickListener { MusicController.setShuffle(context, !(player?.shuffleModeEnabled ?: false)) }
        }
        val prev = iconView(R.drawable.ic_prev, 60, 16).apply { setOnClickListener { player?.seekToPrevious() } }
        playButton = iconView(R.drawable.ic_play, 76, 18).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiColors.accent)
            }
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) p.pause() else p.play()
            }
        }
        val next = iconView(R.drawable.ic_next, 60, 16).apply { setOnClickListener { player?.seekToNextMediaItem() } }
        repeatButton = iconView(R.drawable.ic_repeat, 52, 14).apply {
            setOnClickListener {
                val cur = AppSettings.get(context).musicRepeat
                // kapalı -> tüm liste -> tek şarkı -> kapalı
                val nextMode = when (cur) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                MusicController.setRepeat(context, nextMode)
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(shuffleButton)
            addView(prev)
            addView(playButton, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(next)
            addView(repeatButton)
        }

        sleepLabel = text(12f, UiColors.textSecondary)
        val sleepButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(iconView(R.drawable.ic_timer, 44, 10))
            addView(sleepLabel)
            setOnClickListener { showSleep() }
        }
        val fxButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(iconView(R.drawable.ic_tune, 44, 10))
            addView(text(12f, UiColors.textSecondary).apply { text = "Ses ayarları" })
            setOnClickListener { MusicController.showFxPanel(this@NowPlayingActivity) }
        }
        val queueButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(iconView(R.drawable.ic_view_list, 44, 10))
            addView(text(12f, UiColors.textSecondary).apply { text = "Kuyruk" })
            setOnClickListener { showQueue() }
        }
        speedLabel = text(12f, UiColors.textSecondary)
        val speedButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(iconView(R.drawable.ic_repeat, 44, 10))
            addView(speedLabel)
            setOnClickListener { showSpeed() }
        }
        val lyricsButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(iconView(R.drawable.ic_lyrics, 44, 10))
            addView(text(12f, UiColors.textSecondary).apply { text = "Sözler" })
            setOnClickListener { toggleLyrics() }
        }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(sleepButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(speedButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(lyricsButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(fxButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(queueButton, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val back = iconView(R.drawable.ic_back).apply { setOnClickListener { finish() } }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(text(16f, UiColors.textSecondary).apply { text = "Çalıyor" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(16))
            addView(artFrame, LinearLayout.LayoutParams(artSize, artSize).apply { topMargin = dp(8) })
            addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            addView(times, LinearLayout.LayoutParams(-1, -2))
            addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(bottomRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(UiColors.background)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@NowPlayingActivity).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
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
            ArtLoader.load(art, full, 800)
            loadLyrics(library[item?.mediaId ?: ""])
        }
        playButton.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffleButton.setColorFilter(if (p.shuffleModeEnabled) UiColors.accent else Color.WHITE)
        repeatButton.setImageResource(if (p.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.setColorFilter(if (p.repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else UiColors.accent)
        tick()
    }

    private fun tick() {
        val p = player ?: return
        val dur = p.duration
        durView.text = if (dur > 0) formatDuration(dur) else "--:--"
        if (!dragging) {
            posView.text = formatDuration(p.currentPosition)
            seek.progress = if (dur > 0) (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000) else 0
        }
        val end = MusicController.sleepEndsAtMs
        sleepLabel.text = when {
            MusicController.sleepAtEnd -> "Şarkı sonunda"
            end > System.currentTimeMillis() -> "Uyku: ${((end - System.currentTimeMillis()) / 60_000L).toInt() + 1} dk"
            else -> "Uyku"
        }
        speedLabel.text = "Hız ${"%.2f".format(p.playbackParameters.speed).trimEnd('0').trimEnd('.')}x"
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
                this, "Bu şarkı için söz dosyası yok. Kütüphanede şarkının ⋮ menüsü → Etiketleri düzenle → söz dosyası ekle.",
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
        showChoice("Çalma hızı", speeds.map { "${it}x" }, sel) { player?.setPlaybackSpeed(speeds[it]) }
    }

    private fun showSleep() {
        val options = listOf("Kapalı", "Bu şarkı bitince", "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika")
        val minutes = listOf(0, -1, 15, 30, 45, 60, 90)
        showChoice("Uyku zamanlayıcısı", options, -1) {
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
            .setTitle("Kuyruk (${titles.size})")
            .setSingleChoiceItems(titles.toTypedArray(), p.currentMediaItemIndex) { d, which ->
                p.seekToDefaultPosition(which)
                p.play()
                d.dismiss()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }
}
