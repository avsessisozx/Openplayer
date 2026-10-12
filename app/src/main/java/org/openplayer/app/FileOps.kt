package org.openplayer.app

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TransferItem(val uri: Uri, val path: String?, val name: String)

/** Silme, yeniden adlandırma, taşıma ve kopyalama. Etkinlik oluşturulurken (alan olarak) yaratılmalıdır. */
class FileOps(private val a: AppCompatActivity) {

    private var pending: (() -> Unit)? = null

    private val launcher = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val action = pending
        pending = null
        if (r.resultCode == AppCompatActivity.RESULT_OK) action?.invoke()
    }

    private fun toast(msg: String) = Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()

    private fun launch(sender: android.content.IntentSender, then: () -> Unit) {
        pending = then
        launcher.launch(IntentSenderRequest.Builder(sender).build())
    }

    // ------------------------------------------------------------ erişim

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Taşıma/kopyalama/klasör işlemleri için "tüm dosyalara erişim" gerekir. true dönerse devam edilebilir. */
    fun requireAllFiles(): Boolean {
        if (hasAllFilesAccess()) return true
        PanelBuilder(a)
            .setTitle("Dosya erişimi gerekli")
            .setMessage("Bu işlem için \"Tüm dosyalara erişim\" izni gerekiyor (Android 11 ve üstü). Ayara gidip OpenPlayer için açın, sonra tekrar deneyin.")
            .setPositiveButton("Ayarı aç") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}"))
                    runCatching { a.startActivity(intent) }.onFailure {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
        return false
    }

    fun scan(paths: List<String>, onDone: (() -> Unit)? = null) {
        if (paths.isEmpty()) {
            onDone?.invoke()
            return
        }
        var left = paths.size
        MediaScannerConnection.scanFile(a, paths.toTypedArray(), null) { _, _ ->
            synchronized(this) { left-- }
            if (left <= 0) a.runOnUiThread { onDone?.invoke() }
        }
    }

    // ------------------------------------------------------------ silme

    /** Çöp kutusuna taşır (Android 11+, ayardan kapatılabilir) veya kalıcı siler. */
    fun delete(uris: List<Uri>, onDone: () -> Unit) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val s = if (AppSettings.get(a).useTrash) MediaStore.createTrashRequest(a.contentResolver, uris, true)
            else MediaStore.createDeleteRequest(a.contentResolver, uris)
            launch(s.intentSender) { onDone() }
            return
        }
        deleteLegacy(uris, 0, onDone)
    }

    private fun deleteLegacy(uris: List<Uri>, from: Int, onDone: () -> Unit) {
        var i = from
        while (i < uris.size) {
            try {
                a.contentResolver.delete(uris[i], null, null)
                i++
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                    val next = i
                    launch(e.userAction.actionIntent.intentSender) { deleteLegacy(uris, next, onDone) }
                    return
                }
                toast("Silinemedi")
                return
            }
        }
        onDone()
    }

    // ------------------------------------------------------------ yeniden adlandırma

    private fun sanitize(name: String) = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")

    /** Her uri için yeni tam ad (uzantı dahil). */
    fun rename(uris: List<Uri>, newNames: List<String>, onDone: () -> Unit) {
        if (uris.isEmpty() || uris.size != newNames.size) return
        val names = newNames.map { sanitize(it) }
        if (names.any { it.isEmpty() }) {
            toast("Ad boş olamaz")
            return
        }
        val apply = {
            var failed = 0
            for (i in uris.indices) {
                try {
                    a.contentResolver.update(uris[i], ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, names[i])
                    }, null, null)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) toast("$failed dosya yeniden adlandırılamadı")
            onDone()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val s = MediaStore.createWriteRequest(a.contentResolver, uris)
            launch(s.intentSender, apply)
            return
        }
        try {
            apply()
        } catch (e: SecurityException) {
            toast("Bu dosya için izin alınamadı")
        }
    }

    // ------------------------------------------------------------ klasör seçici

    /** Basit klasör gezgini; "Buraya seç" ve "Yeni klasör" içerir. */
    fun pickFolder(title: String, start: File? = null, onPick: (File) -> Unit) {
        val root = Environment.getExternalStorageDirectory()
        showFolder(title, start?.takeIf { it.isDirectory } ?: root, root, onPick)
    }

    private fun showFolder(title: String, dir: File, root: File, onPick: (File) -> Unit) {
        val subs = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
        val labels = ArrayList<String>()
        if (dir.absolutePath != root.absolutePath) labels += "⬆  Üst klasör"
        subs.forEach { labels += "📁  ${it.name}" }
        val offset = if (dir.absolutePath != root.absolutePath) 1 else 0
        PanelBuilder(a)
            .setTitle("$title\n${dir.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" }}")
            .setItems(labels.toTypedArray()) { _, which ->
                if (offset == 1 && which == 0) showFolder(title, dir.parentFile ?: root, root, onPick)
                else showFolder(title, subs[which - offset], root, onPick)
            }
            .setPositiveButton("Buraya seç") { _, _ -> onPick(dir) }
            .setNeutralButton("Yeni klasör") { _, _ ->
                a.showInput("Yeni klasör", "Klasör adı") { n ->
                    val clean = sanitize(n)
                    if (clean.isNotEmpty()) {
                        val f = File(dir, clean)
                        if (f.exists() || f.mkdirs()) showFolder(title, f, root, onPick) else {
                            toast("Klasör oluşturulamadı")
                            showFolder(title, dir, root, onPick)
                        }
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ------------------------------------------------------------ taşı / kopyala

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return f
    }

    /** Dosyaları hedef klasöre kopyalar; [move] ise başarılı olanların kaynağını siler. Tüm dosyalara erişim gerekir. */
    fun transfer(items: List<TransferItem>, dest: File, move: Boolean, onDone: () -> Unit) {
        if (!requireAllFiles()) return
        val cancelled = AtomicBoolean(false)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = items.size
            progress = 0
        }
        val label = a.label("0 / ${items.size}")
        val box = a.vertical { addView(label); addView(bar) }
        val dialog = PanelBuilder(a)
            .setTitle(if (move) "Taşınıyor" else "Kopyalanıyor")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("İptal") { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()
        val app = a.applicationContext
        Thread {
            var ok = 0
            var fail = 0
            val newPaths = ArrayList<String>()
            val oldPaths = ArrayList<String>()
            for ((i, it) in items.withIndex()) {
                if (cancelled.get()) break
                try {
                    val target = uniqueFile(dest, it.name)
                    val src = it.path?.let { p -> File(p) }
                    if (src != null && src.exists() && move &&
                        runCatching { src.renameTo(target) }.getOrDefault(false)
                    ) {
                        // Aynı birimde anında taşındı
                    } else {
                        app.contentResolver.openInputStream(it.uri)?.use { input ->
                            target.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                        } ?: throw java.io.IOException("açılamadı")
                        if (move) {
                            if (src != null) src.delete()
                        }
                    }
                    newPaths += target.absolutePath
                    it.path?.let { p -> oldPaths += p }
                    ok++
                } catch (e: Exception) {
                    fail++
                }
                val done = i + 1
                a.runOnUiThread {
                    bar.progress = done
                    label.text = "$done / ${items.size}"
                }
            }
            a.runOnUiThread {
                dialog.dismiss()
                scan(newPaths + oldPaths) {
                    toast(if (fail == 0) "$ok dosya ${if (move) "taşındı" else "kopyalandı"}" else "$ok başarılı, $fail başarısız")
                    onDone()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ klasör yeniden adlandırma

    fun renameFolder(dir: File, newName: String, videoPaths: List<String>, onDone: () -> Unit) {
        if (!requireAllFiles()) return
        val clean = sanitize(newName)
        if (clean.isEmpty()) return
        val target = File(dir.parentFile, clean)
        if (target.exists()) {
            toast("Bu adda bir klasör zaten var")
            return
        }
        if (!dir.renameTo(target)) {
            toast("Klasör yeniden adlandırılamadı")
            return
        }
        val moved = videoPaths.map { p -> target.absolutePath + p.removePrefix(dir.absolutePath) }
        scan(moved + videoPaths) { onDone() }
    }
}
