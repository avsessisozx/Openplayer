package org.openplayer.mpvprobe

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.Utils
import java.io.File

/**
 * Extras: name, file, vo, ctx, hwdec, target ("surface" = SurfaceView, "texture" = TextureView,
 * which exercises the SurfaceTexture route that 360 mode needs).
 * Results go to logcat under the tag MPVPROBE.
 */
class ProbeActivity : Activity() {
    private var mpv: MPV? = null
    private var frames = 0L
    private val ui = Handler(Looper.getMainLooper())
    private fun opt(k: String, d: String) = intent.getStringExtra(k) ?: d

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v: View = if (opt("target", "surface") == "texture") {
            TextureView(this).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) =
                        start(Surface(st), w, h)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) { frames++ }
                }
            }
        } else {
            SurfaceView(this).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {}
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                        if (mpv == null) start(h.surface, w, ht)
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        mpv?.setPropertyString("vo", "null")
                        mpv?.detachSurface()
                    }
                })
            }
        }
        setContentView(v, ViewGroup.LayoutParams(-1, -1))
    }

    private fun start(s: Surface, w: Int, h: Int) {
        val name = opt("name", "?")
        Log.i(TAG, "[$name] START sdk=${android.os.Build.VERSION.SDK_INT}")
        try {
            Utils.copyAssets(this)
            val m = MPV()
            m.create(applicationContext)
            Log.i(TAG, "[$name] LOADED native libraries, MPV created")
            mpv = m
            m.addLogObserver(object : MPV.LogObserver {
                override fun logMessage(prefix: String, level: Int, text: String) {
                    if (level <= 40) Log.i(TAG, "[$name] mpv/$prefix($level): ${text.trim()}")
                }
            })
            m.setOptionString("config", "no")
            m.setOptionString("vo", opt("vo", "gpu"))
            m.setOptionString("gpu-context", opt("ctx", "android"))
            m.setOptionString("opengl-es", "yes")
            m.setOptionString("hwdec", opt("hwdec", "no"))
            m.setOptionString("ao", "audiotrack,opensles")
            m.setOptionString("tls-verify", "yes")
            m.setOptionString("tls-ca-file", File(filesDir, "cacert.pem").path)
            m.setOptionString("force-window", "no")
            m.setOptionString("idle", "once")
            m.init()
            m.attachSurface(s)
            m.setPropertyString("android-surface-size", "${w}x$h")
            m.setPropertyString("force-window", "yes")
            m.command("loadfile", opt("file", ""))
            ui.postDelayed({ report("t5", m, name) }, 5000)
            ui.postDelayed({ filters(m, name) }, 7000)
            ui.postDelayed({ report("t11", m, name); Log.i(TAG, "[$name] DONE") }, 11000)
        } catch (e: Throwable) {
            Log.e(TAG, "[$name] FAILED ${e.javaClass.name}: ${e.message}", e)
        }
    }

    private fun filters(m: MPV, name: String) {
        for (f in listOf(
            "lavfi=[equalizer=f=1000:t=q:w=1:g=6]",
            "lavfi=[aecho=0.8:0.9:40:0.3]",
            "lavfi=[pan=stereo|c0=c1|c1=c0]",
            "lavfi=[acompressor]",
            "lavfi=[dynaudnorm]",
            "lavfi=[loudnorm]",
            "lavfi=[asetrate=52920,aresample=44100,atempo=0.8333]",
            "scaletempo2",
            "rubberband",
        )) {
            try {
                m.setPropertyString("af", f)
                Log.i(TAG, "[$name] AF $f -> af=${m.getPropertyString("af")}")
            } catch (e: Throwable) {
                Log.i(TAG, "[$name] AF $f -> ERROR ${e.message}")
            }
        }
        m.setPropertyString("af", "")
    }

    private fun report(tag: String, m: MPV, name: String) {
        Log.i(TAG, "[$name] $tag texFrames=$frames")
        for (p in listOf(
            "mpv-version", "ffmpeg-version", "libass-version", "current-vo", "current-ao",
            "hwdec-current", "video-codec", "video-params/pixelformat", "video-params/w",
            "video-out-params/pixelformat", "playback-time", "core-idle", "estimated-vf-fps",
            "vo-delayed-frame-count", "vo-drop-frame-count", "decoder-frame-drop-count",
            "display-fps", "file-format", "gpu-context",
        )) {
            val v = try { m.getPropertyString(p) } catch (e: Throwable) { "ERR ${e.message}" }
            Log.i(TAG, "[$name] $tag $p=$v")
        }
    }

    private companion object {
        const val TAG = "MPVPROBE"
    }
}
