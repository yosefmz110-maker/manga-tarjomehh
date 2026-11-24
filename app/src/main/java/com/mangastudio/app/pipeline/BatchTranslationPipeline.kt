package com.mangastudio.app.pipeline

import com.mangastudio.app.core.model.MangaPage
import com.mangastudio.app.core.model.MangaProject
import com.mangastudio.app.core.model.PageStatus
import com.mangastudio.app.core.model.StudioSettings
import com.mangastudio.app.core.model.TextBlock
import com.mangastudio.app.core.storage.ProjectRepository
import com.mangastudio.app.engine.inpaint.InpaintEngine
import com.mangastudio.app.engine.ocr.OcrEngine
import com.mangastudio.app.engine.translate.TranslatorFactory
import com.mangastudio.app.engine.typeset.PersianTypesetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File

data class PipelineProgress(
    val isRunning: Boolean = false,
    val totalPages: Int = 0,
    val completedPages: Int = 0,
    val failedPages: Int = 0,
    val activeStages: Map<String, String> = emptyMap(),
    val logs: List<String> = emptyList()
) {
    val fraction: Float
        get() = if (totalPages == 0) 0f else (completedPages.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
}

class BatchTranslationPipeline(
    private val repository: ProjectRepository,
    private val ocrEngine: OcrEngine,
    private val inpaintEngine: InpaintEngine,
    private val typesetter: PersianTypesetter
) {
    private val _progress = MutableStateFlow(PipelineProgress())
    val progress: StateFlow<PipelineProgress> = _progress.asStateFlow()

    private val saveMutex = Mutex()
    private var activeJob: Job? = null

    fun startOrResume(
        scope: CoroutineScope,
        projectId: String,
        settings: StudioSettings,
        isRightToLeftManga: Boolean = false,
        onProjectUpdated: (MangaProject) -> Unit
    ) {
        if (activeJob?.isActive == true) return

        activeJob = scope.launch(Dispatchers.Default) {
            val initialProject = repository.loadProject(projectId) ?: return@launch
            val pages = initialProject.pages.sortedBy { it.index }
            val alreadyDoneCount = pages.count { isFullyRendered(it) }

            _progress.value = PipelineProgress(
                isRunning = true,
                totalPages = pages.size,
                completedPages = alreadyDoneCount,
                failedPages = 0,
                logs = listOf("شروع پردازش ${pages.size} صفحه (تکمیل‌شده قبلی: $alreadyDoneCount)")
            )

            val translator = TranslatorFactory.create(settings)
            val concurrency = settings.parallelWorkers.coerceIn(1, 4)
            val semaphore = Semaphore(concurrency)
            val cleanedDir = repository.getCleanedDir(projectId)
            val renderedDir = repository.getRenderedDir(projectId)

            try {
                pages.map { page ->
                    async {
                        if (isFullyRendered(page)) {
                            return@async
                        }
                        semaphore.withPermit {
                            processSinglePageWithCheckpoint(
                                projectId = projectId,
                                initialPage = page,
                                cleanedDir = cleanedDir,
                                renderedDir = renderedDir,
                                settings = settings,
                                isRightToLeftManga = isRightToLeftManga,
                                translateFn = { blocks -> translator.translateBlocks(blocks, settings) },
                                onProjectUpdated = onProjectUpdated
                            )
                        }
                    }
                }.awaitAll()
            } catch (_: CancellationException) {
                appendLog("پردازش توسط کاربر متوقف شد. تمام مراحل تکمیل‌شده ذخیره شده‌اند.")
            } finally {
                _progress.update { it.copy(isRunning = false, activeStages = emptyMap()) }
            }
        }
    }

    fun pauseOrCancel() {
        activeJob?.cancel()
        activeJob = null
    }

    suspend fun rerenderEditedPage(
        projectId: String,
        page: MangaPage,
        updatedBlocks: List<TextBlock>,
        settings: StudioSettings
    ): MangaPage {
        val baseCleanedPath = page.cleanedPath?.takeIf { File(it).exists() } ?: page.originalPath
        val renderedOut = File(repository.getRenderedDir(projectId), "${page.id}_rendered.jpg").absolutePath

        val finalPath = typesetter.renderPage(
            cleanedImagePath = baseCleanedPath,
            outputRenderedPath = renderedOut,
            blocks = updatedBlocks,
            settings = settings
        )

        val updatedPage = page.copy(
            blocks = updatedBlocks,
            renderedPath = finalPath,
            status = PageStatus.RENDERED,
            errorMessage = null
        )
        saveMutex.withLock {
            repository.updatePage(projectId, updatedPage)
        }
        return updatedPage
    }

    private suspend fun processSinglePageWithCheckpoint(
        projectId: String,
        initialPage: MangaPage,
        cleanedDir: File,
        renderedDir: File,
        settings: StudioSettings,
        isRightToLeftManga: Boolean,
        translateFn: suspend (List<TextBlock>) -> List<String>,
        onProjectUpdated: (MangaProject) -> Unit
    ) {
        var page = initialPage
        val pageLabel = "صفحه ${page.index + 1}"

        try {
            val hasOcr = page.status in setOf(PageStatus.OCR_DONE, PageStatus.CLEANED, PageStatus.TRANSLATED, PageStatus.RENDERED) ||
                (page.status == PageStatus.ERROR && page.blocks.isNotEmpty())

            if (!hasOcr) {
                setPageStage(page.id, "$pageLabel: تشخیص متن (OCR)...")
                val detectedBlocks = ocrEngine.detectTextBlocks(page.originalPath, isRightToLeftManga)
                page = page.copy(
                    blocks = detectedBlocks,
                    status = PageStatus.OCR_DONE,
                    errorMessage = null
                )
                persistPage(projectId, page, onProjectUpdated)
            }

            if (page.blocks.isEmpty()) {
                page = page.copy(
                    cleanedPath = page.originalPath,
                    renderedPath = page.originalPath,
                    status = PageStatus.RENDERED,
                    errorMessage = null
                )
                persistPage(projectId, page, onProjectUpdated)
                markCompleted(page.id, "$pageLabel: بدون متن (تکمیل شد)")
                return
            }

            val cleanedFileExists = page.cleanedPath?.let { File(it).exists() } == true
            if (!cleanedFileExists || page.status == PageStatus.OCR_DONE) {
                setPageStage(page.id, "$pageLabel: پاک‌سازی و بازسازی پس‌زمینه...")
                val cleanedOutPath = File(cleanedDir, "${page.id}_cleaned.png").absolutePath
                val inpaintResult = inpaintEngine.cleanPage(
                    originalImagePath = page.originalPath,
                    outputCleanedPath = cleanedOutPath,
                    blocks = page.blocks,
                    mode = settings.inpaintMode
                )
                page = page.copy(
                    cleanedPath = inpaintResult.cleanedImagePath,
                    blocks = inpaintResult.updatedBlocks,
                    status = PageStatus.CLEANED,
                    errorMessage = null
                )
                persistPage(projectId, page, onProjectUpdated)
            }

            val needsTranslation = page.blocks.any { it.translatedText.isBlank() }
            if (needsTranslation || page.status == PageStatus.CLEANED) {
                setPageStage(page.id, "$pageLabel: ترجمه ${page.blocks.size} حباب...")
                val toTranslateIndices = page.blocks.indices.filter {
                    !page.blocks[it].isManuallyEdited && page.blocks[it].translatedText.isBlank()
                }
                if (toTranslateIndices.isNotEmpty()) {
                    val subset = toTranslateIndices.map { page.blocks[it] }
                    val translations = translateFn(subset)
                    val updatedBlocks = page.blocks.toMutableList()
                    toTranslateIndices.forEachIndexed { localIdx, globalIdx ->
                        updatedBlocks[globalIdx] = updatedBlocks[globalIdx].copy(
                            translatedText = translations.getOrElse(localIdx) { "" }
                        )
                    }
                    page = page.copy(
                        blocks = updatedBlocks,
                        status = PageStatus.TRANSLATED,
                        errorMessage = null
                    )
                    persistPage(projectId, page, onProjectUpdated)
                }
            }

            setPageStage(page.id, "$pageLabel: جاگذاری متن فارسی...")
            val renderedOutPath = File(renderedDir, "${page.id}_rendered.jpg").absolutePath
            val finalPath = typesetter.renderPage(
                cleanedImagePath = page.cleanedPath ?: page.originalPath,
                outputRenderedPath = renderedOutPath,
                blocks = page.blocks,
                settings = settings
            )

            page = page.copy(
                renderedPath = finalPath,
                status = PageStatus.RENDERED,
                errorMessage = null
            )
            persistPage(projectId, page, onProjectUpdated)
            markCompleted(page.id, "$pageLabel: تکمیل شد (${page.blocks.size} حباب)")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val errMsg = e.message ?: "خطای نامشخص در پردازش"
            page = page.copy(status = PageStatus.ERROR, errorMessage = errMsg)
            persistPage(projectId, page, onProjectUpdated)
            _progress.update { state ->
                state.copy(
                    failedPages = state.failedPages + 1,
                    activeStages = state.activeStages - page.id,
                    logs = (state.logs + "خطا در $pageLabel: $errMsg").takeLast(60)
                )
            }
        }
    }

    private fun isFullyRendered(page: MangaPage): Boolean {
        return page.status == PageStatus.RENDERED &&
            page.renderedPath?.let { File(it).exists() } == true
    }

    private suspend fun persistPage(
        projectId: String,
        page: MangaPage,
        onProjectUpdated: (MangaProject) -> Unit
    ) {
        val updatedProject = saveMutex.withLock {
            repository.updatePage(projectId, page)
        }
        if (updatedProject != null) {
            onProjectUpdated(updatedProject)
        }
    }

    private fun setPageStage(pageId: String, stage: String) {
        _progress.update { it.copy(activeStages = it.activeStages + (pageId to stage)) }
    }

    private fun markCompleted(pageId: String, logMsg: String) {
        _progress.update { state ->
            state.copy(
                completedPages = state.completedPages + 1,
                activeStages = state.activeStages - pageId,
                logs = (state.logs + logMsg).takeLast(60)
            )
        }
    }

    private fun appendLog(msg: String) {
        _progress.update { it.copy(logs = (it.logs + msg).takeLast(60)) }
    }
}
