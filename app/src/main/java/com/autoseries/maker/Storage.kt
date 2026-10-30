package com.autoseries.maker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Storage {
    const val ROOT = "AutoSeriesVideoMaker"

    fun readMeta(ctx: Context, uri: Uri): VideoMeta {
        var name = "video"
        var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            fun key(k: Int) = r.extractMetadata(k)
            var w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val dur = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val fps = key(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val audio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            require(w > 0 && h > 0 && dur > 0) { "Unsupported or corrupted video" }
            val br = key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            return VideoMeta(name, size, dur, w, h, fps, audio, br)
        } finally {
            r.release()
        }
    }

    fun thumbnail(ctx: Context, uri: Uri, atMs: Long = 1000): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    fun freeBytes(ctx: Context): Long = StatFs(ctx.cacheDir.path).availableBytes

    class Out(val w: Int, val h: Int, val bitrate: Int)

    /**
     * quality 0 = same as source: canvas resolution is the nearest 9:16 size that does not downscale the clip
     * (720x1280 or 1080x1920) and the bitrate follows the source (clamped), so quality is not lost.
     * 720 / 1080 = fixed, smaller-file presets.
     */
    fun outputFor(quality: Int, meta: VideoMeta): Out {
        val res = when (quality) {
            720 -> 720
            1080 -> 1080
            else -> if (meta.width >= 1000) 1080 else 720
        }
        val bitrate = when {
            quality == 720 -> 2_500_000
            quality == 1080 -> 5_000_000
            meta.bitrate > 0 -> meta.bitrate.coerceIn(2_500_000, 10_000_000)
            else -> if (res == 1080) 8_000_000 else 5_000_000
        }
        return Out(res, res * 16 / 9, bitrate)
    }

    fun estimateBytes(ms: Long, quality: Int, meta: VideoMeta): Long =
        (ms / 1000) * (outputFor(quality, meta).bitrate + 96_000L) / 8

    /** Copies the rendered file into Movies/AutoSeriesVideoMaker/<project>/<series>/ so it appears in the gallery. */
    fun saveVideo(ctx: Context, file: File, project: String, series: Int, part: Int): Uri {
        val rel = "${Environment.DIRECTORY_MOVIES}/$ROOT/$project/Series_%02d".format(series)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Part_%02d.mp4".format(part))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, rel)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Could not create gallery entry (storage full?)")
        try {
            cr.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun saveSummary(ctx: Context, project: String, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${project}_summary.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$project")
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        cr.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    // ---- tiny key-value persistence (settings + history) ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("asvm", Context.MODE_PRIVATE)

    fun nextProjectNumber(ctx: Context): Int {
        val p = prefs(ctx)
        val n = p.getInt("projectCounter", 0) + 1
        p.edit().putInt("projectCounter", n).apply()
        return n
    }

    fun loadInt(ctx: Context, key: String, def: Int) = prefs(ctx).getInt(key, def)
    fun saveInt(ctx: Context, key: String, v: Int) = prefs(ctx).edit().putInt(key, v).apply()

    fun loadHistory(ctx: Context): List<ProjectRecord> {
        val arr = JSONArray(prefs(ctx).getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ProjectRecord(o.getString("folder"), o.getString("source"), o.getInt("parts"), o.getInt("series"), o.getLong("date"), o.getBoolean("complete"))
        }
    }

    fun addHistory(ctx: Context, r: ProjectRecord) {
        val arr = JSONArray(prefs(ctx).getString("history", "[]"))
        arr.put(JSONObject().put("folder", r.folder).put("source", r.source).put("parts", r.parts)
            .put("series", r.series).put("date", r.date).put("complete", r.complete))
        prefs(ctx).edit().putString("history", arr.toString()).apply()
    }
}
