@file:Suppress("DEPRECATION")

package org.openplayer.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MusicTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNo: Int,
    val dateAddedSec: Long,
    val folderPath: String,
    val path: String = "",
    val year: Int = 0,
    val genre: String = "",
    val albumArtist: String = "",
    val coverFile: String? = null,
    val sizeBytes: Long = 0L,
    val composer: String = "",
) {
    val artworkUri: Uri
        get() = if (coverFile != null) Uri.fromFile(java.io.File(coverFile))
        else ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
}

data class MusicGroup(val key: String, val title: String, val subtitle: String, val tracks: List<MusicTrack>)

enum class MusicSort(val label: String) {
    TITLE("Başlık"),
    ARTIST("Sanatçı"),
    ALBUM("Albüm"),
    NEWEST("Eklenme (yeniden eskiye)"),
    DURATION("Süre (uzundan kısaya)"),
}

object MusicRepository {

    private const val UNKNOWN_ARTIST = "Bilinmeyen sanatçı"
    private const val UNKNOWN_ALBUM = "Bilinmeyen albüm"

    fun loadAll(context: Context): List<MusicTrack> {
        val extended = runCatching { query(context, true) }.getOrNull()
        return extended ?: query(context, false)
    }

    private fun query(context: Context, extra: Boolean): List<MusicTrack> {
        val out = ArrayList<MusicTrack>()
        val base = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE,
        )
        val projection = if (extra) {
            base + arrayOf("composer", "genre", "album_artist")
        } else base
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iComposer = if (extra) c.getColumnIndexOrThrow("composer") else -1
                val iGenre = if (extra) c.getColumnIndexOrThrow("genre") else -1
                val iAlbumArtist = if (extra) c.getColumnIndexOrThrow("album_artist") else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: UNKNOWN_ARTIST
                    val album = c.getString(iAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: UNKNOWN_ALBUM
                    val path = c.getString(iData) ?: ""
                    val base = MusicTrack(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'),
                        artist = artist,
                        album = album,
                        albumId = c.getLong(iAlbumId),
                        durationMs = c.getLong(iDur),
                        trackNo = c.getInt(iTrack) % 1000,
                        dateAddedSec = c.getLong(iDate),
                        folderPath = path.substringBeforeLast('/', ""),
                        path = path,
                        year = c.getInt(iYear),
                        sizeBytes = c.getLong(iSize),
                        composer = if (iComposer >= 0) c.getString(iComposer)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "" else "",
                        genre = if (iGenre >= 0) c.getString(iGenre)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "" else "",
                        albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "" else "",
                    )
                    out += MusicTagStore.apply(context, base)
                }
            }
        } catch (_: SecurityException) {
        } catch (e: RuntimeException) {
            // Genişletilmiş sütunlar bu cihazda yoksa üst katman temel sorguyu dener.
            if (extra) throw e
        }
        return out
    }

    fun sort(tracks: List<MusicTrack>, by: MusicSort): List<MusicTrack> {
        val ci = NaturalOrder
        return when (by) {
            MusicSort.TITLE -> tracks.sortedWith(compareBy(ci) { it.title })
            MusicSort.ARTIST -> tracks.sortedWith(compareBy<MusicTrack, String>(ci) { it.artist }.thenBy(ci) { it.title })
            MusicSort.ALBUM -> tracks.sortedWith(compareBy<MusicTrack, String>(ci) { it.album }.thenBy { it.trackNo }.thenBy(ci) { it.title })
            MusicSort.NEWEST -> tracks.sortedByDescending { it.dateAddedSec }
            MusicSort.DURATION -> tracks.sortedByDescending { it.durationMs }
        }
    }

    private fun countText(n: Int) = "$n şarkı"

    fun albums(tracks: List<MusicTrack>): List<MusicGroup> =
        tracks.groupBy { it.albumId to it.album }.map { (k, list) ->
            val artists = list.map { it.artist }.distinct()
            MusicGroup(
                "album_${k.first}", k.second,
                (if (artists.size == 1) artists[0] else "Çeşitli sanatçılar") + " • " + countText(list.size),
                list.sortedWith(compareBy<MusicTrack> { it.trackNo }.thenBy(NaturalOrder) { it.title }),
            )
        }.sortedWith(compareBy(NaturalOrder) { it.title })

    fun artists(tracks: List<MusicTrack>): List<MusicGroup> =
        tracks.groupBy { it.artist }.map { (artist, list) ->
            val albums = list.map { it.album }.distinct().size
            MusicGroup(
                "artist_$artist", artist, "$albums albüm • " + countText(list.size),
                list.sortedWith(compareBy<MusicTrack, String>(NaturalOrder) { it.album }.thenBy { it.trackNo }),
            )
        }.sortedWith(compareBy(NaturalOrder) { it.title })

    fun folders(tracks: List<MusicTrack>): List<MusicGroup> =
        tracks.groupBy { it.folderPath }.map { (path, list) ->
            MusicGroup(
                "folder_$path", path.substringAfterLast('/').ifEmpty { "/" }, countText(list.size),
                list.sortedWith(compareBy(NaturalOrder) { it.title }),
            )
        }.sortedWith(compareBy(NaturalOrder) { it.title })

    private fun group(prefix: String, tracks: List<MusicTrack>, key: (MusicTrack) -> String, unknown: String, sub: (List<MusicTrack>) -> String = { countText(it.size) }) =
        tracks.groupBy { key(it).ifBlank { unknown } }.map { (k, list) ->
            MusicGroup("${prefix}_$k", k, sub(list), list.sortedWith(compareBy<MusicTrack, String>(NaturalOrder) { it.artist }.thenBy(NaturalOrder) { it.album }.thenBy { it.trackNo }))
        }

    fun albumArtists(tracks: List<MusicTrack>): List<MusicGroup> =
        group("aa", tracks, { it.albumArtist.ifBlank { it.artist } }, UNKNOWN_ARTIST) { l -> "${l.map { it.album }.distinct().size} albüm • " + countText(l.size) }
            .sortedWith(compareBy(NaturalOrder) { it.title })

    fun genres(tracks: List<MusicTrack>): List<MusicGroup> =
        group("genre", tracks, { it.genre }, "Bilinmeyen tür").sortedWith(compareBy(NaturalOrder) { it.title })

    fun composers(tracks: List<MusicTrack>): List<MusicGroup> =
        group("composer", tracks, { it.composer }, "Bilinmeyen besteci").sortedWith(compareBy(NaturalOrder) { it.title })

    fun years(tracks: List<MusicTrack>): List<MusicGroup> =
        group("year", tracks, { if (it.year > 0) it.year.toString() else "" }, "Bilinmeyen yıl")
            .sortedByDescending { it.title.toIntOrNull() ?: -1 }
}
