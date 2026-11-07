package org.openplayer.app

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Credentials
import okhttp3.OkHttpClient

/** SMB için JVM'de sahte sunucu yok; FTP ve WebDAV gerçek soketli küçük sahte sunucularla sınanır. */
class NetworkSourcesTest {

    private val data = ByteArray(300_000) { (it * 31 + (it ushr 8)).toByte() }

    @Before
    fun strings() = TestStrings.installTurkish()

    // ------------------------------------------------------------------ Sahte FTP

    /** Pasif kipte RETR ve REST bilen en küçük FTP sunucusu. */
    private inner class FakeFtp(
        private val reportedSize: Long = data.size.toLong(),
        private val sizeSupported: Boolean = true,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val port: Int = listener.localPort
        val commands = CopyOnWriteArrayList<String>()
        val logins = AtomicInteger()
        val activeControls = AtomicInteger()
        val activeTransfers = AtomicInteger()
        val maxTransfersPerControl = AtomicInteger()
        val abortedTransfers = AtomicInteger()

        init {
            Thread {
                while (true) {
                    val control = try { listener.accept() } catch (_: IOException) { break }
                    activeControls.incrementAndGet()
                    Thread { runCatching { session(control) }; activeControls.decrementAndGet() }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun session(control: Socket) = control.use {
            val reader = control.getInputStream().bufferedReader()
            val writer = control.getOutputStream().bufferedWriter()
            fun reply(text: String) { writer.write(text + "\r\n"); writer.flush() }
            var offset = 0L
            var passive: ServerSocket? = null
            var inTransfer = 0
            reply("220 hazir")
            while (true) {
                val line = reader.readLine() ?: break
                commands += line
                val verb = line.substringBefore(' ').uppercase()
                val arg = line.substringAfter(' ', "")
                when (verb) {
                    "USER" -> reply("331 parola")
                    "PASS" -> { logins.incrementAndGet(); reply("230 tamam") }
                    "TYPE" -> reply("200 tamam")
                    "SIZE" -> if (sizeSupported) reply("213 $reportedSize") else reply("502 yok")
                    "REST" -> { offset = arg.toLong(); reply("350 tamam") }
                    "PASV" -> {
                        passive = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                        val p = passive!!.localPort
                        reply("227 Entering Passive Mode (127,0,0,1,${p shr 8},${p and 255})")
                    }
                    "RETR" -> {
                        if (arg != "/film.mkv") { reply("550 yok"); continue }
                        inTransfer++
                        maxTransfersPerControl.accumulateAndGet(inTransfer) { a, b -> maxOf(a, b) }
                        val server = passive!!
                        reply("150 veri")
                        activeTransfers.incrementAndGet()
                        val start = offset
                        offset = 0
                        Thread {
                            try {
                                server.accept().use { socket ->
                                    if (start < data.size) {
                                        socket.getOutputStream().write(data, start.toInt(), data.size - start.toInt())
                                    }
                                }
                                runCatching { reply("226 bitti") }
                            } catch (_: IOException) {
                                abortedTransfers.incrementAndGet()
                            } finally {
                                activeTransfers.decrementAndGet()
                                runCatching { server.close() }
                            }
                            inTransfer--
                        }.apply { isDaemon = true }.start()
                    }
                    "QUIT" -> { reply("221 gule gule"); return@use }
                    else -> reply("502 yok")
                }
            }
        }

        fun connect(): FTPClient = FTPClient().apply {
            controlEncoding = "UTF-8"
            connect("127.0.0.1", port)
            soTimeout = 5000
            login("kullanici", "gizli")
            enterLocalPassiveMode()
            setFileType(FTP.BINARY_FILE_TYPE)
        }

        override fun close() = listener.close()
    }

    private fun readAll(source: StreamSource, start: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var position = start
        while (true) {
            val n = source.read(position, buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
            position += n
        }
        return out.toByteArray()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val limit = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > limit) fail(what)
            Thread.sleep(10)
        }
    }

    @Test
    fun `ftp reports size and reads from start`() = FakeFtp().use { server ->
        FtpStreamSource.open("/film.mkv") { server.connect() }.use { source ->
            assertEquals(data.size.toLong(), source.size)
            assertArrayEquals(data, readAll(source, 0))
        }
    }

    @Test
    fun `ftp seeks with REST on its own connection`() = FakeFtp().use { server ->
        FtpStreamSource.open("/film.mkv") { server.connect() }.use { source ->
            assertArrayEquals(data.copyOfRange(100_000, data.size), readAll(source, 100_000))
        }
        assertTrue("REST 100000" in server.commands)
        assertEquals(1, server.logins.get())
    }

    @Test
    fun `ftp size above 4 GB stays a long`() = FakeFtp(reportedSize = 6_000_000_000L).use { server ->
        FtpStreamSource.open("/film.mkv") { server.connect() }.use { assertEquals(6_000_000_000L, it.size) }
    }

    @Test
    fun `ftp without SIZE has unknown length`() = FakeFtp(sizeSupported = false).use { server ->
        FtpStreamSource.open("/film.mkv") { server.connect() }.use { assertEquals(StreamSource.UNKNOWN_SIZE, it.size) }
    }

    @Test
    fun `ftp reposition reconnects because one connection carries one transfer`() = FakeFtp().use { server ->
        FtpStreamSource.open("/film.mkv") { server.connect() }.use { source ->
            val first = ByteArray(100)
            assertTrue(source.read(0, first, 0, 100) > 0)
            val second = ByteArray(100)
            var got = 0
            while (got < 100) got += source.read(200_000L + got, second, got, 100 - got)
            assertArrayEquals(data.copyOfRange(200_000, 200_100), second)
        }
        assertEquals(2, server.logins.get())
        assertEquals(1, server.maxTransfersPerControl.get())
    }

    @Test
    fun `ftp missing file is an error and closes the connection`() = FakeFtp().use { server ->
        val source = FtpStreamSource.open("/yok.mkv") { server.connect() }
        try {
            source.read(0, ByteArray(10), 0, 10)
            fail("hata bekleniyordu")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("550"))
        }
        source.close()
        eventually("denetim soketi sızdı") { server.activeControls.get() == 0 }
    }

    @Test
    fun `ftp close during transfer frees both sockets`() = FakeFtp().use { server ->
        val source = FtpStreamSource.open("/film.mkv") { server.connect() }
        source.read(0, ByteArray(10), 0, 10)
        source.close()
        source.close()
        eventually("denetim soketi sızdı") { server.activeControls.get() == 0 }
        eventually("veri soketi sızdı") { server.activeTransfers.get() == 0 }
    }

    @Test
    fun `ftp through proxy serves concurrent ranges on separate connections`() = FakeFtp().use { server ->
        LocalStreamProxy().use { proxy ->
            val url = proxy.register("f", "film.mkv") { FtpStreamSource.open("/film.mkv") { server.connect() } }
            val path = java.net.URI(url).rawPath
            val jobs = listOf(0, 50_000, 120_000, 250_000).map { start ->
                val result = java.util.concurrent.FutureTask { rangeRequest(proxy.port, path, start, start + 40_000) }
                Thread(result).start()
                start to result
            }
            jobs.forEach { (start, result) ->
                assertArrayEquals(data.copyOfRange(start, start + 40_001), result.get(15, TimeUnit.SECONDS))
            }
            assertEquals(4, server.logins.get())
            assertEquals(1, server.maxTransfersPerControl.get())
            eventually("denetim soketi sızdı") { server.activeControls.get() == 0 }
        }
    }

    private fun rangeRequest(port: Int, path: String, start: Int, end: Int): ByteArray =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nRange: bytes=$start-$end\r\n\r\n".toByteArray())
            val all = socket.getInputStream().readBytes()
            val head = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            assertTrue(String(all, 0, head, Charsets.ISO_8859_1).startsWith("HTTP/1.1 206"))
            all.copyOfRange(head + 4, all.size)
        }

    // ------------------------------------------------------------------ Sahte WebDAV

    /** Tek istek işleyen küçük HTTP sunucusu; istek başlıklarını kaydeder. */
    private inner class FakeDav(
        private val ranges: Boolean = true,
        private val head: Boolean = true,
        private val requirePassword: String? = null,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val url = "http://127.0.0.1:${listener.localPort}/dav/film.mkv"
        val requests = CopyOnWriteArrayList<List<String>>()

        init {
            Thread {
                while (true) {
                    val socket = try { listener.accept() } catch (_: IOException) { break }
                    Thread { runCatching { handle(socket) } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(socket: Socket) = socket.use {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            requests += lines
            val output = socket.getOutputStream()
            fun send(text: String) = output.write(text.toByteArray(Charsets.ISO_8859_1))
            val method = lines.first().substringBefore(' ')
            val auth = lines.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(':')?.trim()
            if (requirePassword != null && auth != Credentials.basic("kullanici", requirePassword)) {
                send("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return@use
            }
            if (method == "HEAD") {
                if (head) send("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n")
                else send("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return@use
            }
            val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter('=')?.trim()
            if (ranges && range != null) {
                val start = range.substringBefore('-').toInt()
                val end = range.substringAfter('-').toIntOrNull() ?: (data.size - 1)
                if (start >= data.size) {
                    send("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return@use
                }
                send("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${data.size}\r\n" +
                    "Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
                output.write(data, start, end - start + 1)
            } else {
                send("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n")
                output.write(data)
            }
        }

        override fun close() = listener.close()
    }

    private val http = OkHttpClient()

    @After
    fun shutdownClient() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun factory(server: FakeDav, password: String? = null) = WebDavSourceFactory(
        server.url, password?.let { Credentials.basic("kullanici", it) }, "Test/1.0", http,
    )

    @Test
    fun `webdav sends auth and agent and seeks with Range`() = FakeDav(requirePassword = "gizli").use { server ->
        factory(server, "gizli").open().use { source ->
            assertEquals(data.size.toLong(), source.size)
            assertArrayEquals(data.copyOfRange(100_000, data.size), readAll(source, 100_000))
        }
        assertTrue(server.requests.all { lines -> lines.any { it == "User-Agent: Test/1.0" } })
        assertTrue(server.requests.last().any { it == "Range: bytes=100000-" })
    }

    @Test
    fun `webdav wrong password fails`() = FakeDav(requirePassword = "gizli").use { server ->
        try {
            factory(server, "yanlis").open()
            fail("hata bekleniyordu")
        } catch (e: IOException) {
            assertFalse(e.message.orEmpty().contains("yanlis"))
        }
    }

    @Test
    fun `webdav without HEAD asks size by Range`() = FakeDav(head = false).use { server ->
        factory(server).open().use { assertEquals(data.size.toLong(), it.size) }
    }

    @Test
    fun `webdav asks size once across requests`() = FakeDav().use { server ->
        val factory = factory(server)
        repeat(3) { factory.open().close() }
        assertEquals(1, server.requests.count { it.first().startsWith("HEAD") })
    }

    @Test
    fun `webdav without range support skips to offset`() = FakeDav(ranges = false).use { server ->
        factory(server).open().use { source ->
            assertArrayEquals(data.copyOfRange(250_000, data.size), readAll(source, 250_000))
        }
    }

    @Test
    fun `webdav close wakes the transfer and later reads fail`() = FakeDav().use { server ->
        val source = factory(server).open()
        source.read(0, ByteArray(10), 0, 10)
        source.close()
        source.close()
        try {
            readAll(source, 10)
            fail("hata bekleniyordu")
        } catch (_: Exception) {
            // Kapanan kaynak okunamaz.
        }
    }

    @Test
    fun `webdav through proxy keeps the password out of the url`() = FakeDav(requirePassword = "gizli").use { server ->
        LocalStreamProxy().use { proxy ->
            val url = proxy.register(server.url, "film.mkv", factory(server, "gizli"))
            assertFalse("gizli" in url)
            assertFalse(Credentials.basic("kullanici", "gizli").substringAfter(' ') in url)
            val path = java.net.URI(url).rawPath
            assertArrayEquals(data.copyOfRange(1000, 2001), rangeRequest(proxy.port, path, 1000, 2000))
        }
    }
}
