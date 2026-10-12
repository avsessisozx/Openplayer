package org.openplayer.app

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Ana ekran: klasör listesi, klasör içi video listesi, arama, sıralama ve dosya işlemleri. */
class MainActivity : AppCompatActivity() {

    private val settings by lazy { AppSettings.get(this) }

    private lateinit var backButton: ImageView
    private lateinit var titleView: TextView
    private lateinit var searchField: EditText
    private lateinit var searchButton: ImageView
    private lateinit var sortButton: ImageView
    private lateinit var viewButton: ImageView
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView

    private val folderAdapter = FolderAdapter({ openFolder(it) }, { folderMenu(it) })
    private val listAdapter = VideoAdapter(false, { play(it) }, { v, anchor -> videoMenu(v, anchor) })
    private val gridAdapter = VideoAdapter(true, { play(it) }, { v, anchor -> videoMenu(v, anchor) })

    private var all: List<VideoItem> = emptyList()
    private var currentFolder: FolderItem? = null
    private var searching = false
    private var shown: List<VideoItem> = emptyList()

    private var pendingAction: (() -> Unit)? = null
    private val writeAccess =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pendingAction
            pendingAction = null
            if (result.resultCode == RESULT_OK) action?.invoke()
        }

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refresh() else showMessage("Videoları göstermek için izin gerekli.\nDokunarak tekrar dene.")
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                searching -> closeSearch()
                currentFolder != null -> {
                    currentFolder = null
                    render()
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) refresh() else requestPermission.launch(permission)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ arayüz

    private fun buildUi(): View {
        backButton = iconView(R.drawable.ic_back).apply {
            visibility = View.GONE
            setOnClickListener { backCallback.handleOnBackPressed() }
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        searchField = EditText(this).apply {
            hint = "Ara"
            setHintTextColor(UiColors.textSecondary)
            setTextColor(Color.WHITE)
            setSingleLine()
            background = null
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        searchButton = iconView(R.drawable.ic_search).apply {
            setOnClickListener { if (searching) closeSearch() else openSearch() }
        }
        sortButton = iconView(R.drawable.ic_sort).apply { setOnClickListener { showSortMenu() } }
        viewButton = iconView(R.drawable.ic_grid).apply {
            setOnClickListener {
                settings.gridView = !settings.gridView
                render()
            }
        }
        val moreButton = iconView(R.drawable.ic_more_vert).apply { setOnClickListener { showRootMenu(it) } }

        val titleBox = FrameLayout(this).apply {
            addView(titleView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            addView(searchField, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            addView(backButton)
            addView(titleBox, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
            addView(searchButton)
            addView(sortButton)
            addView(viewButton)
            addView(moreButton)
        }

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = folderAdapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(96))
        }
        emptyView = TextView(this).apply {
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { if (!hasPermission()) requestPermission.launch(permission) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(FrameLayout(this@MainActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiColors.accent)
            }
            elevation = dp(6).toFloat()
            contentDescription = "Son oynatılanı aç"
            setOnClickListener { resumeLast() }
        }

        return FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(
                fab,
                FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, dp(20), dp(20)) }
            )
        }
    }

    // ------------------------------------------------------------ veri ve görünüm

    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** MediaStore taraması arka planda yapılır; arayüz takılmaz. */
    private fun refresh() {
        val app = applicationContext
        loader.execute {
            val loaded = VideoRepository.loadAll(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val first = all.isEmpty()
                all = loaded
                val highlight = ResumeStore.lastUri(this)
                listAdapter.highlightUri = highlight
                gridAdapter.highlightUri = highlight
                listAdapter.newDays = settings.newVideoDays
                gridAdapter.newDays = settings.newVideoDays
                render()
                if (first) list.scrollToPosition(0)
            }
        }
    }

    private fun visibleVideos(): List<VideoItem> {
        val hidden = settings.hiddenFolders
        return if (settings.showHiddenFolders) all else all.filter { it.bucketId !in hidden }
    }

    private fun render() {
        val query = searchField.text.toString().trim()
        when {
            searching -> {
                backButton.visibility = View.GONE
                shown = if (query.isEmpty()) emptyList()
                else VideoRepository.sort(this, visibleVideos().filter { it.title.contains(query, ignoreCase = true) }, settings)
                showVideos(shown, if (query.isEmpty()) "Aramak için yazın" else "Sonuç bulunamadı")
            }
            currentFolder != null -> {
                val folder = currentFolder!!
                titleView.text = folder.name
                backButton.visibility = View.VISIBLE
                shown = VideoRepository.sort(this, all.filter { it.bucketId == folder.bucketId }, settings)
                showVideos(shown, "Bu klasörde video yok")
            }
            settings.listMode == ListMode.FILES -> {
                titleView.text = "Videolar"
                backButton.visibility = View.GONE
                shown = VideoRepository.sort(this, visibleVideos(), settings)
                showVideos(shown, "Video bulunamadı")
            }
            else -> {
                titleView.text = "Klasörler"
                backButton.visibility = View.GONE
                sortButton.visibility = View.GONE
                viewButton.visibility = View.GONE
                val folders = VideoRepository.folders(this, all, settings)
                folderAdapter.items = folders
                if (list.layoutManager?.javaClass != LinearLayoutManager::class.java) {
                    list.layoutManager = LinearLayoutManager(this)
                }
                if (list.adapter !== folderAdapter) list.adapter = folderAdapter
                if (folders.isEmpty()) showMessage("Video bulunamadı") else hideMessage()
            }
        }
    }

    private fun showVideos(videos: List<VideoItem>, emptyText: String) {
        sortButton.visibility = View.VISIBLE
        viewButton.visibility = View.VISIBLE
        viewButton.setImageResource(if (settings.gridView) R.drawable.ic_view_list else R.drawable.ic_grid)
        for (ad in listOf(listAdapter, gridAdapter)) {
            ad.fields = settings.listFields
            ad.durationOnThumb = settings.durationOnThumb
        }
        // Aynı yerleşim ve bağdaştırıcı yeniden atanmaz; böylece kaydırma konumu korunur.
        if (settings.gridView) {
            if (list.layoutManager !is GridLayoutManager) {
                list.layoutManager = GridLayoutManager(this, (resources.displayMetrics.widthPixels / dp(170)).coerceAtLeast(2))
            }
            gridAdapter.items = videos
            if (list.adapter !== gridAdapter) list.adapter = gridAdapter
        } else {
            if (list.layoutManager?.javaClass != LinearLayoutManager::class.java) {
                list.layoutManager = LinearLayoutManager(this)
            }
            listAdapter.items = videos
            if (list.adapter !== listAdapter) list.adapter = listAdapter
        }
        if (videos.isEmpty()) showMessage(emptyText) else hideMessage()
    }

    private fun showMessage(text: String) {
        emptyView.text = text
        emptyView.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        emptyView.visibility = View.GONE
    }

    private fun openFolder(folder: FolderItem) {
        currentFolder = folder
        render()
        list.scrollToPosition(0)
    }

    private fun openSearch() {
        searching = true
        titleView.visibility = View.GONE
        searchField.visibility = View.VISIBLE
        searchField.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        render()
    }

    private fun closeSearch() {
        searching = false
        searchField.setText("")
        searchField.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchField.windowToken, 0)
        render()
    }

    // ------------------------------------------------------------ menüler

    private fun showSortMenu() {
        showListPanel(settings) { render() }
    }

    private fun showRootMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Ağ akışı aç")
        menu.menu.add(0, 2, 1, if (settings.showHiddenFolders) "Gizli klasörleri sakla" else "Gizli klasörleri göster")
        menu.menu.add(0, 4, 2, "Ağ kaynakları (SMB / FTP / WebDAV)")
        menu.menu.add(0, 3, 3, "Ayarlar")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showInput("Ağ akışı", "http://, https:// veya rtsp:// adresi") { url ->
                    val uri = Uri.parse(url.trim())
                    if (uri.scheme.isNullOrEmpty()) {
                        Toast.makeText(this, "Geçerli bir adres girin", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
                    }
                }
                2 -> {
                    settings.showHiddenFolders = !settings.showHiddenFolders
                    render()
                }
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
                4 -> startActivity(Intent(this, NetworkActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun folderMenu(folder: FolderItem) {
        val label = if (folder.hidden) "Klasörü göster" else "Klasörü gizle"
        showChoice(folder.name, listOf(label), -1) {
            val set = settings.hiddenFolders.toMutableSet()
            if (folder.hidden) set.remove(folder.bucketId) else set.add(folder.bucketId)
            settings.hiddenFolders = set
            render()
        }
    }

    private fun videoMenu(video: VideoItem, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Oynat")
        menu.menu.add(0, 2, 1, "Paylaş")
        menu.menu.add(0, 3, 2, "Yeniden adlandır")
        menu.menu.add(0, 4, 3, "Sil")
        menu.menu.add(0, 5, 4, "Bilgi")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> play(video)
                2 -> share(video)
                3 -> rename(video)
                4 -> confirmDelete(video)
                5 -> showInfo(video)
            }
            true
        }
        menu.show()
    }

    private fun share(video: VideoItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Paylaş"))
    }

    private fun showInfo(video: VideoItem) {
        val path = SubtitleFiles.pathOf(this, video.uri)
        showMessage(
            "Bilgi",
            buildString {
                appendLine("Ad: ${video.title}")
                appendLine("Klasör: ${video.bucketName}")
                if (path != null) appendLine("Konum: $path")
                appendLine("Boyut: ${"%.1f".format(video.sizeBytes / 1048576.0)} MB")
                appendLine("Süre: ${formatDuration(video.durationMs)}")
            }.trim()
        )
    }

    // ------------------------------------------------------------ yeniden adlandırma ve silme

    /** Android sürümüne göre yazma iznini isteyip işlemi çalıştırır. */
    private fun withWriteAccess(video: VideoItem, action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            pendingAction = action
            val request = MediaStore.createWriteRequest(contentResolver, listOf(video.uri))
            writeAccess.launch(IntentSenderRequest.Builder(request.intentSender).build())
            return
        }
        try {
            action()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                pendingAction = action
                writeAccess.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else {
                Toast.makeText(this, "Bu dosya için izin alınamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rename(video: VideoItem) {
        val ext = video.title.substringAfterLast('.', "")
        showInput("Yeniden adlandır", "Yeni ad", video.title.removeSuffix(if (ext.isEmpty()) "" else ".$ext")) { base ->
            val clean = base.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
            if (clean.isEmpty()) return@showInput
            val newName = if (ext.isEmpty()) clean else "$clean.$ext"
            withWriteAccess(video) {
                try {
                    contentResolver.update(video.uri, ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, newName)
                    }, null, null)
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(this, "Yeniden adlandırılamadı", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDelete(video: VideoItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage(video.title)
            .setPositiveButton("Sil") { _, _ -> delete(video) }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun delete(video: VideoItem) {
        if (Build.VERSION.SDK_INT >= 30) {
            pendingAction = { refresh() }
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(video.uri))
            writeAccess.launch(IntentSenderRequest.Builder(request.intentSender).build())
            return
        }
        try {
            contentResolver.delete(video.uri, null, null)
            refresh()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                pendingAction = { delete(video) }
                writeAccess.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else {
                Toast.makeText(this, "Silinemedi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------ oynatma

    private fun play(video: VideoItem) {
        startPlayer(shown, shown.indexOfFirst { it.id == video.id }.coerceAtLeast(0))
    }

    private fun resumeLast() {
        val last = ResumeStore.lastUri(this)
        val video = all.firstOrNull { it.uri.toString() == last }
        if (video == null) {
            Toast.makeText(this, "Henüz oynatılmış video yok", Toast.LENGTH_SHORT).show()
            return
        }
        val siblings = if (settings.listMode == ListMode.FILES && currentFolder == null) shown
            else VideoRepository.sort(this, all.filter { it.bucketId == video.bucketId }, settings)
        startPlayer(siblings, siblings.indexOfFirst { it.id == video.id }.coerceAtLeast(0))
    }

    private fun startPlayer(videos: List<VideoItem>, index: Int) {
        if (videos.isEmpty()) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(videos.map { it.uri }))
                .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(videos.map { it.title }))
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
        )
    }
}
