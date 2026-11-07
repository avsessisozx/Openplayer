package org.openplayer.app

/** Oynatma ölçümleri: "Hata raporunu kopyala" çıktısına giren mpv bilgisi. */
object PlaybackStats {

    /** Son oynatma özeti (bellekte); uygulama kapanırken [BugReport.notePlayback] ile kalıcılaştırılır. */
    @Volatile var lastReport: String = ""

    /** Rapora giren mpv özellikleri. `vo-drop-frame-count` bu sürümde yok; karşılığı `frame-drop-count`. */
    val PROPERTIES = listOf(
        "mpv-version", "ffmpeg-version", "file-format", "current-vo", "current-ao", "hwdec", "hwdec-current",
        "video-format", "video-params/w", "video-params/h", "video-params/pixelformat",
        "video-params/hw-pixelformat", "video-dec-params/pixelformat", "container-fps", "estimated-vf-fps",
        "decoder-frame-drop-count", "frame-drop-count",
    )

    fun update(engine: MpvEngine) {
        lastReport = format(PROPERTIES.associateWith { engine.getProperty(it) })
    }

    /** Eksik değer "?" yazılır; yazılım ya da donanım çözme bilgisi `hwdec-current`ten çıkar. */
    fun format(p: Map<String, String?>): String {
        fun v(name: String) = p[name]?.takeIf { it.isNotBlank() } ?: "?"
        val hw = p["hwdec-current"]?.takeIf { it.isNotBlank() }
        val decoding = if (hw == null || hw == "no") "yazılım" else "donanım: $hw"
        val pixel = listOfNotNull(
            p["video-dec-params/pixelformat"]?.takeIf { it.isNotBlank() },
            p["video-params/hw-pixelformat"]?.takeIf { it.isNotBlank() },
            p["video-params/pixelformat"]?.takeIf { it.isNotBlank() },
        ).distinct().joinToString(" / ").ifBlank { "?" }
        return buildString {
            append("Motor: ").append(v("mpv-version")).append(" · FFmpeg ").append(v("ffmpeg-version")).append('\n')
            append("Dosya biçimi: ").append(v("file-format")).append('\n')
            append("Video: ").append(v("video-format")).append(' ')
                .append(v("video-params/w")).append('x').append(v("video-params/h"))
                .append(" · ").append(pixel).append('\n')
            append("Çözücü: ").append(decoding).append(" (istenen: ").append(v("hwdec")).append(")\n")
            append("Çıkış: ").append(v("current-vo")).append(" · ses: ").append(v("current-ao")).append('\n')
            append("Kare hızı: dosya ").append(v("container-fps")).append(" · ölçülen ").append(v("estimated-vf-fps")).append('\n')
            append("Atlanan kare: çözücü ").append(v("decoder-frame-drop-count"))
                .append(" · çıkış ").append(v("frame-drop-count")).append('\n')
        }
    }
}
