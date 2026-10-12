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
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(sleepButton, LinearLayout.LayoutParams(0, -2, 1f))
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
            addView(art, LinearLayout.LayoutParams(artSize, artSize).apply { topMargin = dp(8) })
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
            ArtLoader.load(art, track, 800)
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
        sleepLabel.text = if (end > System.currentTimeMillis()) {
            val min = ((end - System.currentTimeMillis()) / 60_000L).toInt() + 1
            "Uyku: $min dk"
        } else "Uyku zamanlayıcısı"
    }

    private fun showSleep() {
        val options = listOf("Kapalı", "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika")
        val minutes = listOf(0, 15, 30, 45, 60, 90)
        showChoice("Uyku zamanlayıcısı", options, -1) {
            MusicController.startSleep(minutes[it])
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
