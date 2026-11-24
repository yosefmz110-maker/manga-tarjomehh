package com.mangastudio.app.engine.inpaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mangastudio.app.MangaStudioApp
import com.mangastudio.app.core.image.ManhwaImageHelper
import com.mangastudio.app.core.model.InpaintMode
import com.mangastudio.app.core.model.NormalizedRect
import com.mangastudio.app.core.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

data class InpaintResult(
    val cleanedImagePath: String,
    val updatedBlocks: List<TextBlock>
)

interface InpaintEngine {
    suspend fun cleanPage(
        originalImagePath: String,
        outputCleanedPath: String,
        blocks: List<TextBlock>,
        mode: InpaintMode
    ): InpaintResult
}

private data class BackgroundStats(
    val medianR: Int,
    val medianG: Int,
    val medianB: Int,
    val stdDev: Double
) {
    val colorInt: Int get() = Color.rgb(medianR, medianG, medianB)
    val isComplex: Boolean get() = stdDev > 17.5
    val luminance: Double get() = 0.299 * medianR + 0.587 * medianG + 0.114 * medianB
}

class OpenCvHybridInpaintEngine : InpaintEngine {

    companion object {
        private const val ROI_CONTEXT_MARGIN = 44
        private const val INPAINT_RADIUS = 5.0
    }

    override suspend fun cleanPage(
        originalImagePath: String,
        outputCleanedPath: String,
        blocks: List<TextBlock>,
        mode: InpaintMode
    ): InpaintResult = withContext(Dispatchers.Default) {
        val fullBitmap = ManhwaImageHelper.loadMutableBitmap(originalImagePath)
        val updatedBlocks = mutableListOf<TextBlock>()

        try {
            val canvas = Canvas(fullBitmap)

            for (block in blocks) {
                val targetBoxes = block.lineBoxes.ifEmpty { listOf(block.bounds) }
                val bgStats = analyzeBlockBackground(fullBitmap, targetBoxes)

                val shouldUseTextureInpaint = MangaStudioApp.isOpenCvReady && when (mode) {
                    InpaintMode.ALWAYS_INPAINT -> true
                    InpaintMode.FAST_SOLID -> false
                    InpaintMode.SMART_HYBRID -> bgStats.isComplex
                }

                if (MangaStudioApp.isOpenCvReady) {
                    cleanBlockWithOpenCvRoi(
                        fullBitmap = fullBitmap,
                        canvas = canvas,
                        block = block,
                        lineBoxes = targetBoxes,
                        bgStats = bgStats,
                        useInpainting = shouldUseTextureInpaint
                    )
                } else {
                    cleanBlockFallbackCanvas(canvas, targetBoxes, bgStats.colorInt)
                }

                val isDarkBg = bgStats.luminance < 128.0
                val idealTextColor = if (isDarkBg) Color.WHITE else Color.BLACK
                val idealStrokeColor = if (isDarkBg) Color.BLACK else Color.WHITE

                updatedBlocks.add(
                    block.copy(
                        isBackgroundComplex = bgStats.isComplex,
                        estimatedBgColor = bgStats.colorInt,
                        textColor = idealTextColor,
                        strokeColor = idealStrokeColor,
                        useStroke = bgStats.isComplex
                    )
                )
            }

            ManhwaImageHelper.saveBitmap(
                bitmap = fullBitmap,
                destFile = File(outputCleanedPath),
                format = Bitmap.CompressFormat.PNG,
                quality = 100
            )

            InpaintResult(
                cleanedImagePath = outputCleanedPath,
                updatedBlocks = updatedBlocks
            )
        } finally {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
        }
    }

    private fun cleanBlockWithOpenCvRoi(
        fullBitmap: Bitmap,
        canvas: Canvas,
        block: TextBlock,
        lineBoxes: List<NormalizedRect>,
        bgStats: BackgroundStats,
        useInpainting: Boolean
    ) {
        val imgW = fullBitmap.width
        val imgH = fullBitmap.height

        val roiLeft = (block.bounds.left.toInt() - ROI_CONTEXT_MARGIN).coerceIn(0, imgW - 1)
        val roiTop = (block.bounds.top.toInt() - ROI_CONTEXT_MARGIN).coerceIn(0, imgH - 1)
        val roiRight = (block.bounds.right.toInt() + ROI_CONTEXT_MARGIN).coerceIn(roiLeft + 1, imgW)
        val roiBottom = (block.bounds.bottom.toInt() + ROI_CONTEXT_MARGIN).coerceIn(roiTop + 1, imgH)

        val roiW = roiRight - roiLeft
        val roiH = roiBottom - roiTop
        if (roiW < 4 || roiH < 4) return

        val roiBitmap = Bitmap.createBitmap(fullBitmap, roiLeft, roiTop, roiW, roiH)
        val rgbaMat = Mat()
        val bgrMat = Mat()
        val grayMat = Mat()
        val maskMat = Mat.zeros(roiH, roiW, CvType.CV_8UC1)
        val resultBgr = Mat()
        val resultRgba = Mat()

        try {
            Utils.bitmapToMat(roiBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgrMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            buildPreciseTextMask(
                bgrMat = bgrMat,
                grayMat = grayMat,
                roiLeft = roiLeft,
                roiTop = roiTop,
                lineBoxes = lineBoxes,
                bgStats = bgStats,
                isComplex = useInpainting,
                outMask = maskMat
            )

            val kernelSize = if (useInpainting) 7.0 else 5.0
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize, kernelSize))
            try {
                Imgproc.dilate(maskMat, maskMat, kernel)
            } finally {
                kernel.release()
            }

            if (useInpainting) {
                Photo.inpaint(bgrMat, maskMat, resultBgr, INPAINT_RADIUS, Photo.INPAINT_TELEA)
            } else {
                bgrMat.copyTo(resultBgr)
                val fillScalar = Scalar(
                    bgStats.medianB.toDouble(),
                    bgStats.medianG.toDouble(),
                    bgStats.medianR.toDouble()
                )
                resultBgr.setTo(fillScalar, maskMat)
            }

            Imgproc.cvtColor(resultBgr, resultRgba, Imgproc.COLOR_BGR2RGBA)
            val cleanedRoiBitmap = Bitmap.createBitmap(roiW, roiH, Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(resultRgba, cleanedRoiBitmap)
                canvas.drawBitmap(cleanedRoiBitmap, roiLeft.toFloat(), roiTop.toFloat(), null)
            } finally {
                cleanedRoiBitmap.recycle()
            }
        } finally {
            roiBitmap.recycle()
            rgbaMat.release()
            bgrMat.release()
            grayMat.release()
            maskMat.release()
            resultBgr.release()
            resultRgba.release()
        }
    }

    private fun buildPreciseTextMask(
        bgrMat: Mat,
        grayMat: Mat,
        roiLeft: Int,
        roiTop: Int,
        lineBoxes: List<NormalizedRect>,
        bgStats: BackgroundStats,
        isComplex: Boolean,
        outMask: Mat
    ) {
        val roiW = bgrMat.cols()
        val roiH = bgrMat.rows()
        val pad = if (isComplex) 4 else 2

        val bgB = bgStats.medianB
        val bgG = bgStats.medianG
        val bgR = bgStats.medianR
        val colorThreshold = if (isComplex) 34.0 else 26.0

        val adaptiveMask = if (isComplex) {
            Mat().also { dst ->
                val thresholdType = if (bgStats.luminance >= 128.0) {
                    Imgproc.THRESH_BINARY_INV
                } else {
                    Imgproc.THRESH_BINARY
                }
                Imgproc.adaptiveThreshold(
                    grayMat,
                    dst,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    thresholdType,
                    15,
                    10.0
                )
            }
        } else {
            null
        }

        try {
            val pixelBgr = ByteArray(3)
            val maskVal = byteArrayOf(255.toByte())
            val adaptiveVal = ByteArray(1)

            for (box in lineBoxes) {
                val x1 = (box.left.toInt() - roiLeft - pad).coerceIn(0, roiW - 1)
                val y1 = (box.top.toInt() - roiTop - pad).coerceIn(0, roiH - 1)
                val x2 = (box.right.toInt() - roiLeft + pad).coerceIn(x1 + 1, roiW)
                val y2 = (box.bottom.toInt() - roiTop + pad).coerceIn(y1 + 1, roiH)

                var markedPixels = 0
                val totalPixels = (x2 - x1) * (y2 - y1)

                for (y in y1 until y2) {
                    for (x in x1 until x2) {
                        bgrMat.get(y, x, pixelBgr)
                        val b = pixelBgr[0].toInt() and 0xFF
                        val g = pixelBgr[1].toInt() and 0xFF
                        val r = pixelBgr[2].toInt() and 0xFF

                        val dist = sqrt(
                            ((r - bgR) * (r - bgR) +
                                (g - bgG) * (g - bgG) +
                                (b - bgB) * (b - bgB)).toDouble()
                        )

                        var isTextPixel = dist > colorThreshold
                        if (!isTextPixel && adaptiveMask != null) {
                            adaptiveMask.get(y, x, adaptiveVal)
                            if ((adaptiveVal[0].toInt() and 0xFF) > 0) {
                                isTextPixel = true
                            }
                        }

                        if (isTextPixel) {
                            outMask.put(y, x, maskVal)
                            markedPixels++
                        }
                    }
                }

                if (markedPixels < totalPixels * 0.04) {
                    val sub = outMask.submat(y1, y2, x1, x2)
                    sub.setTo(Scalar(255.0))
                    sub.release()
                }
            }
        } finally {
            adaptiveMask?.release()
        }
    }

    private fun analyzeBlockBackground(
        bitmap: Bitmap,
        boxes: List<NormalizedRect>
    ): BackgroundStats {
        val reds = mutableListOf<Int>()
        val greens = mutableListOf<Int>()
        val blues = mutableListOf<Int>()

        val imgW = bitmap.width
        val imgH = bitmap.height

        for (box in boxes) {
            val left = (box.left.toInt() - 4).coerceIn(0, imgW - 1)
            val right = (box.right.toInt() + 4).coerceIn(0, imgW - 1)
            val top = (box.top.toInt() - 4).coerceIn(0, imgH - 1)
            val bottom = (box.bottom.toInt() + 4).coerceIn(0, imgH - 1)

            val stepX = max(1, (right - left) / 14)
            var x = left
            while (x <= right) {
                addSample(bitmap.getPixel(x, top), reds, greens, blues)
                addSample(bitmap.getPixel(x, bottom), reds, greens, blues)
                x += stepX
            }

            val stepY = max(1, (bottom - top) / 8)
            var y = top
            while (y <= bottom) {
                addSample(bitmap.getPixel(left, y), reds, greens, blues)
                addSample(bitmap.getPixel(right, y), reds, greens, blues)
                y += stepY
            }
        }

        if (reds.isEmpty()) {
            return BackgroundStats(255, 255, 255, 0.0)
        }

        reds.sort()
        greens.sort()
        blues.sort()

        val mid = reds.size / 2
        val medR = reds[mid]
        val medG = greens[mid]
        val medB = blues[mid]

        val distances = reds.indices.map { i ->
            val dr = (reds[i] - medR).toDouble()
            val dg = (greens[i] - medG).toDouble()
            val db = (blues[i] - medB).toDouble()
            sqrt(dr * dr + dg * dg + db * db)
        }.sorted()

        val trimLimit = (distances.size * 0.85).toInt().coerceAtLeast(1)
        val avgDeviation = distances.take(trimLimit).average()

        return BackgroundStats(
            medianR = medR,
            medianG = medG,
            medianB = medB,
            stdDev = avgDeviation
        )
    }

    private fun addSample(
        pixel: Int,
        reds: MutableList<Int>,
        greens: MutableList<Int>,
        blues: MutableList<Int>
    ) {
        reds.add(Color.red(pixel))
        greens.add(Color.green(pixel))
        blues.add(Color.blue(pixel))
    }

    private fun cleanBlockFallbackCanvas(
        canvas: Canvas,
        lineBoxes: List<NormalizedRect>,
        fillColor: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        for (box in lineBoxes) {
            canvas.drawRoundRect(
                box.left - 3f,
                box.top - 2f,
                box.right + 3f,
                box.bottom + 2f,
                6f,
                6f,
                paint
            )
        }
    }
}
