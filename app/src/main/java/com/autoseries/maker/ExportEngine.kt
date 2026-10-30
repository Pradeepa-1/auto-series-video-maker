@file:OptIn(UnstableApi::class)

package com.autoseries.maker

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders ONE part as a 9:16 clip.
 *
 * Solid-colour background: one pipeline (clip scaled to fit + overlay that paints the colour around it). Simple, fast, reliable.
 * Blur background: two layers (blurred copy underneath, clip on top). If that composition fails on a device, it is
 * retried automatically with the simple pipeline and a dark background; render() then returns true.
 */
class ExportEngine(private val ctx: Context) {

    /** @return true when the blur background had to be replaced by the simple fallback. */
    suspend fun render(
        src: Uri,
        meta: VideoMeta,
        plan: PartPlan,
        spec: DesignSpec,
        quality: Int,
        out: File,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.Main) {
        val o = Storage.outputFor(quality, meta)
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(plan.startMs)
            .setEndPositionMs(plan.endMs)
            .build()
        val item = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()

        if (spec.bgMode == 0) {
            try {
                runBlur(item, meta, plan, spec, o, out, onProgress)
                return@withContext false
            } catch (e: ExportException) {
                Log.w("ExportEngine", "Blur composition failed (${e.errorCodeName}); using simple pipeline", e)
            }
        }
        runSimple(item, meta, plan, spec, o, out, onProgress)
        spec.bgMode == 0
    }

    // ---------------------------------------------------------------- simple: one layer + overlay with solid bands
    private suspend fun runSimple(
        item: MediaItem, meta: VideoMeta, plan: PartPlan, spec: DesignSpec,
        o: Storage.Out, out: File, onProgress: (Float) -> Unit,
    ) {
        val band = if (spec.bgMode == 1) spec.bgColor else 0xFF10141A.toInt()
        val bmp = Banner.render(spec, o.w, o.h, plan.seriesIndex, plan.partInSeries, meta.width, meta.height, band)
        try {
            val overlay = OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp)))
            val edited = EditedMediaItem.Builder(item)
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForWidthAndHeight(o.w, o.h, Presentation.LAYOUT_SCALE_TO_FIT), overlay),
                    )
                )
                .build()
            val composition = Composition.Builder(listOf(EditedMediaItemSequence.Builder(edited).build())).build()
            transform(composition, o, out, onProgress)
        } finally {
            bmp.recycle()
        }
    }

    // ---------------------------------------------------------------- blur: blurred copy under the clip
    private suspend fun runBlur(
        item: MediaItem, meta: VideoMeta, plan: PartPlan, spec: DesignSpec,
        o: Storage.Out, out: File, onProgress: (Float) -> Unit,
    ) {
        val w = o.w
        val h = o.h
        val (fgW, fgH) = Banner.clipSize(w, h, meta.width, meta.height)

        val background = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    emptyList(),
                    // Shrink to 1/4 size, real Gaussian blur + slight dim there (16x fewer pixels than full size),
                    // then stretch back up: smooth result, still fast.
                    listOf(
                        Presentation.createForWidthAndHeight(w / 4, h / 4, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                        GaussianBlur(7f),
                        Brightness(-0.2f),
                        Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_STRETCH_TO_FIT),
                    ),
                )
            )
            .build()
        val foreground = EditedMediaItem.Builder(item)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(fgW, fgH, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build()

        val bmp = Banner.render(spec, w, h, plan.seriesIndex, plan.partInSeries, meta.width, meta.height)
        try {
            val overlay = OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp)))
            val compositor = object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: List<Size>) = Size(w, h)
                override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                    StaticOverlaySettings.Builder().build() // centred, native size
            }
            val composition = Composition.Builder(
                listOf(
                    EditedMediaItemSequence.Builder(background).build(), // input 0 = bottom layer
                    EditedMediaItemSequence.Builder(foreground).build(),
                )
            )
                .setVideoCompositorSettings(compositor)
                .setEffects(Effects(emptyList(), listOf(overlay)))
                .build()
            transform(composition, o, out, onProgress)
        } finally {
            bmp.recycle()
        }
    }

    // ---------------------------------------------------------------- shared
    private suspend fun transform(composition: Composition, o: Storage.Out, out: File, onProgress: (Float) -> Unit) {
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(o.bitrate).build())
            .build()
        if (out.exists()) out.delete()
        val handler = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { handler.post { transformer.cancel() } }
            transformer.start(composition, out.absolutePath)

            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 400)
                }
            }
            handler.postDelayed(poll, 400)
        }
    }
}
