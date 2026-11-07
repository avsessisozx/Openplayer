package spike

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpikeActivity : Activity(), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var sv: SurfaceView
    private lateinit var root: FrameLayout
    @Volatile private var surf: Surface? = null
    @Volatile private var sw = 0
    @Volatile private var sh_ = 0
    private lateinit var out: PrintWriter
    private val logs = ConcurrentLinkedQueue<String>()

    @Volatile private var lastStep = "start"
    @Volatile private var lastAt = SystemClock.uptimeMillis()
    fun step(n: String) { lastStep = n; lastAt = SystemClock.uptimeMillis(); Log.i("SPIKE", "STEP $n") }
    private fun startWatchdog() {
        Thread({
            var dumped = ""
            while (true) {
                Thread.sleep(2000)
                if (SystemClock.uptimeMillis() - lastAt > 20000 && dumped != lastStep) {
                    dumped = lastStep
                    val sb = StringBuilder("HANG at '$lastStep': ")
                    Thread.getAllStackTraces().forEach { (t, st) ->
                        if (t.name == "spike" || t.name == "main" || t.name.contains("event"))
                            sb.append("[${t.name} ${t.state}] ").append(st.take(5).joinToString(" < ")).append(" ")
                    }
                    r("HANG", false, sb.toString())
                }
            }
        }, "wd").apply { isDaemon = true }.start()
    }

    fun r(name: String, ok: Boolean?, detail: String) {
        lastAt = SystemClock.uptimeMillis()
        val tag = when (ok) { true -> "OK"; false -> "FAIL"; null -> "INFO" }
        val line = "SPIKE|$name|$tag|${detail.replace('\n', ' ').take(700)}"
        Log.i("SPIKE", line); out.println(line); out.flush()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        out = PrintWriter(FileWriter(File(getExternalFilesDir(null), "result.txt")))
        root = FrameLayout(this); sv = SurfaceView(this); sv.holder.addCallback(this); root.addView(sv); setContentView(root)
        startWatchdog()
        Thread({
            try { runAll() } catch (t: Throwable) { r("ABORT", false, Log.getStackTraceString(t)) }
            r("DONE", null, "bitti")
        }, "spike").start()
    }
    override fun surfaceCreated(h: SurfaceHolder) { surf = h.surface }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, he: Int) { sw = w; sh_ = he }
    override fun surfaceDestroyed(h: SurfaceHolder) { surf = null }

    private inner class M(val tag: String, opts: Map<String, String>) : MPV.EventObserver, MPV.LogObserver {
        val mpv = MPV()
        val events = ConcurrentLinkedQueue<String>()
        val props = ConcurrentHashMap<String, String>()
        @Volatile var ended: String? = null
        init {
            mpv.create(this@SpikeActivity)
            mpv.setOptionString("config", "no")
            mpv.setOptionString("idle", "once")
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("ao", "audiotrack,opensles,null")
            mpv.setOptionString("tls-verify", "no")
            mpv.setOptionString("sub-font-provider", "none")
            mpv.setOptionString("sub-font", "subfont")
            opts.forEach { (k, v) ->
                val rc = mpv.setOptionString(k, v)
                if (rc < 0) Log.w("SPIKE", "opt $k=$v rc=$rc")
            }
            mpv.addObserver(this); mpv.addLogObserver(this)
            mpv.init()
            mpv.observeProperty("time-pos", 5)
            mpv.observeProperty("sub-text", 1)
        }
        override fun eventProperty(p: String) {}
        override fun eventProperty(p: String, v: Long) { props[p] = "$v" }
        override fun eventProperty(p: String, v: Boolean) { props[p] = "$v" }
        override fun eventProperty(p: String, v: String) { props[p] = v }
        override fun eventProperty(p: String, v: Double) { props[p] = "$v" }
        override fun eventProperty(p: String, v: MPVNode) { props[p] = v.toJson() }
        override fun event(id: Int, n: MPVNode) {
            events.add("$id:${n.toJson()}")
            if (id == 7) ended = n.toJson()
        }
        override fun logMessage(pre: String, lvl: Int, txt: String) {
            logs.add("$tag[$pre/$lvl] ${txt.trim()}")
            if (lvl <= 30 || pre.startsWith("vd") || pre.contains("vo") || (pre == "cplayer" && txt.contains("VO:")))
                Log.i("SPIKELOG", "$tag[$pre/$lvl] ${txt.trim()}")
        }
        fun d(p: String) = runCatching { mpv.getPropertyDouble(p) }.getOrNull()
        fun s(p: String) = runCatching { mpv.getPropertyString(p) }.getOrNull()
        fun close() { runCatching { mpv.destroy() }.onFailure { r("destroy-$tag", false, it.toString()) } }
    }

    private fun wait(ms: Long, c: () -> Boolean): Boolean {
        val t = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < t) { if (c()) return true; Thread.sleep(50) }
        return c()
    }
    private fun playing(m: M, ms: Long = 12000): Boolean {
        wait(ms) { (m.d("time-pos") ?: 0.0) > 0.4 }
        val a = m.d("time-pos") ?: return false
        Thread.sleep(900)
        val b = m.d("time-pos") ?: return false
        return b > a
    }
    private fun pixelCopy(): String {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val l = CountDownLatch(1); var rc = -1
        main.post { PixelCopy.request(sv, bmp, { c -> rc = c; l.countDown() }, main) }
        l.await(5, TimeUnit.SECONDS)
        return if (rc == 0) stat(bmp) else "pixelcopy rc=$rc"
    }
    private fun stat(b: Bitmap): String {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val set = HashSet<Int>(); var sum = 0L
        for (p in px) {
            sum += ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3
            set.add(p and 0xF0F0F0)
        }
        return "mean=${sum / px.size} distinct=${set.size}"
    }
    private fun distinct(s: String) = Regex("distinct=(\\d+)").find(s)?.groupValues?.get(1)?.toInt() ?: 0

    private fun attachSv(m: M) {
        wait(4000) { surf != null && sw > 0 }
        m.mpv.attachSurface(surf!!)
        m.mpv.setPropertyString("android-surface-size", "${sw}x$sh_")
        m.mpv.setOptionString("force-window", "yes")
    }
    private fun teardown(m: M) {
        step("${m.tag} stop"); m.mpv.command("stop")
        step("${m.tag} vo-null"); m.mpv.setPropertyString("vo", "null")
        m.mpv.setPropertyString("force-window", "no")
        step("${m.tag} detach"); m.mpv.detachSurface()
        step("${m.tag} close"); m.close()
    }

    private val HW = "mediacodec,mediacodec-copy,no"

    private fun runAll() {
        val dir = getExternalFilesDir(null)!!
        val t = File(dir, "t.mkv"); val h = File(dir, "h.mp4")
        val page = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        r("env", null, "sdk=${Build.VERSION.SDK_INT} page=$page files=${t.exists()},${h.exists()}")
        val stages = (intent.getStringExtra("stages") ?: "st,afx,flip,multi,hevc,proxy,detach").split(",")
        for (s in stages) {
            step("stage-$s")
            val ok = runCatching {
                when (s) {
                    "st" -> stVariants(t)
                    "afx" -> afEffect(t)
                    "flip" -> { flip(t, "no"); flip(t, HW) }
                    "multi" -> multi(t, h)
                    "hevc" -> hevc(h)
                    "proxy" -> proxy(t)
                    "order-now" -> order(t, false)
                    "app-all" -> order(t, false, APP)
                    "app-nocodecs" -> order(t, false, APP - "hwdec-codecs")
                    "app-nokeep" -> order(t, false, APP - "keep-open")
                    "app-noconfig" -> order(t, false, APP - "config" - "config-dir")
                    "order-wait" -> order(t, true)
                    "det-hw" -> detach(t, "hw", HW, false)
                    "det-sw" -> detach(t, "sw", "no", false)
                    "det-copy" -> detach(t, "copy", "mediacodec-copy", false)
                    "det-hw-vidno" -> detach(t, "hw-vidno", HW, true)
                }
            }
            ok.onFailure { r("stage-$s", false, it.toString()) }
            r("stage-end", null, s)
        }
        r("errlog", null, logs.filter { it.contains("/10]") }.toList().takeLast(10).joinToString(" ;; "))
    }

    private fun stVariants(t: File) {
        val variants = listOf(
            "st-gpu-sw" to mapOf("vo" to "gpu", "hwdec" to "no"),
            "st-gpu-hw" to mapOf("vo" to "gpu", "hwdec" to HW),
            "st-gpu-copy" to mapOf("vo" to "gpu", "hwdec" to "mediacodec-copy"),
            "st-embed" to mapOf("vo" to "mediacodec_embed", "hwdec" to "mediacodec"),
        )
        for ((name, opt) in variants) {
            step(name)
            runCatching { surfaceTexture(name, opt, t) }.onFailure { r(name, false, it.toString()) }
        }
    }

    /** Filtre sesi gercekten degistiriyor mu: ayni dosyada RMS olcumu. */
    private fun afEffect(t: File) {
        val m = M("af", mapOf("vo" to "null", "vid" to "no"))
        m.mpv.command("loadfile", t.path)
        playing(m)
        fun rms(chain: String): String? {
            m.mpv.setPropertyString("af", "@x:lavfi=[$chain,astats=metadata=1:reset=25]")
            Thread.sleep(1600)
            return m.s("af-metadata/x")?.let { Regex("Overall.RMS_level\":\"([^\"]+)").find(it)?.groupValues?.get(1) }
        }
        val base = rms("anull")
        val quiet = rms("volume=0.1")
        val eq = rms("equalizer=f=440:t=q:w=1:g=12")
        val rev = rms("aecho=0.8:0.9:60:0.8")
        val a = base?.toDoubleOrNull(); val b = quiet?.toDoubleOrNull(); val c = eq?.toDoubleOrNull()
        r("af-effect", a != null && b != null && c != null && a - b > 15 && c - a > 5, "rmsDb base=$base vol0.1=$quiet eq+12=$eq aecho=$rev af=${m.s("af")}")
        m.mpv.setPropertyString("af", "lavfi=[asetrate=44100*1.1224,aresample=44100,atempo=0.8909]"); Thread.sleep(700)
        r("af-pitch-chain", m.d("time-pos") != null && logs.none { it.startsWith("af[") && it.contains("/10]") }, "af=${m.s("af")} t=${m.d("time-pos")}")
        m.mpv.command("stop"); m.close()
    }

    private fun capture(): Bitmap? {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val l = CountDownLatch(1); var rc = -1
        main.post { PixelCopy.request(sv, bmp, { c -> rc = c; l.countDown() }, main) }
        l.await(5, TimeUnit.SECONDS)
        return if (rc == 0) bmp else null
    }
    private fun diff(a: Bitmap, b: Bitmap, mirror: Boolean): Double {
        var s = 0L
        for (y in 0 until 90) for (x in 0 until 160) {
            val p = a.getPixel(x, y); val q = b.getPixel(if (mirror) 159 - x else x, y)
            s += Math.abs((p and 255) - (q and 255)) + Math.abs((p shr 8 and 255) - (q shr 8 and 255))
        }
        return s / (160.0 * 90 * 2)
    }

    /** Ayna: kare durdurulup vf sonrasi goruntu gercekten aynalaniyor mu. */
    private fun flip(t: File, hw: String) {
        val name = "flip-" + if (hw == "no") "sw" else "hw"
        step(name)
        val m = M(name, mapOf("vo" to "gpu", "hwdec" to hw)); attachSv(m)
        m.mpv.command("loadfile", t.path); playing(m)
        m.mpv.command("seek", "10", "absolute+exact"); Thread.sleep(500)
        m.mpv.setPropertyBoolean("pause", true); Thread.sleep(700)
        val a = capture()!!
        m.mpv.setPropertyString("vf", "lavfi=[hflip]"); Thread.sleep(1500)
        val b = capture()!!
        val same = diff(a, b, false); val mir = diff(a, b, true)
        r(name, mir < same / 2, "same=$same mirrored=$mir hwcur=${m.s("hwdec-current")} vf=${m.s("vf")} pix=${m.s("video-params/pixelformat")} t=${m.d("time-pos")}")
        m.mpv.setPropertyString("vf", "")
        if (hw != "no") {
            step("flip-switch")
            m.mpv.setPropertyBoolean("pause", false); Thread.sleep(300)
            m.mpv.setPropertyString("vid", "no"); Thread.sleep(300)
            m.mpv.setPropertyString("hwdec", "mediacodec-copy")
            m.mpv.setPropertyString("vf", "lavfi=[hflip]")
            m.mpv.setPropertyString("vid", "auto"); Thread.sleep(1500)
            m.mpv.setPropertyBoolean("pause", true); Thread.sleep(700)
            val c = capture()!!
            m.mpv.setPropertyString("vf", ""); Thread.sleep(1500)
            val d = capture()!!
            val s2 = diff(c, d, false); val m2 = diff(c, d, true)
            r("flip-copy-runtime", m2 < s2 / 2, "same=$s2 mirrored=$m2 hwcur=${m.s("hwdec-current")} pix=${m.s("video-params/pixelformat")} t=${m.d("time-pos")}")
        }
        teardown(m); Thread.sleep(300)
    }

    private fun multi(t: File, h: File) {
        val m = M("a", mapOf("vo" to "gpu", "hwdec" to HW)); attachSv(m)
        m.mpv.command("loadfile", t.path); playing(m)
        val m2 = M("b", mapOf("vo" to "null", "vid" to "no", "ao" to "null"))
        m2.mpv.command("loadfile", h.path)
        val two = playing(m2) && playing(m, 3000)
        r("two-instances", two, "b.t=${m2.d("time-pos")} a.t=${m.d("time-pos")}")
        step("close-b"); m2.close(); Thread.sleep(300)
        r("after-close-b", playing(m, 3000), "a.t=${m.d("time-pos")}")
        step("teardown-a"); teardown(m); Thread.sleep(500)
    }

    private fun hevc(h: File) {
        val m = M("c", mapOf("vo" to "gpu", "hwdec" to HW)); attachSv(m)
        m.mpv.command("loadfile", h.path)
        val hv = playing(m)
        r("hevc10", hv, "fmt=${m.s("video-format")} pix=${m.s("video-params/pixelformat")} hw=${m.s("hwdec-current")} voDrop=${m.s("vo-drop-frame-count")} frameDrop=${m.s("frame-drop-count")} dec=${m.s("decoder-frame-drop-count")} fps=${m.s("estimated-vf-fps")} ${pixelCopy()}")
        step("teardown-c"); teardown(m); Thread.sleep(300)
    }

    private fun proxy(t: File) {
        val srv = RangeServer(t); srv.start()
        val m = M("d", mapOf("vo" to "gpu", "hwdec" to "no", "cache" to "no", "demuxer-max-bytes" to "65536", "demuxer-readahead-secs" to "0", "stream-buffer-size" to "4096")); attachSv(m)
        m.mpv.command("loadfile", "http://127.0.0.1:${srv.port}/tok123/t.mkv")
        val hp = playing(m)
        m.mpv.command("seek", "15", "absolute+exact"); Thread.sleep(1500)
        m.mpv.command("seek", "3", "absolute+exact"); Thread.sleep(1500)
        r("proxy-http", hp && (m.d("time-pos") ?: 0.0) in 3.0..7.0 && srv.reqs.any { it.contains("bytes=") && !it.endsWith("bytes=0-") }, "t=${m.d("time-pos")} reqs=${srv.reqs.size} ranges=${srv.reqs.take(8)} fmt=${m.s("file-format")} seekable=${m.s("seekable")}")
        step("teardown-d"); teardown(m); srv.stop(); Thread.sleep(300)
    }

    /** Uygulamadaki sira: vid=no ile yukle, sonra yuzey + vo=gpu + vid=auto. */
    private val APP = mapOf(
        "config" to "yes", "config-dir" to "/data/data/org.openplayer.spike/files",
        "keep-open" to "always", "hwdec-codecs" to "all", "volume-max" to "200", "sid" to "no",
        "demuxer-max-bytes" to "67108864", "demuxer-max-back-bytes" to "33554432", "idle" to "yes",
    )

    private fun order(t: File, waitVo: Boolean, extra: Map<String, String>? = null) {
        val name = if (extra != null) "app" + extra.size else if (waitVo) "order-wait" else "order-now"
        step(name)
        val m = M(name, (extra ?: emptyMap()) + mapOf("vo" to "null", "vid" to "no", "hwdec" to HW))
        m.mpv.command("loadfile", t.path); Thread.sleep(1200)
        wait(4000) { surf != null && sw > 0 }
        m.mpv.setPropertyString("vid", "no")
        m.mpv.attachSurface(surf!!)
        m.mpv.setPropertyString("android-surface-size", "${sw}x$sh_")
        m.mpv.setPropertyString("force-window", "yes")
        m.mpv.setPropertyString("vo", "gpu")
        if (waitVo) wait(3000) { m.s("current-vo") == "gpu" }
        val voAt = m.s("current-vo")
        m.mpv.setPropertyString("vid", "auto")
        Thread.sleep(2500)
        val hw = m.s("hwdec-current")
        r(name, hw == "mediacodec", "hw=$hw voBeforeVid=$voAt vo=${m.s("current-vo")} ${pixelCopy()} " +
            logs.filter { it.startsWith(name) && (it.contains("hwdec") || it.contains("device") || it.contains("interop")) }.toList().takeLast(4).joinToString(" ; "))
        teardown(m); Thread.sleep(300)
    }

    private fun detach(t: File, name: String, hw: String, vidFirst: Boolean) {
        step("$name play")
        val m = M(name, mapOf("vo" to "gpu", "hwdec" to hw)); attachSv(m)
        m.mpv.command("loadfile", t.path); playing(m)
        r("$name-play", true, "hw=${m.s("hwdec-current")} t=${m.d("time-pos")}")
        if (vidFirst) { step("$name vid=no"); m.mpv.setPropertyString("vid", "no"); Thread.sleep(400) }
        step("$name vo=null"); m.mpv.setPropertyString("vo", "null")
        step("$name force-window=no"); m.mpv.setPropertyString("force-window", "no")
        step("$name detachSurface"); m.mpv.detachSurface()
        Thread.sleep(500)
        step("$name read-pos"); val bg = m.d("time-pos") ?: 0.0; Thread.sleep(900)
        val after = m.d("time-pos") ?: 0.0
        r("$name-detach", after > bg, "bg=$bg after=$after vo=${m.s("current-vo")} vid=${m.s("vid")}")
        step("$name attach"); m.mpv.attachSurface(surf!!)
        m.mpv.setPropertyString("android-surface-size", "${sw}x$sh_")
        m.mpv.setPropertyString("force-window", "yes")
        step("$name vo=gpu"); m.mpv.setPropertyString("vo", "gpu")
        if (vidFirst) { step("$name vid=auto"); m.mpv.setPropertyString("vid", "auto") }
        Thread.sleep(2000)
        val rp = pixelCopy()
        r("$name-reattach", distinct(rp) > 20 && m.s("current-vo") == "gpu", "$rp vo=${m.s("current-vo")} hw=${m.s("hwdec-current")} t=${m.d("time-pos")}")
        step("$name teardown"); teardown(m); Thread.sleep(300)
    }

    private fun surfaceTexture(name: String, opt: Map<String, String>, file: File) {
        val gl = GlConsumer(); val surface = gl.start(1280, 720)
        val m = M(name, opt)
        m.mpv.attachSurface(surface)
        m.mpv.setPropertyString("android-surface-size", "1280x720")
        m.mpv.setOptionString("force-window", "yes")
        m.mpv.command("loadfile", file.path)
        val play = playing(m)
        Thread.sleep(1500)
        val f = gl.frames.get(); val a = gl.lastStat; val pix1 = gl.lastHash
        Thread.sleep(1200)
        val f2 = gl.frames.get()
        val good = play && f2 > f && distinct(gl.lastStat) > 20 && gl.lastHash != pix1
        r(name, good, "frames=$f->$f2 stat=$a/${gl.lastStat} hw=${m.s("hwdec-current")} vo=${m.s("current-vo")} t=${m.d("time-pos")} drop=${m.s("vo-drop-frame-count")}")
        runCatching { teardown(m) }.onFailure { r("$name-teardown", false, it.toString()) }
        gl.stop(); surface.release(); Thread.sleep(400)
    }
}

/** Vr360View benzeri: EGL pbuffer + OES doku; her karede FBO'ya cizip piksel okur. */
class GlConsumer {
    val frames = AtomicInteger()
    @Volatile var lastStat = ""
    @Volatile var lastHash = 0
    private lateinit var th: HandlerThread
    private lateinit var hd: Handler
    private lateinit var st: SurfaceTexture
    private var w = 0
    private var h = 0
    private var dpy = EGL14.EGL_NO_DISPLAY
    private var ctx = EGL14.EGL_NO_CONTEXT
    private var pb = EGL14.EGL_NO_SURFACE
    private var prog = 0
    private var tex = 0
    private var fbo = 0

    fun start(W: Int, H: Int): Surface {
        w = W; h = H
        th = HandlerThread("gl"); th.start(); hd = Handler(th.looper)
        val l = CountDownLatch(1); var s: Surface? = null; var err: Throwable? = null
        hd.post { try { s = init() } catch (t: Throwable) { err = t }; l.countDown() }
        l.await(); err?.let { throw it }
        return s!!
    }

    private fun init(): Surface {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
        val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
        ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pb = EGL14.eglCreatePbufferSurface(dpy, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, pb, pb, ctx)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        val vs = "attribute vec2 p; varying vec2 u; void main(){u=(p+1.0)/2.0;gl_Position=vec4(p,0.,1.);}"
        val fs = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 u; uniform samplerExternalOES s; void main(){gl_FragColor=texture2D(s,u);}"
        prog = GLES20.glCreateProgram()
        for ((ty, src) in listOf(GLES20.GL_VERTEX_SHADER to vs, GLES20.GL_FRAGMENT_SHADER to fs)) {
            val sh = GLES20.glCreateShader(ty); GLES20.glShaderSource(sh, src); GLES20.glCompileShader(sh); GLES20.glAttachShader(prog, sh)
        }
        GLES20.glLinkProgram(prog)
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo = f[0]
        val ct = IntArray(1); GLES20.glGenTextures(1, ct, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ct[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 160, 90, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ct[0], 0)
        st = SurfaceTexture(tex); st.setDefaultBufferSize(w, h)
        st.setOnFrameAvailableListener({ draw() }, hd)
        return Surface(st)
    }

    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    private fun draw() {
        st.updateTexImage(); frames.incrementAndGet()
        GLES20.glViewport(0, 0, 160, 90); GLES20.glUseProgram(prog)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        val a = GLES20.glGetAttribLocation(prog, "p")
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val b = ByteBuffer.allocateDirect(160 * 90 * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, 160, 90, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
        val set = HashSet<Int>(); var sum = 0L; b.rewind(); val ib = b.asIntBuffer(); var hash = 0
        while (ib.hasRemaining()) {
            val p = ib.get(); hash = hash * 31 + p; set.add(p and 0xF0F0F0)
            sum += ((p and 255) + (p shr 8 and 255) + (p shr 16 and 255)) / 3
        }
        lastStat = "mean=${sum / (160 * 90)} distinct=${set.size}"; lastHash = hash
    }

    fun stop() {
        val l = CountDownLatch(1)
        hd.post {
            st.release()
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, pb); EGL14.eglDestroyContext(dpy, ctx); l.countDown()
        }
        l.await(3, TimeUnit.SECONDS); th.quitSafely()
    }
}

/** En kucuk Range destekli HTTP sunucusu. */
class RangeServer(private val f: File) {
    private val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port get() = ss.localPort
    val reqs = CopyOnWriteArrayList<String>()
    @Volatile private var run = true

    fun start() {
        Thread {
            while (run) {
                val c = try { ss.accept() } catch (_: IOException) { break }
                Thread { serve(c) }.start()
            }
        }.start()
    }
    fun stop() { run = false; ss.close() }

    private fun serve(c: Socket) = c.use {
        val br = BufferedReader(InputStreamReader(c.getInputStream()))
        val first = br.readLine() ?: return@use
        var range: String? = null
        while (true) {
            val l = br.readLine() ?: break
            if (l.isEmpty()) break
            if (l.lowercase().startsWith("range:")) range = l.substringAfter(':').trim()
        }
        reqs.add("${first.substringBefore(' ')} ${range ?: "-"}")
        val len = f.length(); var st = 0L; var en = len - 1
        range?.let {
            Regex("bytes=(\\d*)-(\\d*)").find(it)?.let { m ->
                if (m.groupValues[1].isNotEmpty()) st = m.groupValues[1].toLong()
                if (m.groupValues[2].isNotEmpty()) en = m.groupValues[2].toLong()
            }
        }
        val o = BufferedOutputStream(c.getOutputStream())
        val head = if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $st-$en/$len\r\n" else "HTTP/1.1 200 OK\r\n"
        o.write((head + "Accept-Ranges: bytes\r\nContent-Type: video/x-matroska\r\nContent-Length: ${en - st + 1}\r\nConnection: close\r\n\r\n").toByteArray())
        if (first.startsWith("GET")) try {
            RandomAccessFile(f, "r").use { r ->
                r.seek(st); val b = ByteArray(65536); var left = en - st + 1
                while (left > 0) {
                    val n = r.read(b, 0, minOf(left, 65536L).toInt())
                    if (n < 0) break
                    o.write(b, 0, n); left -= n
                }
            }
        } catch (_: IOException) {}
        runCatching { o.flush() }
    }
}
