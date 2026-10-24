package com.pure.bubble

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

/** كل محركات الترجمة + كاش + قاموس + سلسلة احتياط (المحرك المختار ← Google ← بدون انترنت). */
class Translators {

    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3000
    }
    private val mlTranslators = HashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val mlReady = HashSet<String>()
    private val googleGate = Semaphore(5)

    @Volatile
    var lastError: String? = null

    // ------------------------------------------------------------------ public

    fun cached(src: String, tgt: String, text: String): String? =
        synchronized(cache) { cache["$src>$tgt|$text"] }

    /**
     * يترجم قائمة نصوص. العنصر null = فشل (ما نحط له صندوق بدل ما نحط ترجمة غلط).
     * context: نصوص سابقة من نفس الشاشة تساعد الذكاء الاصطناعي.
     */
    suspend fun translate(
        texts: List<String>, s: AppSettings, context: List<String>
    ): List<String?> {
        val out = arrayOfNulls<String>(texts.size)
        val glossary = parseGlossary(s.glossary)
        val todo = ArrayList<Int>()

        for ((i, t) in texts.withIndex()) {
            val g = glossary[t.trim().lowercase()]
            val c = cached(s.src, s.tgt, t)
            when {
                g != null -> out[i] = g
                c != null -> out[i] = c
                else -> todo.add(i)
            }
        }

        if (todo.isNotEmpty()) {
            val pending = todo.map { texts[it] }
            var results: List<String?> = List(pending.size) { null }

            val chain = ArrayList<String>()
            chain.add(s.engine)
            if (s.engine != Engines.GOOGLE) chain.add(Engines.GOOGLE)
            if (s.engine != Engines.OFFLINE) chain.add(Engines.OFFLINE)

            for (engine in chain) {
                val missing = results.indices.filter { results[it] == null }
                if (missing.isEmpty()) break
                val part = missing.map { pending[it] }
                val res: List<String?> = try {
                    when (engine) {
                        Engines.GEMINI -> if (s.geminiKey.isBlank()) throw IllegalStateException("مفتاح Gemini فاضي")
                        else gemini(part, s, context, glossary)
                        Engines.OPENAI -> if (s.oaiKey.isBlank() && !s.oaiBase.contains("localhost") && !s.oaiBase.contains("192.168"))
                            throw IllegalStateException("مفتاح الخدمة فاضي")
                        else openai(part, s, context, glossary)
                        Engines.OFFLINE -> offline(part, s)
                        else -> googleMany(part, s)
                    }
                } catch (e: Exception) {
                    lastError = "$engine: ${e.message ?: e.javaClass.simpleName}"
                    List(part.size) { null }
                }
                val merged = results.toMutableList()
                for ((k, idx) in missing.withIndex()) merged[idx] = res.getOrNull(k)
                results = merged
            }

            for ((k, idx) in todo.withIndex()) {
                val r = results[k]?.trim()
                if (!r.isNullOrEmpty()) {
                    out[idx] = r
                    synchronized(cache) { cache["${s.src}>${s.tgt}|${texts[idx]}"] = r }
                }
            }
        }
        return out.toList()
    }

    /** تحميل نموذج الترجمة بدون انترنت مسبقاً (حتى يشتغل الاحتياط). */
    suspend fun prepareOffline(src: String, tgt: String) {
        runCatching { ensureMl(src, tgt) }
    }

    fun close() {
        mlTranslators.values.forEach { runCatching { it.close() } }
        mlTranslators.clear()
        mlReady.clear()
    }

    // ------------------------------------------------------------------ glossary

    private fun parseGlossary(raw: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in raw.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
        }
        return m
    }

    // ------------------------------------------------------------------ http

    private fun http(
        url: String, method: String, body: ByteArray?, contentType: String?,
        headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) PureBubble/2")
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { ins ->
                val bo = ByteArrayOutputStream()
                ins.copyTo(bo)
                bo.toByteArray()
            } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ Google (مجاني)

    private suspend fun googleMany(texts: List<String>, s: AppSettings): List<String?> =
        coroutineScope {
            texts.map { t ->
                async(Dispatchers.IO) {
                    googleGate.withPermit {
                        var r: String? = null
                        for (attempt in 0 until 2) {
                            r = try { googleOne(t, s.src, s.tgt) } catch (e: Exception) { null }
                            if (r != null) break
                        }
                        r
                    }
                }
            }.map { it.await() }
        }

    private fun googleOne(text: String, src: String, tgt: String): String {
        val body = ("client=gtx&sl=" + Langs.google(src) + "&tl=" + Langs.google(tgt) +
                "&dt=t&dj=0&q=" + URLEncoder.encode(text, "UTF-8")).toByteArray(Charsets.UTF_8)
        val resp = http(
            "https://translate.googleapis.com/translate_a/single", "POST", body,
            "application/x-www-form-urlencoded;charset=UTF-8"
        )
        val root = JSONArray(resp)
        val segs = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            sb.append(seg.optString(0, ""))
        }
        val r = sb.toString().trim()
        if (r.isEmpty()) throw RuntimeException("رد فاضي")
        return r
    }

    // ------------------------------------------------------------------ LLM helpers

    private fun systemPrompt(s: AppSettings, glossary: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("You are a professional screen-text translation engine. ")
        sb.append("Translate every item from ${Langs.english(s.src)} into natural, fluent ${Langs.english(s.tgt)}. ")
        sb.append("Rules: translate ALL words (never leave source-language words untranslated, except personal names, brand names and code); ")
        sb.append("keep numbers, emojis and symbols; keep the meaning exactly, do not explain or add anything; ")
        sb.append("UI labels should be short and natural for an app interface; use the context only to disambiguate. ")
        sb.append("Return ONLY a JSON array of strings with exactly the same length and order as the input array.")
        if (glossary.isNotEmpty()) {
            sb.append(" Mandatory glossary (source=translation): ")
            sb.append(glossary.entries.joinToString("; ") { it.key + "=" + it.value })
        }
        return sb.toString()
    }

    private fun userPayload(texts: List<String>, context: List<String>): String {
        val o = JSONObject()
        if (context.isNotEmpty()) o.put("context_previous_texts", JSONArray(context.takeLast(8)))
        o.put("input", JSONArray(texts))
        return o.toString()
    }

    private fun parseArray(raw: String, expected: Int): List<String?> {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n').substringBeforeLast("```").trim()
        }
        val arr: JSONArray = try {
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    var found: JSONArray? = null
                    val it = o.keys()
                    while (it.hasNext()) {
                        val v = o.opt(it.next())
                        if (v is JSONArray) { found = v; break }
                    }
                    found ?: throw RuntimeException("بدون مصفوفة")
                }
                else -> JSONArray(t.substring(t.indexOf('['), t.lastIndexOf(']') + 1))
            }
        } catch (e: Exception) {
            throw RuntimeException("رد غير مفهوم من الذكاء الاصطناعي")
        }
        if (arr.length() != expected) throw RuntimeException("عدد الترجمات ${arr.length()} بدل $expected")
        return List(expected) { i ->
            val v = arr.opt(i)
            if (v is String) v else v?.toString()
        }
    }

    private suspend fun gemini(
        texts: List<String>, s: AppSettings, context: List<String>, glossary: Map<String, String>
    ): List<String?> = withContext(Dispatchers.IO) {
        val model = s.geminiModel.ifBlank { "gemini-flash-latest" }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(s, glossary)))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userPayload(texts, context))))
            ))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
            .toString().toByteArray(Charsets.UTF_8)
        val resp = http(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            "POST", body, "application/json",
            mapOf("x-goog-api-key" to s.geminiKey.trim()), 25000
        )
        val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        parseArray(text, texts.size)
    }

    private suspend fun openai(
        texts: List<String>, s: AppSettings, context: List<String>, glossary: Map<String, String>
    ): List<String?> = withContext(Dispatchers.IO) {
        val base = s.oaiBase.trim().trimEnd('/')
        val body = JSONObject()
            .put("model", s.oaiModel.trim())
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(s, glossary)))
                .put(JSONObject().put("role", "user").put("content", userPayload(texts, context))))
            .toString().toByteArray(Charsets.UTF_8)
        val headers = HashMap<String, String>()
        if (s.oaiKey.isNotBlank()) headers["Authorization"] = "Bearer " + s.oaiKey.trim()
        val resp = http("$base/chat/completions", "POST", body, "application/json", headers, 30000)
        val text = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        parseArray(text, texts.size)
    }

    // ------------------------------------------------------------------ offline (ML Kit)

    private fun ml(src: String, tgt: String) =
        mlTranslators.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            )
        }

    private suspend fun ensureMl(src: String, tgt: String) {
        val key = "$src>$tgt"
        if (mlReady.contains(key)) return
        ml(src, tgt).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        mlReady.add(key)
    }

    private suspend fun offline(texts: List<String>, s: AppSettings): List<String?> {
        ensureMl(s.src, s.tgt)
        val t = ml(s.src, s.tgt)
        return texts.map { x ->
            try { t.translate(x).await() } catch (e: Exception) { null }
        }
    }
}
