package com.mangastudio.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.mangastudio.app.core.model.NormalizedRect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

data class ImageSlice(
    val offsetY: Int,
    val height: Int,
    val bitmap: Bitmap
)

object ManhwaImageHelper {

    private const val MAX_SINGLE_SLICE_HEIGHT = 2400
    private const val SLICE_OVERLAP_GUARD = 120
    private const val GUTTER_SEARCH_WINDOW = 320

    fun getImageDimensions(path: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, opts)
        return Pair(opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0))
    }

    fun loadMutableBitmap(path: String): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(path, opts)
            ?: throw IllegalStateException("امکان خواندن تصویر وجود ندارد: $path")
        return if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            copy
        }
    }

    fun saveBitmap(
        bitmap: Bitmap,
        destFile: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 98
    ) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { out ->
            bitmap.compress(format, quality, out)
            out.flush()
        }
    }

    fun createSmartSlices(path: String): List<ImageSlice> {
        val (width, height) = getImageDimensions(path)
        if (width <= 0 || height <= 0) return emptyList()

        if (height <= MAX_SINGLE_SLICE_HEIGHT + 400) {
            val full = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: return emptyList()
            return listOf(ImageSlice(offsetY = 0, height = full.height, bitmap = full))
        }

        val decoder = openRegionDecoder(path)
        if (decoder == null) {
            val full = loadMutableBitmap(path)
            return sliceInMemory(full).also { full.recycle() }
        }

        val slices = mutableListOf<ImageSlice>()
        try {
            var currentY = 0
            while (currentY < height) {
                val remaining = height - currentY
                if (remaining <= MAX_SINGLE_SLICE_HEIGHT + GUTTER_SEARCH_WINDOW) {
                    val region = Rect(0, currentY, width, height)
                    val bmp = decoder.decodeRegion(
                        region,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    )
                    if (bmp != null) {
                        slices.add(ImageSlice(currentY, bmp.height, bmp))
                    }
                    break
                }

                val targetCutY = currentY + MAX_SINGLE_SLICE_HEIGHT
                val searchTop = (targetCutY - GUTTER_SEARCH_WINDOW).coerceAtLeast(currentY + 600)
                val searchBottom = (targetCutY + GUTTER_SEARCH_WINDOW).coerceAtMost(height - 100)

                val bestCutY = findBestGutterY(decoder, width, searchTop, searchBottom)
                val nextEndY = bestCutY.coerceIn(currentY + 500, height)

                val region = Rect(0, currentY, width, nextEndY)
                val bmp = decoder.decodeRegion(
                    region,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                )
                if (bmp != null) {
                    slices.add(ImageSlice(currentY, bmp.height, bmp))
                }

                val stepBack = if (nextEndY == targetCutY) SLICE_OVERLAP_GUARD else 0
                currentY = (nextEndY - stepBack).coerceAtLeast(currentY + 400)
            }
        } finally {
            decoder.recycle()
        }

        return slices
    }

    private fun openRegionDecoder(path: String): BitmapRegionDecoder? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(path, false)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findBestGutterY(
        decoder: BitmapRegionDecoder,
        width: Int,
        searchTop: Int,
        searchBottom: Int
    ): Int {
        val stripHeight = (searchBottom - searchTop).coerceAtLeast(1)
        val sampleStrip = try {
            decoder.decodeRegion(
                Rect(0, searchTop, width, searchBottom),
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = if (width > 1000) 2 else 1
                }
            )
        } catch (_: Exception) {
            return (searchTop + searchBottom) / 2
        } ?: return (searchTop + searchBottom) / 2

        try {
            val sw = sampleStrip.width
            val sh = sampleStrip.height
            val scaleY = stripHeight.toFloat() / sh.toFloat()
            val rowPixels = IntArray(sw)
            val stepX = max(1, sw / 80)

            var bestRow = sh / 2
            var minVariance = Double.MAX_VALUE

            for (y in 0 until sh) {
                sampleStrip.getPixels(rowPixels, 0, sw, 0, y, sw, 1)
                var sumLuma = 0.0
                var count = 0
                var x = 0
                while (x < sw) {
                    val p = rowPixels[x]
                    val luma = 0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)
                    sumLuma += luma
                    count++
                    x += stepX
                }
                val mean = sumLuma / count.coerceAtLeast(1)
                var variance = 0.0
                x = 0
                while (x < sw) {
                    val p = rowPixels[x]
                    val luma = 0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)
                    val diff = luma - mean
                    variance += diff * diff
                    x += stepX
                }
                variance /= count.coerceAtLeast(1)

                val whiteBonus = if (mean > 240.0 || mean < 15.0) 0.5 else 1.0
                val score = variance * whiteBonus
                if (score < minVariance) {
                    minVariance = score
                    bestRow = y
                }
            }
            return (searchTop + (bestRow * scaleY).toInt()).coerceIn(searchTop, searchBottom)
        } finally {
            sampleStrip.recycle()
        }
    }

    private fun sliceInMemory(full: Bitmap): List<ImageSlice> {
        val slices = mutableListOf<ImageSlice>()
        var y = 0
        while (y < full.height) {
            val h = min(MAX_SINGLE_SLICE_HEIGHT, full.height - y)
            val sub = Bitmap.createBitmap(full, 0, y, full.width, h)
            slices.add(ImageSlice(y, h, sub))
            y += h
        }
        return slices
    }

    fun hasInkBarrierBetween(
        bitmap: Bitmap,
        upper: NormalizedRect,
        lower: NormalizedRect
    ): Boolean {
        val startY = upper.bottom.toInt().coerceIn(0, bitmap.height - 1)
        val endY = lower.top.toInt().coerceIn(0, bitmap.height - 1)
        if (endY - startY <= 3) return false

        val overlapLeft = max(upper.left, lower.left).toInt().coerceIn(0, bitmap.width - 1)
        val overlapRight = min(upper.right, lower.right).toInt().coerceIn(0, bitmap.width - 1)
        val sampleWidth = overlapRight - overlapLeft
        if (sampleWidth < 8) return false

        val checkStartX = overlapLeft + (sampleWidth * 0.2f).toInt()
        val checkEndX = overlapRight - (sampleWidth * 0.2f).toInt()
        val span = (checkEndX - checkStartX).coerceAtLeast(4)

        val rowBuffer = IntArray(span)
        for (y in (startY + 1) until endY) {
            bitmap.getPixels(rowBuffer, 0, span, checkStartX, y, span, 1)
            var darkCount = 0
            for (pixel in rowBuffer) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma < 85) darkCount++
            }
            if (darkCount.toFloat() / span.toFloat() > 0.55f) {
                return true
            }
        }
        return false
    }

    fun rectsOverlapRatio(a: NormalizedRect, b: NormalizedRect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val minArea = min(a.width * a.height, b.width * b.height).coerceAtLeast(1f)
        return interArea / minArea
    }
}
