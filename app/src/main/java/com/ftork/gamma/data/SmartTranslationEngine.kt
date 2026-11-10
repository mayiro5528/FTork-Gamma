package com.ftork.gamma.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 번역 엔진 고르기.
 *  - "google": Google Cloud 번역 API (Google 번역 앱이 인터넷에서 쓰는 것과 같은 서버 번역). 키 필요.
 *  - "mlkit" : ML Kit (기기 안, 무료, 오프라인) — Google 번역 앱의 "오프라인 번역"과 같은 수준.
 * Google 을 골랐어도 키가 없거나 인터넷이 안 되면 자동으로 ML Kit 로 번역해요.
 */
class SmartTranslationEngine(private val context: Context, val offline: MlKitTranslationEngine) : TranslationEngine by offline {

    private val prefs = context.getSharedPreferences("ftalk_engine", Context.MODE_PRIVATE)

    var preferred: String
        get() = prefs.getString("translate", "mlkit") ?: "mlkit"
        set(v) { prefs.edit().putString("translate", v).apply() }

    var googleKey: String
        get() = prefs.getString("googleKey", "") ?: ""
        set(v) { prefs.edit().putString("googleKey", v.trim()).apply() }

    val hasGoogleKey get() = googleKey.isNotEmpty()
    val usingGoogle get() = preferred == "google" && hasGoogleKey

    /** 마지막 번역을 어느 엔진이 했는지 (진단용) */
    @Volatile var lastEngine = "mlkit"
        private set

    override suspend fun translate(text: String, source: String, target: String): TranslationResult {
        if (source == target) return TranslationResult.Success(text)
        if (usingGoogle) {
            try {
                val out = googleTranslate(listOf(text), source, target).first()
                lastEngine = "google"
                return TranslationResult.Success(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                // 인터넷이 없거나 키 문제 → 기기 번역으로
            }
        }
        lastEngine = "mlkit"
        return offline.translate(text, source, target)
    }

    @Volatile var lastError: String? = null
        private set

    /** 연결 테스트: 성공하면 번역 결과, 실패하면 예외 */
    suspend fun testGoogle(): String = googleTranslate(listOf("안녕하세요"), "ko", "vi").first()

    private suspend fun googleTranslate(texts: List<String>, source: String, target: String): List<String> =
        withContext(Dispatchers.IO) {
            val c = URL("https://translation.googleapis.com/language/translate/v2?key=$googleKey").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 키를 "Android 앱" 으로 제한했을 때 필요한 정보
            c.setRequestProperty("X-Android-Package", context.packageName)
            certSha1?.let { c.setRequestProperty("X-Android-Cert", it.replace(":", "")) }
            val body = JSONObject()
                .put("q", JSONArray(texts))
                .put("source", googleCode(source))
                .put("target", googleCode(target))
                .put("format", "text")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val reply = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(reply).getJSONObject("error").getString("message") }.getOrNull()
                throw IllegalStateException(describe(code, msg))
            }
            val arr = JSONObject(reply).getJSONObject("data").getJSONArray("translations")
            List(arr.length()) { arr.getJSONObject(it).getString("translatedText") }
        }

    /** 서명 인증서 SHA-1 (Google 키를 이 앱 전용으로 제한할 때 콘솔에 넣는 값) */
    val certSha1: String? by lazy {
        runCatching {
            val pm = context.packageManager
            val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!![0]
            }
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.getOrNull()
    }

    companion object {
        private fun googleCode(c: String) = when (c) { "zh" -> "zh-CN"; else -> c }

        private fun describe(code: Int, msg: String?) = when (code) {
            400 -> "Google 키가 올바르지 않아요" + (msg?.let { " ($it)" } ?: "")
            403 -> "Google 키 권한 문제: Cloud Translation API 사용 설정·결제·키 제한을 확인하세요" + (msg?.let { " ($it)" } ?: "")
            429 -> "Google 사용량 한도를 넘었어요"
            else -> "Google 번역 오류 $code" + (msg?.let { " ($it)" } ?: "")
        }
    }
}
