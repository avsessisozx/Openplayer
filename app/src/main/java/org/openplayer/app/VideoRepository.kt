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
)

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val videoCount: Int,
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
        )
        val result = ArrayList<VideoItem>()
        try {
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(nameCol) ?: "Video",
                        durationMs = c.getLong(durCol),
                        bucketId = c.getLong(bucketIdCol),
                        bucketName = c.getString(bucketNameCol) ?: "Diğer",
                    )
                }
            }
        } catch (_: SecurityException) {
            // İzin verilmemiş
        }
        return result
    }

    fun folders(videos: List<VideoItem>): List<FolderItem> =
        videos.groupBy { it.bucketId }
            .map { (id, items) -> FolderItem(id, items.first().bucketName, items.size) }
            .sortedBy { it.name.lowercase() }
}
