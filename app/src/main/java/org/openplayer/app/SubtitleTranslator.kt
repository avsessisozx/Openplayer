package org.openplayer.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * Altyazı çevirisi. Bu dosya Android'e bağlı değildir (yalnız org.json ve java.net kullanır);
 * böylece birim testleriyle sahte bir HTTP katmanı üzerinden sınanır.
 */

enum class TrProvider(val id: String, val label: String) {
    MYMEMORY("mymemory", "MyMemory (ücretsiz, anahtarsız)"),
    LIBRE("libre", "LibreTranslate uyumlu adres"),
    OPENAI("openai", "Yapay zekâ (OpenAI uyumlu adres)");

    companion object {
        fun byId(id: String): TrProvider = entries.firstOrNull { it.id == id } ?: MYMEMORY
    }
}

object TrLangs {
    class Lang(val code: String, val tr: String, val en: String)

    val all = listOf(
        Lang("tr", "Türkçe", "Turkish"),
        Lang("en", "İngilizce", "English"),
        Lang("es", "İspanyolca", "Spanish"),
        Lang("de", "Almanca", "German"),
        Lang("fr", "Fransızca", "French"),
        Lang("it", "İtalyanca", "Italian"),
        Lang("pt", "Portekizce", "Portuguese"),
        Lang("ru", "Rusça", "Russian"),
        Lang("ar", "Arapça", "Arabic"),
        Lang("fa", "Farsça", "Persian"),
        Lang("ja", "Japonca", "Japanese"),
        Lang("ko", "Korece", "Korean"),
        Lang("zh", "Çince", "Chinese"),
        Lang("nl", "Felemenkçe", "Dutch"),
        Lang("pl", "Lehçe", "Polish"),
        Lang("uk", "Ukraynaca", "Ukrainian"),
        Lang("el", "Yunanca", "Greek"),
        Lang("bg", "Bulgarca", "Bulgarian"),
        Lang("ro", "Romence", "Romanian"),
        Lang("hu", "Macarca", "Hungarian"),
        Lang("cs", "Çekçe", "Czech"),
        Lang("sv", "İsveççe", "Swedish"),
        Lang("hi", "Hintçe", "Hindi"),
        Lang("id", "Endonezce", "Indonesian"),
        Lang("az", "Azerice", "Azerbaijani"),
    )

    fun byCode(code: String): Lang? = all.firstOrNull { it.code == code }
    fun label(code: String): String = if (code == "auto") "Otomatik algıla" else byCode(code)?.tr ?: code
    fun englishName(code: String): String = byCode(code)?.en ?: code
}

data class TrConfig(
    val provider: TrProvider,
    val target: String,
    /** "auto" ya da bir dil kodu. */
    val source: String = "auto",
    val email: String = "",
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

class TranslateException(message: String) : IOException(message)

class TranslateCancelled : RuntimeException("cancelled")

class HttpResult(val code: Int, val body: String, val retryAfterSec: Int? = null, val location: String? = null)

fun interface HttpTransport {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult
}

/** Gerçek ağ katmanı. Yönlendirme izlenmez (Authorization başlığı başka sunucuya sızmasın). */
object UrlConnectionTransport : HttpTransport {
    private const val MAX_RESPONSE = 4 * 1024 * 1024

    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val u = try {
            URL(url)
        } catch (e: java.net.MalformedURLException) {
            throw TranslateException("Geçersiz adres: $url")
        }
        if (u.protocol != "https" && u.protocol != "http") throw TranslateException("Yalnız http ve https adresleri desteklenir")
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readLimited(it) } ?: ""
            return HttpResult(
                code, text,
                retryAfterSec = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull(),
                location = conn.getHeaderField("Location"),
            )
        } catch (e: java.net.UnknownHostException) {
            throw TranslateException("Sunucuya ulaşılamadı. İnternet bağlantınızı ve adresi kontrol edin.")
        } catch (e: java.net.SocketTimeoutException) {
            throw TranslateException("Sunucu zamanında yanıt vermedi.")
        } catch (e: IOException) {
            throw TranslateException("Bağlantı hatası: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_RESPONSE) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_RESPONSE - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}

/** Metin listesini seçilen sağlayıcıyla çevirir. */
class SubtitleTranslator(
    private val cfg: TrConfig,
    private val http: HttpTransport = UrlConnectionTransport,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    companion object {
        const val MYMEMORY_URL = "https://api.mymemory.translated.net/get"
        private const val BATCH_ITEMS = 25
        private const val BATCH_CHARS = 3500
        private const val MYMEMORY_MAX_BYTES = 450

        /** Çevrilecek harf içeren bir şey var mı (yalnız ♪ veya sayı olan satırlar atlanır). */
        fun needsTranslation(text: String): Boolean = text.any { it.isLetter() }

        fun unescapeHtml(s: String): String {
            if ('&' !in s) return s
            var t = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
            t = Regex("&#(\\d{1,6});").replace(t) { m ->
                m.groupValues[1].toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
            }
            return t.replace("&amp;", "&")
        }

        fun systemPrompt(source: String, target: String): String {
            val from = if (source == "auto") "the source language (detect it)" else TrLangs.englishName(source)
            val to = TrLangs.englishName(target)
            return "You are a professional subtitle translator. The user sends a JSON array of subtitle lines. " +
                "Translate every line from $from into $to. Rules: return ONLY a JSON array of strings, with exactly the same " +
                "number of items in the same order, no commentary and no code fences; never merge, split, add or drop items; " +
                "keep line breaks as \\n; keep tags, speaker dashes, music symbols and names; use natural spoken language; " +
                "translate sound descriptions such as [door slams] inside their brackets."
        }
    }

    /**
     * [texts] ile aynı sırada çeviri döndürür. Aynı metin bir kez çevrilir; çevrilecek harfi olmayanlar olduğu gibi kalır.
     * [progress] çevrilen benzersiz satır sayısını ve toplamı bildirir.
     */
    fun translate(
        texts: List<String>,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val keys = LinkedHashSet<String>()
        for (t in texts) if (needsTranslation(t)) keys.add(t)
        val list = keys.toList()
        val done = AtomicInteger(0)
        val tick = { n: Int ->
            val d = done.addAndGet(n)
            progress(d, list.size)
        }
        progress(0, list.size)
        val translated: List<String> = when {
            list.isEmpty() -> emptyList()
            cfg.provider == TrProvider.MYMEMORY -> viaMyMemory(list, cancelled, tick)
            cfg.provider == TrProvider.LIBRE -> viaLibre(list, cancelled, tick)
            else -> viaOpenAi(list, cancelled, tick)
        }
        val map = HashMap<String, String>()
        list.forEachIndexed { i, k -> map[k] = translated.getOrNull(i)?.ifBlank { null } ?: k }
        return texts.map { map[it] ?: it }
    }

    fun translateCues(
        cues: List<SubCue>,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SubCue> {
        val out = translate(cues.map { it.text.toString().trim() }, cancelled, progress)
        return cues.mapIndexed { i, c -> c.copy(text = out[i]) }
    }

    // ------------------------------------------------------------------ ortak HTTP

    private fun call(method: String, url: String, headers: Map<String, String>, body: String?, cancelled: () -> Boolean): HttpResult {
        var attempt = 0
        while (true) {
            if (cancelled()) throw TranslateCancelled()
            val r = http.send(method, url, headers, body)
            if (r.code in 200..299) return r
            val retryable = r.code == 429 || r.code in 500..599
            if (retryable && attempt < 4) {
                val waitSec = (r.retryAfterSec ?: (2 shl attempt)).coerceIn(1, 30)
                var waited = 0L
                while (waited < waitSec * 1000L) {
                    if (cancelled()) throw TranslateCancelled()
                    sleep(250)
                    waited += 250
                }
                attempt++
                continue
            }
            throw httpError(r)
        }
    }

    private fun httpError(r: HttpResult): TranslateException {
        val server = serverMessage(r.body)
        val base = when (r.code) {
            401, 403 -> "Sunucu erişimi reddetti (HTTP ${r.code}). API anahtarını ve adresi kontrol edin."
            404 -> "Adres veya model bulunamadı (HTTP 404). Adresi kontrol edin; model adı eskimiş olabilir, 'Model listesini getir' ile güncel bir model seçin."
            301, 302, 307, 308 -> "Adres başka yere yönlendiriyor${r.location?.let { ": $it" } ?: ""}. Doğru adresi girin."
            429 -> "İstek sınırı aşıldı (HTTP 429). Biraz bekleyip tekrar deneyin veya başka sağlayıcı seçin."
            else -> "Sunucu hatası (HTTP ${r.code})."
        }
        return TranslateException(if (server.isEmpty()) base else "$base\n$server")
    }

    private fun serverMessage(body: String): String {
        val t = body.trim()
        if (t.isEmpty()) return ""
        val msg = runCatching {
            val o = JSONObject(t)
            val e = o.opt("error")
            when {
                e is JSONObject -> e.optString("message")
                e is String -> e
                o.has("message") -> o.optString("message")
                o.has("detail") -> o.optString("detail")
                else -> ""
            }
        }.getOrNull() ?: if (t.startsWith("<")) "" else t
        return msg.replace(Regex("\\s+"), " ").trim().take(200)
    }

    private fun jsonHeaders(key: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json"
        if (key.isNotBlank()) h["Authorization"] = "Bearer ${key.trim()}"
        return h
    }

    // ------------------------------------------------------------------ MyMemory

    private fun myMemoryCode(code: String): String = when (code) {
        "auto" -> "Autodetect"
        "zh" -> "zh-CN"
        else -> code
    }

    private fun viaMyMemory(keys: List<String>, cancelled: () -> Boolean, tick: (Int) -> Unit): List<String> {
        val out = arrayOfNulls<String>(keys.size)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = keys.mapIndexed { i, k ->
                pool.submit(Callable {
                    if (cancelled()) throw TranslateCancelled()
                    out[i] = myMemoryText(k, cancelled)
                    tick(1)
                })
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return out.map { it ?: "" }
    }

    /** 500 bayt sınırı nedeniyle uzun satırlar kelime sınırında bölünür. */
    private fun myMemoryText(text: String, cancelled: () -> Boolean): String {
        val chunks = ArrayList<String>()
        var cur = StringBuilder()
        for (word in text.split(' ')) {
            val extra = if (cur.isEmpty()) word else " $word"
            if (cur.isNotEmpty() && (cur.toString() + extra).toByteArray(Charsets.UTF_8).size > MYMEMORY_MAX_BYTES) {
                chunks.add(cur.toString())
                cur = StringBuilder(word)
            } else {
                cur.append(extra)
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks.joinToString(" ") { myMemoryOne(it, cancelled) }
    }

    private fun myMemoryOne(text: String, cancelled: () -> Boolean): String {
        val pair = URLEncoder.encode("${myMemoryCode(cfg.source)}|${myMemoryCode(cfg.target)}", "UTF-8")
        val url = buildString {
            append(MYMEMORY_URL).append("?q=").append(URLEncoder.encode(text, "UTF-8")).append("&langpair=").append(pair)
            if (cfg.email.isNotBlank()) append("&de=").append(URLEncoder.encode(cfg.email.trim(), "UTF-8"))
        }
        val r = call("GET", url, mapOf("Accept" to "application/json"), null, cancelled)
        val json = runCatching { JSONObject(r.body) }.getOrNull() ?: throw TranslateException("MyMemory beklenmeyen yanıt verdi.")
        val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
        if (translated.contains("MYMEMORY WARNING", ignoreCase = true)) {
            throw TranslateException(
                "MyMemory günlük ücretsiz kotası doldu. Ayarlar > Altyazı çevirisi bölümüne e-posta adresi girerseniz kota günde 50.000 karaktere çıkar; " +
                    "ya da başka bir sağlayıcı seçin."
            )
        }
        val status = json.optInt("responseStatus", 200)
        if (status != 200) {
            val details = json.optString("responseDetails").ifBlank { "durum $status" }
            throw TranslateException("MyMemory hatası: ${details.take(160)}")
        }
        if (translated.isBlank()) throw TranslateException("MyMemory boş çeviri döndürdü.")
        return unescapeHtml(translated)
    }

    // ------------------------------------------------------------------ LibreTranslate

    private fun libreEndpoint(): String {
        val base = cfg.apiUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw TranslateException("LibreTranslate adresi girilmemiş (Ayarlar > Altyazı çevirisi).")
        return if (base.endsWith("/translate")) base else "$base/translate"
    }

    private fun viaLibre(keys: List<String>, cancelled: () -> Boolean, tick: (Int) -> Unit): List<String> {
        val endpoint = libreEndpoint()
        val out = ArrayList<String>(keys.size)
        for (batch in batches(keys)) out += libreBatch(endpoint, batch, cancelled, tick)
        return out
    }

    private fun libreBatch(endpoint: String, batch: List<String>, cancelled: () -> Boolean, tick: (Int) -> Unit): List<String> {
        val body = JSONObject()
            .put("q", JSONArray(batch))
            .put("source", cfg.source)
            .put("target", cfg.target)
            .put("format", "text")
        if (cfg.apiKey.isNotBlank()) body.put("api_key", cfg.apiKey.trim())
        val r = call("POST", endpoint, mapOf("Content-Type" to "application/json", "Accept" to "application/json"), body.toString(), cancelled)
        val json = runCatching { JSONObject(r.body) }.getOrNull() ?: throw TranslateException("LibreTranslate beklenmeyen yanıt verdi.")
        val tt = json.opt("translatedText")
        val result: List<String>? = when (tt) {
            is JSONArray -> List(tt.length()) { tt.optString(it) }
            is String -> listOf(tt)
            else -> null
        }
        if (result != null && result.size == batch.size) {
            tick(batch.size)
            return result
        }
        if (batch.size > 1) {
            val mid = batch.size / 2
            return libreBatch(endpoint, batch.subList(0, mid), cancelled, tick) + libreBatch(endpoint, batch.subList(mid, batch.size), cancelled, tick)
        }
        throw TranslateException("LibreTranslate beklenmeyen yanıt verdi.")
    }

    // ------------------------------------------------------------------ OpenAI uyumlu yapay zekâ

    private fun chatEndpoint(): String {
        val base = cfg.apiUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw TranslateException("Yapay zekâ adresi girilmemiş (Ayarlar > Altyazı çevirisi).")
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** Kullanılabilir model adlarını sağlayıcıdan okur ({adres}/models). */
    fun listModels(cancelled: () -> Boolean = { false }): List<String> {
        val base = cfg.apiUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        if (base.isEmpty()) throw TranslateException("Yapay zekâ adresi girilmemiş.")
        val r = call("GET", "$base/models", jsonHeaders(cfg.apiKey), null, cancelled)
        val json = runCatching { JSONObject(r.body) }.getOrNull() ?: throw TranslateException("Model listesi okunamadı.")
        val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: throw TranslateException("Model listesi okunamadı.")
        val ids = ArrayList<String>()
        for (i in 0 until data.length()) {
            val o = data.opt(i)
            val id = when (o) {
                is JSONObject -> o.optString("id").ifBlank { o.optString("name") }
                is String -> o
                else -> ""
            }
            if (id.isNotBlank()) ids.add(id.removePrefix("models/"))
        }
        return ids.distinct().sorted()
    }

    private fun viaOpenAi(keys: List<String>, cancelled: () -> Boolean, tick: (Int) -> Unit): List<String> {
        val endpoint = chatEndpoint()
        if (cfg.model.isBlank()) throw TranslateException("Model adı girilmemiş (Ayarlar > Altyazı çevirisi).")
        val out = ArrayList<String>(keys.size)
        for (batch in batches(keys)) out += openAiBatch(endpoint, batch, cancelled, tick)
        return out
    }

    private fun openAiBatch(endpoint: String, batch: List<String>, cancelled: () -> Boolean, tick: (Int) -> Unit): List<String> {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(cfg.source, cfg.target)))
            .put(JSONObject().put("role", "user").put("content", JSONArray(batch).toString()))
        val body = JSONObject().put("model", cfg.model.trim()).put("temperature", 0.2).put("messages", messages)
        val r = call("POST", endpoint, jsonHeaders(cfg.apiKey), body.toString(), cancelled)
        val content = chatContent(r.body)
        val parsed = parseStringArray(content)
        if (parsed != null && parsed.size == batch.size) {
            tick(batch.size)
            return parsed
        }
        if (batch.size > 1) {
            val mid = batch.size / 2
            return openAiBatch(endpoint, batch.subList(0, mid), cancelled, tick) + openAiBatch(endpoint, batch.subList(mid, batch.size), cancelled, tick)
        }
        // Tek satır: model diziyi bozduysa düz metni kabul et.
        val single = cleanSingle(content)
        if (single.isBlank()) throw TranslateException("Yapay zekâ boş yanıt verdi.")
        tick(1)
        return listOf(single)
    }

    private fun chatContent(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw TranslateException("Yapay zekâ beklenmeyen yanıt verdi.")
        val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw TranslateException("Yapay zekâ beklenmeyen yanıt verdi.")
        val c = msg.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> buildString { for (i in 0 until c.length()) append(c.optJSONObject(i)?.optString("text").orEmpty()) }
            else -> ""
        }
    }

    private fun stripThinking(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    /** Yanıttaki ilk '[' ile son ']' arasını bir dizi olarak okur; öğelerin hepsi metin olmalı. */
    fun parseStringArray(raw: String): List<String>? {
        val text = stripThinking(raw)
        val s = text.indexOf('[')
        val e = text.lastIndexOf(']')
        if (s < 0 || e <= s) return null
        val arr = runCatching { JSONArray(text.substring(s, e + 1)) }.getOrNull() ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v !is String) return null
            out.add(v)
        }
        return out
    }

    private fun cleanSingle(raw: String): String {
        var t = stripThinking(raw).trim()
        t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length - 1).trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
        return t.replace("\\n", "\n").trim()
    }

    private fun batches(keys: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var chars = 0
        for (k in keys) {
            if (cur.isNotEmpty() && (cur.size >= BATCH_ITEMS || chars + k.length > BATCH_CHARS)) {
                out.add(cur)
                cur = ArrayList()
                chars = 0
            }
            cur.add(k)
            chars += k.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}

/** Çevrilmiş altyazıyı .srt metni olarak yazar. */
object SrtWriter {
    fun time(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }

    fun write(cues: List<SubCue>): String {
        val sb = StringBuilder()
        var n = 1
        for (c in cues) {
            val text = c.text.toString().replace("\r", "").trim().replace(Regex("\n{2,}"), "\n")
            if (text.isEmpty()) continue
            sb.append(n++).append('\n')
            sb.append(time(c.startMs)).append(" --> ").append(time(c.endMs)).append('\n')
            sb.append(text).append("\n\n")
        }
        return sb.toString()
    }
}
