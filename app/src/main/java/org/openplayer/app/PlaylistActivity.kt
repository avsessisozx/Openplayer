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
    }

    private class Entry(
        val uri: String,
        val title: String,
        val subtitle: String,
        val video: VideoItem?,
        val track: MusicTrack?,
    )

    private lateinit var kind: String
    private lateinit var titleView: TextView
    private lateinit var body: LinearLayout
    private lateinit var moreButton: ImageView
    private var openId: String? = null
    private var videos: Map<String, VideoItem> = emptyMap()
    private var tracks: Map<String, MusicTrack> = emptyMap()
    private var loaded = false

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (openId != null && intent.getStringExtra(EXTRA_OPEN) == null) {
                openId = null
                render()
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
            addView(ScrollView(this@PlaylistActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
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

    private fun smartIds() = if (kind == PlaylistStore.MUSIC) listOf(SMART_ADDED, SMART_PLAYED, SMART_MOST) else emptyList()

    private fun smartTitle(id: String) = when (id) {
        SMART_ADDED -> "Son eklenenler"
        SMART_PLAYED -> "Son çalınanlar"
        else -> "En çok çalınanlar"
    }

    private fun smartUris(id: String): List<String> = when (id) {
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
        body.removeAllViews()
        val id = openId
        if (id == null) renderRoot() else renderDetail(id)
    }

    private fun renderRoot() {
        titleView.text = if (kind == PlaylistStore.MUSIC) "Müzik listeleri" else "Oynatma listeleri"
        moreButton.visibility = View.GONE
        body.addView(row(R.drawable.ic_favorite, "Favoriler", "${PlaylistStore.favorites(this, kind).size} öğe", { openId = FAV; render() }))
        for (s in smartIds()) body.addView(row(R.drawable.ic_timer, smartTitle(s), "Otomatik liste", { openId = s; render() }))
        body.addView(row(R.drawable.ic_add, "Yeni liste oluştur", "", {
            showInput("Yeni liste", "Liste adı") { n ->
                val p = PlaylistStore.create(this, kind, n)
                openId = p.id
                render()
            }
        }))
        for (p in PlaylistStore.lists(this, kind)) {
            body.addView(row(R.drawable.ic_playlist_add, p.name, "${p.items.size} öğe", { openId = p.id; render() }))
        }
    }

    private fun entriesOf(id: String): List<Entry> = urisOf(id).mapNotNull { u ->
        if (kind == PlaylistStore.VIDEO) videos[u]?.let { Entry(u, it.title, it.bucketName + " • " + formatDuration(it.durationMs), it, null) }
        else tracks[u]?.let { Entry(u, it.title, it.artist + " • " + it.album, null, it) }
    }

    private fun renderDetail(id: String) {
        val user = !(id == FAV || id.startsWith("smart:"))
        titleView.text = when {
            id == FAV -> "Favoriler"
            id.startsWith("smart:") -> smartTitle(id)
            else -> PlaylistStore.get(this, id)?.name ?: "Liste"
        }
        moreButton.visibility = if (user) View.VISIBLE else View.GONE
        val entries = entriesOf(id)
        if (entries.isEmpty()) {
            body.addView(label(
                if (loaded) "Bu listede henüz bir şey yok.\nBir öğenin ⋮ menüsünden ekleyebilirsin." else "Yükleniyor…",
                15f, UiColors.textSecondary
            ).apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(dp(24), dp(48), dp(24), 0) })
            return
        }
        body.addView(row(R.drawable.ic_play, "Hepsini çal", "${entries.size} öğe", { playAll(entries, 0, false) }))
        body.addView(row(R.drawable.ic_shuffle, "Karıştır", "Rastgele sırayla", { playAll(entries, entries.indices.random(), true) }))
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
            Toast.makeText(this, "Çalıyor", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
    }

    private fun entryMenu(listId: String, entries: List<Entry>, i: Int, user: Boolean) {
        val e = entries[i]
        val items = ArrayList<SheetEntry>()
        items += SheetEntry(R.drawable.ic_play, "Buradan çal") { playAll(entries, i, false) }
        if (listId == FAV) {
            items += SheetEntry(R.drawable.ic_favorite_border, "Favorilerden çıkar") {
                PlaylistStore.toggleFavorite(this, kind, e.uri); render()
            }
        }
        if (user) {
            if (i > 0) items += SheetEntry(R.drawable.ic_back, "Yukarı taşı") {
                PlaylistStore.move(this, listId, i, i - 1); render()
            }
            if (i < entries.size - 1) items += SheetEntry(R.drawable.ic_chevron, "Aşağı taşı") {
                PlaylistStore.move(this, listId, i, i + 1); render()
            }
            items += SheetEntry(R.drawable.ic_delete, "Listeden çıkar", danger = true) {
                PlaylistStore.remove(this, listId, e.uri); render()
            }
        }
        showSheet(e.title, items)
    }

    private fun showListMenu() {
        val id = openId ?: return
        showSheet("Liste", listOf(
            SheetEntry(R.drawable.ic_edit, "Yeniden adlandır") {
                showInput("Listeyi yeniden adlandır", "Liste adı", PlaylistStore.get(this, id)?.name ?: "") { n ->
                    PlaylistStore.rename(this, id, n); render()
                }
            },
            SheetEntry(R.drawable.ic_delete, "Listeyi sil", danger = true) {
                PanelBuilder(this).setTitle("Liste silinsin mi?")
                    .setMessage("Yalnızca liste silinir, dosyalar silinmez.")
                    .setPositiveButton("Sil") { _, _ ->
                        PlaylistStore.delete(this, id)
                        openId = null
                        if (intent.getStringExtra(EXTRA_OPEN) != null) finish() else render()
                    }
                    .setNegativeButton("İptal", null).show()
            },
        ))
    }
}
