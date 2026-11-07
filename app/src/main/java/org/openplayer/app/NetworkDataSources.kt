package org.openplayer.app

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource // MPV-REMOVE
import androidx.media3.datasource.ResolvingDataSource // MPV-REMOVE
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.net.ftp.FTPClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.hierynomus.smbj.share.File as SmbFile

// MPV-REMOVE: PlayerHolder silinince aşağıdaki sınıfı ve yukarıdaki iki Media3 satırını silin.
class NetworkDataSourceFactory(private val context: Context, private val base: DataSource.Factory) : DataSource.Factory { // MPV-REMOVE
    override fun createDataSource(): DataSource = ResolvingDataSource.Factory(base) { spec -> // MPV-REMOVE
        spec.withUri(Uri.parse(NetworkPlayback.resolve(context, spec.uri))) // MPV-REMOVE
    }.createDataSource() // MPV-REMOVE

    companion object { // MPV-REMOVE
        fun withWebDavAuth(context: Context, upstream: DataSource.Factory): DataSource.Factory = upstream.also { context.hashCode() } // MPV-REMOVE
    } // MPV-REMOVE
} // MPV-REMOVE

/**
 * Oynatılacak adresi mpv'nin açabileceği adrese çevirir. SMB, FTP ve kimlik bilgili WebDAV yerel
 * [LocalStreamProxy] üzerinden gider; parola uygulamada kalır, mpv'ye ve günlüğüne hiç ulaşmaz.
 * Diğer adresleri (http, https, HLS, DASH, rtsp, yerel dosya) mpv kendisi açar.
 *
 * Yalnız mpv'ye verilen adres değişir: altyazı bulma, OpenSubtitles, kaldığı yerden devam ve benzerleri
 * özgün `smb://`, `ftp://`, `http://` adresiyle çalışmaya devam etmelidir.
 */
object NetworkPlayback {

    /** Manifest göreli adresler içerir, tek dosyalık vekilden okunamaz. */
    private val MANIFEST_EXTENSIONS = setOf("m3u8", "m3u", "mpd")

    private var proxy: LocalStreamProxy? = null

    /** Vekildeki son kaynak hatası; mpv yalnız "açılamadı" der, kullanıcıya bu metin gösterilir. */
    @Volatile var lastError: String? = null
        private set

    /** [resolve] gibi, ayrıca mpv'nin HTTP kullanıcı ajanını ayarlar. Her `open` öncesi çağırın. */
    fun prepare(context: Context, engine: MpvEngine, uri: Uri): String {
        engine.setProperty("user-agent", userAgent(context))
        return resolve(context, uri)
    }

    /** mpv'ye verilecek adres. Aynı dosya için aynı adresi döndürür. Ağ işi yapmaz. */
    @Synchronized
    fun resolve(context: Context, uri: Uri): String {
        val app = context.applicationContext
        val factory = when (uri.scheme?.lowercase()) {
            "smb" -> smbFactory(app, uri)
            "ftp" -> ftpFactory(app, uri)
            "http", "https" -> webDavFactory(app, uri)
            else -> null
        } ?: return uri.toString()
        lastError = null
        return runningProxy().register(uri.toString(), uri.lastPathSegment.orEmpty(), factory)
    }

    /**
     * Vekili kapatır, süren bağlantıları ve sunucu soketlerini bırakır. Oynatma oturumu bitince
     * (`engine.stop()` ya da `engine.release()` sonrası) çağırın. Ana iş parçacığından çağrılabilir.
     */
    @Synchronized
    fun stop() {
        proxy?.close()
        proxy = null
    }

    private fun userAgent(context: Context) = AppSettings.get(context).httpUserAgent.ifBlank { "OpenPlayer/1.0" }

    private fun runningProxy(): LocalStreamProxy {
        proxy?.takeIf { !it.isClosed }?.let { return it }
        // Soket açmak ana iş parçacığında StrictMode'a takılabilir.
        val task = FutureTask { LocalStreamProxy() }
        Thread(task, "stream-proxy-start").start()
        return task.get().also { created ->
            created.onSourceError = { e -> lastError = e.message ?: e.javaClass.simpleName }
            proxy = created
        }
    }

    private fun smbFactory(context: Context, uri: Uri): StreamSourceFactory {
        val server = NetworkStore.find(context, uri)
            ?: NetServer(protocol = NetProtocol.SMB, name = "", host = uri.host.orEmpty())
        val port = if (uri.port > 0) uri.port else 445
        val segments = uri.pathSegments.toList()
        return StreamSourceFactory { SmbStreamSource.open(server, port, segments) }
    }

    private fun ftpFactory(context: Context, uri: Uri): StreamSourceFactory {
        val stored = NetworkStore.find(context, uri)
            ?: NetServer(protocol = NetProtocol.FTP, name = "", host = uri.host.orEmpty(), port = uri.port.coerceAtLeast(0))
        val server = stored.copy(port = if (uri.port > 0) uri.port else stored.port)
        val path = uri.path
        return StreamSourceFactory {
            FtpStreamSource.open(path ?: throw IOException("FTP adresinde dosya yolu yok")) { NetworkBrowser.connectFtp(server) }
        }
    }

    /** Yalnız kullanıcı adı kayıtlı sunucu vekilden geçer; kimlik doğrulamasız adresi mpv doğrudan açar. */
    private fun webDavFactory(context: Context, uri: Uri): StreamSourceFactory? {
        val server = NetworkStore.find(context, uri)?.takeIf { it.user.isNotEmpty() } ?: return null
        if (uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase() in MANIFEST_EXTENSIONS) return null
        return WebDavSourceFactory(uri.toString(), Credentials.basic(server.user, server.pass), userAgent(context))
    }
}

/** smbj ile tek dosya okur. Bağlantı her istekte yeniden kurulur. */
internal class SmbStreamSource private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val file: SmbFile,
    override val size: Long,
) : StreamSource {

    override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (offset >= size) return -1
        val read = try {
            file.read(buffer, offset, bufferOffset, length)
        } catch (e: Exception) {
            throw IOException(Tx.s(R.string.networkdat_004, e.message), e)
        }
        return if (read > 0) read else -1
    }

    /** Dosya kapatma isteği sunucu yanıtını bekler; burası tıkanmış okumayı da uyandırmalı, soket kesilir. */
    override fun close() {
        runCatching { connection.close(true) }
        runCatching { client.close() }
    }

    companion object {
        /** [segments]: adresin yol parçaları; ilki paylaşım adıdır. */
        fun open(server: NetServer, port: Int, segments: List<String>): SmbStreamSource {
            if (segments.isEmpty()) throw IOException(Tx.s(R.string.networkdat_001))
            val client = SMBClient()
            var connection: Connection? = null
            try {
                val conn = client.connect(server.host, port).also { connection = it }
                val session = conn.authenticate(NetworkBrowser.smbAuth(server))
                val share = session.connectShare(segments[0]) as DiskShare
                val file = share.openFile(
                    segments.drop(1).joinToString("\\"),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                return SmbStreamSource(client, conn, file, file.fileInformation.standardInformation.endOfFile)
            } catch (e: Exception) {
                runCatching { connection?.close(true) }
                runCatching { client.close() }
                throw IOException(Tx.s(R.string.networkdat_002, e.message), e)
            }
        }
    }
}

/**
 * Akış olarak okunan kaynak: istenen konum akışın konumu değilse aktarımı kapatıp o konumdan yeniden açar.
 * Vekil bir istekte sırayla okuduğu için aktarım istek başına bir kez açılır.
 */
internal abstract class SequentialStreamSource : StreamSource {
    private val closed = AtomicBoolean(false)
    @Volatile private var stream: InputStream? = null
    private var position = 0L

    /** [offset] konumundan başlayan aktarımı açar. */
    protected abstract fun openTransfer(offset: Long): InputStream

    /** Aktarımı bırakır. Okuma sürerken başka iş parçacığından çağrılabilir, hata atmamalıdır. */
    protected abstract fun releaseTransfer()

    final override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        var current = stream
        if (current == null || offset != position) {
            releaseTransfer()
            current = openTransfer(offset)
            stream = current
            position = offset
        }
        if (closed.get()) {
            releaseTransfer()
            throw IOException("Kaynak kapatıldı")
        }
        val read = current.read(buffer, bufferOffset, length)
        if (read > 0) position += read
        return read
    }

    final override fun close() {
        if (closed.compareAndSet(false, true)) releaseTransfer()
    }
}

/**
 * Bir FTP bağlantısı aynı anda tek aktarım taşır, bu yüzden her örneğin kendi bağlantısı vardır.
 * Konum REST ile verilir; 4 GB üstü dosyada da çalışır.
 */
internal class FtpStreamSource private constructor(
    private val path: String,
    private val connect: () -> FTPClient,
    client: FTPClient,
    override val size: Long,
) : SequentialStreamSource() {

    @Volatile private var ftp: FTPClient? = client
    @Volatile private var transfer: InputStream? = null

    override fun openTransfer(offset: Long): InputStream {
        val client = ftp ?: prepared(connect()).also { ftp = it }
        client.restartOffset = offset
        val stream = client.retrieveFileStream(path)
            ?: throw IOException(Tx.s(R.string.networkdat_005, client.replyString.orEmpty().trim()))
        transfer = stream
        return stream
    }

    /** Yarım kalan aktarımdan sonra denetim bağlantısı temiz değildir; ABOR beklemek yerine kapatılır. */
    override fun releaseTransfer() {
        runCatching { transfer?.close() }
        transfer = null
        runCatching { ftp?.disconnect() }
        ftp = null
    }

    companion object {
        private const val DATA_TIMEOUT_MS = 30_000

        // java.time.Duration alan sürüm minSdk 24'te yok.
        @Suppress("DEPRECATION")
        private fun prepared(client: FTPClient) = client.apply { setDataTimeout(DATA_TIMEOUT_MS) }

        fun open(path: String, connect: () -> FTPClient): FtpStreamSource {
            val client = prepared(connect())
            try {
                val size = client.getSize(path)?.trim()?.toLongOrNull()
                    ?: runCatching { client.listFiles(path).singleOrNull()?.size?.takeIf { it >= 0 } }.getOrNull()
                    ?: StreamSource.UNKNOWN_SIZE
                return FtpStreamSource(path, connect, client, size)
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                throw if (e is IOException) e else IOException(Tx.s(R.string.networkdat_006, e.message), e)
            }
        }
    }
}

/**
 * WebDAV dosyasını OkHttp ile okur. Boyut bir kez sorulur. OkHttp başka sunucuya yönlendirmede
 * Authorization başlığını düşürür; https'ten http'ye düşüren yönlendirme izlenmez.
 */
internal class WebDavSourceFactory(
    private val url: String,
    private val authorization: String?,
    private val userAgent: String,
    private val client: OkHttpClient = sharedClient,
) : StreamSourceFactory {

    @Volatile private var knownSize: Long? = null

    override fun open(): StreamSource {
        val size = knownSize ?: probeSize().also { knownSize = it }
        return Source(size)
    }

    private fun request(): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept-Encoding", "identity")
        .apply { if (authorization != null) header("Authorization", authorization) }

    private fun checked(response: Response): Response {
        if (response.code == 401) {
            response.close()
            throw IOException(Tx.s(R.string.networkbro_003))
        }
        return response
    }

    private fun probeSize(): Long {
        checked(client.newCall(request().head().build()).execute()).use { response ->
            val length = response.header("Content-Length")?.toLongOrNull()
            if (response.isSuccessful && length != null) return length
        }
        // HEAD desteklemeyen ya da boyut bildirmeyen sunucu.
        checked(client.newCall(request().header("Range", "bytes=0-0").build()).execute()).use { response ->
            return when (response.code) {
                206 -> response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    ?: StreamSource.UNKNOWN_SIZE
                200 -> response.header("Content-Length")?.toLongOrNull() ?: StreamSource.UNKNOWN_SIZE
                else -> throw IOException(Tx.s(R.string.networkbro_004, response.code))
            }
        }
    }

    private inner class Source(override val size: Long) : SequentialStreamSource() {
        @Volatile private var call: Call? = null
        @Volatile private var response: Response? = null

        override fun openTransfer(offset: Long): InputStream {
            val builder = request()
            if (offset > 0) builder.header("Range", "bytes=$offset-")
            val started = client.newCall(builder.build()).also { call = it }
            val opened = checked(started.execute()).also { response = it }
            return when {
                opened.code == 206 -> opened.body!!.byteStream()
                opened.code == 200 -> opened.body!!.let { body ->
                    // Sunucu Range desteklemiyor: baştan okunup atlanır.
                    body.source().skip(offset)
                    body.byteStream()
                }
                opened.code == 416 && offset > 0 -> ByteArrayInputStream(ByteArray(0))
                else -> throw IOException(Tx.s(R.string.networkbro_004, opened.code))
            }
        }

        override fun releaseTransfer() {
            runCatching { call?.cancel() }
            runCatching { response?.close() }
            call = null
            response = null
        }
    }

    private companion object {
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .build()
        }
    }
}
