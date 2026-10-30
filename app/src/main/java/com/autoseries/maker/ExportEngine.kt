@file:OptIn(UnstableApi::class)

package com.autoseries.maker

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import kotlin.math.min

/**
 * Renders ONE part: blurred+darkened copy of the clip fills the 9:16 canvas, the untouched clip sits
 * in the middle at its own aspect ratio, and the banner/series/part overlay is drawn on top.
 * Audio comes from the foreground copy only. Everything runs locally; one segment at a time.
 */
class ExportEngine(private val ctx: Context) {

    suspend fun render(
        src: Uri,
        meta: VideoMeta,
        plan: PartPlan,
        spec: DesignSpec,
        quality: Int,
        out: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val w = if (quality == 720) 720 else 1080
        val h = if (quality == 720) 1280 else 1920

        // Fit the clip into W x (1000/1080 of W) so it never collides with the part label row.
        val fit = min(w.toFloat() / meta.width, (1000f * w / 1080f) / meta.height)
        val fgW = even(meta.width * fit)
        val fgH = even(meta.height * fit)

        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(plan.startMs)
            .setEndPositionMs(plan.endMs)
            .build()
        val item = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()

        val background = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                        GaussianBlur(18f * w / 1080f),
                        Brightness(-0.25f),
                    ),
                )
            )
            .build()
        val foreground = EditedMediaItem.Builder(item)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForWidthAndHeight(fgW, fgH, Presentation.LAYOUT_SCALE_TO_FIT)),
                )
            )
            .build()

        val overlayBitmap = Banner.render(spec, w, h, plan.seriesIndex, plan.partInSeries)
        val overlay = OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)))

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

        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(if (quality == 720) 5_000_000 else 8_000_000).build()
            )
            .build()

        if (out.exists()) out.delete()
        val handler = Handler(Looper.getMainLooper())
        try {
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
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                        handler.postDelayed(this, 400)
                    }
                }
                handler.postDelayed(poll, 400)
            }
        } finally {
            overlayBitmap.recycle()
        }
    }

    private fun even(v: Float): Int = (v.toInt() / 2 * 2).coerceAtLeast(2)
}
