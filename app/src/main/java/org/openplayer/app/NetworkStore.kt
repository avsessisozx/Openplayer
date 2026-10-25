package org.openplayer.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NetProtocol(val label: String, val defaultPort: Int, val pathHint: String) {
    SMB(Tx.s(R.string.networksto_002), 445, Tx.s(R.string.networksto_001)),
    FTP("FTP", 21, Tx.s(R.string.networksto_003)),
    WEBDAV("WebDAV", 80, Tx.s(R.string.networksto_004)),
}

data class NetServer(
    val id: String = UUID.randomUUID().toString(),
    val protocol: NetProtocol,
    val name: String,
    val host: String,
    /** 0 = protokolün varsayılan portu. */
    val port: Int = 0,
    /** SMB: paylaşım adı ile başlayan yol. FTP/WebDAV: sunucudaki başlangıç yolu. */
    val path: String = "",
    val user: String = "",
    val pass: String = "",
    /** WebDAV için https kullan. */
    val secure: Boolean = false,
) {
    val effectivePort: Int
        get() = if (port > 0) port else if (protocol == NetProtocol.WEBDAV && secure) 443 else protocol.defaultPort

    /** Gezinmeye başlanacak tam yol ("/" ile başlar). */
    val rootDir: String get() = "/" + path.trim('/')

    val scheme: String
        get() = when (protocol) {
            NetProtocol.SMB -> "smb"
            NetProtocol.FTP -> "ftp"
            NetProtocol.WEBDAV -> if (secure) "https" else "http"
        }

    val summary: String get() = "${protocol.name} · $host:$effectivePort"
}

/** Kayıtlı ağ sunucuları. Parolalar Android Keystore ile şifrelenerek saklanır (SecretBox). */
object NetworkStore {

    private fun prefs(c: Context) = c.getSharedPreferences("network", Context.MODE_PRIVATE)

    fun all(c: Context): List<NetServer> {
        val raw = prefs(c).getString("servers", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                NetServer(
                    id = o.getString("id"),
                    protocol = NetProtocol.valueOf(o.getString("protocol")),
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    path = o.optString("path"),
                    user = o.optString("user"),
                    pass = SecretBox.decrypt(o.optString("pass")),
                    secure = o.optBoolean("secure"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(c: Context, servers: List<NetServer>) {
        val arr = JSONArray()
        servers.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("protocol", it.protocol.name).put("name", it.name)
                    .put("host", it.host).put("port", it.port).put("path", it.path)
                    .put("user", it.user).put("pass", SecretBox.encrypt(it.pass)).put("secure", it.secure)
            )
        }
        prefs(c).edit().putString("servers", arr.toString()).apply()
    }

    fun save(c: Context, server: NetServer) {
        val list = all(c).toMutableList()
        val i = list.indexOfFirst { it.id == server.id }
        if (i >= 0) list[i] = server else list += server
        write(c, list)
    }

    fun delete(c: Context, id: String) = write(c, all(c).filter { it.id != id })

    /** Bir oynatma adresine uyan kayıtlı sunucuyu (kimlik bilgileri için) bulur. */
    fun find(c: Context, uri: Uri): NetServer? {
        val host = uri.host ?: return null
        val wanted = when (uri.scheme?.lowercase()) {
            "smb" -> NetProtocol.SMB
            "ftp" -> NetProtocol.FTP
            "http", "https" -> NetProtocol.WEBDAV
            else -> return null
        }
        // Kimlik bilgisi yalnızca host + port + şema birebir eşleşirse eklenir; böylece
        // saldırganın verdiği başka bir adrese kayıtlı parola gönderilmez.
        val port = if (uri.port > 0) uri.port else when (uri.scheme?.lowercase()) {
            "https" -> 443; "http" -> 80; "smb" -> 445; else -> 21
        }
        return all(c).firstOrNull {
            it.protocol == wanted && it.host.equals(host, ignoreCase = true) &&
                it.effectivePort == port &&
                (wanted != NetProtocol.WEBDAV || it.scheme == uri.scheme?.lowercase())
        }
    }
}
