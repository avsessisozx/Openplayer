package org.openplayer.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Ana ekran: klasör listesi, klasör içi video listesi ve arama. */
class MainActivity : AppCompatActivity() {

    private lateinit var backButton: ImageView
    private lateinit var titleView: TextView
    private lateinit var searchField: EditText
    private lateinit var searchButton: ImageView
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView

    private val folderAdapter = FolderAdapter { openFolder(it) }
    private val videoAdapter = VideoAdapter { play(it) }

    private var all: List<VideoItem> = emptyList()
    private var currentFolder: FolderItem? = null
    private var searching = false
    private var shown: List<VideoItem> = emptyList()

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refresh() else showMessage("Videoları göstermek için izin gerekli.\nDokunarak tekrar dene.")
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                searching -> closeSearch()
                currentFolder != null -> {
                    currentFolder = null
                    render()
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) refresh() else requestPermission.launch(permission)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildUi(): View {
        backButton = icon(R.drawable.ic_back).apply {
            visibility = View.GONE
            setOnClickListener { backCallback.handleOnBackPressed() }
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        searchField = EditText(this).apply {
            hint = "Ara"
            setHintTextColor(UiColors.textSecondary)
            setTextColor(Color.WHITE)
            setSingleLine()
            background = null
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        searchButton = icon(R.drawable.ic_search).apply {
            setOnClickListener { if (searching) closeSearch() else openSearch() }
        }

        val titleBox = FrameLayout(this).apply {
            addView(titleView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            addView(searchField, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(backButton)
            addView(titleBox, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
            addView(searchButton)
        }

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = folderAdapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(96))
        }
        emptyView = TextView(this).apply {
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { if (!hasPermission()) requestPermission.launch(permission) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(FrameLayout(this@MainActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiColors.accent)
            }
            elevation = dp(6).toFloat()
            contentDescription = "Son oynatılanı aç"
            setOnClickListener { resumeLast() }
        }

        return FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(
                fab,
                FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, dp(20), dp(20)) }
            )
        }
    }

    private fun icon(res: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun refresh() {
        all = VideoRepository.loadAll(this)
        videoAdapter.highlightUri = ResumeStore.lastUri(this)
        render()
    }

    private fun render() {
        val query = searchField.text.toString().trim()
        when {
            searching -> {
                backButton.visibility = View.GONE
                shown = if (query.isEmpty()) emptyList()
                else all.filter { it.title.contains(query, ignoreCase = true) }
                showVideos(shown, if (query.isEmpty()) "Aramak için yazın" else "Sonuç bulunamadı")
            }
            currentFolder != null -> {
                val folder = currentFolder!!
                titleView.text = folder.name
                backButton.visibility = View.VISIBLE
                shown = all.filter { it.bucketId == folder.bucketId }
                showVideos(shown, "Bu klasörde video yok")
            }
            else -> {
                titleView.text = "Klasörler"
                backButton.visibility = View.GONE
                val folders = VideoRepository.folders(all)
                folderAdapter.items = folders
                list.adapter = folderAdapter
                if (folders.isEmpty()) showMessage("Video bulunamadı") else hideMessage()
            }
        }
    }

    private fun showVideos(videos: List<VideoItem>, emptyText: String) {
        videoAdapter.items = videos
        list.adapter = videoAdapter
        if (videos.isEmpty()) showMessage(emptyText) else hideMessage()
    }

    private fun showMessage(text: String) {
        emptyView.text = text
        emptyView.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        emptyView.visibility = View.GONE
    }

    private fun openFolder(folder: FolderItem) {
        currentFolder = folder
        render()
    }

    private fun openSearch() {
        searching = true
        titleView.visibility = View.GONE
        searchField.visibility = View.VISIBLE
        searchField.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        render()
    }

    private fun closeSearch() {
        searching = false
        searchField.setText("")
        searchField.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchField.windowToken, 0)
        render()
    }

    private fun play(video: VideoItem) {
        startPlayer(shown, shown.indexOfFirst { it.id == video.id }.coerceAtLeast(0))
    }

    private fun resumeLast() {
        val last = ResumeStore.lastUri(this)
        val video = all.firstOrNull { it.uri.toString() == last }
        if (video == null) {
            Toast.makeText(this, "Henüz oynatılmış video yok", Toast.LENGTH_SHORT).show()
            return
        }
        val siblings = all.filter { it.bucketId == video.bucketId }
        startPlayer(siblings, siblings.indexOfFirst { it.id == video.id }.coerceAtLeast(0))
    }

    private fun startPlayer(videos: List<VideoItem>, index: Int) {
        if (videos.isEmpty()) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(videos.map { it.uri }))
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
        )
    }
}
