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
                                        .padding(
