package org.openplayer.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hareket sensöründen 360°/VR görünüm matrisi üretir (Android'e bağlı değil; birim testle sınanır).
 *
 * Girdi: SensorManager.getRotationMatrixFromVector çıktısı (satır öncelikli 3x3, 4'lü adımla) —
 * cihaz eksenlerinden dünyaya (x = doğu, y = kuzey, z = yukarı) dönüşüm.
 * Çıktı: OpenGL görünüm matrisi (sütun öncelikli 4x4). Kamera ekranın içine bakar (cihazın -Z yönü),
 * GL dünyası Y yukarı, -Z kuzey.
 */
object SensorView {
    /** Ekran yönüne göre ekranın sağ ve yukarı eksenleri (cihazın doğal eksenlerinde). */
    private fun screenAxes(displayRotation: Int): Pair<DoubleArray, DoubleArray> = when (displayRotation) {
        1 -> doubleArrayOf(0.0, 1.0, 0.0) to doubleArrayOf(-1.0, 0.0, 0.0) // ROTATION_90
        2 -> doubleArrayOf(-1.0, 0.0, 0.0) to doubleArrayOf(0.0, -1.0, 0.0) // ROTATION_180
        3 -> doubleArrayOf(0.0, -1.0, 0.0) to doubleArrayOf(1.0, 0.0, 0.0) // ROTATION_270
        else -> doubleArrayOf(1.0, 0.0, 0.0) to doubleArrayOf(0.0, 1.0, 0.0) // ROTATION_0
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** Kameradan GL dünyasına 3x3 matris, m[satır][sütun]. [rotation]: 0..3 (Surface.ROTATION_x değeri). */
    fun cameraMatrix(r: FloatArray, rotation: Int): Array<DoubleArray> {
        val (right, up) = screenAxes(rotation)
        val back = cross(right, up) // ekrandan kullanıcıya doğru
        val m = Array(3) { DoubleArray(3) }
        for ((col, dev) in listOf(right, up, back).withIndex()) {
            // dünya (ENU) = R * dev
            val wx = r[0] * dev[0] + r[1] * dev[1] + r[2] * dev[2]
            val wy = r[4] * dev[0] + r[5] * dev[1] + r[6] * dev[2]
            val wz = r[8] * dev[0] + r[9] * dev[1] + r[10] * dev[2]
            // ENU (z yukarı) → GL (y yukarı, -z kuzey): (x, y, z) → (x, z, -y)
            m[0][col] = wx
            m[1][col] = wz
            m[2][col] = -wy
        }
        return m
    }

    /**
     * Bakış yönünün yatay açısı (radyan; -Z'den +X'e doğru). Bakış neredeyse tam yukarı/aşağı ise
     * yön belirsizdir ve null döner.
     */
    fun forwardYaw(r: FloatArray, rotation: Int): Double? {
        val m = cameraMatrix(r, rotation)
        val fx = -m[0][2]
        val fy = -m[1][2]
        val fz = -m[2][2]
        if (abs(fy) > 0.97) return null
        return atan2(fx, -fz)
    }

    /** GL görünüm matrisi (sütun öncelikli). [yawZero]: "ileri" kabul edilen yatay açı (forwardYaw). */
    fun viewMatrix(r: FloatArray, rotation: Int, yawZero: Double): FloatArray {
        val m = cameraMatrix(r, rotation)
        val c = cos(yawZero)
        val s = sin(yawZero)
        // Rr = Y ekseni etrafında dönüş; a = Rr * m
        val rr = arrayOf(
            doubleArrayOf(c, 0.0, s),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(-s, 0.0, c),
        )
        val a = Array(3) { i -> DoubleArray(3) { j -> rr[i][0] * m[0][j] + rr[i][1] * m[1][j] + rr[i][2] * m[2][j] } }
        // Görünüm = a'nın tersi = a'nın devriği.
        val out = FloatArray(16)
        for (col in 0 until 3) for (row in 0 until 3) out[col * 4 + row] = a[col][row].toFloat()
        out[15] = 1f
        return out
    }
}
