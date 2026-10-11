package org.openplayer.app

import android.graphics.Color
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
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
        val uri = intent?.data ?: return finish()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
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
