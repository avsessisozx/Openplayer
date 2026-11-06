package org.openplayer.app

import android.app.Activity
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process

/**
 * "Performans modu": oynatma ekranı açıkken Android'e işlemciyi yavaşlatmamasını söyler.
 * Cihaz desteklemiyorsa sessizce hiçbir şey yapmaz. Donanım sınırını aşırmaz; yalnızca
 * ısınma/güç kısıtlamasından kaynaklanan takılmaları azaltır.
 */
object PerformanceBoost {

    private var hintSession: PerformanceHintManager.Session? = null
    private var sustained = false

    fun start(activity: Activity) {
        stop(activity)
        runCatching {
            val pm = activity.getSystemService(PowerManager::class.java)
            if (pm != null && pm.isSustainedPerformanceModeSupported) {
                activity.window.setSustainedPerformanceMode(true)
                sustained = true
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                val manager = activity.getSystemService(PerformanceHintManager::class.java)
                hintSession = manager?.createHintSession(intArrayOf(Process.myTid()), 16_666_667L)
            }
        }
    }

    /** Oynatıcı döngüsünden çağrılır: gerçek iş süresini bildirmek ipucu oturumunu canlı tutar. */
    fun report(workNanos: Long) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { hintSession?.reportActualWorkDuration(workNanos.coerceAtLeast(1L)) }
        }
    }

    fun stop(activity: Activity) {
        if (sustained) {
            runCatching { activity.window.setSustainedPerformanceMode(false) }
            sustained = false
        }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { hintSession?.close() }
        }
        hintSession = null
    }
}
