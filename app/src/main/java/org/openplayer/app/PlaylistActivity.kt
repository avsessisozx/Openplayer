package org.openplayer.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Oynatma listeleri ve favoriler (video ve müzik için ortak ekran). */
class PlaylistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "org.openplayer.app.PL_KIND"
        const val EXTRA_OPEN = "org.openplayer.app.PL_OPEN"
        const val FAV = "fav"
        const val SMART_ADDED = "smart:added"
        const val SMART_PLAYED = "smart:played"
        const val SMART_MOST = "smart:most"
        const val SMART_VPLAYED = "smart:vplayed"
        const val SMART_VADDED = "smart:vadded"
        const val SMART_UNPLAYED = "smart:unplayed"
        const val SMART_LARGE = "smart:large"

        /** true ise video için "Medya Yöneticisi" görünümü açılır (depolama özeti + otomatik listeler). */
        const val EXTRA_MANAGER = "org.openplayer.app.PL_MANAGER"
    }

    private class Entry(
        val uri: String,
        val title: String,
        val subtitle: String,
        val video: VideoItem?,
        val track: MusicTrack?,
    )

    private lateinit var kind: String
    private var manager = false
    private lateinit var titleView: TextView
    private lateinit var body: LinearLayout
    private var scroller: ScrollView? = null
    private var rootScrollY = 0
    private var showingRoot = false
    private lateinit var moreButton: ImageView
    private var openId: String? = null
    private var videos: Map<String, VideoItem> = emptyMap()
    private var tracks: Map<String, MusicTrack> = emptyMap()
    private var loaded = false

    private var exportId: String? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val id = exportId
        exportId = null
        if (uri == null || id == null) return@registerForActivityResult
        val entries = entriesOf(id)
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                w.write("#EXTM3U\n")
                for (e in entries) {
                    val dur = (e.track?.durationMs ?: e.video?.durationMs ?: 0L) / 1000
                    w.write("#EXTINF:$dur,${e.title}\n")
                    val path = e.track?.path?.takeIf { it.isNotEmpty() } ?: e.video?.path?.takeIf { it.isNotEmpty() } ?: e.uri
                    w.write(path + "\n")
                }
            }
            Toast.makeText(this, Tx.s(R.string.playlistac_001, entries.size), Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, Tx.s(R.string.playlistac_002), Toast.LENGTH_LONG).show() }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        importList(uri)
    }

    /** .m3u/.m3u8/.pls dosyasını okur; dosya yollarını kütüphanedeki öğelerle eşleştirip yeni liste yapar. */
    private fun importList(uri: android.net.Uri) {
        val lines = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
        }.getOrNull()
        if (lines == null) {
            Toast.makeText(this, Tx.s(R.string.musicactiv_055), Toast.LENGTH_LONG).show()
            return
        }
        val paths = ArrayList<String>()
        for (raw in lines) {
            val l = raw.trim().removePrefix("\uFEFF")
            when {
                l.isEmpty() || l.startsWith("#") || l.startsWith("[") -> Unit
                l.startsWith("File", ignoreCase = true) && l.contains('=') -> paths += l.substringAfter('=').trim()
                l.contains('=') && !l.contains('/') -> Unit
                else -> paths += l
            }
        }
        val pool: List<Pair<String, String>> = if (kind == PlaylistStore.MUSIC) tracks.values.map { it.uri.toString() to it.path }
        else videos.values.map { it.uri.toString() to it.path }
        val matched = ArrayList<String>()
        for (p in paths) {
            val name = p.replace('\\', '/').substringAfterLast('/')
            val hit = pool.firstOrNull { it.second == p }
                ?: pool.firstOrNull { it.second.endsWith("/" + p.replace('\\', '/').removePrefix("./")) }
                ?: pool.firstOrNull { it.second.substringAfterLast('/').equals(name, ignoreCase = true) }
            if (hit != null && hit.first !in matched) matched += hit.first
        }
        val displayName = (uri.lastPathSegment ?: Tx.s(R.string.listpanel_012)).substringAfterLast('/').substringBeforeLast('.').ifBlank { Tx.s(R.string.playlistac_003) }
        val pl = PlaylistStore.create(this, kind, displayName)
        PlaylistStore.add(this, pl.id, matched)
        Toast.makeText(this, Tx.s(R.string.playlistac_004, matched.size, paths.size), Toast.LENGTH_LONG).show()
        openId = pl.id
        render()
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (openId != null && intent.getStringExtra(EXTRA_OPEN) == null) {
                openId = null
                render()
                // Ana listeye dönünce kaldığı konuma getir.
                scroller?.post { scroller?.scrollTo(0, rootScrollY) }
            } else finish()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
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
        kind = intent.getStringExtra(EXTRA_KIND) ?: PlaylistStore.VIDEO
        manager = kind == PlaylistStore.VIDEO && intent.getBooleanExtra(EXTRA_MANAGER, false)
        openId = intent.getStringExtra(EXTRA_OPEN)
        ThemeManager.applyWindow(this)
        onBackPressedDispatcher.addCallback(this, back)
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        moreButton = iconView(R.drawable.ic_more_vert).apply {
            visibility = View.GONE
            setOnClickListener { showListMenu() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            addView(iconView(R.drawable.ic_back).apply { setOnClickListener { back.handleOnBackPressed() } })
            addView(titleView, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
            addView(moreButton)
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(UiColors.background)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@PlaylistActivity).also { scroller = it }.apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        val app = applicationContext
        Thread {
            val v = if (kind == PlaylistStore.VIDEO) VideoRepository.loadAll(app).associateBy { it.uri.toString() } else emptyMap()
            val t = if (kind == PlaylistStore.MUSIC) MusicRepository.loadAll(app).associateBy { it.uri.toString() } else emptyMap()
            runOnUiThread {
                videos = v
                tracks = t
                loaded = true
                render()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (loaded) render()
    }

    private fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun row(icon: Int, title: String, sub: String, onClick: () -> Unit, onMore: (() -> Unit)? = null,
                    art: ((ImageView) -> Unit)? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val iv = ImageView(context)
            AppArt.round(iv, 8)
            if (art != null) art(iv) else {
                iv.setImageResource(icon)
                iv.setColorFilter(UiColors.accent)
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                iv.setPadding(dp(12), dp(12), dp(12), dp(12))
                iv.setBackgroundColor(UiColors.surface)
            }
            addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f))
                addView(label(sub, 13f, UiColors.textSecondary))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            if (onMore != null) addView(iconView(R.drawable.ic_more_vert, 40, 8).apply {
                setColorFilter(UiColors.textSecondary)
                setOnClickListener { onMore() }
            })
        }

    private fun smartIds() = when {
        kind == PlaylistStore.MUSIC -> listOf(SMART_ADDED, SMART_PLAYED, SMART_MOST)
        manager -> listOf(SMART_VPLAYED, SMART_VADDED, SMART_UNPLAYED, SMART_LARGE)
        else -> emptyList()
    }

    private fun smartTitle(id: String) = when (id) {
        SMART_ADDED, SMART_VADDED -> Tx.s(R.string.playlistac_032)
        SMART_PLAYED -> Tx.s(R.string.playlistac_005)
        SMART_VPLAYED -> Tx.s(R.string.playlistac_006)
        SMART_UNPLAYED -> Tx.s(R.string.playlistac_007)
        SMART_LARGE -> Tx.s(R.string.playlistac_008)
        else -> Tx.s(R.string.playlistac_009)
    }

    private fun smartSub(id: String) = when (id) {
        SMART_VPLAYED -> Tx.s(R.string.playlistac_010)
        SMART_VADDED -> "En yeni 100 video"
        SMART_UNPLAYED -> Tx.s(R.string.playlistac_011)
        SMART_LARGE -> Tx.s(R.string.playlistac_012)
        else -> Tx.s(R.string.playlistac_013)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }

    private fun smartUris(id: String): List<String> = when (id) {
        SMART_VPLAYED -> videos.values.filter { ResumeStore.lastPlayed(this, it.uri) > 0 }
            .sortedByDescending { ResumeStore.lastPlayed(this, it.uri) }.take(100).map { it.uri.toString() }
        SMART_VADDED -> videos.values.sortedByDescending { it.dateAddedSec }.take(100).map { it.uri.toString() }
        SMART_UNPLAYED -> videos.values.filter {
            !ResumeStore.isPlayed(this, it.uri.toString()) && ResumeStore.lastPlayed(this, it.uri) == 0L && ResumeStore.position(this, it.uri) == 0L
        }.sortedByDescending { it.dateAddedSec }.take(200).map { it.uri.toString() }
        SMART_LARGE -> videos.values.sortedByDescending { it.sizeBytes }.take(50).map { it.uri.toString() }
        SMART_ADDED -> tracks.values.sortedByDescending { it.dateAddedSec }.take(100).map { it.uri.toString() }
        SMART_PLAYED -> tracks.values.filter { MusicStats.last(this, it.id.toString()) > 0 }
            .sortedByDescending { MusicStats.last(this, it.id.toString()) }.take(100).map { it.uri.toString() }
        else -> tracks.values.filter { MusicStats.count(this, it.id.toString()) > 0 }
            .sortedByDescending { MusicStats.count(this, it.id.toString()) }.take(100).map { it.uri.toString() }
    }

    private fun urisOf(id: String): List<String> = when {
        id == FAV -> PlaylistStore.favorites(this, kind)
        id.startsWith("smart:") -> smartUris(id)
        else -> PlaylistStore.get(this, id)?.items ?: emptyList()
    }

    private fun render() {
        if (showingRoot) rootScrollY = scroller?.scrollY ?: 0
        showingRoot = openId == null
        body.removeAllViews()
        val id = openId
        if (id == null) renderRoot() else renderDetail(id)
    }

    private fun renderRoot() {
        titleView.text = when {
            kind == PlaylistStore.MUSIC -> Tx.s(R.string.playlistac_014)
            manager -> Tx.s(R.string.playlistac_015)
            else -> Tx.s(R.string.mainactivi_074)
        }
        moreButton.visibility = View.GONE
        if (manager) {
            renderManagerRoot()
            return
        }
        body.addView(row(R.drawable.ic_favorite, Tx.s(R.string.mainactivi_025), Tx.s(R.string.playlistac_016, PlaylistStore.favorites(this, kind).size), { openId = FAV; render() }))
        for (s in smartIds()) body.addView(row(R.drawable.ic_timer, smartTitle(s), Tx.s(R.string.playlistac_013), { openId = s; render() }))
        body.addView(row(R.drawable.ic_cloud_download, Tx.s(R.string.playlistac_018), Tx.s(R.string.playlistac_017), {
            importLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "application/vnd.apple.mpegurl", "text/plain", "*/*"))
        }))
        body.addView(row(R.drawable.ic_add, Tx.s(R.string.playlistac_019), "", {
            showInput(Tx.s(R.string.playlistac_033), Tx.s(R.string.playlistac_020)) { n ->
                val p = PlaylistStore.create(this, kind, n)
                openId = p.id
                render()
            }
        }))
        for (p in PlaylistStore.lists(this, kind)) {
            body.addView(row(R.drawable.ic_playlist_add, p.name, Tx.s(R.string.playlistac_016, p.items.size), { openId = p.id; render() }))
        }
    }

    /** Depolama özeti ve otomatik video listeleri (MX Player'daki Medya Yöneticisi gibi). */
    private fun renderManagerRoot() {
        val fs = runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path) }.getOrNull()
        if (fs != null) {
            val total = fs.totalBytes
            val used = total - fs.availableBytes
            body.addView(row(R.drawable.ic_info, Tx.s(R.string.playlistac_022), Tx.s(R.string.playlistac_021, humanSize(used), humanSize(total)), {}))
        }
        if (loaded) {
            val size = videos.values.sumOf { it.sizeBytes }
            body.addView(row(R.drawable.ic_view_list, Tx.s(R.string.mainactivi_009), "${videos.size} video • ${humanSize(size)}", {}))
        } else {
            body.addView(row(R.drawable.ic_view_list, Tx.s(R.string.mainactivi_009), Tx.s(R.string.networkact_003), {}))
        }
        for (s in smartIds()) body.addView(row(R.drawable.ic_timer, smartTitle(s), smartSub(s), { openId = s; render() }))
    }

    private fun entriesOf(id: String): List<Entry> = urisOf(id).mapNotNull { u ->
        if (kind == PlaylistStore.VIDEO) videos[u]?.let {
            val size = if (id == SMART_LARGE) " • " + humanSize(it.sizeBytes) else ""
            Entry(u, it.title, it.bucketName + " • " + formatDuration(it.durationMs) + size, it, null)
        }
        else tracks[u]?.let { Entry(u, it.title, it.artist + " • " + it.album, null, it) }
    }

    private fun renderDetail(id: String) {
        val user = !(id == FAV || id.startsWith("smart:"))
        titleView.text = when {
            id == FAV -> Tx.s(R.string.mainactivi_025)
            id.startsWith("smart:") -> smartTitle(id)
            else -> PlaylistStore.get(this, id)?.name ?: Tx.s(R.string.listpanel_012)
        }
        moreButton.visibility = View.VISIBLE
        val entries = entriesOf(id)
        if (entries.isEmpty()) {
            body.addView(label(
                if (loaded) Tx.s(R.string.playlistac_023) else Tx.s(R.string.networkact_003),
                15f, UiColors.textSecondary
            ).apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(dp(24), dp(48), dp(24), 0) })
            return
        }
        body.addView(row(R.drawable.ic_play, Tx.s(R.string.musicactiv_018), Tx.s(R.string.playlistac_016, entries.size), { playAll(entries, 0, false) }))
        body.addView(row(R.drawable.ic_shuffle, Tx.s(R.string.musicactiv_020), Tx.s(R.string.playlistac_024), { playAll(entries, entries.indices.random(), true) }))
        entries.forEachIndexed { i, e ->
            body.addView(row(0, e.title, e.subtitle, { playAll(entries, i, false) }, { entryMenu(id, entries, i, user) }) { iv ->
                if (e.video != null) ThumbLoader.load(this, e.video, iv) else ArtLoader.load(iv, e.track, 112)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
            })
        }
    }

    private fun playAll(entries: List<Entry>, index: Int, shuffle: Boolean) {
        if (kind == PlaylistStore.VIDEO) {
            val vids = entries.mapNotNull { it.video }
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(vids.map { it.uri }))
                    .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(vids.map { it.title }))
                    .putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, vids.size - 1))
            )
        } else {
            val ts = entries.mapNotNull { it.track }
            MusicController.play(this, ts, index.coerceIn(0, ts.size - 1), shuffle = shuffle)
            Toast.makeText(this, Tx.s(R.string.nowplaying_001), Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
    }

    private fun entryMenu(listId: String, entries: List<Entry>, i: Int, user: Boolean) {
        val e = entries[i]
        val items = ArrayList<SheetEntry>()
        items += SheetEntry(R.drawable.ic_play, Tx.s(R.string.playlistac_025)) { playAll(entries, i, false) }
        if (listId == FAV) {
            items += SheetEntry(R.drawable.ic_favorite_border, Tx.s(R.string.mainactivi_042)) {
                PlaylistStore.toggleFavorite(this, kind, e.uri); render()
            }
        }
        if (user) {
            if (i > 0) items += SheetEntry(R.drawable.ic_back, Tx.s(R.string.playlistac_026)) {
                PlaylistStore.move(this, listId, i, i - 1); render()
            }
            if (i < entries.size - 1) items += SheetEntry(R.drawable.ic_chevron, Tx.s(R.string.playlistac_027)) {
                PlaylistStore.move(this, listId, i, i + 1); render()
            }
            items += SheetEntry(R.drawable.ic_delete, Tx.s(R.string.playlistac_028), danger = true) {
                PlaylistStore.remove(this, listId, e.uri); render()
            }
        }
        showSheet(e.title, items)
    }

    private fun showListMenu() {
        val id = openId ?: return
        val user = !(id == FAV || id.startsWith("smart:"))
        val items = ArrayList<SheetEntry>()
        items += SheetEntry(R.drawable.ic_share, Tx.s(R.string.playlistac_029)) {
            exportId = id
            val name = when {
                id == FAV -> Tx.s(R.string.mainactivi_025)
                id.startsWith("smart:") -> smartTitle(id)
                else -> PlaylistStore.get(this, id)?.name ?: Tx.s(R.string.listpanel_012)
            }
            exportLauncher.launch("$name.m3u8")
        }
        if (user) {
            items += SheetEntry(R.drawable.ic_edit, Tx.s(R.string.mainactivi_006)) {
                showInput(Tx.s(R.string.playlistac_030), Tx.s(R.string.playlistac_020), PlaylistStore.get(this, id)?.name ?: "") { n ->
                    PlaylistStore.rename(this, id, n); render()
                }
            }
            items += SheetEntry(R.drawable.ic_delete, Tx.s(R.string.playlistac_034), danger = true) {
                PanelBuilder(this).setTitle(Tx.s(R.string.playlistac_035))
                    .setMessage(Tx.s(R.string.playlistac_031))
                    .setPositiveButton(Tx.s(R.string.mainactivi_007)) { _, _ ->
                        PlaylistStore.delete(this, id)
                        openId = null
                        if (intent.getStringExtra(EXTRA_OPEN) != null) finish() else render()
                    }
                    .setNegativeButton(Tx.s(R.string.fileops_004), null).show()
            }
        }
        showSheet(Tx.s(R.string.listpanel_012), items)
    }
}
