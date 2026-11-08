package org.openplayer.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Lens türü: görüntünün küreye / sanal ekrana nasıl yerleştirileceği. */
enum class VrLens(val label: String) {
    NORMAL(Tx.s(R.string.vr360_013)),
    FISHEYE(Tx.s(R.string.vr360_001)),
    SPHERE360(Tx.s(R.string.vr360_002)),
    SPHERE180(Tx.s(R.string.vr360_003)),
}

/** 360° / VR180 / balık gözü / sanal ekran ve 3B video biçimleri (stereo: 0 = 3B değil, 1 = üst-alt, 2 = yan yana). */
enum class VrMode(val label: String, val lens: VrLens, val stereo: Int) {
    NONE(Tx.s(R.string.audiofx_004), VrLens.SPHERE360, 0),
    FULL(Tx.s(R.string.vr360_004), VrLens.SPHERE360, 0),
    FULL_TB(Tx.s(R.string.vr360_005), VrLens.SPHERE360, 1),
    FULL_SBS("360° yan yana 3B", VrLens.SPHERE360, 2),
    HALF(Tx.s(R.string.vr360_006), VrLens.SPHERE180, 0),
    HALF_TB(Tx.s(R.string.vr360_007), VrLens.SPHERE180, 1),
    HALF_SBS("180° yan yana 3B", VrLens.SPHERE180, 2),
    FISHEYE(Tx.s(R.string.vr360_008), VrLens.FISHEYE, 0),
    FISHEYE_TB(Tx.s(R.string.vr360_009), VrLens.FISHEYE, 1),
    FISHEYE_SBS(Tx.s(R.string.vr360_010), VrLens.FISHEYE, 2),
    FLAT(Tx.s(R.string.vr360_011), VrLens.NORMAL, 0),
    FLAT_TB(Tx.s(R.string.vr360_012), VrLens.NORMAL, 1),
    FLAT_SBS(Tx.s(R.string.vr360_014), VrLens.NORMAL, 2);

    /** Yarım küre üzerine çizilir (180° ve balık gözü). */
    val halfSphere: Boolean get() = lens == VrLens.SPHERE180 || lens == VrLens.FISHEYE

    companion object {
        /** Seçim menüsünde gösterilen sıra. */
        val choices = listOf(FULL, FULL_TB, FULL_SBS, HALF, HALF_TB, HALF_SBS, FISHEYE, FISHEYE_TB, FISHEYE_SBS, FLAT, FLAT_TB, FLAT_SBS)

        /** Lens ve 3B biçiminden mod bulur. */
        fun of(lens: VrLens, stereo: Int): VrMode =
            entries.first { it != NONE && it.lens == lens && it.stereo == stereo }
    }
}

/** 360° algılama ve bilinen videoların kaydı (listede "360°" etiketi için). */
object Vr360 {
    private const val PREFS = "vr360"

    /** Üstveriden kip: küresel değilse NONE; 180° ayrımı üstveride olmadığından dosya adından gelir. */
    fun modeFor(spherical: Boolean, stereo: Int, name: String): VrMode {
        if (!spherical) return VrMode.NONE
        val lower = name.lowercase()
        val is180 = "180" in lower || "vr180" in lower
        return when {
            is180 && stereo == 1 -> VrMode.HALF_TB
            is180 && stereo == 2 -> VrMode.HALF_SBS
            is180 -> VrMode.HALF
            stereo == 1 -> VrMode.FULL_TB
            stereo == 2 -> VrMode.FULL_SBS
            else -> VrMode.FULL
        }
    }

    /**
     * Dosyanın küresel üstverisini okuyup kipi bulur (mpv bu bilgiyi özellik olarak vermez).
     * [uri]: dosya yolu, `file://`, `content://`, `http(s)://`. SMB/FTP/WebDAV için yerel vekil adresi
     * verilmelidir. Disk ve ağ okur: ana iş parçacığında çağırmayın. Okunamazsa NONE.
     */
    fun probe(context: Context, uri: String, name: String, headers: Map<String, String> = emptyMap()): VrMode {
        val meta = runCatching { readMeta(context, uri, headers) }.getOrNull() ?: return VrMode.NONE
        return modeFor(meta.spherical, meta.stereo, name)
    }

    private fun readMeta(context: Context, uri: String, headers: Map<String, String>): VrVideoMeta? {
        val u = Uri.parse(uri)
        return when (u.scheme?.lowercase()) {
            null -> File(uri).inputStream().channel.use { VrMeta.read(ChannelSource(it)) }
            "file" -> File(u.path ?: return null).inputStream().channel.use { VrMeta.read(ChannelSource(it)) }
            "content" -> context.contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.let { VrMeta.read(ChannelSource(it)) }
            }
            "http", "https" -> VrMeta.read(HttpSource(uri, headers))
            else -> null
        }
    }

    private class ChannelSource(private val channel: FileChannel) : VrMeta.Source {
        override val length: Long = channel.size()
        override fun read(position: Long, into: ByteArray, count: Int): Int =
            channel.read(ByteBuffer.wrap(into, 0, count), position)
    }

    /** Range isteğiyle okur; sunucu Range tanımıyorsa yalnız dosya başı okunabilir. */
    private class HttpSource(private val url: String, private val headers: Map<String, String>) : VrMeta.Source {
        override var length: Long = Long.MAX_VALUE
            private set

        override fun read(position: Long, into: ByteArray, count: Int): Int {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4000
                c.readTimeout = 4000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.setRequestProperty("Range", "bytes=$position-${position + count - 1}")
                val partial = c.responseCode == 206
                if (!partial && (c.responseCode != 200 || position > 0)) return -1
                if (partial) {
                    c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { length = it }
                } else if (c.contentLengthLong > 0) {
                    length = c.contentLengthLong
                }
                c.inputStream.use { input ->
                    var done = 0
                    while (done < count) {
                        val n = input.read(into, done, count - done)
                        if (n < 0) break
                        done += n
                    }
                    return if (done == 0) -1 else done
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private val sphericalName = Regex("(?<![0-9])360(?![0-9p])|vr180|_vr(?![a-z])|[ ._-]vr[ ._-]|equirect")

    /** Dosya adından tahmin (yalnızca liste etiketi için; oynatmada üstveri esas alınır). */
    fun nameLooksSpherical(name: String): Boolean {
        val l = name.lowercase()
        return sphericalName.containsMatchIn(l)
    }

    fun remember(context: Context, uri: String, mode: VrMode) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (mode == VrMode.NONE) p.edit().remove(uri).apply() else p.edit().putString(uri, mode.name).apply()
    }

    fun saved(context: Context, uri: String): VrMode? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uri, null)
            ?.let { runCatching { VrMode.valueOf(it) }.getOrNull() }

    fun isSpherical(context: Context, uri: String, name: String): Boolean =
        (saved(context, uri)?.let { it.lens != VrLens.NORMAL } ?: false) || nameLooksSpherical(name)
}

/** Dosyadan okunan küresel üstveri. [stereo]: 0 = 3B değil, 1 = üst-alt, 2 = yan yana. */
internal data class VrVideoMeta(val spherical: Boolean, val stereo: Int)

/**
 * MP4 (`sv3d`/`st3d`) ve Matroska/WebM (`Projection`/`StereoMode`) videolarından küresel üstveriyi okur.
 * Android'e bağlı değildir, birim testle sınanır. Bozuk ya da kesik dosyada null döner.
 */
internal object VrMeta {
    interface Source {
        /** Bilinmiyorsa [Long.MAX_VALUE]. */
        val length: Long
        /** [position] konumundan en çok [count] bayt okur; bittiyse -1. */
        fun read(position: Long, into: ByteArray, count: Int): Int
    }

    private const val WINDOW = 64 * 1024
    private const val MAX_READS = 96
    private const val MAX_ELEMENTS = 4096
    private const val MKV_MAGIC = 0x1A45DFA3L
    private const val MKV_SEGMENT = 0x18538067L
    private const val MKV_TRACKS = 0x1654AE6BL
    private const val MKV_CLUSTER = 0x1F43B675L
    private const val MKV_TRACK_ENTRY = 0xAEL
    private const val MKV_TRACK_TYPE = 0x83L
    private const val MKV_VIDEO = 0xE0L
    private const val MKV_STEREO_MODE = 0x53B8L
    private const val MKV_PROJECTION = 0x7670L
    private const val MKV_PROJECTION_TYPE = 0x7671L

    private class Stop : Exception()

    /** Ağ isteği sayısını sınırlayan tek pencerelik okuma tamponu. */
    private class Reader(private val src: Source) {
        private val buf = ByteArray(WINDOW)
        private var start = 0L
        private var filled = 0
        private var reads = 0
        val length: Long get() = src.length

        fun u8(pos: Long): Int {
            if (pos < start || pos >= start + filled) {
                if (++reads > MAX_READS) throw Stop()
                start = pos
                filled = src.read(pos, buf, WINDOW).coerceAtLeast(0)
                if (filled == 0) throw Stop()
            }
            return buf[(pos - start).toInt()].toInt() and 0xFF
        }

        fun u32(pos: Long): Long = (0..3).fold(0L) { acc, i -> (acc shl 8) or u8(pos + i).toLong() }
        fun u64(pos: Long): Long = (0..7).fold(0L) { acc, i -> (acc shl 8) or u8(pos + i).toLong() }
        fun tag(pos: Long): String = String(CharArray(4) { u8(pos + it).toChar() })
    }

    fun read(src: Source): VrVideoMeta? = try {
        val r = Reader(src)
        if (r.u32(0) == MKV_MAGIC) matroska(r) else mp4(r)
    } catch (_: Stop) {
        null
    }

    // ---------------------------------------------------------------- MP4

    private class Box(val type: String, val body: Long, val end: Long)

    private fun box(r: Reader, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        val size32 = r.u32(pos)
        val type = r.tag(pos + 4)
        val header = if (size32 == 1L) 16L else 8L
        val size = when (size32) {
            0L -> limit - pos
            1L -> r.u64(pos + 8)
            else -> size32
        }
        if (size < header) return null
        return Box(type, pos + header, if (size > limit - pos) limit else pos + size)
    }

    /** [from]..[to] arasındaki kutuları gezer; [visit] null dışı dönerse durup onu verir. */
    private fun <T : Any> boxes(r: Reader, from: Long, to: Long, visit: (Box) -> T?): T? {
        var pos = from
        repeat(MAX_ELEMENTS) {
            val b = box(r, pos, to) ?: return null
            visit(b)?.let { return it }
            pos = b.end
        }
        return null
    }

    private fun child(r: Reader, parent: Box, type: String): Box? =
        boxes(r, parent.body, parent.end) { b -> b.takeIf { it.type == type } }

    private fun mp4(r: Reader): VrVideoMeta? {
        val moov = boxes(r, 0L, r.length) { b -> b.takeIf { it.type == "moov" } } ?: return null
        return boxes(r, moov.body, moov.end) { trak ->
            if (trak.type == "trak") videoTrack(r, trak) else null
        }
    }

    private fun videoTrack(r: Reader, trak: Box): VrVideoMeta? {
        val mdia = child(r, trak, "mdia") ?: return null
        val hdlr = child(r, mdia, "hdlr") ?: return null
        if (r.tag(hdlr.body + 8) != "vide") return null
        val minf = child(r, mdia, "minf") ?: return null
        val stbl = child(r, minf, "stbl") ?: return null
        val stsd = child(r, stbl, "stsd") ?: return null
        // stsd: 8 bayt sürüm + sayı; görsel örnek girişinde alt kutular başlıktan 78 bayt sonra başlar.
        val entry = box(r, stsd.body + 8, stsd.end) ?: return null
        var spherical = false
        var stereo = 0
        boxes<Unit>(r, entry.body + 78, entry.end) { b ->
            when (b.type) {
                "sv3d" -> spherical = child(r, b, "proj") != null
                "st3d" -> stereo = when (r.u8(b.body + 4)) {
                    1 -> 1
                    2 -> 2
                    else -> 0
                }
            }
            null
        }
        return VrVideoMeta(spherical, stereo)
    }

    // ---------------------------------------------------------------- Matroska

    private class Element(val id: Long, val body: Long, val end: Long)

    private fun vintLength(first: Int): Int {
        if (first == 0) throw Stop()
        return Integer.numberOfLeadingZeros(first) - 23
    }

    private fun element(r: Reader, pos: Long, limit: Long): Element? {
        if (pos >= limit) return null
        val idLen = vintLength(r.u8(pos))
        if (idLen > 4) return null
        val id = (0 until idLen).fold(0L) { acc, i -> (acc shl 8) or r.u8(pos + i).toLong() }
        val sizePos = pos + idLen
        val sizeLen = vintLength(r.u8(sizePos))
        val mask = (1L shl (8 - sizeLen)) - 1
        var size = r.u8(sizePos).toLong() and mask
        var unknown = size == mask
        for (i in 1 until sizeLen) {
            val b = r.u8(sizePos + i).toLong()
            unknown = unknown && b == 0xFFL
            size = (size shl 8) or b
        }
        val body = sizePos + sizeLen
        return Element(id, body, if (unknown || size > limit - body) limit else body + size)
    }

    private fun <T : Any> elements(r: Reader, from: Long, to: Long, visit: (Element) -> T?): T? {
        var pos = from
        repeat(MAX_ELEMENTS) {
            val e = element(r, pos, to) ?: return null
            visit(e)?.let { return it }
            pos = e.end
        }
        return null
    }

    private fun uint(r: Reader, e: Element): Long =
        (e.body until e.end).fold(0L) { acc, p -> (acc shl 8) or r.u8(p).toLong() }

    private fun matroska(r: Reader): VrVideoMeta? {
        val ebml = element(r, 0L, r.length) ?: return null
        val segment = elements(r, ebml.end, r.length) { e -> e.takeIf { it.id == MKV_SEGMENT } } ?: return null
        val tracks = elements(r, segment.body, segment.end) { e ->
            when (e.id) {
                MKV_TRACKS -> e
                MKV_CLUSTER -> throw Stop() // Tracks kümelerden önce gelir; yoksa aramayı uzatma.
                else -> null
            }
        } ?: return null
        return elements(r, tracks.body, tracks.end) { e ->
            if (e.id == MKV_TRACK_ENTRY) matroskaVideo(r, e) else null
        }
    }

    private fun matroskaVideo(r: Reader, entry: Element): VrVideoMeta? {
        var isVideo = false
        var video: Element? = null
        elements<Unit>(r, entry.body, entry.end) { e ->
            when (e.id) {
                MKV_TRACK_TYPE -> isVideo = uint(r, e) == 1L
                MKV_VIDEO -> video = e
            }
            null
        }
        val v = video?.takeIf { isVideo } ?: return null
        var spherical = false
        var stereo = 0
        elements<Unit>(r, v.body, v.end) { e ->
            when (e.id) {
                MKV_STEREO_MODE -> stereo = when (uint(r, e)) {
                    1L -> 2 // yan yana (sol göz önce)
                    3L -> 1 // üst-alt (sol göz önce)
                    else -> 0
                }
                MKV_PROJECTION -> elements<Unit>(r, e.body, e.end) { p ->
                    if (p.id == MKV_PROJECTION_TYPE) spherical = uint(r, p) in 1L..3L
                    null
                }
            }
            null
        }
        return VrVideoMeta(spherical, stereo)
    }
}

/** [Vr360View]'ın görüntü çıkışı. Yöntemler görünümün işçi iş parçacığında, sırayla çağrılır. */
interface VrVideoOutput {
    /** Aynı [surface] yeniden gelirse yalnız boyut değişmiştir. */
    fun attach(surface: Surface, width: Int, height: Int)
    fun detach()
}

/** 360° görüntüsünü mpv motoruna bağlar. */
class MpvVrOutput(private val engine: MpvEngine) : VrVideoOutput {
    private var attached: Surface? = null
    override fun attach(surface: Surface, width: Int, height: Int) {
        attached = surface
        engine.attachSurface(surface, width, height)
    }
    // Yalnız kendi yüzeyini ayırır: normal görünüme dönerken yeni bağlanan yüzeyi koparmaz.
    override fun detach() {
        attached?.let { engine.detachSurface(it) }
        attached = null
    }
}

/**
 * Yüzey ile motor arasındaki sırayı tek yerde tutar: istenen durum yazılır, [runner] üzerinde asıl
 * duruma getirilir. Eski yüzey her zaman önce ayrılır, sonra serbest bırakılır, sonra yenisi
 * bağlanır (bağlıyken serbest bırakılan yüzey MediaCodec ve EGL hatası üretir).
 * Android'e bağlı değildir, birim testle sınanır.
 */
internal class VrSurfaceLink<S : Any>(
    private val runner: (Runnable) -> Unit,
    private val attach: (S, Int, Int) -> Unit,
    private val detach: () -> Unit,
    private val retire: (S) -> Unit,
) {
    private var wanted: S? = null
    private var wantedW = 0
    private var wantedH = 0
    private var maxSize = 0
    private val retiring = ArrayList<S>()
    // Yalnız runner üzerinde kullanılır.
    private var attached: S? = null
    private var attachedW = 0
    private var attachedH = 0

    fun surfaceReady(surface: S) = change {
        if (wanted !== surface) wanted?.let { retiring += it }
        wanted = surface
    }

    fun surfaceLost() = change {
        wanted?.let { retiring += it }
        wanted = null
    }

    fun videoSize(width: Int, height: Int) = change {
        wantedW = width
        wantedH = height
    }

    fun maxTextureSize(size: Int) = change { maxSize = size }

    private inline fun change(block: () -> Unit) {
        synchronized(this, block)
        runner(Runnable { reconcile() })
    }

    private fun reconcile() {
        val surface: S?
        val size: Pair<Int, Int>
        val done: List<S>
        synchronized(this) {
            surface = wanted
            size = fitSize(wantedW, wantedH, maxSize)
            done = retiring.toList()
            retiring.clear()
        }
        if (attached != null && attached !== surface) {
            attached = null
            detach()
        }
        done.forEach(retire)
        if (surface == null) return
        val (w, h) = size
        if (attached !== surface || w != attachedW || h != attachedH) {
            attach(surface, w, h)
            attached = surface
            attachedW = w
            attachedH = h
        }
    }

    /** Sıradaki işler bitene dek bekler; [timeoutMs] içinde bitmezse false. */
    fun flush(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        runner(Runnable { latch.countDown() })
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** Video boyutu henüz bilinmezken; boyut gelince düzeltilir. */
        const val FALLBACK_W = 1920
        const val FALLBACK_H = 1080

        /** Küre dokusu video boyutunda olur; GL sınırını aşarsa oran korunarak küçülür. */
        fun fitSize(width: Int, height: Int, max: Int): Pair<Int, Int> {
            val known = width > 0 && height > 0
            val w = if (known) width else FALLBACK_W
            val h = if (known) height else FALLBACK_H
            val longest = maxOf(w, h)
            if (max <= 0 || longest <= max) return w to h
            return (w.toLong() * max / longest).toInt().coerceAtLeast(1) to (h.toLong() * max / longest).toInt().coerceAtLeast(1)
        }
    }
}

/** Bir SurfaceTexture ile yüzeyi; ikisi birlikte ve bir kez serbest kalır. */
private class VideoTexture(val texture: SurfaceTexture) {
    val surface = Surface(texture)
    /** Çizilmeyi bekleyen kare sayısı. */
    val pending = AtomicInteger(0)
    private var released = false

    /**
     * Bekleyen tüm kareleri alıp dönüşüm matrisini yazar; doku bittiyse false.
     * Biri alınmazsa kuyruk dolar ve mpv'nin çıktı iş parçacığı boş tampon beklerken takılır.
     */
    @Synchronized
    fun latest(matrix: FloatArray): Boolean {
        if (released) return false
        repeat(pending.getAndSet(0).coerceAtLeast(1)) { runCatching { texture.updateTexImage() } }
        texture.getTransformMatrix(matrix)
        return true
    }

    @Synchronized
    fun resize(width: Int, height: Int) {
        if (!released) texture.setDefaultBufferSize(width, height)
    }

    /** Fotoğraf modu: bitmap'i yüzeye çizer (video karesi gibi küreye uygulanır). */
    @Synchronized
    fun drawBitmap(bmp: android.graphics.Bitmap) {
        if (released) return
        texture.setDefaultBufferSize(bmp.width, bmp.height)
        val c = surface.lockCanvas(null)
        c.drawBitmap(bmp, 0f, 0f, null)
        surface.unlockCanvasAndPost(c)
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        surface.release()
        texture.release()
    }
}

/**
 * Videoyu bir kürenin içinden gösteren GL yüzeyi. mpv'nin çıktısı [output] ile motora bağlanan
 * yüzeye yazılır; bakış yönü [yaw]/[pitch]/[fov] ile (veya hareket sensörüyle) değişir.
 *
 * [cardboard] açıkken ekran ikiye bölünür: her göz için küre ayrı çizilir ve lens için
 * barrel (fıçı) ön-distorsiyonu uygulanır; böylece telefon bir VR gözlüğüne yerleştirilebilir.
 *
 * Yüzey kuralları (mpv'nin takılmaması için):
 * - mpv `vo=gpu` ile EGL üzerinden yazar; tampon boyutunu `SurfaceTexture.setDefaultBufferSize`
 *   belirler, verilmezse 1x1 kalır. Bu yüzden [setVideoSize] önce dokuyu, sonra mpv'yi boyutlar.
 * - Tüketici kareleri almazsa mpv'nin çıktı iş parçacığı takılır ve `detachSurface` da onu bekler.
 *   Bu yüzden ayırma GL iş parçacığı hâlâ çizerken yapılır (`onPause` ve `surfaceDestroyed` içinde
 *   `super`den önce), motor çağrısı GL ya da ana iş parçacığında yapılmaz (işçi iş parçacığı) ve
 *   kare bildirimi ana iş parçacığına bağlı değildir.
 * - Süre aşılırsa doku bırakılır: üretici hata alır ve mpv takılmaktan kurtulur.
 */
class Vr360View(context: Context, private val output: VrVideoOutput? = null) : GLSurfaceView(context), SensorEventListener {

    private val link = VrSurfaceLink<VideoTexture>(
        runner = { outputWorker.execute(it) },
        attach = { t, w, h ->
            if (output != null) {
                // Önce doku: mpv boyutu değiştirince EGL yüzeyi yeni tamponu almalı.
                t.resize(w, h)
                output.attach(t.surface, w, h)
            }
        },
        detach = { output?.detach() },
        retire = { it.release() },
    )

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var fov = 90f
    @Volatile var mode: VrMode = VrMode.FULL
        set(value) {
            field = value
            requestRender()
        }

    /** VR gözlük (Cardboard) modu: iki göz yan yana + lens distorsiyonu. */
    @Volatile var cardboard = false
        set(value) {
            field = value
            requestRender()
        }

    /** Görüntünün kendi ekseni etrafında eğimi (iki parmakla döndürme), derece. */
    @Volatile var roll = 0f
        set(value) {
            field = value
            requestRender()
        }

    /** Ufuk düzeltme: hareket sensöründe cihazın yana yatması görüntüye yansımaz. */
    @Volatile var horizonLevel = true
        set(value) {
            field = value
            requestRender()
        }

    /** Videonun / fotoğrafın en-boy oranı (balık gözü ve sanal ekran şekli için). */
    @Volatile var videoAspect = 16f / 9f
        set(value) {
            field = value.coerceIn(0.2f, 6f)
            requestRender()
        }

    private var useSensor = false
    private var sensorAlt = false
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    /** Son sensör örneği: satır öncelikli dönüş matrisi (cihaz → dünya) ve ekran yönü (0..3). */
    private val sensorMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var sensorRotation = 0
    @Volatile private var hasSensorSample = false
    /** "İleri" kabul edilen yatay açı (radyan); sensör açılınca/sıfırlanınca ilk geçerli örnekten alınır. */
    private var yawZero = 0.0
    @Volatile private var pendingRecenter = false
    private val tmpRot = FloatArray(16)
    private var sensorOn = false

    private val renderer = Renderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = false
    }

    /**
     * [dxPx]/[dyPx] GestureDetector.onScroll değerleridir (önceki − şimdiki konum): parmak sağa gidince
     * dx negatif olur. Görüntü parmağı izlesin (YouTube gibi): parmak sağa → sahne sağa kayar.
     */
    fun look(dxPx: Float, dyPx: Float) {
        val k = fov / height.coerceAtLeast(1)
        yaw += dxPx * k
        pitch = (pitch + dyPx * k).coerceIn(-89f, 89f)
        requestRender()
    }

    /** Parmak ekrandayken otomatik dönüş durur. */
    @Volatile var touching = false

    @Volatile private var autoSpeed = 0f
    private var lastAutoNs = 0L
    private val autoTick = object : Runnable {
        override fun run() {
            if (autoSpeed == 0f || !isAttachedToWindow) {
                lastAutoNs = 0L
                return
            }
            val now = System.nanoTime()
            if (lastAutoNs != 0L && !touching) yaw += autoSpeed * (now - lastAutoNs) / 1e9f
            lastAutoNs = now
            requestRender()
            postOnAnimation(this)
        }
    }

    val autoRotating: Boolean get() = autoSpeed != 0f

    /** Panorama otomatik dönüşü (derece/sn). */
    fun setAutoRotate(on: Boolean) {
        autoSpeed = if (on) 8f else 0f
        lastAutoNs = 0L
        removeCallbacks(autoTick)
        if (on) postOnAnimation(autoTick)
    }

    /** Bazı cihazlarda oyun dönüş vektörü yerine standart dönüş vektörü daha doğru çalışır (sensör türü 2). */
    fun setSensorAlt(on: Boolean) {
        if (sensorAlt == on) return
        sensorAlt = on
        if (sensorOn) {
            sensors.unregisterListener(this)
            sensorOn = false
            updateSensor()
        }
    }

    fun zoomFov(factor: Float) {
        fov = (fov / factor).coerceIn(40f, 110f)
        requestRender()
    }

    private var photo: android.graphics.Bitmap? = null

    /** Videonun yerine tek bir fotoğrafı (ör. eş dikdörtgen 360° JPEG) gösterir. */
    fun showBitmap(bmp: android.graphics.Bitmap) {
        photo = bmp
        videoAspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        queueEvent { renderer.drawPhoto() }
    }

    fun resetLook() {
        roll = 0f
        yaw = 0f
        pitch = 0f
        fov = 90f
        // Sensör açıksa o anki yatay yönü yeni "ileri" kabul et (örnek henüz yoksa ilk örnekte).
        recenterSensor()
        requestRender()
    }

    /** Mevcut örnekten, yoksa bir sonraki geçerli örnekten yatay "ileri" yönünü alır. */
    private fun recenterSensor() {
        if (!hasSensorSample) {
            pendingRecenter = true
            return
        }
        val yaw = synchronized(sensorMatrix) { SensorView.forwardYaw(sensorMatrix, sensorRotation) }
        if (yaw != null) {
            yawZero = yaw
            pendingRecenter = false
        } else {
            pendingRecenter = true // telefon düz yatıyor: yön belirsiz, dik tutulunca ayarla
        }
    }

    fun setSensor(on: Boolean) {
        useSensor = on
        updateSensor()
        requestRender()
    }

    val sensorEnabled get() = useSensor

    /** Videonun gösterim boyutu ([MpvEvent.VideoSizeChanged]). Küre dokusu bu boyutta olur. */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoAspect = width.toFloat() / height
        link.videoSize(width, height)
    }

    private fun updateSensor() {
        val want = useSensor && isAttachedToWindow
        if (want == sensorOn) return
        sensorOn = want
        if (want) {
            hasSensorSample = false
            pendingRecenter = true
            val s = if (sensorAlt) {
                sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            } else {
                sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            }
            if (s == null) {
                useSensor = false
                sensorOn = false
            } else sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        } else sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(tmpRot, event.values)
        // Bozuk/sıfır örnekleri yok say: görüntü kaybolmasın.
        for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) if (!tmpRot[i].isFinite()) return
        val rotation = (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
        synchronized(sensorMatrix) {
            System.arraycopy(tmpRot, 0, sensorMatrix, 0, 16)
            sensorRotation = rotation
        }
        hasSensorSample = true
        if (pendingRecenter) recenterSensor()
        requestRender()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSensor()
        if (autoSpeed != 0f) postOnAnimation(autoTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoTick)
        if (sensorOn) {
            sensors.unregisterListener(this)
            sensorOn = false
        }
        super.onDetachedFromWindow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseOutput()
        super.surfaceDestroyed(holder)
    }

    override fun onPause() {
        if (sensorOn) {
            sensors.unregisterListener(this)
            sensorOn = false
        }
        releaseOutput()
        super.onPause()
    }

    /**
     * Motoru yüzeyden ayırır ve dokuyu bırakır. GL iş parçacığı bu sırada kare almayı sürdürür.
     * Dönünce motor bu yüzeyden ayrılmıştır, başka yüzey bağlanabilir.
     */
    private fun releaseOutput() {
        val t = renderer.currentTexture ?: return
        link.surfaceLost()
        if (!link.flush(DETACH_WAIT_MS)) {
            android.util.Log.w("Vr360View", "detach timed out, abandoning surface")
            t.release()
        }
        renderer.currentTexture = null
    }

    override fun onResume() {
        super.onResume()
        updateSensor()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var texId = 0
        @Volatile var currentTexture: VideoTexture? = null
        private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private val tmp = FloatArray(16)
        private var aspect = 1f
        private var surfaceW = 0
        private var surfaceH = 0
        private var meshKeyBuilt = ""
        private var vertices: FloatBuffer? = null
        private var indices: ShortBuffer? = null
        private var indexCount = 0
        private var uMvp = 0
        private var uTex = 0
        private var uCrop = 0
        private var aPos = 0
        private var aUv = 0

        // Gözlük modu: lens distorsiyon geçişi ve göz başına ara doku (FBO).
        private var distProgram = 0
        private var dPos = 0
        private var dUv = 0
        private var uDistTex = 0
        private var uLensCenter = 0
        private var uK1 = 0
        private var uK2 = 0
        private var quad: FloatBuffer? = null
        private var fbo = 0
        private var fboTex = 0
        private var fboW = 0
        private var fboH = 0

        private val vs = """
            uniform mat4 uMvp;
            uniform mat4 uTex;
            uniform vec4 uCrop;
            attribute vec3 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vec2 t = uCrop.xy + aUv * uCrop.zw;
                vUv = (uTex * vec4(t, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uSampler;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uSampler, vUv); }
        """.trimIndent()

        // Tam ekran dörtgen; göz dokusunu barrel distorsiyonla ekrana çizer.
        private val distVs = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val distFs = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uLensCenter;
            uniform float uK1;
            uniform float uK2;
            varying vec2 vUv;
            void main() {
                vec2 r = vUv - uLensCenter;
                float r2 = dot(r, r);
                float f = 1.0 + uK1 * r2 + uK2 * r2 * r2;
                vec2 src = uLensCenter + r * f;
                if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, src);
                }
            }
        """.trimIndent()

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        private fun link(vsrc: String, fsrc: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vsrc))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fsrc))
            GLES20.glLinkProgram(p)
            return p
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // GL bağlamı yeniden oluştu: eski doku ve FBO kimlikleri geçersiz.
            texId = 0
            fbo = 0
            fboTex = 0
            fboW = 0
            fboH = 0

            program = link(vs, fs)
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uCrop = GLES20.glGetUniformLocation(program, "uCrop")
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")

            distProgram = link(distVs, distFs)
            dPos = GLES20.glGetAttribLocation(distProgram, "aPos")
            dUv = GLES20.glGetAttribLocation(distProgram, "aUv")
            uDistTex = GLES20.glGetUniformLocation(distProgram, "uTex")
            uLensCenter = GLES20.glGetUniformLocation(distProgram, "uLensCenter")
            uK1 = GLES20.glGetUniformLocation(distProgram, "uK1")
            uK2 = GLES20.glGetUniformLocation(distProgram, "uK2")

            // Tam ekran dörtgen: [x, y, u, v] × 4 (triangle strip).
            val q = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            q.put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ))
            q.position(0)
            quad = q

            val max = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
            link.maxTextureSize(max[0])

            createTexture()
        }

        /**
         * Dış dokuyu ve mpv'nin yazacağı yüzeyi kurar. Önceki doku hâlâ bağlı olabilir (GL bağlamı
         * kaybı): [link] onu önce ayırır, sonra serbest bırakır.
         */
        private fun createTexture() {
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val t = VideoTexture(SurfaceTexture(texId))
            // Bu iş parçacığında Looper yok: işleyici verilmezse bildirim ana iş parçacığına gider ve
            // ana iş parçacığı motoru beklerken kareler alınmaz.
            t.texture.setOnFrameAvailableListener({
                t.pending.incrementAndGet()
                requestRender()
            }, frameHandler)
            currentTexture = t
            meshKeyBuilt = ""
            buildMesh()
            link.surfaceReady(t)
            if (photo != null) drawPhoto()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
            aspect = width.toFloat() / height.coerceAtLeast(1)
            // GL bağlamı korunarak yüzey geri geldi: bırakılan doku yeniden kurulur.
            if (currentTexture == null) createTexture()
        }

        /** Göz başına ara dokuyu (FBO) istenen boyutta hazırlar; boyut değişince yeniden kurar. */
        private fun ensureFbo(w: Int, h: Int) {
            if (fbo != 0 && w == fboW && h == fboH) return
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            fbo = fb[0]
            val tx = IntArray(1)
            GLES20.glGenTextures(1, tx, 0)
            fboTex = tx[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            fboW = w
            fboH = h
        }

        override fun onDrawFrame(gl: GL10?) {
            val tex = currentTexture ?: return
            if (!tex.latest(texMatrix)) return

            if (meshKey() != meshKeyBuilt) buildMesh()

            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            computeView()

            if (!cardboard || surfaceW <= 1 || surfaceH <= 1) {
                // Tek görünüm (normal 360°).
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
                Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
                val c = eyeCrop(0)
                drawSphere(mvp, c[0], c[1], c[2], c[3])
                return
            }

            // Gözlük modu: iki göz + FBO + barrel distorsiyon.
            val eyeW = surfaceW / 2
            val eyeH = surfaceH
            ensureFbo(eyeW, eyeH)
            val eyeAspect = eyeW.toFloat() / eyeH.coerceAtLeast(1)
            Matrix.perspectiveM(proj, 0, fov, eyeAspect, 0.1f, 10f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            // Ekranı bir kez siyaha boya (gözler arası ve kenarlar siyah kalsın).
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            for (eye in 0..1) {
                val c = eyeCrop(eye)
                // 1) Küreyi göz dokusuna çiz.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glViewport(0, 0, eyeW, eyeH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawSphere(mvp, c[0], c[1], c[2], c[3])
                // 2) Göz dokusunu ekranın ilgili yarısına lens distorsiyonuyla çiz.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(eye * eyeW, 0, eyeW, eyeH)
                drawDistortion()
            }
        }

        /** Bakış yönü matrisini ([view]) sensör veya parmak girdisine göre hesaplar. */
        private fun computeView() {
            Matrix.setIdentityM(view, 0)
            if (sensorOn) {
                // Sensör örneği yoksa (henüz gelmediyse) dümdüz ileri bak; geldikten sonra baş takibi.
                if (hasSensorSample) {
                    val sv = synchronized(sensorMatrix) { SensorView.viewMatrix(sensorMatrix, sensorRotation, yawZero) }
                    System.arraycopy(sv, 0, view, 0, 16)
                }
                if (horizonLevel) {
                    // Ufuk düzeltme: yalnız bakış yönünü al, cihazın yana yatmasını at (yukarı = +Y).
                    val fx = -view[2]
                    val fy = -view[6]
                    val fz = -view[10]
                    if (kotlin.math.abs(fy) < 0.995f) {
                        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, fx, fy, fz, 0f, 1f, 0f)
                    }
                }
                // Parmakla eklenen kaydırma, sensörün üstüne uygulanır.
                Matrix.setIdentityM(tmp, 0)
                Matrix.rotateM(tmp, 0, pitch, 1f, 0f, 0f)
                Matrix.rotateM(tmp, 0, yaw, 0f, 1f, 0f)
                val base = view.copyOf()
                Matrix.multiplyMM(view, 0, tmp, 0, base, 0)
            } else {
                Matrix.rotateM(view, 0, pitch, 1f, 0f, 0f)
                Matrix.rotateM(view, 0, yaw, 0f, 1f, 0f)
            }
            val r = roll
            if (r != 0f) {
                // Görüntüyü kendi ekseni etrafında döndür (iki parmakla döndürme).
                Matrix.setRotateM(tmp, 0, r, 0f, 0f, 1f)
                val base = view.copyOf()
                Matrix.multiplyMM(view, 0, tmp, 0, base, 0)
            }
        }

        /** Göz için doku kırpması: [x, y, genişlik, yükseklik] (0..1). 3B'de her göz kendi yarısını alır. */
        private fun eyeCrop(eye: Int): FloatArray = when (mode.stereo) {
            1 -> if (eye == 0) floatArrayOf(0f, 0.5f, 1f, 0.5f) else floatArrayOf(0f, 0f, 1f, 0.5f) // üst-alt: üst = sol göz
            2 -> if (eye == 0) floatArrayOf(0f, 0f, 0.5f, 1f) else floatArrayOf(0.5f, 0f, 0.5f, 1f) // yan yana: sol = sol göz
            else -> floatArrayOf(0f, 0f, 1f, 1f)
        }

        private fun drawSphere(mvpMat: FloatArray, cx: Float, cy: Float, cz: Float, cw: Float) {
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMat, 0)
            GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
            GLES20.glUniform4f(uCrop, cx, cy, cz, cw)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

            val vb = vertices ?: return
            val ib = indices ?: return
            vb.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
            vb.position(3)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, vb)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ib)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun drawDistortion() {
            GLES20.glUseProgram(distProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glUniform1i(uDistTex, 0)
            GLES20.glUniform2f(uLensCenter, 0.5f, 0.5f)
            GLES20.glUniform1f(uK1, 0.22f)
            GLES20.glUniform1f(uK2, 0.24f)

            val q = quad ?: return
            q.position(0)
            GLES20.glEnableVertexAttribArray(dPos)
            GLES20.glVertexAttribPointer(dPos, 2, GLES20.GL_FLOAT, false, 16, q)
            q.position(2)
            GLES20.glEnableVertexAttribArray(dUv)
            GLES20.glVertexAttribPointer(dUv, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(dPos)
            GLES20.glDisableVertexAttribArray(dUv)
        }

        /** Göz başına dokunun en-boy oranı (3B kırpma yarıya böler). */
        private fun eyeVideoAspect(): Float {
            val a = videoAspect
            return when (mode.stereo) {
                1 -> a * 2f
                2 -> a / 2f
                else -> a
            }
        }

        private fun meshKey(): String = "${mode.lens}|${(eyeVideoAspect() * 100).toInt()}"

        /** Lens türüne göre ağ: küre / yarım küre (360°, 180°), balık gözü UV'li yarım küre veya düz sanal ekran. */
        private fun buildMesh() {
            meshKeyBuilt = meshKey()
            val lens = mode.lens
            val flat = lens == VrLens.NORMAL
            val lonSteps = if (flat) 1 else 64
            val latSteps = if (flat) 1 else 32
            val half = mode.halfSphere
            val lonSpan = if (half) Math.PI else 2 * Math.PI
            val eyeAspect = eyeVideoAspect()
            val vb = ByteBuffer.allocateDirect((lonSteps + 1) * (latSteps + 1) * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (j in 0..latSteps) {
                for (i in 0..lonSteps) {
                    if (flat) {
                        // Kameranın önünde 1 birim uzakta, 1.6 birim genişliğinde sanal ekran.
                        val w = 1.6f
                        val h = w / eyeAspect
                        val x = (i - 0.5f) * w
                        val y = (0.5f - j) * h
                        vb.put(x); vb.put(y); vb.put(-1f)
                        vb.put(i.toFloat()); vb.put(1f - j.toFloat())
                        continue
                    }
                    val lat = Math.PI / 2 - Math.PI * j / latSteps
                    val lon = -lonSpan / 2 + lonSpan * i / lonSteps
                    val x = (cos(lat) * sin(lon)).toFloat()
                    val y = sin(lat).toFloat()
                    val z = (-cos(lat) * cos(lon)).toFloat()
                    vb.put(x); vb.put(y); vb.put(z)
                    if (lens == VrLens.FISHEYE) {
                        // Eşit uzaklıklı (equidistant) 180° balık gözü: görüntü dairesi yüksekliği doldurur.
                        val theta = Math.acos((-z).toDouble().coerceIn(-1.0, 1.0))
                        val r = theta / Math.PI
                        val phi = Math.atan2(y.toDouble(), x.toDouble())
                        vb.put((0.5 + r * cos(phi) / eyeAspect).toFloat())
                        vb.put((0.5 + r * sin(phi)).toFloat())
                    } else {
                        vb.put(i.toFloat() / lonSteps)
                        vb.put(1f - j.toFloat() / latSteps)
                    }
                }
            }
            val ib = ByteBuffer.allocateDirect(lonSteps * latSteps * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (j in 0 until latSteps) {
                for (i in 0 until lonSteps) {
                    val a = (j * (lonSteps + 1) + i).toShort()
                    val b = (a + 1).toShort()
                    val c = (a + lonSteps + 1).toShort()
                    val d = (c + 1).toShort()
                    ib.put(a); ib.put(c); ib.put(b)
                    ib.put(b); ib.put(c); ib.put(d)
                }
            }
            vb.position(0)
            ib.position(0)
            vertices = vb
            indices = ib
            indexCount = lonSteps * latSteps * 6
        }

        /** Fotoğraf modu: bitmap'i dokuya çizer. */
        fun drawPhoto() {
            val b = photo ?: return
            val tex = currentTexture ?: return
            runCatching { tex.drawBitmap(b) }
            requestRender()
        }
    }

    private companion object {
        const val DETACH_WAIT_MS = 2000L

        /** Motor çağrıları: mpv beklerken ana ve GL iş parçacığı durmaz. */
        val outputWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "vr360-output").apply { isDaemon = true } }

        /** Kare bildirimleri: ana iş parçacığı meşgulken de sürer. */
        val frameHandler: Handler by lazy { Handler(HandlerThread("vr360-frames").apply { start() }.looper) }
    }
}
