package com.autoseries.maker

import android.net.Uri

data class VideoMeta(
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float?,
    val hasAudio: Boolean,
    val bitrate: Int = 0,
) {
    val aspect: String get() = if (height == 0) "?" else "%.2f:1".format(width.toFloat() / height)
}

data class PartPlan(
    val globalIndex: Int,    // 1-based over the whole source
    val seriesIndex: Int,    // 1-based
    val partInSeries: Int,   // 1-based, restarts every series
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs get() = endMs - startMs
}

data class ExportedPart(val plan: PartPlan, val uri: Uri, val name: String)

data class ProjectRecord(val folder: String, val source: String, val parts: Int, val series: Int, val date: Long, val complete: Boolean)

object SeriesPlanner {
    /** A trailing remainder shorter than this is merged into the previous part instead of becoming a stub. */
    const val MIN_TAIL_MS = 1500L

    fun plan(totalMs: Long, splitSec: Int, partsPerSeries: Int): List<PartPlan> {
        if (totalMs <= 0 || splitSec <= 0 || partsPerSeries <= 0) return emptyList()
        val step = splitSec * 1000L
        val full = (totalMs / step).toInt()
        val rem = totalMs % step
        val count = when {
            rem == 0L -> full
            rem < MIN_TAIL_MS && full > 0 -> full
            else -> full + 1
        }
        return (0 until count).map { i ->
            val start = i * step
            val end = if (i == count - 1) totalMs else start + step
            PartPlan(i + 1, i / partsPerSeries + 1, i % partsPerSeries + 1, start, end)
        }
    }
}

enum class Screen { HOME, SETUP, EXPORT, DONE }
