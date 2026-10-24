package org.openplayer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = 0x99FFFFFF.toInt()
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }

    init {
        isClickable = false
        isFocusable = false
    }

    fun setCenter(x: Float, y: Float) {
        cx = x
        cy = y
        kx = 0f
        ky = 0f
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
        dir = when {
            dist < 26f * d -> Dir.NONE
            abs(dx) > abs(dy) -> if (dx < 0) Dir.LEFT else Dir.RIGHT
            else -> if (dy < 0) Dir.UP else Dir.DOWN
        }
        invalidate()
        return dir
    }

    private fun tint(active: Boolean) {
        icon.color = if (active) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun triangle(c: Canvas, x: Float, y: Float, size: Float, pointing: Dir) {
        val p = Path()
        when (pointing) {
            Dir.LEFT -> { p.moveTo(x - size, y); p.lineTo(x + size, y - size); p.lineTo(x + size, y + size) }
            Dir.RIGHT -> { p.moveTo(x + size, y); p.lineTo(x - size, y - size); p.lineTo(x - size, y + size) }
            else -> { p.moveTo(x + size, y); p.lineTo(x - size, y - size); p.lineTo(x - size, y + size) }
        }
        p.close()
        c.drawPath(p, icon)
    }

    override fun onDraw(c: Canvas) {
        val r = 78f * d
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        val s = 8f * d
        val off = 52f * d
        // Sol: geri sar
        tint(dir == Dir.LEFT)
        triangle(c, cx - off - s, cy, s, Dir.LEFT)
        triangle(c, cx - off + s, cy, s, Dir.LEFT)
        // Sağ: ileri sar
        tint(dir == Dir.RIGHT)
        triangle(c, cx + off - s, cy, s, Dir.RIGHT)
        triangle(c, cx + off + s, cy, s, Dir.RIGHT)
        // Yukarı: oynat
        tint(dir == Dir.UP)
        triangle(c, cx, cy - off, s, Dir.RIGHT)
        // Aşağı: duraklat
        tint(dir == Dir.DOWN)
        c.drawRect(cx - s, cy + off - s, cx - s * 0.3f, cy + off + s, icon)
        c.drawRect(cx + s * 0.3f, cy + off - s, cx + s, cy + off + s, icon)
        c.drawCircle(cx + kx, cy + ky, 20f * d, knob)
    }
}
