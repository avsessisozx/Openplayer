package org.openplayer.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bütün dillerin strings.xml dosyaları İngilizce (varsayılan) ile aynı yer tutucuları kullanmalı; yoksa uygulama çöker. */
class StringsResourceTest {
    private val ph = Regex("%(\\d+)\\$([-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])")
    private val entry = Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    private fun resDir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val r = File(d, "app/src/main/res")
            if (r.isDirectory) return r
            val r2 = File(d, "src/main/res")
            if (r2.isDirectory) return r2
            d = d.parentFile
        }
        error("res klasörü bulunamadı")
    }

    private fun load(f: File): Map<String, String> =
        entry.findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun translationsKeepPlaceholders() {
        val res = resDir()
        val base = load(File(res, "values/strings.xml"))
        assertTrue("varsayılan metinler boş", base.size > 100)
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
        assertTrue("çeviri klasörü yok", dirs.isNotEmpty())
        val errors = ArrayList<String>()
        for (dir in dirs.sortedBy { it.name }) {
            val tr = load(File(dir, "strings.xml"))
            for ((k, v) in tr) {
                val b = base[k]
                if (b == null) {
                    errors += "${dir.name}: $k varsayılanda yok"
                    continue
                }
                if (ph.findAll(v).map { it.value }.sorted().toList() != ph.findAll(b).map { it.value }.sorted().toList()) {
                    errors += "${dir.name}: $k yer tutucuları farklı"
                }
            }
        }
        assertEquals(errors.joinToString("\n"), 0, errors.size)
    }
}
