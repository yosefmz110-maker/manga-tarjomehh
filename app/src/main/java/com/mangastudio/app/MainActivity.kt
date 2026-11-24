package com.mangastudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mangastudio.app.ui.StudioViewModel
import com.mangastudio.app.ui.screens.CompareScreen
import com.mangastudio.app.ui.screens.ExportScreen
import com.mangastudio.app.ui.screens.HomeScreen
import com.mangastudio.app.ui.screens.ManualEditorScreen
import com.mangastudio.app.ui.screens.ProjectWorkspaceScreen
import com.mangastudio.app.ui.screens.SettingsScreen
import com.mangastudio.app.ui.theme.MangaStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MangaStudioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MangaStudioRoot()
                }
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val WORKSPACE = "workspace"
    const val COMPARE = "compare"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val EXPORT = "export"
}

@Composable
private fun MangaStudioRoot(viewModel: StudioViewModel = viewModel()) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    val pipelineProgress by viewModel.pipelineProgress.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importImages(uris, preserveSelectionOrder = true)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.importFolder(treeUri)
        }
    }

    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { archiveUri ->
        if (archiveUri != null) {
            viewModel.importArchive(archiveUri)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { outerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        uiState = uiState,
                        onCreateProject = { name ->
                            viewModel.createAndOpenProject(name) {
                                navController.navigate(Routes.WORKSPACE)
                            }
                        },
                        onOpenProject = { projectId ->
                            viewModel.openProject(projectId)
                            navController.navigate(Routes.WORKSPACE)
                        },
                        onDeleteProject = { viewModel.deleteProject(it) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }

                composable(Routes.WORKSPACE) {
                    ProjectWorkspaceScreen(
                        uiState = uiState,
                        pipelineProgress = pipelineProgress,
                        onBack = {
                            viewModel.refreshProjects()
                            navController.popBackStack()
                        },
                        onPickImages = {
                            imagePickerLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                        },
                        onPickFolder = {
                            folderPickerLauncher.launch(null)
                        },
                        onPickArchive = {
                            archivePickerLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/vnd.rar",
                                    "application/x-rar-compressed",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        onToggleRtl = { viewModel.setRightToLeftManga(it) },
                        onStartOrResume = { viewModel.startOrResumeBatchProcessing() },
                        onPause = { viewModel.pauseProcessing() },
                        onMovePage = { id, up -> viewModel.movePage(id, up) },
                        onResetPage = { viewModel.resetPageForReprocess(it) },
                        onOpenCompare = { pageId ->
                            viewModel.selectActivePage(pageId)
                            navController.navigate(Routes.COMPARE)
                        },
                        onOpenEditor = { pageId ->
                            viewModel.selectActivePage(pageId)
                            navController.navigate(Routes.EDITOR)
                        },
                        onOpenExport = { navController.navigate(Routes.EXPORT) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }

                composable(Routes.COMPARE) {
                    CompareScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onSelectPage = { viewModel.selectActivePage(it) },
                        onOpenEditor = { pageId ->
                            viewModel.selectActivePage(pageId)
                            navController.navigate(Routes.EDITOR)
                        }
                    )
                }

                composable(Routes.EDITOR) {
                    ManualEditorScreen(
                        uiState = uiState,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        onBack = { navController.popBackStack() },
                        onUpdateBlock = { pageId, blk -> viewModel.updateBlockAndRerender(pageId, blk) },
                        onDeleteBlock = { pageId, blkId -> viewModel.deleteBlockAndRerender(pageId, blkId) },
                        onAddManualBlock = { pageId, txt -> viewModel.addManualBlockAndRerender(pageId, txt) },
                        onUndo = { viewModel.undoEdit(it) },
                        onRedo = { viewModel.redoEdit(it) }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onAddGlossary = { s, t -> viewModel.addGlossaryEntry(s, t) },
                        onRemoveGlossary = { viewModel.removeGlossaryEntry(it) }
                    )
                }

                composable(Routes.EXPORT) {
                    ExportScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onTogglePageSelection = { viewModel.togglePageSelectedForExport(it) },
                        onSelectAll = { viewModel.setAllPagesSelectedForExport(it) },
                        onExportPdf = { viewModel.exportPdf(it) },
                        onExportSeparateImages = { viewModel.exportSeparateImages(it) }
                    )
                }
            }
        }
    }
}
