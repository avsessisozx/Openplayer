package org.openplayer.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/** Liste görünümü ve sıralama paneli: görünüm modu, düzen, sıralama ölçütü, alanlar ve gelişmiş seçenekler. */
private class Chip(val view: TextView, val isSelected: () -> Boolean)

private fun Context.makeChip(label: String): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    maxLines = 2
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setPadding(dp(6), dp(10), dp(6), dp(10))
}

private fun Context.styleChip(chip: Chip) {
    val sel = chip.isSelected()
    chip.view.background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), if (sel) UiColors.accent else 0x55FFFFFF)
        setColor(if (sel) 0x332F8CFF else 0x00000000)
    }
    chip.view.setTextColor(if (sel) UiColors.accent else Color.WHITE)
}

private fun Context.chipRows(chips: List<Chip>, perRow: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        chips.chunked(perRow).forEach { rowChips ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rowChips.forEach { c ->
                    addView(c.view, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                repeat(perRow - rowChips.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
    }

private fun directionLabels(k: SortKey): Pair<String, String> = when (k) {
    SortKey.DATE, SortKey.LAST_PLAYED -> "En eski" to "En yeni"
    SortKey.TITLE, SortKey.PATH, SortKey.EXTENSION -> "A → Z" to "Z → A"
    SortKey.DURATION -> Tx.s(R.string.listpanel_001) to "En uzun"
    SortKey.SIZE -> Tx.s(R.string.listpanel_003) to Tx.s(R.string.listpanel_002)
    SortKey.RESOLUTION -> Tx.s(R.string.listpanel_005) to Tx.s(R.string.listpanel_004)
    SortKey.STATUS -> Tx.s(R.string.listpanel_007) to Tx.s(R.string.listpanel_006)
}

private val fieldLabels = listOf(
    "thumb" to Tx.s(R.string.listpanel_008),
    "duration" to Tx.s(R.string.listpanel_020),
    "ext" to Tx.s(R.string.listpanel_009),
    "played" to Tx.s(R.string.listpanel_010),
    "resolution" to Tx.s(R.string.listpanel_011),
    "path" to Tx.s(R.string.listpanel_021),
    "size" to Tx.s(R.string.listpanel_022),
    "date" to Tx.s(R.string.listpanel_023),
)

fun Context.showListPanel(s: AppSettings, onDone: () -> Unit) {
    var mode = s.listMode
    var key = s.sortKey
    var asc = s.sortAscending
    var grid = s.gridView
    val fields = s.listFields.toMutableSet()
    var durBadge = s.durationOnThumb
    var showHidden = s.showHiddenFolders

    val refreshers = ArrayList<() -> Unit>()
    fun chip(label: String, selected: () -> Boolean, onClick: () -> Unit): Chip {
        val c = Chip(makeChip(label), selected)
        c.view.setOnClickListener {
            onClick()
            refreshers.forEach { it() }
        }
        refreshers += { styleChip(c) }
        styleChip(c)
        return c
    }

    fun title(t: String) = label(t, 18f).apply { setPadding(0, dp(14), 0, dp(4)) }

    val modeChips = ListMode.entries.map { m -> chip(m.label, { mode == m }) { mode = m } }
    val layoutChips = listOf(
        chip(Tx.s(R.string.listpanel_012), { !grid }) { grid = false },
        chip(Tx.s(R.string.listpanel_024), { grid }) { grid = true },
    )
    val keyChips = SortKey.entries.map { k -> chip(k.label, { key == k }) { key = k } }
    val dirLow = chip("", { asc }) { asc = true }
    val dirHigh = chip("", { !asc }) { asc = false }
    refreshers += {
        val (a, b) = directionLabels(key)
        dirLow.view.text = "↑  $a"
        dirHigh.view.text = "↓  $b"
    }
    refreshers.forEach { it() }

    val fieldBoxes = fieldLabels.map { (id, text) ->
        CheckBox(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isChecked = id in fields
            setOnCheckedChangeListener { _, on -> if (on) fields += id else fields -= id }
        }
    }
    val fieldGrid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        fieldBoxes.chunked(2).forEach { pair ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            })
        }
    }

    fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        isChecked = initial
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    val root = vertical(16) {
        addView(title(Tx.s(R.string.listpanel_013)))
        addView(chipRows(modeChips, 2))
        addView(title(Tx.s(R.string.listpanel_014)))
        addView(chipRows(layoutChips, 2))
        addView(title(Tx.s(R.string.listpanel_015)))
        addView(chipRows(keyChips, 3))
        addView(chipRows(listOf(dirLow, dirHigh), 2))
        addView(title(Tx.s(R.string.listpanel_025)))
        addView(fieldGrid)
        addView(title(Tx.s(R.string.listpanel_016)))
        addView(toggle(Tx.s(R.string.listpanel_017), durBadge) { durBadge = it })
        addView(toggle(Tx.s(R.string.listpanel_018), showHidden) { showHidden = it })
    }
    PanelBuilder(this)
        .setView(ScrollView(this).apply { addView(root) })
        .setNegativeButton(Tx.s(R.string.fileops_004), null)
        .setPositiveButton(Tx.s(R.string.listpanel_019)) { _, _ ->
            s.listMode = mode
            s.sortKey = key
            s.sortAscending = asc
            s.gridView = grid
            s.listFields = fields
            s.durationOnThumb = durBadge
            s.showHiddenFolders = showHidden
            onDone()
        }
        .show()
}
