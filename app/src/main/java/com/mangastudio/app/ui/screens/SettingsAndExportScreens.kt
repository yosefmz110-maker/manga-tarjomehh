package com.mangastudio.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mangastudio.app.core.model.GlossaryEntry
import com.mangastudio.app.core.model.InpaintMode
import com.mangastudio.app.core.model.StudioSettings
import com.mangastudio.app.core.model.TranslationTone
import com.mangastudio.app.core.model.TranslatorProvider
import com.mangastudio.app.engine.pdf.PdfExportOptions
import com.mangastudio.app.ui.StudioUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: StudioUiState,
    onBack: () -> Unit,
    onUpdateSettings: (StudioSettings) -> Unit,
    onAddGlossary: (String, String) -> Unit,
    onRemoveGlossary: (GlossaryEntry) -> Unit
) {
    val settings = uiState.settings
    var showApiKey by remember { mutableStateOf(false) }
    var newSrcTerm by remember { mutableStateOf("") }
    var newDstTerm by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "بازگشت")
                    }
                },
                title = { Text("تنظیمات هوش مصنوعی و پردازش", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("موتور ترجمه", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = settings.translatorProvider == TranslatorProvider.GOOGLE_FREE,
                                onClick = { onUpdateSettings(settings.copy(translatorProvider = TranslatorProvider.GOOGLE_FREE)) },
                                label = { Text("گوگل ترنسلیت (نامحدود)") }
                            )
                            FilterChip(
                                selected = settings.translatorProvider == TranslatorProvider.OPENAI_COMPATIBLE,
                                onClick = { onUpdateSettings(settings.copy(translatorProvider = TranslatorProvider.OPENAI_COMPATIBLE)) },
                                label = { Text("API دلخواه") }
                            )
                            FilterChip(
                                selected = settings.translatorProvider == TranslatorProvider.CUSTOM_LOCAL,
                                onClick = { onUpdateSettings(settings.copy(translatorProvider = TranslatorProvider.CUSTOM_LOCAL)) },
                                label = { Text("مدل محلی") }
                            )
                        }

                        if (settings.translatorProvider != TranslatorProvider.GOOGLE_FREE) {
                            OutlinedTextField(
                                value = settings.customApiBaseUrl,
                                onValueChange = { onUpdateSettings(settings.copy(customApiBaseUrl = it)) },
                                label = { Text("آدرس Base URL (مثلاً Ollama یا OpenRouter)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = settings.customModelName,
                                onValueChange = { onUpdateSettings(settings.copy(customModelName = it)) },
                                label = { Text("نام مدل") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = settings.customApiKey,
                                onValueChange = { onUpdateSettings(settings.copy(customApiKey = it)) },
                                label = { Text("کلید API (برای مدل محلی اختیاری)") },
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = settings.sourceLang,
                                onValueChange = { onUpdateSettings(settings.copy(sourceLang = it.trim())) },
                                label = { Text("کد زبان مبدأ") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = settings.targetLang,
                                onValueChange = { onUpdateSettings(settings.copy(targetLang = it.trim())) },
                                label = { Text("کد زبان مقصد") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text("سبک ترجمه (برای مدل‌های LLM):", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = settings.translationTone == TranslationTone.NATURAL_COLLOQUIAL,
                                onClick = { onUpdateSettings(settings.copy(translationTone = TranslationTone.NATURAL_COLLOQUIAL)) },
                                label = { Text("محاوره‌ای") }
                            )
                            FilterChip(
                                selected = settings.translationTone == TranslationTone.SEMI_FORMAL,
                                onClick = { onUpdateSettings(settings.copy(translationTone = TranslationTone.SEMI_FORMAL)) },
                                label = { Text("نیمه‌رسمی") }
                            )
                            FilterChip(
                                selected = settings.translationTone == TranslationTone.LITERARY,
                                onClick = { onUpdateSettings(settings.copy(translationTone = TranslationTone.LITERARY)) },
                                label = { Text("ادبی") }
                            )
                        }
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("پاک‌سازی هوشمند و تایپوگرافی", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = settings.inpaintMode == InpaintMode.SMART_HYBRID,
                                onClick = { onUpdateSettings(settings.copy(inpaintMode = InpaintMode.SMART_HYBRID)) },
                                label = { Text("هوشمند ترکیبی") }
                            )
                            FilterChip(
                                selected = settings.inpaintMode == InpaintMode.ALWAYS_INPAINT,
                                onClick = { onUpdateSettings(settings.copy(inpaintMode = InpaintMode.ALWAYS_INPAINT)) },
                                label = { Text("بازسازی کامل بافت") }
                            )
                            FilterChip(
                                selected = settings.inpaintMode == InpaintMode.FAST_SOLID,
                                onClick = { onUpdateSettings(settings.copy(inpaintMode = InpaintMode.FAST_SOLID)) },
                                label = { Text("سریع") }
                            )
                        }

                        Text("فونت فارسی حباب‌ها:", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.availableFonts.take(3).forEach { font ->
                                FilterChip(
                                    selected = settings.fontName == font,
                                    onClick = { onUpdateSettings(settings.copy(fontName = font)) },
                                    label = { Text(font.substringBeforeLast('.')) }
                                )
                            }
                        }

                        Text(
                            "تعداد پردازش هم‌زمان صفحات: ${settings.parallelWorkers} صفحه",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.parallelWorkers.toFloat(),
                            onValueChange = { onUpdateSettings(settings.copy(parallelWorkers = it.toInt().coerceIn(1, 4))) },
                            valueRange = 1f..4f,
                            steps = 2
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("واژه‌نامه اختصاصی (نام شخصیت‌ها و اصطلاحات)", fontWeight = FontWeight.Bold)
                        Text(
                            "عبارات ثبت‌شده در اینجا در تمام صفحات با ترجمه ثابت و یکپارچه اعمال می‌شوند.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newSrcTerm,
                                onValueChange = { newSrcTerm = it },
                                label = { Text("انگلیسی") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newDstTerm,
                                onValueChange = { newDstTerm = it },
                                label = { Text("فارسی") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    onAddGlossary(newSrcTerm, newDstTerm)
                                    newSrcTerm = ""
                                    newDstTerm = ""
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "افزودن")
                            }
                        }

                        if (settings.glossary.isNotEmpty()) {
                            HorizontalDivider()
                            settings.glossary.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${entry.source}  ←  ${entry.target}", style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { onRemoveGlossary(entry) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    uiState: StudioUiState,
    onBack: () -> Unit,
    onTogglePageSelection: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onExportPdf: (PdfExportOptions) -> Unit,
    onExportSeparateImages: (Boolean) -> Unit
) {
    val project = uiState.activeProject ?: return
    val context = LocalContext.current
    val sortedPages = remember(project.pages) { project.pages.sortedBy { it.index } }

    var fileName by remember(project.id) { mutableStateOf("${project.name}_FA") }
    var quality by remember { mutableFloatStateOf(uiState.settings.pdfQuality.toFloat().coerceIn(80f, 100f)) }
    var preserveOriginalWidth by remember { mutableStateOf(true) }
    var onlySelected by remember { mutableStateOf(false) }

    val exportState = uiState.exportState

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "بازگشت")
                    }
                },
                title = { Text("خروجی PDF و ذخیره تصاویر", fontWeight = FontWeight.Bold) }
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
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("نام فایل PDF خروجی") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("کیفیت تصاویر PDF: ${quality.toInt()}٪", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = quality,
                            onValueChange = { quality = it },
                            valueRange = 80f..100f
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("حفظ ۱۰۰٪ وضوح و ابعاد اصلی صفحات", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = preserveOriginalWidth, onCheckedChange = { preserveOriginalWidth = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("خروجی فقط از صفحات انتخاب‌شده", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = onlySelected, onCheckedChange = { onlySelected = it })
                        }

                        Button(
                            onClick = {
                                onExportPdf(
                                    PdfExportOptions(
                                        outputFileName = fileName,
                                        jpegQuality = quality.toInt(),
                                        maxWidthPx = if (preserveOriginalWidth) 0 else 1400,
                                        onlySelectedPages = onlySelected
                                    )
                                )
                            },
                            enabled = !exportState.isExporting && sortedPages.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("ساخت فایل PDF با بالاترین کیفیت")
                        }

                        OutlinedButton(
                            onClick = { onExportSeparateImages(onlySelected) },
                            enabled = !exportState.isExporting && sortedPages.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("ذخیره تصاویر ترجمه‌شده به‌صورت جداگانه در گالری")
                        }

                        if (exportState.isExporting) {
                            val p = if (exportState.progressTotal == 0) 0f
                            else exportState.progressCurrent.toFloat() / exportState.progressTotal.toFloat()
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "در حال تولید خروجی (${exportState.progressCurrent} از ${exportState.progressTotal})...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            exportState.lastPdfResult?.let { result ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("فایل PDF آماده است!", fontWeight = FontWeight.Bold)
                                Text(
                                    "${result.fileName} (${result.exportedPageCount} صفحه)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = {
                                val uri = result.publicUri ?: result.localFile?.let { file ->
                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                }
                                if (uri != null) {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "باز کردن یا ارسال PDF"))
                                }
                            }) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("ارسال / باز کردن")
                            }
                        }
                    }
                }
            }

            if (onlySelected) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("انتخاب صفحات (${sortedPages.count { it.selectedForExport }} انتخاب‌شده)", fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(onClick = { onSelectAll(true) }) { Text("همه") }
                            TextButton(onClick = { onSelectAll(false) }) { Text("هیچ‌کدام") }
                        }
                    }
                }
                items(sortedPages, key = { it.id }) { page ->
                    Card(shape = RoundedCornerShape(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = page.selectedForExport,
                                onCheckedChange = { onTogglePageSelection(page.id) }
                            )
                            Text("صفحه ${page.index + 1} — ${page.fileName}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
