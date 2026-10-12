package org.openplayer.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onLongClick: (FolderItem) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    var items: List<FolderItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val root: LinearLayout, val name: TextView, val count: TextView, val badge: TextView, val icon: ImageView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val icon = ImageView(c).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(UiColors.folderIcon)
        }
        val badge = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF0435F.toInt())
            }
            visibility = View.GONE
        }
        val iconBox = FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(c.dp(64), c.dp(52))
            addView(icon, FrameLayout.LayoutParams(c.dp(56), c.dp(48), Gravity.START or Gravity.BOTTOM))
            addView(badge, FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.TOP or Gravity.END))
        }
        val name = TextView(c).apply {
            setTextColor(UiColors.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val count = TextView(c).apply {
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(count)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = c.dp(12) }
        }
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            addView(iconBox)
            addView(texts)
        }
        return VH(root, name, count, badge, icon)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.count.text = "${item.videoCount} video" + if (item.hidden) " · gizli" else ""
        holder.badge.visibility = if (item.newCount > 0) View.VISIBLE else View.GONE
        holder.badge.text = if (item.newCount > 99) "99+" else item.newCount.toString()
        holder.icon.alpha = if (item.hidden) 0.5f else 1f
        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}

class VideoAdapter(
    private val grid: Boolean,
    private val onClick: (VideoItem) -> Unit,
    private val onMore: (VideoItem, View) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    var items: List<VideoItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Son oynatılan videonun başlığı vurgulanır. */
    var highlightUri: String? = null

    var newDays: Int = 7

    /** Satırda gösterilecek alanlar (thumb, duration, ext, played, resolution, path, size, date). */
    var fields: Set<String> = setOf("thumb", "duration")
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var durationOnThumb: Boolean = true
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class VH(
        val root: ViewGroup,
        val thumb: ImageView,
        val duration: TextView,
        val title: TextView,
        val tag: TextView,
        val more: ImageView,
        val progress: View,
        val meta: TextView,
        val box: View,
    ) : RecyclerView.ViewHolder(root)

    private fun round(c: android.content.Context, radiusDp: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, c.dp(radiusDp).toFloat())
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1F24.toInt())
        }
        val duration = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(c.dp(6), c.dp(2), c.dp(6), c.dp(2))
        }
        val progress = View(c).apply {
            setBackgroundColor(UiColors.accent)
            visibility = View.GONE
        }
        val box = FrameLayout(c).apply {
            clipToOutline = true
            outlineProvider = round(c, 6)
            addView(thumb, FrameLayout.LayoutParams(-1, -1))
            addView(
                duration,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, c.dp(4), c.dp(5)) }
            )
            addView(progress, FrameLayout.LayoutParams(0, c.dp(3), Gravity.BOTTOM or Gravity.START))
        }
        val title = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (grid) 14f else 16f)
            maxLines = if (grid) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val tag = TextView(c).apply {
            text = "YENİ"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(c.dp(5), c.dp(1), c.dp(5), c.dp(1))
            background = GradientDrawable().apply {
                cornerRadius = c.dp(4).toFloat()
                setColor(0xFFF0435F.toInt())
            }
            visibility = View.GONE
        }
        val meta = TextView(c).apply {
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val more = c.iconView(R.drawable.ic_more_vert, 40, 8).apply {
            setColorFilter(UiColors.textSecondary)
        }

        if (grid) {
            box.layoutParams = LinearLayout.LayoutParams(-1, c.dp(96))
            val textRow = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                addView(tag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = c.dp(4) })
            }
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                isClickable = true
                isFocusable = true
                addView(box)
                addView(textRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(4) })
                addView(meta, LinearLayout.LayoutParams(-1, -2))
            }
            return VH(root, thumb, duration, title, tag, more, progress, meta, box)
        }

        box.layoutParams = LinearLayout.LayoutParams(c.dp(112), c.dp(64))
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = c.dp(2) })
            addView(tag, LinearLayout.LayoutParams(-2, -2).apply { topMargin = c.dp(3) })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = c.dp(14) }
        }
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(16), c.dp(8), c.dp(4), c.dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            isClickable = true
            isFocusable = true
            addView(box)
            addView(texts)
            addView(more)
        }
        return VH(root, thumb, duration, title, tag, more, progress, meta, box)
    }

    private fun metaText(ctx: android.content.Context, item: VideoItem): String {
        val parts = ArrayList<String>()
        if ("duration" in fields && !durationOnThumb) parts += formatDuration(item.durationMs)
        if ("ext" in fields) item.title.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { parts += it.uppercase() }
        if ("resolution" in fields && item.widthPx > 0) parts += "${item.widthPx}×${item.heightPx}"
        if ("size" in fields) parts += android.text.format.Formatter.formatShortFileSize(ctx, item.sizeBytes)
        if ("date" in fields && item.dateAddedSec > 0) {
            parts += java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(item.dateAddedSec * 1000))
        }
        if ("played" in fields) {
            val t = ResumeStore.lastPlayed(ctx, item.uri)
            if (t > 0) parts += java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(t))
        }
        if ("path" in fields && item.path.isNotEmpty()) parts += item.path.substringBeforeLast('/', "")
        return parts.joinToString(" · ")
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.root.context
        val uriText = item.uri.toString()
        holder.title.text = item.title
        holder.title.setTextColor(
            when {
                uriText == highlightUri -> UiColors.accent
                ResumeStore.isPlayed(ctx, uriText) -> UiColors.textSecondary
                else -> UiColors.textPrimary
            }
        )
        holder.duration.text = formatDuration(item.durationMs)
        holder.duration.visibility = if ("duration" in fields && durationOnThumb) View.VISIBLE else View.GONE
        holder.box.visibility = if ("thumb" in fields) View.VISIBLE else View.GONE
        val metaLine = metaText(ctx, item)
        holder.meta.text = metaLine
        holder.meta.visibility = if (metaLine.isEmpty()) View.GONE else View.VISIBLE
        holder.tag.visibility = if (VideoRepository.isNew(ctx, item, newDays)) View.VISIBLE else View.GONE

        // Kaldığı yeri gösteren ince çizgi
        val pos = ResumeStore.position(ctx, item.uri)
        if (pos > 0 && item.durationMs > 0) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.post {
                val w = (holder.thumb.width * (pos.toFloat() / item.durationMs)).toInt().coerceIn(0, holder.thumb.width)
                holder.progress.layoutParams = (holder.progress.layoutParams as FrameLayout.LayoutParams).apply { width = w }
            }
        } else {
            holder.progress.visibility = View.GONE
        }

        ThumbLoader.load(ctx, item, holder.thumb)
        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onMore(item, holder.root)
            true
        }
        holder.more.setOnClickListener { onMore(item, holder.more) }
    }
}
