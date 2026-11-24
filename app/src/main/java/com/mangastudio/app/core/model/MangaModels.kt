package com.mangastudio.app.core.model

import java.util.UUID

enum class PageStatus {
    PENDING,
    OCR_DONE,
    CLEANED,
    TRANSLATED,
    RENDERED,
    ERROR
}

enum class BubbleType {
    DIALOGUE,
    THOUGHT,
    NARRATION,
    SHOUT,
    SFX
}

enum class TranslatorProvider {
    GOOGLE_FREE,
    OPENAI_COMPATIBLE,
    CUSTOM_LOCAL
}

enum class InpaintMode {
    SMART_HYBRID,
    ALWAYS_INPAINT,
    FAST_SOLID
}

enum class TranslationTone {
    NATURAL_COLLOQUIAL,
    SEMI_FORMAL,
    LITERARY
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class TextBlock(
    val id: String = UUID.randomUUID().toString(),
    val bounds: NormalizedRect,
    val lineBoxes: List<NormalizedRect> = emptyList(),
    val originalText: String,
    val translatedText: String = "",
    val type: BubbleType = BubbleType.DIALOGUE,
    val fontSizeOverride: Float? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val textColor: Int = 0xFF000000.toInt(),
    val strokeColor: Int = 0xFFFFFFFF.toInt(),
    val useStroke: Boolean = false,
    val isBackgroundComplex: Boolean = false,
    val estimatedBgColor: Int = 0xFFFFFFFF.toInt(),
    val isManuallyEdited: Boolean = false
)

data class MangaPage(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val fileName: String,
    val originalPath: String,
    val cleanedPath: String? = null,
    val renderedPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val status: PageStatus = PageStatus.PENDING,
    val errorMessage: String? = null,
    val blocks: List<TextBlock> = emptyList(),
    val selectedForExport: Boolean = true
)

data class GlossaryEntry(
    val source: String,
    val target: String
)

data class StudioSettings(
    val sourceLang: String = "en",
    val targetLang: String = "fa",
    val translatorProvider: TranslatorProvider = TranslatorProvider.GOOGLE_FREE,
    val customApiBaseUrl: String = "https://api.openai.com/v1",
    val customApiKey: String = "",
    val customModelName: String = "gpt-4o-mini",
    val translationTone: TranslationTone = TranslationTone.NATURAL_COLLOQUIAL,
    val inpaintMode: InpaintMode = InpaintMode.SMART_HYBRID,
    val fontName: String = "Vazirmatn-Bold.ttf",
    val parallelWorkers: Int = 3,
    val pdfQuality: Int = 95,
    val glossary: List<GlossaryEntry> = emptyList()
)

data class MangaProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pages: List<MangaPage> = emptyList()
)
