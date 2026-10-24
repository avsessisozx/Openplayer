package org.openplayer.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Lens türü: görüntünün küreye / sanal ekrana nasıl yerleştirileceği. */
enum class VrLens(val label: String) {
    NORMAL("Normal lens (sanal ekran)"),
    FISHEYE("Balık gözü lens"),
    SPHERE360("360° tam küre"),
    SPHERE180("180° yarım küre"),
}

/** 360° / VR180 / balık gözü / sanal ekran ve 3B video biçimleri (stereo: 0 = 3B değil, 1 = üst-alt, 2 = yan yana). */
enum class VrMode(val label: String, val lens: VrLens, val stereo: Int) {
    NONE("Kapalı", VrLens.SPHERE360, 0),
    FULL("360° (tek görüntü)", VrLens.SPHERE360, 0),
    FULL_TB("360° üst-alt 3B", VrLens.SPHERE360, 1),
    FULL_SBS("360° yan yana 3B", VrLens.SPHERE360, 2),
    HALF("180° (tek görüntü)", VrLens.SPHERE180, 0),
    HALF_TB("180° üst-alt 3B", VrLens.SPHERE180, 1),
    HALF_SBS("180° yan yana 3B", VrLens.SPHERE180, 2),
    FISHEYE("Balık gözü (tek görüntü)", VrLens.FISHEYE, 0),
    FISHEYE_TB("Balık gözü üst-alt 3B", VrLens.FISHEYE, 1),
    FISHEYE_SBS("Balık gözü yan yana 3B", VrLens.FISHEYE, 2),
    FLAT("Normal lens (tek görüntü)", VrLens.NORMAL, 0),
    FLAT_TB("Normal lens üst-alt 3B", VrLens.NORMAL, 1),
    FLAT_SBS("Normal lens yan yana 3B", VrLens.NORMAL, 2);

    /** Yarım küre üzerine çizilir (180° ve balık gözü). */
    val halfSphere: Boolean get() = lens == VrLens.SPHERE180 || lens == VrLens.FISHEYE

    companion object {
        /** Seçim menüsünde gösterilen sıra. */
        val choices = listOf(FULL, FULL_TB, FULL_SBS, HALF, HALF_TB, HALF_SBS, FISHEYE, FISHEYE_TB, FISHEYE_SBS, FLAT, FLAT_TB, FLAT_SBS)

        /** Lens ve 3B biçiminden mod bulur. */
        fun of(lens: VrLens, stereo: Int): VrMode =
            entries.first { it != NONE && it.lens == lens && it.stereo == stereo }
    }
}

/** 360° algılama ve bilinen videoların kaydı (listede "360°" etiketi için). */
object Vr360 {
    private const val PREFS = "vr360"

    /** Video biçiminden (koddaki gibi) küresel video olup olmadığını anlar; bilmiyorsa NONE. */
    fun detect(format: Format?, name: String): VrMode {
        if (format == null) return VrMode.NONE
        val hasProjection = format.projectionData != null
        val lower = name.lowercase()
        val is180 = "180" in lower || "vr180" in lower
        val stereo = format.stereoMode
        if (!hasProjection) return VrMode.NONE
        return when {
            is180 && stereo == C.STEREO_MODE_TOP_BOTTOM -> VrMode.HALF_TB
            is180 && stereo == C.STEREO_MODE_LEFT_RIGHT -> VrMode.HALF_SBS
            is180 -> VrMode.HALF
            stereo == C.STEREO_MODE_TOP_BOTTOM -> VrMode.FULL_TB
            stereo == C.STEREO_MODE_LEFT_RIGHT -> VrMode.FULL_SBS
            else -> VrMode.FULL
        }
    }

    private val sphericalName = Regex("(?<![0-9])360(?![0-9p])|vr180|_vr(?![a-z])|[ ._-]vr[ ._-]|equirect")

    /** Dosya adından tahmin (yalnızca liste etiketi için; oynatmada üstveri esas alınır). */
    fun nameLooksSpherical(name: String): Boolean {
        val l = name.lowercase()
        return sphericalName.containsMatchIn(l)
    }

    fun remember(context: Context, uri: String, mode: VrMode) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (mode == VrMode.NONE) p.edit().remove(uri).apply() else p.edit().putString(uri, mode.name).apply()
    }

    fun saved(context: Context, uri: String): VrMode? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uri, null)
            ?.let { runCatching { VrMode.valueOf(it) }.getOrNull() }

    fun isSpherical(context: Context, uri: String, name: String): Boolean =
        (saved(context, uri)?.let { it.lens != VrLens.NORMAL } ?: false) || nameLooksSpherical(name)
}

/**
 * Videoyu bir kürenin içinden gösteren GL yüzeyi. ExoPlayer'ın çıktısı [onSurface] ile verilen
 * yüzeye yazılır; bakış yönü [yaw]/[pitch]/[fov] ile (veya hareket sensörüyle) değişir.
 *
 * [cardboard] açıkken ekran ikiye bölünür: her göz için küre ayrı çizilir ve lens için
 * barrel (fıçı) ön-distorsiyonu uygulanır; böylece telefon bir VR gözlüğüne yerleştirilebilir.
 */
class Vr360View(context: Context, private val onSurface: (Surface?) -> Unit) : GLSurfaceView(context), SensorEventListener {

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var fov = 90f
    @Volatile var mode: VrMode = VrMode.FULL
        set(value) {
            field = value
            requestRender()
        }

    /** VR gözlük (Cardboard) modu: iki göz yan yana + lens distorsiyonu. */
    @Volatile var cardboard = false
        set(value) {
            field = value
            requestRender()
        }

    /** Görüntünün kendi ekseni etrafında eğimi (iki parmakla döndürme), derece. */
    @Volatile var roll = 0f
        set(value) {
            field = value
            requestRender()
        }

    /** Ufuk düzeltme: hareket sensöründe cihazın yana yatması görüntüye yansımaz. */
    @Volatile var horizonLevel = true
        set(value) {
            field = value
            requestRender()
        }

    /** Videonun / fotoğrafın en-boy oranı (balık gözü ve sanal ekran şekli için). */
    @Volatile var videoAspect = 16f / 9f
        set(value) {
            field = value.coerceIn(0.2f, 6f)
            requestRender()
        }

    private var useSensor = false
    private var sensorAlt = false
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** "Bakış yönünü sıfırla" anında yakalanan sensör yöneliminin tersi; sonraki kareler buna göre. */
    private val recenterMat = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val tmpRot = FloatArray(16)
    private val tmpRemap = FloatArray(16)
    private var sensorOn = false

    private val renderer = Renderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = false
    }

    fun look(dxPx: Float, dyPx: Float) {
        val k = fov / height.coerceAtLeast(1)
        yaw -= dxPx * k
        pitch = (pitch - dyPx * k).coerceIn(-89f, 89f)
        requestRender()
    }

    /** Parmak ekrandayken otomatik dönüş durur. */
    @Volatile var touching = false

    @Volatile private var autoSpeed = 0f
    private var lastAutoNs = 0L
    private val autoTick = object : Runnable {
        override fun run() {
            if (autoSpeed == 0f || !isAttachedToWindow) {
                lastAutoNs = 0L
                return
            }
            val now = System.nanoTime()
            if (lastAutoNs != 0L && !touching) yaw += autoSpeed * (now - lastAutoNs) / 1e9f
            lastAutoNs = now
            requestRender()
            postOnAnimation(this)
        }
    }

    val autoRotating: Boolean get() = autoSpeed != 0f

    /** Panorama otomatik dönüşü (derece/sn). */
    fun setAutoRotate(on: Boolean) {
        autoSpeed = if (on) 8f else 0f
        lastAutoNs = 0L
        removeCallbacks(autoTick)
        if (on) postOnAnimation(autoTick)
    }

    /** Bazı cihazlarda oyun dönüş vektörü yerine standart dönüş vektörü daha doğru çalışır (sensör türü 2). */
    fun setSensorAlt(on: Boolean) {
        if (sensorAlt == on) return
        sensorAlt = on
        if (sensorOn) {
            sensors.unregisterListener(this)
            sensorOn = false
            updateSensor()
        }
    }

    fun zoomFov(factor: Float) {
        fov = (fov / factor).coerceIn(40f, 110f)
        requestRender()
    }

    private var photo: android.graphics.Bitmap? = null

    /** Videonun yerine tek bir fotoğrafı (ör. eş dikdörtgen 360° JPEG) gösterir. */
    fun showBitmap(bmp: android.graphics.Bitmap) {
        photo = bmp
        videoAspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        queueEvent { renderer.drawPhoto() }
    }

    fun resetLook() {
        roll = 0f
        yaw = 0f
        pitch = 0f
        fov = 90f
        // Sensör açıksa o anki yönelimi yeni "ileri" kabul et.
        synchronized(sensorMatrix) { Matrix.invertM(recenterMat, 0, sensorMatrix, 0) }
        requestRender()
    }

    fun setSensor(on: Boolean) {
        useSensor = on
        updateSensor()
        requestRender()
    }

    val sensorEnabled get() = useSensor

    private fun updateSensor() {
        val want = useSensor && isAttachedToWindow
        if (want == sensorOn) return
        sensorOn = want
        if (want) {
            val s = if (sensorAlt) {
                sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            } else {
                sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            }
            if (s == null) {
                useSensor = false
                sensorOn = false
            } else sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        } else sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(tmpRot, event.values)
        // Yatay tutuş için eksenleri çevir (ekran döndürmesine göre).
        val rotation = (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
        val (ax, ay) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
        }
        SensorManager.remapCoordinateSystem(tmpRot, ax, ay, tmpRemap)
        synchronized(sensorMatrix) { System.arraycopy(tmpRemap, 0, sensorMatrix, 0, 16) }
        requestRender()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSensor()
        if (autoSpeed != 0f) postOnAnimation(autoTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoTick)
        if (sensorOn) {
            sensors.unregisterListener(this)
            sensorOn = false
        }
        super.onDetachedFromWindow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurface(null)
        super.surfaceDestroyed(holder)
    }

    override fun onPause() {
        if (sensorOn) {
            sensors.unregisterListener(this)
            sensorOn = false
        }
        onSurface(null)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updateSensor()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var texId = 0
        private var st: SurfaceTexture? = null
        private var surface: Surface? = null
        private val texMatrix = FloatArray(16)
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private val tmp = FloatArray(16)
        private var aspect = 1f
        private var surfaceW = 0
        private var surfaceH = 0
        private var meshKeyBuilt = ""
        private var vertices: FloatBuffer? = null
        private var indices: ShortBuffer? = null
        private var indexCount = 0
        private var uMvp = 0
        private var uTex = 0
        private var uCrop = 0
        private var aPos = 0
        private var aUv = 0

        // Gözlük modu: lens distorsiyon geçişi ve göz başına ara doku (FBO).
        private var distProgram = 0
        private var dPos = 0
        private var dUv = 0
        private var uDistTex = 0
        private var uLensCenter = 0
        private var uK1 = 0
        private var uK2 = 0
        private var quad: FloatBuffer? = null
        private var fbo = 0
        private var fboTex = 0
        private var fboW = 0
        private var fboH = 0

        private val vs = """
            uniform mat4 uMvp;
            uniform mat4 uTex;
            uniform vec4 uCrop;
            attribute vec3 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vec2 t = uCrop.xy + aUv * uCrop.zw;
                vUv = (uTex * vec4(t, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uSampler;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uSampler, vUv); }
        """.trimIndent()

        // Tam ekran dörtgen; göz dokusunu barrel distorsiyonla ekrana çizer.
        private val distVs = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val distFs = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uLensCenter;
            uniform float uK1;
            uniform float uK2;
            varying vec2 vUv;
            void main() {
                vec2 r = vUv - uLensCenter;
                float r2 = dot(r, r);
                float f = 1.0 + uK1 * r2 + uK2 * r2 * r2;
                vec2 src = uLensCenter + r * f;
                if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, src);
                }
            }
        """.trimIndent()

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        private fun link(vsrc: String, fsrc: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vsrc))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fsrc))
            GLES20.glLinkProgram(p)
            return p
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            st?.release()
            surface?.release()
            // GL bağlamı yeniden oluştu: eski FBO kimlikleri geçersiz.
            fbo = 0
            fboTex = 0
            fboW = 0
            fboH = 0

            program = link(vs, fs)
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uCrop = GLES20.glGetUniformLocation(program, "uCrop")
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")

            distProgram = link(distVs, distFs)
            dPos = GLES20.glGetAttribLocation(distProgram, "aPos")
            dUv = GLES20.glGetAttribLocation(distProgram, "aUv")
            uDistTex = GLES20.glGetUniformLocation(distProgram, "uTex")
            uLensCenter = GLES20.glGetUniformLocation(distProgram, "uLensCenter")
            uK1 = GLES20.glGetUniformLocation(distProgram, "uK1")
            uK2 = GLES20.glGetUniformLocation(distProgram, "uK2")

            // Tam ekran dörtgen: [x, y, u, v] × 4 (triangle strip).
            val q = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            q.put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ))
            q.position(0)
            quad = q

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val tex = SurfaceTexture(texId)
            tex.setOnFrameAvailableListener { requestRender() }
            st = tex
            val sf = Surface(tex)
            surface = sf
            meshKeyBuilt = ""
            buildMesh()
            post { onSurface(sf) }
            if (photo != null) drawPhoto()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
            aspect = width.toFloat() / height.coerceAtLeast(1)
        }

        /** Göz başına ara dokuyu (FBO) istenen boyutta hazırlar; boyut değişince yeniden kurar. */
        private fun ensureFbo(w: Int, h: Int) {
            if (fbo != 0 && w == fboW && h == fboH) return
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            fbo = fb[0]
            val tx = IntArray(1)
            GLES20.glGenTextures(1, tx, 0)
            fboTex = tx[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            fboW = w
            fboH = h
        }

        override fun onDrawFrame(gl: GL10?) {
            val tex = st ?: return
            runCatching { tex.updateTexImage() }
            tex.getTransformMatrix(texMatrix)

            if (meshKey() != meshKeyBuilt) buildMesh()

            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            computeView()

            if (!cardboard || surfaceW <= 1 || surfaceH <= 1) {
                // Tek görünüm (normal 360°).
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
                Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
                val c = eyeCrop(0)
                drawSphere(mvp, c[0], c[1], c[2], c[3])
                return
            }

            // Gözlük modu: iki göz + FBO + barrel distorsiyon.
            val eyeW = surfaceW / 2
            val eyeH = surfaceH
            ensureFbo(eyeW, eyeH)
            val eyeAspect = eyeW.toFloat() / eyeH.coerceAtLeast(1)
            Matrix.perspectiveM(proj, 0, fov, eyeAspect, 0.1f, 10f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            // Ekranı bir kez siyaha boya (gözler arası ve kenarlar siyah kalsın).
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            for (eye in 0..1) {
                val c = eyeCrop(eye)
                // 1) Küreyi göz dokusuna çiz.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glViewport(0, 0, eyeW, eyeH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawSphere(mvp, c[0], c[1], c[2], c[3])
                // 2) Göz dokusunu ekranın ilgili yarısına lens distorsiyonuyla çiz.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(eye * eyeW, 0, eyeW, eyeH)
                drawDistortion()
            }
        }

        /** Bakış yönü matrisini ([view]) sensör veya parmak girdisine göre hesaplar. */
        private fun computeView() {
            Matrix.setIdentityM(view, 0)
            if (sensorOn) {
                // recenter * sensör: "sıfırla" anından bu yana göreli dönüş.
                synchronized(sensorMatrix) { Matrix.multiplyMM(view, 0, recenterMat, 0, sensorMatrix, 0) }
                // GL dünyası (Y yukarı) ← sensör dünyası (Z yukarı)
                Matrix.rotateM(view, 0, -90f, 1f, 0f, 0f)
                if (horizonLevel) {
                    // Ufuk düzeltme: yalnız bakış yönünü al, cihazın yana yatmasını at (yukarı = +Y).
                    val fx = -view[2]
                    val fy = -view[6]
                    val fz = -view[10]
                    if (kotlin.math.abs(fy) < 0.995f) {
                        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, fx, fy, fz, 0f, 1f, 0f)
                    }
                }
                // Parmakla eklenen kaydırma, sensörün üstüne uygulanır.
                Matrix.setIdentityM(tmp, 0)
                Matrix.rotateM(tmp, 0, pitch, 1f, 0f, 0f)
                Matrix.rotateM(tmp, 0, yaw, 0f, 1f, 0f)
                val base = view.copyOf()
                Matrix.multiplyMM(view, 0, tmp, 0, base, 0)
            } else {
                Matrix.rotateM(view, 0, pitch, 1f, 0f, 0f)
                Matrix.rotateM(view, 0, yaw, 0f, 1f, 0f)
            }
            val r = roll
            if (r != 0f) {
                // Görüntüyü kendi ekseni etrafında döndür (iki parmakla döndürme).
                Matrix.setRotateM(tmp, 0, r, 0f, 0f, 1f)
                val base = view.copyOf()
                Matrix.multiplyMM(view, 0, tmp, 0, base, 0)
            }
        }

        /** Göz için doku kırpması: [x, y, genişlik, yükseklik] (0..1). 3B'de her göz kendi yarısını alır. */
        private fun eyeCrop(eye: Int): FloatArray = when (mode.stereo) {
            1 -> if (eye == 0) floatArrayOf(0f, 0.5f, 1f, 0.5f) else floatArrayOf(0f, 0f, 1f, 0.5f) // üst-alt: üst = sol göz
            2 -> if (eye == 0) floatArrayOf(0f, 0f, 0.5f, 1f) else floatArrayOf(0.5f, 0f, 0.5f, 1f) // yan yana: sol = sol göz
            else -> floatArrayOf(0f, 0f, 1f, 1f)
        }

        private fun drawSphere(mvpMat: FloatArray, cx: Float, cy: Float, cz: Float, cw: Float) {
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMat, 0)
            GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
            GLES20.glUniform4f(uCrop, cx, cy, cz, cw)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

            val vb = vertices ?: return
            val ib = indices ?: return
            vb.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
            vb.position(3)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, vb)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ib)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun drawDistortion() {
            GLES20.glUseProgram(distProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glUniform1i(uDistTex, 0)
            GLES20.glUniform2f(uLensCenter, 0.5f, 0.5f)
            GLES20.glUniform1f(uK1, 0.22f)
            GLES20.glUniform1f(uK2, 0.24f)

            val q = quad ?: return
            q.position(0)
            GLES20.glEnableVertexAttribArray(dPos)
            GLES20.glVertexAttribPointer(dPos, 2, GLES20.GL_FLOAT, false, 16, q)
            q.position(2)
            GLES20.glEnableVertexAttribArray(dUv)
            GLES20.glVertexAttribPointer(dUv, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(dPos)
            GLES20.glDisableVertexAttribArray(dUv)
        }

        /** Göz başına dokunun en-boy oranı (3B kırpma yarıya böler). */
        private fun eyeVideoAspect(): Float {
            val a = videoAspect
            return when (mode.stereo) {
                1 -> a * 2f
                2 -> a / 2f
                else -> a
            }
        }

        private fun meshKey(): String = "${mode.lens}|${(eyeVideoAspect() * 100).toInt()}"

        /** Lens türüne göre ağ: küre / yarım küre (360°, 180°), balık gözü UV'li yarım küre veya düz sanal ekran. */
        private fun buildMesh() {
            meshKeyBuilt = meshKey()
            val lens = mode.lens
            val flat = lens == VrLens.NORMAL
            val lonSteps = if (flat) 1 else 64
            val latSteps = if (flat) 1 else 32
            val half = mode.halfSphere
            val lonSpan = if (half) Math.PI else 2 * Math.PI
            val eyeAspect = eyeVideoAspect()
            val vb = ByteBuffer.allocateDirect((lonSteps + 1) * (latSteps + 1) * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (j in 0..latSteps) {
                for (i in 0..lonSteps) {
                    if (flat) {
                        // Kameranın önünde 1 birim uzakta, 1.6 birim genişliğinde sanal ekran.
                        val w = 1.6f
                        val h = w / eyeAspect
                        val x = (i - 0.5f) * w
                        val y = (0.5f - j) * h
                        vb.put(x); vb.put(y); vb.put(-1f)
                        vb.put(i.toFloat()); vb.put(1f - j.toFloat())
                        continue
                    }
                    val lat = Math.PI / 2 - Math.PI * j / latSteps
                    val lon = -lonSpan / 2 + lonSpan * i / lonSteps
                    val x = (cos(lat) * sin(lon)).toFloat()
                    val y = sin(lat).toFloat()
                    val z = (-cos(lat) * cos(lon)).toFloat()
                    vb.put(x); vb.put(y); vb.put(z)
                    if (lens == VrLens.FISHEYE) {
                        // Eşit uzaklıklı (equidistant) 180° balık gözü: görüntü dairesi yüksekliği doldurur.
                        val theta = Math.acos((-z).toDouble().coerceIn(-1.0, 1.0))
                        val r = theta / Math.PI
                        val phi = Math.atan2(y.toDouble(), x.toDouble())
                        vb.put((0.5 + r * cos(phi) / eyeAspect).toFloat())
                        vb.put((0.5 + r * sin(phi)).toFloat())
                    } else {
                        vb.put(i.toFloat() / lonSteps)
                        vb.put(1f - j.toFloat() / latSteps)
                    }
                }
            }
            val ib = ByteBuffer.allocateDirect(lonSteps * latSteps * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (j in 0 until latSteps) {
                for (i in 0 until lonSteps) {
                    val a = (j * (lonSteps + 1) + i).toShort()
                    val b = (a + 1).toShort()
                    val c = (a + lonSteps + 1).toShort()
                    val d = (c + 1).toShort()
                    ib.put(a); ib.put(c); ib.put(b)
                    ib.put(b); ib.put(c); ib.put(d)
                }
            }
            vb.position(0)
            ib.position(0)
            vertices = vb
            indices = ib
            indexCount = lonSteps * latSteps * 6
        }

        /** Fotoğraf modu: bitmap'i SurfaceTexture'a çizer (video karesi gibi küreye uygulanır). */
        fun drawPhoto() {
            val b = photo ?: return
            val tex = st ?: return
            val sf = surface ?: return
            runCatching {
                tex.setDefaultBufferSize(b.width, b.height)
                val c = sf.lockCanvas(null)
                c.drawBitmap(b, 0f, 0f, null)
                sf.unlockCanvasAndPost(c)
            }
            requestRender()
        }
    }
}
