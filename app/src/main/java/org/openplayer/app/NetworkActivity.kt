package org.openplayer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Kayıtlı SMB, FTP ve WebDAV sunucularını listeler; sunucularda gezip video açar. */
class NetworkActivity : AppCompatActivity() {

    private class Row(
        val icon: Int,
        val title: String,
        val subtitle: String?,
        val onClick: () -> Unit,
        val onLong: (() -> Unit)? = null,
    )

    private class RowHolder(val root: LinearLayout, val icon: ImageView, val title: TextView, val sub: TextView) :
        RecyclerView.ViewHolder(root)

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val c = parent.context
            val icon = ImageView(c).apply {
                setColorFilter(UiColors.folderIcon)
                setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
                layoutParams = LinearLayout.LayoutParams(c.dp(48), c.dp(48))
            }
            val title = TextView(c).apply {
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(c).apply {
                setTextColor(UiColors.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = c.dp(14) }
            }
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                isFocusable = true
                addView(icon)
                addView(texts)
            }
            return RowHolder(root, icon, title, sub)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows[position]
            holder.icon.setImageResource(row.icon)
            holder.title.text = row.title
            holder.sub.text = row.subtitle.orEmpty()
            holder.sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            holder.root.setOnClickListener { row.onClick() }
            holder.root.setOnLongClickListener {
                row.onLong?.invoke()
                row.onLong != null
            }
        }
    }

    private lateinit var titleView: TextView
    private lateinit var addButton: ImageView
    private lateinit var statusView: TextView
    private val adapter = RowAdapter()

    private var server: NetServer? = null
    private var dir = "/"
    private var entries: List<NetEntry> = emptyList()
    private var requestId = 0

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(buildUi())
        showServers()
    }

    private fun buildUi(): View {
        val back = iconView(R.drawable.ic_back).apply { setOnClickListener { goBack() } }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        addButton = iconView(R.drawable.ic_add).apply { setOnClickListener { showServerDialog(null) } }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(back)
            addView(titleView, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) }.also { titleView.gravity = Gravity.CENTER_VERTICAL })
            addView(addButton)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NetworkActivity)
            adapter = this@NetworkActivity.adapter
        }
        statusView = TextView(this).apply {
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            visibility = View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(FrameLayout(this@NetworkActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(statusView, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
    }

    // ------------------------------------------------------------------ gezinme

    private fun boundary(s: NetServer): String =
        if (s.protocol == NetProtocol.SMB) "/" + s.rootDir.trim('/').substringBefore('/') else s.rootDir

    private fun goBack() {
        val s = server
        when {
            s == null -> finish()
            dir.trimEnd('/').ifEmpty { "/" } == boundary(s).trimEnd('/').ifEmpty { "/" } -> {
                server = null
                showServers()
            }
            else -> {
                dir = dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                load()
            }
        }
    }

    private fun setStatus(text: String?) {
        statusView.text = text.orEmpty()
        statusView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun showServers() {
        titleView.text = "Ağ kaynakları"
        addButton.visibility = View.VISIBLE
        val servers = NetworkStore.all(this)
        adapter.rows = servers.map { s ->
            Row(
                icon = R.drawable.ic_public,
                title = s.name.ifBlank { s.host },
                subtitle = s.summary,
                onClick = { openServer(s) },
                onLong = { serverMenu(s) },
            )
        }
        statusView.setOnClickListener(null)
        setStatus(if (servers.isEmpty()) "Henüz sunucu yok.\nSağ üstteki + ile SMB, FTP veya WebDAV sunucusu ekleyin." else null)
    }

    private fun openServer(s: NetServer) {
        server = s
        dir = s.rootDir
        load()
    }

    private fun load() {
        val s = server ?: return
        titleView.text = (s.name.ifBlank { s.host }) + dir.let { if (it == "/") "" else it }
        addButton.visibility = View.GONE
        adapter.rows = emptyList()
        statusView.setOnClickListener(null)
        setStatus("Yükleniyor…")
        val id = ++requestId
        val wanted = dir
        Thread {
            val result = runCatching { NetworkBrowser.list(s, wanted) }
            runOnUiThread {
                if (id != requestId || isFinishing) return@runOnUiThread
                result.onSuccess { showEntries(s, it) }
                result.onFailure { e ->
                    setStatus("Bağlanılamadı:\n${e.message ?: e.javaClass.simpleName}\n\nYeniden denemek için dokunun.")
                    statusView.setOnClickListener { load() }
                }
            }
        }.start()
    }

    private fun showEntries(s: NetServer, list: List<NetEntry>) {
        entries = list
        statusView.setOnClickListener(null)
        setStatus(if (list.isEmpty()) "Bu klasörde klasör veya video yok." else null)
        adapter.rows = list.map { e ->
            if (e.isDir) {
                Row(R.drawable.ic_folder, e.name, null, onClick = {
                    dir = dir.trimEnd('/') + "/" + e.name
                    load()
                })
            } else {
                Row(R.drawable.ic_play, e.name, humanSize(e.size), onClick = { play(s, e) })
            }
        }
    }

    private fun play(s: NetServer, picked: NetEntry) {
        val videos = entries.filter { !it.isDir }
        val index = videos.indexOf(picked).coerceAtLeast(0)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(videos.map { NetworkBrowser.uriFor(s, dir, it.name) }))
                .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(videos.map { it.name }))
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
        )
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024L * 1024 -> "%d KB".format(bytes / 1024)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }

    // ------------------------------------------------------------------ sunucu ekleme / düzenleme

    private fun serverMenu(s: NetServer) {
        showChoice(s.name.ifBlank { s.host }, listOf("Düzenle", "Sil"), -1) { which ->
            when (which) {
                0 -> showServerDialog(s)
                1 -> AlertDialog.Builder(this)
                    .setMessage("“${s.name.ifBlank { s.host }}” silinsin mi?")
                    .setPositiveButton("Sil") { _, _ ->
                        NetworkStore.delete(this, s.id)
                        showServers()
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun field(hint: String, initial: String, password: Boolean = false, number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(initial)
        setSingleLine()
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    private fun showServerDialog(existing: NetServer?) {
        val protocols = NetProtocol.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@NetworkActivity, android.R.layout.simple_spinner_dropdown_item, protocols.map { it.label })
            setSelection(existing?.protocol?.ordinal ?: 0)
        }
        val name = field("Ad (isteğe bağlı)", existing?.name.orEmpty())
        val host = field("Sunucu adresi (örn. 192.168.1.10)", existing?.host.orEmpty())
        val port = field("Port (boş = varsayılan)", existing?.port?.takeIf { it > 0 }?.toString().orEmpty(), number = true)
        val path = field(protocols[spinner.selectedItemPosition].pathHint, existing?.path.orEmpty())
        val user = field("Kullanıcı adı (boş = anonim / misafir)", existing?.user.orEmpty())
        val pass = field("Parola", existing?.pass.orEmpty(), password = true)
        val secure = CheckBox(this).apply {
            text = "HTTPS kullan"
            isChecked = existing?.secure ?: false
            setTextColor(Color.WHITE)
        }
        fun syncProtocol() {
            val p = protocols[spinner.selectedItemPosition]
            path.hint = p.pathHint
            secure.visibility = if (p == NetProtocol.WEBDAV) View.VISIBLE else View.GONE
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = syncProtocol()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        syncProtocol()

        val form = vertical(20) {
            addView(spinner)
            listOf(name, host, port, path, user, pass).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
            addView(secure)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Sunucu ekle" else "Sunucuyu düzenle")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("İptal", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val protocol = protocols[spinner.selectedItemPosition]
                val hostText = host.text.toString().trim()
                val pathText = path.text.toString().trim()
                val portNumber = port.text.toString().trim().toIntOrNull() ?: 0
                val error = when {
                    hostText.isEmpty() -> "Sunucu adresi gerekli"
                    protocol == NetProtocol.SMB && pathText.trim('/').isEmpty() -> "SMB için paylaşım adı gerekli"
                    portNumber !in 0..65535 -> "Port 0-65535 arasında olmalı"
                    else -> null
                }
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                NetworkStore.save(
                    this,
                    NetServer(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        protocol = protocol,
                        name = name.text.toString().trim(),
                        host = hostText,
                        port = portNumber,
                        path = pathText,
                        user = user.text.toString().trim(),
                        pass = pass.text.toString(),
                        secure = protocol == NetProtocol.WEBDAV && secure.isChecked,
                    )
                )
                dialog.dismiss()
                showServers()
            }
        }
        dialog.show()
    }
}
