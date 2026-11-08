package org.openplayer.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/** mpv'ye verilecek yerel adres. SMB/FTP/WebDAV önce [NetworkPlayback] ile vekile çevrilir. */
object MpvUris {
    /** `content://` için mpv'nin kapatacağı bir dosya tanıtıcısı açar; açılamazsa null. */
    fun resolve(context: Context, uri: String): String? = target(uri) { u ->
        runCatching { context.contentResolver.openFileDescriptor(Uri.parse(u), "r")?.detachFd() }.getOrNull()
    }

    /** mpv'nin açabileceği adres; [openFd] `content://` için tanıtıcı açar. Saf işlev (birim testli). */
    internal fun target(uri: String, openFd: (String) -> Int?): String? = when {
        uri.startsWith("content://") -> openFd(uri)?.let { "fdclose://$it" }
        uri.startsWith("file://") -> runCatching { java.net.URI(uri).path }.getOrNull() ?: uri.removePrefix("file://")
        else -> uri
    }
}

/** mpv JSON çıktılarının ayrıştırılması (saf işlevler, birim testli). */
object MpvJson {
    fun tracks(json: String?): List<MpvTrack> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::track) }
    }

    private fun track(o: JSONObject): MpvTrack? {
        val type = when (o.optString("type")) {
            "video" -> MpvTrackType.VIDEO
            "audio" -> MpvTrackType.AUDIO
            "sub" -> MpvTrackType.SUB
            else -> return null
        }
        return MpvTrack(
            id = o.optInt("id", -1).takeIf { it >= 0 } ?: return null,
            type = type,
            title = o.optString("title").ifBlank { null },
            language = o.optString("lang").ifBlank { null },
            codec = o.optString("codec").ifBlank { null },
            selected = o.optBoolean("selected"),
            isDefault = o.optBoolean("default"),
            isForced = o.optBoolean("forced"),
            isExternal = o.optBoolean("external"),
            width = o.optInt("demux-w"),
            height = o.optInt("demux-h"),
            channels = o.optInt("demux-channel-count"),
            sampleRate = o.optInt("demux-samplerate"),
        )
    }

    fun endReason(reason: String?): EndReason = when (reason) {
        "eof" -> EndReason.EOF
        "quit" -> EndReason.QUIT
        "error" -> EndReason.ERROR
        "redirect" -> EndReason.REDIRECT
        else -> EndReason.STOP
    }
}

/**
 * [MpvEngine]'in libmpv gerçeklemesi. Sınıf açıklamasındaki kilitlenme kuralları burada uygulanır:
 * çözücü canlıyken VO veya `hwdec` değişmez; önce `vid=no` ile video kapatılır, iş bitince açılır.
 * Yüzey yokken video hep kapalıdır (`vid=no`): böylece yüzeysiz çözücü kurulmaz, dosya yüklenirken
 * gelen yüzey de canlı bir çözücüye denk gelmez ve doğrudan donanım çıkışı kullanılabilir.
 */
class MpvPlayer(context: Context) : MpvEngine, MPV.EventObserver, MPV.LogObserver {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mpv = MPV()

    private val flow = MutableSharedFlow<MpvEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<MpvEvent> = flow.asSharedFlow()
    private val listeners = CopyOnWriteArrayList<(MpvEvent) -> Unit>()

    private class Watch(val property: String, val callback: (String?) -> Unit)
    private val watches = CopyOnWriteArrayList<Watch>()
    private val observed = HashSet<String>()

    @Volatile private var released = false
    @Volatile private var fileOpen = false
    @Volatile private var paused = false
    @Volatile private var coreIdle = true
    @Volatile private var buffering = false
    @Volatile private var eof = false
    @Volatile private var positionSec = 0.0
    @Volatile private var durationSec = 0.0
    @Volatile private var speedValue = 1f
    @Volatile private var lastPlaying = false
    @Volatile private var lastPositionPost = 0L
    /** Hata ayıklama raporu için önbellek ilerlemesi (saniye). */
    @Volatile var bufferedSec = 0.0
        private set

    private var surface: Surface? = null
    private var wantedHwdec = HWDEC_DEFAULT
    private var videoFilter = ""
    private var openUri: String? = null
    @Volatile private var pendingSeek: Pair<Long, Boolean>? = null

    init {
        runCatching { Utils.copyAssets(app) }.onFailure { Log.w(TAG, "assets", it) }
        val dir = app.filesDir.path
        val cache = app.cacheDir.path
        mpv.create(app)
        val options = mapOf(
            "config" to "yes",
            "config-dir" to dir,
            "gpu-shader-cache-dir" to cache,
            "icc-cache-dir" to cache,
            "msg-level" to "all=warn",
            "idle" to "yes",
            "keep-open" to "always",
            "force-window" to "no",
            "vo" to "null",
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "ao" to "audiotrack,opensles",
            "hwdec" to HWDEC_DEFAULT,
            "hwdec-codecs" to "all",
            "tls-verify" to "yes",
            "tls-ca-file" to "$dir/cacert.pem",
            "volume-max" to "200",
            "vid" to "no",
            "sid" to "no",
            "sub-auto" to "no",
            "audio-file-auto" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "ytdl" to "no",
            "load-scripts" to "no",
            "demuxer-max-bytes" to "67108864",
            "demuxer-max-back-bytes" to "33554432",
        )
        options.forEach { (k, v) ->
            val rc = mpv.setOptionString(k, v)
            if (rc < 0) Log.w(TAG, "option $k=$v -> $rc")
        }
        mpv.addObserver(this)
        mpv.addLogObserver(this)
        mpv.init()
        observeInternal("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        observeInternal("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        observeInternal("demuxer-cache-time", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        observeInternal("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
        observeInternal("core-idle", MPV.mpvFormat.MPV_FORMAT_FLAG)
        observeInternal("paused-for-cache", MPV.mpvFormat.MPV_FORMAT_FLAG)
        observeInternal("eof-reached", MPV.mpvFormat.MPV_FORMAT_FLAG)
        observeInternal("track-list", MPV.mpvFormat.MPV_FORMAT_NONE)
        // `video-params` her karede tetiklenir; boyut için yalnız boyut değişince gelenler izlenir.
        observeInternal("dwidth", MPV.mpvFormat.MPV_FORMAT_NONE)
        observeInternal("dheight", MPV.mpvFormat.MPV_FORMAT_NONE)
    }

    private fun observeInternal(name: String, format: Int) {
        observed += name
        mpv.observeProperty(name, format)
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        if (released) return
        runCatching(block).onFailure { Log.w(TAG, "$what: ${it.message}") }
    }

    // ---------------------------------------------------------------- durum

    override val isReleased: Boolean get() = released
    override val isPlaying: Boolean get() = fileOpen && !paused && !coreIdle && !eof
    override val playWhenReady: Boolean get() = fileOpen && !paused
    override val positionMs: Long get() = (positionSec * 1000).roundToLong().coerceAtLeast(0L)
    override val durationMs: Long get() = (durationSec * 1000).roundToLong().coerceAtLeast(0L)
    override val speed: Float get() = speedValue

    override fun addListener(listener: (MpvEvent) -> Unit) { listeners += listener }
    override fun removeListener(listener: (MpvEvent) -> Unit) { listeners -= listener }

    private fun post(event: MpvEvent) {
        main.post {
            flow.tryEmit(event)
            listeners.forEach { it(event) }
        }
    }

    // ---------------------------------------------------------------- oynatma

    override fun open(uri: String, startMs: Long, paused: Boolean) = guarded("open") {
        val target = MpvUris.resolve(app, uri)
        if (target == null) {
            post(MpvEvent.Error("cannot open: ${Uri.parse(uri).scheme}"))
            return@guarded
        }
        openUri = uri
        pendingSeek = null
        eof = false
        positionSec = startMs / 1000.0
        durationSec = 0.0
        mpv.setPropertyBoolean("pause", paused)
        if (startMs > 0) {
            mpv.command("loadfile", target, "replace", "-1", "start=${seconds(startMs)}")
        } else {
            mpv.command("loadfile", target, "replace")
        }
    }

    override fun stop() = guarded("stop") { mpv.command("stop") }
    override fun play() = guarded("play") { mpv.setPropertyBoolean("pause", false) }
    override fun pause() = guarded("pause") { mpv.setPropertyBoolean("pause", true) }

    override fun seekTo(positionMs: Long, exact: Boolean) = guarded("seek") {
        eof = false
        positionSec = positionMs / 1000.0
        if (!fileOpen) {
            // Dosya yüklenirken gelen sarma (kaldığı yerden devam) açılınca uygulanır.
            pendingSeek = positionMs to exact
            return@guarded
        }
        mpv.command("seek", seconds(positionMs), if (exact) "absolute+exact" else "absolute+keyframes")
    }

    override fun setSpeed(speed: Float) = guarded("speed") {
        speedValue = speed
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    override fun setVolume(percent: Int) = guarded("volume") {
        mpv.setPropertyInt("volume", percent.coerceIn(0, 200))
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

    override fun command(vararg args: String) = guarded("command ${args.firstOrNull()}") { mpv.command(*args) }

    override fun setProperty(name: String, value: String) = guarded("set $name") {
        mpv.setPropertyString(name, value)
    }

    override fun getProperty(name: String): String? {
        if (released) return null
        return runCatching { mpv.getPropertyString(name) }.getOrNull()
    }

    override fun getPropertyJson(name: String): String? {
        if (released) return null
        return runCatching { mpv.getPropertyNode(name)?.toJson() }.getOrNull()
    }

    override fun observe(property: String, callback: (String?) -> Unit): AutoCloseable {
        val watch = Watch(property, callback)
        watches += watch
        guarded("observe $property") {
            // Motorun kendi izlediği özellikler başka biçimde gelir; metin biçimi ayrıca istenir.
            if (observedText.add(property)) mpv.observeProperty(property, MPV.mpvFormat.MPV_FORMAT_STRING)
        }
        val now = getProperty(property)
        main.post { if (watch in watches) callback(now) }
        return AutoCloseable { watches -= watch }
    }

    private val observedText = HashSet<String>()

    override fun tracks(): List<MpvTrack> = MpvJson.tracks(getPropertyJson("track-list"))

    // ---------------------------------------------------------------- görüntü

    @Synchronized
    override fun attachSurface(surface: Surface, width: Int, height: Int) = guarded("attach") {
        if (this.surface === surface) {
            setSurfaceSize(width, height)
            return@guarded
        }
        mpv.setPropertyString("vid", "no")
        if (this.surface != null) dropSurface()
        mpv.attachSurface(surface)
        this.surface = surface
        setSurfaceSize(width, height)
        mpv.setPropertyString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        mpv.setPropertyString("vid", "auto")
    }

    private fun setSurfaceSize(width: Int, height: Int) {
        if (width > 0 && height > 0) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    private fun dropSurface() {
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
        surface = null
    }

    @Synchronized
    override fun detachSurface() = guarded("detach") {
        if (surface == null) return@guarded
        mpv.setPropertyString("vid", "no")
        dropSurface()
    }

    @Synchronized
    override fun detachSurface(surface: Surface) {
        if (this.surface === surface) detachSurface()
    }

    @Synchronized
    override fun setHardwareDecoding(value: String) = guarded("hwdec") {
        wantedHwdec = value
        applyDecoding()
    }

    @Synchronized
    override fun setVideoFilter(filter: String) = guarded("vf") {
        videoFilter = filter
        applyDecoding()
    }

    /** Yazılım filtresi varken doğrudan donanım karesi kullanılamaz: kopyalı kipe geçilir. */
    private fun applyDecoding() {
        val hwdec = effectiveHwdec(wantedHwdec, videoFilter)
        if (hwdec == appliedHwdec) {
            mpv.setPropertyString("vf", videoFilter)
            return
        }
        val videoOn = surface != null
        if (videoOn) mpv.setPropertyString("vid", "no")
        mpv.setPropertyString("hwdec", hwdec)
        appliedHwdec = hwdec
        mpv.setPropertyString("vf", videoFilter)
        if (videoOn) mpv.setPropertyString("vid", "auto")
    }

    private var appliedHwdec = HWDEC_DEFAULT

    override fun release() {
        synchronized(this) {
            if (released) return
            runCatching {
                mpv.setPropertyString("vid", "no")
                if (surface != null) dropSurface()
            }
            released = true
        }
        listeners.clear()
        watches.clear()
        mpv.removeObserver(this)
        mpv.removeLogObserver(this)
        // Kapatma beklenebilir; ana iş parçacığını tutmasın.
        Thread({ runCatching { mpv.destroy() }.onFailure { Log.w(TAG, "destroy", it) } }, "mpv-release").start()
    }

    // ---------------------------------------------------------------- mpv olayları (yerel iş parçacığı)

    override fun eventProperty(property: String) {
        when (property) {
            "track-list" -> post(MpvEvent.TracksChanged)
            "dwidth", "dheight" -> main.post(::readVideoSize)
        }
        deliver(property, null)
    }

    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> { paused = value; playingChanged() }
            "core-idle" -> { coreIdle = value; playingChanged() }
            "paused-for-cache" -> if (buffering != value) {
                buffering = value
                post(MpvEvent.Buffering(value))
            }
            "eof-reached" -> if (value != eof) {
                eof = value
                playingChanged()
                // keep-open: dosya kapanmaz, son kare ekranda kalır; doğal son burada bildirilir.
                if (value) post(MpvEvent.FileEnded(EndReason.EOF))
            }
        }
    }

    override fun eventProperty(property: String, value: String) = deliver(property, value)

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                positionSec = value
                val now = System.currentTimeMillis()
                if (now - lastPositionPost >= POSITION_INTERVAL_MS) {
                    lastPositionPost = now
                    post(MpvEvent.Position(positionMs))
                }
            }
            "duration" -> {
                durationSec = value
                post(MpvEvent.Duration(durationMs))
            }
            "demuxer-cache-time" -> bufferedSec = value
        }
    }

    override fun eventProperty(property: String, value: MPVNode) = Unit

    override fun event(eventId: Int, node: MPVNode) {
        when (eventId) {
            EVENT_START_FILE -> {
                fileOpen = false
                main.post { lastSize = Triple(-1, -1, -1) }
                post(MpvEvent.FileStarted(openUri))
            }
            EVENT_FILE_LOADED -> {
                fileOpen = true
                main.post { pendingSeek?.let { (ms, exact) -> pendingSeek = null; seekTo(ms, exact) } }
                post(MpvEvent.FileLoaded)
            }
            EVENT_PLAYBACK_RESTART -> post(MpvEvent.PlaybackRestarted)
            EVENT_END_FILE -> {
                fileOpen = false
                val json = runCatching { JSONObject(node.toJson()) }.getOrNull()
                val reason = MpvJson.endReason(json?.optString("reason"))
                playingChanged()
                post(MpvEvent.FileEnded(reason))
                if (reason == EndReason.ERROR) post(MpvEvent.Error(json?.optString("file_error").orEmpty().ifBlank { "unknown" }))
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= LOG_LEVEL_ERROR) Log.e(TAG, "[$prefix] ${text.trim()}")
        else if (level <= LOG_LEVEL_WARN) Log.w(TAG, "[$prefix] ${text.trim()}")
    }

    private fun playingChanged() {
        val now = isPlaying
        if (now != lastPlaying) {
            lastPlaying = now
            post(MpvEvent.PlayingChanged(now))
        }
    }

    private var lastSize = Triple(-1, -1, -1)
    private fun readVideoSize() {
        if (released) return
        val w = getProperty("video-params/dw")?.toIntOrNull() ?: 0
        val h = getProperty("video-params/dh")?.toIntOrNull() ?: 0
        val rotation = getProperty("video-params/rotate")?.toIntOrNull() ?: 0
        val sideways = rotation == 90 || rotation == 270
        val size = Triple(if (sideways) h else w, if (sideways) w else h, rotation)
        if (size == lastSize) return
        lastSize = size
        val event = MpvEvent.VideoSizeChanged(size.first, size.second, rotation)
        flow.tryEmit(event)
        listeners.forEach { it(event) }
    }

    private fun deliver(property: String, value: String?) {
        if (watches.none { it.property == property }) return
        main.post { watches.filter { it.property == property }.forEach { it.callback(value) } }
    }

    companion object {
        private const val TAG = "OpenPlayerMpv"
        private const val POSITION_INTERVAL_MS = 250L
        private const val LOG_LEVEL_ERROR = 20
        private const val LOG_LEVEL_WARN = 30
        // mpv/client.h: mpv_event_id
        private const val EVENT_START_FILE = 6
        private const val EVENT_END_FILE = 7
        private const val EVENT_FILE_LOADED = 8
        private const val EVENT_PLAYBACK_RESTART = 21

        const val HWDEC_DEFAULT = "mediacodec,mediacodec-copy,no"

        /**
         * Ayar → mpv `hwdec`. Varsayılan [DecoderMode.HW_PLUS]: doğrudan donanım çıkışı, olmazsa kopyalı
         * donanım, o da olmazsa yazılım (mpv-android'in varsayılanı). [DecoderMode.HW] yalnız doğrudan
         * donanım dener; açamazsa ekran sıradaki kipe kendisi geçer.
         */
        fun hwdecFor(mode: DecoderMode): String = when (mode) {
            DecoderMode.HW -> "mediacodec"
            DecoderMode.HW_PLUS -> HWDEC_DEFAULT
            DecoderMode.SW -> "no"
        }

        /** Filtre varsa doğrudan `mediacodec` kipi listeden çıkar (kopyalı ya da yazılım kalır). */
        fun effectiveHwdec(wanted: String, filter: String): String {
            if (filter.isBlank()) return wanted
            val modes = wanted.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "mediacodec" }
            return when {
                modes.isEmpty() -> "mediacodec-copy,no"
                "mediacodec-copy" !in modes && "no" !in modes && wanted.contains("mediacodec") -> "mediacodec-copy,no"
                else -> modes.joinToString(",")
            }
        }

        /** [MpvEngines] fabrikasını bağlar; [App] açılışında bir kez çağrılır. */
        fun install() {
            MpvEngines.factory = { MpvPlayer(it) }
        }
    }
}
