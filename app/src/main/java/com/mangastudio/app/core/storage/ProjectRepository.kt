package com.mangastudio.app.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import com.google.gson.GsonBuilder
import com.mangastudio.app.core.image.ManhwaImageHelper
import com.mangastudio.app.core.model.MangaPage
import com.mangastudio.app.core.model.MangaProject
import com.mangastudio.app.core.model.PageStatus
import com.mangastudio.app.core.model.StudioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class ProjectRepository(private val context: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val projectsRoot: File = File(context.filesDir, "projects").apply { mkdirs() }
    private val settingsFile: File = File(context.filesDir, "studio_settings.json")

    private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "webp")

    suspend fun loadSettings(): StudioSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) return@withContext StudioSettings()
        try {
            gson.fromJson(settingsFile.readText(), StudioSettings::class.java) ?: StudioSettings()
        } catch (_: Exception) {
            StudioSettings()
        }
    }

    suspend fun saveSettings(settings: StudioSettings) = withContext(Dispatchers.IO) {
        settingsFile.writeText(gson.toJson(settings))
    }

    fun getProjectDir(projectId: String): File = File(projectsRoot, projectId).apply { mkdirs() }
    fun getOriginalsDir(projectId: String): File = File(getProjectDir(projectId), "originals").apply { mkdirs() }
    fun getCleanedDir(projectId: String): File = File(getProjectDir(projectId), "cleaned").apply { mkdirs() }
    fun getRenderedDir(projectId: String): File = File(getProjectDir(projectId), "rendered").apply { mkdirs() }

    suspend fun createProject(name: String): MangaProject = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifEmpty { "پروژه جدید" }
        val project = MangaProject(name = cleanName)
        saveProject(project)
        project
    }

    suspend fun saveProject(project: MangaProject) = withContext(Dispatchers.IO) {
        val dir = getProjectDir(project.id)
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        val tempFile = File(dir, "project.json.tmp")
        val targetFile = File(dir, "project.json")
        tempFile.writeText(gson.toJson(updated))
        if (!tempFile.renameTo(targetFile)) {
            targetFile.writeText(tempFile.readText())
            tempFile.delete()
        }
    }

    suspend fun loadProject(projectId: String): MangaProject? = withContext(Dispatchers.IO) {
        val file = File(getProjectDir(projectId), "project.json")
        if (!file.exists()) return@withContext null
        try {
            gson.fromJson(file.readText(), MangaProject::class.java)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listProjects(): List<MangaProject> = withContext(Dispatchers.IO) {
        projectsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val jsonFile = File(dir, "project.json")
                if (!jsonFile.exists()) return@mapNotNull null
                try {
                    gson.fromJson(jsonFile.readText(), MangaProject::class.java)
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        getProjectDir(projectId).deleteRecursively()
    }

    suspend fun updatePage(projectId: String, updatedPage: MangaPage): MangaProject? =
        withContext(Dispatchers.IO) {
            val project = loadProject(projectId) ?: return@withContext null
            val newPages = project.pages.map { if (it.id == updatedPage.id) updatedPage else it }
            val updatedProject = project.copy(pages = newPages)
            saveProject(updatedProject)
            updatedProject
        }

    suspend fun reorderPages(projectId: String, orderedIds: List<String>): MangaProject? =
        withContext(Dispatchers.IO) {
            val project = loadProject(projectId) ?: return@withContext null
            val pageMap = project.pages.associateBy { it.id }
            val reordered = orderedIds.mapIndexedNotNull { idx, id ->
                pageMap[id]?.copy(index = idx)
            }
            val updated = project.copy(pages = reordered)
            saveProject(updated)
            updated
        }

    suspend fun importImageUris(
        projectId: String,
        uris: List<Uri>,
        preserveSelectionOrder: Boolean = true
    ): MangaProject = withContext(Dispatchers.IO) {
        val project = loadProject(projectId) ?: createProject("پروژه جدید")
        val originalsDir = getOriginalsDir(project.id)

        val entries = uris.mapIndexedNotNull { idx, uri ->
            val name = resolveFileName(uri) ?: "page_${idx + 1}.jpg"
            val ext = name.substringAfterLast('.', "jpg").lowercase(Locale.US)
            if (ext !in supportedImageExtensions && !isImageMimeType(uri)) return@mapIndexedNotNull null
            Pair(name, uri)
        }.let { list ->
            if (preserveSelectionOrder) list
            else list.sortedWith { a, b -> NaturalOrderComparator.compare(a.first, b.first) }
        }

        val existingCount = project.pages.size
        val addedPages = mutableListOf<MangaPage>()

        entries.forEachIndexed { offset, (displayName, uri) ->
            val pageIndex = existingCount + offset
            val ext = displayName.substringAfterLast('.', "jpg")
                .lowercase(Locale.US)
                .takeIf { it in supportedImageExtensions } ?: "jpg"
            val destFile = File(originalsDir, String.format(Locale.US, "%04d_%s.%s", pageIndex, sanitizeBaseName(displayName), ext))

            context.contentResolver.openInputStream(uri)?.use { input ->
                copyStreamToFile(input, destFile)
            }
            if (destFile.exists() && destFile.length() > 0) {
                val (w, h) = ManhwaImageHelper.getImageDimensions(destFile.absolutePath)
                addedPages.add(
                    MangaPage(
                        index = pageIndex,
                        fileName = displayName,
                        originalPath = destFile.absolutePath,
                        width = w,
                        height = h,
                        status = PageStatus.PENDING
                    )
                )
            }
        }

        val updated = project.copy(pages = project.pages + addedPages)
        saveProject(updated)
        updated
    }

    suspend fun importFolderUri(
        projectId: String,
        treeUri: Uri
    ): MangaProject = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("دسترسی به پوشه انتخاب‌شده ممکن نیست.")

        val imageFiles = mutableListOf<DocumentFile>()
        collectImagesFromFolder(rootDoc, imageFiles)

        val sortedUris = imageFiles
            .sortedWith { a, b -> NaturalOrderComparator.compare(a.name.orEmpty(), b.name.orEmpty()) }
            .map { it.uri }

        importImageUris(projectId, sortedUris, preserveSelectionOrder = true)
    }

    private fun collectImagesFromFolder(dir: DocumentFile, out: MutableList<DocumentFile>) {
        val children = dir.listFiles()
            .sortedWith { a, b -> NaturalOrderComparator.compare(a.name.orEmpty(), b.name.orEmpty()) }
        for (doc in children) {
            if (doc.isDirectory) {
                collectImagesFromFolder(doc, out)
            } else if (doc.isFile) {
                val ext = doc.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in supportedImageExtensions) {
                    out.add(doc)
                }
            }
        }
    }

    suspend fun importArchiveUri(
        projectId: String,
        archiveUri: Uri
    ): MangaProject = withContext(Dispatchers.IO) {
        val project = loadProject(projectId) ?: createProject("پروژه آرشیو")
        val fileName = resolveFileName(archiveUri)?.lowercase(Locale.US) ?: ""
        val tempDir = File(context.cacheDir, "archive_extract_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            val tempArchive = File(tempDir, "input_archive")
            context.contentResolver.openInputStream(archiveUri)?.use { input ->
                copyStreamToFile(input, tempArchive)
            } ?: throw IllegalStateException("خطا در خواندن فایل فشرده.")

            val isRar = fileName.endsWith(".rar") || fileName.endsWith(".cbr") || hasRarSignature(tempArchive)
            val extractedFiles = if (isRar) {
                extractRarImages(tempArchive, File(tempDir, "out"))
            } else {
                extractZipImages(tempArchive, File(tempDir, "out"))
            }

            val sortedExtracted = extractedFiles.sortedWith { a, b ->
                NaturalOrderComparator.compare(a.relativeTo(tempDir).path, b.relativeTo(tempDir).path)
            }

            val originalsDir = getOriginalsDir(project.id)
            val existingCount = project.pages.size
            val addedPages = mutableListOf<MangaPage>()

            sortedExtracted.forEachIndexed { offset, extractedFile ->
                val pageIndex = existingCount + offset
                val ext = extractedFile.extension.lowercase(Locale.US)
                val destFile = File(
                    originalsDir,
                    String.format(Locale.US, "%04d_%s.%s", pageIndex, sanitizeBaseName(extractedFile.name), ext)
                )
                extractedFile.copyTo(destFile, overwrite = true)
                val (w, h) = ManhwaImageHelper.getImageDimensions(destFile.absolutePath)
                if (w > 0 && h > 0) {
                    addedPages.add(
                        MangaPage(
                            index = pageIndex,
                            fileName = extractedFile.name,
                            originalPath = destFile.absolutePath,
                            width = w,
                            height = h,
                            status = PageStatus.PENDING
                        )
                    )
                }
            }

            val updated = project.copy(pages = project.pages + addedPages)
            saveProject(updated)
            updated
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun extractZipImages(zipFile: File, outDir: File): List<File> {
        outDir.mkdirs()
        val result = mutableListOf<File>()
        val canonicalOut = outDir.canonicalPath

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.name.startsWith("__MACOSX")) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext in supportedImageExtensions) {
                        val target = File(outDir, entry.name)
                        if (target.canonicalPath.startsWith(canonicalOut)) {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { fos ->
                                zis.copyTo(fos)
                            }
                            result.add(target)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    private fun extractRarImages(rarFile: File, outDir: File): List<File> {
        outDir.mkdirs()
        val result = mutableListOf<File>()
        val canonicalOut = outDir.canonicalPath

        Archive(rarFile).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                if (!header.isDirectory) {
                    val cleanEntryPath = header.fileName.replace('\\', '/')
                    val ext = cleanEntryPath.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext in supportedImageExtensions && !cleanEntryPath.startsWith("__MACOSX")) {
                        val target = File(outDir, cleanEntryPath)
                        if (target.canonicalPath.startsWith(canonicalOut)) {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { fos ->
                                archive.extractFile(header, fos)
                            }
                            result.add(target)
                        }
                    }
                }
                header = archive.nextFileHeader()
            }
        }
        return result
    }

    private fun hasRarSignature(file: File): Boolean {
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        return header[0] == 'R'.code.toByte() &&
            header[1] == 'a'.code.toByte() &&
            header[2] == 'r'.code.toByte() &&
            header[3] == '!'.code.toByte()
    }

    private fun resolveFileName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun isImageMimeType(uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri) ?: return false
        return mime.startsWith("image/")
    }

    private fun sanitizeBaseName(name: String): String {
        val base = name.substringBeforeLast('.')
        return base.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(32).ifEmpty { "page" }
    }

    private fun copyStreamToFile(input: InputStream, dest: File) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out ->
            input.copyTo(out, bufferSize = 32 * 1024)
        }
    }
}

object NaturalOrderComparator : Comparator<String> {
    private val chunkRegex = Regex("(\\d+|\\D+)")

    override fun compare(a: String, b: String): Int {
        val chunksA = chunkRegex.findAll(a.lowercase(Locale.US)).map { it.value }.toList()
        val chunksB = chunkRegex.findAll(b.lowercase(Locale.US)).map { it.value }.toList()
        val limit = minOf(chunksA.size, chunksB.size)

        for (i in 0 until limit) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            if (ca == cb) continue

            val numA = ca.toBigIntegerOrNull()
            val numB = cb.toBigIntegerOrNull()
            if (numA != null && numB != null) {
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.compareTo(cb)
                if (cmp != 0) return cmp
            }
        }
        return chunksA.size.compareTo(chunksB.size)
    }
}
