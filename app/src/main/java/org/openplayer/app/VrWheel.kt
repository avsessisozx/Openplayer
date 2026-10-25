package org.openplayer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 360° videoda ekrana basılı tutunca çıkan çember: parmağı sola/sağa kaydırınca geri/ileri sarar,
 * yukarı oynatır, aşağı duraklatır. Yalnız çizim yapar; dokunma olaylarını oynatıcı yönetir.
 */
class VrWheelView(ctx: Context) : View(ctx) {
    enum class Dir { NONE, LEFT, RIGHT, UP, DOWN }

    private val d = ctx.resources.displayMetrics.density
    private var cx = 0f
    private var cy = 0f
    private var kx = 0f
    private var ky = 0f
    var dir = Dir.NONE
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3101418.toInt()
        setShadowLayer(14f * d, 0f, 3f * d, 0x99000000.toInt())
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = 0x66FFFFFF
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = 0x33FFFFFF
    }
    private val sector = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        setShadowLayer(8f * d, 0f, 2f * d, 0x99000000.toInt())
    }
    private val tmpRect = RectF()

    /** Açılış animasyonu (0..1) ve etkin yönün vurgu şiddeti (0..1). */
    private var appear = 0f
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    private var shownDir = Dir.NONE

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_SOFTWARE, null) // gölge katmanı için
    }

    fun setCenter(x: Float, y: Float) {
        cx = x
        cy = y
        kx = 0f
        ky = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { appear = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    /** Parmak konumunu işler; yöndeki değişikliği döndürür. */
    fun move(x: Float, y: Float): Dir {
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        val max = 52f * d
        val k = if (dist > max) max / dist else 1f
        kx = dx * k
        ky = dy * k
        val nd = when {
            dist < 26f * d -> Dir.NONE
            abs(dx) > abs(dy) -> if (dx < 0) Dir.LEFT else Dir.RIGHT
            else -> if (dy < 0) Dir.UP else Dir.DOWN
        }
        if (nd != dir) {
            dir = nd
            if (nd != Dir.NONE) shownDir = nd
            glowAnim?.cancel()
            glowAnim = ValueAnimator.ofFloat(glow, if (nd == Dir.NONE) 0f else 1f).apply {
                duration = 140
                addUpdateListener { glow = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
        return dir
    }

    private fun tint(active: Boolean) {
        icon.color = if (active) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt()
    }

    private fun triangle(c: Canvas, x: Float, y: Float, size: Float, pointing: Dir) {
        val p = Path()
        when (pointing) {
            Dir.LEFT -> { p.moveTo(x - size, y); p.lineTo(x + size, y - size); p.lineTo(x + size, y + size) }
            else -> { p.moveTo(x + size, y); p.lineTo(x - size, y - size); p.lineTo(x - size, y + size) }
        }
        p.close()
        c.drawPath(p, icon)
    }

    private fun angleOf(dir: Dir): Float = when (dir) {
        Dir.RIGHT -> -45f
        Dir.DOWN -> 45f
        Dir.LEFT -> 135f
        else -> 225f // UP
    }

    override fun onDraw(c: Canvas) {
        if (appear <= 0f) return
        c.save()
        c.scale(appear, appear, cx, cy)
        val r = 82f * d
        val accent = UiColors.accent
        c.drawCircle(cx, cy, r, fill)
        // Etkin yönün dilimi: vurgu rengi, parmakla birlikte belirir.
        if (shownDir != Dir.NONE && glow > 0f) {
            sector.color = (accent and 0x00FFFFFF) or ((0x99 * glow).toInt() shl 24)
            tmpRect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(tmpRect, angleOf(shownDir), 90f, true, sector)
            // Dilimin üstünü koyu bir iç daireyle kapat: halka görünümü.
            sector.color = 0xE6101418.toInt()
            c.drawCircle(cx, cy, 30f * d, sector)
        }
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, 30f * d, inner)
        // Dilim ayırıcı çizgiler
        for (a in floatArrayOf(45f, 135f, 225f, 315f)) {
            val rad = Math.toRadians(a.toDouble())
            c.drawLine(
                cx + (30f * d * Math.cos(rad)).toFloat(), cy + (30f * d * Math.sin(rad)).toFloat(),
                cx + (r * Math.cos(rad)).toFloat(), cy + (r * Math.sin(rad)).toFloat(), inner,
            )
        }
        val off = 56f * d
        fun scaleFor(dr: Dir) = 1f + 0.3f * (if (shownDir == dr) glow else 0f)
        // Sol: geri sar
        tint(dir == Dir.LEFT)
        var s = 8f * d * scaleFor(Dir.LEFT)
        triangle(c, cx - off - s, cy, s, Dir.LEFT)
        triangle(c, cx - off + s, cy, s, Dir.LEFT)
        // Sağ: ileri sar
        tint(dir == Dir.RIGHT)
        s = 8f * d * scaleFor(Dir.RIGHT)
        triangle(c, cx + off - s, cy, s, Dir.RIGHT)
        triangle(c, cx + off + s, cy, s, Dir.RIGHT)
        // Yukarı: oynat
        tint(dir == Dir.UP)
        s = 9f * d * scaleFor(Dir.UP)
        triangle(c, cx, cy - off, s, Dir.RIGHT)
        // Aşağı: duraklat
        tint(dir == Dir.DOWN)
        s = 8f * d * scaleFor(Dir.DOWN)
        c.drawRect(cx - s, cy + off - s, cx - s * 0.3f, cy + off + s, icon)
        c.drawRect(cx + s * 0.3f, cy + off - s, cx + s, cy + off + s, icon)
        c.drawCircle(cx + kx, cy + ky, 19f * d, knob)
        c.restore()
    }
}
