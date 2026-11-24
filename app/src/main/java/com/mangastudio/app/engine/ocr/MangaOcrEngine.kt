package com.mangastudio.app.engine.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mangastudio.app.core.image.ManhwaImageHelper
import com.mangastudio.app.core.model.BubbleType
import com.mangastudio.app.core.model.NormalizedRect
import com.mangastudio.app.core.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface OcrEngine {
    suspend fun detectTextBlocks(
        imagePath: String,
        isRightToLeftReading: Boolean = false
    ): List<TextBlock>

    fun close()
}

private data class RawOcrLine(
    val text: String,
    val bounds: NormalizedRect,
    val sliceBitmapRef: Bitmap?,
    val sliceOffsetY: Int
)

class MlKitMangaOcrEngine : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val sfxKeywords = setOf(
        "BAM", "BOOM", "BANG", "CRACK", "CRASH", "SMASH", "SLASH", "SWOOSH", "WHOOSH",
        "THUD", "THUMP", "CLANK", "CLANG", "SNAP", "POP", "ZAP", "POW", "WHAM", "RUMBLE",
        "GRAB", "SHING", "FWOOSH", "BA-DUMP", "DUM", "TAP", "STEP", "GASP", "SIGH", "HUFF"
    )

    override suspend fun detectTextBlocks(
        imagePath: String,
        isRightToLeftReading: Boolean
    ): List<TextBlock> = withContext(Dispatchers.Default) {
        val slices = ManhwaImageHelper.createSmartSlices(imagePath)
        if (slices.isEmpty()) return@withContext emptyList()

        val allRawLines = mutableListOf<RawOcrLine>()
        val clusteredBlocks = mutableListOf<TextBlock>()

        try {
            for (slice in slices) {
                val inputImage = InputImage.fromBitmap(slice.bitmap, 0)
                val visionText = recognizer.process(inputImage).await()

                val sliceLines = mutableListOf<RawOcrLine>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        val cleanedText = normalizeOcrLine(line.text)
                        if (!isValidComicText(cleanedText, rect.width(), rect.height())) continue

                        val globalRect = NormalizedRect(
                            left = rect.left.toFloat(),
                            top = (rect.top + slice.offsetY).toFloat(),
                            right = rect.right.toFloat(),
                            bottom = (rect.bottom + slice.offsetY).toFloat()
                        )

                        val isDuplicate = allRawLines.any { existing ->
                            ManhwaImageHelper.rectsOverlapRatio(existing.bounds, globalRect) > 0.55f
                        }
                        if (!isDuplicate) {
                            val raw = RawOcrLine(
                                text = cleanedText,
                                bounds = globalRect,
                                sliceBitmapRef = slice.bitmap,
                                sliceOffsetY = slice.offsetY
                            )
                            sliceLines.add(raw)
                            allRawLines.add(raw)
                        }
                    }
                }

                clusteredBlocks.addAll(clusterLinesIntoBubbles(sliceLines, slice.bitmap, slice.offsetY))
            }
        } finally {
            slices.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }

        sortBlocksInReadingOrder(clusteredBlocks, isRightToLeftReading)
    }

    private fun clusterLinesIntoBubbles(
        lines: List<RawOcrLine>,
        sliceBitmap: Bitmap,
        sliceOffsetY: Int
    ): List<TextBlock> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedBy { it.bounds.top }
        val clusters = mutableListOf<MutableList<RawOcrLine>>()

        for (line in sorted) {
            var matchedCluster: MutableList<RawOcrLine>? = null
            var bestScore = Float.MAX_VALUE

            for (cluster in clusters) {
                if (canJoinCluster(line, cluster, sliceBitmap, sliceOffsetY)) {
                    val last = cluster.last()
                    val dist = abs(line.bounds.centerX - last.bounds.centerX) +
                        max(0f, line.bounds.top - last.bounds.bottom) * 2f
                    if (dist < bestScore) {
                        bestScore = dist
                        matchedCluster = cluster
                    }
                }
            }

            if (matchedCluster != null) {
                matchedCluster.add(line)
            } else {
                clusters.add(mutableListOf(line))
            }
        }

        return clusters.map { cluster ->
            val orderedLines = cluster.sortedWith(compareBy<RawOcrLine> { it.bounds.top }.thenBy { it.bounds.left })
            val unionBounds = NormalizedRect(
                left = orderedLines.minOf { it.bounds.left },
                top = orderedLines.minOf { it.bounds.top },
                right = orderedLines.maxOf { it.bounds.right },
                bottom = orderedLines.maxOf { it.bounds.bottom }
            )
            val mergedText = joinComicLines(orderedLines.map { it.text })
            val avgLineHeight = orderedLines.map { it.bounds.height }.average().toFloat()
            val bubbleType = classifyBubbleType(mergedText, unionBounds, avgLineHeight, orderedLines.size)

            TextBlock(
                bounds = unionBounds,
                lineBoxes = orderedLines.map { it.bounds },
                originalText = mergedText,
                type = bubbleType
            )
        }
    }

    private fun canJoinCluster(
        candidate: RawOcrLine,
        cluster: List<RawOcrLine>,
        sliceBitmap: Bitmap,
        sliceOffsetY: Int
    ): Boolean {
        val anchor = cluster.last()
        val avgHeight = cluster.map { it.bounds.height }.average().toFloat().coerceAtLeast(10f)

        val heightRatio = max(candidate.bounds.height, avgHeight) / min(candidate.bounds.height, avgHeight).coerceAtLeast(1f)
        if (heightRatio > 1.85f) return false

        val verticalGap = candidate.bounds.top - anchor.bounds.bottom
        if (verticalGap > avgHeight * 1.35f || verticalGap < -avgHeight * 0.4f) return false

        val clusterLeft = cluster.minOf { it.bounds.left }
        val clusterRight = cluster.maxOf { it.bounds.right }
        val clusterCenterX = (clusterLeft + clusterRight) / 2f
        val horizontalOverlap = min(clusterRight, candidate.bounds.right) - max(clusterLeft, candidate.bounds.left)
        val centerDistance = abs(candidate.bounds.centerX - clusterCenterX)
        val maxAllowedCenterShift = max(clusterRight - clusterLeft, candidate.bounds.width) * 0.55f

        if (horizontalOverlap <= -avgHeight * 0.6f && centerDistance > maxAllowedCenterShift) {
            return false
        }

        if (verticalGap > 4f) {
            val localAnchor = NormalizedRect(
                anchor.bounds.left,
                anchor.bounds.top - sliceOffsetY,
                anchor.bounds.right,
                anchor.bounds.bottom - sliceOffsetY
            )
            val localCandidate = NormalizedRect(
                candidate.bounds.left,
                candidate.bounds.top - sliceOffsetY,
                candidate.bounds.right,
                candidate.bounds.bottom - sliceOffsetY
            )
            if (ManhwaImageHelper.hasInkBarrierBetween(sliceBitmap, localAnchor, localCandidate)) {
                return false
            }
        }

        return true
    }

    private fun joinComicLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (i in lines.indices) {
            val current = lines[i].trim()
            if (current.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(current)
            } else if (sb.endsWith("-") && !sb.endsWith("--")) {
                sb.deleteCharAt(sb.length - 1)
                sb.append(current)
            } else {
                sb.append(' ').append(current)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun classifyBubbleType(
        text: String,
        bounds: NormalizedRect,
        avgLineHeight: Float,
        lineCount: Int
    ): BubbleType {
        val upper = text.uppercase()
        val words = upper.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (lineCount == 1 && words.size <= 2 &&
            (sfxKeywords.any { upper.contains(it) } || (avgLineHeight > 44f && !text.contains(" ")))
        ) {
            return BubbleType.SFX
        }

        if (text.contains("!!") || text.contains("?!") || text.contains("!?")) {
            return BubbleType.SHOUT
        }

        val aspectRatio = bounds.width / bounds.height.coerceAtLeast(1f)
        if (aspectRatio > 2.6f && words.size >= 6) {
            return BubbleType.NARRATION
        }

        return BubbleType.DIALOGUE
    }

    private fun normalizeOcrLine(raw: String): String {
        return raw
            .replace("|", "I")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("’", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isValidComicText(text: String, width: Int, height: Int): Boolean {
        if (text.isBlank()) return false
        if (width < 8 || height < 8) return false
        val hasLetterOrDigit = text.any { it.isLetterOrDigit() }
        if (!hasLetterOrDigit && text !in setOf("!", "?", "!!", "?!", "!?", "...")) {
            return false
        }
        if (text.length == 1 && text[0].isLetter() && text.uppercase() !in setOf("I", "A", "O")) {
            return false
        }
        return true
    }

    private fun sortBlocksInReadingOrder(
        blocks: List<TextBlock>,
        isRightToLeft: Boolean
    ): List<TextBlock> {
        return blocks.sortedWith { a, b ->
            val yDiff = a.bounds.top - b.bounds.top
            if (abs(yDiff) > 110f) {
                yDiff.compareTo(0f)
            } else {
                if (isRightToLeft) {
                    b.bounds.centerX.compareTo(a.bounds.centerX)
                } else {
                    a.bounds.centerX.compareTo(b.bounds.centerX)
                }
            }
        }
    }

    override fun close() {
        recognizer.close()
    }
}
