package org.openplayer.app

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalStreamProxyTest {

    /** Her konumun baytı konumdan hesaplanır; böylece 6 GB'lık dosya bellekte yer tutmaz. */
    private open class FakeSource(override val size: Long, private val failAt: Long = Long.MAX_VALUE) : StreamSource {
        val closes = AtomicInteger()
        val reading = AtomicInteger()
        @Volatile var overlappingRead = false

        override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            if (reading.incrementAndGet() > 1) overlappingRead = true
            try {
                if (offset >= failAt) throw IOException("kaynak koptu")
                if (size >= 0 && offset >= size) return -1
                var count = minOf(length, 64 * 1024)
                if (size >= 0) count = minOf(count.toLong(), size - offset).toInt()
                if (failAt != Long.MAX_VALUE) count = minOf(count.toLong(), failAt - offset).toInt()
                for (i in 0 until count) buffer[bufferOffset + i] = byteAt(offset + i)
                return count
            } finally {
                reading.decrementAndGet()
            }
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    /** İlk okumada close çağrılana dek bekler: yanıt vermeyen sunucu gibi. */
    private class StalledSource : FakeSource(1_000_000) {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            entered.countDown()
            released.await(30, TimeUnit.SECONDS)
            throw IOException("kapatıldı")
        }

        override fun close() {
            super.close()
            released.countDown()
        }
    }

    private class Reply(val head: String, val body: ByteArray) {
        val status: Int get() = head.lineSequence().first().split(' ')[1].toInt()
        fun header(name: String): String? = head.lineSequence().drop(1)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }

    private val proxy = LocalStreamProxy()
    private val sources = CopyOnWriteArrayList<FakeSource>()

    @After
    fun tearDown() {
        proxy.close()
    }

    private fun serve(size: Long, name: String = "film.mkv", make: () -> FakeSource = { FakeSource(size) }): String =
        proxy.register(name, name) { make().also { sources += it } }

    private fun target(url: String) = URI(url).rawPath

    private fun exchange(rawRequest: String): Reply = Socket("127.0.0.1", proxy.port).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().write(rawRequest.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        val all = ByteArrayOutputStream()
        runCatching { socket.getInputStream().copyTo(all) } // RST de yanıtın sonudur.
        val bytes = all.toByteArray()
        val split = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        Reply(String(bytes, 0, split, Charsets.ISO_8859_1), bytes.copyOfRange(split + 4, bytes.size))
    }

    private fun get(url: String, range: String? = null, method: String = "GET"): Reply =
        exchange("$method ${target(url)} HTTP/1.1\r\nHost: 127.0.0.1\r\n${range?.let { "Range: $it\r\n" }.orEmpty()}\r\n")

    private fun eventually(what: String, check: () -> Boolean) {
        val limit = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > limit) fail(what)
            Thread.sleep(10)
        }
    }

    private fun assertAllSourcesClosed() {
        eventually("kaynak kapanmadı") { sources.isNotEmpty() && sources.all { it.closes.get() >= 1 } }
        eventually("bağlantı sızdı") { proxy.activeConnections == 0 }
    }

    // ------------------------------------------------------------------ Range ayrıştırma

    @Test
    fun `range parse covers edges`() {
        assertEquals(HttpRange.Full, HttpRange.parse(null, 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRange.parse("bytes=0-", 100))
        assertEquals(HttpRange.Partial(10, 20), HttpRange.parse("bytes=10-20", 100))
        assertEquals(HttpRange.Partial(99, 99), HttpRange.parse("bytes=99-", 100))
        assertEquals(HttpRange.Partial(90, 99), HttpRange.parse("bytes=90-5000", 100))
        assertEquals(HttpRange.Partial(70, 99), HttpRange.parse("bytes=-30", 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRange.parse("bytes=-5000", 100))
        assertEquals(HttpRange.Partial(5, 5), HttpRange.parse("BYTES = 5 - 5", 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRange.parse("bytes=0-99999999999999999999999", 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRange.parse("bytes=-99999999999999999999999", 100))

        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=100-", 100))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=99999999999999999999999-", 100))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=-0", 100))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=0-", 0))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=-5", 0))

        assertEquals(HttpRange.Full, HttpRange.parse("bytes=20-10", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("bytes=-", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("bytes=0-1,5-6", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("items=0-1", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("bytes=a-b", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("bytes=0-5", StreamSource.UNKNOWN_SIZE))
    }

    @Test
    fun `range parse keeps sizes above 4 GB`() {
        val size = 6L * 1024 * 1024 * 1024
        assertEquals(HttpRange.Partial(5_000_000_000, size - 1), HttpRange.parse("bytes=5000000000-", size))
        assertEquals(HttpRange.Partial(size - 10, size - 1), HttpRange.parse("bytes=-10", size))
    }

    // ------------------------------------------------------------------ Yanıtlar

    @Test
    fun `whole file is 200 with length and type`() {
        val reply = get(serve(100_000))
        assertEquals(200, reply.status)
        assertEquals("100000", reply.header("Content-Length"))
        assertEquals("video/x-matroska", reply.header("Content-Type"))
        assertEquals("bytes", reply.header("Accept-Ranges"))
        assertEquals("close", reply.header("Connection"))
        assertArrayEquals(expected(0, 100_000), reply.body)
        assertAllSourcesClosed()
    }

    @Test
    fun `open range is 206`() {
        val reply = get(serve(1000), "bytes=990-")
        assertEquals(206, reply.status)
        assertEquals("bytes 990-999/1000", reply.header("Content-Range"))
        assertEquals("10", reply.header("Content-Length"))
        assertArrayEquals(expected(990, 10), reply.body)
    }

    @Test
    fun `closed range is 206 and stops at its end`() {
        val reply = get(serve(1_000_000), "bytes=100000-700000")
        assertEquals(206, reply.status)
        assertEquals("bytes 100000-700000/1000000", reply.header("Content-Range"))
        assertArrayEquals(expected(100_000, 600_001), reply.body)
    }

    @Test
    fun `suffix range is 206`() {
        val reply = get(serve(1000), "bytes=-3")
        assertEquals(206, reply.status)
        assertEquals("bytes 997-999/1000", reply.header("Content-Range"))
        assertArrayEquals(expected(997, 3), reply.body)
    }

    @Test
    fun `range past end is 416 with size`() {
        val reply = get(serve(1000), "bytes=1000-")
        assertEquals(416, reply.status)
        assertEquals("bytes */1000", reply.header("Content-Range"))
        assertEquals("0", reply.header("Content-Length"))
        assertEquals(0, reply.body.size)
        assertAllSourcesClosed()
    }

    @Test
    fun `multiple ranges fall back to 200`() {
        val reply = get(serve(1000), "bytes=0-1,5-6")
        assertEquals(200, reply.status)
        assertEquals(1000, reply.body.size)
    }

    @Test
    fun `head reports size without body and ignores range`() {
        val reply = get(serve(1000), "bytes=5-6", "HEAD")
        assertEquals(200, reply.status)
        assertEquals("1000", reply.header("Content-Length"))
        assertEquals(0, reply.body.size)
        assertAllSourcesClosed()
    }

    @Test
    fun `empty file is 200`() {
        val reply = get(serve(0))
        assertEquals(200, reply.status)
        assertEquals("0", reply.header("Content-Length"))
        assertEquals(0, reply.body.size)
    }

    @Test
    fun `file above 4 GB is served by long offsets`() {
        val size = 6L * 1024 * 1024 * 1024
        val start = 5_000_000_000L
        val reply = get(serve(size), "bytes=$start-${start + 99_999}")
        assertEquals(206, reply.status)
        assertEquals("bytes $start-${start + 99_999}/$size", reply.header("Content-Range"))
        assertArrayEquals(expected(start, 100_000), reply.body)
    }

    @Test
    fun `unknown size streams to end without length`() {
        val url = proxy.register("x", "x.ts") {
            object : FakeSource(StreamSource.UNKNOWN_SIZE) {
                override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int =
                    if (offset >= 5000) -1 else super.read(offset, buffer, bufferOffset, minOf(length.toLong(), 5000 - offset).toInt())
            }
        }
        val reply = get(url, "bytes=10-")
        assertEquals(200, reply.status)
        assertNull(reply.header("Content-Length"))
        assertEquals("none", reply.header("Accept-Ranges"))
        assertArrayEquals(expected(0, 5000), reply.body)
    }

    // ------------------------------------------------------------------ Jeton ve istek denetimi

    @Test
    fun `missing or wrong token is 403 and never opens the source`() {
        val url = serve(1000)
        val path = target(url)
        val parts = path.split('/')
        assertEquals(403, exchange("GET / HTTP/1.1\r\n\r\n").status)
        assertEquals(403, exchange("GET /${parts[2]}/${parts[3]} HTTP/1.1\r\n\r\n").status)
        val wrong = parts[1].replaceRange(0, 1, if (parts[1][0] == 'a') "b" else "a")
        assertEquals(403, exchange("GET /$wrong/${parts[2]}/${parts[3]} HTTP/1.1\r\n\r\n").status)
        assertEquals(403, exchange("POST /$wrong/${parts[2]} HTTP/1.1\r\n\r\n").status)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `tokens differ between sessions`() {
        LocalStreamProxy().use { other ->
            val mine = target(proxy.register("a", "a.mp4") { FakeSource(1) }).split('/')[1]
            val theirs = target(other.register("a", "a.mp4") { FakeSource(1) }).split('/')[1]
            assertEquals(64, mine.length)
            assertNotEquals(mine, theirs)
        }
    }

    @Test
    fun `right token with unknown stream is 404`() {
        val token = target(serve(1000)).split('/')[1]
        assertEquals(404, exchange("GET /$token/yok/film.mkv HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun `other methods are 405`() {
        val reply = exchange("PUT ${target(serve(1000))} HTTP/1.1\r\n\r\n")
        assertEquals(405, reply.status)
        assertEquals("GET, HEAD", reply.header("Allow"))
    }

    @Test
    fun `malformed and oversized requests are 400`() {
        assertEquals(400, exchange("nonsense\r\n\r\n").status)
        // Sunucunun okumayı bıraktığı boyda: okunmamış veri kalırsa kapanış yanıtı silebilir.
        val prefix = "GET / HTTP/1.1\r\nX: "
        assertEquals(400, exchange(prefix + "a".repeat(16 * 1024 + 1 - prefix.length)).status)
    }

    @Test
    fun `url carries no secret and escapes the name`() {
        val url = proxy.register("smb://kullanici:parola@nas/film", "Örnek film #1 100%.mkv") { FakeSource(10) }
        assertTrue(url.startsWith("http://127.0.0.1:${proxy.port}/"))
        assertFalse("parola" in url)
        assertFalse("kullanici" in url)
        assertFalse(' ' in url)
        assertFalse('#' in url)
        assertEquals(200, get(url).status)
    }

    @Test
    fun `listens only on loopback`() {
        val outside = java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && !it.isLinkLocalAddress }
            ?: return
        try {
            Socket().use { it.connect(InetSocketAddress(outside, proxy.port), 1000) }
            fail("yerel ağdan bağlanıldı")
        } catch (_: IOException) {
            // Beklenen.
        }
    }

    // ------------------------------------------------------------------ Kaynak sorunları

    @Test
    fun `source that fails to open is 502 and reported`() {
        val errors = CopyOnWriteArrayList<Exception>()
        proxy.onSourceError = { errors += it }
        val url = proxy.register("x", "x.mkv") { throw IOException("giriş reddedildi") }
        assertEquals(502, get(url).status)
        assertEquals("giriş reddedildi", errors.single().message)
        eventually("bağlantı sızdı") { proxy.activeConnections == 0 }
    }

    @Test
    fun `mid transfer failure truncates and closes the source`() {
        val errors = CopyOnWriteArrayList<Exception>()
        proxy.onSourceError = { errors += it }
        val url = serve(1_000_000) { FakeSource(1_000_000, failAt = 100_000) }
        val reply = get(url)
        assertEquals("1000000", reply.header("Content-Length"))
        assertTrue(reply.body.size <= 100_000)
        assertEquals("kaynak koptu", errors.single().message)
        assertAllSourcesClosed()
    }

    @Test
    fun `source ending early is not a clean end`() {
        val errors = CopyOnWriteArrayList<Exception>()
        proxy.onSourceError = { errors += it }
        val url = proxy.register("x", "x.mkv") {
            object : FakeSource(1000) {
                override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int =
                    if (offset >= 500) -1 else super.read(offset, buffer, bufferOffset, minOf(length.toLong(), 500 - offset).toInt())
            }
        }
        val reply = get(url)
        assertEquals(1, errors.size)
        assertTrue(reply.body.size <= 500)
    }

    // ------------------------------------------------------------------ Kopma ve eşzamanlılık

    @Test
    fun `client disconnect mid transfer closes the source`() {
        val url = serve(4L * 1024 * 1024 * 1024)
        Socket("127.0.0.1", proxy.port).use { socket ->
            socket.getOutputStream().write("GET ${target(url)} HTTP/1.1\r\n\r\n".toByteArray())
            socket.getInputStream().read(ByteArray(4096))
        }
        assertAllSourcesClosed()
    }

    @Test
    fun `client disconnect wakes a stalled source read`() {
        val stalled = StalledSource()
        val url = proxy.register("x", "x.mkv") { stalled.also { sources += it } }
        val errors = CopyOnWriteArrayList<Exception>()
        proxy.onSourceError = { errors += it }
        Socket("127.0.0.1", proxy.port).use { socket ->
            socket.getOutputStream().write("GET ${target(url)} HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(stalled.entered.await(5, TimeUnit.SECONDS))
        }
        assertAllSourcesClosed()
        assertEquals(1, stalled.closes.get())
        assertTrue("istemci kopması hata sayılmamalı", errors.isEmpty())
    }

    @Test
    fun `seek style reconnect leaves one live connection`() {
        val url = serve(4L * 1024 * 1024 * 1024)
        repeat(20) { i ->
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.getOutputStream().write("GET ${target(url)} HTTP/1.1\r\nRange: bytes=${i * 1_000_000}-\r\n\r\n".toByteArray())
                socket.getInputStream().read(ByteArray(1024))
            }
        }
        assertEquals(20, sources.size)
        assertAllSourcesClosed()
    }

    @Test
    fun `concurrent ranges use separate sources and get correct bytes`() {
        val url = serve(8_000_000)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { i ->
                pool.submit<Boolean> {
                    val start = i * 500_000L
                    val reply = get(url, "bytes=$start-${start + 1_499_999}")
                    reply.status == 206 && reply.body.contentEquals(expected(start, 1_500_000))
                }
            }
            assertTrue(jobs.all { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(8, sources.size)
        assertFalse(sources.any { it.overlappingRead })
        assertAllSourcesClosed()
    }

    @Test
    fun `connection limit answers 503 and recovers`() {
        val limited = LocalStreamProxy(maxConnections = 2)
        val stalls = CopyOnWriteArrayList<StalledSource>()
        val url = limited.register("x", "x.mkv") { StalledSource().also { stalls += it } }
        val request = "GET ${target(url)} HTTP/1.1\r\n\r\n".toByteArray()
        val raw = { Socket("127.0.0.1", limited.port).also { it.soTimeout = 5000; it.getOutputStream().write(request) } }
        limited.use {
            val first = raw()
            val second = raw()
            eventually("iki bağlantı açılmadı") { stalls.size == 2 }
            raw().use { third ->
                assertTrue(String(third.getInputStream().readBytes(), Charsets.ISO_8859_1).startsWith("HTTP/1.1 503"))
            }
            first.close()
            second.close()
            eventually("yer açılmadı") { limited.activeConnections == 0 }
            raw().use { fourth ->
                eventually("yeni bağlantı reddedildi") { stalls.size == 3 }
                assertEquals(3, stalls.size)
                fourth.close()
            }
        }
    }

    // ------------------------------------------------------------------ Kayıt ve kapatma

    @Test
    fun `same key gives same url`() {
        val first = proxy.register("k", "a.mkv") { FakeSource(1) }
        assertEquals(first, proxy.register("k", "a.mkv") { FakeSource(1) })
    }

    @Test
    fun `oldest stream is dropped without cutting its transfer`() {
        val url = serve(4L * 1024 * 1024 * 1024, "eski.mkv")
        Socket("127.0.0.1", proxy.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET ${target(url)} HTTP/1.1\r\n\r\n".toByteArray())
            socket.getInputStream().read(ByteArray(1024))
            repeat(LocalStreamProxy.MAX_STREAMS) { i -> proxy.register("yeni$i", "yeni.mkv") { FakeSource(1) } }
            assertEquals(404, get(url).status)
            assertTrue(socket.getInputStream().read(ByteArray(1024)) > 0)
        }
    }

    @Test
    fun `close stops listening and frees every source`() {
        val stalled = StalledSource()
        val url = proxy.register("x", "x.mkv") { stalled.also { sources += it } }
        val port = proxy.port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET ${target(url)} HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(stalled.entered.await(5, TimeUnit.SECONDS))
            proxy.close()
            proxy.close()
            assertTrue(proxy.isClosed)
            assertAllSourcesClosed()
        }
        try {
            Socket("127.0.0.1", port).close()
            fail("vekil hâlâ dinliyor")
        } catch (_: ConnectException) {
            // Beklenen.
        }
    }

    private companion object {
        /** Sıradaki bayt, 256'nın katlarında da değişsin diye üst bitler de katılır. */
        fun byteAt(position: Long): Byte = ((position xor (position ushr 8) xor (position ushr 16) xor (position ushr 32)) and 0xFF).toByte()

        fun expected(start: Long, length: Int) = ByteArray(length) { byteAt(start + it) }
    }
}
