package com.mangastudio.app.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mangastudio.app.core.model.BubbleType
import com.mangastudio.app.core.model.TextBlock
import com.mangastudio.app.ui.StudioUiState
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    uiState: StudioUiState,
    onBack: () -> Unit,
    onSelectPage: (String) -> Unit,
    onOpenEditor: (String) -> Unit
) {
    val project = uiState.activeProject ?: return
    val pages = remember(project.pages) { project.pages.sortedBy { it.index } }
    val page = uiState.activePage ?: pages.firstOrNull() ?: return
    val pagePos = pages.indexOfFirst { it.id == page.id }.coerceAtLeast(0)

    var viewMode by remember { mutableIntStateOf(0) }
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var boxWidthPx by remember { mutableFloatStateOf(1f) }
    val context = LocalContext.current

    val aspect = (page.width.coerceAtLeast(1).toFloat() / page.height.coerceAtLeast(1).toFloat())
        .coerceIn(0.12f, 2.2f)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "بازگشت")
                    }
                },
                title = {
                    Text("مقایسه صفحه ${page.index + 1} از ${pages.size}", fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = { onOpenEditor(page.id) }) {
                        Icon(Icons.Default.Edit, contentDescription = "ویرایش دستی")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("مقایسه کشویی", "ترجمه‌شده", "پاک‌شده", "اصلی").forEachIndexed { idx, title ->
                    FilterChip(
                        selected = viewMode == idx,
                        onClick = { viewMode = idx },
                        label = { Text(title, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                val originalReq = ImageRequest.Builder(context)
                    .data(File(page.originalPath))
                    .memoryCacheKey("${page.originalPath}_orig")
                    .build()

                val translatedPath = page.renderedPath ?: page.cleanedPath ?: page.originalPath
                val translatedReq = ImageRequest.Builder(context)
                    .data(File(translatedPath))
                    .memoryCacheKey("${translatedPath}_${uiState.renderRevision}")
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()

                val cleanedPath = page.cleanedPath ?: page.originalPath
                val cleanedReq = ImageRequest.Builder(context)
                    .data(File(cleanedPath))
                    .memoryCacheKey("${cleanedPath}_${uiState.renderRevision}")
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(10.dp))
                            .onSizeChanged { boxWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                            .then(
                                if (viewMode == 0) {
                                    Modifier.pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, dragAmount ->
                                            change.consume()
                                            splitFraction = (splitFraction + dragAmount / boxWidthPx).coerceIn(0.05f, 0.95f)
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        when (viewMode) {
                            0 -> {
                                AsyncImage(
                                    model = translatedReq,
                                    contentDescription = "ترجمه‌شده",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize()
                                )
                                AsyncImage(
                                    model = originalReq,
                                    contentDescription = "اصلی",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .drawWithContent {
                                            clipRect(right = size.width * splitFraction) {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(3.dp)
                                        .offset { IntOffset((boxWidthPx * splitFraction).roundToInt(), 0) }
                                        .background(MaterialTheme.colorScheme.secondary)
                                )
                                Text(
                                    "اصلی",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                Text(
                                    "ترجمه‌شده",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            1 -> AsyncImage(model = translatedReq, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                            2 -> AsyncImage(model = cleanedReq, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                            3 -> AsyncImage(model = originalReq, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (pagePos > 0) onSelectPage(pages[pagePos - 1].id) },
                    enabled = pagePos > 0
                ) {
                    Text("صفحه قبل")
                }

                Button(onClick = { onOpenEditor(page.id) }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ویرایش دستی حباب‌ها")
                }

                OutlinedButton(
                    onClick = { if (pagePos < pages.lastIndex) onSelectPage(pages[pagePos + 1].id) },
                    enabled = pagePos < pages.lastIndex
                ) {
                    Text("صفحه بعد")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEditorScreen(
    uiState: StudioUiState,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUpdateBlock: (String, TextBlock) -> Unit,
    onDeleteBlock: (String, String) -> Unit,
    onAddManualBlock: (String, String) -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit
) {
    val page = uiState.activePage ?: return
    val context = LocalContext.current

    var selectedBlockId by remember(page.id) { mutableStateOf(page.blocks.firstOrNull()?.id) }
    val selectedBlock = page.blocks.find { it.id == selectedBlockId }

    var draftTranslation by remember(selectedBlock?.id, selectedBlock?.translatedText) {
        mutableStateOf(selectedBlock?.translatedText.orEmpty())
    }
    var draftFontSize by remember(selectedBlock?.id, selectedBlock?.fontSizeOverride) {
        mutableFloatStateOf(selectedBlock?.fontSizeOverride ?: 0f)
    }
    var draftOffsetX by remember(selectedBlock?.id, selectedBlock?.offsetX) {
        mutableFloatStateOf(selectedBlock?.offsetX ?: 0f)
    }
    var draftOffsetY by remember(selectedBlock?.id, selectedBlock?.offsetY) {
        mutableFloatStateOf(selectedBlock?.offsetY ?: 0f)
    }
    var draftUseStroke by remember(selectedBlock?.id, selectedBlock?.useStroke) {
        mutableStateOf(selectedBlock?.useStroke ?: false)
    }
    var draftTextColor by remember(selectedBlock?.id, selectedBlock?.textColor) {
        mutableIntStateOf(selectedBlock?.textColor ?: AndroidColor.BLACK)
    }
    val draftType by remember(selectedBlock?.id, selectedBlock?.type) {
        mutableStateOf(selectedBlock?.type ?: BubbleType.DIALOGUE)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var newBlockText by remember { mutableStateOf("") }

    LaunchedEffect(page.blocks) {
        if (selectedBlockId != null && page.blocks.none { it.id == selectedBlockId }) {
            selectedBlockId = page.blocks.firstOrNull()?.id
        }
    }

    val aspect = (page.width.coerceAtLeast(1).toFloat() / page.height.coerceAtLeast(1).toFloat())
        .coerceIn(0.12f, 2.2f)
    val displayPath = page.renderedPath ?: page.cleanedPath ?: page.originalPath

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "بازگشت")
                    }
                },
                title = { Text("ویرایشگر دستی • صفحه ${page.index + 1}", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { onUndo(page.id) }, enabled = canUndo && !uiState.isRerendering) {
                        Icon(Icons.Default.Undo, contentDescription = "بازگشت مرحله قبل")
                    }
                    IconButton(onClick = { onRedo(page.id) }, enabled = canRedo && !uiState.isRerendering) {
                        Icon(Icons.Default.Redo, contentDescription = "انجام مجدد")
                    }
                    IconButton(onClick = { showAddDialog = true }, enabled = !uiState.isRerendering) {
                        Icon(Icons.Default.Add, contentDescription = "افزودن متن دستی")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isRerendering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(File(displayPath))
                                .memoryCacheKey("${displayPath}_${uiState.renderRevision}")
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )

                        val imgW = page.width.coerceAtLeast(1).toFloat()
                        val imgH = page.height.coerceAtLeast(1).toFloat()

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val sx = size.width / imgW
                            val sy = size.height / imgH
                            page.blocks.forEach { blk ->
                                val isSel = blk.id == selectedBlockId
                                val l = (blk.bounds.left + blk.offsetX) * sx
                                val t = (blk.bounds.top + blk.offsetY) * sy
                                val w = blk.bounds.width * sx
                                val h = blk.bounds.height * sy
                                drawRect(
                                    color = if (isSel) Color(0xFF38D9A9) else Color(0xAA7C8CFF),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = if (isSel) 5f else 2.5f)
                                )
                            }
                        }

                        var containerW by remember { mutableFloatStateOf(1f) }
                        var containerH by remember { mutableFloatStateOf(1f) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged {
                                    containerW = it.width.toFloat().coerceAtLeast(1f)
                                    containerH = it.height.toFloat().coerceAtLeast(1f)
                                }
                        ) {
                            page.blocks.forEach { blk ->
                                val sx = containerW / imgW
                                val sy = containerH / imgH
                                val leftPx = ((blk.bounds.left + blk.offsetX) * sx).roundToInt()
                                val topPx = ((blk.bounds.top + blk.offsetY) * sy).roundToInt()
                                val widthDp = ((blk.bounds.width * sx) / context.resources.displayMetrics.density).dp
                                val heightDp = ((blk.bounds.height * sy) / context.resources.displayMetrics.density).dp

                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(leftPx, topPx) }
                                        .size(width = widthDp.coerceAtLeast(24.dp), height = heightDp.coerceAtLeast(24.dp))
                                        .clickable { selectedBlockId = blk.id }
                                )
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedBlock == null) {
                        Text(
                            "برای ویرایش، روی یکی از حباب‌های کادردار روی تصویر بزنید یا با دکمه + متن جدید اضافه کنید.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val blkIdx = page.blocks.indexOfFirst { it.id == selectedBlock.id } + 1
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "ویرایش حباب شماره $blkIdx از ${page.blocks.size}",
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { onDeleteBlock(page.id, selectedBlock.id) },
                                enabled = !uiState.isRerendering
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف حباب", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (selectedBlock.originalText.isNotBlank()) {
                            Text(
                                "متن انگلیسی شناسایی‌شده: ${selectedBlock.originalText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = draftTranslation,
                            onValueChange = { draftTranslation = it },
                            label = { Text("ترجمه فارسی") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (draftFontSize <= 0f) "اندازه فونت: خودکار" else "اندازه فونت: ${draftFontSize.toInt()}px",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (draftFontSize > 0f) {
                                TextButton(onClick = { draftFontSize = 0f }) {
                                    Text("بازگشت به خودکار")
                                }
                            }
                        }
                        Slider(
                            value = draftFontSize,
                            onValueChange = { draftFontSize = it },
                            valueRange = 0f..72f
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "جابه‌جایی (X: ${draftOffsetX.toInt()}, Y: ${draftOffsetY.toInt()})",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row {
                                IconButton(onClick = { draftOffsetX -= 10f }) { Icon(Icons.Default.KeyboardArrowRight, "چپ") }
                                IconButton(onClick = { draftOffsetY -= 10f }) { Icon(Icons.Default.KeyboardArrowUp, "بالا") }
                                IconButton(onClick = { draftOffsetY += 10f }) { Icon(Icons.Default.KeyboardArrowDown, "پایین") }
                                IconButton(onClick = { draftOffsetX += 10f }) { Icon(Icons.Default.KeyboardArrowLeft, "راست") }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("رنگ متن:", style = MaterialTheme.typography.bodySmall)
                                listOf(AndroidColor.BLACK, AndroidColor.WHITE, AndroidColor.RED, AndroidColor.YELLOW).forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(col))
                                            .border(
                                                width = if (draftTextColor == col) 3.dp else 1.dp,
                                                color = if (draftTextColor == col) MaterialTheme.colorScheme.secondary else Color.Gray,
                                                shape = CircleShape
                                            )
                                            .clickable { draftTextColor = col }
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("حاشیه دور متن", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Switch(checked = draftUseStroke, onCheckedChange = { draftUseStroke = it })
                            }
                        }

                        Button(
                            onClick = {
                                val updated = selectedBlock.copy(
                                    translatedText = draftTranslation.trim(),
                                    fontSizeOverride = if (draftFontSize <= 1f) null else draftFontSize,
                                    offsetX = draftOffsetX,
                                    offsetY = draftOffsetY,
                                    useStroke = draftUseStroke,
                                    textColor = draftTextColor,
                                    strokeColor = if (draftTextColor == AndroidColor.WHITE) AndroidColor.BLACK else AndroidColor.WHITE,
                                    type = draftType
                                )
                                onUpdateBlock(page.id, updated)
                            },
                            enabled = !uiState.isRerendering,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("اعمال تغییرات روی صفحه (آنی)")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("افزودن متن فارسی جدید") },
            text = {
                OutlinedTextField(
                    value = newBlockText,
                    onValueChange = { newBlockText = it },
                    label = { Text("متن فارسی") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val txt = newBlockText.trim()
                    if (txt.isNotEmpty()) {
                        onAddManualBlock(page.id, txt)
                    }
                    newBlockText = ""
                    showAddDialog = false
                }) {
                    Text("افزودن")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("انصراف") }
            }
        )
    }
}
