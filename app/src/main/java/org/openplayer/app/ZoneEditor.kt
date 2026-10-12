package org.openplayer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Dikey kaydırma şeritlerinin önizlemesi: iki ayırıcı çizgi sürüklenerek sol/orta/sağ şeritlerin
 * sınırları ayarlanır. Çizgiler çakışırsa orta şerit yoktur.
 */
class ZoneEditor(context: Context, private val prefs: GesturePrefs) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(3).toFloat() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(14).toFloat()
        color = 0xFFFFFFFF.toInt()
    }
    private val rect = RectF()
    private var dragging = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, context.dp(150))
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dp(20).toFloat()
        val w = width - pad * 2
        val h = height.toFloat()
        val a = pad + w * prefs.split1
        val b = pad + w * prefs.split2.coerceAtLeast(prefs.split1)
        val colors = intArrayOf(0x553B82F6, 0x55F59E0B, 0x5522C55E)
        val bounds = floatArrayOf(pad, a, b, pad + w)
        val zones = GesturePrefs.zones
        for (i in 0..2) {
            fill.color = colors[i]
            rect.set(bounds[i], 0f, bounds[i + 1], h)
            canvas.drawRect(rect, fill)
            if (bounds[i + 1] - bounds[i] > context.dp(44)) {
                canvas.drawText(prefs.get(zones[i]).label, (bounds[i] + bounds[i + 1]) / 2f, h / 2f, text)
            }
        }
        line.color = 0xFFFFFFFF.toInt()
        canvas.drawLine(a, 0f, a, h, line)
        canvas.drawLine(b, 0f, b, h, line)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = context.dp(20).toFloat()
        val w = (width - pad * 2).coerceAtLeast(1f)
        val frac = ((e.x - pad) / w).coerceIn(0.1f, 0.9f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val a = pad + w * prefs.split1
                val b = pad + w * prefs.split2
                val da = kotlin.math.abs(e.x - a)
                val db = kotlin.math.abs(e.x - b)
                dragging = when {
                    da < db -> 1
                    db < da -> 2
                    e.x < a -> 1
                    else -> 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 1) {
                    prefs.split1 = frac
                    if (prefs.split2 < frac) prefs.split2 = frac
                } else if (dragging == 2) {
                    prefs.split2 = frac
                    if (prefs.split1 > frac) prefs.split1 = frac
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
        }
        return true
    }
}
