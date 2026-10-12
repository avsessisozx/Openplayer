package org.openplayer.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.util.TypedValue
import android.media.AudioManager
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AspectMode(val label: String, val resize: Int, val ratio: Float?) {
    FIT("Sığdır", AspectRatioFrameLayout.RESIZE_MODE_FIT, null),
    FILL("Esnet (doldur)", AspectRatioFrameLayout.RESIZE_MODE_FILL, null),
    CROP("Kırp (yakınlaştır)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, null),
    R16_9("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f),
    R4_3("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f),
    R21_9("21:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 21f / 9f),
}

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "org.openplayer.app.URIS"
        const val EXTRA_TITLES = "org.openplayer.app.TITLES"
        const val EXTRA_INDEX = "org.openplayer.app.INDEX"
    }

    val settings by lazy { AppSettings.get(this) }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSubtitle(uri)
        }

    fun pickSubtitleFile() {
        subtitlePicker.launch(arrayOf("*/*"))
    }

    private fun importSubtitle(uri: Uri) {
        Thread {
            try {
                val name = UriGuard.safeFileName(SubtitleFiles.displayName(this, uri))
                val file = File(SubtitleFiles.downloadDir(this), "import_${System.currentTimeMillis()}_$name")
                val ok = contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { SubtitleFiles.copyLimited(input, it) }
                } ?: false
                if (!ok) { file.delete(); throw java.io.IOException("too large") }
                SubtitleFiles.pruneDownloads(this)
                runOnUiThread { loadExternalSubtitle(file) }
            } catch (e: Exception) {
                runOnUiThread { toast("Dosya okunamadı") }
            }
        }.start()
    }

    lateinit var player: ExoPlayer
    lateinit var subtitles: SubtitleController
    lateinit var audioFx: AudioFx
    private lateinit var menus: PlayerMenus

    // Görünümler
    private lateinit var playerView: PlayerView
    private lateinit var subtitleView: SubtitleView
    private lateinit var nightOverlay: View
    private lateinit var controls: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var decoderLabel: TextView
    private lateinit var shortcutScroll: HorizontalScrollView
    private lateinit var shortcutRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var playButton: ImageView
    private lateinit var prevButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var aspectButton: ImageView
    private lateinit var lockButton: ImageView
    private lateinit var unlockButton: ImageView
    private lateinit var hud: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Durum
    var locked = false
        private set
    var kidsLocked = false
        private set
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    var aspect = AspectMode.FIT
        private set
    var mirror = false
        private set
    var vflip = false
        private set
    var night = false
        private set
    var muted = false
        private set
    var abA = -1L
        private set
    var abB = -1L
        private set
    var sleepDeadline = 0L
        private set
    var sleepAtEnd = false
        private set
    var sidecars: List<File> = emptyList()
        private set
    var currentUri: Uri? = null
        private set
    private var controlsVisible = false
    private var scrubbing = false
    private var subtitleAutoDone = false
    private var kidsStep = 0
    private var kidsLastTap = 0L
    private var ticks = 0
    private var errorDialogShown = false

    // ---------------------------------------------------------------- yaşam döngüsü

    private val rebuildListener: (ExoPlayer) -> Unit = { fresh ->
        runOnUiThread {
            bindPlayer(fresh)
            onItemChanged()
            refreshAll()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            scheduleAutoHide()
        }

        override fun onPlaybackStateChanged(state: Int) {
            updatePlayButton()
            if (state == Player.STATE_ENDED) showControls(sticky = true)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) {
                sleepAtEnd = false
                player.pauseAtEndOfMediaItems = false
                refreshShortcuts()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) onItemChanged()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyOrientation()
            applyTransform()
            updatePipParams()
        }

        override fun onTracksChanged(tracks: Tracks) {
            autoSelectSubtitle(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitles.onEmbeddedCues(cueGroup.cues)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioFx.attach(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlaybackError(error)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 100)
        }
    }

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideHudRunnable = Runnable { hud.visibility = View.GONE }
    private val hideUnlockRunnable = Runnable { unlockButton.visibility = View.GONE }
    private val sleepRunnable = Runnable {
        player.pause()
        sleepDeadline = 0L
        Toast.makeText(this, "Uyku zamanlayıcısı: oynatma durduruldu", Toast.LENGTH_LONG).show()
        refreshShortcuts()
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                kidsLocked -> showHud("Çocuk kilidi açık")
                locked -> {
                    showUnlockButton()
                    toast("Ekran kilitli")
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(buildUi())
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, backCallback)

        audioFx = AudioFx(this)
        subtitles = SubtitleController(this, subtitleView)
        subtitles.applyStyle()
        menus = PlayerMenus(this)

        player = PlayerHolder.get(this)
        bindPlayer(player)
        PlayerHolder.addRebuildListener(rebuildListener)

        if (!loadFromIntent(intent)) {
            if (player.mediaItemCount == 0) {
                finish()
                return
            }
            onItemChanged()
        }
        refreshAll()
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        playerView.player = player
        handler.post(ticker)
        applyOrientation()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        saveResume()
        if (isFinishing) return
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (settings.backgroundPlay && player.isPlaying) {
            try {
                startService(Intent(this, PlaybackService::class.java))
            } catch (_: Exception) {
                // Arka plan servisi başlatılamazsa oynatma duraklatılır.
                player.pause()
            }
        } else if (!inPip) {
            player.pause()
        }
        if (!inPip) playerView.player = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PlayerHolder.removeRebuildListener(rebuildListener)
        if (::player.isInitialized) player.removeListener(playerListener)
        if (isFinishing) {
            saveResume()
            audioFx.release()
            PlayerHolder.release()
            stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (settings.autoPip && player.isPlaying && !locked && !kidsLocked) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) hideControls() else playerView.player = player
    }


    // ---------------------------------------------------------------- klavye, fare ve medya tuşları

    private val systemAudio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private fun nudgeVolume(direction: Int) {
        systemAudio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (locked || kidsLocked) return super.onKeyDown(keyCode, event)
        val step = settings.doubleTapSeekSec.coerceAtLeast(1) * 1000L
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> { togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { player.pause(); player.seekTo(0); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-(if (event.isShiftPressed) step * 6 else step)); showControls(); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(if (event.isShiftPressed) step * 6 else step); showControls(); true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { nudgeVolume(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { nudgeVolume(-1); true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_PAGE_DOWN -> { next(); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_PAGE_UP -> { previous(); true }
            KeyEvent.KEYCODE_M -> { onShortcut(Shortcut.MUTE); true }
            KeyEvent.KEYCODE_F -> { cycleAspect(); true }
            KeyEvent.KEYCODE_R -> { cycleOrientation(); true }
            KeyEvent.KEYCODE_LEFT_BRACKET -> { setSpeed((player.playbackParameters.speed - 0.1f).coerceIn(0.25f, 4f)); true }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { setSpeed((player.playbackParameters.speed + 0.1f).coerceIn(0.25f, 4f)); true }
            KeyEvent.KEYCODE_COMMA -> { jumpSubtitle(false); true }
            KeyEvent.KEYCODE_PERIOD -> { jumpSubtitle(true); true }
            else -> false
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    /** Fare tekerleği ile ses ayarı. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!locked && !kidsLocked &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                nudgeVolume(if (v > 0) +1 else -1)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------------------------------------------------------- oynatıcı bağlama

    private fun bindPlayer(p: ExoPlayer) {
        if (::player.isInitialized) player.removeListener(playerListener)
        player = p
        p.addListener(playerListener)
        playerView.player = p
        audioFx.attach(p.audioSessionId)
    }

    private fun loadFromIntent(intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        // Dışarıdan gelen her şey güvenilmezdir: şema/yol doğrulanır, sayı ve uzunluk sınırlanır.
        val rawUris: List<Uri> = runCatching {
            intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: listOfNotNull(intent.data)
        }.getOrDefault(emptyList())
        val rawTitles = runCatching { intent.getStringArrayListExtra(EXTRA_TITLES) }.getOrNull()
        val rawIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        val uris = ArrayList<Uri>()
        val titles = ArrayList<String>()
        var index = 0
        rawUris.take(UriGuard.MAX_ITEMS).forEachIndexed { i, u ->
            if (u != null && UriGuard.isAllowed(this, u)) {
                if (i == rawIndex) index = uris.size
                uris += u
                titles += UriGuard.sanitizeTitle(rawTitles?.getOrNull(i)) ?: ""
            }
        }
        if (uris.isEmpty()) {
            if (rawUris.isNotEmpty()) toast("Bu adres güvenlik nedeniyle açılmadı")
            return false
        }
        openItems(uris, titles, index)
        return true
    }

    fun openItems(uris: List<Uri>, titles: List<String>?, index: Int) {
        val items = uris.mapIndexed { i, uri ->
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(titles?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: SubtitleFiles.displayName(this, uri))
                        .build()
                )
                .build()
        }
        val saved = if (settings.resumePlayback) ResumeStore.position(this, uris[index]) else 0L
        player.setMediaItems(items, index, if (saved > 5_000) saved else 0L)
        player.prepare()
        player.playWhenReady = true
        errorDialogShown = false
        onItemChanged()
    }

    fun openStream(url: String) {
        val uri = Uri.parse(url.trim())
        if (uri.scheme?.lowercase() !in setOf("http", "https", "rtsp") || uri.host.isNullOrBlank() ||
            !UriGuard.isAllowed(this, uri)
        ) {
            toast("Geçerli bir adres girin (http://, https:// veya rtsp://)")
            return
        }
        openItems(listOf(uri), listOf(UriGuard.sanitizeTitle(url.trim().substringAfterLast('/')) ?: "Akış"), 0)
    }

    private fun onItemChanged() {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri
        currentUri = uri
        titleView.text = item.mediaMetadata.title ?: uri?.let { SubtitleFiles.displayName(this, it) } ?: ""
        abA = -1L
        abB = -1L
        subtitleAutoDone = false
        subtitles.delayMs = 0L
        subtitles.setOff()
        sidecars = if (uri != null) SubtitleFiles.findSidecars(this, uri) else emptyList()
        val remembered = uri?.let { SubtitleFiles.remembered(this, it) }
        if (settings.autoEnableSubtitle) {
            when {
                remembered != null -> loadExternalSubtitle(remembered, remember = false, quiet = true)
                sidecars.isNotEmpty() -> loadExternalSubtitle(sidecars.first(), remember = false, quiet = true)
            }
        }
        updateNavButtons()
        refreshShortcuts()
    }

    private fun autoSelectSubtitle(tracks: Tracks) {
        if (subtitleAutoDone || tracks.groups.isEmpty()) return
        subtitleAutoDone = true
        if (subtitles.mode != SubtitleController.Mode.OFF || !settings.autoEnableSubtitle) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        if (groups.isEmpty()) return
        val wanted = settings.subtitleLanguages.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val byLang = wanted.firstNotNullOfOrNull { lang ->
            groups.firstOrNull { g ->
                val l = g.getTrackFormat(0).language?.lowercase() ?: ""
                l.startsWith(lang) || (lang == "tr" && l.startsWith("tur"))
            }
        }
        val pick = byLang ?: groups.firstOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
        if (pick != null) subtitles.useEmbedded(pick)
    }

    fun loadExternalSubtitle(file: File, remember: Boolean = true, quiet: Boolean = false) {
        Thread {
            val result = runCatching {
                require(file.length() <= SubtitleFiles.MAX_BYTES) { "too large" }
                val text = SubtitleParser.decode(file.readBytes(), settings.subtitleCharset, settings.subtitleFallbackCharset)
                SubtitleParser.parse(file.name, text)
            }
            runOnUiThread {
                val cues = result.getOrNull()
                if (cues.isNullOrEmpty()) {
                    if (!quiet) toast("Altyazı okunamadı veya boş: ${file.name}")
                } else {
                    subtitles.useExternal(cues, file.name)
                    if (remember) currentUri?.let { SubtitleFiles.remember(this, it, file) }
                    if (!quiet) toast("Altyazı yüklendi: ${file.name}")
                }
            }
        }.start()
    }

    private fun onPlaybackError(error: PlaybackException) {
        if (errorDialogShown) return
        errorDialogShown = true
        val next = when (settings.decoder) {
            DecoderMode.HW -> DecoderMode.HW_PLUS
            DecoderMode.HW_PLUS -> DecoderMode.SW
            DecoderMode.SW -> null
        }
        val msg = "Bu video oynatılamadı (${error.errorCodeName})."
        if (next == null) {
            showMessage("Oynatma hatası", msg)
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Oynatma hatası")
                .setMessage("$msg\nÇözücü ${next.label} olarak değiştirilip tekrar denensin mi?")
                .setPositiveButton("Dene") { _, _ ->
                    errorDialogShown = false
                    changeDecoder(next)
                }
                .setNegativeButton("Hayır", null)
                .show()
        }
    }

    fun changeDecoder(mode: DecoderMode) {
        settings.decoder = mode
        PlayerHolder.rebuild(this)
        toast("Çözücü: ${mode.label}")
    }

    // ---------------------------------------------------------------- arayüz oluşturma

    private fun buildUi(): View {
        playerView = (layoutInflater.inflate(R.layout.player_view, null) as PlayerView).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            subtitleView?.visibility = View.GONE
        }
        subtitleView = SubtitleView(this)
        nightOverlay = View(this).apply {
            setBackgroundColor(0x66301800)
            visibility = View.GONE
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        decoderLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { menus.showDecoderMenu() }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView(R.drawable.ic_back).apply { setOnClickListener { onBackPressedDispatcher.onBackPressed() } })
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            addView(iconView(R.drawable.ic_music_note).apply { setOnClickListener { menus.showAudioTracks() } })
            addView(iconView(R.drawable.ic_subtitles).apply { setOnClickListener { menus.showSubtitleMenu() } })
            addView(decoderLabel, LinearLayout.LayoutParams(-2, dp(48)))
            addView(iconView(R.drawable.ic_more_vert).apply { setOnClickListener { menus.showOverflow() } })
        }

        shortcutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        shortcutScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(shortcutRow, FrameLayout.LayoutParams(-2, -2))
        }

        elapsedView = label("0:00", 14f)
        remainingView = label("-0:00", 14f).apply {
            setOnClickListener {
                settings.showRemainingTime = !settings.showRemainingTime
                updateProgress(player.currentPosition)
            }
        }
        seekBar = SeekBar(this).apply {
            val accent = ColorStateList.valueOf(UiColors.accent)
            progressTintList = accent
            thumbTintList = accent
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsedView.text = formatDuration(progress * 1000L)
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    scrubbing = true
                    handler.removeCallbacks(hideControlsRunnable)
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    scrubbing = false
                    player.seekTo(s.progress * 1000L)
                    scheduleAutoHide()
                }
            })
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(elapsedView)
            addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(remainingView)
        }

        lockButton = iconView(R.drawable.ic_lock).apply { setOnClickListener { setLocked(true) } }
        prevButton = iconView(R.drawable.ic_prev, 56, 14).apply { setOnClickListener { previous() } }
        playButton = iconView(R.drawable.ic_pause, 64, 14).apply { setOnClickListener { togglePlay() } }
        nextButton = iconView(R.drawable.ic_next, 56, 14).apply { setOnClickListener { next() } }
        aspectButton = iconView(R.drawable.ic_aspect).apply { setOnClickListener { cycleAspect() } }
        val pipButton = iconView(R.drawable.ic_pip).apply { setOnClickListener { enterPip() } }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            addView(LinearLayout(context).apply {
                gravity = Gravity.START
                addView(lockButton)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(prevButton)
            addView(playButton)
            addView(nextButton)
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(aspectButton)
                addView(pipButton)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekRow, LinearLayout.LayoutParams(-1, -2))
            addView(buttonRow, LinearLayout.LayoutParams(-1, -2))
        }

        val topScrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
        }
        val bottomScrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
        }

        controls = FrameLayout(this).apply {
            visibility = View.GONE
            addView(topScrim, FrameLayout.LayoutParams(-1, dp(220), Gravity.TOP))
            addView(bottomScrim, FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM))
            addView(topBar, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
            addView(shortcutScroll, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(60) })
            addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        // Gösterge arka plansız: yalnızca gölgeli yazı, böylece görüntüyü kapatmaz.
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        unlockButton = iconView(R.drawable.ic_lock_open, 56, 16).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            visibility = View.GONE
            setOnClickListener { setLocked(false) }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(nightOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(subtitleView, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(unlockButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = dp(24) })
        }
        playerView.setOnTouchListener(GestureController(this))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controls.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        return root
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- kontroller

    fun onScreenTap() {
        if (controlsVisible) hideControls() else showControls()
    }

    fun showControls(sticky: Boolean = false) {
        if (locked || kidsLocked) return
        controlsVisible = true
        controls.visibility = View.VISIBLE
        updateProgress(player.currentPosition)
        updatePlayButton()
        handler.removeCallbacks(hideControlsRunnable)
        if (!sticky) scheduleAutoHide()
    }

    fun hideControls() {
        controlsVisible = false
        controls.visibility = View.GONE
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && player.isPlaying && !scrubbing) {
            handler.postDelayed(hideControlsRunnable, settings.controlsTimeoutSec * 1000L)
        }
    }

    /** Menüler açıkken kontrollerin kapanmaması için. */
    fun keepControls() {
        handler.removeCallbacks(hideControlsRunnable)
    }

    fun showHud(text: String) {
        handler.removeCallbacks(hideHudRunnable)
        hud.text = text
        hud.visibility = View.VISIBLE
    }

    fun hideHudSoon() {
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 700)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying || player.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekRelative(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    private fun previous() {
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    private fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else toast("Sonraki video yok")
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        settings.playbackSpeed = speed
        refreshShortcuts()
    }

    private fun updatePlayButton() {
        playButton.setImageResource(if (player.isPlaying || player.playWhenReady && player.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateNavButtons() {
        prevButton.alpha = 1f
        nextButton.alpha = if (player.hasNextMediaItem()) 1f else 0.4f
    }

    private fun updateProgress(pos: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        seekBar.max = (duration / 1000).toInt().coerceAtLeast(1)
        if (!scrubbing) {
            seekBar.progress = (pos / 1000).toInt()
            elapsedView.text = formatDuration(pos)
        }
        remainingView.text = if (settings.showRemainingTime) "-" + formatDuration((duration - pos).coerceAtLeast(0)) else formatDuration(duration)
        seekBar.secondaryProgress = (player.bufferedPosition / 1000).toInt()
        decoderLabel.text = settings.decoder.short
    }

    private fun tick() {
        val pos = player.currentPosition
        subtitles.tick(pos)
        if (abA >= 0 && abB > abA && pos >= abB) player.seekTo(abA)
        if (controlsVisible) updateProgress(pos)
        if (++ticks % 50 == 0 && player.isPlaying) saveResume()
    }

    fun saveResume() {
        val uri = currentUri ?: return
        if (!::player.isInitialized) return
        val duration = player.duration
        val pos = player.currentPosition
        val finished = duration > 0 && pos > duration - 5_000
        ResumeStore.save(this, uri, if (finished) 0L else pos)
        if (duration > 0 && pos > duration * 0.9) ResumeStore.markPlayed(this, uri)
    }

    // ---------------------------------------------------------------- kilitler

    fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            hideControls()
            showHud("Ekran kilitlendi")
            hideHudSoon()
            showUnlockButton()
        } else {
            unlockButton.visibility = View.GONE
            showControls()
        }
    }

    fun showUnlockButton() {
        unlockButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 2500)
    }

    fun startKidsLock() {
        kidsLocked = true
        kidsStep = 0
        hideControls()
        showHud("Çocuk kilidi açık\nAçmak için köşelere sırayla dokunun:\nsol üst, sağ üst, sağ alt, sol alt")
        hideHudSoon()
    }

    fun kidsTap(x: Float, y: Float, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (now - kidsLastTap > 6000) kidsStep = 0
        kidsLastTap = now
        val left = x < w * 0.2f
        val right = x > w * 0.8f
        val top = y < h * 0.25f
        val bottom = y > h * 0.75f
        val corner = when {
            left && top -> 0
            right && top -> 1
            right && bottom -> 2
            left && bottom -> 3
            else -> -1
        }
        if (corner == -1) {
            showHud("Çocuk kilidi açık")
            hideHudSoon()
            return
        }
        kidsStep = if (corner == kidsStep) kidsStep + 1 else if (corner == 0) 1 else 0
        if (kidsStep >= 4) {
            kidsLocked = false
            kidsStep = 0
            showHud("Kilit açıldı")
            hideHudSoon()
        }
    }

    // ---------------------------------------------------------------- görüntü dönüşümleri

    fun cycleAspect() {
        val all = AspectMode.entries
        setAspect(all[(aspect.ordinal + 1) % all.size])
        showHud(aspect.label)
        hideHudSoon()
    }

    fun setAspect(mode: AspectMode) {
        aspect = mode
        playerView.resizeMode = mode.resize
        applyTransform()
    }

    fun zoomBy(factor: Float) {
        if (!settings.pinchZoomEnabled) return
        zoom = (zoom * factor).coerceIn(1f, 5f)
        if (zoom <= 1.01f) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
        applyTransform()
        showHud("Yakınlaştırma %${(zoom * 100).roundToInt()}")
    }

    fun panBy(dx: Float, dy: Float) {
        val limitX = playerView.width * (zoom - 1f) / 2f
        val limitY = playerView.height * (zoom - 1f) / 2f
        panX = (panX + dx).coerceIn(-limitX, limitX)
        panY = (panY + dy).coerceIn(-limitY, limitY)
        applyTransform()
    }

    fun toggleMirror() {
        mirror = !mirror
        applyTransform()
        refreshShortcuts()
    }

    fun toggleVerticalFlip() {
        vflip = !vflip
        applyTransform()
        refreshShortcuts()
    }

    fun toggleNight() {
        night = !night
        nightOverlay.visibility = if (night) View.VISIBLE else View.GONE
        refreshShortcuts()
    }

    private fun applyTransform() {
        val surface = playerView.videoSurfaceView ?: return
        val size = player.videoSize
        val videoRatio = if (size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        var sx = 1f
        var sy = 1f
        val target = aspect.ratio
        if (target != null && videoRatio > 0f) {
            if (target > videoRatio) sx = target / videoRatio else sy = videoRatio / target
        }
        surface.scaleX = sx * zoom * (if (mirror) -1f else 1f)
        surface.scaleY = sy * zoom * (if (vflip) -1f else 1f)
        surface.translationX = panX
        surface.translationY = panY
    }

    fun cycleOrientation() {
        val all = OrientationMode.entries
        settings.orientation = all[(settings.orientation.ordinal + 1) % all.size]
        applyOrientation()
        showHud("Ekran yönü: ${settings.orientation.label}")
        hideHudSoon()
    }

    private fun applyOrientation() {
        val size = player.videoSize
        requestedOrientation = when (settings.orientation) {
            OrientationMode.AUTO -> when {
                size.width == 0 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                size.width >= size.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // ---------------------------------------------------------------- altyazı hareketleri

    fun adjustSubtitleSize(factor: Float) {
        settings.subtitleSizeSp = (settings.subtitleSizeSp * factor).coerceIn(10f, 48f)
        subtitles.applyStyle()
        showHud("Altyazı boyutu ${settings.subtitleSizeSp.roundToInt()}")
    }

    fun setSubtitleBottom(fraction: Float) {
        settings.subtitleBottomFraction = fraction.coerceIn(0f, 0.6f)
        subtitles.applyStyle()
    }

    fun jumpSubtitle(next: Boolean) {
        val pos = player.currentPosition
        val target = if (next) subtitles.nextCueMs(pos) else subtitles.previousCueMs(pos)
        if (target == null) toast("Altyazı satırı yok") else player.seekTo(target.coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- kısayollar

    fun refreshAll() {
        refreshShortcuts()
        updatePlayButton()
        updateNavButtons()
        decoderLabel.text = settings.decoder.short
        shortcutScroll.visibility = if (settings.showShortcuts) View.VISIBLE else View.GONE
        subtitles.applyStyle()
    }

    fun refreshShortcuts() {
        if (!::shortcutRow.isInitialized) return
        shortcutScroll.visibility = if (settings.showShortcuts) View.VISIBLE else View.GONE
        shortcutRow.removeAllViews()
        shortcutRow.addView(actionButton(R.drawable.ic_edit, null, "Öğeleri özelleştir", false) { menus.showCustomizeShortcuts() })
        for (s in settings.shortcuts) {
            shortcutRow.addView(buildShortcut(s))
        }
    }

    private fun buildShortcut(s: Shortcut): View {
        var icon = s.icon
        var glyph = s.glyph
        var active = false
        var label = s.label
        var rotation = 0f
        when (s) {
            Shortcut.SPEED -> {
                val sp = player.playbackParameters.speed
                glyph = (if (sp == sp.roundToInt().toFloat()) sp.roundToInt().toString() else "%.2f".format(sp).trimEnd('0')) + "X"
                active = sp != 1f
                label = "Hız"
            }
            Shortcut.AB_REPEAT -> {
                glyph = when {
                    abA >= 0 && abB > abA -> "A-B"
                    abA >= 0 -> "A-"
                    else -> "A-B"
                }
                active = abA >= 0
            }
            Shortcut.REPEAT -> {
                icon = if (player.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
                active = player.repeatMode != Player.REPEAT_MODE_OFF
            }
            Shortcut.SHUFFLE -> active = player.shuffleModeEnabled
            Shortcut.MUTE -> {
                active = muted
                icon = if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            }
            Shortcut.NIGHT -> active = night
            Shortcut.MIRROR -> active = mirror
            Shortcut.VFLIP -> {
                active = vflip
                rotation = 90f
            }
            Shortcut.SLEEP -> active = sleepDeadline > 0 || sleepAtEnd
            Shortcut.BACKGROUND -> active = settings.backgroundPlay
            Shortcut.ROTATE, Shortcut.EQUALIZER, Shortcut.SCREENSHOT -> Unit
        }
        return actionButton(icon, glyph, label, active, rotation) { onShortcut(s) }
    }

    private fun onShortcut(s: Shortcut) {
        scheduleAutoHide()
        when (s) {
            Shortcut.ROTATE -> cycleOrientation()
            Shortcut.BACKGROUND -> {
                settings.backgroundPlay = !settings.backgroundPlay
                toast(if (settings.backgroundPlay) "Arka planda oynatma açık" else "Arka planda oynatma kapalı")
                refreshShortcuts()
            }
            Shortcut.MUTE -> {
                muted = !muted
                player.volume = if (muted) 0f else 1f
                refreshShortcuts()
            }
            Shortcut.EQUALIZER -> menus.showEqualizer()
            Shortcut.AB_REPEAT -> cycleAb()
            Shortcut.SPEED -> menus.showSpeed()
            Shortcut.REPEAT -> {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                showHud(when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Tümünü tekrarla"
                    Player.REPEAT_MODE_ONE -> "Bu videoyu tekrarla"
                    else -> "Tekrar kapalı"
                })
                hideHudSoon()
                refreshShortcuts()
            }
            Shortcut.SHUFFLE -> {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                refreshShortcuts()
            }
            Shortcut.SLEEP -> menus.showSleepTimer()
            Shortcut.NIGHT -> toggleNight()
            Shortcut.SCREENSHOT -> takeScreenshot()
            Shortcut.MIRROR -> toggleMirror()
            Shortcut.VFLIP -> toggleVerticalFlip()
        }
    }

    private fun cycleAb() {
        val pos = player.currentPosition
        when {
            abA < 0 -> {
                abA = pos
                showHud("A noktası: ${formatDuration(pos)}")
            }
            abB < 0 -> {
                if (pos <= abA) {
                    showHud("B noktası A'dan sonra olmalı")
                } else {
                    abB = pos
                    showHud("A-B döngüsü: ${formatDuration(abA)} → ${formatDuration(abB)}")
                }
            }
            else -> {
                abA = -1L
                abB = -1L
                showHud("A-B tekrar kapatıldı")
            }
        }
        hideHudSoon()
        refreshShortcuts()
    }

    // ---------------------------------------------------------------- uyku zamanlayıcısı

    fun setSleepTimer(minutes: Int, atEnd: Boolean) {
        handler.removeCallbacks(sleepRunnable)
        sleepDeadline = 0L
        sleepAtEnd = false
        player.pauseAtEndOfMediaItems = false
        when {
            atEnd -> {
                sleepAtEnd = true
                player.pauseAtEndOfMediaItems = true
                toast("Video bitince duraklatılacak")
            }
            minutes > 0 -> {
                sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
                handler.postDelayed(sleepRunnable, minutes * 60_000L)
                toast("$minutes dakika sonra durdurulacak")
            }
            else -> toast("Uyku zamanlayıcısı kapatıldı")
        }
        refreshShortcuts()
    }

    // ---------------------------------------------------------------- PiP, ekran görüntüsü, paylaşma

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val size = player.videoSize
        val builder = PictureInPictureParams.Builder()
        if (size.width > 0 && size.height > 0) {
            val ratio = (size.width.toFloat() / size.height).coerceIn(0.42f, 2.38f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 24) {
            toast("Bu Android sürümü pencere içinde pencere modunu desteklemiyor")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(pipParams()!!)
            else @Suppress("DEPRECATION") enterPictureInPictureMode()
        }.onFailure { toast("Pencere içinde pencere modu açılamadı") }
    }

    fun takeScreenshot() {
        val surface = playerView.videoSurfaceView
        val size = player.videoSize
        if (surface == null || size.width == 0) {
            toast("Ekran görüntüsü için video oynatılıyor olmalı")
            return
        }
        val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        when {
            surface is TextureView -> {
                surface.getBitmap(bmp)
                saveBitmap(bmp)
            }
            surface is SurfaceView && Build.VERSION.SDK_INT >= 26 -> {
                PixelCopy.request(surface, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) saveBitmap(bmp) else toast("Ekran görüntüsü alınamadı")
                }, handler)
            }
            else -> toast("Bu cihazda ekran görüntüsü desteklenmiyor")
        }
    }

    private fun saveBitmap(bmp: Bitmap) {
        runCatching {
            val name = "OpenPlayer_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenPlayer")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("kayıt açılamadı")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("Ekran görüntüsü kaydedildi (Resimler/OpenPlayer)")
        }.onFailure { toast("Ekran görüntüsü kaydedilemedi") }
    }

    fun shareCurrent() {
        val uri = currentUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Paylaş"))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && settings.backgroundPlay &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Dışarıdan (menülerden) gelen basit mutlak değer kontrolü. */
    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f
}
