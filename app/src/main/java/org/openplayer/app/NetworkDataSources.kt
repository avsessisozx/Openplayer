package org.openplayer.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import okhttp3.Credentials
import org.apache.commons.net.ftp.FTPClient
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import com.hierynomus.smbj.share.File as SmbFile

/** smb:// ve ftp:// adreslerini ilgili veri kaynağına yönlendirir; diğer adresler [base]'e gider. */
class NetworkDataSourceFactory(
    private val context: Context,
    private val base: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Routing(context.applicationContext, base.createDataSource())

    private class Routing(private val context: Context, private val base: DataSource) : DataSource {
        private var active: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            base.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source: DataSource = when (dataSpec.uri.scheme?.lowercase()) {
                "smb" -> SmbDataSource(context).also { s -> listeners.forEach { s.addTransferListener(it) } }
                "ftp" -> FtpDataSource(context).also { s -> listeners.forEach { s.addTransferListener(it) } }
                else -> base
            }
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }

    companion object {
        /** WebDAV sunucusuna giden http(s) isteklerine kayıtlı kimlik bilgisini ekler. */
        fun withWebDavAuth(context: Context, upstream: DataSource.Factory): DataSource.Factory {
            val app = context.applicationContext
            return ResolvingDataSource.Factory(upstream) { spec ->
                val server = NetworkStore.find(app, spec.uri)
                if (server != null && server.user.isNotEmpty()) {
                    spec.withAdditionalHeaders(mapOf("Authorization" to Credentials.basic(server.user, server.pass)))
                } else {
                    spec
                }
            }
        }
    }
}

/** smbj ile SMB paylaşımından okur. Ağ gidiş-dönüşlerini azaltmak için 512 KB'lık önbellek kullanır. */
class SmbDataSource(private val context: Context) : BaseDataSource(true) {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var file: SmbFile? = null
    private var currentUri: Uri? = null
    private var opened = false

    private var filePosition = 0L
    private var bytesRemaining = 0L
    private val cache = ByteArray(512 * 1024)
    private var cacheStart = 0
    private var cacheEnd = 0

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)
        val segments = uri.pathSegments
        if (segments.isEmpty()) throw IOException("SMB adresinde paylaşım adı yok")
        val server = NetworkStore.find(context, uri)
            ?: NetServer(protocol = NetProtocol.SMB, name = "", host = uri.host.orEmpty())
        val port = if (uri.port > 0) uri.port else 445
        try {
            val c = SMBClient().also { client = it }
            val conn = c.connect(server.host, port).also { connection = it }
            val sess = conn.authenticate(NetworkBrowser.smbAuth(server)).also { session = it }
            val sh = (sess.connectShare(segments[0]) as DiskShare).also { share = it }
            val path = segments.drop(1).joinToString("\\")
            file = sh.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: Exception) {
            closeQuietly()
            throw IOException("SMB bağlantı hatası: ${e.message}", e)
        }
        val size = file!!.fileInformation.standardInformation.endOfFile
        filePosition = dataSpec.position
        if (filePosition > size) {
            closeQuietly()
            throw IOException("SMB: istenen konum dosya boyutunu aşıyor")
        }
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - filePosition
        cacheStart = 0
        cacheEnd = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (cacheStart >= cacheEnd) {
            val want = minOf(cache.size.toLong(), bytesRemaining).toInt()
            val n = try {
                file!!.read(cache, filePosition, 0, want)
            } catch (e: Exception) {
                throw IOException("SMB okuma hatası: ${e.message}", e)
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            cacheStart = 0
            cacheEnd = n
            filePosition += n
        }
        val count = minOf(minOf(readLength, cacheEnd - cacheStart).toLong(), bytesRemaining).toInt()
        System.arraycopy(cache, cacheStart, target, offset, count)
        cacheStart += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closeQuietly()
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        runCatching { file?.close() }
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        file = null; share = null; session = null; connection = null; client = null
    }
}

/** commons-net ile FTP sunucusundan okur. Her açılışta yeni bir bağlantı kurulur. */
class FtpDataSource(private val context: Context) : BaseDataSource(true) {

    private var ftp: FTPClient? = null
    private var stream: InputStream? = null
    private var currentUri: Uri? = null
    private var opened = false
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)
        val path = uri.path ?: throw IOException("FTP adresinde dosya yolu yok")
        val server = NetworkStore.find(context, uri)
            ?: NetServer(protocol = NetProtocol.FTP, name = "", host = uri.host.orEmpty(), port = uri.port.coerceAtLeast(0))
        val client = try {
            NetworkBrowser.connectFtp(server.copy(port = if (uri.port > 0) uri.port else server.port)).also { ftp = it }
        } catch (e: IOException) {
            throw e
        }
        try {
            val size = client.getSize(path)?.trim()?.toLongOrNull()
            if (dataSpec.position > 0) client.restartOffset = dataSpec.position
            stream = client.retrieveFileStream(path)
                ?: throw IOException("FTP dosyası açılamadı: ${client.replyString.orEmpty().trim()}")
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                size != null -> (size - dataSpec.position).coerceAtLeast(0)
                else -> C.LENGTH_UNSET.toLong()
            }
        } catch (e: Exception) {
            closeQuietly()
            throw if (e is IOException) e else IOException("FTP hatası: ${e.message}", e)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) readLength
        else minOf(readLength.toLong(), bytesRemaining).toInt()
        val n = try {
            stream!!.read(target, offset, toRead)
        } catch (e: IOException) {
            throw e
        }
        if (n == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining != 0L) throw EOFException()
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closeQuietly()
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        runCatching { stream?.close() }
        runCatching { ftp?.disconnect() }
        stream = null
        ftp = null
    }
}
