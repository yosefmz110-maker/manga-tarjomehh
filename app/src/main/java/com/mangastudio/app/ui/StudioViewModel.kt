package com.mangastudio.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangastudio.app.core.model.BubbleType
import com.mangastudio.app.core.model.GlossaryEntry
import com.mangastudio.app.core.model.MangaPage
import com.mangastudio.app.core.model.MangaProject
import com.mangastudio.app.core.model.NormalizedRect
import com.mangastudio.app.core.model.PageStatus
import com.mangastudio.app.core.model.StudioSettings
import com.mangastudio.app.core.model.TextBlock
import com.mangastudio.app.core.storage.ProjectRepository
import com.mangastudio.app.engine.inpaint.OpenCvHybridInpaintEngine
import com.mangastudio.app.engine.ocr.MlKitMangaOcrEngine
import com.mangastudio.app.engine.pdf.ExportResult
import com.mangastudio.app.engine.pdf.HighQualityPdfExporter
import com.mangastudio.app.engine.pdf.PdfExportOptions
import com.mangastudio.app.engine.typeset.PersianTypesetter
import com.mangastudio.app.pipeline.BatchTranslationPipeline
import com.mangastudio.app.pipeline.PipelineProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExportUiState(
    val isExporting: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val lastPdfResult: ExportResult? = null,
    val lastImagesSavedCount: Int? = null
)

data class StudioUiState(
    val projects: List<MangaProject> = emptyList(),
    val activeProject: MangaProject? = null,
    val activePageId: String? = null,
    val settings: StudioSettings = StudioSettings(),
    val availableFonts: List<String> = emptyList(),
    val isImporting: Boolean = false,
    val isRerendering: Boolean = false,
    val isRightToLeftManga: Boolean = false,
    val renderRevision: Long = 0L,
    val exportState: ExportUiState = ExportUiState(),
    val message: String? = null
) {
    val activePage: MangaPage?
        get() = activeProject?.pages?.find { it.id == activePageId }
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val ocrEngine = MlKitMangaOcrEngine()
    private val inpaintEngine = OpenCvHybridInpaintEngine()
    private val typesetter = PersianTypesetter(application)
    private val pdfExporter = HighQualityPdfExporter(application)
    private val pipeline = BatchTranslationPipeline(
        repository = repository,
        ocrEngine = ocrEngine,
        inpaintEngine = inpaintEngine,
        typesetter = typesetter
    )

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    val pipelineProgress: StateFlow<PipelineProgress> = pipeline.progress

    private val undoMap = mutableMapOf<String, MutableList<List<TextBlock>>>()
    private val redoMap = mutableMapOf<String, MutableList<List<TextBlock>>>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init {
        viewModelScope.launch {
            val loadedSettings = repository.loadSettings()
            val loadedProjects = repository.listProjects()
            val fonts = typesetter.listAvailableFonts()
            _uiState.update {
                it.copy(
                    projects = loadedProjects,
                    settings = loadedSettings,
                    availableFonts = fonts
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun showMessage(msg: String) {
        _uiState.update { it.copy(message = msg) }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            val list = repository.listProjects()
            _uiState.update { it.copy(projects = list) }
        }
    }

    fun createAndOpenProject(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val project = repository.createProject(name)
            val list = repository.listProjects()
            _uiState.update {
                it.copy(
                    projects = list,
                    activeProject = project,
                    activePageId = null,
                    exportState = ExportUiState()
                )
            }
            onCreated(project.id)
        }
    }

    fun openProject(projectId: String) {
        viewModelScope.launch {
            val project = repository.loadProject(projectId)
            if (project != null) {
                _uiState.update {
                    it.copy(
                        activeProject = project,
                        activePageId = project.pages.firstOrNull()?.id,
                        exportState = ExportUiState()
                    )
                }
            } else {
                showMessage("پروژه یافت نشد.")
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            if (_uiState.value.activeProject?.id == projectId) {
                pipeline.pauseOrCancel()
            }
            repository.deleteProject(projectId)
            val list = repository.listProjects()
            _uiState.update {
                it.copy(
                    projects = list,
                    activeProject = if (it.activeProject?.id == projectId) null else it.activeProject
                )
            }
            showMessage("پروژه حذف شد.")
        }
    }

    fun selectActivePage(pageId: String) {
        _uiState.update { it.copy(activePageId = pageId) }
        syncUndoRedoFlags(pageId)
    }

    fun setRightToLeftManga(rtl: Boolean) {
        _uiState.update { it.copy(isRightToLeftManga = rtl) }
    }

    fun importImages(uris: List<Uri>, preserveSelectionOrder: Boolean = true) {
        val projectId = _uiState.value.activeProject?.id ?: return
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            try {
                val updated = repository.importImageUris(projectId, uris, preserveSelectionOrder)
                applyProjectUpdate(updated)
                showMessage("${uris.size} تصویر با حفظ ترتیب اضافه شد.")
            } catch (e: Exception) {
                showMessage("خطا در وارد کردن تصاویر: ${e.message}")
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun importFolder(treeUri: Uri) {
        val projectId = _uiState.value.activeProject?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            try {
                val prevCount = _uiState.value.activeProject?.pages?.size ?: 0
                val updated = repository.importFolderUri(projectId, treeUri)
                applyProjectUpdate(updated)
                val added = updated.pages.size - prevCount
                showMessage("$added صفحه از پوشه انتخاب‌شده وارد شد.")
            } catch (e: Exception) {
                showMessage("خطا در خواندن پوشه: ${e.message}")
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun importArchive(archiveUri: Uri) {
        val projectId = _uiState.value.activeProject?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            try {
                val prevCount = _uiState.value.activeProject?.pages?.size ?: 0
                val updated = repository.importArchiveUri(projectId, archiveUri)
                applyProjectUpdate(updated)
                val added = updated.pages.size - prevCount
                showMessage("$added صفحه از فایل فشرده استخراج و وارد شد.")
            } catch (e: Exception) {
                showMessage("خطا در استخراج فایل ZIP/RAR: ${e.message}")
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun movePage(pageId: String, moveUp: Boolean) {
        val project = _uiState.value.activeProject ?: return
        val pages = project.pages.sortedBy { it.index }.toMutableList()
        val idx = pages.indexOfFirst { it.id == pageId }
        if (idx == -1) return
        val targetIdx = if (moveUp) idx - 1 else idx + 1
        if (targetIdx !in pages.indices) return

        val item = pages.removeAt(idx)
        pages.add(targetIdx, item)

        viewModelScope.launch {
            val updated = repository.reorderPages(project.id, pages.map { it.id })
            if (updated != null) applyProjectUpdate(updated)
        }
    }

    fun togglePageSelectedForExport(pageId: String) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return
        viewModelScope.launch {
            val updated = repository.updatePage(project.id, page.copy(selectedForExport = !page.selectedForExport))
            if (updated != null) applyProjectUpdate(updated)
        }
    }

    fun setAllPagesSelectedForExport(selected: Boolean) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            val updated = project.copy(pages = project.pages.map { it.copy(selectedForExport = selected) })
            repository.saveProject(updated)
            applyProjectUpdate(updated)
        }
    }

    fun startOrResumeBatchProcessing() {
        val project = _uiState.value.activeProject ?: return
        if (project.pages.isEmpty()) {
            showMessage("ابتدا تصاویر یا فصل مانگا را وارد کنید.")
            return
        }
        pipeline.startOrResume(
            scope = viewModelScope,
            projectId = project.id,
            settings = _uiState.value.settings,
            isRightToLeftManga = _uiState.value.isRightToLeftManga,
            onProjectUpdated = { updated ->
                applyProjectUpdate(updated)
            }
        )
    }

    fun pauseProcessing() {
        pipeline.pauseOrCancel()
    }

    fun resetPageForReprocess(pageId: String) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return
        viewModelScope.launch {
            val resetPage = page.copy(
                status = PageStatus.PENDING,
                errorMessage = null,
                blocks = emptyList(),
                cleanedPath = null,
                renderedPath = null
            )
            val updated = repository.updatePage(project.id, resetPage)
            if (updated != null) {
                applyProjectUpdate(updated)
                showMessage("صفحه برای پردازش مجدد آماده شد.")
            }
        }
    }

    fun updateBlockAndRerender(pageId: String, updatedBlock: TextBlock) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return

        pushUndoState(page.id, page.blocks)
        val newBlocks = page.blocks.map {
            if (it.id == updatedBlock.id) updatedBlock.copy(isManuallyEdited = true) else it
        }
        applyManualBlocks(project.id, page, newBlocks)
    }

    fun deleteBlockAndRerender(pageId: String, blockId: String) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return

        pushUndoState(page.id, page.blocks)
        val newBlocks = page.blocks.filterNot { it.id == blockId }
        applyManualBlocks(project.id, page, newBlocks)
    }

    fun addManualBlockAndRerender(pageId: String, translatedText: String) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return

        pushUndoState(page.id, page.blocks)
        val w = page.width.coerceAtLeast(600).toFloat()
        val h = page.height.coerceAtLeast(800).toFloat()
        val boxW = w * 0.36f
        val boxH = (h * 0.10f).coerceIn(80f, 180f)
        val cx = w / 2f
        val cy = h / 2f

        val newBlock = TextBlock(
            bounds = NormalizedRect(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f),
            originalText = "",
            translatedText = translatedText,
            type = BubbleType.DIALOGUE,
            isManuallyEdited = true
        )
        applyManualBlocks(project.id, page, page.blocks + newBlock)
    }

    fun undoEdit(pageId: String) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return
        val stack = undoMap[pageId] ?: return
        if (stack.isEmpty()) return

        val previousBlocks = stack.removeAt(stack.lastIndex)
        redoMap.getOrPut(pageId) { mutableListOf() }.add(page.blocks)
        syncUndoRedoFlags(pageId)
        applyManualBlocks(project.id, page, previousBlocks)
    }

    fun redoEdit(pageId: String) {
        val project = _uiState.value.activeProject ?: return
        val page = project.pages.find { it.id == pageId } ?: return
        val stack = redoMap[pageId] ?: return
        if (stack.isEmpty()) return

        val nextBlocks = stack.removeAt(stack.lastIndex)
        undoMap.getOrPut(pageId) { mutableListOf() }.add(page.blocks)
        syncUndoRedoFlags(pageId)
        applyManualBlocks(project.id, page, nextBlocks)
    }

    private fun pushUndoState(pageId: String, snapshot: List<TextBlock>) {
        val stack = undoMap.getOrPut(pageId) { mutableListOf() }
        stack.add(snapshot)
        if (stack.size > 25) stack.removeAt(0)
        redoMap[pageId]?.clear()
        syncUndoRedoFlags(pageId)
    }

    private fun syncUndoRedoFlags(pageId: String) {
        _canUndo.value = undoMap[pageId]?.isNotEmpty() == true
        _canRedo.value = redoMap[pageId]?.isNotEmpty() == true
    }

    private fun applyManualBlocks(projectId: String, page: MangaPage, newBlocks: List<TextBlock>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRerendering = true) }
            try {
                pipeline.rerenderEditedPage(
                    projectId = projectId,
                    page = page,
                    updatedBlocks = newBlocks,
                    settings = _uiState.value.settings
                )
                val reloaded = repository.loadProject(projectId)
                if (reloaded != null) {
                    applyProjectUpdate(reloaded)
                }
            } catch (e: Exception) {
                showMessage("خطا در اعمال ویرایش: ${e.message}")
            } finally {
                _uiState.update {
                    it.copy(
                        isRerendering = false,
                        renderRevision = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    fun updateSettings(newSettings: StudioSettings) {
        viewModelScope.launch {
            repository.saveSettings(newSettings)
            _uiState.update { it.copy(settings = newSettings) }
        }
    }

    fun addGlossaryEntry(source: String, target: String) {
        val cleanSrc = source.trim()
        val cleanDst = target.trim()
        if (cleanSrc.isEmpty() || cleanDst.isEmpty()) return
        val current = _uiState.value.settings
        val updatedList = current.glossary.filterNot { it.source.equals(cleanSrc, ignoreCase = true) } +
            GlossaryEntry(cleanSrc, cleanDst)
        updateSettings(current.copy(glossary = updatedList))
    }

    fun removeGlossaryEntry(entry: GlossaryEntry) {
        val current = _uiState.value.settings
        updateSettings(current.copy(glossary = current.glossary.filterNot { it == entry }))
    }

    fun exportPdf(options: PdfExportOptions) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    exportState = ExportUiState(
                        isExporting = true,
                        progressCurrent = 0,
                        progressTotal = project.pages.size
                    )
                )
            }
            try {
                val result = pdfExporter.exportToPdf(
                    pages = project.pages,
                    options = options,
                    onProgress = { cur, tot ->
                        _uiState.update { st ->
                            st.copy(
                                exportState = st.exportState.copy(
                                    progressCurrent = cur,
                                    progressTotal = tot
                                )
                            )
                        }
                    }
                )
                _uiState.update {
                    it.copy(
                        exportState = ExportUiState(
                            isExporting = false,
                            progressCurrent = result.exportedPageCount,
                            progressTotal = result.exportedPageCount,
                            lastPdfResult = result
                        )
                    )
                }
                showMessage("فایل PDF با موفقیت در Downloads/MangaStudio ذخیره شد.")
            } catch (e: Exception) {
                _uiState.update { it.copy(exportState = it.exportState.copy(isExporting = false)) }
                showMessage("خطا در ساخت PDF: ${e.message}")
            }
        }
    }

    fun exportSeparateImages(onlySelected: Boolean) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    exportState = it.exportState.copy(
                        isExporting = true,
                        progressCurrent = 0,
                        progressTotal = project.pages.size
                    )
                )
            }
            try {
                val savedCount = pdfExporter.exportImagesSeparately(
                    projectName = project.name,
                    pages = project.pages,
                    onlySelectedPages = onlySelected,
                    onProgress = { cur, tot ->
                        _uiState.update { st ->
                            st.copy(
                                exportState = st.exportState.copy(
                                    progressCurrent = cur,
                                    progressTotal = tot
                                )
                            )
                        }
                    }
                )
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            isExporting = false,
                            lastImagesSavedCount = savedCount
                        )
                    )
                }
                showMessage("$savedCount تصویر با کیفیت بالا در گالری (Pictures/MangaStudio) ذخیره شد.")
            } catch (e: Exception) {
                _uiState.update { it.copy(exportState = it.exportState.copy(isExporting = false)) }
                showMessage("خطا در ذخیره تصاویر: ${e.message}")
            }
        }
    }

    private fun applyProjectUpdate(updated: MangaProject) {
        _uiState.update { state ->
            val updatedList = state.projects
                .filterNot { it.id == updated.id }
                .let { listOf(updated) + it }
                .sortedByDescending { it.updatedAt }
            state.copy(
                projects = updatedList,
                activeProject = if (state.activeProject?.id == updated.id) updated else state.activeProject,
                renderRevision = System.currentTimeMillis()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.pauseOrCancel()
        ocrEngine.close()
    }
}
