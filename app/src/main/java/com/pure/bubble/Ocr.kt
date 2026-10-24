package com.pure.bubble

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** عنصر نص واحد يتترجم ويتغطى بصندوق. rect بإحداثيات الصورة اللي انقرأت. */
class OcrItem(val rect: Rect, val text: String, val lines: Int)

class Ocr {
    private val recognizers = HashMap<String, TextRecognizer>()

    private fun recognizer(src: String): TextRecognizer = recognizers.getOrPut(src) {
        when (src) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    suspend fun read(bmp: Bitmap, src: String, joinAll: Boolean = false): List<OcrItem> {
        val result = recognizer(src).process(InputImage.fromBitmap(bmp, 0)).await()
        val cjk = Langs.cjk.contains(src)
        val out = ArrayList<OcrItem>()

        for (block in result.textBlocks) {
            val lines = block.lines.filter { it.boundingBox != null && it.text.isNotBlank() }
            if (lines.isEmpty()) continue

            val words = lines.map { l -> l.text.trim().split(Regex("\\s+")).size }
            val avgWords = words.average()
            val punct = lines.any { l -> l.text.trim().last() in ".,!?:;،؟。！？" }
            // قوائم/أزرار: أسطر قصيرة كل وحدة لحالها
            val separate = !joinAll && !cjk && lines.size >= 2 && avgWords <= 2.2 && !punct

            if (separate) {
                for (l in lines) addItem(out, Rect(l.boundingBox!!), l.text.trim(), 1, cjk)
            } else {
                val rect = block.boundingBox?.let { Rect(it) } ?: continue
                val sb = StringBuilder()
                for ((i, l) in lines.withIndex()) {
                    var t = l.text.trim()
                    if (i > 0) {
                        if (sb.endsWith("-") && !cjk) sb.setLength(sb.length - 1)
                        else if (!cjk) sb.append(' ')
                    }
                    sb.append(t)
                }
                addItem(out, rect, sb.toString().trim(), lines.size, cjk)
            }
        }
        return out
    }

    private fun addItem(out: MutableList<OcrItem>, rect: Rect, text: String, lines: Int, cjk: Boolean) {
        if (text.isEmpty()) return
        if (text.none { it.isLetter() }) return           // أرقام/رموز بس
        if (!cjk && text.count { it.isLetter() } < 2) return // حرف وحيد = ضجيج
        if (rect.width() < 6 || rect.height() < 6) return
        out.add(OcrItem(rect, text, lines))
    }

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }

    companion object {
        /** لون الخلفية حول النص (الوسيط من نقاط على حدود المستطيل). */
        fun sampleBg(bmp: Bitmap, r: Rect): Int {
            val pad = 3
            val l = (r.left - pad).coerceIn(0, bmp.width - 1)
            val t = (r.top - pad).coerceIn(0, bmp.height - 1)
            val rr = (r.right + pad).coerceIn(0, bmp.width - 1)
            val bb = (r.bottom + pad).coerceIn(0, bmp.height - 1)
            val reds = ArrayList<Int>()
            val greens = ArrayList<Int>()
            val blues = ArrayList<Int>()
            val n = 8
            for (i in 0..n) {
                val x = l + (rr - l) * i / n
                val y = t + (bb - t) * i / n
                for (p in intArrayOf(bmp.getPixel(x, t), bmp.getPixel(x, bb), bmp.getPixel(l, y), bmp.getPixel(rr, y))) {
                    reds.add(Color.red(p)); greens.add(Color.green(p)); blues.add(Color.blue(p))
                }
            }
            reds.sort(); greens.sort(); blues.sort()
            val m = reds.size / 2
            return Color.rgb(reds[m], greens[m], blues[m])
        }

        fun contrast(bg: Int): Int {
            val lum = (0.299f * Color.red(bg) + 0.587f * Color.green(bg) + 0.114f * Color.blue(bg)) / 255f
            return if (lum > 0.55f) 0xFF111111.toInt() else 0xFFF8F8F8.toInt()
        }

        /** هل النص أصلاً بلغة الهدف (ترجمتنا القديمة أو نص عربي أصلي)؟ */
        fun looksLikeTarget(text: String, tgt: String): Boolean {
            if (tgt != "ar" && tgt != "fa") return false
            var letters = 0
            var arabic = 0
            for (ch in text) {
                if (!ch.isLetter()) continue
                letters++
                if (ch.code in 0x0600..0x06FF || ch.code in 0x0750..0x077F || ch.code in 0xFB50..0xFEFF) arabic++
            }
            return letters > 0 && arabic * 2 > letters
        }
    }
}
