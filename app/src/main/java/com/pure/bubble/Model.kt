package com.pure.bubble

import android.content.Context

data class Lang(val name: String, val code: String)

object Langs {
    // لغات المصدر محدودة باللغات اللي يقدر ML Kit يقرأها (OCR)
    val sources = listOf(
        Lang("الإنجليزية", "en"),
        Lang("اليابانية", "ja"),
        Lang("الكورية", "ko"),
        Lang("الصينية", "zh"),
        Lang("الفرنسية", "fr"),
        Lang("الإسبانية", "es"),
        Lang("الألمانية", "de"),
        Lang("الإيطالية", "it"),
        Lang("التركية", "tr"),
        Lang("الإندونيسية", "id")
    )

    val targets = listOf(
        Lang("العربية", "ar"),
        Lang("الإنجليزية", "en"),
        Lang("الفارسية", "fa"),
        Lang("التركية", "tr"),
        Lang("الفرنسية", "fr"),
        Lang("الإسبانية", "es"),
        Lang("الألمانية", "de"),
        Lang("الروسية", "ru"),
        Lang("الإيطالية", "it"),
        Lang("الإندونيسية", "id")
    )

    val cjk = setOf("ja", "ko", "zh")

    fun english(code: String): String = when (code) {
        "ar" -> "Arabic"; "en" -> "English"; "fa" -> "Persian"; "tr" -> "Turkish"
        "fr" -> "French"; "es" -> "Spanish"; "de" -> "German"; "ru" -> "Russian"
        "it" -> "Italian"; "id" -> "Indonesian"; "ja" -> "Japanese"; "ko" -> "Korean"
        "zh" -> "Chinese"; else -> code
    }

    /** رمز اللغة عند Google Translate. */
    fun google(code: String): String = if (code == "zh") "zh-CN" else code
}

enum class Mode(val key: String) {
    FULL("full"),       // ترجمة الشاشة الكاملة (مرة وحدة عند الضغط)
    PARTIAL("partial"), // ترجمة جزئية (منطقة تحددها)
    AUTO("auto"),       // ترجمة تلقائية
    REALTIME("rt"),     // ترجمة في الوقت الحقيقي
    MANGA("manga"),     // مانجا / مانهوا / ويبتون: الترجمة تلزق بالفقاعة
    NOVEL("novel");     // روايات ونصوص طويلة

    /** يشتغل بشكل مستمر (يقرأ النص الجديد لحاله). */
    val continuous: Boolean get() = this != FULL

    /** كشف تغيّر النص تحت الصندوق (حوارات، ترجمة فيديو). ما نحتاجه بالمانجا والروايات لأن المحتوى ثابت. */
    val detectsChange: Boolean get() = this == AUTO || this == REALTIME || this == PARTIAL

    /** دمج أسطر الفقرة بجملة وحدة. */
    val joinsLines: Boolean get() = this == MANGA || this == NOVEL

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: AUTO
    }
}

object Engines {
    const val GOOGLE = "google"
    const val GEMINI = "gemini"
    const val OPENAI = "openai"
    const val OFFLINE = "offline"

    val list = listOf(
        GOOGLE to "Google Translate (مجاني، بدون مفتاح)",
        GEMINI to "Gemini (أدق، يحتاج مفتاح مجاني)",
        OPENAI to "OpenAI / Groq / DeepSeek / Ollama (مفتاحك)",
        OFFLINE to "بدون انترنت (Google ML Kit)"
    )
}

data class AppSettings(
    val src: String = "en",
    val tgt: String = "ar",
    val mode: Mode = Mode.AUTO,
    val engine: String = Engines.GOOGLE,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-flash-latest",
    val oaiBase: String = "https://api.groq.com/openai/v1",
    val oaiKey: String = "",
    val oaiModel: String = "llama-3.3-70b-versatile",
    val glossary: String = "",
    val coverAlpha: Int = 96,    // شفافية صندوق الترجمة 60..100
    val bubbleAlpha: Int = 100   // شفافية الفقاعة العائمة 30..100
)

object Prefs {
    private const val FILE = "pure_bubble2"

    fun load(c: Context): AppSettings {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = AppSettings()
        return AppSettings(
            src = p.getString("src", d.src) ?: d.src,
            tgt = p.getString("tgt", d.tgt) ?: d.tgt,
            mode = Mode.of(p.getString("mode", d.mode.key)),
            engine = p.getString("engine", d.engine) ?: d.engine,
            geminiKey = p.getString("gkey", "") ?: "",
            geminiModel = p.getString("gmodel", d.geminiModel) ?: d.geminiModel,
            oaiBase = p.getString("obase", d.oaiBase) ?: d.oaiBase,
            oaiKey = p.getString("okey", "") ?: "",
            oaiModel = p.getString("omodel", d.oaiModel) ?: d.oaiModel,
            glossary = p.getString("glossary", "") ?: "",
            coverAlpha = p.getInt("cover", d.coverAlpha),
            bubbleAlpha = p.getInt("bubble", d.bubbleAlpha)
        )
    }

    fun save(c: Context, s: AppSettings) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("src", s.src)
            .putString("tgt", s.tgt)
            .putString("mode", s.mode.key)
            .putString("engine", s.engine)
            .putString("gkey", s.geminiKey)
            .putString("gmodel", s.geminiModel)
            .putString("obase", s.oaiBase)
            .putString("okey", s.oaiKey)
            .putString("omodel", s.oaiModel)
            .putString("glossary", s.glossary)
            .putInt("cover", s.coverAlpha)
            .putInt("bubble", s.bubbleAlpha)
            .apply()
    }
}
