package org.openplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Playlist(val id: String, var name: String, val kind: String, val items: MutableList<String>)

/** Favoriler ve oynatma listeleri (video ve müzik ayrı). Ayar yedeğiyle birlikte taşınır. */
object PlaylistStore {
    const val VIDEO = "video"
    const val MUSIC = "music"

    private const val FILE = "playlists"
    private const val KEY = "data"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private class Data(val favorites: MutableMap<String, MutableList<String>>, val lists: MutableList<Playlist>)

    private fun load(c: Context): Data {
        val fav = mutableMapOf(VIDEO to mutableListOf<String>(), MUSIC to mutableListOf<String>())
        val lists = mutableListOf<Playlist>()
        runCatching {
            val o = JSONObject(prefs(c).getString(KEY, "{}") ?: "{}")
            val f = o.optJSONObject("fav")
            for (k in listOf(VIDEO, MUSIC)) {
                val arr = f?.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) fav[k]!!.add(arr.getString(i))
            }
            val ls = o.optJSONArray("lists")
            if (ls != null) for (i in 0 until ls.length()) {
                val p = ls.getJSONObject(i)
                val items = mutableListOf<String>()
                val arr = p.optJSONArray("items")
                if (arr != null) for (j in 0 until arr.length()) items.add(arr.getString(j))
                lists += Playlist(p.getString("id"), p.optString("name", Tx.s(R.string.listpanel_012)), p.optString("kind", VIDEO), items)
            }
        }
        return Data(fav, lists)
    }

    private fun save(c: Context, d: Data) {
        val o = JSONObject()
        val f = JSONObject()
        for ((k, v) in d.favorites) f.put(k, JSONArray(v))
        o.put("fav", f)
        val ls = JSONArray()
        for (p in d.lists) {
            ls.put(JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("items", JSONArray(p.items)))
        }
        o.put("lists", ls)
        prefs(c).edit().putString(KEY, o.toString()).apply()
    }

    fun favorites(c: Context, kind: String): List<String> = load(c).favorites[kind] ?: emptyList()

    fun isFavorite(c: Context, kind: String, uri: String) = uri in favorites(c, kind)

    fun toggleFavorite(c: Context, kind: String, uri: String): Boolean {
        val d = load(c)
        val l = d.favorites.getOrPut(kind) { mutableListOf() }
        val now = if (uri in l) { l.remove(uri); false } else { l.add(uri); true }
        save(c, d)
        return now
    }

    fun setFavorites(c: Context, kind: String, uris: List<String>, on: Boolean) {
        val d = load(c)
        val l = d.favorites.getOrPut(kind) { mutableListOf() }
        for (u in uris) if (on) { if (u !in l) l.add(u) } else l.remove(u)
        save(c, d)
    }

    fun lists(c: Context, kind: String): List<Playlist> = load(c).lists.filter { it.kind == kind }

    fun get(c: Context, id: String): Playlist? = load(c).lists.firstOrNull { it.id == id }

    fun create(c: Context, kind: String, name: String): Playlist {
        val d = load(c)
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { Tx.s(R.string.playlistac_033) }.take(80), kind, mutableListOf())
        d.lists += p
        save(c, d)
        return p
    }

    fun rename(c: Context, id: String, name: String) {
        val d = load(c)
        d.lists.firstOrNull { it.id == id }?.name = name.trim().ifEmpty { Tx.s(R.string.listpanel_012) }.take(80)
        save(c, d)
    }

    fun delete(c: Context, id: String) {
        val d = load(c)
        d.lists.removeAll { it.id == id }
        save(c, d)
    }

    fun add(c: Context, id: String, uris: List<String>): Int {
        val d = load(c)
        val p = d.lists.firstOrNull { it.id == id } ?: return 0
        var n = 0
        for (u in uris) if (u !in p.items) { p.items.add(u); n++ }
        save(c, d)
        return n
    }

    fun remove(c: Context, id: String, uri: String) {
        val d = load(c)
        d.lists.firstOrNull { it.id == id }?.items?.remove(uri)
        save(c, d)
    }

    fun move(c: Context, id: String, from: Int, to: Int) {
        val d = load(c)
        val p = d.lists.firstOrNull { it.id == id } ?: return
        if (from !in p.items.indices || to !in p.items.indices) return
        p.items.add(to, p.items.removeAt(from))
        save(c, d)
    }
}

/** "Listeye ekle" penceresi: mevcut listelerden birini seçtirir veya yenisini oluşturur. */
fun android.app.Activity.showAddToPlaylist(kind: String, uris: List<String>, onDone: () -> Unit = {}) {
    val lists = PlaylistStore.lists(this, kind)
    val labels = ArrayList<String>()
    labels += "＋  Yeni liste…"
    lists.forEach { labels += "${it.name}  (${it.items.size})" }
    PanelBuilder(this)
        .setTitle(Tx.s(R.string.mainactivi_067))
        .setItems(labels.toTypedArray()) { _, which ->
            if (which == 0) {
                showInput(Tx.s(R.string.playlistac_033), Tx.s(R.string.playlistac_020)) { name ->
                    val p = PlaylistStore.create(this, kind, name)
                    val n = PlaylistStore.add(this, p.id, uris)
                    android.widget.Toast.makeText(this, Tx.s(R.string.playlistst_001, p.name, n), android.widget.Toast.LENGTH_SHORT).show()
                    onDone()
                }
            } else {
                val p = lists[which - 1]
                val n = PlaylistStore.add(this, p.id, uris)
                android.widget.Toast.makeText(
                    this, if (n == 0) Tx.s(R.string.playlistst_002) else Tx.s(R.string.playlistst_001, p.name, n),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                onDone()
            }
        }
        .setNegativeButton(Tx.s(R.string.fileops_004), null)
        .show()
}
