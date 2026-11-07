package org.openplayer.app

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.flow.SharedFlow

/**
 * OpenPlayer'ın tek oynatma motoru (libmpv). Video, müzik ve 360° aynı örneği paylaşır;
 * örnek uygulamaya aittir, böylece arka plan, PiP ve ekran döndürmede oynatma kesilmez.
 * Örneği [MpvEngines.get] ile alın.
 *
 * İş parçacığı: yöntemler her iş parçacığından çağrılabilir. Dinleyiciler, [observe] geri çağrıları
 * ve [events] ana iş parçacığında teslim edilir.
 *
 * Kurallar (emülatörde ölçüldü, atlanırsa mpv kilitlenir veya özellik sessizce çalışmaz):
 * 1. `vo`, `hwdec` ve `vf` özelliklerini [setProperty] ile DEĞİŞTİRMEYİN. Canlı MediaCodec çözücüsü
 *    varken `vo` ya da `hwdec` değişimi mpv çekirdeğini geri dönmemek üzere kilitler. Bunun için
 *    [attachSurface], [detachSurface], [setHardwareDecoding] ve [setVideoFilter] var.
 * 2. Donanım karesi (`mediacodec`) yazılım filtresine giremez: `vf` hata vermeden etkisiz kalır.
 *    [setVideoFilter] gerektiğinde `mediacodec-copy` kipine kendisi geçer.
 * 3. `af` serbesttir: `lavfi=[...]` zinciri oynarken değiştirilebilir. AAR'da `rubberband` YOK;
 *    perde için `asetrate` + `aresample` + `atempo` ya da `scaletempo2` kullanın.
 * 4. Düşen kare sayacı `frame-drop-count`tur; `vo-drop-frame-count` bu sürümde boş döner.
 * 5. `content://` adresi mpv'ye verilmez; [open] Uri'yi kendisi çözer. SMB/FTP/WebDAV yerel
 *    HTTP vekili üzerinden `http://127.0.0.1:...` olarak açılır; mpv Range isteği gönderir.
 */
interface MpvEngine {

    /** Olay akışı: bkz. [MpvEvent]. Tek atışlıktır, geçmiş olaylar tekrar gelmez. */
    val events: SharedFlow<MpvEvent>

    /** Coroutine kullanmayan yerler (servis, etkinlik) için [events] karşılığı. */
    fun addListener(listener: (MpvEvent) -> Unit)
    fun removeListener(listener: (MpvEvent) -> Unit)

    val isReleased: Boolean
    /** Oynuyor: duraklatılmamış, açık dosya var ve tamponda beklemiyor. */
    val isPlaying: Boolean
    /** Kullanıcı niyeti: tamponda beklerken de true kalır. */
    val playWhenReady: Boolean
    val positionMs: Long
    /** Bilinmiyorsa 0. */
    val durationMs: Long
    val speed: Float

    /**
     * Dosyayı açar, varsa öncekinin yerine geçer. [uri]: dosya yolu, `file://`, `content://`,
     * `http(s)://`, `rtsp://` ya da yerel vekil adresi. Sonuç [MpvEvent.FileLoaded] veya
     * [MpvEvent.Error] ile gelir.
     */
    fun open(uri: String, startMs: Long = 0L, paused: Boolean = false)

    /** Dosyayı kapatır; motor kullanılabilir kalır. */
    fun stop()

    fun play()
    fun pause()

    /** [exact] false ise en yakın anahtar kareye gider (sürüklerken daha hızlı). */
    fun seekTo(positionMs: Long, exact: Boolean = true)

    /** 1.0 = normal. */
    fun setSpeed(speed: Float)

    /** Yüzde: 100 = normal, 200'e kadar artırılabilir. */
    fun setVolume(percent: Int)

    /** Ham mpv komutu, örn. `command("sub-add", yol, "select")`. Başarısızlık sessizdir. */
    fun command(vararg args: String)

    /** Ham mpv özelliği yazar. `vo`, `hwdec`, `vf` için sınıf açıklamasındaki kurala bakın. */
    fun setProperty(name: String, value: String)

    /** Ham mpv özelliği okur; yoksa ya da dosya açık değilse null. */
    fun getProperty(name: String): String?

    /** Düğüm özellikleri için JSON metni (`track-list`, `chapter-list`, `metadata`...). */
    fun getPropertyJson(name: String): String?

    /**
     * Özellik değişimlerini izler. Geri çağrı yeni değeri metin olarak alır (yoksa null) ve
     * kayıttan hemen sonra güncel değerle bir kez çalışır. Durdurmak için dönen nesneyi kapatın.
     */
    fun observe(property: String, callback: (String?) -> Unit): AutoCloseable

    /** Parça listesi; dosya açık değilse boş. */
    fun tracks(): List<MpvTrack>

    /**
     * Görüntü yüzeyini bağlar. Herhangi bir [Surface] olabilir; `Surface(SurfaceTexture)` de
     * çalışır (360° yolu, OES doku ile doğrulandı). Boyut değişince yeniden çağırın.
     */
    fun attachSurface(surface: Surface, width: Int, height: Int)

    /**
     * Yüzeyi güvenle ayırır, yüzey yok edilmeden önce çağrılmalıdır. Ses sürer (arka plan oynatma);
     * [attachSurface] ile görüntü aynı konumdan geri gelir.
     */
    fun detachSurface()

    /** [value]: `mediacodec,mediacodec-copy,no`, `mediacodec-copy,no` ya da `no`. */
    fun setHardwareDecoding(value: String)

    /** Görüntü filtresi, örn. `lavfi=[hflip]`; boş metin kaldırır. Sınıf açıklaması kural 2. */
    fun setVideoFilter(filter: String)

    /** Motoru kapatır; sonraki [MpvEngines.get] yeni örnek kurar. */
    fun release()
}

/** Motordan gelen olaylar. */
sealed class MpvEvent {
    /** Yeni dosya yüklenmeye başladı. */
    data class FileStarted(val uri: String?) : MpvEvent()
    /** Dosya açıldı: süre ve parçalar artık okunabilir. */
    data object FileLoaded : MpvEvent()
    /** Sarma ya da açılış sonrası oynatma yeniden başladı. */
    data object PlaybackRestarted : MpvEvent()
    data class Position(val ms: Long) : MpvEvent()
    data class Duration(val ms: Long) : MpvEvent()
    data class PlayingChanged(val playing: Boolean) : MpvEvent()
    data class Buffering(val active: Boolean) : MpvEvent()
    data object TracksChanged : MpvEvent()
    data class VideoSizeChanged(val width: Int, val height: Int, val rotation: Int) : MpvEvent()
    /** Dosya bitti ya da kapandı. Doğal sonda [reason] [EndReason.EOF] olur. */
    data class FileEnded(val reason: EndReason) : MpvEvent()
    /** Dosya açılamadı ya da oynatma hatayla kesildi. */
    data class Error(val message: String) : MpvEvent()
}

enum class EndReason { EOF, STOP, QUIT, ERROR, REDIRECT }

enum class MpvTrackType { VIDEO, AUDIO, SUB }

/** `track-list` kaydı. [id], `vid`/`aid`/`sid` özelliğine yazılan parça numarasıdır. */
data class MpvTrack(
    val id: Int,
    val type: MpvTrackType,
    val title: String?,
    val language: String?,
    val codec: String?,
    val selected: Boolean,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val channels: Int = 0,
    val sampleRate: Int = 0,
) {
    /** VobSub, PGS gibi görüntü tabanlı altyazı: metni okunamaz, çevrilemez. */
    val isImageSubtitle: Boolean
        get() = type == MpvTrackType.SUB && codec in IMAGE_SUB_CODECS

    private companion object {
        val IMAGE_SUB_CODECS = setOf("dvd_subtitle", "dvdsub", "hdmv_pgs_subtitle", "pgssub", "dvb_subtitle", "dvbsub", "xsub")
    }
}

/** Uygulama genelindeki tek motor örneğine erişim. */
object MpvEngines {
    /** Gerçek motor Faz 1'de buraya bağlanır. */
    internal var factory: ((Context) -> MpvEngine)? = null

    @Volatile var current: MpvEngine? = null
        internal set

    @Synchronized
    fun get(context: Context): MpvEngine {
        current?.takeIf { !it.isReleased }?.let { return it }
        val make = factory ?: error("mpv motoru henüz bağlanmadı")
        return make(context.applicationContext).also { current = it }
    }
}
