package org.openplayer.app

import android.annotation.SuppressLint
import android.graphics.Outline
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
import coil.load
import coil.request.videoFrameMillis

class FolderAdapter(private val onClick: (FolderItem) -> Unit) :
    RecyclerView.Adapter<FolderAdapter.VH>() {

    var items: List<FolderItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val root: LinearLayout, val name: TextView, val count: TextView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val icon = ImageView(c).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(UiColors.folderIcon)
            layoutParams = LinearLayout.LayoutParams(c.dp(56), c.dp(48))
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
                .apply { marginStart = c.dp(16) }
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
            addView(icon)
            addView(texts)
        }
        return VH(root, name, count)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.count.text = "${item.videoCount} video"
        holder.root.setOnClickListener { onClick(item) }
    }
}

class VideoAdapter(private val onClick: (VideoItem) -> Unit) :
    RecyclerView.Adapter<VideoAdapter.VH>() {

    var items: List<VideoItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Son oynatılan videonun başlığı vurgulanır. */
    var highlightUri: String? = null
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val duration: TextView,
        val title: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1F24.toInt())
        }
        val duration = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(c.dp(6), c.dp(2), c.dp(6), c.dp(2))
        }
        val box = FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(c.dp(112), c.dp(64))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, c.dp(6).toFloat())
                }
            }
            addView(thumb, FrameLayout.LayoutParams(-1, -1))
            addView(
                duration,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, c.dp(4), c.dp(4)) }
            )
        }
        val title = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = c.dp(14) }
        }
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(16), c.dp(8), c.dp(16), c.dp(8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            addView(box)
            addView(title)
        }
        return VH(root, thumb, duration, title)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.title.setTextColor(
            if (item.uri.toString() == highlightUri) UiColors.accent else UiColors.textPrimary
        )
        holder.duration.text = formatDuration(item.durationMs)
        holder.thumb.load(item.uri, Thumbs.get(holder.root.context)) {
            videoFrameMillis(1000)
            crossfade(true)
        }
        holder.root.setOnClickListener { onClick(item) }
    }
}
