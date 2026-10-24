package com.pure.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** صندوق ترجمة واحد. الموقع بإحداثيات "المحتوى": الشاشة = contentTop + scroll. */
class Box(
    var left: Float,
    var contentTop: Float,
    val width: Float,
    val height: Float,
    val src: String,
    val text: String,
    val bg: Int,
    val layout: StaticLayout,
    val radius: Float,
    val padX: Float
) {
    var noise = 0
    var movedAt = 0L
    var sig: IntArray? = null
    var alpha = -1 // -1 = شفافية الطبقة الافتراضية
}

/** طبقة كاملة الشاشة ترسم الترجمة فوق النص الأصلي. ما تستقبل لمس. */
class TranslationLayer(ctx: Context) : View(ctx) {

    val boxes = ArrayList<Box>()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x9900639B.toInt()
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val d = resources.displayMetrics.density

    var coverAlpha = 245          // 0..255
    var shown = true
        set(v) { field = v; invalidate() }

    /** موقع المحتوى الحقيقي المقاس من اللقطات. */
    var scroll = 0f
        set(v) { field = v; invalidate() }

    var region: Rect? = null      // منطقة الترجمة الجزئية (تنرسم بخط متقطع)
        set(v) { field = v; invalidate() }

    // ---- تنبؤ بالحركة: الصناديق تتحرك كل فريم بسرعة التمرير بدل ما تقفز كل 100 مللي ثانية
    @Volatile
    var velocity = 0f             // بكسل لكل مللي ثانية (موجب = المحتوى ينزل)
        private set
    private var stamp = 0L
    private var decay = false     // true = تمرير سريع ما قدرنا نتتبعه: نكمل بتباطؤ طبيعي

    fun setMotion(v: Float, at: Long, decaying: Boolean) {
        velocity = v
        stamp = at
        decay = decaying
        invalidate()
    }

    fun effScroll(now: Long): Float {
        val v = velocity
        if (v == 0f) return scroll
        val dt = (now - stamp).coerceAtLeast(0L).toFloat()
        val off = if (decay) {
            val tau = 260f
            v * tau * (1f - exp(-dt / tau))
        } else {
            // تنبؤ قصير، ويتلاشى إذا وقفت الحركة بدون ما تجي لقطة جديدة
            val base = v * min(dt + 30f, 90f)
            if (dt > 90f) base * max(0f, 1f - (dt - 90f) / 160f) else base
        }
        val cap = height.coerceAtLeast(1) * 1.5f
        return scroll + off.coerceIn(-cap, cap)
    }

    fun hasBoxes() = boxes.isNotEmpty()

    fun add(list: List<Box>) {
        if (list.isEmpty()) return
        boxes.addAll(list)
        invalidate()
    }

    fun remove(b: Box) {
        boxes.remove(b)
        invalidate()
    }

    fun clear() {
        boxes.clear()
        velocity = 0f
        invalidate()
    }

    /** الموقع "المنطقي" (حسب القياس الأخير). */
    fun screenTop(b: Box) = b.contentTop + scroll

    /** مستطيلات الصناديق كما هي معروضة الحين على الشاشة [l,t,r,b] مع هامش. */
    fun screenRects(inflate: Int, screenH: Int, now: Long): List<IntArray> {
        val es = effScroll(now)
        val out = ArrayList<IntArray>(boxes.size)
        for (b in boxes) {
            val t = b.contentTop + es
            if (t > screenH || t + b.height < 0) continue
            out.add(
                intArrayOf(
                    (b.left - inflate).toInt(), (t - inflate).toInt(),
                    (b.left + b.width + inflate).toInt(), (t + b.height + inflate).toInt()
                )
            )
        }
        return out
    }

    /** نشيل الصناديق البعيدة جداً (أكثر من شاشتين) حتى ما تكبر القائمة. */
    fun prune(screenH: Int) {
        val it = boxes.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val t = screenTop(b)
            if (t > screenH * 3f || t + b.height < -screenH * 2f) it.remove()
        }
    }

    /**
     * يبني صندوق من مستطيل النص الأصلي. rect بإحداثيات الشاشة وقت اللقطة،
     * scrollAtCapture = قيمة scroll وقت اللقطة.
     */
    fun build(
        rect: Rect, scrollAtCapture: Float, src: String, text: String, bg: Int, fg: Int,
        lines: Int, screenW: Int, mode: Mode
    ): Box {
        val manga = mode == Mode.MANGA
        val novel = mode == Mode.NOVEL

        // المانجا: نغطي أكثر (حواف الحروف) ونكبّر الخط ليملي الفقاعة
        val pad = when {
            manga -> 5f * d
            novel -> 2f * d
            else -> 3f * d
        }
        var w = rect.width() + 2f * pad
        w = max(w, 44f * d)
        w = min(w, screenW - 6f)
        val innerW = (w - 2f * pad).toInt().coerceAtLeast(24)
        val h0 = rect.height() + 2f * pad

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = fg
        }
        val pitch = rect.height().toFloat() / max(1, lines)
        val factor = when {
            manga -> 0.98f
            novel -> 0.86f
            else -> 0.78f
        }
        val maxSize = if (manga) 30f * d else 24f * d
        var size = (pitch * factor).coerceIn(11f * d, maxSize)
        val minSize = if (manga) 10f * d else 9f * d
        val align = if (novel) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER

        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.ANYRTL_LTR)
                .setIncludePad(false)
                .setLineSpacing(0f, if (novel) 1.15f else 1.0f)
                .build()
            if (layout.height <= h0 || size <= minSize) break
            size -= d * 0.75f
        }

        val growth = if (novel) 1.6f else 1.9f
        val boxH = min(max(h0, layout.height + 2f * pad), h0 * growth + 6f * d)
        val left = (rect.exactCenterX() - w / 2f).coerceIn(2f, max(2f, screenW - w - 2f))
        val top = if (novel) rect.top - pad else rect.exactCenterY() - boxH / 2f
        val radius = if (manga) min(min(w, boxH) * 0.28f, 22f * d) else 6f * d
        val box = Box(left, top - scrollAtCapture, w, boxH, src, text, bg, layout, radius, pad)
        if (manga || novel) box.alpha = 255 // محتوى ثابت: تغطية كاملة بدون شبح للنص الأصلي
        return box
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val now = SystemClock.uptimeMillis()
        val es = effScroll(now)
        val h = height.toFloat()
        for (b in boxes) {
            val top = b.contentTop + es
            if (top > h || top + b.height < 0f) continue
            val a = if (b.alpha >= 0) b.alpha else coverAlpha
            bgPaint.color = (a shl 24) or (b.bg and 0xFFFFFF)
            canvas.drawRoundRect(b.left, top, b.left + b.width, top + b.height, b.radius, b.radius, bgPaint)
            canvas.save()
            canvas.translate(b.left + b.padX, top + (b.height - b.layout.height) / 2f)
            b.layout.draw(canvas)
            canvas.restore()
        }
        region?.let {
            canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), borderPaint)
        }
        // نكمل الرسم كل فريم طول ما في حركة
        if (velocity != 0f && now - stamp < 900L && abs(velocity) > 0.02f) postInvalidateOnAnimation()
    }
}
