package com.mangastudio.app.engine.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mangastudio.app.core.model.MangaPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class PdfExportOptions(
    val outputFileName: String,
    val jpegQuality: Int = 96,
    val maxWidthPx: Int = 0,
    val onlySelectedPages: Boolean = false
)

data class ExportResult(
    val fileName: String,
    val localFile: File?,
    val publicUri: Uri?,
    val exportedPageCount: Int
)

class HighQualityPdfExporter(private val context: Context) {

    suspend fun exportToPdf(
        pages: List<MangaPage>,
        options: PdfExportOptions,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportResult = withContext(Dispatchers.IO) {
        val targetPages = pages
            .sortedBy { it.index }
            .filter { !options.onlySelectedPages || it.selectedForExport }
            .filter { page ->
                val path = page.renderedPath ?: page.cleanedPath ?: page.originalPath
                File(path).exists()
            }

        require(targetPages.isNotEmpty()) { "هیچ صفحه معتبری برای ساخت PDF انتخاب نشده است." }

        val safeName = sanitizeFileName(options.outputFileName).let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }

        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MangaStudio")
            .apply { mkdirs() }
        val localPdfFile = File(exportDir, safeName)

        BufferedOutputStream(FileOutputStream(localPdfFile), 64 * 1024).use { bos ->
            writeDirectJpegPdf(
                output = bos,
                pages = targetPages,
                quality = options.jpegQuality.coerceIn(75, 100),
                maxWidthPx = options.maxWidthPx,
                onProgress = onProgress
            )
        }

        val publicUri = copyPdfToPublicDownloads(localPdfFile, safeName)

        ExportResult(
            fileName = safeName,
            localFile = localPdfFile,
            publicUri = publicUri,
            exportedPageCount = targetPages.size
        )
    }

    suspend fun exportImagesSeparately(
        projectName: String,
        pages: List<MangaPage>,
        onlySelectedPages: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val targetPages = pages
            .sortedBy { it.index }
            .filter { !onlySelectedPages || it.selectedForExport }

        val safeFolder = sanitizeFileName(projectName)
        var savedCount = 0

        targetPages.forEachIndexed { idx, page ->
            val srcPath = page.renderedPath ?: page.cleanedPath ?: page.originalPath
            val srcFile = File(srcPath)
            if (!srcFile.exists()) return@forEachIndexed

            val isPng = srcFile.extension.equals("png", ignoreCase = true)
            val ext = if (isPng) "png" else "jpg"
            val mime = if (isPng) "image/png" else "image/jpeg"
            val outName = String.format(Locale.US, "%03d_%s.%s", idx + 1, sanitizeFileName(page.fileName.substringBeforeLast('.')), ext)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MangaStudio/$safeFolder")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    savedCount++
                }
            } else {
                val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MangaStudio/$safeFolder")
                    .apply { mkdirs() }
                srcFile.copyTo(File(outDir, outName), overwrite = true)
                savedCount++
            }
            onProgress(idx + 1, targetPages.size)
        }
        savedCount
    }

    private fun writeDirectJpegPdf(
        output: OutputStream,
        pages: List<MangaPage>,
        quality: Int,
        maxWidthPx: Int,
        onProgress: (Int, Int) -> Unit
    ) {
        val offsets = mutableMapOf<Int, Long>()
        var bytePos = 0L

        fun writeAscii(s: String) {
            val bytes = s.toByteArray(StandardCharsets.US_ASCII)
            output.write(bytes)
            bytePos += bytes.size
        }

        fun writeBytes(b: ByteArray) {
            output.write(b)
            bytePos += b.size
        }

        writeAscii("%PDF-1.4\n")
        writeBytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        val pageCount = pages.size
        val pageObjIds = mutableListOf<Int>()

        pages.forEachIndexed { idx, page ->
            val srcPath = page.renderedPath ?: page.cleanedPath ?: page.originalPath
            val (jpegBytes, imgW, imgH) = loadJpegStreamForPdf(srcPath, quality, maxWidthPx)

            val rawScale = 595.0 / imgW.coerceAtLeast(1).toDouble()
            val maxHeightCapScale = if (imgH * rawScale > 14000.0) 14000.0 / imgH.toDouble() else rawScale
            val pageW = String.format(Locale.US, "%.2f", imgW * maxHeightCapScale)
            val pageH = String.format(Locale.US, "%.2f", imgH * maxHeightCapScale)

            val imgObjId = 3 + idx * 3
            val contentObjId = 4 + idx * 3
            val pageObjId = 5 + idx * 3
            pageObjIds.add(pageObjId)

            offsets[imgObjId] = bytePos
            writeAscii(
                "$imgObjId 0 obj\n" +
                    "<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegBytes.size} >>\n" +
                    "stream\n"
            )
            writeBytes(jpegBytes)
            writeAscii("\nendstream\nendobj\n")

            val drawCmd = "q\n$pageW 0 0 $pageH 0 0 cm\n/Im0 Do\nQ\n"
            offsets[contentObjId] = bytePos
            writeAscii(
                "$contentObjId 0 obj\n" +
                    "<< /Length ${drawCmd.length} >>\n" +
                    "stream\n$drawCmd" +
                    "endstream\nendobj\n"
            )

            offsets[pageObjId] = bytePos
            writeAscii(
                "$pageObjId 0 obj\n" +
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] " +
                    "/Resources << /XObject << /Im0 $imgObjId 0 R >> >> /Contents $contentObjId 0 R >>\n" +
                    "endobj\n"
            )

            onProgress(idx + 1, pageCount)
        }

        offsets[2] = bytePos
        val kidsStr = pageObjIds.joinToString(" ") { "$it 0 R" }
        writeAscii("2 0 obj\n<< /Type /Pages /Kids [$kidsStr] /Count ${pageObjIds.size} >>\nendobj\n")

        offsets[1] = bytePos
        writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val xrefPos = bytePos
        val totalObjects = 2 + pageCount * 3
        writeAscii("xref\n0 ${totalObjects + 1}\n")
        writeAscii("0000000000 65535 f \n")
        for (objId in 1..totalObjects) {
            val offset = offsets[objId] ?: 0L
            writeAscii(String.format(Locale.US, "%010d 00000 n \n", offset))
        }

        writeAscii(
            "trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\n" +
                "startxref\n$xrefPos\n%%EOF"
        )
        output.flush()
    }

    private fun loadJpegStreamForPdf(
        path: String,
        quality: Int,
        maxWidthPx: Int
    ): Triple<ByteArray, Int, Int> {
        val file = File(path)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val origW = boundsOpts.outWidth.coerceAtLeast(1)
        val origH = boundsOpts.outHeight.coerceAtLeast(1)

        val isRawJpeg = boundsOpts.outMimeType.equals("image/jpeg", ignoreCase = true)
        if (isRawJpeg && (maxWidthPx <= 0 || origW <= maxWidthPx) && quality >= 95) {
            return Triple(file.readBytes(), origW, origH)
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(path, decodeOpts)
            ?: throw IllegalStateException("خطا در خواندن تصویر برای خروجی PDF: $path")

        try {
            if (maxWidthPx in 400 until bitmap.width) {
                val targetH = ((bitmap.height.toDouble() / bitmap.width.toDouble()) * maxWidthPx).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, maxWidthPx, targetH, true)
                bitmap.recycle()
                bitmap = scaled
            }
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            return Triple(baos.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun copyPdfToPublicDownloads(sourceFile: File, displayName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MangaStudio")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                uri
            } else {
                Uri.fromFile(sourceFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifEmpty { "MangaStudio_Export" }
    }
}
