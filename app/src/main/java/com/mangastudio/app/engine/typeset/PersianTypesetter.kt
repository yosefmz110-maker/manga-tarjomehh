package com.mangastudio.app.engine.typeset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.mangastudio.app.core.image.ManhwaImageHelper
import com.mangastudio.app.core.model.BubbleType
import com.mangastudio.app.core.model.StudioSettings
import com.mangastudio.app.core.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PersianTypesetter(private val context: Context) {

    companion object {
        private const val RLM = "\u200F"
        private const val MIN_FONT_SIZE = 13f
        private const val MAX_FONT_SIZE = 86f
        private const val LINE_SPACING_MULT = 1.08f
    }

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    suspend fun renderPage(
        cleanedImagePath: String,
        outputRenderedPath: String,
        blocks: List<TextBlock>,
        settings: StudioSettings
    ): String = withContext(Dispatchers.Default) {
        val bitmap = ManhwaImageHelper.loadMutableBitmap(cleanedImagePath)
        val typeface = loadTypeface(settings.fontName)

        try {
            val canvas = Canvas(bitmap)

            for (block in blocks) {
                val text = block.translatedText.trim()
                if (text.isEmpty()) continue

                val safeRect = computeSafeBubbleRect(bitmap, block)
                drawFittedPersianBlock(
                    canvas = canvas,
                    block = block,
                    targetRect = safeRect,
                    baseTypeface = typeface
                )
            }

            val outFile = File(outputRenderedPath)
            val format = if (settings.pdfQuality >= 100) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            ManhwaImageHelper.saveBitmap(
                bitmap = bitmap,
                destFile = outFile,
                format = format,
                quality = settings.pdfQuality.coerceIn(85, 100)
            )
            outFile.absolutePath
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun computeSafeBubbleRect(bitmap: Bitmap, block: TextBlock): RectF {
        val b = block.bounds
        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()

        var left = (b.left + block.offsetX).coerceIn(4f, imgW - 20f)
        var top = (b.top + block.offsetY).coerceIn(4f, imgH - 20f)
        var right = (b.right + block.offsetX).coerceIn(left + 16f, imgW - 4f)
        var bottom = (b.bottom + block.offsetY).coerceIn(top + 16f, imgH - 4f)

        if (!block.isBackgroundComplex && block.type != BubbleType.SFX) {
            val bgColor = block.estimatedBgColor
            val maxExpandX = min((right - left) * 0.24f, 36f).toInt()
            val maxExpandY = min((bottom - top) * 0.22f, 32f).toInt()
            val cy = ((top + bottom) / 2f).toInt().coerceIn(0, bitmap.height - 1)
            val cx = ((left + right) / 2f).toInt().coerceIn(0, bitmap.width - 1)

            val freeLeft = probeFreePixels(bitmap, left.toInt(), cy, -1, 0, maxExpandX, bgColor)
            val freeRight = probeFreePixels(bitmap, right.toInt(), cy, 1, 0, maxExpandX, bgColor)
            val freeTop = probeFreePixels(bitmap, cx, top.toInt(), 0, -1, maxExpandY, bgColor)
            val freeBottom = probeFreePixels(bitmap, cx, bottom.toInt(), 0, 1, maxExpandY, bgColor)

            val safePad = 6f
            left = (left - max(0, freeLeft - safePad.toInt())).coerceAtLeast(4f)
            right = (right + max(0, freeRight - safePad.toInt())).coerceAtMost(imgW - 4f)
            top = (top - max(0, freeTop - safePad.toInt())).coerceAtLeast(4f)
            bottom = (bottom + max(0, freeBottom - safePad.toInt())).coerceAtMost(imgH - 4f)
        }

        val minReadableWidth = min(imgW * 0.28f, 110f)
        if (right - left < minReadableWidth && !block.isBackgroundComplex) {
            val cx = (left + right) / 2f
            val half = minReadableWidth / 2f
            left = (cx - half).coerceAtLeast(4f)
            right = (cx + half).coerceAtMost(imgW - 4f)
        }

        return RectF(left, top, right, bottom)
    }

    private fun probeFreePixels(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        dx: Int,
        dy: Int,
        maxSteps: Int,
        bgColor: Int
    ): Int {
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        var free = 0

        for (step in 1..maxSteps) {
            val x = startX + dx * step
            val y = startY + dy * step
            if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) break
            val p = bitmap.getPixel(x, y)
            val dr = Color.red(p) - bgR
            val dg = Color.green(p) - bgG
            val db = Color.blue(p) - bgB
            val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())
            if (dist > 40.0) break
            free = step
        }
        return free
    }

    private fun drawFittedPersianBlock(
        canvas: Canvas,
        block: TextBlock,
        targetRect: RectF,
        baseTypeface: Typeface
    ) {
        val widthPx = targetRect.width().toInt().coerceAtLeast(24)
        val heightPx = targetRect.height().toInt().coerceAtLeast(20)
        val rtlText = formatRtlText(block.translatedText)

        val effectiveTypeface = when (block.type) {
            BubbleType.SHOUT, BubbleType.SFX -> Typeface.create(baseTypeface, Typeface.BOLD)
            BubbleType.THOUGHT -> Typeface.create(baseTypeface, Typeface.ITALIC)
            else -> baseTypeface
        }

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = block.textColor
            typeface = effectiveTypeface
            style = Paint.Style.FILL
            isFakeBoldText = (block.type == BubbleType.SHOUT || block.type == BubbleType.SFX)
        }

        val chosenFontSize = block.fontSizeOverride
            ?: findOptimalFontSize(
                text = rtlText,
                paint = fillPaint,
                maxWidth = widthPx,
                maxHeight = heightPx,
                bubbleType = block.type
            )

        fillPaint.textSize = chosenFontSize
        val finalLayout = buildRtlStaticLayout(rtlText, fillPaint, widthPx)

        val drawX = targetRect.left + (targetRect.width() - finalLayout.width) / 2f
        val drawY = targetRect.top + (targetRect.height() - finalLayout.height) / 2f

        canvas.save()
        canvas.translate(drawX, drawY)

        if (block.useStroke || block.type == BubbleType.SFX) {
            val strokePaint = TextPaint(fillPaint).apply {
                color = block.strokeColor
                style = Paint.Style.STROKE
                strokeWidth = (chosenFontSize * 0.18f).coerceIn(2.5f, 7.5f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val strokeLayout = buildRtlStaticLayout(rtlText, strokePaint, widthPx)
            strokeLayout.draw(canvas)
        }

        finalLayout.draw(canvas)
        canvas.restore()
    }

    private fun findOptimalFontSize(
        text: String,
        paint: TextPaint,
        maxWidth: Int,
        maxHeight: Int,
        bubbleType: BubbleType
    ): Float {
        var low = MIN_FONT_SIZE
        var high = when (bubbleType) {
            BubbleType.SFX, BubbleType.SHOUT -> min(MAX_FONT_SIZE, maxHeight * 0.85f)
            else -> min(56f, maxHeight * 0.65f)
        }.coerceAtLeast(MIN_FONT_SIZE + 2f)

        var bestSize = low

        repeat(10) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val layout = buildRtlStaticLayout(text, paint, maxWidth)

            val fitsHeight = layout.height <= maxHeight
            val fitsSingleWords = !hasBrokenSingleWordOverflow(layout, maxWidth)

            if (fitsHeight && fitsSingleWords) {
                bestSize = mid
                low = mid + 0.5f
            } else {
                high = mid - 0.5f
            }
        }

        return bestSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    private fun hasBrokenSingleWordOverflow(layout: StaticLayout, maxWidth: Int): Boolean {
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > maxWidth + 2f) return true
        }
        return false
    }

    private fun buildRtlStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int
    ): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(0f, LINE_SPACING_MULT)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(true)
        }
        return builder.build()
    }

    private fun formatRtlText(text: String): String {
        return text
            .lines()
            .joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) "" else "$RLM$trimmed"
            }
    }

    private fun loadTypeface(fontName: String): Typeface {
        return typefaceCache.getOrPut(fontName) {
            try {
                Typeface.createFromAsset(context.assets, "fonts/$fontName")
            } catch (_: Exception) {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }
    }

    fun listAvailableFonts(): List<String> {
        val fromAssets = try {
            context.assets.list("fonts")
                ?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return if (fromAssets.isNotEmpty()) fromAssets else listOf("Vazirmatn-Bold.ttf", "Lalezar-Regular.ttf", "System-Bold")
    }
}
