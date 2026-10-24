package org.openplayer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ayrıntılı hata raporu: kullanıcı tek dokunuşla panoya kopyalayıp geliştiriciye yapıştırır.
 * Parola, API anahtarı ve kullanıcı adları rapora yazılmaz.
 */
object BugReport {
    private const val PREFS = "bugreport"
    private const val SETTINGS_PREFS = "settings"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    /** Beklenmeyen bir çökmeyi bir sonraki açılışta rapora girmek üzere saklar. */
    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val text = "${stamp()} iş parçacığı=${thread.name}\n" + android.util.Log.getStackTraceString(e).take(6000)
                prefs(app).edit().putString("last_crash", text).commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Altyazı çevirisinin son denemesi (sonuç + istek günlüğü). */
    fun noteTranslate(c: Context, text: String) {
        prefs(c).edit().putString("last_translate", "${stamp()}\n$text".take(12000)).apply()
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(pi: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    private val secretNames = setOf("os_key", "os_user", "os_pass", "tr_key", "tr_email")

    private fun isSecret(key: String): Boolean {
        val k = key.lowercase()
        return k in secretNames || "pass" in k || "token" in k || "secret" in k
    }

    fun build(c: Context): String {
        val app = c.applicationContext
        val sb = StringBuilder()
        sb.append("=== OpenPlayer hata raporu ===\n")
        sb.append("Zaman: ").append(stamp()).append('\n')
        val pi = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        sb.append("Sürüm: ").append(pi?.versionName ?: "?")
        sb.append(" (kod ").append(pi?.let { versionCodeOf(it) } ?: "?").append(")\n")
        sb.append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        val dm = app.resources.displayMetrics
        sb.append("Ekran: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" · yoğunluk ").append(dm.density).append('\n')
        sb.append("Dil: ").append(Locale.getDefault()).append('\n')
        runCatching {
            val st = StatFs(app.filesDir.path)
            sb.append("Boş alan: ").append(st.availableBytes / (1024 * 1024)).append(" MB\n")
        }
        val mem = Runtime.getRuntime()
        sb.append("Bellek: ").append(mem.totalMemory() / (1024 * 1024)).append(" / ").append(mem.maxMemory() / (1024 * 1024)).append(" MB\n")

        sb.append("\n--- Ayarlar (gizli bilgiler hariç) ---\n")
        runCatching {
            val all = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).all
            for ((k, v) in all.toSortedMap()) {
                if (isSecret(k)) sb.append(k).append(" = ").append(if (v.toString().isEmpty()) "(boş)" else "(gizli)").append('\n')
                else sb.append(k).append(" = ").append(v.toString().take(120)).append('\n')
            }
        }

        val p = prefs(app)
        sb.append("\n--- Son altyazı çevirisi ---\n")
        sb.append(p.getString("last_translate", null) ?: "(henüz çeviri denenmedi)").append('\n')

        sb.append("\n--- Son çökme ---\n")
        sb.append(p.getString("last_crash", null) ?: "(kayıtlı çökme yok)").append('\n')

        sb.append("\n--- Uygulama günlüğü (son hata/uyarılar) ---\n")
        sb.append(recentLog()).append('\n')
        return sb.toString()
    }

    private fun recentLog(): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "600", "-v", "time"))
        val lines = BufferedReader(InputStreamReader(proc.inputStream)).use { r -> r.readLines() }
        proc.destroy()
        val keep = lines.filter { l ->
            (" E/" in l || " W/" in l || "AndroidRuntime" in l || "OpenPlayer" in l || "OPFEAT" in l)
        }
        keep.takeLast(120).joinToString("\n").ifBlank { "(günlük boş)" }.take(14000)
    }.getOrElse { "(günlük okunamadı: ${it.message})" }

    /** Raporu arka planda hazırlayıp panoya koyar; bitince [done] ana iş parçacığında çağrılır. */
    fun copyToClipboard(activity: android.app.Activity, done: (Int) -> Unit) {
        Thread {
            val text = build(activity)
            activity.runOnUiThread {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("OpenPlayer hata raporu", text))
                done(text.length)
            }
        }.start()
    }
}
