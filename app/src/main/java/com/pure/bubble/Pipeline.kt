package com.pure.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * قلب التطبيق. فكرته:
 *  - الصناديق ما تنخفي أبداً أثناء الالتقاط. اللقطة تحتوي الصناديق، فنتجاهل مناطقها (mask)
 *    ونقرأ النص الجديد بس (خارج الصناديق).
 *  - لما الصفحة تتمرر نحرّك الصناديق بنفس مقدار التمرير فوراً (ScrollTracker).
 *  - الصندوق شبه معتم (96%)، والنسبة الصغيرة اللي تتسرب من النص الأصلي نستخدمها لنعرف
 *    إذا تغيّر النص تحت الصندوق (مثل ترجمة حوار جديد)، فنشيل الصندوق ونعيد القراءة.
 */
class Pipeline(
    private val scope: CoroutineScope,
    private var source: CaptureSource,
    private val layer: TranslationLayer,
    private val translators: Translators,
    private val ocr: Ocr,
    private val settingsProvider: () -> AppSettings,
    private val excludeRects: () -> List<Rect>,
    private val toast: (String) -> Unit,
    private val onBusy: (Boolean) -> Unit
) {
    private class Dropped(val rect: Rect, val key: String, val noise: Int, val at: Long)

    private var screenW = source.width
    private var screenH = source.height
    private var tracker = ScrollTracker(screenW, screenH, 10)

    private var prevGray = tracker.newGray()
    private var curGray = tracker.newGray()
    private var refGray = tracker.newGray()
    private var prevMask = tracker.newMask()
    private var curMask = tracker.newMask()
    private var refMask = tracker.newMask()

    private var lastFrame: Frame? = null
    private var baselineValid = false

    private var scroll = 0f
    private var hintDy = 0
    private var vel = 0f
    private var lastScrollAt = 0L
    private var lastFrameAt = 0L
    private var lost = false
    private var lostSince = 0L

    private var dirty: Rect? = null
    private var lastMotionAt = 0L
    private var lastOcrAt = 0L
    private var retryNotBefore = 0L
    private var ocrBusy = false
    private var forceFull = false
    private var epoch = 0

    private val dropped = ArrayList<Dropped>()
    private val ctxTexts = ArrayDeque<String>()
    private var errorToastAt = 0L

    private var job: Job? = null

    @Volatile
    var paused = false
        private set

    /** تعليق مؤقت أثناء ظهور قائمة/محدد منطقة فوق الشاشة (الصناديق تبقى ظاهرة). */
    @Volatile
    var suspended = false
        private set
    var region: Rect? = null
        private set

    val busy: Boolean get() = ocrBusy

    // ------------------------------------------------------------------ control

    fun start() {
        job?.cancel()
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastFrame?.close()
        lastFrame = null
    }

    /** إيقاف مؤقت (المستخدم ضغط الفقاعة): نخفي الصناديق ونوقف الشغل. */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) {
            layer.shown = false
        } else {
            clearBoxes()
            layer.shown = true
            resetBaseline()
            markAllDirty()
        }
    }

    fun setSuspended(v: Boolean) {
        if (suspended == v) return
        suspended = v
        if (!v) resetBaseline()
    }

    /** نستدعيها بعد ما يختفي أي شي فوق الشاشة (قائمة، تحديد منطقة) حتى ما يتحسب كتغيير. */
    fun resetBaseline() {
        baselineValid = false
        lost = false
    }

    fun clearBoxes() {
        epoch++
        layer.clear()
        scroll = 0f
        layer.scroll = 0f
        dropped.clear()
    }

    fun hasBoxes() = layer.hasBoxes()

    fun markAllDirty() {
        dirty = Rect(0, 0, screenW, screenH)
        retryNotBefore = 0L
    }

    fun setRegion(r: Rect?) {
        region = r
        layer.region = r
        // الصناديق خارج المنطقة تروح
        val rr = r ?: return
        val it = layer.boxes.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val top = layer.screenTop(b)
            val box = Rect(b.left.toInt(), top.toInt(), (b.left + b.width).toInt(), (top + b.height).toInt())
            if (!Rect.intersects(box, rr)) it.remove()
        }
        layer.invalidate()
        dirty = Rect(rr)
        retryNotBefore = 0L
    }

    /** ترجمة الشاشة كاملة مرة وحدة (وضع الشاشة الكاملة). */
    fun requestFull() {
        forceFull = true
        retryNotBefore = 0L
    }

    /** تدوير الشاشة. */
    fun onResize(w: Int, h: Int) {
        screenW = w
        screenH = h
        tracker = ScrollTracker(w, h, 10)
        prevGray = tracker.newGray(); curGray = tracker.newGray(); refGray = tracker.newGray()
        prevMask = tracker.newMask(); curMask = tracker.newMask(); refMask = tracker.newMask()
        lastFrame?.close()
        lastFrame = null
        baselineValid = false
        lost = false
        clearBoxes()
        markAllDirty()
    }

    // ------------------------------------------------------------------ loop

    private fun now() = SystemClock.uptimeMillis()

    private suspend fun loop() {
        while (scope.isActive) {
            val t0 = now()
            if (!paused && !suspended) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val n = now()
                    if (n - errorToastAt > 8000) {
                        errorToastAt = n
                        toast("خطأ: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            val moving = now() - lastMotionAt < 400L
            val interval = when {
                settingsProvider().mode == Mode.REALTIME -> 40L
                moving -> 45L
                else -> 90L
            }
            delay(max(8L, interval - (now() - t0)))
        }
    }

    private suspend fun tick() {
        val nf = source.acquireNew()
        val n = now()
        if (nf != null) {
            lastFrameAt = n
            processFrame(nf)
        } else if (n - lastFrameAt > 250L) {
            // الشاشة هدت ولا جاءت لقطات جديدة
            if (lost) {
                lastFrame?.let { tryResync(n, it) }
            } else if (vel != 0f) {
                vel = 0f
                layer.setMotion(0f, n, false)
            }
        }
        maybeOcr()
    }

    // ------------------------------------------------------------------ tracking

    private fun swapPrevCur() {
        val g = prevGray; prevGray = curGray; curGray = g
        val m = prevMask; prevMask = curMask; curMask = m
    }

    private suspend fun processFrame(nf: Frame) {
        val s = settingsProvider()
        val now = now()
        val inflate = 10 + minOf(160, (abs(layer.velocity) * 80f).toInt())
        val boxRects = layer.screenRects(inflate, screenH, now)
        val excl = excludeRects()

        // نخزن اللقطة الجديدة (ونقفل القديمة). الشغل الثقيل تحت يقرأ nf قبل ما تتبدل.
        val old = lastFrame

        withContext(Dispatchers.Default) {
            nf.fillGray(tracker, curGray)
            java.util.Arrays.fill(curMask, false)
            for (r in boxRects) tracker.markRect(curMask, r[0], r[1], r[2], r[3])
            for (r in excl) tracker.markRect(curMask, r.left - 8, r.top - 8, r.right + 8, r.bottom + 8)
        }

        lastFrame = nf
        old?.close()

        if (!baselineValid) {
            baselineValid = true
            swapPrevCur()
            if (s.mode.continuous && dirty == null) markAllDirty()
            return
        }

        val hint = hintDy
        val lostNow = lost
        val res = withContext(Dispatchers.Default) {
            tracker.compare(prevGray, prevMask, curGray, curMask, if (lostNow) 0 else hint)
        }

        if (lostNow) {
            // ننتظر تهدأ الشاشة، بعدين نحاول نرجّع الصناديق لمكانها الصحيح
            if (res.kind != ScrollTracker.STATIC) {
                lastMotionAt = now
                swapPrevCur()
                if (now - lostSince > 6000) giveUpLost()
                return
            }
            resyncWithCur(now)
            swapPrevCur()
            return
        }

        when (res.kind) {
            ScrollTracker.STATIC -> {
                hintDy = 0
                if (vel != 0f) {
                    vel = 0f
                    layer.setMotion(0f, now, false)
                }
                // ما صار تغيير خارج الصناديق: نتأكد أن النص تحت الصناديق ما تغيّر
                if (s.mode.detectsChange) checkLeaks(nf, s, now)
            }
            ScrollTracker.SCROLL -> applyScroll(res.dy, now)
            ScrollTracker.PARTIAL -> {
                res.dirty?.let { addDirty(Rect(it[0] - 30, it[1] - 30, it[2] + 30, it[3] + 30)) }
                if (s.mode.detectsChange) checkLeaks(nf, s, now)
            }
            else -> { // PAGE
                lastMotionAt = now
                if (layer.hasBoxes()) {
                    System.arraycopy(prevGray, 0, refGray, 0, prevGray.size)
                    System.arraycopy(prevMask, 0, refMask, 0, prevMask.size)
                    lost = true
                    lostSince = now
                    // ما نخفي الصناديق: تكمل بنفس اتجاه التمرير وتتباطأ لين تهدأ الشاشة
                    layer.setMotion(vel, now, true)
                } else {
                    markAllDirty()
                }
            }
        }
        swapPrevCur()
    }

    /** الشاشة هدت بعد تمرير سريع: نقارن بآخر لقطة موثوقة ونرجّع الصناديق لمكانها، أو نمسحها لو الصفحة تغيّرت. */
    private suspend fun resyncWithCur(now: Long) {
        val wide = withContext(Dispatchers.Default) {
            tracker.compareWide(refGray, refMask, curGray, curMask)
        }
        if (wide.kind == ScrollTracker.SCROLL || wide.kind == ScrollTracker.STATIC) {
            lost = false
            layer.shown = !paused
            vel = 0f
            layer.setMotion(0f, now, false)
            if (wide.kind == ScrollTracker.SCROLL) applyScroll(wide.dy, now)
        } else {
            giveUpLost()
        }
    }

    /** نفس resyncWithCur بس من آخر لقطة محفوظة (لما ما تجي لقطات جديدة). */
    private suspend fun tryResync(now: Long, f: Frame) {
        val rects = layer.screenRects(80, screenH, now)
        withContext(Dispatchers.Default) {
            f.fillGray(tracker, curGray)
            java.util.Arrays.fill(curMask, false)
            for (r in rects) tracker.markRect(curMask, r[0], r[1], r[2], r[3])
        }
        resyncWithCur(now)
        // نبدأ مقارنة جديدة من هذي اللقطة
        swapPrevCur()
    }

    private fun giveUpLost() {
        lost = false
        layer.shown = !paused
        clearBoxes()
        markAllDirty()
    }

    private fun applyScroll(dy: Int, now: Long) {
        scroll += dy
        layer.scroll = scroll
        hintDy = dy
        lastMotionAt = now
        val dt = (now - lastScrollAt).coerceIn(16L, 300L)
        val inst = dy.toFloat() / dt
        vel = if (lastScrollAt != 0L && now - lastScrollAt < 300L) 0.4f * vel + 0.6f * inst else dy / 90f
        lastScrollAt = now
        layer.setMotion(vel, now, false)
        for (b in layer.boxes) {
            b.movedAt = now
            b.sig = null
        }
        // المنطقة اللي انكشفت
        val d = dirty
        if (d != null) {
            d.offset(0, dy)
            if (!d.intersect(0, 0, screenW, screenH)) dirty = null
        }
        val margin = 90
        if (dy > 0) addDirty(Rect(0, 0, screenW, dy + margin))
        else addDirty(Rect(0, screenH + dy - margin, screenW, screenH))
        layer.prune(screenH)
    }

    private fun addDirty(r: Rect) {
        val rr = Rect(r)
        if (!rr.intersect(0, 0, screenW, screenH)) return
        val d = dirty
        if (d == null) dirty = rr else d.union(rr)
    }

    // ------------------------------------------------------------------ تغيّر النص تحت الصندوق

    private fun checkLeaks(f: Frame, s: AppSettings, now: Long) {
        if (s.coverAlpha >= 99) return
        val it = layer.boxes.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.noise >= 3) continue
            if (now - b.movedAt < 450) continue
            val top = layer.screenTop(b)
            if (top < 0 || top + b.height > screenH) continue
            val l = (b.left + 6).toInt()
            val t = (top + 4).toInt()
            val r = (b.left + b.width - 6).toInt()
            val bt = (top + b.height - 4).toInt()
            val sig = f.signature(l, t, r, bt) ?: continue
            val ref = b.sig
            if (ref == null) {
                b.sig = sig
                continue
            }
            var diff = 0
            for (i in sig.indices) if (abs(sig[i] - ref[i]) >= 2) diff++
            val limit = 6 + b.noise * 5
            if (diff >= limit) {
                val rect = Rect(b.left.toInt(), top.toInt(), (b.left + b.width).toInt(), (top + b.height).toInt())
                dropped.add(Dropped(rect, normKey(b.src), b.noise, now))
                it.remove()
                addDirty(Rect(rect.left - 20, rect.top - 20, rect.right + 20, rect.bottom + 20))
                retryNotBefore = 0L
                lastOcrAt = 0L
                layer.invalidate()
            } else {
                b.sig = sig
            }
        }
    }

    private fun normKey(t: String): String =
        t.lowercase().filter { it.isLetterOrDigit() }

    // ------------------------------------------------------------------ OCR

    private suspend fun maybeOcr() {
        if (paused || ocrBusy || lost) return
        val f = lastFrame ?: return
        val s = settingsProvider()
        val now = now()
        if (now < retryNotBefore) return

        val rect: Rect
        var oneShot = false

        if (forceFull) {
            forceFull = false
            oneShot = true
            rect = Rect(0, 0, screenW, screenH)
        } else {
            if (s.mode == Mode.FULL) return
            val d = dirty ?: return
            val rt = s.mode == Mode.REALTIME
            val settle = if (rt) 100L else 300L
            val gap = if (rt) 220L else 550L
            if (now - lastOcrAt < gap) return
            if (now - lastMotionAt < settle && now - lastOcrAt < 1000L) return
            rect = Rect(d)
        }

        if (s.mode == Mode.PARTIAL && !oneShot) {
            val reg = region ?: return
            if (!rect.intersect(reg)) {
                dirty = null
                return
            }
        }
        val r = rect
        if (r.width() < 20 || r.height() < 12) {
            dirty = null
            return
        }

        if (!oneShot) dirty = null
        ocrBusy = true
        lastOcrAt = now
        onBusy(true)

        val epoch0 = epoch
        val scroll0 = scroll
        val es = layer.effScroll(now)
        val maskBoxes = layer.boxes.map { b ->
            val top = b.contentTop + es
            Pair(Rect(b.left.toInt() - 4, top.toInt() - 4, (b.left + b.width).toInt() + 4, (top + b.height).toInt() + 4), b.bg)
        }
        val excl = excludeRects()
        val bmp = withContext(Dispatchers.Default) { f.crop(r) }
        if (bmp == null) {
            ocrBusy = false
            onBusy(false)
            return
        }
        scope.launch { runOcr(bmp, r, s, epoch0, scroll0, maskBoxes, excl, oneShot) }
    }

    private suspend fun runOcr(
        bmp: Bitmap, origin: Rect, s: AppSettings, epoch0: Int, scroll0: Float,
        masks: List<Pair<Rect, Int>>, excl: List<Rect>, oneShot: Boolean
    ) {
        var retryRects: Rect? = null
        try {
            // 1) نمسح مناطق الصناديق الحالية من الصورة حتى ما تنقرأ الترجمة نفسها
            withContext(Dispatchers.Default) {
                val cv = Canvas(bmp)
                val p = Paint()
                for ((r, bg) in masks) {
                    p.color = 0xFF000000.toInt() or (bg and 0xFFFFFF)
                    cv.drawRect(
                        (r.left - origin.left).toFloat(), (r.top - origin.top).toFloat(),
                        (r.right - origin.left).toFloat(), (r.bottom - origin.top).toFloat(), p
                    )
                }
                p.color = 0xFF808080.toInt()
                for (r in excl) {
                    cv.drawRect(
                        (r.left - origin.left - 6).toFloat(), (r.top - origin.top - 6).toFloat(),
                        (r.right - origin.left + 6).toFloat(), (r.bottom - origin.top + 6).toFloat(), p
                    )
                }
            }

            // 2) قراءة النص
            val read = ocr.read(bmp, s.src, s.mode.joinsLines)

            // 3) فلترة + ألوان الخلفية
            class Cand(val rect: Rect, val item: OcrItem, val bg: Int)

            val cands = ArrayList<Cand>()
            for (it in read) {
                val rect = Rect(it.rect)
                rect.offset(origin.left, origin.top)
                if (Ocr.looksLikeTarget(it.text, s.tgt)) continue
                if (!oneShot && (rect.top <= 3 || rect.bottom >= screenH - 3)) continue
                var covered = false
                val area = max(1, rect.width() * rect.height())
                for ((m, _) in masks) {
                    val inter = Rect(rect)
                    if (inter.intersect(m) && inter.width() * inter.height() * 100 / area > 35) {
                        covered = true
                        break
                    }
                }
                if (covered) continue
                val bg = Ocr.sampleBg(bmp, it.rect)
                cands.add(Cand(rect, it, bg))
            }
            bmp.recycle()
            if (cands.isEmpty()) return

            // 4) ترجمة
            val texts = cands.map { it.item.text }
            val translated = translators.translate(texts, s, ctxTexts.toList())
            if (epoch0 != epoch || paused) return

            val fresh = ArrayList<Box>()
            val n = now()
            for ((i, c) in cands.withIndex()) {
                val tr = translated[i]
                if (tr == null) {
                    val rr = retryRects
                    if (rr == null) retryRects = Rect(c.rect) else rr.union(c.rect)
                    continue
                }
                if (tr.equals(c.item.text, ignoreCase = true)) continue
                val box = layer.build(
                    c.rect, scroll0, s.src, c.item.text, c.bg, Ocr.contrast(c.bg), c.item.lines, screenW, s.mode
                )
                box.movedAt = n
                val key = normKey(c.item.text)
                val d = dropped.firstOrNull { n - it.at < 8000 && it.key == key && Rect.intersects(it.rect, c.rect) }
                if (d != null) {
                    box.noise = d.noise + 1
                    dropped.remove(d)
                }
                fresh.add(box)
                ctxTexts.addLast(c.item.text)
                while (ctxTexts.size > 12) ctxTexts.removeFirst()
            }
            dropped.removeAll { n - it.at > 8000 }
            layer.add(fresh)

            val failedRect = retryRects
            if (failedRect != null) {
                // فشلت بعض الترجمات (انترنت؟): نعيد المحاولة بعد شوي
                retryNotBefore = n + 2500
                addDirty(failedRect)
                val last = translators.lastError
                if (n - errorToastAt > 10000 && last != null) {
                    errorToastAt = n
                    toast("تعذرت الترجمة: $last")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val n = now()
            retryNotBefore = n + 3000
            addDirty(origin)
            if (n - errorToastAt > 8000) {
                errorToastAt = n
                toast("خطأ بالقراءة: ${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
            ocrBusy = false
            onBusy(false)
        }
    }
}
