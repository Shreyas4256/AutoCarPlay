package com.autocarplay.core

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.Locale

data class PhoneVideo(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String?,
)

/** Reads the videos stored on the phone through MediaStore. */
object PhoneVideos {

    /** Runtime permission needed to list every video on this Android version. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAccess(context: Context): Boolean {
        if (granted(context, permission)) return true
        // Android 14+ "Select photos and videos": only the picked items are visible.
        return Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    /** Returns up to [limit] videos starting at [offset], newest first, plus whether more exist. */
    fun query(context: Context, offset: Int, limit: Int): Pair<List<PhoneVideo>, Boolean> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val result = ArrayList<PhoneVideo>()
        var hasMore = false
        context.contentResolver.query(
            collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val folderCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            if (offset > 0 && !cursor.moveToPosition(offset - 1)) return result to false
            while (cursor.moveToNext()) {
                if (result.size == limit) {
                    hasMore = true
                    break
                }
                val id = cursor.getLong(idCol)
                result += PhoneVideo(
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getString(nameCol) ?: "Video $id",
                    durationMs = cursor.getLong(durationCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    folder = cursor.getString(folderCol),
                )
            }
        }
        return result to hasMore
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            context.contentResolver.loadThumbnail(uri, Size(256, 144), null)
        } catch (e: Exception) {
            null
        }
    }

    fun describe(video: PhoneVideo): String {
        val parts = mutableListOf<String>()
        if (video.durationMs > 0) parts += formatDuration(video.durationMs)
        if (video.sizeBytes > 0) parts += formatSize(video.sizeBytes)
        video.folder?.let { parts += it }
        return parts.joinToString(" • ")
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.US, "%.1f GB", mb / 1024)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
