package org.openplayer.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

fun Context.iconView(res: Int, sizeDp: Int = 48, padDp: Int = 12): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(Color.WHITE)
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Altında etiketi olan yuvarlak düğme (kısayol satırı ve üç nokta paneli için). */
fun Context.actionButton(
    icon: Int,
    glyph: String?,
    label: String,
    active: Boolean,
    iconRotation: Float = 0f,
    scale: Float = 1f,
    bgOpacity: Float = 0.6f,
    showLabel: Boolean = true,
    ring: Boolean = false,
    onClick: () -> Unit,
): View {
    fun argb(rgb: Int) = ((bgOpacity.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)
    val circle = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp((56 * scale).toInt()), dp((56 * scale).toInt())).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val btnShape = AppSettings.get(context).buttonShape
        background = GradientDrawable().apply {
            when (btnShape) {
                ButtonShape.CIRCLE -> shape = GradientDrawable.OVAL
                ButtonShape.ROUNDED -> { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat() }
                ButtonShape.SQUARE -> { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(4).toFloat() }
            }
            if (ring) setStroke(dp(2), 0x99FFFFFF.toInt())
            setColor(
                if (ring && !active) 0x00000000
                else if (active) ((maxOf(bgOpacity, 0.6f) * 255).toInt() shl 24) or (ThemeManager.accent and 0xFFFFFF)
                else argb(0x202830)
            )
        }
        if (icon != 0) {
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                rotation = iconRotation
                val pad = dp((14 * scale).toInt())
                setPadding(pad, pad, pad, pad)
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            addView(TextView(context).apply {
                text = glyph
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }
    val text = TextView(this).apply {
        this.text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale.coerceIn(0.8f, 1.2f))
        setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER_HORIZONTAL
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(4), 0, 0)
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            if (showLabel) dp((76 * scale).toInt().coerceAtLeast(56)) else dp((56 * scale).toInt() + 14),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setPadding(dp(2), dp(4), dp(2), dp(4))
        addView(circle)
        if (showLabel) addView(text, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { onClick() }
    }
}

fun Context.seekBar(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar =
    SeekBar(this).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p + min)
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }

fun Context.label(text: String, sizeSp: Float = 14f, color: Int = Color.WHITE): TextView = TextView(this).apply {
    this.text = text
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

fun Context.vertical(padDp: Int = 20, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padDp), dp(8), dp(padDp), dp(8))
    build()
}

fun Context.showChoice(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val builder = PanelBuilder(this).setTitle(title).setNegativeButton("İptal", null)
    if (selected >= 0) {
        builder.setSingleChoiceItems(items.toTypedArray(), selected) { d, which ->
            d.dismiss()
            onPick(which)
        }
    } else {
        builder.setItems(items.toTypedArray()) { _, which -> onPick(which) }
    }
    builder.show()
}

fun Context.showMultiChoice(
    title: String,
    items: List<String>,
    checked: BooleanArray,
    onOk: (BooleanArray) -> Unit,
) {
    val state = checked.copyOf()
    PanelBuilder(this)
        .setTitle(title)
        .setMultiChoiceItems(items.toTypedArray(), state) { _, which, isChecked -> state[which] = isChecked }
        .setPositiveButton("Tamam") { _, _ -> onOk(state) }
        .setNegativeButton("İptal", null)
        .show()
}

fun Context.showInput(
    title: String,
    hint: String,
    initial: String = "",
    password: Boolean = false,
    onOk: (String) -> Unit,
) {
    val input = EditText(this).apply {
        setText(initial)
        this.hint = hint
        setSingleLine()
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val box = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(input, FrameLayout.LayoutParams(-1, -2))
    }
    PanelBuilder(this)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("Tamam") { _, _ -> onOk(input.text.toString()) }
        .setNegativeButton("İptal", null)
        .show()
}

fun Context.showMessage(title: String, message: String) {
    PanelBuilder(this).setTitle(title).setMessage(message).setPositiveButton("Tamam", null).show()
}


/** Tüm menüler için ortak yarı saydam, yuvarlak köşeli panel. Opaklık ve renkler tema ayarlarından gelir. */
class PanelBuilder(private val ctx: Context) : AlertDialog.Builder(ctx, ThemeManager.dialogStyle()) {
    override fun create(): AlertDialog {
        val d = super.create()
        d.setOnShowListener {
            val w = d.window ?: return@setOnShowListener
            val alpha = AppSettings.get(ctx).panelOpacityPercent.coerceIn(30, 100) * 255 / 100
            val bg = GradientDrawable().apply {
                cornerRadius = ctx.dp(20).toFloat()
                setColor((alpha shl 24) or (ThemeManager.surface and 0xFFFFFF))
                setStroke(ctx.dp(1), 0x22FFFFFF)
            }
            w.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(bg, ctx.dp(8)))
            w.setDimAmount(0.2f)
            for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                d.getButton(b)?.setTextColor(ThemeManager.accent)
            }
        }
        return d
    }
}
