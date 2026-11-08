package org.openplayer.app

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vekilin okuduğu tek bir uzak dosya. Her HTTP isteği için [StreamSourceFactory] yeni bir örnek açar,
 * bu yüzden bir örneği aynı anda yalnız bir iş parçacığı okur. Yalnız [close] başka iş parçacığından
 * gelebilir: istemci koptuğunda bekleyen [read] çağrısını uyandırmak için çağrılır.
 */
interface StreamSource : Closeable {
    /** Toplam bayt; bilinmiyorsa [UNKNOWN_SIZE]. */
    val size: Long

    /** [offset] konumundan en çok [length] bayt okur. Dosya sonunda -1 döner. */
    @Throws(IOException::class)
    fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int

    /** Bağlantıyı ve soketleri bırakır. Okuma sürerken çağrılabilir, hata atmaz. */
    override fun close()

    companion object {
        const val UNKNOWN_SIZE = -1L
    }
}

/** Her istekte bağlantısı ayrı yeni bir [StreamSource] açar. Bağlantı burada kurulur, engelleyicidir. */
fun interface StreamSourceFactory {
    @Throws(IOException::class)
    fun open(): StreamSource
}

/** `Range` başlığının yorumu. */
sealed class HttpRange {
    /** Başlık yok, geçersiz ya da çoklu aralık: dosyanın tamamı 200 ile gönderilir. */
    data object Full : HttpRange()

    /** [start] ve [end] dahil, 206 ile gönderilir. */
    data class Partial(val start: Long, val end: Long) : HttpRange() {
        val length: Long get() = end - start + 1
    }

    /** Aralık dosyanın dışında: 416. */
    data object Unsatisfiable : HttpRange()

    companion object {
        private val SINGLE = Regex("""^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$""", RegexOption.IGNORE_CASE)

        /** Çok uzun sayı hata değildir: dosyadan büyük bir değer gibi davranılır. */
        private fun number(digits: String) = digits.toLongOrNull() ?: Long.MAX_VALUE

        /** Boyut bilinmiyorsa aralık hesaplanamaz, tamamı gönderilir. */
        fun parse(header: String?, size: Long): HttpRange {
            if (header == null || size < 0) return Full
            val match = SINGLE.matchEntire(header) ?: return Full
            val (first, last) = match.destructured
            if (first.isEmpty()) {
                if (last.isEmpty()) return Full
                val suffix = number(last)
                if (suffix == 0L || size == 0L) return Unsatisfiable
                return Partial(if (suffix >= size) 0 else size - suffix, size - 1)
            }
            val start = number(first)
            val end = if (last.isEmpty()) Long.MAX_VALUE else number(last)
            if (end < start) return Full
            if (start >= size) return Unsatisfiable
            return Partial(start, minOf(end, size - 1))
        }
    }
}

/**
 * Yalnız 127.0.0.1'de dinleyen, Range destekli küçük HTTP sunucusu. SMB/FTP/WebDAV dosyalarını mpv'ye
 * `http://127.0.0.1:port/<jeton>/<akış>/<ad>` adresiyle sunar. Parola ne adrese ne de günlüğe yazılır;
 * kimlik bilgisi uygulamada kalır. Jetonsuz istek 403 alır, başka uygulamalar dosyalara ulaşamaz.
 *
 * Her bağlantı tek istek taşır (`Connection: close`) ve kendi [StreamSource] örneğini açar: mpv sararken
 * eski bağlantıyı kapatıp yenisini açar, FTP bağlantısı da aynı anda tek aktarım taşır. Her bağlantıda bir
 * izleyici iş parçacığı istemcinin kopmasını bekler ve kaynağı hemen kapatır; böylece sunucudan yanıt
 * beklerken tıkanmış okuma da uyanır.
 *
 * Kapatma ana iş parçacığından yapılabilir, ağ işi orada çalışmaz.
 */
class LocalStreamProxy(maxConnections: Int = DEFAULT_MAX_CONNECTIONS) : Closeable {

    private class Stream(val id: String, val url: String, val contentType: String, val factory: StreamSourceFactory)

    private val server = ServerSocket(0, BACKLOG, LOOPBACK)
    private val token = randomHex(TOKEN_BYTES)
    private val permits = Semaphore(maxConnections)
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val closed = AtomicBoolean(false)

    /** Anahtar: [register] çağıranın verdiği kimlik. En eski kayıt en başta. */
    private val streams = LinkedHashMap<String, Stream>()

    /** Kaynak açılamadığında ya da okuma hatayla kesildiğinde çağrılır; istemcinin kopması hata değildir. */
    @Volatile var onSourceError: ((Exception) -> Unit)? = null

    val port: Int = server.localPort

    val isClosed: Boolean get() = closed.get()

    /** Sürmekte olan bağlantı sayısı. */
    val activeConnections: Int get() = connections.size

    init {
        thread("stream-proxy-accept") { acceptLoop() }
    }

    /**
     * Dosyayı kaydeder, mpv'ye verilecek adresi döndürür. Aynı [key] aynı adresi verir. En çok
     * [MAX_STREAMS] kayıt tutulur; düşen kaydın süren bağlantıları kesilmez, yalnız yeni istek 404 alır.
     * Böylece sıradaki dosyaya geçerken mpv'nin eski dosyadaki son okumaları hataya dönmez.
     *
     * @param fileName adresin son parçası; mpv dosya türü tahmini için kullanır. Kullanıcı adı ya da
     *   parola içermemelidir.
     */
    fun register(key: String, fileName: String, factory: StreamSourceFactory): String {
        synchronized(streams) {
            streams.remove(key)?.let { existing ->
                streams[key] = existing
                return existing.url
            }
            val id = randomHex(ID_BYTES)
            val name = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            val stream = Stream(id, "http://127.0.0.1:$port/$token/$id/$name", contentTypeFor(fileName), factory)
            streams[key] = stream
            while (streams.size > MAX_STREAMS) streams.remove(streams.keys.first())
            return stream.url
        }
    }

    /** Dinlemeyi bırakır, tüm bağlantıları keser, kaynakları kapatır. Tekrar çağrılabilir. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        synchronized(streams) { streams.clear() }
        // Soket kapanınca her bağlantının izleyicisi uyanır ve kaynağı kendi iş parçacığında kapatır.
        connections.forEach { it.disconnect() }
    }

    private fun find(id: String): Stream? = synchronized(streams) { streams.values.firstOrNull { it.id == id } }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            if (!socket.inetAddress.isLoopbackAddress) {
                runCatching { socket.close() }
            } else if (!permits.tryAcquire()) {
                runCatching {
                    socket.use { writeHead(it.getOutputStream(), 503, "Service Unavailable", contentLength = 0L) }
                }
            } else {
                val connection = Connection(socket)
                connections += connection
                if (closed.get()) connection.disconnect()
                thread("stream-proxy-conn") { connection.run() }
            }
        }
    }

    private inner class Connection(private val socket: Socket) {
        private val aborted = AtomicBoolean(false)
        private val sourceClosed = AtomicBoolean(false)
        @Volatile private var source: StreamSource? = null

        fun run() {
            try {
                serve()
            } catch (_: IOException) {
                // İstemci gitti ya da istek okunamadı; yanıt verilecek kimse yok.
            } finally {
                closeSource()
                disconnect()
                connections -= this
                permits.release()
            }
        }

        fun disconnect() {
            runCatching { socket.close() }
        }

        private fun closeSource() {
            val current = source ?: return
            if (sourceClosed.compareAndSet(false, true)) runCatching { current.close() }
        }

        /** Hata sonrası kesin kapanış (RST): istemci eksik dosyayı doğal son sanmasın. */
        private fun reset() {
            runCatching { socket.setSoLinger(true, 0) }
            disconnect()
        }

        private fun watchClient() {
            thread("stream-proxy-watch") {
                try {
                    val input = socket.getInputStream()
                    val sink = ByteArray(256)
                    while (input.read(sink) >= 0) {
                        // İstek gövdesi beklenmiyor, gelen atılır.
                    }
                } catch (_: IOException) {
                    // Soket kapandı.
                }
                aborted.set(true)
                closeSource()
            }
        }

        private fun serve() {
            socket.tcpNoDelay = true
            socket.soTimeout = HEADER_TIMEOUT_MS
            val output = socket.getOutputStream()
            val request = try {
                readRequest(socket.getInputStream())
            } catch (_: BadRequest) {
                writeHead(output, 400, "Bad Request", contentLength = 0L)
                return
            } ?: return

            if (!hasToken(request.segments.getOrNull(0))) {
                writeHead(output, 403, "Forbidden", contentLength = 0L)
                return
            }
            val isHead = request.method == "HEAD"
            if (!isHead && request.method != "GET") {
                writeHead(output, 405, "Method Not Allowed", "Allow: GET, HEAD", contentLength = 0L)
                return
            }
            val stream = request.segments.getOrNull(1)?.let { find(it) }
            if (stream == null) {
                writeHead(output, 404, "Not Found", contentLength = 0L)
                return
            }

            socket.soTimeout = 0
            watchClient()
            val opened = try {
                stream.factory.open()
            } catch (e: Exception) {
                if (!aborted.get()) {
                    onSourceError?.invoke(e)
                    writeHead(output, 502, "Bad Gateway", contentLength = 0L)
                }
                return
            }
            source = opened
            if (aborted.get()) return

            val size = opened.size
            val type = "Content-Type: ${stream.contentType}"
            val ranges = if (size >= 0) "Accept-Ranges: bytes" else "Accept-Ranges: none"
            // RFC 9110: Range yalnız GET içindir, HEAD tam boyutu bildirir.
            when (val range = if (isHead) HttpRange.Full else HttpRange.parse(request.range, size)) {
                HttpRange.Unsatisfiable ->
                    writeHead(output, 416, "Range Not Satisfiable", ranges, "Content-Range: bytes */$size", contentLength = 0L)
                HttpRange.Full -> {
                    writeHead(output, 200, "OK", type, ranges, contentLength = size)
                    if (!isHead) transfer(opened, output, 0L, size)
                }
                is HttpRange.Partial -> {
                    val contentRange = "Content-Range: bytes ${range.start}-${range.end}/$size"
                    writeHead(output, 206, "Partial Content", type, ranges, contentRange, contentLength = range.length)
                    transfer(opened, output, range.start, range.length)
                }
            }
        }

        /** [length] negatifse dosya sonuna kadar gönderir. */
        private fun transfer(from: StreamSource, output: OutputStream, start: Long, length: Long) {
            val buffer = ByteArray(BUFFER_SIZE)
            var position = start
            var remaining = if (length < 0) Long.MAX_VALUE else length
            while (remaining > 0) {
                val read = try {
                    from.read(position, buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                } catch (e: Exception) {
                    if (!aborted.get()) onSourceError?.invoke(e)
                    reset()
                    return
                }
                if (read <= 0) break
                output.write(buffer, 0, read)
                position += read
                remaining -= read
            }
            if (length >= 0 && remaining > 0) {
                if (!aborted.get()) onSourceError?.invoke(IOException("Kaynak beklenenden erken bitti"))
                reset()
            }
        }
    }

    private class BadRequest : IOException()

    private class Request(val method: String, val segments: List<String>, val range: String?)

    /** İstek başlığını okur. Bağlantı başlık bitmeden kapanırsa null döner. */
    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream(512)
        var lineLength = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                if (lineLength == 0) break
                lineLength = 0
            } else if (b != '\r'.code) {
                lineLength++
            }
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) throw BadRequest()
        }
        val lines = head.toString("ISO-8859-1").split('\n').map { it.trimEnd('\r') }
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[1].startsWith("/") || !parts[2].startsWith("HTTP/")) throw BadRequest()
        val range = lines.drop(1).firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')
        val path = parts[1].substringBefore('?').substringBefore('#')
        return Request(parts[0], path.split('/').drop(1), range?.trim())
    }

    private fun hasToken(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    /** [contentLength] negatifse başlık yazılmaz; gövde bağlantının kapanmasıyla biter. */
    private fun writeHead(output: OutputStream, status: Int, reason: String, vararg headers: String, contentLength: Long) {
        val text = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { text.append(it).append("\r\n") }
        if (contentLength >= 0) text.append("Content-Length: ").append(contentLength).append("\r\n")
        text.append("Connection: close\r\n\r\n")
        output.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun thread(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    companion object {
        /** mpv bir dosya için aynı anda birkaç bağlantıdan fazlasını açmaz; sınır kötüye kullanıma karşıdır. */
        const val DEFAULT_MAX_CONNECTIONS = 16
        const val MAX_STREAMS = 4

        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val BACKLOG = 16
        private const val TOKEN_BYTES = 32
        private const val ID_BYTES = 8
        private const val BUFFER_SIZE = 512 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val HEADER_TIMEOUT_MS = 10_000

        private val random = SecureRandom()

        private fun randomHex(bytes: Int): String =
            ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        private val CONTENT_TYPES = mapOf(
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
            "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
            "wmv" to "video/x-ms-wmv", "asf" to "video/x-ms-asf", "flv" to "video/x-flv",
            "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mts" to "video/mp2t",
            "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "vob" to "video/mpeg",
            "3gp" to "video/3gpp", "ogv" to "video/ogg",
        )

        fun contentTypeFor(fileName: String): String =
            CONTENT_TYPES[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }
}
