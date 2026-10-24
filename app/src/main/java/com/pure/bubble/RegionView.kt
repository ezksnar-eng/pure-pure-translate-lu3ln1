package com.pure.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** شاشة تحديد منطقة الترجمة الجزئية: اسحب مستطيل. */
class RegionView(ctx: Context, private val onDone: (Rect?) -> Unit) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x66000000 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
    }

    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; ex = e.x; ey = e.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x; ey = e.y
            }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y
                dragging = false
                val r = Rect(
                    minOf(sx, ex).toInt(), minOf(sy, ey).toInt(),
                    maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt()
                )
                if (r.width() >= 48 * d && r.height() >= 28 * d) onDone(r) else onDone(null)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onDone(null)
                return true
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (dragging) {
            val r = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
            val path = Path().apply {
                addRect(0f, 0f, w, h, Path.Direction.CW)
                addRect(r, Path.Direction.CCW)
            }
            canvas.drawPath(path, dim)
            canvas.drawRect(r, border)
        } else {
            canvas.drawRect(0f, 0f, w, h, dim)
        }
        canvas.drawText("اسحب لتحديد منطقة الترجمة", w / 2f, h * 0.12f, text)
    }
}
