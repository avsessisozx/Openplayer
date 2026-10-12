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

    private enum class Tab(val title: String) { SONGS("Şarkılar"), ALBUMS("Albümler"), ARTISTS("Sanatçılar"), FOLDERS("Klasörler") }

    private class Row(
        val title: String,
        val subtitle: String,
        val art: MusicTrack?,
        val icon: Int = 0,
        val trailing: String? = null,
        val highlight: Boolean = false,
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

    private var all: List<MusicTrack> = emptyList()
    private var tab = Tab.SONGS
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
                searching -> closeSearch()
                detail != null -> {
                    detail = null
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
        ThemeManager.applyWindow(this)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.get(this).addListener(playerListener)
        updateMini()
        if (hasPermission()) load() else requestPermission.launch(permission)
    }

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
        }

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buildTabs()

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MusicActivity)
            adapter = this@MusicActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
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
            addView(tabRow, LinearLayout.LayoutParams(-1, dp(44)))
            addView(FrameLayout(this@MusicActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(mini, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        // "Videolar" sekmesi ilk sırada: müzik modundan videoya dönüş.
        tabRow.addView(tabView("Videolar", false) { finish() }, LinearLayout.LayoutParams(0, -1, 1f))
        for (t in Tab.entries) {
            tabRow.addView(tabView(t.title, t == tab && detail == null) {
                tab = t
                detail = null
                closeSearch()
                render()
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun tabView(title: String, selected: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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
            tracks.forEachIndexed { i, t ->
                rows += Row(
                    t.title, "${t.artist} • ${t.album}", t, trailing = formatDuration(t.durationMs),
                    highlight = t.id.toString() == playingId,
                    onLong = { showTrackMenu(t) },
                ) { MusicController.play(this, tracks, i); afterPlay() }
            }
        }

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
            else -> {
                titleView.text = "Müzik"
                val groups = when (tab) {
                    Tab.ALBUMS -> MusicRepository.albums(all)
                    Tab.ARTISTS -> MusicRepository.artists(all)
                    else -> MusicRepository.folders(all)
                }
                for (g in groups) {
                    rows += Row(
                        g.title, g.subtitle, g.tracks.firstOrNull(),
                        icon = if (tab == Tab.FOLDERS) R.drawable.ic_folder else 0,
                    ) {
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
        val lm = list.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        adapter.rows = rows
        if (keepScroll && pos >= 0) lm.scrollToPosition(pos) else if (!keepScroll) list.scrollToPosition(0)
    }

    private fun afterPlay() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        updateMini()
    }

    private fun showTrackMenu(t: MusicTrack) {
        val items = arrayOf("Sıradakine ekle", "Kuyruğun sonuna ekle", "Bilgi")
        PanelBuilder(this)
            .setTitle(t.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        MusicController.enqueue(this, t, next = true)
                        android.widget.Toast.makeText(this, "Sıradakine eklendi", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        MusicController.enqueue(this, t, next = false)
                        android.widget.Toast.makeText(this, "Kuyruğa eklendi", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> showMessage(
                        t.title,
                        "Sanatçı: ${t.artist}\nAlbüm: ${t.album}\nSüre: ${formatDuration(t.durationMs)}\nKlasör: ${t.folderPath}"
                    )
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
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

        inner class VH(
            root: View,
            val art: ImageView,
            val title: TextView,
            val sub: TextView,
            val trailing: TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val art = ImageView(parent.context)
            ArtLoader.roundCorners(art, 8)
            val title = text(16f)
            val sub = text(13f, UiColors.textSecondary)
            val trailing = text(12f, UiColors.textSecondary)
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(sub)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
                addView(trailing)
            }
            return VH(root, art, title, sub, trailing)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.title.text = r.title
            h.title.setTextColor(if (r.highlight) UiColors.accent else Color.WHITE)
            h.sub.text = r.subtitle
            h.trailing.text = r.trailing ?: ""
            if (r.icon != 0) {
                h.art.tag = null
                h.art.setImageResource(r.icon)
                h.art.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(12)
                h.art.setPadding(pad, pad, pad, pad)
                h.art.setColorFilter(UiColors.accent)
                h.art.setBackgroundColor(UiColors.surface)
            } else {
                ArtLoader.load(h.art, r.art, dp(104))
            }
            h.itemView.setOnClickListener { r.onClick() }
            val long = r.onLong
            if (long != null) h.itemView.setOnLongClickListener { long(); true } else h.itemView.setOnLongClickListener(null)
        }
    }
}
