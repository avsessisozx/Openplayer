package org.openplayer.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Özel klasör: videolar uygulamanın kendi (diğer uygulamalara kapalı) alanına taşınır, galeride
 * görünmez; açmak için cihazın ekran kilidi (PIN, desen, parmak izi) sorulur.
 */
object Vault {
    class Entry(val file: File, val name: String, val origPath: String?)

    private fun dir(c: Context) = File(c.applicationContext.filesDir, "vault").apply { mkdirs() }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun isVaultFile(c: Context, f: File): Boolean {
        val root = runCatching { dir(c).canonicalPath }.getOrNull() ?: return false
        val p = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return p.startsWith("$root/")
    }

    fun list(c: Context): List<Entry> {
        val arr = runCatching { JSONArray(prefs(c).getString("index", "[]")) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val f = File(dir(c), o.optString("file"))
            if (f.exists()) out += Entry(f, o.optString("name", f.name), o.optString("orig").ifEmpty { null })
        }
        return out
    }

    private fun save(c: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("file", it.file.name).put("name", it.name).put("orig", it.origPath ?: "")) }
        prefs(c).edit().putString("index", arr.toString()).apply()
    }

    /** Dosyayı kasaya kopyalar. Başarılıysa dosyayı döndürür. */
    fun add(c: Context, uri: Uri, name: String, origPath: String?): File? = runCatching {
        val ext = name.substringAfterLast('.', "")
        val target = File(dir(c), UUID.randomUUID().toString() + if (ext.isEmpty()) "" else ".${UriGuard.safeFileName(ext, 8)}")
        c.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        } ?: return null
        val list = list(c).toMutableList()
        list += Entry(target, UriGuard.sanitizeTitle(name) ?: "video", origPath)
        save(c, list)
        target
    }.getOrNull()

    fun remove(c: Context, e: Entry) {
        e.file.delete()
        save(c, list(c).filter { it.file.name != e.file.name })
    }

    /** Kasadaki dosyayı Movies/OpenPlayer'a geri yazar (galeride görünür olur) ve kasadan siler. */
    fun restore(c: Context, e: Entry): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val resolver = c.contentResolver
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        val uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, e.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/OpenPlayer")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> e.file.inputStream().use { it.copyTo(out, 1 shl 16) } } ?: throw java.io.IOException()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            remove(c, e)
            true
        } catch (ex: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    fun clearAll(c: Context) {
        dir(c).deleteRecursively()
        prefs(c).edit().clear().apply()
    }
}

/** Seçilen videoları kasaya taşır: önce kopyalar, sonra özgünleri (kalıcı) siler. */
fun androidx.appcompat.app.AppCompatActivity.lockToVault(ops: FileOps, videos: List<VideoItem>, onDone: () -> Unit) {
    if (videos.isEmpty()) return
    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (Build.VERSION.SDK_INT >= 23 && !km.isDeviceSecure) {
        showMessage("Özel klasör", "Özel klasör için cihazında bir ekran kilidi (PIN, desen veya parmak izi) olmalı. Önce telefonun ayarlarından ekran kilidi belirle.")
        return
    }
    PanelBuilder(this)
        .setTitle("Özel klasöre kilitle")
        .setMessage(
            "${videos.size} video özel klasöre taşınacak; galeride ve diğer uygulamalarda görünmeyecek, " +
                "açmak için ekran kilidin sorulacak.\n\n" +
                "Dikkat: Uygulamayı kaldırırsan veya uygulama verisini silersen bu videolar KALICI olarak kaybolur. " +
                "Önemli dosyaların yedeğini başka yerde tut."
        )
        .setPositiveButton("Kilitle") { _, _ -> doLock(ops, videos, onDone) }
        .setNegativeButton("İptal", null)
        .show()
}

private fun androidx.appcompat.app.AppCompatActivity.doLock(ops: FileOps, videos: List<VideoItem>, onDone: () -> Unit) {
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = videos.size }
    val txt = label("0 / ${videos.size}")
    val dialog = PanelBuilder(this).setTitle("Kopyalanıyor").setView(vertical { addView(txt); addView(bar) })
        .setCancelable(false).create()
    dialog.show()
    val app = applicationContext
    Thread {
        val done = ArrayList<VideoItem>()
        for ((i, v) in videos.withIndex()) {
            if (Vault.add(app, v.uri, v.title, v.path.ifEmpty { null }) != null) done += v
            runOnUiThread { bar.progress = i + 1; txt.text = "${i + 1} / ${videos.size}" }
        }
        runOnUiThread {
            dialog.dismiss()
            if (done.isEmpty()) {
                Toast.makeText(this, "Kilitlenemedi", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            Toast.makeText(this, "${done.size} video kasaya kopyalandı. Şimdi özgün dosyalar silinecek.", Toast.LENGTH_LONG).show()
            ops.delete(done.map { it.uri }, permanent = true) { onDone() }
        }
    }.start()
}

/** Özel klasör ekranı; açılırken ekran kilidi sorulur, ekran görüntüsü alınamaz. */
class VaultActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private var authed = false
    private var leftAt = 0L

    private val auth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            authed = true
            render()
        } else finish()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        ThemeManager.applyWindow(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            addView(iconView(R.drawable.ic_back).apply { setOnClickListener { finish() } })
            addView(TextView(context).apply {
                text = "Özel klasör"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(UiColors.background)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@VaultActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        requestAuth()
    }

    override fun onStop() {
        super.onStop()
        leftAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (authed && leftAt > 0 && System.currentTimeMillis() - leftAt > 3 * 60_000L) {
            authed = false
            body.removeAllViews()
            requestAuth()
        }
    }

    private fun requestAuth() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT < 23 || !km.isDeviceSecure) {
            showMessage("Özel klasör", "Cihazında ekran kilidi yok. Özel klasör için önce telefonun ayarlarından PIN, desen veya parmak izi belirle.")
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("Özel klasör", "Devam etmek için ekran kilidini doğrula")
        if (intent == null) {
            authed = true
            render()
        } else auth.launch(intent)
    }

    private fun render() {
        body.removeAllViews()
        if (!authed) return
        val entries = Vault.list(this)
        if (entries.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "Özel klasör boş.\nBir videonun ⋮ menüsünden \"Özel klasöre kilitle\" ile ekleyebilirsin."
                setTextColor(UiColors.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), 0)
            })
            return
        }
        for (e in entries) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { play(e) }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_lock)
                    setColorFilter(UiColors.accent)
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = e.name
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = android.text.format.Formatter.formatFileSize(context, e.file.length())
                        setTextColor(UiColors.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
                addView(iconView(R.drawable.ic_more_vert, 40, 8).apply {
                    setColorFilter(UiColors.textSecondary)
                    setOnClickListener { menu(e) }
                })
            })
        }
    }

    private fun play(e: Vault.Entry) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, arrayListOf(Uri.fromFile(e.file)))
                .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf(e.name))
                .putExtra(PlayerActivity.EXTRA_INDEX, 0)
        )
    }

    private fun menu(e: Vault.Entry) {
        showSheet(e.name, listOf(
            SheetEntry(R.drawable.ic_play, "Oynat") { play(e) },
            SheetEntry(R.drawable.ic_move, "Geri yükle (Movies/OpenPlayer)") {
                Thread {
                    val ok = Vault.restore(applicationContext, e)
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Geri yüklendi" else "Geri yüklenemedi (Android 10+ gerekir)", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }.start()
            },
            SheetEntry(R.drawable.ic_info, "Özellikler") {
                showMediaInfo(e.name, Uri.fromFile(e.file), e.file.length(), e.file.lastModified())
            },
            SheetEntry(R.drawable.ic_delete, "Kalıcı olarak sil", danger = true) {
                PanelBuilder(this).setTitle("Kalıcı silinsin mi?")
                    .setMessage("${e.name}\n\nBu işlem geri alınamaz.")
                    .setPositiveButton("Sil") { _, _ ->
                        Vault.remove(this, e)
                        render()
                    }
                    .setNegativeButton("İptal", null).show()
            },
        ))
    }
}
