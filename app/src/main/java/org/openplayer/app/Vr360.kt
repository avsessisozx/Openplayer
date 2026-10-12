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

/** 360° / VR180 / 3B video biçimleri. */
enum class VrMode(val label: String, val halfSphere: Boolean, val stereo: Int) {
    NONE("Kapalı", false, 0),
    FULL("360° (tek görüntü)", false, 0),
    FULL_TB("360° üst-alt 3B", false, 1),
    FULL_SBS("360° yan yana 3B", false, 2),
    HALF("180° (tek görüntü)", true, 0),
    HALF_TB("180° üst-alt 3B", true, 1),
    HALF_SBS("180° yan yana 3B", true, 2);

    companion object {
        /** Seçim menüsünde gösterilen sıra. */
        val choices = listOf(FULL, FULL_TB, FULL_SBS, HALF, HALF_TB, HALF_SBS)
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
        saved(context, uri) != null || nameLooksSpherical(name)
}

/**
 * Videoyu bir kürenin içinden gösteren GL yüzeyi. ExoPlayer'ın çıktısı [onSurface] ile verilen
 * yüzeye yazılır; bakış yönü [yaw]/[pitch]/[fov] ile (veya hareket sensörüyle) değişir.
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

    private var useSensor = false
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
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

    fun zoomFov(factor: Float) {
        fov = (fov / factor).coerceIn(40f, 110f)
        requestRender()
    }

    fun resetLook() {
        yaw = 0f
        pitch = 0f
        fov = 90f
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
            val s = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
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
    }

    override fun onDetachedFromWindow() {
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
        private var meshHalf = false
        private var vertices: FloatBuffer? = null
        private var indices: ShortBuffer? = null
        private var indexCount = 0
        private var uMvp = 0
        private var uTex = 0
        private var uCrop = 0
        private var aPos = 0
        private var aUv = 0

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

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            st?.release()
            surface?.release()
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, v)
            GLES20.glAttachShader(program, f)
            GLES20.glLinkProgram(program)
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uCrop = GLES20.glGetUniformLocation(program, "uCrop")
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")

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
            meshHalf = !mode.halfSphere
            buildMesh(mode.halfSphere)
            post { onSurface(sf) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat() / height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            val tex = st ?: return
            runCatching { tex.updateTexImage() }
            tex.getTransformMatrix(texMatrix)

            val half = mode.halfSphere
            if (half != meshHalf) buildMesh(half)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
            Matrix.setIdentityM(view, 0)
            if (sensorOn) {
                synchronized(sensorMatrix) { System.arraycopy(sensorMatrix, 0, view, 0, 16) }
                // GL dünyası (Y yukarı) ← sensör dünyası (Z yukarı)
                Matrix.rotateM(view, 0, -90f, 1f, 0f, 0f)
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
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
            // Doku (0,0) = görüntünün sol altı; 3B'de yalnızca sol/üst göz kullanılır.
            when (mode.stereo) {
                1 -> GLES20.glUniform4f(uCrop, 0f, 0.5f, 1f, 0.5f)
                2 -> GLES20.glUniform4f(uCrop, 0f, 0f, 0.5f, 1f)
                else -> GLES20.glUniform4f(uCrop, 0f, 0f, 1f, 1f)
            }
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

        /** Enlem/boylam ızgarasıyla küre (180° için yarım küre). Kamera kürenin içindedir. */
        private fun buildMesh(half: Boolean) {
            meshHalf = half
            val lonSteps = 64
            val latSteps = 32
            val lonSpan = if (half) Math.PI else 2 * Math.PI
            val vb = ByteBuffer.allocateDirect((lonSteps + 1) * (latSteps + 1) * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (j in 0..latSteps) {
                val lat = Math.PI / 2 - Math.PI * j / latSteps
                for (i in 0..lonSteps) {
                    val lon = -lonSpan / 2 + lonSpan * i / lonSteps
                    vb.put((cos(lat) * sin(lon)).toFloat())
                    vb.put(sin(lat).toFloat())
                    vb.put((-cos(lat) * cos(lon)).toFloat())
                    vb.put(i.toFloat() / lonSteps)
                    vb.put(1f - j.toFloat() / latSteps)
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
    }
}
