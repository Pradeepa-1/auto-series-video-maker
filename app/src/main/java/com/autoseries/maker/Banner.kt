package com.autoseries.maker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

class Template(
    val name: String,
    val c1: Int,
    val c2: Int,
    val text: Int,
    val accent: Int,
    val chipText: Int,
    val radius: Float,
    val shadow: Boolean,
)

private fun c(hex: Long) = hex.toInt()

val TEMPLATES = listOf(
    Template("Sunset", c(0xFFFF512F), c(0xFFF09819), c(0xFFFFFFFF), c(0xFFFFD54F), c(0xFF3E2000), 48f, true),
    Template("Ocean", c(0xFF0F2027), c(0xFF2C7DA0), c(0xFFFFFFFF), c(0xFF4FC3F7), c(0xFF002233), 40f, true),
    Template("Royal", c(0xFF41295A), c(0xFF8E2DE2), c(0xFFFFFFFF), c(0xFFFFCA28), c(0xFF2A1040), 56f, true),
    Template("Forest", c(0xFF134E5E), c(0xFF71B280), c(0xFFFFFFFF), c(0xFFFFF176), c(0xFF0B3320), 36f, true),
    Template("Rose", c(0xFFC31432), c(0xFF240B36), c(0xFFFFFFFF), c(0xFFFF8A80), c(0xFF3A0010), 52f, true),
    Template("Gold", c(0xFF1A1A1A), c(0xFF3A3A3A), c(0xFFFFD54F), c(0xFFFFB300), c(0xFF1A1200), 24f, true),
    Template("Clean", c(0xFFFFFFFF), c(0xFFEDEDED), c(0xFF1A1A1A), c(0xFFE53935), c(0xFFFFFFFF), 60f, true),
    Template("Neon", c(0xFF000428), c(0xFF004E92), c(0xFF69F0AE), c(0xFF00E5FF), c(0xFF00212B), 28f, false),
)

val FONTS: List<Pair<String, Typeface>> by lazy {
    listOf(
        "Bold" to Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
        "Serif" to Typeface.create(Typeface.SERIF, Typeface.BOLD),
        "Condensed" to Typeface.create("sans-serif-condensed", Typeface.BOLD),
        "Rounded" to Typeface.create("sans-serif-medium", Typeface.BOLD),
    )
}

val TITLE_COLORS = listOf(
    "Auto" to 0, "White" to c(0xFFFFFFFF), "Yellow" to c(0xFFFFEB3B),
    "Black" to c(0xFF111111), "Mint" to c(0xFF69F0AE), "Pink" to c(0xFFFF4DA6),
)
val PART_STYLES = listOf("PART 01", "PART 1", "EPISODE 01", "பாகம் 01")
val PART_POSITIONS = listOf("Top Right", "Bottom Right", "Bottom Center")
val ALIGNS = listOf("Left", "Center", "Right")
val SIZES = listOf("Small", "Medium", "Large")

val TITLE_STYLES = listOf("Box banner", "Text only (above video)")
val BG_MODES = listOf("Blur video", "Solid colour")
val BG_COLORS = listOf(
    "Black" to c(0xFF000000), "Navy" to c(0xFF0D1B4C), "Purple" to c(0xFF4A148C), "Red" to c(0xFFB71C1C),
    "Green" to c(0xFF1B5E20), "Teal" to c(0xFF006064), "Orange" to c(0xFFE65100), "Pink" to c(0xFFAD1457),
    "Grey" to c(0xFF37474F), "White" to c(0xFFFFFFFF),
)

data class DesignSpec(
    val titleStyle: Int = 1,
    val bgMode: Int = 0,
    val bgColor: Int = BG_COLORS[1].second,
    val title: String = "",
    val seriesName: String = "SERIES",
    val template: Int = 0,
    val font: Int = 0,
    val sizeLevel: Int = 1,
    val align: Int = 1,
    val titleColor: Int = 0,
    val partStyle: Int = 0,
    val partPos: Int = 1,
)

object Banner {
    fun partLabel(spec: DesignSpec, n: Int): String = when (spec.partStyle) {
        0 -> "PART %02d".format(n)
        1 -> "PART $n"
        2 -> "EPISODE %02d".format(n)
        else -> "பாகம் %02d".format(n)
    }

    /** Transparent overlay (banner, series name, part label). Used by both the live preview and the export, so they match. */
    /** Size the clip occupies on the canvas: full width for landscape, never taller than ~1000/1080 of the width. */
    fun clipSize(w: Int, vw: Int, vh: Int): Pair<Int, Int> {
        val fit = minOf(w.toFloat() / vw, (1000f * w / 1080f) / vh)
        return ((vw * fit).toInt() / 2 * 2).coerceAtLeast(2) to ((vh * fit).toInt() / 2 * 2).coerceAtLeast(2)
    }

    fun render(spec: DesignSpec, w: Int, h: Int, seriesIdx: Int, part: Int, vw: Int = 1920, vh: Int = 1080): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val s = w / 1080f
        val t = TEMPLATES[spec.template]

        if (spec.title.isNotBlank()) {
            val videoTop = (h - clipSize(w, vw, vh).second) / 2f
            if (spec.titleStyle == 1) drawPlainTitle(cv, spec, t, w, s, videoTop) else drawBanner(cv, spec, t, w, s)
        }

        val face = FONTS[spec.font].second
        val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 54f * s; color = t.chipText }
        val seriesPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 42f * s; color = Color.WHITE }
        val margin = 48f * s
        val seriesText = "${spec.seriesName.ifBlank { "SERIES" }} %02d".format(seriesIdx)
        val rowY = h * 0.8125f
        val partText = partLabel(spec, part)
        val dark = 0xB0000000.toInt()
        when (spec.partPos) {
            0 -> {
                pill(cv, partText, chipPaint, w - margin, 60f * s, 2, t.accent)
                pill(cv, seriesText, seriesPaint, margin, rowY, 0, dark)
            }
            1 -> {
                pill(cv, partText, chipPaint, w - margin, rowY, 2, t.accent)
                pill(cv, seriesText, seriesPaint, margin, rowY + 6f * s, 0, dark)
            }
            else -> {
                pill(cv, seriesText, seriesPaint, w / 2f, rowY - 120f * s, 1, dark)
                pill(cv, partText, chipPaint, w / 2f, rowY, 1, t.accent)
            }
        }
        return bmp
    }

    /** Title as glowing text with no box, sitting just above the video. */
    private fun drawPlainTitle(cv: Canvas, spec: DesignSpec, t: Template, w: Int, s: Float, videoTop: Float) {
        val fill = if (spec.titleColor != 0) spec.titleColor else t.accent
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FONTS[spec.font].second
            color = fill
            setShadowLayer(22f * s, 0f, 0f, fill)
        }
        val innerW = (w - 2 * 60f * s).toInt()
        val align = when (spec.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        var size = (64f + 14f * spec.sizeLevel) * s
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(spec.title, 0, spec.title.length, tp, innerW).setAlignment(align).build()
            if (layout.lineCount <= 2 || size <= 26f * s) break
            size -= 2f * s
        }
        val top = (videoTop - 36f * s - layout.height).coerceAtLeast(40f * s)
        cv.save()
        cv.translate(60f * s, top)
        layout.draw(cv)
        tp.clearShadowLayer()
        tp.color = fill
        layout.draw(cv) // crisp second pass over the glow
        cv.restore()
    }

    private fun drawBanner(cv: Canvas, spec: DesignSpec, t: Template, w: Int, s: Float) {
        val margin = 48f * s
        val pad = 36f * s
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FONTS[spec.font].second
            color = if (spec.titleColor != 0) spec.titleColor else t.text
        }
        val innerW = (w - 2 * margin - 2 * pad).toInt()
        val align = when (spec.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        var size = (70f + 14f * spec.sizeLevel) * s
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(spec.title, 0, spec.title.length, tp, innerW)
                .setAlignment(align).setLineSpacing(0f, 1.05f).build()
            if ((layout.lineCount <= 2 && layout.height <= 300f * s) || size <= 26f * s) break
            size -= 2f * s
        }
        val top = 150f * s
        val rect = RectF(margin, top, w - margin, top + layout.height + 2 * pad)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, t.c1, t.c2, Shader.TileMode.CLAMP)
            if (t.shadow) setShadowLayer(26f * s, 0f, 10f * s, 0x99000000.toInt())
        }
        cv.drawRoundRect(rect, t.radius * s, t.radius * s, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * s; color = t.accent }
        cv.drawRoundRect(rect, t.radius * s, t.radius * s, border)
        cv.save()
        cv.translate(margin + pad, top + pad)
        layout.draw(cv)
        cv.restore()
        val bar = RectF(w / 2f - 110f * s, rect.bottom + 14f * s, w / 2f + 110f * s, rect.bottom + 24f * s)
        cv.drawRoundRect(bar, 5f * s, 5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent })
    }

    /** anchor: 0 = x is the left edge, 1 = x is the centre, 2 = x is the right edge. */
    private fun pill(cv: Canvas, text: String, tp: TextPaint, x: Float, y: Float, anchor: Int, fill: Int) {
        val padX = tp.textSize * 0.7f
        val padY = tp.textSize * 0.4f
        val tw = tp.measureText(text)
        val fm = tp.fontMetrics
        val bw = tw + 2 * padX
        val bh = (fm.descent - fm.ascent) + 2 * padY
        val left = when (anchor) { 0 -> x; 1 -> x - bw / 2; else -> x - bw }
        val r = RectF(left, y, left + bw, y + bh)
        cv.drawRoundRect(r, bh / 2, bh / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        cv.drawText(text, left + padX, y + padY - fm.ascent, tp)
    }
}
