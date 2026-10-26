package org.openplayer.app

import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Dikey kaydırmada bir bölgeye atanabilecek işlevler. */
enum class GestureAction(val label: String) {
    NONE(Tx.s(R.string.gesturecon_001)),
    BRIGHTNESS(Tx.s(R.string.gesturecon_002)),
    VOLUME(Tx.s(R.string.gesturecon_015)),
    SPEED(Tx.s(R.string.gesturecon_003)),
    SEEK(Tx.s(R.string.gesturecon_016)),
    SUB_POSITION(Tx.s(R.string.gesturecon_004)),
    SUB_SIZE(Tx.s(R.string.gesturecon_005)),
    ZOOM(Tx.s(R.string.gesturecon_006)),
}

/**
 * Ekran üç dikey şeride bölünür: sol, orta, sağ. Her şeridin işlevi ve şeritlerin sınırları ayarlanır.
 * Varsayılan: sol parlaklık, sağ ses, orta kapalı (sınırlar ekranın ortasında birleşir).
 */
class GesturePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("gestures", Context.MODE_PRIVATE)

    companion object {
        val zones = listOf("left", "mid", "right")
        fun zoneLabel(zone: String) = when (zone) {
            "left" -> Tx.s(R.string.gesturecon_017)
            "mid" -> Tx.s(R.string.gesturecon_018)
            else -> Tx.s(R.string.gesturecon_007)
        }
    }

    private fun default(zone: String) = when (zone) {
        "left" -> GestureAction.BRIGHTNESS
        "right" -> GestureAction.VOLUME
        else -> GestureAction.NONE
    }

    private fun parse(name: String?) = name?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() }

    fun get(zone: String): GestureAction =
        parse(prefs.getString(zone, null))
            // Eski dört bölgeli ayardan göç: üst bölgelerin işlevi alınır.
            ?: parse(prefs.getString(zone + (if (zone == "left" || zone == "right") "_top" else ""), null)).takeIf { zone != "mid" }
            ?: default(zone)

    fun set(zone: String, action: GestureAction) {
        prefs.edit().putString(zone, action.name).apply()
    }

    /** Sol şeridin bitiş noktası (ekran genişliğinin oranı). */
    var split1: Float
        get() = prefs.getFloat("split1", 0.5f)
        set(v) = prefs.edit().putFloat("split1", v.coerceIn(0.1f, 0.9f)).apply()

    /** Sağ şeridin başlangıç noktası. Orta şerit split1..split2 arasıdır; eşitse orta yoktur. */
    var split2: Float
        get() = prefs.getFloat("split2", 0.5f)
        set(v) = prefs.edit().putFloat("split2", v.coerceIn(0.1f, 0.9f)).apply()

    fun zoneAt(xFraction: Float): String {
        val a = split1
        val b = split2.coerceAtLeast(a)
        return when {
            xFraction < a -> "left"
            xFraction < b -> "mid"
            else -> "right"
        }
    }
}

/**
 * Yatay kaydırma: ileri/geri sarma. Dikey kaydırma: dokunulan bölgenin işlevi.
 * Çimdik: yakınlaştırma (alt şeritte altyazı boyutu). Yakınlaşmışken tek parmak: kaydırma (pan).
 * Alt şeritte yatay kaydırma: önceki/sonraki altyazı, dikey: altyazı konumu.
 */
class GestureController(private val a: PlayerActivity) : View.OnTouchListener {

    private enum class Mode { NONE, HORIZONTAL, VERTICAL, SUB_H, SUB_V }

    private val audio = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = GesturePrefs(a)

    private var mode = Mode.NONE
    private var zone = "left"
    private var startSubSize = 20f
    private var lastFraction = 0f
    private var verticalSeeking = false
    private var startPos = 0L
    private var startVolume = 0
    private var startBrightness = 0.5f
    private var startSpeed = 1f
    private var startSubBottom = 0.08f
    private var seekTarget = 0L
    private var subSwipe = 0f
    private var inSubtitleArea = false
    private var onSubtitleText = false
    private var scaleOnSubtitle = false
    private var scaling = false
    private var width = 1
    private var height = 1
    private var brightnessSet = -1f

    private val scaleDetector = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (a.vr != VrMode.NONE) {
                scaleOnSubtitle = false
                scaling = true
                return true
            }
            scaleOnSubtitle = a.settings.subtitleGestures && a.subtitles.mode != SubtitleController.Mode.OFF &&
                    d.focusY > height * 0.72f
            scaling = a.settings.pinchZoomEnabled || scaleOnSubtitle
            return scaling
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (a.vr != VrMode.NONE) a.vrZoom(d.scaleFactor)
            else if (scaleOnSubtitle) a.adjustSubtitleSize(d.scaleFactor) else a.zoomBy(d.scaleFactor)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
            if (!scaleOnSubtitle && a.vr == VrMode.NONE) a.snapZoom()
            a.hideHudSoon()
        }
    })

    private var ignoring = false

    // 360° iki parmakla döndürme
    private var twisting = false
    private var lastTwist = 0f

    private fun twistAngle(e: MotionEvent): Float =
        Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun vrTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> a.vrTouching(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                a.vrTouching(false)
                a.vrWheelEnd()
                twisting = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                lastTwist = twistAngle(e)
                twisting = true
            }
            MotionEvent.ACTION_POINTER_UP -> twisting = false
            MotionEvent.ACTION_MOVE -> {
                if (a.vrWheelActive()) {
                    a.vrWheelMove(e.x, e.y)
                } else if (twisting && e.pointerCount == 2 && a.settings.vrTwoFingerRotate) {
                    val ang = twistAngle(e)
                    var d = ang - lastTwist
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    lastTwist = ang
                    if (abs(d) < 45f) a.vrRollBy(-d)
                }
            }
        }
    }

    private val detector = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            mode = Mode.NONE
            subSwipe = 0f
            zone = prefs.zoneAt(e.x / width)
            startSubSize = a.settings.subtitleSizeSp
            lastFraction = 0f
            inSubtitleArea = a.settings.subtitleGestures && a.subtitles.mode != SubtitleController.Mode.OFF &&
                    (e.y > height * 0.72f ||
                        (e.y > height * (1f - a.settings.subtitleBottomFraction - 0.16f) &&
                            e.y < height * (1f - a.settings.subtitleBottomFraction + 0.04f)))
            // Parmak altyazı metninin hizasında mı: vurgu kutusu çıkar, uzun basış hızlı oynatmayı başlatmaz.
            onSubtitleText = inSubtitleArea && a.subtitles.showing &&
                e.y > height * (1f - a.settings.subtitleBottomFraction - 0.16f) &&
                e.y < height * (1f - a.settings.subtitleBottomFraction + 0.04f)
            if (onSubtitleText) a.subtitleGrab(true)
            startPos = a.player.currentPosition
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val b = a.window.attributes.screenBrightness
            startBrightness = if (b < 0f) 0.5f else b
            startSpeed = a.player.playbackParameters.speed
            startSubBottom = a.settings.subtitleBottomFraction
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e1 == null || scaling || e2.pointerCount > 1) return true
            if (a.vr != VrMode.NONE) {
                if (!a.vrWheelActive()) a.vrLook(dx, dy)
                return true
            }
            if (a.zoom > 1.02f) {
                a.panBy(-dx, -dy)
                return true
            }
            val totalX = e2.x - e1.x
            val totalY = e2.y - e1.y
            if (mode == Mode.NONE) {
                if (abs(totalX) < a.dp(12) && abs(totalY) < a.dp(12)) return true
                val horizontal = abs(totalX) > abs(totalY)
                mode = when {
                    inSubtitleArea && horizontal -> Mode.SUB_H
                    inSubtitleArea -> Mode.SUB_V
                    horizontal -> Mode.HORIZONTAL
                    else -> Mode.VERTICAL
                }
            }
            when (mode) {
                Mode.HORIZONTAL -> if (a.settings.horizontalSeekEnabled) {
                    val delta = (totalX / width * a.settings.swipeSeekSec * 1000f).toLong()
                    val duration = a.player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    seekTarget = (startPos + delta).coerceIn(0L, duration)
                    a.scrubStart()
                    a.scrubMove(seekTarget)
                    a.showHud("${formatDuration(seekTarget)}\n[${if (delta >= 0) "+" else "-"}${formatDuration(abs(delta))}]")
                }
                Mode.VERTICAL -> applyVertical(-totalY / height)
                Mode.SUB_H -> {
                    subSwipe = totalX
                    a.subtitleSwipeMove(totalX)
                }
                Mode.SUB_V -> {
                    a.setSubtitleBottom(startSubBottom - totalY / height)
                }
                Mode.NONE -> Unit
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (a.vr != VrMode.NONE) {
                if (a.settings.vrHoldWheel && !scaling) a.vrWheelStart(e.x, e.y)
                return
            }
            if (scaling || onSubtitleText) return
            if (a.startFastPlay()) mode = Mode.NONE
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (a.vr != VrMode.NONE) {
                if (a.vrAutoRotating() && a.settings.vrTapStopsAutoRotate) {
                    a.vrSetAutoRotate(false)
                    a.showHud(Tx.s(R.string.gesturecon_010))
                    a.hideHudSoon()
                    return true
                }
                if (a.vrSensorEnabled() && a.settings.vrTapStopsSensor) {
                    a.vrSetSensor(false)
                    a.showHud(Tx.s(R.string.gesturecon_011))
                    a.hideHudSoon()
                    return true
                }
            }
            a.onScreenTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            when (a.settings.doubleTapAction) {
                DoubleTapAction.PLAY_PAUSE -> a.togglePlay()
                DoubleTapAction.SEEK -> {
                    val sec = a.settings.doubleTapSeekSec
                    when {
                        e.x < width * 0.33f -> {
                            a.seekRelative(-sec * 1000L)
                            a.showHud("-$sec s")
                            a.hideHudSoon()
                        }
                        e.x > width * 0.67f -> {
                            a.seekRelative(sec * 1000L)
                            a.showHud("+$sec s")
                            a.hideHudSoon()
                        }
                        else -> a.togglePlay()
                    }
                }
                DoubleTapAction.NONE -> Unit
            }
            return true
        }
    })

    private fun applyVertical(fraction: Float) {
        when (prefs.get(zone)) {
            GestureAction.BRIGHTNESS -> {
                val value = (startBrightness + fraction).coerceIn(0.01f, 1f)
                a.window.attributes = a.window.attributes.apply { screenBrightness = value }
                brightnessSet = value
                a.showHud(Tx.s(R.string.gesturecon_012, (value * 100).roundToInt()))
            }
            GestureAction.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val value = (startVolume + fraction * max).roundToInt().coerceIn(0, max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                a.showHud(Tx.s(R.string.gesturecon_019, (value * 100f / max).roundToInt()))
            }
            GestureAction.SPEED -> {
                val value = (((startSpeed + fraction * 2f) * 20).roundToInt() / 20f).coerceIn(0.25f, 4f)
                a.setSpeed(value)
                a.showHud(Tx.s(R.string.gesturecon_013, "%.2f".format(value)))
            }
            GestureAction.SEEK -> {
                val delta = (fraction * a.settings.swipeSeekSec * 1000f).toLong()
                val duration = a.player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                seekTarget = (startPos + delta).coerceIn(0L, duration)
                verticalSeeking = true
                a.scrubStart()
                a.scrubMove(seekTarget)
                a.showHud("${formatDuration(seekTarget)}\n[${if (delta >= 0) "+" else "-"}${formatDuration(abs(delta))}]")
            }
            GestureAction.SUB_POSITION -> {
                a.setSubtitleBottom(startSubBottom + fraction)
                a.showHud(Tx.s(R.string.gesturecon_004))
            }
            GestureAction.SUB_SIZE -> {
                a.settings.subtitleSizeSp = (startSubSize * (1f + fraction * 2f)).coerceIn(10f, 48f)
                a.subtitles.applyStyle()
                a.showHud(Tx.s(R.string.gesturecon_014, a.settings.subtitleSizeSp.roundToInt()))
            }
            GestureAction.ZOOM -> {
                a.zoomBy(1f + (fraction - lastFraction) * 2f)
                lastFraction = fraction
            }
            GestureAction.NONE -> Unit
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        width = v.width.coerceAtLeast(1)
        height = v.height.coerceAtLeast(1)

        if (a.kidsLocked) {
            if (event.actionMasked == MotionEvent.ACTION_UP) a.kidsTap(event.x, event.y, width, height)
            return true
        }
        if (a.locked) {
            if (event.actionMasked == MotionEvent.ACTION_UP) a.showUnlockButton()
            return true
        }

        val guard = a.dp(a.settings.edgeGuardDp)
        if (guard > 0 && event.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoring = event.x < guard || event.x > width - guard
        }
        if (ignoring) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoring = false
            return true
        }
        if (a.vr != VrMode.NONE) vrTouch(event)
        scaleDetector.onTouchEvent(event)
        val handled = detector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            a.stopFastPlay()
            if (brightnessSet > 0f) {
                if (a.settings.rememberBrightness) a.settings.savedBrightness = brightnessSet
                brightnessSet = -1f
            }
            when (mode) {
                Mode.HORIZONTAL -> if (a.settings.horizontalSeekEnabled) a.scrubEnd(seekTarget)
                Mode.VERTICAL -> if (verticalSeeking) {
                    verticalSeeking = false
                    a.scrubEnd(seekTarget)
                }
                Mode.SUB_H -> a.subtitleSwipeEnd(
                    event.actionMasked == MotionEvent.ACTION_UP && abs(subSwipe) > a.dp(60),
                    subSwipe < 0,
                )
                else -> Unit
            }
            if (onSubtitleText) {
                onSubtitleText = false
                a.subtitleGrab(false)
            }
            mode = Mode.NONE
            a.hideHudSoon()
        }
        return handled
    }
}
