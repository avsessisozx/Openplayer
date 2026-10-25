package org.openplayer.app

import java.io.File

/** Birim testlerinde Tx.s() çağrılarını values-tr/strings.xml üzerinden çözer (Android bağlamı gerekmez). */
object TestStrings {
    private val entry = Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    private fun resDir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            File(d, "app/src/main/res").takeIf { it.isDirectory }?.let { return it }
            File(d, "src/main/res").takeIf { it.isDirectory }?.let { return it }
            d = d.parentFile
        }
        error("res klasörü bulunamadı")
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\'", "'").replace("\\\"", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun installTurkish() {
        val texts = entry.findAll(File(resDir(), "values-tr/strings.xml").readText())
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val idToName = Class.forName("org.openplayer.app.R\$string").fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
        Tx.testResolver = { id, args ->
            val text = texts[idToName[id]] ?: "#$id"
            if (args.isEmpty()) text else java.lang.String.format(text, *args)
        }
    }

    fun uninstall() {
        Tx.testResolver = null
    }
}
