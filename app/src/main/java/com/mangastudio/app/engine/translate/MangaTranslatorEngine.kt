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
            val userAgent = USER_AGENTS[attempt % USER_AGENTS.size]

            val url = "$baseEndpoint?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&ie=UTF-8&oe=UTF-8"
            val body = FormBody.Builder()
                .add("q", text)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful && responseBody.isNotBlank()) {
                        return parseGoogleJson(responseBody)
                    }
                    lastError = IOException("HTTP ${response.code} از سرور گوگل ترنسلیت")
                }
            } catch (e: Exception) {
                lastError = e
            }

            delay(500L * (attempt + 1) + Random.nextLong(100L, 350L))
        }

        throw IOException("خطا در ارتباط با موتور ترجمه: ${lastError?.message ?: "پاسخ نامعتبر"}")
    }

    private fun parseGoogleJson(jsonText: String): String {
        val root = JsonParser.parseString(jsonText)
        if (!root.isJsonArray) throw IOException("فرمت پاسخ ترجمه ناشناخته است.")
        val outerArray = root.asJsonArray
        if (outerArray.size() == 0 || !outerArray[0].isJsonArray) return ""

        val segments = outerArray[0].asJsonArray
        val sb = StringBuilder()
        for (el in segments) {
            if (el.isJsonArray) {
                val arr = el.asJsonArray
                if (arr.size() > 0 && !arr[0].isJsonNull) {
                    sb.append(arr[0].asString)
                }
            }
        }
        return sb.toString()
    }
}

class OpenAiCompatibleTranslator(
    private val client: OkHttpClient
) : TranslatorEngine {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        settings: StudioSettings
    ): List<String> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val toneGuide = when (settings.translationTone) {
            TranslationTone.NATURAL_COLLOQUIAL -> "فارسی روان، طبیعی و محاوره‌ای مناسب کمیک و مانهوا"
            TranslationTone.SEMI_FORMAL -> "فارسی نیمه‌رسمی و خوش‌خوان"
            TranslationTone.LITERARY -> "فارسی ادبی، حماسی و دقیق"
        }

        val glossaryNote = if (settings.glossary.isNotEmpty()) {
            val pairs = settings.glossary.joinToString("\n") { "- ${it.source} => ${it.target}" }
            "\nواژه‌نامه اجباری (دقیقاً مطابق این اسامی ترجمه کن):\n$pairs"
        } else {
            ""
        }

        val systemPrompt = """
            تو یک مترجم حرفه‌ای مانگا و مانهوا هستی.
            متن‌های ورودی را از ${settings.sourceLang} به ${settings.targetLang} با لحن «$toneGuide» ترجمه کن.
            قوانین:
            1. هیچ دیالوگی را حذف یا خلاصه نکن.
            2. لحن فریاد، نجوا، افکار و افکت صوتی (SFX) را حفظ کن.
            3. خروجی فقط و فقط یک آرایه JSON معتبر از رشته‌ها با دقیقاً همان تعداد عناصر ورودی باشد و هیچ توضیح اضافه‌ای ننویس.
            $glossaryNote
        """.trimIndent()

        val inputItems = blocks.mapIndexed { idx, block ->
            val typeTag = when (block.type) {
                BubbleType.SHOUT -> "[SHOUT] "
                BubbleType.SFX -> "[SFX] "
                BubbleType.NARRATION -> "[NARRATION] "
                BubbleType.THOUGHT -> "[THOUGHT] "
                BubbleType.DIALOGUE -> ""
            }
            mapOf("index" to idx, "text" to "$typeTag${block.originalText}")
        }

        val payload = mapOf(
            "model" to settings.customModelName.ifBlank { "gpt-4o-mini" },
            "temperature" to 0.3,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to gson.toJson(inputItems))
            )
        )

        val baseUrl = settings.customApiBaseUrl.trimEnd('/')
        val endpoint = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")

        if (settings.customApiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${settings.customApiKey.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("خطای API ترجمه (کد ${response.code}): ${bodyStr.take(200)}")
            }
            val root = JsonParser.parseString(bodyStr).asJsonObject
            val content = root
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()

            parseJsonArrayOutput(content, blocks.size, settings.targetLang)
        }
    }

    private fun parseJsonArrayOutput(
        rawContent: String,
        expectedCount: Int,
        targetLang: String
    ): List<String> {
        val startIdx = rawContent.indexOf('[')
        val endIdx = rawContent.lastIndexOf(']')
        if (startIdx == -1 || endIdx <= startIdx) {
            throw IOException("مدل خروجی JSON معتبر برنگرداند.")
        }
        val jsonSlice = rawContent.substring(startIdx, endIdx + 1)
        val arr: JsonArray = JsonParser.parseString(jsonSlice).asJsonArray

        return List(expectedCount) { i ->
            val text = if (i < arr.size() && !arr[i].isJsonNull) arr[i].asString else ""
            GlossaryAndPersianNormalizer.normalizePersian(text, targetLang)
        }
    }
}
// END_OF_FILE
