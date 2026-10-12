package org.openplayer.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Yedek oynatma motoru: ExoPlayer'ın açamadığı biçimler için libVLC kullanır.
 * Arayüz bilerek sadedir: oynat/duraklat, sarma, ses ve altyazı parçası, hız, dış altyazı.
 */
class VlcPlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URIS = "org.openplayer.app.VLC_URIS"
        private const val EXTRA_TITLES = "org.openplayer.app.VLC_TITLES"
        private const val EXTRA_INDEX = "org.openplayer.app.VLC_INDEX"
        private const val EXTRA_POSITION = "org.openplayer.app.VLC_POSITION"

        fun launch(context: Context, uris: List<Uri>, titles: List<String>?, index: Int, positionMs: Long) {
            context.startActivity(
                Intent(context, VlcPlayerActivity::class.java)
                    .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                    .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles ?: emptyList()))
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_POSITION, positionMs)
            )
        }
    }

    private var libVlc: LibVLC? = null
    private var vlc: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var controls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var seek: SeekBar
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var playButton: ImageView
    private lateinit var message: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private var dragging = false
    private var controlsVisible = true
    private var speed = 1f
    private var resumeAt = 0L
    private var subtitleSeq = 0

    private val hideRunnable = Runnable { showControls(false) }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = (uri.lastPathSegment ?: "sub.srt").substringAfterLast('/').substringAfterLast(':')
        val ext = name.substringAfterLast('.', "srt").take(5)
        val f = java.io.File(cacheDir, "vlc_sub_${subtitleSeq++}.$ext")
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            vlc?.addSlave(Media.Slave.Type.Subtitle, Uri.fromFile(f), true)
        }.getOrNull() != null
        toast(if (ok) "Altyazı eklendi" else "Altyazı yüklenemedi")
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        uris = runCatching { intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS) }.getOrNull()?.filterNotNull() ?: emptyList()
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        resumeAt = intent.getLongExtra(EXTRA_POSITION, 0L)
        if (uris.isEmpty()) {
            finish()
            return
        }
        setContentView(buildUi())
        hideBars()
        start()
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        videoLayout = VLCVideoLayout(this)
        titleView = label("", 16f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(iconView(R.drawable.ic_back).apply { setOnClickListener { finish() } })
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconView(R.drawable.ic_headphones).apply { setOnClickListener { chooseAudio() } })
            addView(iconView(R.drawable.ic_subtitles).apply { setOnClickListener { chooseSubtitle() } })
            addView(iconView(R.drawable.ic_timer).apply { setOnClickListener { chooseSpeed() } })
        }
        posView = label("0:00", 13f)
        durView = label("0:00", 13f)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) posView.text = formatDuration((vlc?.length ?: 0L) * p / 1000)
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    dragging = true
                    handler.removeCallbacks(hideRunnable)
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    val len = vlc?.length ?: 0L
                    if (len > 0) vlc?.time = len * s.progress / 1000
                    dragging = false
                    scheduleHide()
                }
            })
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(posView)
            addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(durView)
        }
        playButton = iconView(R.drawable.ic_pause, 60, 14).apply {
            setOnClickListener { togglePlay() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(iconView(R.drawable.ic_prev).apply { setOnClickListener { step(-1) } })
            addView(iconView(R.drawable.ic_replay10).apply { setOnClickListener { seekBy(-10_000) } })
            addView(playButton)
            addView(iconView(R.drawable.ic_forward10).apply { setOnClickListener { seekBy(10_000) } })
            addView(iconView(R.drawable.ic_next).apply { setOnClickListener { step(1) } })
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            addView(seekRow)
            addView(buttons)
        }
        message = label("", 15f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoLayout, FrameLayout.LayoutParams(-1, -1))
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showControls(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }
        })
        videoLayout.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        return root
    }

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        val v = if (show) View.VISIBLE else View.GONE
        controls.visibility = v
        topBar.visibility = v
        handler.removeCallbacks(hideRunnable)
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 4000)
    }

    // ------------------------------------------------------------------ oynatma

    private fun start() {
        val lib = runCatching { LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames")) }.getOrNull()
        if (lib == null) {
            fail("VLC motoru başlatılamadı.")
            return
        }
        libVlc = lib
        val mp = MediaPlayer(lib)
        vlc = mp
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { e ->
            runOnUiThread {
                when (e.type) {
                    MediaPlayer.Event.EndReached -> if (index < uris.size - 1) step(1) else finish()
                    MediaPlayer.Event.EncounteredError -> fail("Bu dosya VLC motoruyla da açılamadı.")
                    MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> updatePlayIcon()
                }
            }
        }
        open(index, resumeAt)
        handler.post(ticker)
        showControls(true)
    }

    private fun open(i: Int, positionMs: Long) {
        val lib = libVlc ?: return
        val mp = vlc ?: return
        index = i
        message.visibility = View.GONE
        val uri = uris[i]
        titleView.text = titles.getOrNull(i)?.takeIf { it.isNotBlank() } ?: SubtitleFiles.displayName(this, uri)
        runCatching { pfd?.close() }
        pfd = null
        val media = runCatching {
            if (uri.scheme == "content") {
                val p = contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
                pfd = p
                Media(lib, p.fileDescriptor)
            } else Media(lib, uri)
        }.getOrNull()
        if (media == null) {
            fail("Dosya açılamadı.")
            return
        }
        media.setHWDecoderEnabled(true, false)
        mp.media = media
        media.release()
        mp.play()
        if (positionMs > 3000) mp.time = positionMs
        mp.rate = speed
    }

    private fun fail(msg: String) {
        message.text = msg
        message.visibility = View.VISIBLE
        showControls(true)
    }

    private fun step(delta: Int) {
        val n = index + delta
        if (n in uris.indices) open(n, 0L)
    }

    private fun seekBy(deltaMs: Long) {
        val mp = vlc ?: return
        val len = mp.length
        val t = (mp.time + deltaMs).coerceAtLeast(0L)
        mp.time = if (len > 0) t.coerceAtMost(len) else t
        scheduleHide()
    }

    private fun togglePlay() {
        val mp = vlc ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
        updatePlayIcon()
        scheduleHide()
    }

    private fun updatePlayIcon() {
        playButton.setImageResource(if (vlc?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        val mp = vlc ?: return
        val len = mp.length
        val t = mp.time
        durView.text = if (len > 0) formatDuration(len) else "--:--"
        if (!dragging) {
            posView.text = formatDuration(t.coerceAtLeast(0L))
            seek.progress = if (len > 0) (t * 1000 / len).toInt().coerceIn(0, 1000) else 0
        }
    }

    // ------------------------------------------------------------------ parçalar

    private fun chooseAudio() {
        val mp = vlc ?: return
        val tracks = mp.audioTracks ?: emptyArray()
        if (tracks.isEmpty()) {
            toast("Ses parçası bilgisi yok")
            return
        }
        val cur = tracks.indexOfFirst { it.id == mp.audioTrack }
        showChoice("Ses parçası", tracks.map { it.name }, cur) { mp.audioTrack = tracks[it].id }
    }

    private fun chooseSubtitle() {
        val mp = vlc ?: return
        val tracks = mp.spuTracks ?: emptyArray()
        val labels = ArrayList<String>()
        labels += "Kapalı"
        tracks.forEach { labels += it.name }
        labels += "Dosyadan altyazı yükle…"
        showChoice("Altyazı", labels, -1) { i ->
            when {
                i == 0 -> mp.spuTrack = -1
                i == labels.size - 1 -> pickSubtitle.launch(arrayOf("*/*"))
                else -> mp.spuTrack = tracks[i - 1].id
            }
        }
    }

    private fun chooseSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val sel = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - speed) } ?: 2
        showChoice("Oynatma hızı", speeds.map { "${it}x" }, sel) {
            speed = speeds[it]
            vlc?.rate = speed
        }
    }

    override fun onPause() {
        super.onPause()
        vlc?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            vlc?.stop()
            vlc?.detachViews()
            vlc?.release()
            libVlc?.release()
        }
        runCatching { pfd?.close() }
        vlc = null
        libVlc = null
        super.onDestroy()
    }
}
