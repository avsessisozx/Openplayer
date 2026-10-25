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
import android.os.SystemClock
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
import android.view.ViewGroup
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AspectMode(val label: String, val resize: Int, val ratio: Float?, val inCycle: Boolean = true) {
    FIT(Tx.s(R.string.playeracti_001), AspectRatioFrameLayout.RESIZE_MODE_FIT, null),
    FILL(Tx.s(R.string.playeracti_060), AspectRatioFrameLayout.RESIZE_MODE_FILL, null),
    CROP(Tx.s(R.string.playeracti_002), AspectRatioFrameLayout.RESIZE_MODE_ZOOM, null),
    R16_9("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f),
    R4_3("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f),
    R21_9("21:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 21f / 9f),
    ORIGINAL(Tx.s(R.string.playeracti_003), AspectRatioFrameLayout.RESIZE_MODE_FIT, null, false),
    R1_1("1:1", AspectRatioFrameLayout.RESIZE_MODE_FIT, 1f, false),
    R5_4("5:4", AspectRatioFrameLayout.RESIZE_MODE_FIT, 5f / 4f, false),
    R16_10("16:10", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 10f, false),
    R18_9("18:9 (2:1)", AspectRatioFrameLayout.RESIZE_MODE_FIT, 2f, false),
    R235("2.35:1", AspectRatioFrameLayout.RESIZE_MODE_FIT, 2.35f, false),
    R239("2.39:1", AspectRatioFrameLayout.RESIZE_MODE_FIT, 2.39f, false),
}

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "org.openplayer.app.URIS"
        const val EXTRA_TITLES = "org.openplayer.app.TITLES"
        const val EXTRA_INDEX = "org.openplayer.app.INDEX"
        const val ACTION_PIP = "org.openplayer.app.PIP_ACTION"
    }

    val settings by lazy { AppSettings.get(this) }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSubtitle(uri)
        }

    fun pickSubtitleFile() {
        subtitlePicker.launch(arrayOf("*/*"))
    }

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) attachExternalAudio(uri)
        }

    /** Videoya ayrı bir ses dosyası ekler (örn. farklı dilde dublaj). */
    fun pickExternalAudio() {
        if (currentUri == null) return
        audioPicker.launch(arrayOf("audio/*", "application/octet-stream"))
    }

    private var pendingExternalAudio = false

    private fun attachExternalAudio(audioUri: Uri) {
        val factory = PlayerHolder.mediaSourceFactory
        if (factory == null || player.mediaItemCount == 0) {
            toast(Tx.s(R.string.playeracti_004))
            return
        }
        runCatching {
            val index = player.currentMediaItemIndex
            val pos = player.currentPosition
            val wasPlaying = player.playWhenReady
            val sources = MutableList(player.mediaItemCount) { factory.createMediaSource(player.getMediaItemAt(it)) }
            val audio = factory.createMediaSource(MediaItem.fromUri(audioUri))
            sources[index] = androidx.media3.exoplayer.source.MergingMediaSource(true, true, sources[index], audio)
            pendingExternalAudio = true
            player.setMediaSources(sources, index, pos)
            player.prepare()
            player.playWhenReady = wasPlaying
            toast(Tx.s(R.string.playeracti_005, SubtitleFiles.displayName(this, audioUri)))
        }.onFailure {
            pendingExternalAudio = false
            toast(Tx.s(R.string.playeracti_004))
        }
    }

    /** Eklenen dış ses, ses parçalarının sonuncusu olarak gelir; onu seç. */
    private fun selectExternalAudio(tracks: Tracks) {
        if (!pendingExternalAudio) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        pendingExternalAudio = false
        if (groups.size < 2) return
        val g = groups.last()
        if (!g.isSupported) {
            toast(Tx.s(R.string.playeracti_006))
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
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
                runOnUiThread { toast(Tx.s(R.string.musicactiv_055)) }
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
    private lateinit var controlsInner: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var decoderLabel: TextView
    private lateinit var shortcutScroll: HorizontalScrollView
    private lateinit var shortcutRow: LinearLayout
    private lateinit var seekBar: ChapterSeekBar
    var chapters: List<Chapter> = emptyList()
        private set
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var playButton: ImageView
    private lateinit var prevButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var aspectButton: ImageView
    private lateinit var lockButton: ImageView
    private lateinit var unlockButton: ImageView
    private lateinit var hud: TextView
    private lateinit var clockView: TextView
    private lateinit var osdView: TextView
    private lateinit var rewButton: ImageView
    private lateinit var ffButton: ImageView

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
    private var lastBackPress = 0L
    private var fastPlaying = false
    private var fastPrevSpeed = 1f
    private var fadeAnim: android.animation.ValueAnimator? = null
    private var resumeDialog: androidx.appcompat.app.AlertDialog? = null
    private var batteryText = ""
    private var batteryReadAt = 0L

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
            if (isPlaying) fadeIn()
            updatePipParams()
        }

        override fun onPlaybackStateChanged(state: Int) {
            updatePlayButton()
            if (state == Player.STATE_ENDED) {
                stopFastPlay()
                if (settings.backToList && player.repeatMode == Player.REPEAT_MODE_OFF && !sleepAtEnd) {
                    finish()
                    return
                }
                showControls(sticky = true)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) {
                sleepAtEnd = false
                player.pauseAtEndOfMediaItems = false
                refreshShortcuts()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                onItemChanged()
                // Listede sonraki/önceki videoya geçince de kaldığı yerden devam (MX gibi).
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && !settings.resumeOnlyFirst) {
                    mediaItem?.localConfiguration?.uri?.let { offerResume(it, autoPlay = true) }
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            vrUpdateAspect(videoSize)
            applyOrientation()
            applyTransform()
            updatePipParams()
        }

        override fun onTracksChanged(tracks: Tracks) {
            autoSelectSubtitle(tracks)
            applyPendingAudio(tracks)
            selectExternalAudio(tracks)
            detectVr(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitles.onEmbeddedCues(cueGroup.cues)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioFx.attach(audioSessionId)
            applyAuxEffect()
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
        Toast.makeText(this, Tx.s(R.string.playeracti_007), Toast.LENGTH_LONG).show()
        refreshShortcuts()
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                kidsLocked -> showHud(Tx.s(R.string.playeracti_008))
                locked -> {
                    showUnlockButton()
                    toast(Tx.s(R.string.playeracti_009))
                }
                settings.doubleBackToExit && SystemClock.uptimeMillis() - lastBackPress > 2000 -> {
                    lastBackPress = SystemClock.uptimeMillis()
                    toast(Tx.s(R.string.playeracti_010))
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
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
        ContextCompat.registerReceiver(this, pipReceiver, android.content.IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverRegistered = true
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (settings.rememberBrightness && settings.savedBrightness > 0f) {
            window.attributes = window.attributes.apply { screenBrightness = settings.savedBrightness.coerceIn(0.01f, 1f) }
        }
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

        val loaded = loadFromIntent(intent)
        if (isFinishing) return
        if (!loaded) {
            if (player.mediaItemCount == 0) {
                finish()
                return
            }
            onItemChanged()
        }
        refreshAll()
        if (settings.showControlsAtStart) showControls()
        requestNotificationPermissionIfNeeded()
        if (intent.getBooleanExtra("org.openplayer.app.OPEN_SUBTITLE_SEARCH", false)) {
            handler.postDelayed({ if (!isFinishing) menus.showOnlineSearch() }, 700)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        bindVideo(player)
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
        if (!inPip) bindVideo(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverRegistered = false
        }
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
        if (isInPictureInPictureMode) hideControls() else bindVideo(player)
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
        p.skipSilenceEnabled = settings.skipSilence
        runCatching {
            p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, settings.audioFocus)
            p.setHandleAudioBecomingNoisy(settings.pauseOnNoisy)
        }
        p.addListener(playerListener)
        bindVideo(p)
        audioFx.attach(p.audioSessionId)
        applyAuxEffect()
    }

    /** Yankı (reverb) gibi yardımcı efekti oynatıcıya bağlar. */
    fun applyAuxEffect() {
        if (!::player.isInitialized) return
        val id = audioFx.reverbId
        runCatching { player.setAuxEffectInfo(androidx.media3.common.AuxEffectInfo(id, if (id != 0) 1f else 0f)) }
            .onFailure { android.util.Log.w("OPFEAT", "aux effect failed", it) }
        if (settings.reverbPreset > 0) android.util.Log.i("OPFEAT", "reverb preset=${settings.reverbPreset} id=$id")
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
            if (rawUris.isNotEmpty()) toast(Tx.s(R.string.playeracti_011))
            return false
        }
        openItems(uris, titles, index)
        return true
    }

    /** Özel klasördeki (kasa) bir dosya mı? */
    private fun isVaultUri(uri: Uri?): Boolean =
        uri?.scheme == "file" && uri.path?.let { Vault.isVaultFile(this, File(it)) } == true

    private var vaultSecure = false

    /** Kasadaki video oynarken ekran görüntüsü ve son uygulamalar önizlemesi engellenir. */
    private fun applyVaultSecurity(uri: Uri?) {
        val secure = isVaultUri(uri)
        if (secure == vaultSecure) return
        vaultSecure = secure
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun openItems(uris: List<Uri>, titles: List<String>?, index: Int) {
        resetRay()
        if (settings.playbackEngine == PlaybackEngine.VLC) {
            VlcPlayerActivity.launch(this, uris, titles, index, if (settings.resumePlayback) ResumeStore.position(this, uris[index]) else 0L)
            finish()
            return
        }
        val items = uris.mapIndexed { i, uri ->
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(
                            if (isVaultUri(uri)) Tx.s(R.string.playeracti_012)
                            else titles?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: SubtitleFiles.displayName(this, uri)
                        )
                        .build()
                )
                .build()
        }
        val stored = if (settings.resumeMode != ResumeMode.NO && !settings.incognito) ResumeStore.position(this, uris[index]) else 0L
        val ask = settings.resumeMode == ResumeMode.ASK && stored > 5_000
        val saved = if (ask) 0L else stored
        if (MusicController.isMusic(player.currentMediaItem)) {
            // Müzikten kalan tekrar/karıştırma ayarları videoya taşınmasın.
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = false
        }
        player.volume = if (muted) 0f else 1f
        player.setMediaItems(items, index, if (saved > 5_000) saved else 0L)
        player.prepare()
        player.playWhenReady = !ask
        errorDialogShown = false
        onItemChanged()
        if (ask) askResume(uris[index], stored)
    }

    /**
     * Listede başka videoya geçildiğinde kaldığı yeri uygular (Evet: sormadan, Sor: pencereyle).
     * İlk açılışta openItems zaten konumu verir; bu yalnız geçişlerde çağrılır.
     */
    private fun offerResume(uri: Uri, autoPlay: Boolean) {
        if (settings.resumeMode == ResumeMode.NO || settings.incognito || isVaultUri(uri)) return
        val stored = ResumeStore.position(this, uri)
        if (stored <= 5_000) return
        if (settings.resumeMode == ResumeMode.ASK) {
            player.pause()
            askResume(uri, stored)
        } else {
            player.seekTo(stored)
            if (autoPlay) player.play()
        }
    }

    private fun askResume(uri: Uri, positionMs: Long) {
        resumeDialog?.dismiss()
        android.util.Log.i("OPFEAT", "resume ask at=$positionMs")
        var decided = false
        fun decide(resume: Boolean) {
            if (decided) return
            decided = true
            if (currentUri == uri) {
                if (resume) player.seekTo(positionMs) else player.seekTo(0L)
                player.play()
            }
        }
        resumeDialog = PanelBuilder(this)
            .setTitle(Tx.s(R.string.playeracti_013))
            .setMessage(Tx.s(R.string.playeracti_014, formatDuration(positionMs)))
            .setPositiveButton(Tx.s(R.string.playeracti_015)) { _, _ -> decide(true) }
            .setNegativeButton(Tx.s(R.string.playeracti_016)) { _, _ -> decide(false) }
            .setOnCancelListener { decide(false) }
            .show()
    }

    fun openStream(url: String) {
        val uri = Uri.parse(url.trim())
        if (uri.scheme?.lowercase() !in setOf("http", "https", "rtsp") || uri.host.isNullOrBlank() ||
            !UriGuard.isAllowed(this, uri)
        ) {
            toast(Tx.s(R.string.playeracti_017))
            return
        }
        openItems(listOf(uri), listOf(UriGuard.sanitizeTitle(url.trim().substringAfterLast('/')) ?: Tx.s(R.string.mediainfo_015)), 0)
    }

    /** Geçerli videonun görünüm/hız/altyazı ayarlarını kaydeder. */
    private fun saveMemory() {
        val uri = currentUri ?: return
        if (!settings.perVideoMemory || settings.incognito || !::player.isInitialized || isVaultUri(uri)) return
        val old = VideoMemory.get(this, uri)
        VideoMemory.put(
            this, uri,
            VideoMemory.Entry(
                speed = player.playbackParameters.speed,
                aspect = aspect.name,
                zoom = zoom,
                subDelayMs = subtitles.delayMs,
                subSpeed = subtitles.speed,
                audioIndex = old?.audioIndex ?: -1,
            )
        )
    }

    private var pendingAudioIndex = -1

    private fun restoreMemory(uri: Uri?) {
        pendingAudioIndex = -1
        if (uri == null || !settings.perVideoMemory) return
        val e = VideoMemory.get(this, uri) ?: return
        player.setPlaybackSpeed(e.speed)
        AspectMode.entries.firstOrNull { it.name == e.aspect }?.let { setAspect(it) }
        zoom = e.zoom
        if (zoom <= 1f) { panX = 0f; panY = 0f }
        applyTransform()
        subtitles.delayMs = e.subDelayMs
        subtitles.speed = e.subSpeed
        pendingAudioIndex = e.audioIndex
        refreshShortcuts()
    }

    private fun applyPendingAudio(tracks: Tracks) {
        val idx = pendingAudioIndex
        if (idx < 0) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        pendingAudioIndex = -1
        val g = groups.getOrNull(idx) ?: return
        if (g.isSelected || !g.isSupported) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    private fun onItemChanged() {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri
        if (uri != currentUri) saveMemory()
        if (uri != currentUri) {
            triedDecoders.clear()
            orientationOverride = null
            if (PlayerHolder.decoderOverride != null) {
                PlayerHolder.decoderOverride = null
                handler.post { if (!isDestroyed) PlayerHolder.rebuild(this) }
            }
            vrManual = null
            if (vr != VrMode.NONE) setVr(VrMode.NONE, remember = false)
            if (fastPlaying) fastPlaying = false
            // Her yeni video varsayılan hızla başlar; videoya özel hafıza varsa aşağıda geri yüklenir.
            player.setPlaybackSpeed(settings.playbackSpeed)
        }
        currentUri = uri
        applyVaultSecurity(uri)
        chapters = emptyList()
        seekBar.startsSec = emptyList()
        if (uri != null) {
            Thread {
                val list = Chapters.load(applicationContext, uri)
                runOnUiThread {
                    if (!isDestroyed && currentUri == uri && list.isNotEmpty()) {
                        chapters = list
                        seekBar.startsSec = list.map { (it.startMs / 1000).toInt() }
                    }
                }
            }.start()
        }
        titleView.text = item.mediaMetadata.title ?: uri?.let { SubtitleFiles.displayName(this, it) } ?: ""
        abA = -1L
        abB = -1L
        subtitleAutoDone = false
        subtitles.delayMs = settings.subtitleDefaultDelayMs
        subtitles.speed = 1.0
        subtitles.setOff()
        sidecars = if (uri != null) SubtitleFiles.findSidecars(this, uri) else emptyList()
        val remembered = uri?.let { SubtitleFiles.remembered(this, it) }
        if (settings.autoEnableSubtitle) {
            when {
                remembered != null -> loadExternalSubtitle(remembered, remember = false, quiet = true)
                sidecars.isNotEmpty() -> loadExternalSubtitle(sidecars.first(), remember = false, quiet = true)
            }
        }
        restoreMemory(uri)
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
        val pick = byLang
            ?: groups.firstOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
            // Dil etiketi olmayan tek/ilk parça: otomatik altyazı açıkken onu göster (MX gibi).
            ?: groups.firstOrNull { g -> g.getTrackFormat(0).language.let { it.isNullOrBlank() || it == "und" } }
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
                    if (!quiet) toast(Tx.s(R.string.playeracti_018, file.name))
                } else {
                    subtitles.useExternal(cues, file.name)
                    if (remember) currentUri?.let { SubtitleFiles.remember(this, it, file) }
                    if (!quiet) toast(Tx.s(R.string.playeracti_019, file.name))
                }
            }
        }.start()
    }

    private val triedDecoders = HashSet<DecoderMode>()

    /** Mevcut kuyruğu VLC yedek motoruna devreder ve bu ekranı kapatır. */
    private fun openInVlc(fromError: Boolean): Boolean {
        if (!::player.isInitialized || player.mediaItemCount == 0) return false
        val uris = List(player.mediaItemCount) { player.getMediaItemAt(it).localConfiguration?.uri }.filterNotNull()
        if (uris.isEmpty()) return false
        val titles = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "" }
        val idx = player.currentMediaItemIndex.coerceIn(0, uris.size - 1)
        val pos = player.currentPosition
        player.pause()
        if (fromError) toast(Tx.s(R.string.playeracti_020))
        VlcPlayerActivity.launch(this, uris, titles, idx, pos)
        finish()
        return true
    }

    private fun onPlaybackError(error: PlaybackException) {
        val cur = PlayerHolder.effectiveDecoder(this)
        triedDecoders += cur
        val order = listOf(DecoderMode.HW, DecoderMode.HW_PLUS, DecoderMode.SW)
        val next = order.firstOrNull { it !in triedDecoders }
        if (next != null && settings.autoDecoderFallback) {
            // MX gibi: sormadan sıradaki çözücüyle aynı konumdan yeniden dener.
            decoderLabel.text = next.short
            toast(Tx.s(R.string.playeracti_021, next.label))
            handler.post {
                if (isDestroyed) return@post
                PlayerHolder.decoderOverride = next
                PlayerHolder.rebuild(this)
            }
            return
        }
        if (settings.playbackEngine != PlaybackEngine.EXO && openInVlc(fromError = true)) return
        if (settings.skipOnError && player.hasNextMediaItem()) {
            toast(Tx.s(R.string.playeracti_022))
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
            return
        }
        if (errorDialogShown) return
        errorDialogShown = true
        showMessage(Tx.s(R.string.playeracti_024), Tx.s(R.string.playeracti_023, error.errorCodeName))
    }

    fun changeDecoder(mode: DecoderMode) {
        settings.decoder = mode
        PlayerHolder.rebuild(this)
        toast(Tx.s(R.string.playeracti_025, mode.label))
    }

    // ---------------------------------------------------------------- arayüz oluşturma

    private fun scrimColor(): Int = ((settings.scrimPercent.coerceIn(0, 100) * 255 / 100) shl 24)

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
            clockView = TextView(this@PlayerActivity).apply {
                setTextColor(0xDDFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(6), 0, dp(6), 0)
                visibility = View.GONE
            }
            addView(clockView)
            addView(iconView(R.drawable.ic_music_note).apply { setOnClickListener { menus.showAudioTracks() } })
            addView(iconView(R.drawable.ic_subtitles).apply {
                contentDescription = Tx.s(R.string.mediainfo_014)
                setOnClickListener { menus.showSubtitleMenu() }
            })
            addView(decoderLabel, LinearLayout.LayoutParams(-2, dp(48)))
            addView(iconView(R.drawable.ic_more_vert).apply { setOnClickListener { menus.showOverflow() } })
        }

        shortcutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        shortcutScroll = RayScroll(this).apply {
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
        seekBar = ChapterSeekBar(this).apply {
            val accent = ColorStateList.valueOf(UiColors.accent)
            progressTintList = accent
            thumbTintList = accent
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        elapsedView.text = formatDuration(progress * 1000L)
                        scrubMove(progress * 1000L)
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    scrubbing = true
                    scrubStart()
                    handler.removeCallbacks(hideControlsRunnable)
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    scrubbing = false
                    scrubEnd(s.progress * 1000L)
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
        rewButton = iconView(R.drawable.ic_replay10, 52, 13).apply {
            contentDescription = Tx.s(R.string.playeracti_061)
            setOnClickListener { stepSeek(-1) }
        }
        ffButton = iconView(R.drawable.ic_forward10, 52, 13).apply {
            contentDescription = Tx.s(R.string.playeracti_026)
            setOnClickListener { stepSeek(+1) }
        }
        aspectButton = iconView(R.drawable.ic_aspect).apply { setOnClickListener { cycleAspect() } }
        val pipButton = iconView(R.drawable.ic_pip).apply { setOnClickListener { enterPip() } }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            addView(LinearLayout(context).apply {
                gravity = Gravity.START
                addView(lockButton)
                addView(iconView(R.drawable.ic_tune).apply {
                    setOnClickListener {
                        settings.showShortcuts = !settings.showShortcuts
                        refreshShortcuts()
                        scheduleAutoHide()
                    }
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(prevButton)
            addView(rewButton)
            addView(playButton)
            addView(ffButton)
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(scrimColor(), 0))
        }
        val bottomScrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(scrimColor(), 0))
        }

        controls = FrameLayout(this).apply {
            visibility = View.GONE
            // Karartma gradyanları tam ekran; kenar boşluğu (çentik/sistem çubuğu) yalnız düğmelere uygulanır.
            addView(topScrim, FrameLayout.LayoutParams(-1, dp(220), Gravity.TOP))
            addView(bottomScrim, FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM))
            controlsInner = FrameLayout(this@PlayerActivity).apply {
                addView(topBar, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
                // Genişlik içeriğe göre: simgelerin sağındaki boş alan sürüklemeye/dokunuşa tepki vermez.
                addView(shortcutScroll, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(60) })
                addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
            addView(controlsInner, FrameLayout.LayoutParams(-1, -1))
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
        osdView = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
            addView(osdView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(unlockButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = dp(24) })
        }
        playerView.setOnTouchListener(GestureController(this))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controlsInner.setPadding(i.left, i.top, i.right, i.bottom)
            (osdView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = i.top
                lp.rightMargin = i.right
                osdView.layoutParams = lp
            }
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
        when (settings.tapAction) {
            TapAction.TOGGLE_UI -> if (controlsVisible) hideControls() else showControls()
            TapAction.SHOW_THEN_PAUSE -> if (controlsVisible) togglePlay() else showControls()
            TapAction.BOTH -> {
                togglePlay()
                showControls()
            }
            TapAction.PLAY_PAUSE -> {
                togglePlay()
                showHud(if (player.playWhenReady) "▶" else "❚❚")
                hideHudSoon()
            }
        }
    }

    // ---- Uzun bas: hızlı oynat (MX gibi). Bırakınca önceki hıza döner. ----

    fun startFastPlay(): Boolean {
        if (!settings.longPressSpeed || locked || kidsLocked || fastPlaying) return false
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        fastPrevSpeed = player.playbackParameters.speed
        val fast = settings.longPressSpeedValue
        fastPlaying = true
        player.setPlaybackSpeed(fast)
        if (!player.playWhenReady) player.play()
        val label = if (fast == fast.roundToInt().toFloat()) fast.roundToInt().toString() else "%.2f".format(fast).trimEnd('0').trimEnd(',', '.')
        showHud("${label}x ▶▶")
        android.util.Log.i("OPFEAT", "fast on speed=$fast prev=$fastPrevSpeed")
        return true
    }

    fun stopFastPlay() {
        if (!fastPlaying) return
        fastPlaying = false
        player.setPlaybackSpeed(fastPrevSpeed)
        hideHudSoon()
        android.util.Log.i("OPFEAT", "fast off speed=$fastPrevSpeed")
    }

    /** ±N sn düğmeleri. */
    private fun stepSeek(direction: Int) {
        val sec = settings.seekButtonSec
        seekRelative(direction * sec * 1000L)
        showHud(if (direction > 0) "+$sec s" else "-$sec s")
        hideHudSoon()
        scheduleAutoHide()
    }

    /** Başlarken/sarınca sesi kısa sürede açar (ani ses patlaması olmasın). */
    fun fadeIn() {
        if (!settings.audioFade || muted || !::player.isInitialized) return
        fadeAnim?.cancel()
        player.volume = 0f
        fadeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            addUpdateListener { if (!muted) player.volume = it.animatedValue as Float }
            start()
        }
    }

    private fun cancelFade() {
        fadeAnim?.cancel()
        fadeAnim = null
        if (::player.isInitialized) player.volume = if (muted) 0f else 1f
    }

    fun showControls(sticky: Boolean = false) {
        if (locked || kidsLocked) return
        controlsVisible = true
        controls.visibility = View.VISIBLE
        updateClock()
        updateProgress(player.currentPosition)
        updatePlayButton()
        handler.removeCallbacks(hideControlsRunnable)
        if (!sticky) scheduleAutoHide()
    }

    fun hideControls() {
        controlsVisible = false
        controls.visibility = View.GONE
        handler.removeCallbacks(hideControlsRunnable)
        if (::osdView.isInitialized) updateClock()
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

    // ---- Canlı sarma: kaydırırken görüntü akar (yakın anahtar kare), bırakınca tam konuma gidilir ----
    private var scrubActive = false
    private var scrubWasPlaying = false
    private var lastScrubAt = 0L

    fun scrubStart() {
        if (scrubActive) return
        scrubActive = true
        scrubWasPlaying = player.playWhenReady
        if (settings.liveScrub) {
            player.pause()
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            runCatching { player.isScrubbingModeEnabled = true }
        }
    }

    fun scrubMove(posMs: Long) {
        if (!scrubActive || !settings.liveScrub) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScrubAt < 40) return
        lastScrubAt = now
        player.seekTo(posMs)
    }

    fun scrubEnd(posMs: Long) {
        if (!scrubActive) return
        scrubActive = false
        runCatching { player.isScrubbingModeEnabled = false }
        player.setSeekParameters(if (settings.exactSeekOnRelease) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(posMs)
        if (settings.liveScrub && scrubWasPlaying) player.play()
        if (scrubWasPlaying) fadeIn()
    }

    fun showHud(text: String) {
        handler.removeCallbacks(hideHudRunnable)
        hud.text = text
        hud.visibility = View.VISIBLE
    }

    fun hideHudSoon() {
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, settings.hudHideMs.toLong())
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
        if (player.isPlaying) fadeIn()
    }

    private fun previous() {
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    private fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else toast(Tx.s(R.string.playeracti_027))
    }

    /** Bu oturumdaki hızı değiştirir (varsayılan hız Ayarlar → Oynatıcı'dan seçilir). */
    fun setSpeed(speed: Float) {
        fastPlaying = false
        player.setPlaybackSpeed(speed)
        refreshShortcuts()
    }

    private fun updatePlayButton() {
        playButton.setImageResource(if (player.isPlaying || player.playWhenReady && player.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateNavButtons() {
        prevButton.alpha = 1f
        nextButton.alpha = if (player.hasNextMediaItem()) 1f else 0.4f
        val nav = if (settings.showPrevNext) View.VISIBLE else View.GONE
        prevButton.visibility = nav
        nextButton.visibility = nav
        val seek = if (settings.showSeekButtons) View.VISIBLE else View.GONE
        rewButton.visibility = seek
        ffButton.visibility = seek
    }

    /** Saat ve pil yazısı ("14:05 · %85"). */
    private fun clockText(): String {
        val now = System.currentTimeMillis()
        if (now - batteryReadAt > 30_000) {
            batteryReadAt = now
            batteryText = runCatching {
                val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = Build.VERSION.SDK_INT >= 23 && bm.isCharging
                if (pct in 0..100) "%$pct" + (if (charging) " ⚡" else "") else ""
            }.getOrDefault("")
        }
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(now))
        return if (batteryText.isEmpty()) time else "$time · $batteryText"
    }

    private fun updateClock() {
        if (settings.topBarClock) {
            clockView.text = clockText()
            clockView.visibility = View.VISIBLE
        } else {
            clockView.visibility = View.GONE
        }
        val osd = settings.osdInfo
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (osd == OsdInfo.NONE || controlsVisible || inPip) {
            osdView.visibility = View.GONE
            return
        }
        val parts = ArrayList<String>()
        if (osd == OsdInfo.CLOCK || osd == OsdInfo.BOTH) parts += clockText()
        if (osd == OsdInfo.ELAPSED || osd == OsdInfo.BOTH) parts += formatDuration(player.currentPosition)
        osdView.text = parts.joinToString("  ")
        if (osdView.visibility != View.VISIBLE) android.util.Log.i("OPFEAT", "osd visible: ${osdView.text}")
        osdView.visibility = View.VISIBLE
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
        decoderLabel.text = PlayerHolder.effectiveDecoder(this).short
    }

    private fun tick() {
        val pos = player.currentPosition
        subtitles.tick(pos)
        if (abA >= 0 && abB > abA && pos >= abB) player.seekTo(abA)
        if (controlsVisible) updateProgress(pos)
        if (ticks % 5 == 0) updateClock()
        if (++ticks % 50 == 0 && player.isPlaying) saveResume()
    }

    fun saveResume() {
        val uri = currentUri ?: return
        if (!::player.isInitialized || settings.incognito || isVaultUri(uri)) return
        val duration = player.duration
        val pos = player.currentPosition
        val finished = duration > 0 && pos > duration - 5_000
        saveMemory()
        ResumeStore.save(this, uri, if (finished) 0L else pos)
        if (duration > 0 && pos > duration * 0.9) ResumeStore.markPlayed(this, uri)
    }

    // ---------------------------------------------------------------- kilitler

    fun setLocked(value: Boolean) {
        locked = value
        stopFastPlay()
        if (value && settings.lockRotation) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        if (!value && settings.lockRotation) applyOrientation()
        if (value) {
            hideControls()
            showHud(Tx.s(R.string.playeracti_062))
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
        showHud(Tx.s(R.string.playeracti_028))
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
            showHud(Tx.s(R.string.playeracti_008))
            hideHudSoon()
            return
        }
        kidsStep = if (corner == kidsStep) kidsStep + 1 else if (corner == 0) 1 else 0
        if (kidsStep >= 4) {
            kidsLocked = false
            kidsStep = 0
            showHud(Tx.s(R.string.playeracti_029))
            hideHudSoon()
        }
    }

    // ---------------------------------------------------------------- görüntü dönüşümleri

    fun cycleAspect() {
        val all = AspectMode.entries.filter { it.inCycle }
        val i = all.indexOf(aspect)
        setAspect(all[(i + 1) % all.size])
        showHud(aspect.label)
        hideHudSoon()
    }

    fun setAspect(mode: AspectMode) {
        aspect = mode
        playerView.resizeMode = mode.resize
        applyTransform()
    }

    fun zoomBy(factor: Float) {
        if (vr != VrMode.NONE) return
        if (!settings.pinchZoomEnabled) return
        zoom = (zoom * factor).coerceIn(1f, 5f)
        if (zoom <= 1.01f) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
        applyTransform()
        showHud(Tx.s(R.string.playeracti_030, (zoom * 100).roundToInt()))
    }

    fun snapZoom() {
        if (!settings.quickZoom || zoom <= 1f) return
        val steps = floatArrayOf(1f, 1.25f, 1.5f, 2f, 3f, 5f)
        val target = steps.minByOrNull { kotlin.math.abs(it - zoom) } ?: return
        zoom = target
        if (zoom <= 1f) {
            panX = 0f
            panY = 0f
        }
        panBy(0f, 0f)
        showHud("%${(zoom * 100).toInt()}")
    }

    fun panBy(dx: Float, dy: Float) {
        val limitX = playerView.width * (zoom - 1f) / 2f
        val limitY = playerView.height * (zoom - 1f) / 2f
        panX = (panX + dx).coerceIn(-limitX, limitX)
        panY = (panY + dy).coerceIn(-limitY, limitY)
        applyTransform()
    }

    // ---------------------------------------------------------------- 360°

    var vr: VrMode = VrMode.NONE
        private set
    private var vrManual: VrMode? = null
    private var vrView: Vr360View? = null
    private var vrRoot: FrameLayout? = null

    /** Görüntü yüzeyini bağlar: 360° açıksa GL küreye, değilse normal görünüme. */
    private fun bindVideo(p: ExoPlayer?) {
        val v = vrView
        if (vr != VrMode.NONE && v != null) {
            playerView.player = null
            if (p != null) v.onResume() else v.onPause()
        } else {
            playerView.player = p
        }
    }

    private fun detectVr(tracks: Tracks) {
        val manual = vrManual
        if (manual != null) return
        var format: Format? = null
        for (g in tracks.groups) {
            if (g.type == C.TRACK_TYPE_VIDEO && g.length > 0) {
                val idx = (0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0
                format = g.getTrackFormat(idx)
                break
            }
        }
        val name = titleView.text?.toString().orEmpty()
        var detected = Vr360.detect(format, name)
        if (detected == VrMode.NONE) detected = currentUri?.let { Vr360.saved(this, it.toString()) } ?: VrMode.NONE
        if (detected != vr) setVr(detected, remember = true)
    }

    /** Elle veya otomatik 360° biçimini ayarlar. NONE normal oynatmaya döner. */
    fun setVr(mode: VrMode, remember: Boolean = true, manual: Boolean = false) {
        if (manual) vrManual = mode
        val old = vr
        vr = mode
        if (remember) currentUri?.let { Vr360.remember(this, it.toString(), mode) }
        if (mode == VrMode.NONE) {
            if (vrOrientationLocked) {
                vrOrientationLocked = false
                applyOrientation()
            }
            vrView?.let { v ->
                v.onPause()
                (v.parent as? ViewGroup)?.removeView(v)
            }
            vrView = null
            if (old != VrMode.NONE && ::player.isInitialized) {
                player.clearVideoSurface()
                playerView.visibility = View.VISIBLE
                playerView.player = player
            }
            zoom = 1f
            panX = 0f
            panY = 0f
        } else {
            var v = vrView
            if (v == null) {
                v = Vr360View(this) { sf ->
                    handler.post {
                        if (sf != null && vr != VrMode.NONE && ::player.isInitialized) player.setVideoSurface(sf)
                        else if (sf == null && ::player.isInitialized) player.clearVideoSurface()
                    }
                }
                v.setOnTouchListener(GestureController(this))
                v.horizonLevel = settings.vrHorizonLevel
                v.setSensorAlt(settings.vrSensorAlt)
                v.setAutoRotate(settings.vrAutoRotate)
                vrView = v
                vrUpdateAspect(player.videoSize)
                playerView.player = null
                playerView.visibility = View.GONE
                val parent = playerView.parent as ViewGroup
                parent.addView(v, parent.indexOfChild(playerView) + 1, FrameLayout.LayoutParams(-1, -1))
                v.onResume()
            }
            v.mode = mode
            if (old == VrMode.NONE) {
                v.resetLook()
                showHud(Tx.s(R.string.playeracti_031))
                hideHudSoon()
            }
        }
        refreshShortcuts()
    }

    fun vrLook(dx: Float, dy: Float) {
        vrView?.look(dx, dy)
    }

    fun vrZoom(factor: Float) {
        vrView?.zoomFov(factor)
    }

    fun vrSensorEnabled(): Boolean = vrView?.sensorEnabled == true

    private var vrOrientationLocked = false

    fun vrUpdateAspect(size: VideoSize) {
        val v = vrView ?: return
        if (size.width > 0 && size.height > 0) v.videoAspect = size.width * size.pixelWidthHeightRatio / size.height
    }

    fun vrSetSensor(on: Boolean) {
        vrView?.setSensor(on)
        val actual = vrView?.sensorEnabled == true
        if (on && !actual) toast(Tx.s(R.string.playeracti_032))
        // Sensör açıkken ekran kendiliğinden dönüp görüntüyü bozmasın.
        if (actual && settings.vrLockRotation && !vrOrientationLocked) {
            vrOrientationLocked = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else if (!actual && vrOrientationLocked) {
            vrOrientationLocked = false
            applyOrientation()
        }
    }

    /** Görüntüyü iki parmakla kendi ekseni etrafında döndürür (derece farkı). */
    fun vrRollBy(deltaDeg: Float) {
        val v = vrView ?: return
        v.roll = (v.roll + deltaDeg) % 360f
    }

    /** Ufku düzelt: elle verilen eğimi sıfırlar. */
    fun vrLevelHorizon() {
        vrView?.roll = 0f
        showHud(Tx.s(R.string.playeracti_033))
        hideHudSoon()
    }

    // --- 360° basılı tutunca çember (sar / oynat / duraklat)
    private var vrWheel: VrWheelView? = null
    private var wheelDir = VrWheelView.Dir.NONE
    private val wheelSeeker = object : Runnable {
        override fun run() {
            when (wheelDir) {
                VrWheelView.Dir.LEFT -> seekRelative(-5000L)
                VrWheelView.Dir.RIGHT -> seekRelative(5000L)
                else -> return
            }
            handler.postDelayed(this, 350)
        }
    }

    fun vrWheelActive(): Boolean = vrWheel != null

    fun vrWheelStart(x: Float, y: Float) {
        if (vrWheel != null || vr == VrMode.NONE) return
        val w = VrWheelView(this)
        w.setCenter(x, y)
        vrWheel = w
        (playerView.parent as ViewGroup).addView(w, FrameLayout.LayoutParams(-1, -1))
    }

    fun vrWheelMove(x: Float, y: Float) {
        val w = vrWheel ?: return
        val d = w.move(x, y)
        if (d == wheelDir) return
        wheelDir = d
        handler.removeCallbacks(wheelSeeker)
        when (d) {
            VrWheelView.Dir.LEFT, VrWheelView.Dir.RIGHT -> handler.post(wheelSeeker)
            VrWheelView.Dir.UP -> player.play()
            VrWheelView.Dir.DOWN -> player.pause()
            else -> Unit
        }
    }

    fun vrWheelEnd() {
        val w = vrWheel ?: return
        handler.removeCallbacks(wheelSeeker)
        wheelDir = VrWheelView.Dir.NONE
        (w.parent as? ViewGroup)?.removeView(w)
        vrWheel = null
    }

    fun vrTouching(on: Boolean) {
        vrView?.touching = on
    }

    fun vrAutoRotating(): Boolean = vrView?.autoRotating == true

    fun vrSetAutoRotate(on: Boolean) {
        vrView?.setAutoRotate(on)
    }

    fun vrSetHorizonLevel(on: Boolean) {
        settings.vrHorizonLevel = on
        vrView?.horizonLevel = on
    }

    fun vrReset() {
        vrView?.resetLook()
    }

    fun vrCardboardEnabled(): Boolean = vrView?.cardboard == true

    /** VR gözlük (Cardboard) modu: ekranı ikiye bölüp her göze lens distorsiyonuyla çizer. */
    fun vrSetCardboard(on: Boolean) {
        val v = vrView ?: return
        v.cardboard = on
        if (on) {
            // Gözlük modu baş takibi ister: sensörü aç ve bakışı sıfırla.
            vrSetSensor(true)
            v.resetLook()
            showHud(Tx.s(R.string.playeracti_034))
            hideHudSoon()
        }
    }

    fun toggleMirror() {
        if (vr != VrMode.NONE) return
        mirror = !mirror
        applyTransform()
        refreshShortcuts()
    }

    fun toggleVerticalFlip() {
        if (vr != VrMode.NONE) return
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
        if (aspect == AspectMode.ORIGINAL && size.width > 0 && size.height > 0 && playerView.width > 0 && playerView.height > 0) {
            // Görüntü ekrana sığdırılmış hâlinden, videonun kendi piksel boyutuna ölçeklenir.
            val vw = size.width * size.pixelWidthHeightRatio
            val fit = minOf(playerView.width / vw, playerView.height / size.height.toFloat())
            if (fit > 0f) {
                sx = 1f / fit
                sy = 1f / fit
            }
        }
        surface.scaleX = sx * zoom * (if (mirror) -1f else 1f)
        surface.scaleY = sy * zoom * (if (vflip) -1f else 1f)
        surface.translationX = panX
        surface.translationY = panY
    }

    /** Döndür düğmesi: o an dikeyse yataya, yataysa dikeye geçer (ayardaki varsayılan mod değişmez). */
    private var orientationOverride: Int? = null

    fun cycleOrientation() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val target = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        orientationOverride = target
        requestedOrientation = target
        showHud(if (landscape) Tx.s(R.string.playeracti_064) else Tx.s(R.string.playeracti_063))
        hideHudSoon()
    }

    private fun applyOrientation() {
        if (vrOrientationLocked) return
        orientationOverride?.let {
            requestedOrientation = it
            return
        }
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
        showHud(Tx.s(R.string.gesturecon_014, settings.subtitleSizeSp.roundToInt()))
    }

    fun setSubtitleBottom(fraction: Float) {
        settings.subtitleBottomFraction = fraction.coerceIn(0f, 0.6f)
        subtitles.applyStyle()
    }

    fun jumpSubtitle(next: Boolean) {
        val pos = player.currentPosition
        val target = if (next) subtitles.nextCueMs(pos) else subtitles.previousCueMs(pos)
        if (target == null) toast(Tx.s(R.string.playeracti_035)) else player.seekTo(target.coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- kısayollar

    fun refreshAll() {
        refreshShortcuts()
        updatePlayButton()
        updateNavButtons()
        decoderLabel.text = PlayerHolder.effectiveDecoder(this).short
        shortcutScroll.visibility = if (settings.showShortcuts) View.VISIBLE else View.GONE
        subtitles.applyStyle()
    }

    fun refreshShortcuts() {
        if (!::shortcutRow.isInitialized) return
        shortcutScroll.visibility = if (settings.showShortcuts) View.VISIBLE else View.GONE
        shortcutRow.removeAllViews()
        val all = settings.shortcuts
        // Varsayılan olarak yalnızca ilk 4 simge görünür; kalanlar parmakla çekilince veya ok ile açılır.
        val head = all.take(SHORTCUT_DEFAULT_COUNT)
        val rest = all.drop(SHORTCUT_DEFAULT_COUNT)
        for (s in head) shortcutRow.addView(buildShortcut(s))
        extraRow = null; extraInner = null; arrowView = null
        if (rest.isNotEmpty()) {
            val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (s in rest) inner.addView(buildShortcut(s))
            inner.addView(actionButton(
                R.drawable.ic_edit, null, Tx.s(R.string.playeracti_036), false,
                scale = settings.shortcutSizePercent / 100f,
                bgOpacity = settings.shortcutOpacityPercent / 100f,
                showLabel = false,
            ) { menus.showCustomizeShortcuts() })
            val frame = FrameLayout(this).apply {
                clipChildren = true
                addView(inner, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            }
            extraInner = inner
            extraRow = frame
            shortcutRow.addView(frame, LinearLayout.LayoutParams(0, -2))
            val arrow = iconView(R.drawable.ic_back, 48, 12).apply {
                setOnClickListener {
                    settleRay(if (rayFraction > 0.5f) 0f else 1f)
                    scheduleAutoHide()
                }
            }
            arrowView = arrow
            shortcutRow.addView(arrow)
            applyRay(rayFraction)
        }
    }

    // --- Kısayol rayı: parmakla çekince yumuşakça açılır/kapanır (0 = varsayılan 5 simge, 1 = tümü).
    // Oynatıcıdan çıkınca sıfırlanır (Activity alanı).
    private val SHORTCUT_DEFAULT_COUNT = 4
    private var rayFraction = 0f
    private var rayAnim: android.animation.ValueAnimator? = null
    private var extraRow: FrameLayout? = null
    private var extraInner: LinearLayout? = null
    private var arrowView: View? = null

    private fun rayFull(): Int {
        val inner = extraInner ?: return 0
        inner.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return inner.measuredWidth
    }

    private fun applyRay(f: Float) {
        rayFraction = f.coerceIn(0f, 1f)
        val row = extraRow ?: return
        val lp = row.layoutParams
        lp.width = (rayFull() * rayFraction).toInt()
        row.layoutParams = lp
        row.alpha = 0.25f + 0.75f * rayFraction
        arrowView?.rotation = 180f * (1f - rayFraction)
    }

    /** Satırı varsayılan (kapalı) hâle getirir; yeni videoya geçerken ve oynatıcıdan çıkarken kullanılır. */
    private fun resetRay() {
        rayAnim?.cancel()
        applyRay(0f)
        if (::shortcutScroll.isInitialized) shortcutScroll.scrollTo(0, 0)
    }

    private fun settleRay(target: Float) {
        rayAnim?.cancel()
        rayAnim = android.animation.ValueAnimator.ofFloat(rayFraction, target).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { applyRay(it.animatedValue as Float) }
            start()
        }
    }

    private inner class RayScroll(ctx: android.content.Context) : HorizontalScrollView(ctx) {
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var prevX = 0f
        private var startF = 0f
        private var lastDelta = 0f
        private var ray = false

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; prevX = ev.x; lastDelta = 0f
                    ray = false
                    rayAnim?.cancel()
                    return super.onInterceptTouchEvent(ev)
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!ray) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 0.6f && rayFull() > 0 &&
                        ((dx < 0 && rayFraction < 1f) || (dx > 0 && rayFraction > 0f && scrollX == 0))
                    ) {
                        ray = true
                        startF = rayFraction
                        // Parmağı birebir izle: eşik (slop) kadar mesafe kayıp sayılır, ilk karede sıçrama olmaz.
                        downX = downX + (if (dx < 0) -slop else slop).toFloat()
                        prevX = ev.x
                        applyRay(startF - (ev.x - downX) / rayFull().coerceAtLeast(1))
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return if (ray) true else super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!ray) return super.onTouchEvent(ev)
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    val full = rayFull().coerceAtLeast(1)
                    applyRay(startF - (ev.x - downX) / full)
                    lastDelta = ev.x - prevX
                    prevX = ev.x
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    ray = false
                    val target = when {
                        lastDelta < -2f -> 1f
                        lastDelta > 2f -> 0f
                        rayFraction > 0.5f -> 1f
                        else -> 0f
                    }
                    settleRay(target)
                    scheduleAutoHide()
                }
            }
            return true
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
                label = Tx.s(R.string.playeracti_037)
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
            Shortcut.ROTATE, Shortcut.EQUALIZER, Shortcut.SCREENSHOT, Shortcut.SKIP_INTRO -> Unit
        }
        return actionButton(
            icon, glyph, label, active, rotation,
            scale = settings.shortcutSizePercent / 100f,
            bgOpacity = settings.shortcutOpacityPercent / 100f,
            showLabel = false,
        ) { onShortcut(s) }
    }

    private fun onShortcut(s: Shortcut) {
        scheduleAutoHide()
        when (s) {
            Shortcut.ROTATE -> cycleOrientation()
            Shortcut.SKIP_INTRO -> {
                val target = player.currentPosition + settings.skipIntroSec * 1000L
                player.seekTo(if (player.duration > 0) minOf(target, player.duration - 1000L) else target)
                toast("+${settings.skipIntroSec} s")
            }
            Shortcut.BACKGROUND -> {
                settings.backgroundPlay = !settings.backgroundPlay
                toast(if (settings.backgroundPlay) Tx.s(R.string.playeracti_039) else Tx.s(R.string.playeracti_038))
                refreshShortcuts()
            }
            Shortcut.MUTE -> {
                muted = !muted
                cancelFade()
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
                    Player.REPEAT_MODE_ALL -> Tx.s(R.string.playeracti_040)
                    Player.REPEAT_MODE_ONE -> "Bu videoyu tekrarla"
                    else -> Tx.s(R.string.playeracti_041)
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
                showHud(Tx.s(R.string.playeracti_042, formatDuration(pos)))
            }
            abB < 0 -> {
                if (pos <= abA) {
                    showHud(Tx.s(R.string.playeracti_043))
                } else {
                    abB = pos
                    showHud(Tx.s(R.string.playeracti_044, formatDuration(abA), formatDuration(abB)))
                }
            }
            else -> {
                abA = -1L
                abB = -1L
                showHud(Tx.s(R.string.playeracti_045))
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
                toast(Tx.s(R.string.playeracti_046))
            }
            minutes > 0 -> {
                sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
                handler.postDelayed(sleepRunnable, minutes * 60_000L)
                toast("$minutes dakika sonra durdurulacak")
            }
            else -> toast(Tx.s(R.string.playeracti_047))
        }
        refreshShortcuts()
    }

    // ---------------------------------------------------------------- PiP, ekran görüntüsü, paylaşma

    private var pipReceiverRegistered = false

    /** PiP penceresindeki düğmeler (önceki/oynat/sonraki ya da ±10 sn). */
    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (!::player.isInitialized) return
            val seek = settings.pipSeekButtons
            when (intent.getIntExtra("pip_action", 0)) {
                1 -> if (seek) seekRelative(-10_000) else previous()
                2 -> if (player.isPlaying) player.pause() else player.play()
                3 -> if (seek) seekRelative(10_000) else if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
        }
    }

    private fun pipActions(): List<android.app.RemoteAction> {
        fun action(code: Int, icon: Int, title: String): android.app.RemoteAction {
            val pending = android.app.PendingIntent.getBroadcast(
                this, code,
                Intent(ACTION_PIP).setPackage(packageName).putExtra("pip_action", code),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            return android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), title, title, pending)
        }
        val seek = settings.pipSeekButtons
        val playing = player.isPlaying
        return listOf(
            action(1, if (seek) R.drawable.ic_replay10 else R.drawable.ic_prev, if (seek) "10 sn geri" else Tx.s(R.string.playeracti_048)),
            action(2, if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) Tx.s(R.string.playeracti_066) else Tx.s(R.string.playeracti_065)),
            action(3, if (seek) R.drawable.ic_forward10 else R.drawable.ic_next, if (seek) "10 sn ileri" else Tx.s(R.string.playeracti_067)),
        )
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val size = player.videoSize
        val builder = PictureInPictureParams.Builder()
        runCatching { builder.setActions(pipActions()) }
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
            toast(Tx.s(R.string.playeracti_049))
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(pipParams()!!)
            else @Suppress("DEPRECATION") enterPictureInPictureMode()
        }.onFailure { toast(Tx.s(R.string.playeracti_050)) }
    }

    fun takeScreenshot() {
        val surface = playerView.videoSurfaceView
        val size = player.videoSize
        if (surface == null || size.width == 0) {
            toast(Tx.s(R.string.playeracti_051))
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
                    if (result == PixelCopy.SUCCESS) saveBitmap(bmp) else toast(Tx.s(R.string.playeracti_052))
                }, handler)
            }
            else -> toast(Tx.s(R.string.playeracti_053))
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
                ?: error(Tx.s(R.string.playeracti_054))
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast(Tx.s(R.string.playeracti_055))
        }.onFailure { toast(Tx.s(R.string.playeracti_056)) }
    }

    fun shareCurrent() {
        val uri = currentUri ?: return
        if (isVaultUri(uri)) {
            toast(Tx.s(R.string.playeracti_057))
            return
        }
        // file:// adresi başka uygulamaya verilemez (Android 7+ uygulamayı çökertir); MediaStore adresi kullanılır.
        val shareUri = when (uri.scheme) {
            "content" -> uri
            "file" -> uri.path?.let { mediaStoreUri(it) }
            else -> null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            if (shareUri != null) {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "rtsp") {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uri.toString())
            } else {
                toast(Tx.s(R.string.playeracti_058))
                return
            }
        }
        runCatching { startActivity(Intent.createChooser(send, Tx.s(R.string.mainactivi_045))) }
            .onFailure { toast(Tx.s(R.string.playeracti_059)) }
    }

    /** Dosya yolunu galerideki (MediaStore) içerik adresine çevirir; bulunamazsa null. */
    private fun mediaStoreUri(path: String): Uri? {
        val files = MediaStore.Files.getContentUri("external")
        // /sdcard/... ile /storage/emulated/0/... aynı dosyadır; MediaStore ikincisini tutar.
        val candidates = listOfNotNull(path, runCatching { File(path).canonicalPath }.getOrNull()).distinct()
        for (p in candidates) {
            val found = runCatching {
                @Suppress("DEPRECATION")
                contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(p), null)
                    ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(files, c.getLong(0)) else null }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && settings.backgroundPlay &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    fun openSettings(page: String? = null) {
        val i = Intent(this, SettingsActivity::class.java)
        if (page != null) i.putExtra(SettingsActivity.EXTRA_PAGE, page)
        startActivity(i)
    }

    /** Dışarıdan (menülerden) gelen basit mutlak değer kontrolü. */
    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f
}
