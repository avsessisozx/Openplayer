package org.openplayer.app

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "org.openplayer.app.URIS"
        const val EXTRA_INDEX = "org.openplayer.app.INDEX"
    }

    private var player: ExoPlayer? = null
    private var startIndex = -1
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = false
            setBackgroundColor(Color.BLACK)
        }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0x99000000.toInt())
            setPadding(32, 16, 32, 16)
            visibility = View.GONE
        }

        setContentView(
            FrameLayout(this).apply {
                addView(playerView)
                addView(
                    hud,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
        )
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        @Suppress("DEPRECATION")
        val uris: List<Uri> = intent?.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
            ?: listOfNotNull(intent?.data)
        if (uris.isEmpty()) return finish()
        val index = (if (startIndex >= 0) startIndex else intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, uris.size - 1)

        val exo = ExoPlayer.Builder(this, NextRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .build().also { player = it }
        playerView.player = exo
        exo.setMediaItems(uris.map { MediaItem.fromUri(it) }, index, ResumeStore.position(this, uris[index]))
        exo.prepare()
        exo.playWhenReady = true

        playerView.setOnTouchListener(
            GestureController(
                activity = this,
                player = exo,
                prefs = GesturePrefs(this),
                onHud = { text ->
                    hud.text = text
                    hud.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                },
                onToggleControls = {
                    if (playerView.isControllerFullyVisible) playerView.hideController()
                    else playerView.showController()
                }
            )
        )
    }

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            val uri = p.currentMediaItem?.localConfiguration?.uri
            if (uri != null) {
                val duration = p.duration
                val nearEnd = duration > 0 && p.currentPosition > duration - 5_000
                ResumeStore.save(this, uri, if (nearEnd) 0L else p.currentPosition)
            }
            startIndex = p.currentMediaItemIndex
        }
        playerView.player = null
        player?.release()
        player = null
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
