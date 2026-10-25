package org.openplayer.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 360° / 180° / balık gözü fotoğraf görüntüleyici. Aynı GL küre görünümünü ([Vr360View]) videodan bağımsız
 * olarak kullanır: kaydırarak bakış, çimdikle yakınlaştırma, iki parmakla döndürme, hareket sensörü ve VR gözlük.
 */
class Vr360PhotoActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var view: Vr360View
    private lateinit var bar: LinearLayout
    private var mode = VrMode.FULL
    private var twisting = false
    private var lastTwist = 0f
    private var scaling = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings.get(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = Vr360View(this) { }
        view.horizonLevel = settings.vrHorizonLevel
        view.setSensorAlt(settings.vrSensorAlt)
        view.setAutoRotate(settings.vrAutoRotate)

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(chip(Tx.s(R.string.vr360photo_008)) { pickLens() })
            addView(chip("3B") { pickStereo() })
            addView(chip(Tx.s(R.string.vr360photo_001)) { toggleSensor() })
            addView(chip(Tx.s(R.string.vr360photo_002)) { toggleCardboard() })
            addView(chip(Tx.s(R.string.vr360photo_003)) { view.roll = 0f; toast(Tx.s(R.string.playeracti_033)) })
            addView(chip(Tx.s(R.string.nowplaying_009)) { finish() })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)
        view.setOnTouchListener(touch)

        val uri = intent.data
        if (uri == null) {
            toast(Tx.s(R.string.vr360photo_004))
            finish()
            return
        }
        Thread {
            val bmp = decode(uri)
            runOnUiThread {
                if (bmp == null) {
                    toast(Tx.s(R.string.vr360photo_005))
                    finish()
                } else {
                    val ratio = bmp.width.toFloat() / bmp.height
                    mode = if (ratio >= 1.8f) VrMode.FULL else VrMode.FLAT
                    view.mode = mode
                    view.showBitmap(bmp)
                    toast(if (mode == VrMode.FULL) Tx.s(R.string.vr360photo_007) else Tx.s(R.string.vr360photo_006))
                }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    /** Büyük fotoğrafı doku sınırına sığacak şekilde küçülterek okur. */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun pickLens() {
        val lenses = VrLens.entries
        AlertDialog.Builder(this)
            .setTitle(Tx.s(R.string.playermenu_120))
            .setItems(lenses.map { (if (mode.lens == it) "✓ " else "") + it.label }.toTypedArray()) { _, i ->
                mode = VrMode.of(lenses[i], mode.stereo)
                view.mode = mode
            }
            .show()
    }

    private fun pickStereo() {
        val names = listOf(Tx.s(R.string.playermenu_106), Tx.s(R.string.playermenu_105), Tx.s(R.string.playermenu_144))
        AlertDialog.Builder(this)
            .setTitle(Tx.s(R.string.playermenu_121))
            .setItems(names.mapIndexed { i, n -> (if (mode.stereo == i) "✓ " else "") + n }.toTypedArray()) { _, i ->
                mode = VrMode.of(mode.lens, i)
                view.mode = mode
            }
            .show()
    }

    private fun toggleSensor() {
        view.setSensor(!view.sensorEnabled)
        if (view.sensorEnabled) {
            if (settings.vrLockRotation) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            view.resetLook()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun toggleCardboard() {
        val on = !view.cardboard
        view.cardboard = on
        if (on) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            view.setSensor(true)
            view.resetLook()
            bar.visibility = View.GONE
        }
    }

    private fun angle(e: MotionEvent): Float =
        Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { view.zoomFov(d.scaleFactor); return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false }
        })
    }

    private val detector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!scaling && e2.pointerCount == 1) view.look(dx, dy)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (view.autoRotating && settings.vrTapStopsAutoRotate) {
                    view.setAutoRotate(false)
                } else if (view.sensorEnabled && settings.vrTapStopsSensor) {
                    view.setSensor(false)
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else if (!view.cardboard) {
                    bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                view.resetLook()
                return true
            }
        })
    }

    private val touch = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.touching = false; twisting = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { lastTwist = angle(e); twisting = true }
            MotionEvent.ACTION_POINTER_UP -> twisting = false
            MotionEvent.ACTION_MOVE -> if (twisting && e.pointerCount == 2 && settings.vrTwoFingerRotate) {
                val ang = angle(e)
                var d = ang - lastTwist
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                lastTwist = ang
                if (kotlin.math.abs(d) < 45f) view.roll = (view.roll - d) % 360f
            }
        }
        scaleDetector.onTouchEvent(e)
        detector.onTouchEvent(e)
        true
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }

    override fun onPause() {
        view.onPause()
        super.onPause()
    }
}
