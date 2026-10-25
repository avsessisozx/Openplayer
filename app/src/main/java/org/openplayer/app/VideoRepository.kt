@file:Suppress("DEPRECATION")

package org.openplayer.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val path: String = "",
)

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val videoCount: Int,
    val newCount: Int,
    val hidden: Boolean,
    /** Ağaç görünümünde bu klasörün tam yolu (boşsa MediaStore klasörü). */
    val path: String = "",
    /** Alt klasörleri olan üst klasör: açılınca alt klasörleri listelenir; seçim işlemleri uygulanmaz. */
    val group: Boolean = false,
)

object VideoRepository {

    /** Cihazdaki tüm videoları MediaStore'dan okur. İzin yoksa boş liste döner. */
    fun loadAll(context: Context): List<VideoItem> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA,
        )
        val result = ArrayList<VideoItem>()
        try {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                @Suppress("DEPRECATION")
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(nameCol) ?: Tx.s(R.string.mediainfo_013),
                        durationMs = c.getLong(durCol),
                        bucketId = c.getLong(bucketIdCol),
                        bucketName = c.getString(bucketNameCol) ?: Tx.s(R.string.videorepos_001),
                        sizeBytes = c.getLong(sizeCol),
                        dateAddedSec = c.getLong(dateCol),
                        widthPx = c.getInt(wCol),
                        heightPx = c.getInt(hCol),
                        path = c.getString(dataCol) ?: "",
                    )
                }
            }
        } catch (_: SecurityException) {
            // İzin verilmemiş
        }
        return result
    }

    /** Yeni eklenmiş ve henüz izlenmemiş mi? */
    fun isNew(context: Context, video: VideoItem, days: Int): Boolean {
        val limit = System.currentTimeMillis() / 1000 - days * 86_400L
        return video.dateAddedSec > limit && !ResumeStore.isPlayed(context, video.uri.toString()) &&
                ResumeStore.position(context, video.uri) == 0L
    }

    fun folders(context: Context, videos: List<VideoItem>, settings: AppSettings): List<FolderItem> {
        val hidden = settings.hiddenFolders
        class Entry(val folder: FolderItem, val newest: Long, val size: Long)
        val entries = videos.groupBy { it.bucketId }
            .map { (id, items) ->
                Entry(
                    FolderItem(
                        bucketId = id,
                        name = items.first().bucketName,
                        videoCount = items.size,
                        newCount = items.count { isNew(context, it, settings.newVideoDays) },
                        hidden = id in hidden,
                    ),
                    items.maxOf { it.dateAddedSec },
                    items.sumOf { it.sizeBytes },
                )
            }
            .filter { settings.showHiddenFolders || !it.folder.hidden }
        val byName = Comparator<Entry> { a, b -> a.folder.name.lowercase().compareTo(b.folder.name.lowercase()) }
        val order = settings.folderOrder
        val base: Comparator<Entry> = when (order.substringBeforeLast('_')) {
            "new" -> compareBy<Entry> { it.newest }
            "count" -> compareBy<Entry> { it.folder.videoCount }
            "size" -> compareBy<Entry> { it.size }
            else -> byName
        }
        val dirCmp = if (order.endsWith("_desc")) base.reversed() else base
        return entries.sortedWith(dirCmp.then(byName)).map { it.folder }
    }

    /**
     * Ağaç görünümü (MX Player gibi): [dir] null ise en üst klasörler; değilse o klasörün alt klasörleri.
     * Alt klasörü olmayan klasör normal klasördür (bucketId gerçek); alt klasörü olanlar [FolderItem.group] olur.
     * Üst klasörün kendi videoları varsa listenin başına, klasörün kendi adıyla bir öğe eklenir.
     */
    fun treeFolders(context: Context, videos: List<VideoItem>, settings: AppSettings, dir: String?): List<FolderItem> {
        val hidden = settings.hiddenFolders
        val vis = if (settings.showHiddenFolders) videos else videos.filter { it.bucketId !in hidden }
        fun dirOf(v: VideoItem) = v.path.substringBeforeLast('/', "")
        // Depolama kökü: /storage/emulated/0 veya /storage/<kimlik>; yolu bilinmeyen videolar düz klasör olarak kalır.
        fun rootOf(d: String): String {
            val parts = d.split('/')
            return when {
                d.startsWith("/storage/emulated/") && parts.size >= 4 -> parts.take(4).joinToString("/")
                d.startsWith("/storage/") && parts.size >= 3 -> parts.take(3).joinToString("/")
                else -> ""
            }
        }
        class Node(val key: String, val name: String, val items: MutableList<VideoItem> = ArrayList(), var group: Boolean = false)
        val nodes = LinkedHashMap<String, Node>()
        val result = ArrayList<FolderItem>()
        val entries = ArrayList<Triple<FolderItem, Long, Long>>() // klasör, en yeni, toplam boyut
        fun addNode(n: Node, folderPath: String) {
            val first = n.items.first()
            val bucket = if (n.group) folderPath.hashCode().toLong() or (1L shl 40) else first.bucketId
            val item = FolderItem(
                bucketId = bucket,
                name = n.name,
                videoCount = n.items.size,
                newCount = n.items.count { isNew(context, it, settings.newVideoDays) },
                hidden = !n.group && first.bucketId in hidden,
                path = folderPath,
                group = n.group,
            )
            entries.add(Triple(item, n.items.maxOf { it.dateAddedSec }, n.items.sumOf { it.sizeBytes }))
        }
        val cur = dir
        val base = dir
        val children = LinkedHashMap<String, Node>() // alt klasör yolu → düğüm
        val own = ArrayList<VideoItem>()
        for (v in vis) {
            val d = dirOf(v)
            if (d.isEmpty() || rootOf(d).isEmpty()) {
                // Yolu bilinmeyen: düz klasör (yalnız en üst düzeyde)
                if (cur == null) children.getOrPut("#" + v.bucketId) { Node("#" + v.bucketId, v.bucketName) }.items.add(v)
                continue
            }
            val root = rootOf(d)
            if (base == null) {
                // En üst düzey: depolama kökünün altındaki ilk klasör (kökteki videolar kendi klasörü olarak görünür)
                val rel = d.removePrefix(root).trimStart('/')
                val seg = rel.substringBefore('/')
                val key = if (rel.isEmpty()) root else "$root/$seg"
                val n = children.getOrPut(key) { Node(key, if (rel.isEmpty()) v.bucketName else seg) }
                n.items.add(v)
                if (rel.contains('/')) n.group = true
            } else {
                if (d == base) { own.add(v); continue }
                if (!d.startsWith("$base/")) continue
                val rel = d.removePrefix("$base/")
                val seg = rel.substringBefore('/')
                val key = "$base/$seg"
                val n = children.getOrPut(key) { Node(key, seg) }
                n.items.add(v)
                if (rel.contains('/')) n.group = true
            }
        }
        // Kendi videoları olan ve alt klasörü de bulunan düğümler: alt klasör yoksa düz klasör olarak kalır.
        if (own.isNotEmpty()) {
            val n = Node(base ?: "", base!!.substringAfterLast('/'))
            n.items.addAll(own)
            addNode(n, base)
        }
        val ownCount = entries.size
        for ((key, n) in children) addNode(n, if (key.startsWith("#")) "" else key)
        // En üst düzeyde, alt klasörü olup kendi videosu da olan klasör: kendi videoları alt listede görünür.
        val byName = Comparator<Triple<FolderItem, Long, Long>> { a, b -> a.first.name.lowercase().compareTo(b.first.name.lowercase()) }
        val order = settings.folderOrder
        val baseCmp: Comparator<Triple<FolderItem, Long, Long>> = when (order.substringBeforeLast('_')) {
            "new" -> compareBy<Triple<FolderItem, Long, Long>> { it.second }
            "count" -> compareBy<Triple<FolderItem, Long, Long>> { it.first.videoCount }
            "size" -> compareBy<Triple<FolderItem, Long, Long>> { it.third }
            else -> byName
        }
        val dirCmp = if (order.endsWith("_desc")) baseCmp.reversed() else baseCmp
        val head = entries.take(ownCount)
        val rest = entries.drop(ownCount).sortedWith(dirCmp.then(byName))
        result.addAll((head + rest).map { it.first })
        return result
    }

    private fun status(context: Context, v: VideoItem): Int = when {
        ResumeStore.isPlayed(context, v.uri.toString()) -> 2
        ResumeStore.position(context, v.uri) > 0L -> 1
        else -> 0
    }

    /** Seçili ölçüte ve yöne göre sıralar. */
    fun sort(context: Context, videos: List<VideoItem>, settings: AppSettings): List<VideoItem> {
        val ci: Comparator<String> = if (settings.naturalSort) NaturalOrder else String.CASE_INSENSITIVE_ORDER
        val cmp: Comparator<VideoItem> = when (settings.sortKey) {
            SortKey.TITLE -> compareBy<VideoItem, String>(ci) { it.title }
            SortKey.DATE -> compareBy<VideoItem> { it.dateAddedSec }
            SortKey.LAST_PLAYED -> compareBy<VideoItem> { ResumeStore.lastPlayed(context, it.uri) }
            SortKey.STATUS -> compareBy<VideoItem> { status(context, it) }
            SortKey.DURATION -> compareBy<VideoItem> { it.durationMs }
            SortKey.SIZE -> compareBy<VideoItem> { it.sizeBytes }
            SortKey.RESOLUTION -> compareBy<VideoItem> { it.widthPx.toLong() * it.heightPx }
            SortKey.PATH -> compareBy<VideoItem, String>(ci) { it.path }
            SortKey.EXTENSION -> compareBy<VideoItem, String>(ci) { it.title.substringAfterLast('.', "") }
        }
        // Eşit olanlar başlığa göre sıralanır; sonuç kararlı kalır.
        val full = cmp.thenBy(ci) { it.title }
        return videos.sortedWith(if (settings.sortAscending) full else full.reversed())
    }
}

/** "Bölüm 2", "Bölüm 10"dan önce gelsin diye sayıları değerine göre karşılaştırır. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            if (x.isDigit() && y.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
