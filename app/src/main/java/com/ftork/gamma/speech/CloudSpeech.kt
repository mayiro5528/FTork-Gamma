package com.ftork.gamma.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Google Cloud Speech-to-Text 로 한 문장 분량의 소리를 받아써요 (번역과 같은 Google 키).
 * 기기 인식기가 빠르게 글자를 보여 주고, 문장이 끝나면 이걸로 다시 받아써서 더 정확한 글로 바꿔요.
 * - 언어는 하나로 정해서 보내요 (모를 때만 후보 언어를 함께)
 * - 최신 모델(latest_long)을 먼저 쓰고, 그 언어에 없으면 기본 모델로
 */
class CloudSpeech(
    private val key: () -> String,
    private val packageName: String,
    private val certSha1: () -> String?,
) {
    class Failure(val code: Int, message: String) : Exception(message)

    /** 언어별로 latest_long 이 안 되면 기억해 두고 기본 모델을 써요 */
    private val noModel = mutableSetOf<String>()
    var model = "latest_long"

    /** @param lang "vi-VN" 같은 정확한 코드. @param alt 언어를 모를 때 후보 */
    suspend fun recognize(pcm: ShortArray, lang: String, alt: String? = null): String = withContext(Dispatchers.IO) {
        val audio = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
        val useModel = lang !in noModel && alt == null
        try {
            call(b64, lang, alt, if (useModel) model else null)
        } catch (e: Failure) {
            if (useModel && e.code == 400) { noModel += lang; call(b64, lang, alt, null) } else throw e
        }
    }

    private fun call(b64: String, lang: String, alt: String?, model: String?): String {
        val k = key()
        if (k.isEmpty()) throw Failure(0, "Google 키가 없어요")
        val c = URL("https://speech.googleapis.com/v1p1beta1/speech:recognize?key=$k").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 20000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        // 키를 "Android 앱" 으로 제한했을 때 필요한 정보 (번역과 같아요)
        c.setRequestProperty("X-Android-Package", packageName)
        certSha1()?.let { c.setRequestProperty("X-Android-Cert", it.replace(":", "")) }
        val config = JSONObject()
            .put("encoding", "LINEAR16").put("sampleRateHertz", 16000)
            .put("languageCode", lang)
            .put("enableAutomaticPunctuation", true)
        if (model != null) config.put("model", model)
        if (alt != null) config.put("alternativeLanguageCodes", JSONArray().put(alt))
        val body = JSONObject().put("config", config).put("audio", JSONObject().put("content", b64))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val reply = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(reply).getJSONObject("error").getString("message") }.getOrNull() ?: ""
            throw Failure(code, describe(code, msg))
        }
        val results = JSONObject(reply).optJSONArray("results") ?: return ""
        return (0 until results.length()).joinToString(" ") {
            results.getJSONObject(it).optJSONArray("alternatives")?.optJSONObject(0)?.optString("transcript").orEmpty().trim()
        }.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        fun describe(code: Int, msg: String): String = when {
            code == 403 && ("disabled" in msg || "has not been used" in msg) ->
                "Google Cloud 콘솔에서 Cloud Speech-to-Text API 를 '사용'으로 켜 주세요."
            code == 403 && ("restrict" in msg || "blocked" in msg || "not authorized" in msg) ->
                "키의 API 제한에 Cloud Speech-to-Text API 를 추가해 주세요 (또는 Android 앱 제한 값 확인)."
            code == 400 && "API key not valid" in msg -> "Google 키가 올바르지 않아요."
            code == 429 -> "Google 음성 인식 사용량 한도를 넘었어요."
            else -> "Google 음성 인식 오류 $code ${msg.take(120)}"
        }
    }
}
