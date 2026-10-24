package org.openplayer.app

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensorViewTest {
    /** Cihazın x, y, z eksenlerinin dünyadaki (doğu, kuzey, yukarı) karşılıklarından satır öncelikli matris. */
    private fun pose(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) {
            r[i * 4 + 0] = x[i]
            r[i * 4 + 1] = y[i]
            r[i * 4 + 2] = z[i]
        }
        r[15] = 1f
        return r
    }

    private fun v(a: Float, b: Float, c: Float) = floatArrayOf(a, b, c)

    /** Görünüm matrisini GL dünya vektörüne uygular. */
    private fun apply(view: FloatArray, x: Float, y: Float, z: Float): FloatArray = FloatArray(3) { i ->
        view[0 * 4 + i] * x + view[1 * 4 + i] * y + view[2 * 4 + i] * z
    }

    private fun assertIdentity(view: FloatArray) {
        for (col in 0 until 3) for (row in 0 until 3) {
            assertEquals("[$row][$col]", if (row == col) 1f else 0f, view[col * 4 + row], 1e-5f)
        }
    }

    // Dik tutulan, kuzeye bakan telefon (dikey): sağ = doğu, yukarı = yukarı, ekran güneye (kullanıcıya) bakar.
    private val portraitNorth = pose(v(1f, 0f, 0f), v(0f, 0f, 1f), v(0f, -1f, 0f))

    @Test
    fun portraitFacingNorthIsIdentity() {
        assertIdentity(SensorView.viewMatrix(portraitNorth, 0, 0.0))
    }

    @Test
    fun landscapeFacingNorthIsIdentity() {
        // ROTATION_90: ekranın sağı = cihaz +Y, yukarısı = cihaz -X.
        val r = pose(v(0f, 0f, -1f), v(1f, 0f, 0f), v(0f, -1f, 0f))
        assertIdentity(SensorView.viewMatrix(r, 1, 0.0))
    }

    @Test
    fun landscape270FacingNorthIsIdentity() {
        // ROTATION_270: ekranın sağı = cihaz -Y, yukarısı = cihaz +X.
        val r = pose(v(0f, 0f, 1f), v(-1f, 0f, 0f), v(0f, -1f, 0f))
        assertIdentity(SensorView.viewMatrix(r, 3, 0.0))
    }

    @Test
    fun facingEastLooksAtEastWithoutRecenter() {
        // Doğuya bakan dikey telefon: sağ = güney, yukarı = yukarı, ekran batıya bakar.
        val r = pose(v(0f, -1f, 0f), v(0f, 0f, 1f), v(-1f, 0f, 0f))
        val view = SensorView.viewMatrix(r, 0, 0.0)
        // GL'de doğu = +X; kamera onu tam önünde (-Z) görmeli.
        val e = apply(view, 1f, 0f, 0f)
        assertEquals(0f, e[0], 1e-5f)
        assertEquals(0f, e[1], 1e-5f)
        assertEquals(-1f, e[2], 1e-5f)
    }

    @Test
    fun recenterMakesCurrentDirectionForward() {
        val r = pose(v(0f, -1f, 0f), v(0f, 0f, 1f), v(-1f, 0f, 0f))
        val yaw = SensorView.forwardYaw(r, 0)
        assertNotNull(yaw)
        assertEquals(PI / 2, yaw!!, 1e-6)
        assertIdentity(SensorView.viewMatrix(r, 0, yaw))
    }

    @Test
    fun recenterKeepsPitchButRemovesYaw() {
        // Batıya bakıp 30° yukarı eğilmiş dikey telefon: bakış (ENU) = (-cos30, 0, sin30).
        val s = Math.sin(Math.toRadians(30.0)).toFloat()
        val c = Math.cos(Math.toRadians(30.0)).toFloat()
        val r = pose(v(0f, 1f, 0f), v(s, 0f, c), v(c, 0f, -s)) // sağ = kuzey, yukarı, geri = -bakış
        val yaw = SensorView.forwardYaw(r, 0)!!
        val view = SensorView.viewMatrix(r, 0, yaw)
        // Yeniden ortalamadan sonra bakış tam ileri (-Z) yönünde, 30° yukarıda olmalı: (0, sin30, -cos30).
        val o = apply(view, 0f, s, -c)
        assertEquals(0f, o[0], 1e-5f)
        assertEquals(0f, o[1], 1e-5f)
        assertEquals(-1f, o[2], 1e-5f)
        // Dünya "yukarı" yönü kameranın yukarısıyla cos30 kadar örtüşür (eğim korunur).
        assertEquals(c, apply(view, 0f, 1f, 0f)[1], 1e-5f)
    }

    @Test
    fun flatPhoneHasNoDefinedYaw() {
        // Ekranı yukarı bakacak şekilde masada yatan telefon: kamera ekranın içine, yani aşağı bakar.
        val r = pose(v(1f, 0f, 0f), v(0f, 1f, 0f), v(0f, 0f, 1f))
        assertNull(SensorView.forwardYaw(r, 0))
    }
}
