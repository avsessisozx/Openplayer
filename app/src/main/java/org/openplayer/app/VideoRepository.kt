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
)

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val videoCount: Int,
    val newCount: Int,
    val hidden: Boolean,
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
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(nameCol) ?: "Video",
                        durationMs = c.getLong(durCol),
                        bucketId = c.getLong(bucketIdCol),
                        bucketName = c.getString(bucketNameCol) ?: "Diğer",
                        sizeBytes = c.getLong(sizeCol),
                        dateAddedSec = c.getLong(dateCol),
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
        return videos.groupBy { it.bucketId }
            .map { (id, items) ->
                FolderItem(
                    bucketId = id,
                    name = items.first().bucketName,
                    videoCount = items.size,
                    newCount = items.count { isNew(context, it, settings.newVideoDays) },
                    hidden = id in hidden,
                )
            }
            .filter { settings.showHiddenFolders || !it.hidden }
            .sortedBy { it.name.lowercase() }
    }

    fun sort(videos: List<VideoItem>, order: SortOrder): List<VideoItem> = when (order) {
        SortOrder.NAME_ASC -> videos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        SortOrder.NAME_DESC -> videos.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
        SortOrder.DATE_DESC -> videos.sortedByDescending { it.dateAddedSec }
        SortOrder.DATE_ASC -> videos.sortedBy { it.dateAddedSec }
        SortOrder.SIZE_DESC -> videos.sortedByDescending { it.sizeBytes }
        SortOrder.SIZE_ASC -> videos.sortedBy { it.sizeBytes }
        SortOrder.DURATION_DESC -> videos.sortedByDescending { it.durationMs }
        SortOrder.DURATION_ASC -> videos.sortedBy { it.durationMs }
    }
}
