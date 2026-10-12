package org.openplayer.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SheetEntry(val icon: Int, val label: String, val danger: Boolean = false, val action: () -> Unit)

/** Alttan açılan, temaya ve menü opaklığına uyan işlem menüsü (MX'teki ⋮ menüsü gibi). */
fun Context.showSheet(title: String, entries: List<SheetEntry>) {
    val dialog = android.app.Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val alpha = AppSettings.get(this).panelOpacityPercent.coerceIn(30, 100) * 255 / 100
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            val r = dp(22).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor((alpha shl 24) or (ThemeManager.surface and 0xFFFFFF))
        }
    }
    root.addView(TextView(this).apply {
        text = title
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(24), dp(20), dp(24), dp(14))
    })
    root.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for (e in entries) {
        val color = if (e.danger) 0xFFFF6B6B.toInt() else Color.WHITE
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isClickable = true
            isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(e.icon)
                setColorFilter(if (e.danger) color else UiColors.textSecondary)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(TextView(context).apply {
                text = e.label
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(20) })
            setOnClickListener {
                dialog.dismiss()
                e.action()
            }
        })
    }
    val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
    val scroll = ScrollView(this).apply { addView(list) }
    root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
    list.measure(
        View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.UNSPECIFIED
    )
    if (list.measuredHeight > maxH) scroll.layoutParams = LinearLayout.LayoutParams(-1, maxH)
    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setDimAmount(0.35f)
    }
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
}
