package org.openplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Müzik kütüphanesi: şarkılar, albümler, sanatçılar, klasörler; altta mini oynatıcı. */
class MusicActivity : AppCompatActivity() {

    private enum class Tab(val title: String) {
        HOME("Kitaplık"), SONGS("Şarkılar"), ALBUMS("Albümler"), ARTISTS("Sanatçılar"),
        ALBUM_ARTISTS("Albüm sanatçıları"), GENRES("Tarzlar"), YEARS("Yıllar"), COMPOSERS("Besteciler"),
        FOLDERS("Klasörler"), TREE("Klasör hiyerarşisi")
    }

    private class Row(
        val title: String,
        val subtitle: String,
        val art: MusicTrack?,
        val icon: Int = 0,
        val iconBg: Int = 0,
        val trailing: String? = null,
        val highlight: Boolean = false,
        val trackId: Long = -1L,
        val onMore: (() -> Unit)? = null,
        val onLong: (() -> Unit)? = null,
        val onClick: () -> Unit,
    )

    private lateinit var settings: AppSettings
    private lateinit var titleView: TextView
    private lateinit var searchField: EditText
    private lateinit var backButton: ImageView
    private lateinit var searchButton: ImageView
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView
    private lateinit var tabRow: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView

    private val fileOps = FileOps(this)
    private val selected = LinkedHashSet<Long>()
    private var selectionOn = false
    private var shownTracks: List<MusicTrack> = emptyList()
    private lateinit var topBarView: View
    private lateinit var selBar: LinearLayout
    private lateinit var selTitle: TextView
    private lateinit var actionBar: LinearLayout
    private var pendingTrack: MusicTrack? = null

    private val pickCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tr = pendingTrack
        pendingTrack = null
        if (uri != null && tr != null) {
            val ok = MusicTagStore.setCover(this, tr.id, uri)
            Toast.makeText(this, if (ok) "Kapak değiştirildi" else "Görsel okunamadı", Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private val pickLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tr = pendingTrack
        pendingTrack = null
        if (uri != null && tr != null) {
            val ok = Lyrics.importFile(this, tr.id, uri)
            Toast.makeText(this, if (ok) "Söz dosyası eklendi" else "Dosya okunamadı veya çok büyük", Toast.LENGTH_SHORT).show()
        }
    }

    private var all: List<MusicTrack> = emptyList()
    private var tab = Tab.SONGS
    private var treePath: String? = null
    private lateinit var tabScroll: android.widget.HorizontalScrollView
    private var detail: MusicGroup? = null
    private var searching = false
    private val adapter = RowAdapter()

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load() else showEmpty("Müzikleri göstermek için izin gerekli.\nDokunarak tekrar dene.")
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                selectionOn -> exitSelection()
                searching -> closeSearch()
                detail != null -> {
                    detail = null
                    render()
                }
                tab == Tab.TREE && treePath != null && treePath != treeRoot() -> {
                    treePath = treePath!!.substringBeforeLast('/')
                    render()
                }
                tab != Tab.HOME && settings.musicStartHome -> {
                    tab = Tab.HOME
                    render()
                }
                else -> finish()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateMini()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) render(keepScroll = true)
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
        settings = AppSettings.get(this)
        tab = if (settings.musicStartHome) Tab.HOME else Tab.SONGS
        ThemeManager.applyWindow(this)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.get(this).addListener(playerListener)
        updateMini()
        if (hasPermission()) load() else requestPermission.launch(permission)
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private var askedNotifications = false

    override fun onStop() {
        PlayerHolder.player?.removeListener(playerListener)
        super.onStop()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ arayüz

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buildUi(): View {
        backButton = iconView(R.drawable.ic_back).apply { setOnClickListener { backCallback.handleOnBackPressed() } }
        titleView = text(22f, bold = true).apply { text = "Müzik" }
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
        val sortButton = iconView(R.drawable.ic_sort).apply { setOnClickListener { showSortMenu() } }
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
            addView(iconView(R.drawable.ic_playlist_add).apply {
                contentDescription = "Listeler"
                setOnClickListener {
                    startActivity(Intent(this@MusicActivity, PlaylistActivity::class.java)
                        .putExtra(PlaylistActivity.EXTRA_KIND, PlaylistStore.MUSIC))
                }
            })
        }

        topBarView = topBar
        selTitle = text(20f)
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            visibility = View.GONE
            addView(iconView(R.drawable.ic_close).apply { setOnClickListener { exitSelection() } })
            addView(selTitle, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(16) })
            addView(iconView(R.drawable.ic_play).apply { setOnClickListener { playSelection() } })
            addView(iconView(R.drawable.ic_info).apply { setOnClickListener { infoSelection() } })
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
            act(R.drawable.ic_check, "Hepsini seç") { selectAll() }
            act(R.drawable.ic_playlist_add, "Listeye ekle") { addSelectionToPlaylist() }
            act(R.drawable.ic_edit, "Etiketleri düzenle") { editTags(selectedTracks()) }
            act(R.drawable.ic_share, "Paylaş") { shareTracks(selectedTracks()) }
            act(R.drawable.ic_delete, "Sil") { deleteTracks(selectedTracks()) }
        }

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, FrameLayout.LayoutParams(-2, -1))
        }
        buildTabs()

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MusicActivity)
            adapter = this@MusicActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        installListGestures()
        emptyView = text(16f, UiColors.textSecondary).apply {
            maxLines = 6
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { if (!hasPermission()) requestPermission.launch(permission) }
        }

        miniArt = ImageView(this)
        ArtLoader.roundCorners(miniArt, 8)
        miniTitle = text(15f, bold = true)
        miniSub = text(12f, UiColors.textSecondary)
        miniPlay = iconView(R.drawable.ic_play).apply {
            setOnClickListener {
                val p = PlayerHolder.player ?: return@setOnClickListener
                if (p.isPlaying) p.pause() else p.play()
            }
        }
        val miniNext = iconView(R.drawable.ic_next).apply {
            setOnClickListener { PlayerHolder.player?.seekToNextMediaItem() }
        }
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(4), dp(8))
            background = GradientDrawable().apply { setColor(UiColors.surface) }
            visibility = View.GONE
            addView(miniArt, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(miniTitle)
                addView(miniSub)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(miniPlay)
            addView(miniNext)
            setOnClickListener { startActivity(Intent(this@MusicActivity, NowPlayingActivity::class.java)) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(UiColors.background)
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(selBar, LinearLayout.LayoutParams(-1, -2))
            addView(tabScroll, LinearLayout.LayoutParams(-1, dp(44)))
            addView(FrameLayout(this@MusicActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(mini, LinearLayout.LayoutParams(-1, -2))
            addView(actionBar, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        // "Videolar" sekmesi ilk sırada: müzik modundan videoya dönüş.
        tabRow.addView(tabView("Videolar", false) { finish() }, LinearLayout.LayoutParams(-2, -1))
        var selectedView: View? = null
        for (t in Tab.entries) {
            val v = tabView(t.title, t == tab && detail == null) {
                tab = t
                detail = null
                treePath = null
                closeSearch()
                render()
            }
            if (t == tab) selectedView = v
            tabRow.addView(v, LinearLayout.LayoutParams(-2, -1))
        }
        tabRow.addView(tabView("Listeler", false) {
            startActivity(Intent(this, PlaylistActivity::class.java).putExtra(PlaylistActivity.EXTRA_KIND, PlaylistStore.MUSIC))
        }, LinearLayout.LayoutParams(-2, -1))
        selectedView?.let { v -> tabScroll.post { tabScroll.smoothScrollTo((v.left - dp(40)).coerceAtLeast(0), 0) } }
    }

    private fun switchTab(delta: Int) {
        val all = Tab.entries
        val next = all.indexOf(tab) + delta
        if (next !in all.indices) return
        tab = all[next]
        detail = null
        treePath = null
        closeSearch()
        render()
        list.translationX = delta * dp(60).toFloat()
        list.alpha = 0.3f
        list.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private var pinchLast = 0L

    /** Listede sağa/sola kaydırınca sekme değişir; iki parmakla çimdik simge boyutunu ayarlar. */
    private fun installListGestures() {
        val scale = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val cur = settings.musicScale
                val next = Math.round(cur * d.scaleFactor).coerceIn(30, 400)
                if (next == cur) return false
                settings.musicScale = next
                val now = android.os.SystemClock.uptimeMillis()
                if (now - pinchLast > 60) {
                    pinchLast = now
                    adapter.refreshSelection()
                }
                return true
            }

            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                adapter.refreshSelection()
            }
        })
        var downX = 0f
        var downY = 0f
        var swiping = false
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                scale.onTouchEvent(e)
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        swiping = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !swiping) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (kotlin.math.abs(dx) > dp(24) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.8f) swiping = true
                    }
                }
                return e.pointerCount >= 2 || swiping
            }

            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {
                scale.onTouchEvent(e)
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_UP -> {
                        if (swiping) {
                            val dx = e.x - downX
                            if (kotlin.math.abs(dx) > dp(70)) switchTab(if (dx < 0) 1 else -1)
                        }
                        swiping = false
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> swiping = false
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun tabView(title: String, selected: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(if (selected) UiColors.accent else UiColors.textSecondary)
            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { onClick() }
            if (selected) paint.isUnderlineText = true
        }

    private fun openSearch() {
        searching = true
        titleView.visibility = View.GONE
        searchField.visibility = View.VISIBLE
        searchField.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(searchField, 0)
        render()
    }

    private fun closeSearch() {
        if (!searching) return
        searching = false
        searchField.setText("")
        searchField.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(searchField.windowToken, 0)
        render()
    }

    private fun showSortMenu() {
        val all = MusicSort.entries
        showChoice("Şarkıları sırala", all.map { it.label }, all.indexOf(settings.musicSort)) {
            settings.musicSort = all[it]
            render()
        }
    }

    // ------------------------------------------------------------ veri

    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun load() {
        val app = applicationContext
        loader.execute {
            val loaded = MusicRepository.loadAll(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                all = loaded
                render()
            }
        }
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
        adapter.rows = emptyList()
    }

    private fun currentMediaId(): String? {
        val item = PlayerHolder.player?.currentMediaItem
        return if (MusicController.isMusic(item)) item?.mediaId else null
    }

    private fun render(keepScroll: Boolean = false) {
        buildTabs()
        val query = searchField.text.toString().trim()
        val playingId = currentMediaId()
        val rows = ArrayList<Row>()
        val d = detail

        fun songRows(tracks: List<MusicTrack>) {
            shownTracks = tracks
            tracks.forEachIndexed { i, t ->
                rows += Row(
                    t.title, "${t.artist} • ${t.album}", t, trailing = formatDuration(t.durationMs),
                    highlight = t.id.toString() == playingId,
                    trackId = t.id,
                    onMore = { showTrackMenu(t) },
                    onLong = { toggleSelect(t) },
                ) {
                    if (selectionOn) toggleSelect(t) else { MusicController.play(this, tracks, i); afterPlay() }
                }
            }
        }
        shownTracks = emptyList()

        when {
            searching -> {
                titleView.text = "Ara"
                if (query.isEmpty()) {
                    showEmpty("Aramak için yazın")
                    return
                }
                val q = query.lowercase()
                songRows(MusicRepository.sort(
                    all.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) },
                    settings.musicSort
                ))
            }
            d != null -> {
                titleView.text = d.title
                rows += Row("Hepsini çal", "${d.tracks.size} şarkı", null, icon = R.drawable.ic_play) {
                    MusicController.play(this, d.tracks, 0, shuffle = false); afterPlay()
                }
                rows += Row("Karıştır", "Rastgele sırayla çal", null, icon = R.drawable.ic_shuffle) {
                    MusicController.play(this, d.tracks, (d.tracks.indices).random(), shuffle = true); afterPlay()
                }
                songRows(d.tracks)
            }
            tab == Tab.HOME -> {
                titleView.text = "Kitaplık"
                fun entry(t: Tab, icon: Int, color: Int, count: String) {
                    rows += Row(t.title, count, null, icon = icon, iconBg = color) {
                        tab = t
                        treePath = null
                        render()
                    }
                }
                entry(Tab.SONGS, R.drawable.ic_music_note, 0xFF6F86D6.toInt(), "${all.size} şarkı")
                entry(Tab.FOLDERS, R.drawable.ic_folder, 0xFF3B82F6.toInt(), "${MusicRepository.folders(all).size} klasör")
                entry(Tab.TREE, R.drawable.ic_move, 0xFF5B6EE1.toInt(), "Klasör ağacında gez")
                entry(Tab.ALBUMS, R.drawable.ic_album, 0xFF6050C0.toInt(), "${MusicRepository.albums(all).size} albüm")
                entry(Tab.ARTISTS, R.drawable.ic_mic, 0xFF5A5599.toInt(), "${MusicRepository.artists(all).size} sanatçı")
                entry(Tab.ALBUM_ARTISTS, R.drawable.ic_mic, 0xFF6A5FA8.toInt(), "${MusicRepository.albumArtists(all).size} albüm sanatçısı")
                entry(Tab.GENRES, R.drawable.ic_headphones, 0xFF7A4A8C.toInt(), "${MusicRepository.genres(all).size} tarz")
                entry(Tab.YEARS, R.drawable.ic_calendar, 0xFF3F8FA0.toInt(), "${MusicRepository.years(all).size} yıl")
                entry(Tab.COMPOSERS, R.drawable.ic_person, 0xFF2F7D4F.toInt(), "${MusicRepository.composers(all).size} besteci")
                rows += Row("Çalma listeleri", "Favoriler ve listeleriniz", null, icon = R.drawable.ic_list, iconBg = 0xFF8A4B3F.toInt()) {
                    startActivity(Intent(this, PlaylistActivity::class.java).putExtra(PlaylistActivity.EXTRA_KIND, PlaylistStore.MUSIC))
                }
            }
            tab == Tab.SONGS -> {
                titleView.text = "Müzik"
                val sorted = MusicRepository.sort(all, settings.musicSort)
                if (sorted.isNotEmpty()) {
                    rows += Row("Tümünü karıştır", "${sorted.size} şarkı", null, icon = R.drawable.ic_shuffle) {
                        MusicController.play(this, sorted, sorted.indices.random(), shuffle = true); afterPlay()
                    }
                }
                songRows(sorted)
            }
            tab == Tab.TREE -> {
                val root = treeRoot()
                val cur = treePath ?: root
                titleView.text = cur.substringAfterLast('/').ifEmpty { "Klasör hiyerarşisi" }
                val prefix = cur.trimEnd('/') + "/"
                val under = all.filter { it.folderPath.startsWith(prefix) || it.folderPath == cur }
                val dirs = under.filter { it.folderPath != cur }.groupBy { it.folderPath.removePrefix(prefix).substringBefore('/') }
                for ((name, list) in dirs.entries.sortedWith(compareBy(NaturalOrder) { it.key })) {
                    rows += Row(name, "${list.size} şarkı", null, icon = R.drawable.ic_folder) {
                        treePath = prefix + name
                        render()
                    }
                }
                songRows(MusicRepository.sort(under.filter { it.folderPath == cur }, settings.musicSort))
            }
            else -> {
                titleView.text = "Müzik"
                val groups = when (tab) {
                    Tab.ALBUMS -> MusicRepository.albums(all)
                    Tab.ARTISTS -> MusicRepository.artists(all)
                    Tab.ALBUM_ARTISTS -> MusicRepository.albumArtists(all)
                    Tab.GENRES -> MusicRepository.genres(all)
                    Tab.YEARS -> MusicRepository.years(all)
                    Tab.COMPOSERS -> MusicRepository.composers(all)
                    else -> MusicRepository.folders(all)
                }
                val icon = when (tab) {
                    Tab.FOLDERS -> R.drawable.ic_folder
                    Tab.GENRES -> R.drawable.ic_music_note
                    Tab.YEARS -> R.drawable.ic_calendar
                    Tab.COMPOSERS -> R.drawable.ic_person
                    else -> 0
                }
                for (g in groups) {
                    rows += Row(g.title, g.subtitle, g.tracks.firstOrNull(), icon = icon) {
                        detail = g
                        render()
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            showEmpty(if (hasPermission()) "Müzik bulunamadı.\nCihazdaki ses dosyaları burada görünür." else "Müzikleri göstermek için izin gerekli.\nDokunarak tekrar dene.")
            return
        }
        emptyView.visibility = View.GONE
        updateSelectionUi()
        val lm = list.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        adapter.rows = rows
        if (keepScroll && pos >= 0) lm.scrollToPosition(pos) else if (!keepScroll) list.scrollToPosition(0)
    }

    /** Klasör ağacının kökü: cihaz depolamasının ortak üst yolu. */
    private fun treeRoot(): String {
        val storage = "/storage/emulated/0"
        return if (all.any { it.folderPath.startsWith(storage) }) storage else "/"
    }

    private fun afterPlay() {
        updateMini()
    }

    // ------------------------------------------------------------ seçim modu

    private fun toggleSelect(t: MusicTrack) {
        selectionOn = true
        if (!selected.add(t.id)) selected.remove(t.id)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selected.clear()
        selectionOn = false
        updateSelectionUi()
    }

    private fun selectedTracks(): List<MusicTrack> = shownTracks.filter { it.id in selected }

    private fun selectAll() {
        shownTracks.forEach { selected.add(it.id) }
        selectionOn = true
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (selectionOn && selected.isEmpty()) selectionOn = false
        topBarView.visibility = if (selectionOn) View.GONE else View.VISIBLE
        selBar.visibility = if (selectionOn) View.VISIBLE else View.GONE
        actionBar.visibility = if (selectionOn) View.VISIBLE else View.GONE
        selTitle.text = "${selected.size} / ${shownTracks.size} Seçildi"
        adapter.refreshSelection()
    }

    private fun playSelection() {
        val ts = selectedTracks()
        if (ts.isEmpty()) return
        exitSelection()
        MusicController.play(this, ts, 0)
        updateMini()
    }

    private fun infoSelection() {
        val ts = selectedTracks()
        if (ts.isEmpty()) return
        if (ts.size == 1) {
            showTrackInfo(ts[0])
            return
        }
        showSummaryInfo("Seçili şarkılar", listOf(
            "Şarkı sayısı" to ts.size.toString(),
            "Toplam boyut" to android.text.format.Formatter.formatFileSize(this, ts.sumOf { it.sizeBytes }),
            "Toplam süre" to formatDuration(ts.sumOf { it.durationMs }),
        ))
    }

    private fun showTrackInfo(t: MusicTrack) {
        showMediaInfo(t.title, t.uri, t.sizeBytes, t.dateAddedSec * 1000L)
    }

    private fun addSelectionToPlaylist() {
        val uris = selectedTracks().map { it.uri.toString() }
        if (uris.isEmpty()) return
        showAddToPlaylist(PlaylistStore.MUSIC, uris) { exitSelection() }
    }

    private fun shareTracks(ts: List<MusicTrack>) {
        if (ts.isEmpty()) return
        val send = if (ts.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, ts[0].uri)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(ts.map { it.uri }))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Paylaş"))
    }

    private fun deleteTracks(ts: List<MusicTrack>) {
        if (ts.isEmpty()) return
        val trash = Build.VERSION.SDK_INT >= 30 && settings.useTrash
        PanelBuilder(this)
            .setTitle("Silinsin mi?")
            .setMessage("${ts.size} şarkı ${if (trash) "çöp kutusuna taşınacak (geri alınabilir)." else "kalıcı olarak silinecek."}")
            .setPositiveButton("Sil") { _, _ ->
                fileOps.delete(ts.map { it.uri }) {
                    exitSelection()
                    load()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun renameTrack(t: MusicTrack) {
        val full = t.path.substringAfterLast('/').ifEmpty { t.title }
        val ext = full.substringAfterLast('.', "")
        showInput("Dosyayı yeniden adlandır", "Yeni ad", full.removeSuffix(if (ext.isEmpty()) "" else ".$ext")) { base ->
            val clean = base.trim()
            if (clean.isEmpty()) return@showInput
            fileOps.rename(listOf(t.uri), listOf(if (ext.isEmpty()) clean else "$clean.$ext")) { load() }
        }
    }

    // ------------------------------------------------------------ etiket düzenleme

    private fun editTags(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        val single = tracks.size == 1
        val t0 = tracks[0]
        fun field(hint: String, initial: String, number: Boolean = false) = EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSingleLine()
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val fTitle = field("Şarkı adı", if (single) t0.title else "")
        val fArtist = field(if (single) "Sanatçı" else "Sanatçı (boş = değiştirme)", if (single) t0.artist else "")
        val fAlbum = field(if (single) "Albüm" else "Albüm (boş = değiştirme)", if (single) t0.album else "")
        val fAlbumArtist = field(if (single) "Albüm sanatçısı" else "Albüm sanatçısı (boş = değiştirme)", if (single) t0.albumArtist else "")
        val fGenre = field(if (single) "Tür" else "Tür (boş = değiştirme)", if (single) t0.genre else "")
        val fYear = field(if (single) "Yıl" else "Yıl (boş = değiştirme)", if (single && t0.year > 0) t0.year.toString() else "", true)
        val fTrack = field("Parça no", if (single && t0.trackNo > 0) t0.trackNo.toString() else "", true)
        val box = vertical {
            addView(label(
                if (single) "Değişiklikler uygulamada saklanır; dosyanın kendisi değişmez."
                else "${tracks.size} şarkı için ortak alanlar. Boş bıraktığın alan değişmez.",
                12f, UiColors.textSecondary
            ))
            if (single) addView(fTitle)
            addView(fArtist)
            addView(fAlbum)
            addView(fAlbumArtist)
            addView(fGenre)
            addView(fYear)
            if (single) addView(fTrack)
            if (single) {
                addView(android.widget.Button(this@MusicActivity).apply {
                    text = "Kapağı değiştir…"
                    setOnClickListener {
                        pendingTrack = t0
                        pickCover.launch(arrayOf("image/*"))
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                addView(android.widget.Button(this@MusicActivity).apply {
                    text = "Söz dosyası ekle (.lrc / .txt)…"
                    setOnClickListener {
                        pendingTrack = t0
                        pickLyrics.launch(arrayOf("*/*"))
                    }
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        PanelBuilder(this)
            .setTitle(if (single) "Etiketleri düzenle" else "${tracks.size} şarkıyı düzenle")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                for (t in tracks) {
                    val cur = MusicTagStore.get(this, t.id)
                    fun pick(v: EditText, old: String?): String? = v.text.toString().trim().ifEmpty { null } ?: old
                    MusicTagStore.save(
                        this, t.id,
                        MusicTagStore.Tags(
                            title = if (single) pick(fTitle, null) else cur?.title,
                            artist = pick(fArtist, cur?.artist),
                            album = pick(fAlbum, cur?.album),
                            albumArtist = pick(fAlbumArtist, cur?.albumArtist),
                            genre = pick(fGenre, cur?.genre),
                            year = fYear.text.toString().trim().toIntOrNull() ?: cur?.year,
                            trackNo = if (single) fTrack.text.toString().trim().toIntOrNull() else cur?.trackNo,
                            cover = cur?.cover ?: false,
                        )
                    )
                }
                exitSelection()
                load()
            }
            .setNeutralButton("Orijinale döndür") { _, _ ->
                tracks.forEach {
                    MusicTagStore.reset(this, it.id)
                    Lyrics.removeUserFile(this, it.id)
                }
                exitSelection()
                load()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ------------------------------------------------------------ satır menüsü

    private fun showTrackMenu(t: MusicTrack) {
        val u = t.uri.toString()
        val fav = PlaylistStore.isFavorite(this, PlaylistStore.MUSIC, u)
        val e = ArrayList<SheetEntry>()
        e += SheetEntry(R.drawable.ic_play, "Çal") {
            val i = shownTracks.indexOfFirst { it.id == t.id }
            if (i >= 0) MusicController.play(this, shownTracks, i) else MusicController.play(this, listOf(t), 0)
            updateMini()
        }
        e += SheetEntry(R.drawable.ic_next, "Sıradakine ekle") {
            MusicController.enqueue(this, t, next = true)
            Toast.makeText(this, "Sıradakine eklendi", Toast.LENGTH_SHORT).show()
            updateMini()
        }
        e += SheetEntry(R.drawable.ic_view_list, "Kuyruğun sonuna ekle") {
            MusicController.enqueue(this, t, next = false)
            Toast.makeText(this, "Kuyruğa eklendi", Toast.LENGTH_SHORT).show()
            updateMini()
        }
        e += SheetEntry(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
            if (fav) "Favorilerden çıkar" else "Favorilere ekle") {
            PlaylistStore.toggleFavorite(this, PlaylistStore.MUSIC, u)
        }
        e += SheetEntry(R.drawable.ic_playlist_add, "Oynatma listesine ekle") {
            showAddToPlaylist(PlaylistStore.MUSIC, listOf(u))
        }
        e += SheetEntry(R.drawable.ic_edit, "Etiketleri düzenle") { editTags(listOf(t)) }
        e += SheetEntry(R.drawable.ic_share, "Paylaş") { shareTracks(listOf(t)) }
        e += SheetEntry(R.drawable.ic_edit, "Dosyayı yeniden adlandır") { renameTrack(t) }
        e += SheetEntry(R.drawable.ic_info, "Özellikler") { showTrackInfo(t) }
        e += SheetEntry(R.drawable.ic_delete, "Sil", danger = true) { deleteTracks(listOf(t)) }
        showSheet(t.title, e)
    }

    private fun updateMini() {
        val p = PlayerHolder.player
        val item = p?.currentMediaItem
        if (p == null || !MusicController.isMusic(item)) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = item?.mediaMetadata?.title ?: ""
        miniSub.text = item?.mediaMetadata?.artist ?: ""
        val track = all.firstOrNull { it.id.toString() == item?.mediaId }
        ArtLoader.load(miniArt, track, dp(96))
        miniPlay.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ------------------------------------------------------------ liste

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        var rows: List<Row> = emptyList()
            @android.annotation.SuppressLint("NotifyDataSetChanged")
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun refreshSelection() = notifyDataSetChanged()

        inner class VH(
            root: View,
            val art: ImageView,
            val title: TextView,
            val sub: TextView,
            val trailing: TextView,
            val more: ImageView,
            val check: ImageView,
            val artBox: View,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val art = ImageView(parent.context)
            ArtLoader.roundCorners(art, 8)
            val check = checkMark(parent.context)
            val artBox = FrameLayout(parent.context).apply {
                addView(art, FrameLayout.LayoutParams(-1, -1))
                addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            val title = text(16f)
            val sub = text(13f, UiColors.textSecondary)
            val trailing = text(12f, UiColors.textSecondary)
            val more = iconView(R.drawable.ic_more_vert, 40, 8).apply { setColorFilter(UiColors.textSecondary) }
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(6), dp(4), dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                addView(artBox, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(sub)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
                addView(trailing)
                addView(more)
            }
            return VH(root, art, title, sub, trailing, more, check, artBox)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val sc = settings.musicScale / 100f
            val tm = Math.pow(sc.toDouble(), 0.4).toFloat().coerceIn(0.8f, 1.5f)
            val box = (dp(52) * sc).toInt().coerceAtLeast(dp(28))
            h.artBox.layoutParams = (h.artBox.layoutParams as LinearLayout.LayoutParams).apply { width = box; height = box }
            h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * tm)
            h.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * tm)
            h.trailing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * tm)
            val sel = r.trackId >= 0 && r.trackId in selected
            h.check.visibility = if (sel) View.VISIBLE else View.GONE
            h.itemView.setBackgroundColor(if (sel) 0x33FFFFFF else 0)
            h.title.text = r.title
            h.title.setTextColor(if (r.highlight) UiColors.accent else Color.WHITE)
            h.sub.text = r.subtitle
            h.trailing.text = r.trailing ?: ""
            val more = r.onMore
            h.more.visibility = if (more != null && !selectionOn) View.VISIBLE else View.GONE
            h.more.setOnClickListener { more?.invoke() }
            h.art.clipToOutline = r.iconBg == 0
            if (r.icon != 0) {
                h.art.tag = null
                h.art.setImageResource(r.icon)
                h.art.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(12)
                h.art.setPadding(pad, pad, pad, pad)
                if (r.iconBg != 0) {
                    h.art.setColorFilter(Color.WHITE)
                    h.art.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(r.iconBg)
                    }
                } else {
                    h.art.setColorFilter(UiColors.accent)
                    h.art.setBackgroundColor(UiColors.surface)
                }
            } else {
                ArtLoader.load(h.art, r.art, (dp(104) * sc).toInt().coerceIn(96, 800))
            }
            h.itemView.setOnClickListener { r.onClick() }
            val long = r.onLong
            if (long != null) h.itemView.setOnLongClickListener { long(); true } else h.itemView.setOnLongClickListener(null)
        }
    }
}
