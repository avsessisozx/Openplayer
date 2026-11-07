package org.openplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Vr360OutputTest {
    private val log = ArrayList<String>()
    private val link = VrSurfaceLink<String>(
        runner = { it.run() },
        attach = { s, w, h -> log += "attach $s ${w}x$h" },
        detach = { log += "detach" },
        retire = { log += "retire $it" },
    )

    @Test fun detachesBeforeRetiringAndAttachingTheNewSurface() {
        link.videoSize(3840, 1600)
        link.surfaceReady("a")
        log.clear()
        link.surfaceReady("b")
        assertEquals(listOf("detach", "retire a", "attach b 3840x1600"), log)
    }

    @Test fun lostSurfaceIsDetachedThenRetired() {
        link.surfaceReady("a")
        log.clear()
        link.surfaceLost()
        link.surfaceLost()
        assertEquals(listOf("detach", "retire a"), log)
    }

    @Test fun sizeChangeOnlyReattachesWhenItDiffers() {
        link.videoSize(1920, 1080)
        link.surfaceReady("a")
        link.videoSize(1920, 1080)
        link.videoSize(3840, 1920)
        assertEquals(listOf("attach a 1920x1080", "attach a 3840x1920"), log)
    }

    @Test fun nothingIsAttachedAfterTheSurfaceIsGone() {
        link.surfaceReady("a")
        link.surfaceLost()
        log.clear()
        link.videoSize(1280, 720)
        link.maxTextureSize(4096)
        assertTrue(log.isEmpty())
    }

    @Test fun unknownSizeFallsBackAndLargeSizeKeepsAspect() {
        assertEquals(1920 to 1080, VrSurfaceLink.fitSize(0, 0, 0))
        assertEquals(3840 to 1600, VrSurfaceLink.fitSize(3840, 1600, 4096))
        assertEquals(4096 to 2048, VrSurfaceLink.fitSize(8192, 4096, 4096))
    }

    // ---------------------------------------------------------------- üstveri

    private class Bytes(private val data: ByteArray) : VrMeta.Source {
        var reads = 0
        override val length: Long get() = data.size.toLong()
        override fun read(position: Long, into: ByteArray, count: Int): Int {
            reads++
            if (position >= data.size) return -1
            val n = minOf(count.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), into, 0, n)
            return n
        }
    }

    private fun u32(v: Long) = ByteArray(4) { (v shr (24 - it * 8)).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return u32(body.size + 8L) + type.toByteArray() + body
    }

    private fun mp4(entryChildren: ByteArray, handler: String = "vide", leadingMdat: Int = 0): ByteArray {
        val entry = box("hvc1", ByteArray(78), box("hvcC", ByteArray(40)), entryChildren)
        val stsd = box("stsd", ByteArray(4), u32(1), entry)
        val hdlr = box("hdlr", ByteArray(8), handler.toByteArray(), ByteArray(12))
        val trak = box("trak", box("tkhd", ByteArray(84)), box("mdia", box("mdhd", ByteArray(24)), hdlr, box("minf", box("stbl", stsd))))
        return box("ftyp", "isom".toByteArray(), ByteArray(4)) + box("mdat", ByteArray(leadingMdat)) +
            box("moov", box("mvhd", ByteArray(100)), trak)
    }

    private val sv3d get() = box("sv3d", box("svhd", ByteArray(5)), box("proj", box("prhd", ByteArray(16)), box("equi", ByteArray(20))))
    private fun st3d(mode: Int) = box("st3d", ByteArray(4), byteArrayOf(mode.toByte()))

    @Test fun mp4Spherical() {
        assertEquals(VrVideoMeta(true, 0), VrMeta.read(Bytes(mp4(sv3d))))
        assertEquals(VrVideoMeta(true, 1), VrMeta.read(Bytes(mp4(sv3d + st3d(1)))))
        assertEquals(VrVideoMeta(true, 2), VrMeta.read(Bytes(mp4(st3d(2) + sv3d))))
    }

    @Test fun mp4WithoutProjectionIsNotSpherical() {
        assertEquals(VrVideoMeta(false, 0), VrMeta.read(Bytes(mp4(ByteArray(0)))))
        assertEquals(VrVideoMeta(false, 1), VrMeta.read(Bytes(mp4(st3d(1)))))
        assertEquals(VrVideoMeta(false, 0), VrMeta.read(Bytes(mp4(box("sv3d", box("svhd", ByteArray(5)))))))
    }

    @Test fun mp4AudioTrackIsIgnored() {
        assertNull(VrMeta.read(Bytes(mp4(sv3d, handler = "soun"))))
    }

    @Test fun moovAtTheEndNeedsFewReads() {
        val src = Bytes(mp4(sv3d, leadingMdat = 5_000_000))
        assertEquals(VrVideoMeta(true, 0), VrMeta.read(src))
        assertTrue("reads=${src.reads}", src.reads <= 8)
    }

    @Test fun truncatedOrForeignDataGivesNull() {
        assertNull(VrMeta.read(Bytes(mp4(sv3d).copyOf(60))))
        assertNull(VrMeta.read(Bytes(ByteArray(0))))
        assertNull(VrMeta.read(Bytes("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())))
    }

    private fun ebml(id: Long, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val out = ByteArrayOutputStream()
        var started = false
        for (shift in 24 downTo 0 step 8) {
            val b = ((id shr shift) and 0xFF).toInt()
            if (b != 0 || started) {
                out.write(b)
                started = true
            }
        }
        // Boyut hep 4 bayt: küçük değerleri uzun yazan dosyalar da okunmalı.
        out.write(0x10 or (body.size shr 24))
        out.write(body.size shr 16)
        out.write(body.size shr 8)
        out.write(body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun mkv(video: ByteArray, type: Int = 1): ByteArray {
        val entry = ebml(0xAE, ebml(0xD7, byteArrayOf(1)), ebml(0x83, byteArrayOf(type.toByte())), ebml(0xE0, ebml(0xB0, byteArrayOf(0x0F, 0)), video))
        val audio = ebml(0xAE, ebml(0x83, byteArrayOf(2)), ebml(0xE1, ByteArray(8)))
        return ebml(0x1A45DFA3, ByteArray(10)) + ebml(0x18538067, ebml(0x1549A966, ByteArray(30)), ebml(0x1654AE6B, audio, entry))
    }

    private val projection = ebml(0x7670, ebml(0x7671, byteArrayOf(1)), ebml(0x7672, ByteArray(20)))

    @Test fun matroskaSpherical() {
        assertEquals(VrVideoMeta(true, 0), VrMeta.read(Bytes(mkv(projection))))
        assertEquals(VrVideoMeta(true, 1), VrMeta.read(Bytes(mkv(ebml(0x53B8, byteArrayOf(3)) + projection))))
        assertEquals(VrVideoMeta(true, 2), VrMeta.read(Bytes(mkv(projection + ebml(0x53B8, byteArrayOf(1))))))
    }

    @Test fun matroskaRectangularIsNotSpherical() {
        val flat = ebml(0x7670, ebml(0x7671, byteArrayOf(0)))
        assertEquals(VrVideoMeta(false, 0), VrMeta.read(Bytes(mkv(flat))))
        assertEquals(VrVideoMeta(false, 0), VrMeta.read(Bytes(mkv(ByteArray(0)))))
    }

    @Test fun matroskaUnknownSegmentSizeIsRead() {
        val tracks = ebml(0x1654AE6B, ebml(0xAE, ebml(0x83, byteArrayOf(1)), ebml(0xE0, projection)))
        val head = ebml(0x1A45DFA3, ByteArray(4)) + byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01) + ByteArray(7) { 0xFF.toByte() }
        assertEquals(VrVideoMeta(true, 0), VrMeta.read(Bytes(head + tracks)))
    }
}
