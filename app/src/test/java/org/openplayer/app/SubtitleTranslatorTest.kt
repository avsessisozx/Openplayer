package org.openplayer.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class SubtitleTranslatorTest {
    @Before
    fun turkishStrings() = TestStrings.installTurkish()

    @After
    fun clearStrings() = TestStrings.uninstall()


    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private class Fake(val handler: (Call) -> HttpResult) : HttpTransport {
        val calls = ArrayList<Call>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
            val c = Call(method, url, headers, body)
            calls += c
            return handler(c)
        }
    }

    private val noSleep: (Long) -> Unit = { }

    private fun chatReply(content: String) = HttpResult(
        200,
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()
    )

    private fun userArray(c: Call): JSONArray {
        val msgs = JSONObject(c.body!!).getJSONArray("messages")
        return JSONArray(msgs.getJSONObject(msgs.length() - 1).getString("content"))
    }

    private fun openAi(handler: (Call) -> HttpResult) = Fake(handler).let {
        it to SubtitleTranslator(
            TrConfig(TrProvider.OPENAI, target = "tr", apiUrl = "https://example.test/v1/", apiKey = "k123", model = "m"),
            it, noSleep
        )
    }

    @Test fun openAi_translates_in_order_dedups_and_keeps_symbols() {
        val (fake, tr) = openAi { c ->
            val arr = userArray(c)
            chatReply(JSONArray((0 until arr.length()).map { "TR:" + arr.getString(it) }).toString())
        }
        val out = tr.translate(listOf("Hello", "♪ ♪", "Hello", "Bye\nnow", "123"))
        assertEquals(listOf("TR:Hello", "♪ ♪", "TR:Hello", "TR:Bye\nnow", "123"), out)
        assertEquals(1, fake.calls.size)
        assertEquals(2, userArray(fake.calls[0]).length()) // yalnız "Hello" ve "Bye\nnow"
        assertEquals("https://example.test/v1/chat/completions", fake.calls[0].url)
        assertEquals("Bearer k123", fake.calls[0].headers["Authorization"])
    }

    @Test fun openAi_handles_code_fence_and_thinking() {
        val (_, tr) = openAi { c ->
            val arr = userArray(c)
            val json = JSONArray((0 until arr.length()).map { "ok " + arr.getString(it) }).toString()
            chatReply("<think>düşünüyorum [1]</think>\n```json\n$json\n```")
        }
        assertEquals(listOf("ok a", "ok b"), tr.translate(listOf("a", "b")))
    }

    @Test fun openAi_splits_batch_when_model_returns_wrong_length() {
        var wrongOnce = true
        val (fake, tr) = openAi { c ->
            val arr = userArray(c)
            if (arr.length() > 1 && wrongOnce) {
                wrongOnce = false
                chatReply("[\"only one\"]")
            } else {
                chatReply(JSONArray((0 until arr.length()).map { "X" + arr.getString(it) }).toString())
            }
        }
        assertEquals(listOf("Xa", "Xb", "Xc", "Xd"), tr.translate(listOf("a", "b", "c", "d")))
        assertTrue(fake.calls.size >= 3)
    }

    @Test fun openAi_single_line_falls_back_to_plain_text() {
        val (_, tr) = openAi { chatReply("Merhaba dünya") }
        assertEquals(listOf("Merhaba dünya"), tr.translate(listOf("Hello world")))
    }

    @Test fun retries_429_then_succeeds_and_honours_cancel() {
        var n = 0
        val sleeps = ArrayList<Long>()
        val fake = Fake { c ->
            n++
            if (n < 3) HttpResult(429, "{\"error\":{\"message\":\"slow down\"}}", retryAfterSec = 1)
            else chatReply(JSONArray(listOf("sonuç")).toString())
        }
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake) { sleeps += it }
        assertEquals(listOf("sonuç"), tr.translate(listOf("result")))
        assertEquals(3, fake.calls.size)
        assertTrue(sleeps.sum() >= 2000)

        try {
            SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake, noSleep)
                .translate(listOf("again"), cancelled = { true })
            fail("iptal bekleniyordu")
        } catch (_: TranslateCancelled) {
        }
    }

    @Test fun http_errors_become_readable_messages() {
        val fake = Fake { HttpResult(401, "{\"error\":{\"message\":\"Invalid API Key\"}}") }
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", apiKey = "bad", model = "m"), fake, noSleep)
        try {
            tr.translate(listOf("hello"))
            fail()
        } catch (e: TranslateException) {
            assertTrue(e.message!!.contains("401"))
            assertTrue(e.message!!.contains("Invalid API Key"))
            assertTrue(!e.message!!.contains("bad"))
        }
    }

    @Test fun openAi_requires_model_and_url() {
        try {
            SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "", model = "m"), Fake { chatReply("[]") }, noSleep).translate(listOf("a"))
            fail()
        } catch (_: TranslateException) {
        }
        try {
            SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = ""), Fake { chatReply("[]") }, noSleep).translate(listOf("a"))
            fail()
        } catch (_: TranslateException) {
        }
    }

    @Test fun libre_batches_payload_and_response() {
        val fake = Fake { c ->
            val q = JSONObject(c.body!!).getJSONArray("q")
            HttpResult(200, JSONObject().put("translatedText", JSONArray((0 until q.length()).map { "L:" + q.getString(it) })).toString())
        }
        val tr = SubtitleTranslator(TrConfig(TrProvider.LIBRE, "tr", "en", apiUrl = "https://libre.test/", apiKey = "abc"), fake, noSleep)
        assertEquals(listOf("L:One", "L:Two"), tr.translate(listOf("One", "Two")))
        val body = JSONObject(fake.calls[0].body!!)
        assertEquals("https://libre.test/translate", fake.calls[0].url)
        assertEquals("en", body.getString("source"))
        assertEquals("tr", body.getString("target"))
        assertEquals("abc", body.getString("api_key"))
    }

    @Test fun libre_splits_when_array_length_differs() {
        var first = true
        val fake = Fake { c ->
            val q = JSONObject(c.body!!).getJSONArray("q")
            if (first && q.length() > 1) {
                first = false
                HttpResult(200, "{\"translatedText\":[\"x\"]}")
            } else HttpResult(200, JSONObject().put("translatedText", JSONArray((0 until q.length()).map { "L:" + q.getString(it) })).toString())
        }
        val tr = SubtitleTranslator(TrConfig(TrProvider.LIBRE, "tr", apiUrl = "https://libre.test/translate"), fake, noSleep)
        assertEquals(listOf("L:a", "L:b", "L:c"), tr.translate(listOf("a", "b", "c")))
    }

    @Test fun myMemory_builds_url_decodes_entities_and_reports_quota() {
        val fake = Fake { c ->
            val q = URLDecoder.decode(c.url.substringAfter("?q=").substringBefore("&"), "UTF-8")
            HttpResult(200, JSONObject().put("responseData", JSONObject().put("translatedText", "Merhaba &#39;$q&#39; &amp; &quot;iyi&quot;")).put("responseStatus", 200).toString())
        }
        val tr = SubtitleTranslator(TrConfig(TrProvider.MYMEMORY, "tr", "en", email = "me@example.com"), fake, noSleep)
        val out = tr.translate(listOf("Hello there"))
        assertEquals(listOf("Merhaba 'Hello there' & \"iyi\""), out)
        val url = fake.calls[0].url
        assertTrue(url.startsWith(SubtitleTranslator.MYMEMORY_URL))
        assertTrue(url.contains("langpair=en%7Ctr"))
        assertTrue(url.contains("de=me%40example.com"))

        val quota = Fake { HttpResult(200, "{\"responseData\":{\"translatedText\":\"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY\"},\"responseStatus\":200}") }
        try {
            SubtitleTranslator(TrConfig(TrProvider.MYMEMORY, "tr", "en"), quota, noSleep).translate(listOf("x y"))
            fail()
        } catch (e: TranslateException) {
            assertTrue(e.message!!.contains("kota"))
        }
    }

    @Test fun myMemory_splits_long_lines_below_limit() {
        val sizes = ArrayList<Int>()
        val fake = Fake { c ->
            val q = URLDecoder.decode(c.url.substringAfter("?q=").substringBefore("&"), "UTF-8")
            sizes += q.toByteArray().size
            HttpResult(200, JSONObject().put("responseData", JSONObject().put("translatedText", q.uppercase())).put("responseStatus", 200).toString())
        }
        val long = (1..200).joinToString(" ") { "word$it" }
        val out = SubtitleTranslator(TrConfig(TrProvider.MYMEMORY, "tr", "en"), fake, noSleep).translate(listOf(long))
        assertTrue(sizes.size > 1)
        assertTrue(sizes.all { it <= 450 })
        assertEquals(long.uppercase(), out[0])
    }

    @Test fun listModels_reads_data_ids() {
        val fake = Fake { HttpResult(200, "{\"data\":[{\"id\":\"models/b\"},{\"id\":\"a\"}]}") }
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1/chat/completions", apiKey = "k"), fake, noSleep)
        assertEquals(listOf("a", "b"), tr.listModels())
        assertEquals("https://x.test/v1/models", fake.calls[0].url)
        assertEquals("GET", fake.calls[0].method)
    }

    @Test fun translateCues_keeps_timing_and_srt_writer_formats() {
        val fake = Fake { c ->
            val arr = userArray(c)
            chatReply(JSONArray((0 until arr.length()).map { "T-" + arr.getString(it) }).toString())
        }
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake, noSleep)
        val cues = listOf(SubCue(1_500, 3_000, "Hi"), SubCue(3_725_005, 3_728_000, "Line one\nLine two"))
        val out = tr.translateCues(cues)
        assertEquals(1_500L, out[0].startMs)
        assertEquals("T-Hi", out[0].text.toString())
        val srt = SrtWriter.write(out)
        assertEquals(
            "1\n00:00:01,500 --> 00:00:03,000\nT-Hi\n\n2\n01:02:05,005 --> 01:02:08,000\nT-Line one\nLine two\n\n",
            srt
        )
    }

    @Test fun parseStringArray_rejects_non_string_items() {
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr"), Fake { chatReply("") }, noSleep)
        assertEquals(null, tr.parseStringArray("[1,2]"))
        assertEquals(listOf("a", "b"), tr.parseStringArray("Sure! [\"a\",\"b\"] done"))
        assertEquals(null, tr.parseStringArray("no array"))
    }

    @Test fun failure_midway_keeps_cache_and_resume_skips_done_lines() {
        val lines = (1..120).map { "line number $it" }
        var failFrom = 60
        val fake = Fake { c ->
            val arr = userArray(c)
            val first = arr.getString(0).substringAfterLast(' ').toInt()
            if (first >= failFrom) HttpResult(500, "{\"error\":{\"message\":\"boom\"}}")
            else chatReply(JSONArray((0 until arr.length()).map { "T:" + arr.getString(it) }).toString())
        }
        val cache = HashMap<String, String>()
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake, noSleep)
        try {
            tr.translate(lines, cache = cache)
            fail("hata bekleniyordu")
        } catch (_: TranslateException) {
        }
        assertTrue("ilk parçalar kaydedilmeli", cache.size >= 50)
        assertTrue(cache.containsKey("line number 1"))
        failFrom = 10_000
        val before = fake.calls.size
        val out = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake, noSleep)
            .translate(lines, cache = cache)
        assertEquals(120, out.size)
        assertTrue(out.all { it.startsWith("T:") })
        val resumeCalls = fake.calls.drop(before)
        assertTrue("çevrilenler yeniden istenmemeli", resumeCalls.none { userArray(it).getString(0) == "line number 1" })
    }

    @Test fun daily_quota_429_fails_fast_with_clear_message() {
        val body = "[{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric generate_content_free_tier_requests per day\"," +
            "\"details\":[{\"violations\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}]"
        val fake = Fake { HttpResult(429, body) }
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake, noSleep)
        try {
            tr.translate(listOf("hello"))
            fail()
        } catch (e: TranslateException) {
            assertTrue(e.message!!, "Günlük" in e.message!!)
        }
        assertEquals(1, fake.calls.size)
        assertTrue(tr.log.isNotEmpty())
    }

    @Test fun gemini_retry_delay_in_body_is_honoured() {
        var n = 0
        val sleeps = ArrayList<Long>()
        val fake = Fake {
            n++
            if (n == 1) HttpResult(429, "[{\"error\":{\"message\":\"rate\",\"details\":[{\"retryDelay\":\"34s\"}]}}]")
            else chatReply(JSONArray(listOf("tamam")).toString())
        }
        val tr = SubtitleTranslator(TrConfig(TrProvider.OPENAI, "tr", apiUrl = "https://x.test/v1", model = "m"), fake) { sleeps += it }
        assertEquals(listOf("tamam"), tr.translate(listOf("ok")))
        assertTrue(sleeps.sum() >= 34_000)
    }
}
