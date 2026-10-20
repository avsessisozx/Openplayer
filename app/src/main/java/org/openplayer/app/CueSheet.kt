package org.openplayer.app

/**
 * .cue sayfası ayrıştırıcı: tek bir ses dosyasını birden çok parçaya bölen CUE dosyalarını
 * okur. Oynatma, ses dosyasının Media3 "clipping" ile kırpılmış kopyalarıyla yapılır
 * (bkz. [MusicController.toItem]); bu dosya yalnızca metni çözümler ve sanal parçalar üretir.
 */
object CueSheet {

    data class CueTrack(val number: Int, val title: String, val performer: String, val startMs: Long)
    data class Cue(val file: String, val performer: String, val album: String, val tracks: List<CueTrack>)

    /** Baytları metne çevirir (UTF-8; BOM atılır). */
    fun decode(bytes: ByteArray): String {
        val s = String(bytes, Charsets.UTF_8)
        return if (s.isNotEmpty() && s[0] == '﻿') s.substring(1) else s
    }

    fun parse(text: String): Cue? {
        var file = ""
        var globalPerformer = ""
        var album = ""
        val tracks = ArrayList<CueTrack>()

        var curNo = -1
        var curTitle = ""
        var curPerformer = ""
        var curStart = -1L
        var sawTrack = false

        fun flush() {
            if (sawTrack && curNo >= 0 && curStart >= 0) {
                tracks += CueTrack(curNo, curTitle, curPerformer.ifEmpty { globalPerformer }, curStart)
            }
            curTitle = ""
            curPerformer = ""
            curStart = -1L
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val sp = line.indexOf(' ')
            val key = (if (sp < 0) line else line.substring(0, sp)).uppercase()
            val rest = if (sp < 0) "" else line.substring(sp + 1).trim()
            when (key) {
                "FILE" -> file = quoted(rest)
                "TRACK" -> {
                    flush()
                    sawTrack = true
                    curNo = rest.takeWhile { it.isDigit() }.toIntOrNull() ?: (tracks.size + 1)
                }
                "TITLE" -> if (!sawTrack) album = quoted(rest) else curTitle = quoted(rest)
                "PERFORMER" -> if (!sawTrack) globalPerformer = quoted(rest) else curPerformer = quoted(rest)
                "INDEX" -> {
                    // INDEX 01 mm:ss:ff — parça başı olarak 01 alınır (00 ön boşluktur).
                    val parts = rest.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val idx = parts[0].toIntOrNull()
                        val t = parseTime(parts[1])
                        if (t >= 0 && (idx == 1 || curStart < 0)) curStart = t
                    }
                }
            }
        }
        flush()
        if (file.isEmpty() || tracks.isEmpty()) return null
        return Cue(file, globalPerformer, album, tracks.sortedBy { it.number })
    }

    /** mm:ss:ff (ff = 1/75 sn kare) -> ms; hatalıysa -1. */
    private fun parseTime(s: String): Long {
        val p = s.split(":")
        if (p.size != 3) return -1
        val m = p[0].toLongOrNull() ?: return -1
        val sec = p[1].toLongOrNull() ?: return -1
        val fr = p[2].toLongOrNull() ?: return -1
        return (m * 60 + sec) * 1000L + fr * 1000L / 75L
    }

    private fun quoted(rest: String): String {
        val first = rest.indexOf('"')
        val last = rest.lastIndexOf('"')
        return if (first >= 0 && last > first) rest.substring(first + 1, last) else rest.trim()
    }

    /** Cue'nun FILE adını kütüphanedeki bir parçayla eşler (dosya adına göre). */
    fun resolveAudio(cue: Cue, library: List<MusicTrack>): MusicTrack? {
        val name = cue.file.replace('\\', '/').substringAfterLast('/').lowercase()
        if (name.isEmpty()) return null
        return library.firstOrNull { it.path.replace('\\', '/').substringAfterLast('/').lowercase() == name }
            ?: library.firstOrNull { it.path.lowercase().endsWith(name) }
    }

    /** Cue parçalarını, aynı ses dosyasını kırpan sanal [MusicTrack]'lere dönüştürür. */
    fun buildTracks(cue: Cue, audio: MusicTrack): List<MusicTrack> {
        val list = cue.tracks
        return list.mapIndexed { i, ct ->
            val start = ct.startMs
            val end = if (i + 1 < list.size) list[i + 1].startMs else 0L // 0 = sona kadar
            val dur = if (end > start) end - start
            else (audio.durationMs - start).coerceAtLeast(0L)
            audio.copy(
                id = -(audio.id.coerceAtLeast(1) * 1000 + ct.number), // sentetik, benzersiz
                title = ct.title.ifBlank { "Parça ${ct.number}" },
                artist = ct.performer.ifBlank { cue.performer.ifBlank { audio.artist } },
                album = cue.album.ifBlank { audio.album },
                trackNo = ct.number,
                durationMs = dur,
                clipStartMs = start,
                clipEndMs = end,
            )
        }
    }
}
