package org.openplayer.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Dışarıdan (başka uygulama, web bağlantısı) gelen adreslerin doğrulanması.
 * PlayerActivity dışa açık olduğundan gelen her şey güvenilmez kabul edilir.
 */
object UriGuard {
    const val MAX_ITEMS = 2000
    const val MAX_TITLE = 300
    const val MAX_URL = 4096

    private val allowedSchemes = setOf("content", "file", "http", "https", "rtsp", "smb", "ftp")

    fun isAllowed(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in allowedSchemes) return false
        if (uri.toString().length > MAX_URL) return false
        return when (scheme) {
            "file" -> uri.path?.let { !isPrivateToApp(context, File(it)) || Vault.isVaultFile(context, File(it)) } ?: false
            "content" -> uri.authority?.let { it != "${context.packageName}.provider" } ?: false
            else -> !uri.host.isNullOrBlank()
        }
    }

    /** Uygulamanın kendi özel verisini (ayarlar, parolalar, önbellek) oynatıcıya okutmayı engeller. */
    fun isPrivateToApp(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalFile.path }.getOrDefault(file.absolutePath)
        val roots = listOfNotNull(
            context.applicationInfo.dataDir,
            context.applicationInfo.deviceProtectedDataDir,
            "/data/data/${context.packageName}",
            "/data/user/",
            "/data/user_de/",
        )
        return roots.any { r ->
            val rr = runCatching { File(r).canonicalPath }.getOrDefault(r)
            target == rr || target.startsWith(rr.trimEnd('/') + "/")
        }
    }

    fun sanitizeTitle(t: String?): String? =
        t?.filter { !it.isISOControl() }?.take(MAX_TITLE)?.ifBlank { null }

    /** Dosya adı olarak güvenli, yol ayracı ve kontrol karakteri içermeyen bir ad üretir. */
    fun safeFileName(name: String, maxLen: Int = 80): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_")
            .trim('.', ' ', '_')
            .take(maxLen)
        return cleaned.ifEmpty { "dosya" }
    }
}
