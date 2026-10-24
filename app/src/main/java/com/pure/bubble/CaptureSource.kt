package com.pure.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import java.nio.ByteOrder
import java.nio.IntBuffer

/** لقطة وحدة من الشاشة. نقرأ البكسلات مباشرة من الذاكرة بدون ما ننشئ Bitmap لكل لقطة. */
class Frame(private val image: Image) {
    val width: Int = image.width
    val height: Int = image.height
    private val plane = image.planes[0]
    private val rowPx: Int = plane.rowStride / plane.pixelStride
    private val ints: IntBuffer = plane.buffer.order(ByteOrder.nativeOrder()).asIntBuffer()

    fun close() {
        runCatching { image.close() }
    }

    private fun gray(v: Int): Int =
        ((v and 255) * 77 + ((v shr 8) and 255) * 151 + ((v shr 16) and 255) * 28) shr 8

    /** يعبّي مصفوفة الرمادي للمتتبع: الفهرس = عمود * الارتفاع + صف. */
    fun fillGray(t: ScrollTracker, out: IntArray) {
        val cols = t.cols
        val h = minOf(t.h, height)
        val xs = IntArray(cols) { minOf(t.colX(it), width - 1) }
        for (y in 0 until h) {
            val base = y * rowPx
            for (c in 0 until cols) {
                out[c * t.h + y] = gray(ints.get(base + xs[c]))
            }
        }
    }

    /** بصمة صغيرة (12×8 نقطة) لمنطقة، تستعمل لكشف تغيّر النص تحت الصندوق. */
    fun signature(l: Int, t: Int, r: Int, b: Int): IntArray? {
        val w = r - l
        val h = b - t
        if (w < 24 || h < 12) return null
        val sig = IntArray(12 * 8)
        var k = 0
        for (j in 0 until 8) {
            val y = (t + h * (2 * j + 1) / 16).coerceIn(0, height - 1)
            for (i in 0 until 12) {
                val x = (l + w * (2 * i + 1) / 24).coerceIn(0, width - 1)
                sig[k++] = gray(ints.get(y * rowPx + x))
            }
        }
        return sig
    }

    /** يقصّ منطقة من اللقطة إلى Bitmap قابل للتعديل. */
    fun crop(rect: Rect): Bitmap? {
        val l = rect.left.coerceIn(0, width - 1)
        val t = rect.top.coerceIn(0, height - 1)
        val r = rect.right.coerceIn(l + 1, width)
        val b = rect.bottom.coerceIn(t + 1, height)
        val w = r - l
        val h = b - t
        val buf = plane.buffer

        // المحاولة الأولى: ننسخ بس الصفوف المطلوبة. الثانية: كل اللقطة (احتياط).
        var stripe: Bitmap? = null
        var offsetY = 0
        try {
            buf.position(t * plane.rowStride)
            val bm = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
            bm.copyPixelsFromBuffer(buf)
            stripe = bm
        } catch (e: Exception) {
            stripe = null
        } finally {
            runCatching { buf.rewind() }
        }
        if (stripe == null) {
            try {
                val bm = Bitmap.createBitmap(rowPx, height, Bitmap.Config.ARGB_8888)
                bm.copyPixelsFromBuffer(buf)
                stripe = bm
                offsetY = t
            } catch (e: Exception) {
                return null
            } finally {
                runCatching { buf.rewind() }
            }
        }
        val src = stripe ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, -l.toFloat(), -offsetY.toFloat(), null)
        src.recycle()
        return out
    }
}

class CaptureSource(
    private val projection: MediaProjection,
    var width: Int,
    var height: Int,
    private var dpi: Int,
    handler: Handler
) {
    private var reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
    private val vd: VirtualDisplay = projection.createVirtualDisplay(
        "pure-capture", width, height, dpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface, null, handler
    )

    /** أحدث لقطة جديدة، أو null لو ما تغيّرت الشاشة من آخر مرة. */
    fun acquireNew(): Frame? {
        val img = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        return Frame(img)
    }

    /** تدوير الشاشة: ما ننشئ VirtualDisplay جديد (أندرويد 14 يمنع)، نغيّر حجمه ونبدّل السطح. */
    fun resize(w: Int, h: Int, newDpi: Int) {
        if (w == width && h == height) return
        val old = reader
        width = w
        height = h
        dpi = newDpi
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
        vd.resize(w, h, newDpi)
        vd.surface = reader.surface
        runCatching { old.close() }
    }

    fun release() {
        runCatching { vd.release() }
        runCatching { reader.close() }
    }
}
