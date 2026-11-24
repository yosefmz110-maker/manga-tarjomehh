package com.mangastudio.app.engine.translate

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.mangastudio.app.core.model.BubbleType
import com.mangastudio.app.core.model.GlossaryEntry
import com.mangastudio.app.core.model.StudioSettings
import com.mangastudio.app.core.model.TextBlock
import com.mangastudio.app.core.model.TranslationTone
import com.mangastudio.app.core.model.TranslatorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

interface TranslatorEngine {
    suspend fun translateBlocks(
        blocks: List<TextBlock>,
        settings: StudioSettings
    ): List<String>
}

object TranslatorFactory {
    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun create(settings: StudioSettings): TranslatorEngine {
        return when (settings.translatorProvider) {
            TranslatorProvider.GOOGLE_FREE -> GoogleFreeBatchTranslator(sharedHttpClient)
            TranslatorProvider.OPENAI_COMPATIBLE,
            TranslatorProvider.CUSTOM_LOCAL -> OpenAiCompatibleTranslator(sharedHttpClient)
        }
    }
}

object GlossaryAndPersianNormalizer {

    private val placeholderRegex = Regex("""Q\s*Z\s*X\s*([0-9۰-۹٠-٩]+)\s*X\s*Z\s*Q""", RegexOption.IGNORE_CASE)

    fun protectGlossary(
        text: String,
        glossary: List<GlossaryEntry>
    ): Pair<String, Map<Int, String>> {
        if (glossary.isEmpty()) return Pair(text, emptyMap())
        var result = text
        val tokenMap = mutableMapOf<Int, String>()

        val sorted = glossary
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .sortedByDescending { it.source.length }

        sorted.forEachIndexed { idx, entry ->
            val escaped = Regex.escape(entry.source.trim())
            val regex = Regex("""(?i)(?<![A-Za-z0-9])$escaped(?![A-Za-z0-9])""")
            if (regex.containsMatchIn(result)) {
                val token = "QZX${idx}XZQ"
                result = regex.replace(result, token)
                tokenMap[idx] = entry.target.trim()
            }
        }
        return Pair(result, tokenMap)
    }

    fun restoreGlossary(text: String, tokenMap: Map<Int, String>): String {
        if (tokenMap.isEmpty()) return text
        return placeholderRegex.replace(text) { match ->
            val idx = toAsciiDigits(match.groupValues[1]).toIntOrNull()
            if (idx != null && tokenMap.containsKey(idx)) tokenMap[idx]!! else match.value
        }
    }

    fun toAsciiDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizePersian(raw: String, targetLang: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        if (targetLang.equals("fa", ignoreCase = true)) {
            text = text
                .replace('ي', 'ی')
                .replace('ك', 'ک')
                .replace('ة', 'ه')
                .replace("?", "؟")
                .replace(Regex("""\s+([،؛؟!.])"""), "$1")
                .replace(Regex("""(^|\s)(ن?می)\s+([\u0600-\u06FF]{2,})"""), "$1$2\u200C$3")
                .replace(Regex("""([\u0600-\u06FF]+)\s+(ها(?:ی|یی|ش|یت|یش|یمان|یتان|یشان)?)(?=$|[\s،؛؟!.])"""), "$1\u200C$2")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
        }
        return text
    }
}

class GoogleFreeBatchTranslator(
    private val client: OkHttpClient
) : TranslatorEngine {

    companion object {
        private const val MAX_BATCH_CHARS = 3200
        private val ENDPOINTS = listOf(
            "https://translate.googleapis.com/translate_a/single",
            "https://translate.google.com/translate_a/single",
            "https://clients5.google.com/translate_a/single"
        )
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )
        private val endpointCounter = AtomicInteger(0)
        private val markerRegex = Regex("""###\s*([0-9۰-۹٠-٩]+)\s*###""")
    }

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        settings: StudioSettings
    ): List<String> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val results = MutableList(blocks.size) { "" }
        val protectedTexts = blocks.map { block ->
            val preprocessed = preprocessComicEnglish(block.originalText)
            GlossaryAndPersianNormalizer.protectGlossary(preprocessed, settings.glossary)
        }

        val batches = mutableListOf<MutableList<Int>>()
        var currentBatch = mutableListOf<Int>()
        var currentLen = 0

        for (i in blocks.indices) {
            val itemText = protectedTexts[i].first
            if (itemText.isBlank()) {
                results[i] = blocks[i].originalText
                continue
            }
            val itemLen = itemText.length + 14
            if (currentBatch.isNotEmpty() && currentLen + itemLen > MAX_BATCH_CHARS) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentLen = 0
            }
            currentBatch.add(i)
            currentLen += itemLen
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)

        for ((batchIdx, indices) in batches.withIndex()) {
            if (batchIdx > 0) {
                delay(Random.nextLong(180L, 420L))
            }

            if (indices.size == 1) {
                val idx = indices.first()
                val rawTranslated = requestGoogleWithRetry(
                    text = protectedTexts[idx].first,
                    sourceLang = settings.sourceLang,
                    targetLang = settings.targetLang
                )
                results[idx] = finalizeSingle(rawTranslated, protectedTexts[idx].second, settings.targetLang)
            } else {
                val combined = buildString {
                    indices.forEachIndexed { localIdx, globalIdx ->
                        if (localIdx > 0) append("\n")
                        append("###").append(localIdx).append("###\n")
                        append(protectedTexts[globalIdx].first)
                    }
                }

                val rawBatchOutput = requestGoogleWithRetry(
                    text = combined,
                    sourceLang = settings.sourceLang,
                    targetLang = settings.targetLang
                )
                val parsedMap = parseBatchMarkers(rawBatchOutput)

                if (parsedMap.size == indices.size) {
                    indices.forEachIndexed { localIdx, globalIdx ->
                        val piece = parsedMap[localIdx].orEmpty()
                        results[globalIdx] = finalizeSingle(piece, protectedTexts[globalIdx].second, settings.targetLang)
                    }
                } else {
                    indices.forEachIndexed { localIdx, globalIdx ->
                        val piece = parsedMap[localIdx]
                        val translated = if (!piece.isNullOrBlank()) {
                            piece
                        } else {
                            delay(Random.nextLong(120L, 260L))
                            requestGoogleWithRetry(
                                text = protectedTexts[globalIdx].first,
                                sourceLang = settings.sourceLang,
                                targetLang = settings.targetLang
                            )
                        }
                        results[globalIdx] = finalizeSingle(translated, protectedTexts[globalIdx].second, settings.targetLang)
                    }
                }
            }
        }

        results
    }

    private fun finalizeSingle(
        raw: String,
        tokenMap: Map<Int, String>,
        targetLang: String
    ): String {
        val restored = GlossaryAndPersianNormalizer.restoreGlossary(raw, tokenMap)
        return GlossaryAndPersianNormalizer.normalizePersian(restored, targetLang)
    }

    private fun preprocessComicEnglish(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return text
        val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length.toFloat()
        if (upperRatio < 0.85f) return text

        val lowered = text.lowercase()
        val sb = StringBuilder(lowered.length)
        var capNext = true
        for (i in lowered.indices) {
            val ch = lowered[i]
            if (capNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?') {
                    capNext = true
                }
            }
        }
        return sb.toString()
            .replace(Regex("""\bi\b"""), "I")
            .replace(Regex("""\bi'([a-z]+)"""), "I'$1")
    }

    private fun parseBatchMarkers(output: String): Map<Int, String> {
        val matches = markerRegex.findAll(output).toList()
        if (matches.isEmpty()) return emptyMap()

        val map = mutableMapOf<Int, String>()
        for (i in matches.indices) {
            val idxNum = GlossaryAndPersianNormalizer.toAsciiDigits(matches[i].groupValues[1]).toIntOrNull()
                ?: continue
            val contentStart = matches[i].range.last + 1
            val contentEnd = if (i + 1 < matches.size) matches[i + 1].range.first else output.length
            val segment = output.substring(contentStart, contentEnd).trim()
            map[idxNum] = segment
        }
        return map
    }

    private suspend fun requestGoogleWithRetry(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String {
        var lastError: Exception? = null

        for (attempt in 0 until 4) {
            val baseEndpoint = ENDPOINTS[endpointCounter.getAndIncrement().and(0x7FFFFFFF) % ENDPOINTS.size]
            
