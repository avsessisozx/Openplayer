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
import android.annotation.SuppressLint
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

    private val folderAdapter = FolderAdapter({ onFolderClick(it) }, { startFolderSelection(it) })
    private val listAdapter = VideoAdapter(false, { onVideoClick(it) }, { v, _ -> videoSheet(v) }, { startVideoSelection(it) })
    private val gridAdapter = VideoAdapter(true, { onVideoClick(it) }, { v, _ -> videoSheet(v) }, { startVideoSelection(it) })

    private val fileOps = FileOps(this)
    private val selVideos = LinkedHashSet<Long>()
    private val selFolders = LinkedHashSet<Long>()
    private var selectionOn = false
    private lateinit var topBarView: View
    private lateinit var selBar: LinearLayout
    private lateinit var selTitle: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var fabView: View
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    private var all: List<VideoItem> = emptyList()
    private var currentFolder: FolderItem? = null
    /** Klasör ağacında içinde bulunulan klasörün yolu; null = en üst düzey. */
    private var treeDir: String? = null
    private var searching = false
    private var shown: List<VideoItem> = emptyList()


    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refresh() else showMessage(Tx.s(R.string.mainactivi_001))
        }

    private val openPhoto360 =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startActivity(Intent(this, Vr360PhotoActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                selectionOn -> exitSelection()
                searching -> closeSearch()
                currentFolder != null -> {
                    currentFolder = null
                    render()
                    // Klasör listesine dönünce kaldığı konuma geri getir.
                    folderListState?.let { st -> runCatching { list.layoutManager?.onRestoreInstanceState(st) } }
                }
                treeDir != null -> {
                    treeDir = treeParent(treeDir!!)
                    render()
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        // AppCompat kendi yapılandırmasıyla yoğunluk ölçeğini ezmesin.
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        ThemeManager.applyWindow(this)
        setContentView(buildUi())
    }

    private lateinit var modeTabs: LinearLayout

    override fun onResume() {
        super.onResume()
        modeTabs.visibility = if (settings.musicMode) View.VISIBLE else View.GONE
        swipe.isEnabled = settings.swipeRefresh
        fabView.visibility = if (settings.showFab && !selectionOn) View.VISIBLE else View.GONE
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
            hint = Tx.s(R.string.mainactivi_002)
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
                if (inFolderList()) settings.folderGrid = !settings.folderGrid
                else settings.gridView = !settings.gridView
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
        installPinch()
        emptyView = TextView(this).apply {
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { if (!hasPermission()) requestPermission.launch(permission) }
        }

        topBarView = topBar
        selTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            visibility = View.GONE
            addView(iconView(R.drawable.ic_close).apply { setOnClickListener { exitSelection() } })
            addView(selTitle, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(16) })
            addView(iconView(R.drawable.ic_play).apply { setOnClickListener { playSelection() } })
            addView(iconView(R.drawable.ic_info).apply { setOnClickListener { infoSelection() } })
            addView(iconView(R.drawable.ic_more_vert).apply { setOnClickListener { selectionMenu() } })
        }
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiColors.surface)
            visibility = View.GONE
            fun act(icon: Int, desc: String, onClick: () -> Unit) {
                addView(iconView(icon, 48, 12).apply {
                    contentDescription = desc
                    setOnClickListener { onClick() }
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            act(R.drawable.ic_check, Tx.s(R.string.mainactivi_003)) { selectAll() }
            act(R.drawable.ic_move, Tx.s(R.string.mainactivi_004)) { transferSelection(move = true) }
            act(R.drawable.ic_copy, Tx.s(R.string.mainactivi_005)) { transferSelection(move = false) }
            act(R.drawable.ic_playlist_add, Tx.s(R.string.mainactivi_073)) { addSelectionToPlaylist() }
            act(R.drawable.ic_edit, Tx.s(R.string.mainactivi_006)) { renameSelection() }
            act(R.drawable.ic_delete, Tx.s(R.string.mainactivi_007)) { deleteSelection() }
        }

        modeTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            for ((title, selected) in listOf(Tx.s(R.string.mainactivi_009) to true, Tx.s(R.string.mainactivi_008) to false)) {
                addView(TextView(context).apply {
                    text = title
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(if (selected) UiColors.accent else UiColors.textSecondary)
                    if (selected) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        paint.isUnderlineText = true
                    }
                    if (!selected) setOnClickListener { startActivity(Intent(this@MainActivity, MusicActivity::class.java)) }
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(selBar, LinearLayout.LayoutParams(-1, -2))
            addView(modeTabs, LinearLayout.LayoutParams(-1, -2))
            swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this@MainActivity).apply {
                setColorSchemeColors(UiColors.accent)
                setProgressBackgroundColorSchemeColor(UiColors.surface)
                addView(list, android.view.ViewGroup.LayoutParams(-1, -1))
                setOnRefreshListener {
                    if (hasPermission()) refresh() else isRefreshing = false
                }
            }
            addView(FrameLayout(this@MainActivity).apply {
                addView(swipe, FrameLayout.LayoutParams(-1, -1))
                addView(emptyView, FrameLayout.LayoutParams(-1, -1))
                pinchHud = TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(0xCC000000.toInt())
                    }
                    visibility = View.GONE
                }
                addView(pinchHud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(actionBar, LinearLayout.LayoutParams(-1, -2))
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
            contentDescription = Tx.s(R.string.mainactivi_010)
            setOnClickListener { resumeLast() }
        }

        fabView = fab
        return FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(UiColors.background)
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
                swipe.isRefreshing = false
                val first = all.isEmpty()
                all = if (settings.show360) loaded else loaded.filter { !Vr360.isSpherical(this, it.uri.toString(), it.title) }
                selVideos.retainAll(loaded.map { it.id }.toSet())
                selFolders.retainAll(loaded.map { it.bucketId }.toSet())
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
        renderInner()
        if (::selBar.isInitialized) updateSelectionUi()
    }

    private fun renderInner() {
        val query = searchField.text.toString().trim()
        when {
            searching -> {
                backButton.visibility = View.GONE
                shown = if (query.isEmpty()) emptyList()
                else VideoRepository.sort(this, visibleVideos().filter { it.title.contains(query, ignoreCase = true) }, settings)
                showVideos(shown, if (query.isEmpty()) Tx.s(R.string.mainactivi_012) else Tx.s(R.string.mainactivi_011))
            }
            currentFolder != null -> {
                val folder = currentFolder!!
                titleView.text = folder.name
                backButton.visibility = View.VISIBLE
                shown = VideoRepository.sort(this, all.filter { it.bucketId == folder.bucketId }, settings)
                applyHighlight(if (settings.markLastInFolder) lastPlayedIn(shown) else null)
                showVideos(shown, Tx.s(R.string.mainactivi_013))
            }
            settings.listMode == ListMode.FILES -> {
                titleView.text = Tx.s(R.string.mainactivi_009)
                applyHighlight(null)
                backButton.visibility = View.GONE
                shown = VideoRepository.sort(this, visibleVideos(), settings)
                showVideos(shown, Tx.s(R.string.mainactivi_014))
            }
            else -> {
                titleView.text = Tx.s(R.string.mainactivi_015)
                backButton.visibility = if (treeDir != null) View.VISIBLE else View.GONE
                if (treeDir != null) titleView.text = treeDir!!.substringAfterLast('/')
                sortButton.visibility = View.VISIBLE
                viewButton.visibility = View.VISIBLE
                viewButton.setImageResource(if (settings.folderGrid) R.drawable.ic_view_list else R.drawable.ic_grid)
                val folders = VideoRepository.treeFolders(this, all, settings, treeDir)
                folderAdapter.scalePercent = settings.folderScale
                folderAdapter.textScalePercent = settings.listTextScale
                folderAdapter.grid = settings.folderGrid
                folderAdapter.items = folders
                val wantGrid = settings.folderGrid
                val lmClass = list.layoutManager?.javaClass
                if (wantGrid && lmClass != androidx.recyclerview.widget.GridLayoutManager::class.java) {
                    list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, folderSpan())
                } else if (!wantGrid && lmClass != LinearLayoutManager::class.java) {
                    list.layoutManager = LinearLayoutManager(this)
                } else if (wantGrid) {
                    (list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount = folderSpan()
                }
                if (list.adapter !== folderAdapter) list.adapter = folderAdapter
                if (folders.isEmpty()) showMessage(Tx.s(R.string.mainactivi_014)) else hideMessage()
            }
        }
    }

    private fun gridSpan(): Int =
        Math.round(resources.displayMetrics.widthPixels / (dp(170) * settings.gridScale / 100f)).coerceIn(1, 8)

    private var pinchHud: TextView? = null
    private var lastPinchApply = 0L

    private fun pinchKind(): Int = if (list.adapter === folderAdapter) 0 else if (list.adapter === gridAdapter) 1 else 2

    /** İki parmakla çimdik: klasör/video simgelerini sürekli (serbest) boyutlandırır. */
    private fun installPinch() {
        val detector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val kind = pinchKind()
                val cur = when (kind) { 0 -> settings.folderScale; 1 -> settings.gridScale; else -> settings.listScale }
                val next = Math.round(cur * d.scaleFactor).coerceIn(25, 500)
                if (next == cur) return false
                when (kind) { 0 -> settings.folderScale = next; 1 -> settings.gridScale = next; else -> settings.listScale = next }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPinchApply > 60) {
                    lastPinchApply = now
                    applyPinch(kind)
                }
                pinchHud?.let { it.text = "%$next"; it.visibility = View.VISIBLE }
                return true
            }

            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                applyPinch(pinchKind())
                list.postDelayed({ pinchHud?.visibility = View.GONE }, 700)
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return e.pointerCount >= 2
            }

            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {
                detector.onTouchEvent(e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyPinch(kind: Int) {
        when (kind) {
            0 -> { folderAdapter.scalePercent = settings.folderScale; folderAdapter.notifyDataSetChanged() }
            1 -> {
                gridAdapter.scalePercent = settings.gridScale
                gridAdapter.spanCount = gridSpan()
                (list.layoutManager as? GridLayoutManager)?.spanCount = gridSpan()
                gridAdapter.notifyDataSetChanged()
            }
            else -> { listAdapter.scalePercent = settings.listScale; listAdapter.notifyDataSetChanged() }
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
        listAdapter.scalePercent = settings.listScale
        listAdapter.textScalePercent = settings.listTextScale
        gridAdapter.scalePercent = settings.gridScale
        gridAdapter.textScalePercent = settings.listTextScale
        gridAdapter.spanCount = gridSpan()
        // Aynı yerleşim ve bağdaştırıcı yeniden atanmaz; böylece kaydırma konumu korunur.
        if (settings.gridView) {
            if (list.layoutManager !is GridLayoutManager) {
                list.layoutManager = GridLayoutManager(this, gridSpan())
            } else {
                (list.layoutManager as GridLayoutManager).spanCount = gridSpan()
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

    private var folderListState: android.os.Parcelable? = null

    private fun openFolder(folder: FolderItem) {
        if (currentFolder == null) folderListState = list.layoutManager?.onSaveInstanceState()
        currentFolder = folder
        render()
        val target = if (settings.scrollToLast) lastPlayedIn(shown)?.let { u -> shown.indexOfFirst { it.uri.toString() == u } } ?: -1 else -1
        list.scrollToPosition(target.coerceAtLeast(0))
    }

    /** Listedeki en son oynatılan videonun adresi (hiç oynatılmadıysa null). */
    private fun lastPlayedIn(videos: List<VideoItem>): String? {
        var best: VideoItem? = null
        var bestAt = 0L
        for (v in videos) {
            val at = ResumeStore.lastPlayed(this, v.uri)
            if (at > bestAt) {
                bestAt = at
                best = v
            }
        }
        return best?.uri?.toString()
    }

    /** Vurgulanacak video: verilen (klasördeki son) ya da genel son oynatılan. */
    private fun applyHighlight(uri: String?) {
        val h = uri ?: ResumeStore.lastUri(this)
        listAdapter.highlightUri = h
        gridAdapter.highlightUri = h
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
        if (inFolderList()) showFolderOrder() else showListPanel(settings) { render() }
    }

    /** Ana ekranda klasör listesi mi gösteriliyor (klasör içi, arama ve tüm videolar değil)? */
    private fun inFolderList() = currentFolder == null && !searching && settings.listMode == ListMode.FOLDERS

    private fun folderSpan(): Int =
        Math.round(resources.displayMetrics.widthPixels / dp(150).toFloat()).coerceIn(2, 5)

    private fun showFolderOrder() {
        val keys = listOf("name_asc", "name_desc", "new_desc", "new_asc", "count_desc", "count_asc", "size_desc", "size_asc")
        val labels = listOf(
            Tx.s(R.string.mainactivi_017), Tx.s(R.string.mainactivi_016),
            Tx.s(R.string.mainactivi_019), Tx.s(R.string.mainactivi_018),
            Tx.s(R.string.mainactivi_021), Tx.s(R.string.mainactivi_020),
            Tx.s(R.string.mainactivi_023), Tx.s(R.string.mainactivi_022),
        )
        showChoice(Tx.s(R.string.mainactivi_024), labels, keys.indexOf(settings.folderOrder)) { i ->
            settings.folderOrder = keys[i]
            render()
        }
    }

    private fun showRootMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 5, 0, Tx.s(R.string.mainactivi_074))
        menu.menu.add(0, 6, 1, Tx.s(R.string.mainactivi_025))
        menu.menu.add(0, 7, 2, Tx.s(R.string.mainactivi_026))
        menu.menu.add(0, 1, 2, Tx.s(R.string.mainactivi_027))
        menu.menu.add(0, 2, 3, if (settings.showHiddenFolders) Tx.s(R.string.mainactivi_028) else Tx.s(R.string.listpanel_018))
        menu.menu.add(0, 4, 4, Tx.s(R.string.mainactivi_029))
        menu.menu.add(0, 8, 5, Tx.s(R.string.mainactivi_030))
        menu.menu.add(0, 10, 5, Tx.s(R.string.mainactivi_031))
        menu.menu.add(0, 3, 6, Tx.s(R.string.mainactivi_032))
        if (settings.exitButton) menu.menu.add(0, 9, 7, Tx.s(R.string.mainactivi_033))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showInput(Tx.s(R.string.mainactivi_034), "http://, https:// veya rtsp:// adresi") { url ->
                    val uri = Uri.parse(url.trim())
                    if (uri.scheme.isNullOrEmpty()) {
                        Toast.makeText(this, Tx.s(R.string.mainactivi_035), Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
                    }
                }
                2 -> {
                    settings.showHiddenFolders = !settings.showHiddenFolders
                    render()
                }
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
                10 -> openPhoto360.launch(arrayOf("image/*"))
                7 -> startActivity(Intent(this, VaultActivity::class.java))
                5 -> startActivity(Intent(this, PlaylistActivity::class.java).putExtra(PlaylistActivity.EXTRA_KIND, PlaylistStore.VIDEO))
                6 -> startActivity(
                    Intent(this, PlaylistActivity::class.java)
                        .putExtra(PlaylistActivity.EXTRA_KIND, PlaylistStore.VIDEO)
                        .putExtra(PlaylistActivity.EXTRA_OPEN, PlaylistActivity.FAV)
                )
                4 -> startActivity(Intent(this, NetworkActivity::class.java))
                8 -> startActivity(
                    Intent(this, PlaylistActivity::class.java)
                        .putExtra(PlaylistActivity.EXTRA_KIND, PlaylistStore.VIDEO)
                        .putExtra(PlaylistActivity.EXTRA_MANAGER, true)
                )
                9 -> finishAffinity()
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------ seçim modu

    private fun folderMode(): Boolean =
        selFolders.isNotEmpty() || (currentFolder == null && !searching && settings.listMode == ListMode.FOLDERS)

    private fun onVideoClick(v: VideoItem) {
        if (selectionOn) toggleVideo(v) else play(v)
    }

    private fun onFolderClick(f: FolderItem) {
        when {
            f.group -> if (!selectionOn) {
                folderListState = list.layoutManager?.onSaveInstanceState()
                treeDir = f.path
                render()
                list.scrollToPosition(0)
            }
            selectionOn -> toggleFolder(f)
            else -> openFolder(f)
        }
    }

    /** Ağaçta bir üst klasör; en üst düzeye (depolama köküne) varınca null. */
    private fun treeParent(dir: String): String? {
        val up = dir.substringBeforeLast('/', "")
        val parts = up.split('/')
        val isRoot = up.isEmpty() ||
            (up.startsWith("/storage/emulated/") && parts.size <= 4) || (up.startsWith("/storage/") && parts.size <= 3)
        return if (isRoot) null else up
    }

    private fun startVideoSelection(v: VideoItem) {
        selectionOn = true
        if (!selVideos.add(v.id)) selVideos.remove(v.id)
        updateSelectionUi()
    }

    private fun startFolderSelection(f: FolderItem) {
        if (f.group) return
        selectionOn = true
        if (!selFolders.add(f.bucketId)) selFolders.remove(f.bucketId)
        updateSelectionUi()
    }

    private fun toggleVideo(v: VideoItem) {
        if (!selVideos.add(v.id)) selVideos.remove(v.id)
        updateSelectionUi()
    }

    private fun toggleFolder(f: FolderItem) {
        if (!selFolders.add(f.bucketId)) selFolders.remove(f.bucketId)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selVideos.clear()
        selFolders.clear()
        selectionOn = false
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val folders = folderMode()
        val count = if (folders) selFolders.size else selVideos.size
        if (selectionOn && count == 0) selectionOn = false
        topBarView.visibility = if (selectionOn) View.GONE else View.VISIBLE
        selBar.visibility = if (selectionOn) View.VISIBLE else View.GONE
        actionBar.visibility = if (selectionOn) View.VISIBLE else View.GONE
        fabView.visibility = if (selectionOn || !settings.showFab) View.GONE else View.VISIBLE
        if (::modeTabs.isInitialized) modeTabs.visibility = if (settings.musicMode && !selectionOn) View.VISIBLE else View.GONE
        val total = if (folders) folderAdapter.items.size else shown.size
        selTitle.text = Tx.s(R.string.mainactivi_036, count, total)
        folderAdapter.selected = selFolders.toSet()
        for (ad in listOf(listAdapter, gridAdapter)) {
            ad.selecting = selectionOn
            ad.selected = selVideos.toSet()
        }
    }

    /** Seçimdeki videolar (klasör seçiliyse klasörlerdeki tüm videolar). */
    private fun targetVideos(): List<VideoItem> =
        if (folderMode()) VideoRepository.sort(this, all.filter { it.bucketId in selFolders }, settings)
        else shown.filter { it.id in selVideos }

    private fun folderDir(bucketId: Long): java.io.File? =
        all.firstOrNull { it.bucketId == bucketId }?.path?.takeIf { it.isNotEmpty() }
            ?.substringBeforeLast('/')?.let { java.io.File(it) }

    private fun selectAll() {
        if (folderMode()) folderAdapter.items.filter { !it.group }.forEach { selFolders.add(it.bucketId) }
        else shown.forEach { selVideos.add(it.id) }
        selectionOn = true
        updateSelectionUi()
    }

    private fun invertSelection() {
        if (folderMode()) {
            val now = folderAdapter.items.filter { !it.group }.map { it.bucketId }.filter { it !in selFolders }
            selFolders.clear(); selFolders.addAll(now)
        } else {
            val now = shown.map { it.id }.filter { it !in selVideos }
            selVideos.clear(); selVideos.addAll(now)
        }
        updateSelectionUi()
    }

    /** İlk ve son seçili öğe arasındakilerin hepsini seçer. */
    private fun selectRange() {
        if (folderMode()) {
            val idx = folderAdapter.items.withIndex().filter { it.value.bucketId in selFolders }.map { it.index }
            if (idx.size >= 2) for (i in idx.min()..idx.max()) selFolders.add(folderAdapter.items[i].bucketId)
        } else {
            val idx = shown.withIndex().filter { it.value.id in selVideos }.map { it.index }
            if (idx.size >= 2) for (i in idx.min()..idx.max()) selVideos.add(shown[i].id)
        }
        updateSelectionUi()
    }

    private fun playSelection() {
        val v = targetVideos()
        if (v.isEmpty()) return
        exitSelection()
        startPlayer(v, 0)
    }

    private fun summaryLines(videos: List<VideoItem>): List<Pair<String, String>> {
        val size = videos.sumOf { it.sizeBytes }
        val dur = videos.sumOf { it.durationMs }
        return listOf(
            Tx.s(R.string.mainactivi_037) to videos.size.toString(),
            Tx.s(R.string.mainactivi_075) to android.text.format.Formatter.formatFileSize(this, size),
            Tx.s(R.string.mainactivi_038) to formatDuration(dur),
        )
    }

    private fun infoSelection() {
        val videos = targetVideos()
        if (videos.isEmpty()) return
        if (!folderMode() && videos.size == 1) {
            val v = videos[0]
            showMediaInfo(v.title, v.uri, v.sizeBytes, v.dateAddedSec * 1000L)
            return
        }
        val lines = ArrayList<Pair<String, String>>()
        if (folderMode()) {
            val names = folderAdapter.items.filter { it.bucketId in selFolders }.map { it.name }
            lines += (if (names.size == 1) Tx.s(R.string.mainactivi_039) else Tx.s(R.string.mainactivi_015)) to names.joinToString(", ")
            if (selFolders.size == 1) folderDir(selFolders.first())?.let { lines += Tx.s(R.string.mainactivi_076) to it.absolutePath }
        }
        lines += summaryLines(videos)
        showSummaryInfo(if (folderMode()) Tx.s(R.string.mainactivi_041) else Tx.s(R.string.mainactivi_040), lines)
    }

    private fun selectionMenu() {
        val videos = targetVideos()
        val entries = ArrayList<SheetEntry>()
        if (videos.isNotEmpty()) {
            val uris = videos.map { it.uri.toString() }
            val allFav = uris.all { PlaylistStore.isFavorite(this, PlaylistStore.VIDEO, it) }
            entries += SheetEntry(if (allFav) R.drawable.ic_favorite_border else R.drawable.ic_favorite,
                if (allFav) Tx.s(R.string.mainactivi_042) else Tx.s(R.string.mainactivi_077)) {
                PlaylistStore.setFavorites(this, PlaylistStore.VIDEO, uris, !allFav)
                Toast.makeText(this, if (allFav) Tx.s(R.string.mainactivi_044) else Tx.s(R.string.mainactivi_043), Toast.LENGTH_SHORT).show()
                exitSelection()
            }
            entries += SheetEntry(R.drawable.ic_share, Tx.s(R.string.mainactivi_045)) { shareMany(videos) }
            entries += SheetEntry(R.drawable.ic_lock, Tx.s(R.string.mainactivi_046)) {
                lockToVault(fileOps, videos) { exitSelection(); refresh() }
            }
            if (videos.size == 1) {
                val v = videos[0]
                entries += SheetEntry(R.drawable.ic_cut, Tx.s(R.string.mainactivi_047)) { showTrimDialog(v.uri, v.title, v.durationMs) { refresh() } }
                entries += SheetEntry(R.drawable.ic_headphones, Tx.s(R.string.mainactivi_048)) { showExtractAudio(v.uri, v.title) }
            }
        }
        entries += SheetEntry(R.drawable.ic_swap, Tx.s(R.string.mainactivi_049)) { invertSelection() }
        entries += SheetEntry(R.drawable.ic_list, Tx.s(R.string.mainactivi_050)) { selectRange() }
        if (folderMode()) {
            val hide = folderAdapter.items.filter { it.bucketId in selFolders }.any { !it.hidden }
            entries += SheetEntry(R.drawable.ic_folder, if (hide) Tx.s(R.string.mainactivi_052) else Tx.s(R.string.mainactivi_051)) {
                val set = settings.hiddenFolders.toMutableSet()
                if (hide) set.addAll(selFolders) else set.removeAll(selFolders)
                settings.hiddenFolders = set
                exitSelection()
                render()
            }
        }
        showSheet(Tx.s(R.string.mainactivi_053), entries)
    }

    private fun transferSelection(move: Boolean) {
        val videos = targetVideos()
        if (videos.isEmpty()) return
        if (!fileOps.requireAllFiles()) return
        val start = videos.firstOrNull()?.path?.takeIf { it.isNotEmpty() }?.let { java.io.File(it).parentFile }
        fileOps.pickFolder(if (move) Tx.s(R.string.mainactivi_055) else Tx.s(R.string.mainactivi_054), start) { dest ->
            val items = videos.map { TransferItem(it.uri, it.path.takeIf { p -> p.isNotEmpty() }, it.title) }
            fileOps.transfer(items, dest, move) {
                exitSelection()
                refresh()
            }
        }
    }

    private fun addSelectionToPlaylist() {
        val uris = targetVideos().map { it.uri.toString() }
        if (uris.isEmpty()) return
        showAddToPlaylist(PlaylistStore.VIDEO, uris) { exitSelection() }
    }

    private fun renameSelection() {
        if (folderMode()) {
            if (selFolders.size != 1) {
                Toast.makeText(this, Tx.s(R.string.mainactivi_056), Toast.LENGTH_SHORT).show()
                return
            }
            val id = selFolders.first()
            val dir = folderDir(id)
            if (dir == null) {
                Toast.makeText(this, Tx.s(R.string.mainactivi_057), Toast.LENGTH_SHORT).show()
                return
            }
            showInput(Tx.s(R.string.mainactivi_058), Tx.s(R.string.mainactivi_078), dir.name) { n ->
                val paths = all.filter { it.bucketId == id }.map { it.path }.filter { it.isNotEmpty() }
                fileOps.renameFolder(dir, n, paths) {
                    exitSelection()
                    refresh()
                }
            }
            return
        }
        val videos = targetVideos()
        when {
            videos.isEmpty() -> Unit
            videos.size == 1 -> renameOne(videos[0])
            else -> batchRename(videos)
        }
    }

    private fun renameOne(video: VideoItem) {
        val ext = video.title.substringAfterLast('.', "")
        showInput(Tx.s(R.string.mainactivi_006), Tx.s(R.string.mainactivi_078), video.title.removeSuffix(if (ext.isEmpty()) "" else ".$ext")) { base ->
            val clean = base.trim()
            if (clean.isEmpty()) return@showInput
            val newName = if (ext.isEmpty()) clean else "$clean.$ext"
            fileOps.rename(listOf(video.uri), listOf(newName)) {
                exitSelection()
                refresh()
            }
        }
    }

    private fun batchRename(videos: List<VideoItem>) {
        fun field(hint: String, initial: String = "") = EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSingleLine()
        }
        val pattern = field(Tx.s(R.string.listpanel_014), "{name}")
        val find = field(Tx.s(R.string.mainactivi_059))
        val repl = field(Tx.s(R.string.mainactivi_060))
        val preview = label("", 12f, UiColors.textSecondary)
        fun compute(i: Int, v: VideoItem): String {
            val ext = v.title.substringAfterLast('.', "")
            val base = v.title.removeSuffix(if (ext.isEmpty()) "" else ".$ext")
            var s = pattern.text.toString()
                .replace("{name}", base)
                .replace("{nnn}", "%03d".format(i + 1))
                .replace("{nn}", "%02d".format(i + 1))
                .replace("{n}", (i + 1).toString())
            val f = find.text.toString()
            if (f.isNotEmpty()) s = s.replace(f, repl.text.toString())
            return if (ext.isEmpty()) s else "$s.$ext"
        }
        fun updatePreview() {
            preview.text = Tx.s(R.string.mainactivi_061) + compute(0, videos[0])
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        pattern.addTextChangedListener(watcher)
        find.addTextChangedListener(watcher)
        repl.addTextChangedListener(watcher)
        updatePreview()
        val box = vertical {
            addView(label(Tx.s(R.string.mainactivi_062), 12f, UiColors.textSecondary))
            addView(pattern)
            addView(find)
            addView(repl)
            addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        PanelBuilder(this)
            .setTitle(Tx.s(R.string.mainactivi_063, videos.size))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(Tx.s(R.string.mainactivi_064)) { _, _ ->
                val names = videos.mapIndexed { i, v -> compute(i, v) }
                if (names.toSet().size != names.size) {
                    Toast.makeText(this, Tx.s(R.string.mainactivi_065), Toast.LENGTH_LONG).show()
                } else {
                    fileOps.rename(videos.map { it.uri }, names) {
                        exitSelection()
                        refresh()
                    }
                }
            }
            .setNegativeButton(Tx.s(R.string.fileops_004), null)
            .show()
    }

    private fun deleteSelection() {
        val videos = targetVideos()
        if (videos.isEmpty()) return
        val trash = Build.VERSION.SDK_INT >= 30 && settings.useTrash
        val what = if (folderMode()) Tx.s(R.string.mainactivi_066, selFolders.size, videos.size) else "${videos.size} video"
        PanelBuilder(this)
            .setTitle(Tx.s(R.string.mainactivi_079))
            .setMessage("$what ${if (trash) "çöp kutusuna taşınacak (30 gün içinde geri alınabilir)." else "kalıcı olarak silinecek."}")
            .setPositiveButton(Tx.s(R.string.mainactivi_007)) { _, _ ->
                val subs = if (!trash && settings.deleteSubtitlesToo) videos.flatMap { SubtitleFiles.exactSidecarsForPath(it.path) } else emptyList()
                fileOps.delete(videos.map { it.uri }) {
                    subs.forEach { runCatching { it.delete() } }
                    exitSelection()
                    refresh()
                }
            }
            .setNegativeButton(Tx.s(R.string.fileops_004), null)
            .show()
    }

    // ------------------------------------------------------------ satır menüsü (⋮)

    private fun videoSheet(video: VideoItem) {
        val u = video.uri.toString()
        val fav = PlaylistStore.isFavorite(this, PlaylistStore.VIDEO, u)
        val entries = ArrayList<SheetEntry>()
        entries += SheetEntry(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
            if (fav) Tx.s(R.string.mainactivi_042) else Tx.s(R.string.mainactivi_077)) {
            PlaylistStore.toggleFavorite(this, PlaylistStore.VIDEO, u)
        }
        entries += SheetEntry(R.drawable.ic_playlist_add, Tx.s(R.string.mainactivi_067)) {
            showAddToPlaylist(PlaylistStore.VIDEO, listOf(u))
        }
        entries += SheetEntry(R.drawable.ic_share, Tx.s(R.string.mainactivi_045)) { shareMany(listOf(video)) }
        entries += SheetEntry(R.drawable.ic_edit, Tx.s(R.string.mainactivi_006)) { renameOne(video) }
        entries += SheetEntry(R.drawable.ic_subtitles, Tx.s(R.string.mainactivi_068)) {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, arrayListOf(video.uri))
                    .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf(video.title))
                    .putExtra(PlayerActivity.EXTRA_INDEX, 0)
                    .putExtra("org.openplayer.app.OPEN_SUBTITLE_SEARCH", true)
            )
        }
        entries += SheetEntry(R.drawable.ic_cut, Tx.s(R.string.mainactivi_047)) {
            showTrimDialog(video.uri, video.title, video.durationMs) { refresh() }
        }
        entries += SheetEntry(R.drawable.ic_headphones, Tx.s(R.string.mainactivi_048)) {
            showExtractAudio(video.uri, video.title)
        }
        entries += SheetEntry(R.drawable.ic_lock, Tx.s(R.string.mainactivi_046)) {
            lockToVault(fileOps, listOf(video)) { refresh() }
        }
        entries += SheetEntry(R.drawable.ic_info, Tx.s(R.string.mainactivi_069)) {
            showMediaInfo(video.title, video.uri, video.sizeBytes, video.dateAddedSec * 1000L)
        }
        entries += SheetEntry(R.drawable.ic_delete, Tx.s(R.string.mainactivi_007), danger = true) { deleteSingle(video) }
        showSheet(video.title, entries)
    }

    private fun deleteSingle(video: VideoItem) {
        val trash = Build.VERSION.SDK_INT >= 30 && settings.useTrash
        PanelBuilder(this)
            .setTitle(Tx.s(R.string.mainactivi_079))
            .setMessage(video.title + "\n\n" + if (trash) Tx.s(R.string.mainactivi_071) else Tx.s(R.string.mainactivi_070))
            .setPositiveButton(Tx.s(R.string.mainactivi_007)) { _, _ ->
                val subs = if (!trash && settings.deleteSubtitlesToo) SubtitleFiles.exactSidecarsForPath(video.path) else emptyList()
                fileOps.delete(listOf(video.uri)) {
                    subs.forEach { runCatching { it.delete() } }
                    refresh()
                }
            }
            .setNegativeButton(Tx.s(R.string.fileops_004), null)
            .show()
    }

    private fun shareMany(videos: List<VideoItem>) {
        val send = if (videos.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, videos[0].uri)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(videos.map { it.uri }))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, Tx.s(R.string.mainactivi_045)))
    }

    // ------------------------------------------------------------ oynatma

    private fun play(video: VideoItem) {
        startPlayer(shown, shown.indexOfFirst { it.id == video.id }.coerceAtLeast(0))
    }

    private fun resumeLast() {
        val last = ResumeStore.lastUri(this)
        val video = all.firstOrNull { it.uri.toString() == last }
        if (video == null) {
            Toast.makeText(this, Tx.s(R.string.mainactivi_072), Toast.LENGTH_SHORT).show()
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
