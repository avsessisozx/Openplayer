package org.openplayer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Random

/**
 * Dalga biçimli ilerleme çubuğu. Çubuk yükseklikleri şarkıya özgü (kimliğinden türetilen) bir desendir;
 * şarkının gerçek ses dalgası değildir. Dokunup sürükleyerek konum seçilir.
 */
class WaveSeekView(context: Context) : View(context) {

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Sürükleme sırasında (0..1) ve bırakınca çağrılır. */
    var onScrub: ((Float) -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null
    var onDragChanged: ((Boolean) -> Unit)? = null

    private var heights = FloatArray(0)
    private var seed = 0L
    private val played = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rest = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false

    fun setSeed(value: Long) {
        if (seed == value && heights.isNotEmpty()) return
        seed = value
        heights = FloatArray(0)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(56))
    }

    private fun ensure(bars: Int) {
        if (heights.size == bars) return
        val r = Random(seed)
        val h = FloatArray(bars)
        var prev = 0.5f
        for (i in 0 until bars) {
            // Komşu çubuklar birbirine yakın: doğal görünen bir dalga.
            prev = (prev * 0.55f + r.nextFloat() * 0.45f).coerceIn(0.12f, 1f)
            h[i] = 0.2f + 0.8f * prev
        }
        heights = h
    }

    override fun onDraw(canvas: Canvas) {
        val bw = context.dp(4).toFloat()
        val gap = context.dp(3).toFloat()
        val bars = ((width + gap) / (bw + gap)).toInt().coerceAtLeast(1)
        ensure(bars)
        played.color = UiColors.accent
        rest.color = 0x59FFFFFF
        val total = bars * bw + (bars - 1) * gap
        val x0 = (width - total) / 2f
        val cy = height / 2f
        val playedBars = progress * bars
        for (i in 0 until bars) {
            val h = height * heights[i]
            val x = x0 + i * (bw + gap)
            rect.set(x, cy - h / 2f, x + bw, cy + h / 2f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, if (i < playedBars) played else rest)
        }
    }

    private fun fractionAt(x: Float) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                onDragChanged?.invoke(true)
                progress = fractionAt(e.x)
                onScrub?.invoke(progress)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                progress = fractionAt(e.x)
                onScrub?.invoke(progress)
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                progress = fractionAt(e.x)
                onSeek?.invoke(progress)
                onDragChanged?.invoke(false)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onDragChanged?.invoke(false)
            }
        }
        return true
    }
}
