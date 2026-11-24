package com.mangastudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mangastudio.app.core.model.MangaPage
import com.mangastudio.app.core.model.MangaProject
import com.mangastudio.app.core.model.PageStatus
import com.mangastudio.app.core.model.TranslatorProvider
import com.mangastudio.app.pipeline.PipelineProgress
import com.mangastudio.app.ui.StudioUiState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: StudioUiState,
    onCreateProject: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Manga Studio", fontWeight = FontWeight.Bold)
                        Text(
                            "ترجمه هوشمند مانگا و مانهوا (بدون محدودیت)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "تنظیمات")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("فصل / پروژه جدید") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val activeEngineLabel = when (uiState.settings.translatorProvider) {
                    TranslatorProvider.GOOGLE_FREE -> "گوگل ترنسلیت دسته‌ای (رایگان و نامحدود)"
                    TranslatorProvider.OPENAI_COMPATIBLE -> "API اختصاصی (${uiState.settings.customModelName})"
                    TranslatorProvider.CUSTOM_LOCAL -> "مدل محلی (${uiState.settings.customModelName})"
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "استودیوی آماده پردازش",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "• تشخیص متن (OCR) و پاک‌سازی بافت: ۱۰۰٪ آفلاین روی گوشی",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "• موتور ترجمه فعال: $activeEngineLabel",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "• خروجی PDF مستقیم بدون افت کیفیت + ویرایشگر دستی حباب‌ها",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text(
                    "پروژه‌های اخیر (${uiState.projects.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (uiState.projects.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("هنوز پروژه‌ای نساخته‌اید", fontWeight = FontWeight.Bold)
                            Text(
                                "دکمه «فصل / پروژه جدید» را بزنید و صفحات مانگا یا فایل ZIP/RAR را وارد کنید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(uiState.projects, key = { it.id }) { project ->
                    ProjectItemCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { onDeleteProject(project.id) }
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("ساخت پروژه جدید") },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("نام مانگا یا شماره فصل") },
                    placeholder = { Text("مثلاً: Chapter 1120") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = newProjectName.trim().ifEmpty { "فصل جدید" }
                    showNewDialog = false
                    newProjectName = ""
                    onCreateProject(name)
                }) {
                    Text("ساخت پروژه")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun ProjectItemCard(
    project: MangaProject,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val total = project.pages.size
    val done = project.pages.count { it.status == PageStatus.RENDERED }
    val progress = if (total == 0) 0f else done.toFloat() / total.toFloat()
    val coverPath = project.pages.firstOrNull()?.let { it.renderedPath ?: it.originalPath }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (coverPath != null) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$done از $total صفحه ترجمه‌شده",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "حذف پروژه",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    uiState: StudioUiState,
    pipelineProgress: PipelineProgress,
    onBack: () -> Unit,
    onPickImages: () -> Unit,
    onPickFolder: () -> Unit,
    onPickArchive: () -> Unit,
    onToggleRtl: (Boolean) -> Unit,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onMovePage: (String, Boolean) -> Unit,
    onResetPage: (String) -> Unit,
    onOpenCompare: (String) -> Unit,
    onOpenEditor: (String) -> Unit,
    onOpenExport: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val project = uiState.activeProject ?: return
    val sortedPages = remember(project.pages) { project.pages.sortedBy { it.index } }
    val doneCount = sortedPages.count { it.status == PageStatus.RENDERED }
    val errorCount = sortedPages.count { it.status == PageStatus.ERROR }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "بازگشت")
                    }
                },
                title = {
                    Column {
                        Text(
                            project.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${sortedPages.size} صفحه • $doneCount تکمیل‌شده",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenExport, enabled = sortedPages.isNotEmpty()) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "خروجی PDF")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "تنظیمات")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("افزودن صفحات فصل", fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onPickImages,
                                enabled = !uiState.isImporting && !pipelineProgress.isRunning,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تصاویر", maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = onPickFolder,
                                enabled = !uiState.isImporting && !pipelineProgress.isRunning,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("پوشه", maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = onPickArchive,
                                enabled = !uiState.isImporting && !pipelineProgress.isRunning,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("ZIP/RAR", maxLines = 1)
                            }
                        }

                        if (uiState.isImporting) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("در حال وارد کردن و مرتب‌سازی صفحات...", style = MaterialTheme.typography.bodySmall)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("ترتیب حباب‌ها:", style = MaterialTheme.typography.bodySmall)
                            FilterChip(
                                selected = !uiState.isRightToLeftManga,
                                onClick = { onToggleRtl(false) },
                                label = { Text("مانهوا / کمیک") }
                            )
                            FilterChip(
                                selected = uiState.isRightToLeftManga,
                                onClick = { onToggleRtl(true) },
                                label = { Text("مانگای ژاپنی") }
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (pipelineProgress.isRunning) {
                                Button(
                                    onClick = onPause,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("توقف موقت (ذخیره مرحله)")
                                }
                            } else {
                                val buttonLabel = when {
                                    doneCount > 0 && doneCount < sortedPages.size -> "ادامه ترجمه (${sortedPages.size - doneCount} صفحه باقی‌مانده)"
                                    errorCount > 0 -> "تلاش مجدد برای صفحات خطادار ($errorCount)"
                                    doneCount == sortedPages.size && sortedPages.isNotEmpty() -> "همه صفحات تکمیل شده‌اند"
                                    else -> "شروع ترجمه خودکار فصل"
                                }
                                Button(
                                    onClick = onStartOrResume,
                                    enabled = sortedPages.isNotEmpty() && doneCount < sortedPages.size,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(buttonLabel)
                                }
                            }

                            OutlinedButton(
                                onClick = onOpenExport,
                                enabled = sortedPages.isNotEmpty() && !pipelineProgress.isRunning
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("PDF")
                            }
                        }

                        if (pipelineProgress.isRunning || pipelineProgress.totalPages > 0) {
                            val fraction = if (sortedPages.isEmpty()) 0f else doneCount.toFloat() / sortedPages.size.toFloat()
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Text(
                                "پیشرفت کل: $doneCount از ${sortedPages.size} صفحه",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            pipelineProgress.activeStages.values.take(3).forEach { stage ->
                                Text(
                                    "• $stage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            items(sortedPages, key = { it.id }) { page ->
                PageRowCard(
                    page = page,
                    isFirst = page.index == 0,
                    isLast = page.index == sortedPages.lastIndex,
                    activeStageText = pipelineProgress.activeStages[page.id],
                    renderRevision = uiState.renderRevision,
                    isBusy = pipelineProgress.isRunning,
                    onMoveUp = { onMovePage(page.id, true) },
                    onMoveDown = { onMovePage(page.id, false) },
                    onCompare = { onOpenCompare(page.id) },
                    onEdit = { onOpenEditor(page.id) },
                    onReset = { onResetPage(page.id) }
                )
            }
        }
    }
}

@Composable
private fun PageRowCard(
    page: MangaPage,
    isFirst: Boolean,
    isLast: Boolean,
    activeStageText: String?,
    renderRevision: Long,
    isBusy: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCompare: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val previewPath = page.renderedPath ?: page.cleanedPath ?: page.originalPath
    val statusText = activeStageText ?: when (page.status) {
        PageStatus.PENDING -> "در انتظار پردازش"
        PageStatus.OCR_DONE -> "متن تشخیص داده شد (${page.blocks.size} حباب)"
        PageStatus.CLEANED -> "پاک‌سازی شد • در انتظار ترجمه"
        PageStatus.TRANSLATED -> "ترجمه شد • در انتظار جاگذاری"
        PageStatus.RENDERED -> "تکمیل‌شده (${page.blocks.size} حباب)"
        PageStatus.ERROR -> "خطا: ${page.errorMessage.orEmpty()}"
    }
    val statusColor = when (page.status) {
        PageStatus.RENDERED -> MaterialTheme.colorScheme.secondary
        PageStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(previewPath))
                    .memoryCacheKey("${previewPath}_$renderRevision")
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 58.dp, height = 78.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCompare)
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "صفحه ${page.index + 1}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(6.dp))
                    if (page.status == PageStatus.RENDERED) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (page.status == PageStatus.ERROR) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    "${page.fileName} (${page.width}×${page.height})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = onCompare,
                        label = { Text("مقایسه", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Compare, null, Modifier.size(15.dp)) }
                    )
                    AssistChip(
                        onClick = onEdit,
                        label = { Text("ویرایش", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(15.dp)) }
                    )
                    if (page.status == PageStatus.RENDERED || page.status == PageStatus.ERROR) {
                        IconButton(
                            onClick = onReset,
                            enabled = !isBusy,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "پردازش مجدد", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMoveUp, enabled = !isFirst && !isBusy, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "بالا")
                }
                IconButton(onClick = onMoveDown, enabled = !isLast && !isBusy, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "پایین")
                }
            }
        }
    }
}
