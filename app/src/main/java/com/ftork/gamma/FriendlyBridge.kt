package com.ftork.gamma

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ftork.gamma.data.MlKitTranslationEngine
import com.ftork.gamma.data.SmartTranslationEngine
import com.ftork.gamma.data.TranslationResult
import com.ftork.gamma.photo.PhotoLine
import com.ftork.gamma.photo.PhotoTranslator
import com.ftork.gamma.speech.Speaker
import com.ftork.gamma.speech.SpeechListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * FTalk Beta 화면(index.html)이 부르는 `FriendlyNative.*` 함수들.
 * 일은 모두 FTalk 엔진으로 해요.
 *  - 음성 인식 : 휴대폰 음성 인식(SpeechRecognizer, 보통 Google) — 언어를 하나씩 정확히 지정
 *  - 번역      : ML Kit (기기 안, 무료)
 *  - 읽기      : 휴대폰 TextToSpeech
 *  - 사진      : ML Kit 글자 인식 + ML Kit 번역
 *
 * streamStart / streamStop(위스퍼 스트림)은 일부러 만들지 않았어요.
 * 그러면 화면이 휴대폰 음성 인식 경로(startRecognition…)를 써요.
 *
 * JS 에서 오는 호출은 별도 스레드라서, 모두 메인 스레드로 넘겨서 처리해요.
 */
class FriendlyBridge(private val activity: MainActivity) {

    private val main = Handler(Looper.getMainLooper())
    /** Google 키가 있으면 Google 서버 번역, 없으면 ML Kit (기기 안) */
    private val engine = SmartTranslationEngine(activity, MlKitTranslationEngine())
    private val speaker = Speaker(activity)
    private val listener = SpeechListener(activity)
    private val photos = PhotoTranslator(engine)
    private val log = ArrayDeque<String>()

    private var me = "ko"
    private var partner = "vi"
    private var photoTarget: String? = null
    private var earpiece = false

    // 음성 인식 상태
    private var speechId = 0
    private var finishing = false

    // 사진 상태
    private var currentPhoto: Bitmap? = null
    private var currentTranslated: Bitmap? = null
    private var savedOnce = false
    private var photoJob: Job? = null

    private fun ui(block: () -> Unit) = main.post(block)
    private fun note(line: String) {
        synchronized(log) {
            log.addLast(SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date()) + " " + line)
            while (log.size > 80) log.removeFirst()
        }
    }

    // ================= 화면 · 설정 =================

    @JavascriptInterface fun pageReady() = ui { activity.onPageReady() }

    @JavascriptInterface fun appVersion(): String = BuildConfig.VERSION_NAME

    /** 화면의 엔진 설정. 음성 인식은 항상 Google 음성 인식(android), 번역은 mlkit 또는 google. */
    @JavascriptInterface
    fun getConfig(): String {
        val key = engine.googleKey
        return JSONObject()
            .put("stt", "android").put("translate", if (engine.preferred == "google") "google" else "mlkit").put("realtime", false)
            .put("whisperInstalled", true).put("hasOpenaiKey", false)
            .put("hasGoogleKey", key.isNotEmpty())
            .put("googleKey", if (key.length > 10) key.take(6) + "…" + key.takeLast(4) else "")
            .put("package", activity.packageName)
            .put("sha1", engine.certSha1 ?: "")
            .put("speechService", listener.serviceLabel)
            .toString()
    }

    @JavascriptInterface
    fun setConfig(json: String) {
        runCatching {
            val o = JSONObject(json)
            o.optString("me").takeIf { it.isNotEmpty() }?.let { me = it }
            o.optString("partner").takeIf { it.isNotEmpty() }?.let { partner = it }
            o.optString("translate").takeIf { it == "google" || it == "mlkit" }?.let { engine.preferred = it }
        }
        warmUp()
    }

    /** 설정 화면의 Google 키 저장 · 삭제 (빈 값이면 삭제). OpenAI 키는 쓰지 않아요. */
    @JavascriptInterface
    fun saveKey(kind: String, value: String) {
        if (kind == "google") engine.googleKey = value
    }

    /** 설정 화면의 "연결 테스트" */
    @JavascriptInterface
    fun testCloud(kind: String) {
        activity.lifecycleScope.launch {
            val message = if (kind != "google") "FTalk 은 Google 키만 써요."
            else if (!engine.hasGoogleKey) "Google 키를 먼저 저장하세요."
            else try {
                "연결 성공 · Google 번역: 안녕하세요 → " + engine.testGoogle()
            } catch (e: Exception) {
                "연결 실패 · " + (e.message ?: "알 수 없는 오류") + "\n(키가 없거나 실패하면 기기 번역 ML Kit 로 번역해요)"
            }
            activity.callJs("friendlyNativeAi", "test", JSONObject().put("message", message).toString())
        }
    }

    private var warmed = ""

    /**
     * 내 언어·상대방 언어 번역 모델을 미리 받아 둬요.
     * 그래야 상황별 대화에서 문장을 고르거나 처음 말할 때 기다리지 않고 바로 번역돼요.
     */
    private fun warmUp() {
        val pair = "$me-$partner"
        if (pair == warmed) return
        warmed = pair
        activity.lifecycleScope.launch {
            for (lang in listOf(me, partner).filter { it != "en" }) {
                engine.download(lang).onFailure { note("모델 미리 받기 실패 $lang: ${it.message}") }
            }
        }
    }

    @JavascriptInterface fun keepScreenOn(on: Boolean) = ui { activity.keepScreenOn(on) }
    @JavascriptInterface fun setSituation(name: String) { note("상황: $name") }

    @JavascriptInterface
    fun copyText(text: String) = ui {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FTalk", text))
    }

    @JavascriptInterface
    fun shareText(text: String) = ui {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { activity.startActivity(Intent.createChooser(send, "공유")) }
    }

    // ================= 음성 인식 (눌러 말하기 · 실시간 대화 · 듣기) =================

    /** lang 은 ko-KR, vi-VN 같은 정확한 언어 코드. 한 번에 한 언어만 들어요. */
    @JavascriptInterface
    fun startRecognition(id: Int, lang: String) = ui {
        activity.withMicPermission { granted ->
            if (!granted) {
                emitSpeech(id, "error", "not-allowed"); emitSpeech(id, "end", ""); return@withMicPermission
            }
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                emitSpeech(id, "error", "recognizer-unavailable"); emitSpeech(id, "end", ""); return@withMicPermission
            }
            speaker.stop() // 번역 읽는 소리가 마이크로 다시 들어가지 않게
            speechId = id
            finishing = false
            note("인식 시작 $lang")
            listener.start(lang, SpeechListener.Callbacks(
                onReady = { if (speechId == id) emitSpeech(id, "start", "") },
                onPartial = { if (speechId == id) emitSpeech(id, "partial", it) },
                onFinal = { text ->
                    if (speechId == id) {
                        if (text.isNotBlank()) emitSpeech(id, "final", text) else emitSpeech(id, "error", "no-speech")
                        end(id)
                    }
                },
                onError = { code ->
                    if (speechId == id) {
                        note("인식 오류 $code")
                        emitSpeech(id, "error", webError(code))
                        end(id)
                    }
                },
            ))
        }
    }

    /** 말 끝내기: 지금까지 들은 걸로 결과를 받아요. */
    @JavascriptInterface
    fun finishRecognition(id: Int) = ui {
        if (speechId == id) { finishing = true; listener.finish() }
    }

    /** 취소: 결과 없이 멈춰요. (화면은 이미 이 id 를 잊었어요) */
    @JavascriptInterface
    fun stopRecognition(id: Int) = ui {
        if (speechId == id) { speechId = 0; listener.stop() }
    }

    private fun end(id: Int) {
        emitSpeech(id, "end", "")
        if (speechId == id) speechId = 0
        listener.stop()
    }

    private fun emitSpeech(id: Int, kind: String, payload: String) =
        activity.callJs("friendlyNativeSpeechEvent", id, kind, payload)

    /** 안드로이드 오류 번호 → 화면(Web Speech) 오류 이름 */
    private fun webError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 10 -> "busy"
        SpeechRecognizer.ERROR_SERVER, 11 -> "server"
        SpeechRecognizer.ERROR_CLIENT -> if (finishing) "no-speech" else "client"
        12 -> "language-not-supported"
        13 -> "language-unavailable"
        else -> "client"
    }

    // ================= 번역 =================

    @JavascriptInterface
    fun translate(id: Int, text: String, from: String, to: String) = ui {
        activity.lifecycleScope.launch {
            when (val r = engine.translate(text, from, to)) {
                is TranslationResult.Success -> activity.callJs("friendlyNativeTranslation", id, true, r.text)
                is TranslationResult.Failure -> {
                    note("번역 실패 $from→$to: ${r.message}")
                    activity.callJs("friendlyNativeTranslation", id, false, r.message)
                }
            }
        }
    }

    // ================= 읽기 (TTS) =================

    /** 듣기 모드에서는 통화 스피커(귀)로, 다른 모드는 일반 스피커로 읽어요. */
    @JavascriptInterface fun setEarpiece(on: Boolean) { earpiece = on }

    @JavascriptInterface
    fun speak(text: String, lang: String, rate: Double) = ui {
        speaker.rate = rate.toFloat().coerceIn(0.5f, 2f)
        val error = speaker.speak(text, lang, earpiece) {
            activity.callJs("friendlyNativeTts", "done", "")
        }
        if (error != null) {
            note("읽기 실패 $lang: $error")
            val detail = if (error.contains("언어")) "lang" else "init"
            activity.callJs("friendlyNativeTts", "error", detail)
            if (detail != "lang") activity.callJs("friendlyNativeNotice", error)
        }
        earpiece = false
    }

    @JavascriptInterface fun stopSpeaking() = ui { speaker.stop(); speaker.useEarpiece(false) }

    // ================= 계산기 환율 =================

    /** 1 단위 외화 = 몇 원인지. (인터넷이 될 때만, 개인 정보 없이 공개 환율만 받아요) */
    @JavascriptInterface
    fun fetchRates() {
        activity.lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL("https://open.er-api.com/v6/latest/KRW").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val rates = JSONObject(body).getJSONObject("rates")
                    val out = JSONObject().put("KRW", 1.0)
                    for (cur in listOf("VND", "USD", "CNY", "JPY")) {
                        val perKrw = rates.optDouble(cur, 0.0)
                        if (perKrw > 0) out.put(cur, 1.0 / perKrw)
                    }
                    out.toString()
                }.getOrNull()
            }
            if (json != null) activity.callJs("friendlyNativeRates", json) else note("환율 받기 실패")
        }
    }

    // ================= 사진 번역 =================

    @JavascriptInterface fun setPhotoTarget(code: String) { photoTarget = code }
    @JavascriptInterface fun takePhoto() = ui { activity.launchCamera() }
    @JavascriptInterface fun pickPhoto() = ui { activity.launchGallery() }
    @JavascriptInterface fun translatePhoto() = ui { currentPhoto?.let { runPhoto(it) } }

    // ---- 실시간 카메라 (화면의 openLive() 가 불러요) ----
    @JavascriptInterface fun liveCameraSupported(): Boolean = true
    @JavascriptInterface fun openLiveCamera(json: String) = ui {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        o.put("theme", theme).put("style", style)
        activity.launchLive(o.toString())
    }

    // FTalk 화면 설정(다크/라이트 · 컬러/심플): 카메라 화면도 같은 색으로 그려요.
    @Volatile private var theme = "dark"
    @Volatile private var style = "simple"
    @JavascriptInterface fun setTheme(theme: String, style: String) { this.theme = theme; this.style = style }

    /** 실시간 카메라에서 셔터를 눌렀을 때: 번역하고 원본·번역본을 자동 저장해요. */
    fun onLiveShot(src: String, dst: String) {
        val bmp = com.ftork.gamma.photo.LiveCameraResult.take()
        if (bmp == null) { activity.callJs("friendlyNativePhotoCancel"); return }
        photoTarget = dst
        activity.callJs("friendlyNativePhotoReturn")
        photoJob?.cancel()
        photoJob = activity.lifecycleScope.launch {
            currentPhoto = bmp; currentTranslated = null; savedOnce = false
            val encoded = withContext(Dispatchers.Default) { PhotoTranslator.toBase64Jpeg(bmp) }
            activity.callJs("friendlyNativePhoto", "saveState", "none", null)
            activity.callJs("friendlyNativePhoto", "original", "글자 찾는 중…", encoded)
            runPhoto(bmp, src, dst, autoSave = true)
        }
    }

    private fun target() = photoTarget?.takeIf { it == me || it == partner } ?: me
    private fun source() = if (target() == me) partner else me

    fun onPhotoCancelled() = activity.callJs("friendlyNativePhotoCancel")

    /** 카메라로 찍은 사진은 바로 번역, 갤러리 사진은 "번역하기"를 누르면 번역해요. */
    fun onPhoto(uri: Uri, fromCamera: Boolean) {
        activity.callJs("friendlyNativePhotoReturn")
        photoJob?.cancel()
        photoJob = activity.lifecycleScope.launch {
            val bmp = try {
                withContext(Dispatchers.IO) { PhotoTranslator.load(activity.contentResolver, uri) }
            } catch (e: Exception) {
                activity.callJs("friendlyNativePhoto", "error", "사진을 읽지 못했어요.", null); return@launch
            }
            currentPhoto = bmp; currentTranslated = null; savedOnce = false
            val encoded = withContext(Dispatchers.Default) { PhotoTranslator.toBase64Jpeg(bmp) }
            activity.callJs("friendlyNativePhoto", "saveState", "none", null)
            if (fromCamera) {
                activity.callJs("friendlyNativePhoto", "original", "글자 찾는 중…", encoded)
                runPhoto(bmp)
            } else {
                activity.callJs("friendlyNativePhoto", "original", null, encoded)
                activity.callJs("friendlyNativePhoto", "loaded", "번역하기를 누르면 번역합니다", null)
            }
        }
    }

    private fun runPhoto(bmp: Bitmap, src: String = source(), dst: String = target(), autoSave: Boolean = false) {
        photoJob?.cancel()
        photoJob = activity.lifecycleScope.launch {
            activity.callJs("friendlyNativePhoto", "status", "글자 찾는 중…", null)
            val lines: List<PhotoLine> = try {
                photos.translate(bmp, src, dst)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                note("사진 번역 실패: ${e.message}")
                activity.callJs("friendlyNativePhoto", "error", e.message ?: "번역하지 못했어요.", null); return@launch
            }
            if (lines.isEmpty()) {
                activity.callJs("friendlyNativePhoto", "error", "사진에서 글자를 찾지 못했어요.", null); return@launch
            }
            activity.callJs("friendlyNativePhoto", "status", "번역을 사진에 넣는 중…", null)
            val (translated, encoded) = withContext(Dispatchers.Default) {
                val t = PhotoTranslator.render(bmp, lines)
                t to PhotoTranslator.toBase64Jpeg(t)
            }
            currentTranslated = translated
            val json = JSONArray().apply { lines.forEach { put(JSONObject().put("src", it.src).put("dst", it.dst)) } }
            activity.callJs("friendlyNativePhoto", "lines", json.toString(), null)
            activity.callJs("friendlyNativePhoto", "result", "${lines.size}곳 번역 · 기기 번역", encoded)
            if (autoSave) {
                val ok = saveBoth(translated)
                activity.callJs("friendlyNativePhoto", "saveState", if (ok) "saved" else "failed", null)
                if (ok) activity.callJs("friendlyNativeNotice", "원본과 번역본을 사진 앱(FTalk 앨범)에 저장했어요")
            } else {
                activity.callJs("friendlyNativePhoto", "saveState", "ready", null)
            }
        }
    }

    /** 원본과 번역본을 사진 앱(Pictures/FTalk)에 저장해요. */
    @JavascriptInterface
    fun savePhoto() = ui {
        val translated = currentTranslated ?: return@ui
        activity.lifecycleScope.launch {
            val ok = saveBoth(translated)
            activity.callJs("friendlyNativePhoto", "saveState", if (ok) "saved" else "failed", null)
        }
    }

    private suspend fun saveBoth(translated: Bitmap): Boolean {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                if (!savedOnce) currentPhoto?.let { saveImage(it, "FTalk_${stamp}_원본") }
                saveImage(translated, "FTalk_${stamp}_번역")
                true
            }.getOrDefault(false)
        }
        if (ok) savedOnce = true
        return ok
    }

    private fun saveImage(bmp: Bitmap, name: String) {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FTalk")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("저장 위치 없음")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out); bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
    }

    // ================= 새 버전 받기 (GitHub) =================

    /** GitHub 최신 릴리스 이름(v1.1 등)을 화면에 알려 줘요. */
    @JavascriptInterface
    fun checkUpdate() {
        activity.lifecycleScope.launch {
            val tag = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(LATEST_API).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    JSONObject(body).optString("tag_name")
                }.getOrNull()
            }
            activity.callJs("ftolkUpdateInfo", tag ?: "", BuildConfig.VERSION_NAME)
        }
    }

    /** 최신 APK 다운로드 페이지를 브라우저로 열어요. (이 주소만 열 수 있어요) */
    @JavascriptInterface
    fun openDownload() = ui {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE))) }
    }

    // ================= 진단 · 문제 보고 =================

    @JavascriptInterface
    fun runDiagnostics() {
        activity.lifecycleScope.launch {
            val langs = engine.downloadedLanguages()
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val online = cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val mic = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val msg = buildString {
                appendLine("앱: FTalk v${BuildConfig.VERSION_NAME}")
                appendLine("기기: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                appendLine("음성 인식 서비스: " + if (SpeechRecognizer.isRecognitionAvailable(activity)) "있음" else "없음 (Google 앱 필요)")
                appendLine("마이크 권한: " + if (mic) "허용됨" else "아직 없음 (마이크 버튼을 누르면 물어봐요)")
                appendLine("인터넷: " + if (online) "연결됨" else "끊김")
                appendLine("음성 인식기: " + listener.serviceLabel)
                appendLine("번역 엔진: " + when {
                    engine.usingGoogle -> "Google 서버 번역 (Cloud Translation)" + if (engine.lastEngine == "mlkit" && engine.lastError != null) " · 최근 실패 → 기기 번역 사용: ${engine.lastError}" else ""
                    engine.preferred == "google" -> "Google 선택됨 · 키 없음 → 기기 번역(ML Kit)"
                    else -> "기기 번역 (ML Kit)"
                })
                append("받은 기기 번역 모델: " + if (langs.isEmpty()) "없음" else langs.sorted().joinToString(", "))
            }
            activity.callJs("friendlyNativeDiagnostic", msg)
        }
    }

    @JavascriptInterface
    fun copyReport(): String {
        val text = buildString {
            appendLine("FTalk v${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine("언어: $me ↔ $partner")
            synchronized(log) { log.forEach { appendLine(it) } }
        }
        copyText(text)
        return text
    }

    companion object {
        const val DOWNLOAD_PAGE = "https://github.com/mayiro5528/FTork-Gamma/releases/latest"
        const val LATEST_API = "https://api.github.com/repos/mayiro5528/FTork-Gamma/releases/latest"
    }

    fun shutdown() {
        listener.stop()
        speaker.shutdown()
        photos.close()
        engine.close()
    }
}
