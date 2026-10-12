package org.openplayer.app

import android.net.Uri
import android.util.Xml
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NetEntry(val name: String, val isDir: Boolean, val size: Long)

/** SMB, FTP ve WebDAV sunucularında klasör listeler. Tüm çağrılar engelleyicidir; arka planda çalıştırın. */
object NetworkBrowser {

    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "m2ts", "mts",
        "mpg", "mpeg", "vob", "divx", "ogv", "rmvb", "asf"
    )

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in videoExtensions

    /** Adresin yol kısmı: SMB'de ilk bölüm paylaşım adıdır. */
    fun uriFor(server: NetServer, dir: String, name: String): Uri {
        val authority = if (server.effectivePort == server.protocol.defaultPort && server.protocol != NetProtocol.WEBDAV) {
            server.host
        } else {
            "${server.host}:${server.effectivePort}"
        }
        return Uri.Builder()
            .scheme(server.scheme)
            .encodedAuthority(authority)
            .path(dir.trimEnd('/') + "/" + name)
            .build()
    }

    fun list(server: NetServer, dir: String): List<NetEntry> {
        val entries = when (server.protocol) {
            NetProtocol.SMB -> listSmb(server, dir)
            NetProtocol.FTP -> listFtp(server, dir)
            NetProtocol.WEBDAV -> listDav(server, dir)
        }
        return entries
            .filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
            .filter { it.isDir || isVideo(it.name) }
            .sortedWith(compareByDescending<NetEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    // ------------------------------------------------------------------ SMB

    fun smbAuth(server: NetServer): AuthenticationContext =
        if (server.user.isEmpty()) AuthenticationContext.guest()
        else {
            val domain = server.user.substringBefore('\\', "").takeIf { '\\' in server.user } ?: ""
            AuthenticationContext(server.user.substringAfter('\\'), server.pass.toCharArray(), domain)
        }

    private fun listSmb(server: NetServer, dir: String): List<NetEntry> {
        val segments = dir.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw IOException("SMB için paylaşım adı gerekli")
        val shareName = segments[0]
        val sub = segments.drop(1).joinToString("\\")
        return SMBClient().use { client ->
            client.connect(server.host, server.effectivePort).use { conn ->
                conn.authenticate(smbAuth(server)).use { session ->
                    (session.connectShare(shareName) as DiskShare).use { share ->
                        share.list(sub).map { info ->
                            NetEntry(
                                name = info.fileName,
                                isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                                size = info.endOfFile,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ FTP

    fun connectFtp(server: NetServer): FTPClient {
        val ftp = FTPClient()
        ftp.controlEncoding = "UTF-8"
        ftp.connectTimeout = 15_000
        try {
            ftp.connect(server.host, server.effectivePort)
            ftp.soTimeout = 30_000
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw IOException("FTP sunucusu bağlantıyı reddetti")
            val user = server.user.ifEmpty { "anonymous" }
            val pass = if (server.user.isEmpty()) "anonymous@" else server.pass
            if (!ftp.login(user, pass)) throw IOException("FTP girişi başarısız (kullanıcı adı veya parola hatalı)")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            return ftp
        } catch (e: Exception) {
            runCatching { ftp.disconnect() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    private fun listFtp(server: NetServer, dir: String): List<NetEntry> {
        val ftp = connectFtp(server)
        try {
            return (ftp.listFiles(dir) ?: emptyArray()).map { NetEntry(it.name, it.isDirectory, it.size) }
        } finally {
            runCatching { ftp.logout() }
            runCatching { ftp.disconnect() }
        }
    }

    // ------------------------------------------------------------------ WebDAV

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun listDav(server: NetServer, dir: String): List<NetEntry> {
        val base = uriFor(server, dir.trimEnd('/'), "").toString() // sonunda "/" ile biter
        val builder = Request.Builder()
            .url(base)
            .method("PROPFIND", "".toRequestBody())
            .header("Depth", "1")
        if (server.user.isNotEmpty()) builder.header("Authorization", Credentials.basic(server.user, server.pass))
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 401) throw IOException("WebDAV: kullanıcı adı veya parola hatalı")
            if (resp.code != 207 && !resp.isSuccessful) throw IOException("WebDAV hatası ${resp.code}")
            val body = resp.body?.byteStream() ?: return emptyList()
            return parseDav(body, dir)
        }
    }

    private fun parseDav(stream: java.io.InputStream, dir: String): List<NetEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)
        val out = ArrayList<NetEntry>()
        val self = dir.trimEnd('/')
        var href: String? = null
        var size = 0L
        var isDir = false
        var inResponse = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> { inResponse = true; href = null; size = 0L; isDir = false }
                    "href" -> if (inResponse && href == null) href = parser.nextText()
                    "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "collection" -> if (inResponse) isDir = true
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    inResponse = false
                    val h = href
                    if (h != null) {
                        val path = (if ("://" in h) Uri.parse(h).path else Uri.decode(h)).orEmpty().trimEnd('/')
                        if (path != self && path.isNotEmpty()) out += NetEntry(path.substringAfterLast('/'), isDir, size)
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
