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
import com.ftork.gamma.speech.SentenceSegmenter
import com.ftork.gamma.speech.CloudSpeech
import com.ftork.gamma.speech.LiveMic
import com.ftork.gamma.speech.SentenceSpans
import com.ftork.gamma.speech.MissedRescue
import com.ftork.gamma.speech.Pcm
import com.ftork.gamma.speech.Speaker
import com.ftork.gamma.speech.MissedSpeech
import com.ftork.gamma.speech.SpeechListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 *               Google 엔진 + 키: 기기 인식기가 놓친 말(글자 없이 지나간 말소리)만 Google Cloud 음성 인식으로 받아써요
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
    private val engine = SmartTranslationEngine(activity, MlKitTranslationEngine().apply { log = { note(it) } })
    private val speaker = Speaker(activity)
    private val listener = SpeechListener(activity)
    /** Google Cloud 음성 인식 (번역과 같은 키) */
    private val cloud = CloudSpeech({ engine.googleKey }, activity.packageName, { engine.certSha1 })
    private val liveMic by lazy { LiveMic() }
    /** Google 음성 인식이 키·API 문제로 안 될 때 이유 (고칠 때까지 기기 인식만) */
    private var cloudOff: String? = null
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
        if (BuildConfig.DEBUG) android.util.Log.i("FTalkTest", line)
    }

    /** 시험용 (디버그 빌드): 앱 폴더의 음성 파일들을 가상 마이크로 틀어요. */
    fun startVirtualMic(names: List<String>, delayMs: Long) {
        if (!BuildConfig.DEBUG) return
        activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val mic = runCatching { com.ftork.gamma.speech.VirtualMic.load(names.map { java.io.File(activity.filesDir, it) }) }
                .onFailure { note("가상 마이크 실패: $it") }.getOrNull() ?: return@launch
            mic.play(delayMs)
            main.post { listener.virtualMic = mic; note("가상 마이크 재생 ${mic.durationMs}ms: $names") }
        }
    }

    /** 시험용 (디버그 빌드): 음성 파일 하나를 인식 방식별로 받아써요 (RecognitionBench). */
    fun startBench(name: String, mode: String) {
        if (!BuildConfig.DEBUG) return
        stopFlow(); listener.stop()
        activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var mic = runCatching { com.ftork.gamma.speech.VirtualMic.load(listOf(java.io.File(activity.filesDir, name))) }
                .onFailure { note("벤치 실패: $it") }.getOrNull() ?: return@launch
            if ("+agc" in mode) mic = com.ftork.gamma.speech.VirtualMic(com.ftork.gamma.speech.VirtualMic.agc(mic.pcmCopy()))
            main.post { com.ftork.gamma.speech.RecognitionBench(activity, mic, mode, listener.googleService, if (engine.hasGoogleKey) cloud else null) { note(it) }.start() }
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
            .put("speechService", if (cloudOn()) listener.serviceLabel + " + 놓친 말은 Google Cloud 음성 인식" else listener.serviceLabel + (cloudOff?.let { " · Google 음성 인식 꺼짐: $it" } ?: ""))
            .put("cloudSpeech", cloudOn())
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
        if (kind == "google") { engine.googleKey = value; cloudOff = null }
    }

    /** 설정 화면의 "연결 테스트" */
    @JavascriptInterface
    fun testCloud(kind: String) {
        activity.lifecycleScope.launch {
            val message = if (kind != "google") "FTalk 은 Google 키만 써요."
            else if (!engine.hasGoogleKey) "Google 키를 먼저 저장하세요."
            else {
                val t = try {
                    "연결 성공 · Google 번역: 안녕하세요 → " + engine.testGoogle()
                } catch (e: Exception) {
                    "연결 실패 · Google 번역: " + (e.message ?: "알 수 없는 오류") + "\n(키가 없거나 실패하면 기기 번역 ML Kit 로 번역해요)"
                }
                // 음성 인식도 같은 키로 되는지 (0.5초 조용한 소리를 보내 봐요)
                val s = try {
                    cloud.recognize(ShortArray(8000), "ko-KR"); cloudOff = null
                    "연결 성공 · Google 음성 인식"
                } catch (e: Exception) {
                    "연결 실패 · Google 음성 인식: " + (e.message ?: "알 수 없는 오류") + "\n(안 되면 기기 음성 인식으로 들어요)"
                }
                t + "\n" + s + if (t.startsWith("연결 실패") || s.startsWith("연결 실패")) keyIds() else ""
            }
            activity.callJs("friendlyNativeAi", "test", JSONObject().put("message", message).toString())
        }
    }

    /** 키를 "Android 앱" 으로 제한했을 때 콘솔에 넣어야 하는 이 앱의 값 (시험용 앱은 값이 달라요) */
    private fun keyIds() = "\n\nGoogle Cloud 콘솔 → 키 → Android 앱 제한에 넣을 값:\n패키지 이름: ${activity.packageName}\nSHA-1: ${engine.certSha1 ?: "-"}"

    /** 대화 중 Google 번역이 실패해 기기 번역으로 바뀌었을 때, 같은 이유로는 한 번만 알려요. */
    private var shownGoogleError: String? = null
    private fun reportGoogleFallback() {
        val err = engine.lastError ?: return
        if (engine.lastEngine != "mlkit" || err == shownGoogleError) return
        shownGoogleError = err
        note("Google 번역 실패 → 기기 번역: $err")
        activity.callJs("notice", "Google 번역이 안 돼서 기기 번역으로 바꿨어요. 설정 → Google 키 → 연결 테스트에서 이유를 볼 수 있어요.")
    }

    // ================= 실시간 대화 양방향 (Android 14+ 기기 안 인식기의 두 언어 자동 전환) =================
    /** checking · ready(양방향) · downloading(언어 받는 중) · unsupported(이 폰은 안 됨) · failed(써 보니 오류) */
    @Volatile private var dualState = "checking"
    @Volatile private var dualDetail = ""
    private var dualChecks = 0

    @JavascriptInterface fun dualState(): String = dualState
    @JavascriptInterface fun dualDetail(): String = dualDetail

    private fun checkDual(): Boolean = ui {
        if (Build.VERSION.SDK_INT < 34) { setDual("unsupported", "Android 14 이상에서만 돼요 (지금 Android ${Build.VERSION.RELEASE})"); return@ui }
        if (!listener.onDeviceAvailable) { setDual("unsupported", "이 폰에 기기 안 음성 인식기가 없어요"); return@ui }
        val a = me; val b = partner
        listener.checkOnDevice(bcp(a)) { installed, supported ->
            val missing = listOf(a, b).filter { it !in installed }
            when {
                missing.isEmpty() -> setDual("ready", "${langName(a)} · ${langName(b)} 양방향 인식 준비됨")
                missing.all { it in supported } -> {
                    missing.forEach { listener.downloadOnDevice(bcp(it)) }
                    setDual("downloading", missing.joinToString(", ") { langName(it) } + " 음성 인식 데이터를 받는 중 (Wi-Fi 권장)")
                    // 받는 중이면 잠시 뒤 다시 확인
                    if (dualChecks++ < 20) main.postDelayed({ checkDual() }, 15_000)
                }
                else -> setDual("unsupported", "이 폰의 기기 안 음성 인식이 " + missing.filter { it !in supported }.joinToString(", ") { langName(it) } + "를 지원하지 않아요")
            }
        }
    }

    private fun setDual(state: String, detail: String) {
        val changed = state != dualState
        dualState = state; dualDetail = detail
        note("양방향: $state · $detail")
        if (changed) activity.callJs("ftolkDualState", state, detail)
    }

    private fun langName(c: String) = when (c) { "ko" -> "한국어"; "vi" -> "베트남어"; "en" -> "영어"; "zh" -> "중국어"; else -> c }

    private var warmed = ""

    /**
     * 내 언어·상대방 언어 번역 모델을 미리 받아 둬요.
     * 그래야 상황별 대화에서 문장을 고르거나 처음 말할 때 기다리지 않고 바로 번역돼요.
     */
    private fun warmUp() {
        val pair = "$me-$partner"
        if (pair == warmed) return
        warmed = pair
        dualChecks = 0
        checkDual()
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
            if (!flow.carry) syncFeed()
            // 양방향 준비가 끝났으면 기기 안 인식기로 두 언어를 함께 들어요 (번갈아 듣기 없음)
            val twoWay = autoDetect && dualState == "ready"
            // 양방향: 인식기는 계속 듣고 있어요. 화면의 다음 듣기에 그대로 이어 붙여요 (다시 시작하면 첫 단어를 놓쳐요).
            if (twoWay && flow.carry) { adopt(id); return@withMicPermission }
            stopFlow()
            speechId = id
            finishing = false
            // 실시간 대화: 지금 차례 언어 + 상대 언어를 함께 알려 줘서(Android 14+) 누가 말해도 알아듣게
            val also = if (autoDetect) otherTag(lang) else null
            note("인식 시작 $lang" + (also?.let { " (+$it)" } ?: "") + if (twoWay) " · 양방향 연속" else "")
            if (twoWay) { startFlow(id, lang, also); return@withMicPermission }
            val begin = {
                listener.start(lang, SpeechListener.Callbacks(
                    onReady = { if (speechId == id) { busyStreak = 0; readyAt = now(); missed.reset(readyAt); emitSpeech(id, "start", "") } },
                    onLevel = { db -> if (speechId == id) checkMissed(db) },
                    onPartial = { if (speechId == id) { missed.text(now()); if (BuildConfig.DEBUG) android.util.Log.d("FTalkTest", "부분 $it"); emitSpeech(id, "partial", it) } },
                    onLanguage = { tag -> if (speechId == id) activity.callJs("ftolkDetectedLanguage", id, tag.substringBefore('-').lowercase()) },
                    onFinal = { text ->
                        if (speechId == id) {
                            note("최종 [$lang] $text")
                            // 글자가 없어도 Google 음성 인식이면 그 소리를 한 번 더 받아써 봐요 (말소리가 있었을 때만)
                            if (text.isNotBlank() || cloudOn()) finalAndEnd(id, text, lang.substringBefore('-').lowercase(), false, readyAt - 300, now())
                            else { emitSpeech(id, "error", "no-speech"); end(id) }
                        }
                    },
                    onError = { code ->
                        if (speechId == id) {
                            note("인식 오류 $code" + if (twoWay) " (양방향)" else "")
                            // "못 알아들음": Google 음성 인식이면 그 소리를 한 번 더 받아써 봐요
                            if (!twoWay && SpeechListener.isSilence(code) && cloudOn()) finalAndEnd(id, "", lang.substringBefore('-').lowercase(), false, readyAt - 300, now())
                            else {
                            if (twoWay && (code == 12 || code == 13 || code == SpeechRecognizer.ERROR_CLIENT)) {
                                // 기기 안 인식기가 이 언어를 못 하면 양방향을 끄고 번갈아 듣기로 돌아가요.
                                dualState = "failed"; dualDetail = "기기 안 음성 인식 오류 $code → 번갈아 듣기로 전환"
                                activity.callJs("ftolkDualState", dualState, dualDetail)
                            }
                            // "사용 중"·연결 끊김 같은 인식기 오류는 인식기를 새로 만들고 조용히 다시 듣게 해요.
                            val recoverable = code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == 11 ||
                                (code == SpeechRecognizer.ERROR_CLIENT && !finishing)
                            if (recoverable) { busyStreak++; listener.reset() }
                            emitSpeech(id, "error", if (recoverable && busyStreak < 4) "no-speech" else webError(code))
                            end(id)
                            }
                        }
                    },
                ), also, fastEndpoint, twoWay)
            }
            // 방금 인식기를 새로 만들었으면 잠깐 쉬었다가 시작해야 "사용 중" 오류가 안 나요.
            if (busyStreak > 0) main.postDelayed({ if (speechId == id) begin() }, 300L * busyStreak) else begin()
        }
    }

    /** 실시간 대화일 때 true (화면이 알려 줘요) */
    @Volatile private var autoDetect = false
    private var busyStreak = 0
    @JavascriptInterface fun setAutoDetect(on: Boolean) { autoDetect = on }

    /** 실시간 대화·듣기일 때 true: 짧은 쉼에서도 문장을 끊어서 빨리 번역 (화면이 알려 줘요) */
    @Volatile private var fastEndpoint = false
    @JavascriptInterface fun setFastEndpoint(on: Boolean) { fastEndpoint = on }

    private fun bcp(code: String) = when (code) { "ko" -> "ko-KR"; "vi" -> "vi-VN"; "en" -> "en-US"; "zh" -> "zh-CN"; else -> code }
    private fun otherTag(lang: String): String? {
        val code = lang.substringBefore('-').lowercase()
        val other = if (code == me) partner else if (code == partner) me else null
        return other?.let { bcp(it) }
    }

    /** 말 끝내기: 지금까지 들은 걸로 결과를 받아요. */
    @JavascriptInterface
    fun finishRecognition(id: Int) = ui {
        if (speechId != id) return@ui
        if (flow.active) {
            // 양방향 연속: 지금까지 말한 걸 바로 확정하고 멈춰요
            // 이미 확정해 둔 문장(줄 · 다시 받아쓰는 중)이 있으면 그것을, 없으면 지금까지 말한 것을
            val queued = if (flow.carry) null else flow.pending.removeFirstOrNull() ?: refining.firstOrNull()?.s
            val s = if (flow.carry || queued != null) null else flow.seg.onSilence()
            val lang0 = s?.lang ?: flow.lang
            val (f, t) = spans.take(now())
            stopFlow()
            when {
                queued != null -> { sendLang(id, queued.lang); emitSpeech(id, "final", queued.text); emitSpeech(id, "end", ""); speechId = 0 }
                s != null -> finalAndEnd(id, s.text, lang0, true, f, t)
                else -> { emitSpeech(id, "error", "no-speech"); emitSpeech(id, "end", ""); speechId = 0 }
            }
            return@ui
        }
        finishing = true; listener.finish()
    }

    /** 취소: 결과 없이 멈춰요. (화면은 이미 이 id 를 잊었어요) */
    @JavascriptInterface
    fun stopRecognition(id: Int) = ui {
        if (speechId == id) { speechId = 0; stopFlow(); listener.stop() }
        else if (flow.carry) {
            // 끝난 듣기를 정리하는 경우가 많아요. 곧바로 다음 듣기가 오지 않을 때만(진짜 멈춤) 멈춰요.
            // 그사이 화면이 다음 듣기를 이어받았으면(carryGen 이 바뀜) 늦게 온 취소라서 무시해요.
            val gen = flow.carryGen
            main.postDelayed({ if (flow.carry && flow.carryGen == gen) stopFlow() }, 200)
        }
    }

    private fun end(id: Int) {
        emitSpeech(id, "end", "")
        if (speechId == id) speechId = 0
        listener.stop()
    }

    // ================= 양방향 연속 듣기 =================
    // 기기 안 인식기(두 언어 자동 전환)는 한 사람이 말을 마쳐도 "확정"을 보내지 않고,
    // 다음 사람 말을 새 글자로 이어서 보내요. 그래서 앱이 직접 문장 경계를 찾아서 확정해요 (SentenceSegmenter).
    // 인식기는 멈추지 않고 계속 들어요. 확정 뒤 화면이 다음 듣기를 시작하면 그 듣기에 이어 붙여요.
    // 화면이 다음 듣기를 시작하기 전에 확정된 문장은 줄(pending)에 세워 두었다가 차례로 넘겨요.
    private class Flow {
        var active = false     // 연속 인식기가 돌고 있음
        var carry = false      // 화면 쪽 듣기는 끝났고, 다음 듣기를 기다리는 중
        var carryGen = 0       // 화면이 다음 듣기를 이어받을 때마다 늘어나요 (늦게 온 취소 구분용)
        var lang: String? = null      // 인식기가 마지막으로 알려 준 언어
        val seg = SentenceSegmenter()
        val pending = kotlin.collections.ArrayDeque<SentenceSegmenter.Sentence>()
    }
    private val flow = Flow()
    private val silenceCommit: Runnable = object : Runnable {
        override fun run() {
            // 인식기가 밀려서 글자가 안 오는 중이면 조금 더 기다려요
            val more = flow.seg.silenceWait(android.os.SystemClock.elapsedRealtime())
            if (more > 0) { main.postDelayed(this, more); return }
            flow.seg.onSilence()?.let { commitFlow(it, now()) }
        }
    }
    private val carryTimeout = Runnable { if (flow.carry) { note("양방향: 화면이 다시 듣지 않아 멈춤"); stopFlow() } }

    private fun startFlow(id: Int, lang: String, also: String?) {
        flow.active = true; flow.carry = false; missed.reset(now()); spans.reset(now())
        flow.seg.reset(); flow.pending.clear(); flow.lang = null
        listener.start(lang, SpeechListener.Callbacks(
            onReady = { busyStreak = 0; if (speechId != 0 && !flow.carry) emitSpeech(speechId, "start", "") },
            onLevel = { db -> if (flow.active && !flow.carry) checkMissed(db) },
            onPartial = { flowPartial(it) },
            onLanguage = { tag -> flow.lang = tag.substringBefore('-').lowercase() },
            onFinal = { text ->
                // 인식기가 스스로 확정해 준 경우 (말을 오래 멈췄을 때 등)
                main.removeCallbacks(silenceCommit)
                val s = flow.seg.onFinal(text)
                note("최종(인식기) ${s?.text ?: ""}")
                val id2 = speechId
                flow.active = false; flow.carry = false; flow.pending.clear()
                if (id2 != 0) {
                    if (s != null) { val (f, t) = spans.take(now()); finalAndEnd(id2, s.text, s.lang ?: flow.lang, true, f, t) }
                    else { emitSpeech(id2, "error", "no-speech"); end(id2) }
                } else listener.stop()
            },
            onError = { code ->
                main.removeCallbacks(silenceCommit)
                note("인식 오류 $code (양방향 연속)")
                if (code == 12 || code == 13 || code == SpeechRecognizer.ERROR_CLIENT) {
                    dualState = "failed"; dualDetail = "기기 안 음성 인식 오류 $code → 번갈아 듣기로 전환"
                    activity.callJs("ftolkDualState", dualState, dualDetail)
                }
                val recoverable = code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == 11
                if (recoverable) { busyStreak++; listener.reset() }
                val id2 = speechId
                flow.active = false; flow.carry = false; flow.pending.clear()
                val s = flow.seg.onSilence()
                if (id2 != 0) {
                    // 말하던 게 있으면 그것까지는 확정해 줘요
                    if (s != null) { val (f, t) = spans.take(now()); finalAndEnd(id2, s.text, s.lang ?: flow.lang, true, f, t) }
                    else { emitSpeech(id2, "error", if (SpeechListener.isSilence(code) || recoverable) "no-speech" else webError(code)); end(id2) }
                } else listener.stop()
            },
        ), also, fastEndpoint, useOnDevice = true)
    }

    private fun flowPartial(p: String) {
        if (!flow.active) return
        if (BuildConfig.DEBUG) android.util.Log.d("FTalkTest", "부분 $p")
        val at = now()
        val r = flow.seg.onPartial(p, at, flow.lang)
        if (r.commits.isNotEmpty()) { val cut = quietCut(at); r.commits.forEach { commitFlow(it, cut) } }
        if (r.changed) { spans.partial(at); rescue.cancel() }
        if (!r.changed) return
        missed.text(now())
        val shown = flow.seg.shown
        if (shown.isNotBlank() && speechId != 0 && !flow.carry && flow.pending.isEmpty()) emitSpeech(speechId, "partial", shown)
        // 글자가 그대로 멈춰 있으면 확정 (문장 끝말이면 짧게 기다려요)
        scheduleSilence()
    }

    private fun scheduleSilence() {
        main.removeCallbacks(silenceCommit)
        if (flow.seg.shown.isNotBlank()) main.postDelayed(silenceCommit, flow.seg.silenceDelay())
    }

    /** 확정한 문장을 화면에 넘겨요. 화면이 아직 다음 듣기를 시작하지 않았으면 줄에 세워 둬요. */
    private fun deliver(s: SentenceSegmenter.Sentence) {
        val id = speechId
        if (id == 0 || flow.carry || flow.pending.isNotEmpty()) {
            flow.pending.addLast(s)
            return
        }
        note("확정 [${s.lang ?: flow.lang ?: "?"}] ${s.text}")
        sendLang(id, s.lang)
        emitSpeech(id, "final", s.text)
        emitSpeech(id, "end", "")
        speechId = 0
        flow.carry = true
        main.removeCallbacks(carryTimeout); main.postDelayed(carryTimeout, 4000)
    }

    private fun sendLang(id: Int, lang: String?) {
        val l = lang ?: flow.lang ?: return
        activity.callJs("ftolkDetectedLanguage", id, l)
    }

    /** 화면이 다음 듣기를 시작함 → 계속 듣고 있던 인식기에 이어 붙여요. */
    private fun adopt(id: Int) {
        main.removeCallbacks(carryTimeout); missed.reset(now())
        speechId = id; finishing = false
        flow.carry = false
        flow.carryGen++
        emitSpeech(id, "start", "")
        val next = flow.pending.removeFirstOrNull()
        if (next != null) {
            // 이미 확정해 둔 문장이 있어요 → 화면이 시작을 받은 뒤 바로 넘겨요
            main.post { if (speechId == id && !flow.carry) deliver(next) }
            return
        }
        if (flow.seg.shown.isNotBlank()) {
            emitSpeech(id, "partial", flow.seg.shown)
            scheduleSilence()
        }
    }

    /** 연속 듣기를 멈춰요 (멈춤 버튼, 모드 바꾸기, 번역 읽기 시작 등) */
    private fun stopFlow() {
        main.removeCallbacks(silenceCommit); main.removeCallbacks(carryTimeout)
        flow.pending.clear(); refining.clear(); rescue.cancel()
        if (flow.active) { flow.active = false; flow.carry = false; listener.stop() }
    }

    // ---- Google Cloud 음성 인식: 기기 인식기가 놓친 소리만 받아써요 ----
    // 2026-11 폰 시험(폴드8, 베트남어 v1~v5): 문장마다 Google 로 바꾸면 오히려 나빴어요(8.1% → 12.3%, 쉼에서 잘라도 비슷).
    // 기기 글자는 소리보다 늦게 와서 문장 구간이 어긋나고, 잘 맞춰 잘라도 Google(latest_long)이 기기보다 낫지 않았어요.
    // 그래서 기기 글자가 있으면 그대로 쓰고, 글자가 하나도 없는데 말소리가 있었던 구간만 Google 로 받아써요.
    private val spans = SentenceSpans()
    private var readyAt = 0L
    private class Refine(var s: SentenceSegmenter.Sentence, var ready: Boolean = false)
    private val refining = kotlin.collections.ArrayDeque<Refine>()

    private fun cloudOn() = engine.usingGoogle && cloudOff == null && android.os.Build.VERSION.SDK_INT >= 33
    /** 듣기 시작 전에: Google 음성 인식을 쓰면 앱이 직접 마이크를 받아요 (소리를 남겨 두려고) */
    private fun syncFeed() { listener.feed = if (cloudOn()) liveMic else null }

    /** 문장 사이를 자를 곳: 소리가 있으면 쉼(가장 조용한 곳), 없으면 글자 시각으로 */
    private fun quietCut(at: Long): Long {
        val r = spans.searchRange(at) ?: return spans.splitPoint(at)
        val pcm = if (cloudOn()) listener.audio?.clip(r.first, r.second) else null
        val ms = pcm?.let { Pcm.quietestMs(it) } ?: return spans.splitPoint(at)
        return r.first + ms
    }

    /** 양방향: 확정된 문장을 (다시 받아써서) 순서대로 넘겨요 */
    private fun commitFlow(s: SentenceSegmenter.Sentence, to: Long) {
        val (f, t) = spans.take(to)
        val job = Refine(s); refining.addLast(job)
        refine(s.text, s.lang ?: flow.lang, f, t) { text ->
            job.s = s.copy(text = text); job.ready = true
            flushRefining()
        }
    }

    private fun flushRefining() {
        while (refining.firstOrNull()?.ready == true) {
            val j = refining.removeFirst()
            if (!flow.active) continue
            if (j.s.text.isNotBlank()) deliver(j.s) else missedNotice()
        }
    }

    /** 양방향: 인식기가 놓친 구간을 Google 로 받아써서 문장으로 넘겨요 (못 알아들으면 "다시 말해 주세요") */
    private fun rescueGap() {
        val (f, t) = spans.take(now())
        val job = Refine(SentenceSegmenter.Sentence("", flow.lang)); refining.addLast(job)
        note("빠진 구간 Google 받아쓰기 ${t - f}ms")
        refine("", flow.lang, f, t) { text -> job.s = job.s.copy(text = text); job.ready = true; flushRefining() }
    }

    /** 한 번 듣기의 마지막 문장: (다시 받아써서) 넘기고 듣기를 끝내요 */
    private fun finalAndEnd(id: Int, text: String, code: String?, announce: Boolean, from: Long, to: Long) {
        if (speechId == id) speechId = 0
        listener.stop()
        refine(text, code, from, to) { t ->
            if (t.isBlank()) { emitSpeech(id, "error", "no-speech"); emitSpeech(id, "end", ""); return@refine }
            if (announce && code != null) activity.callJs("ftolkDetectedLanguage", id, code)
            emitSpeech(id, "final", t); emitSpeech(id, "end", "")
        }
    }

    private fun refine(text: String, code: String?, from: Long, to: Long, done: (String) -> Unit) {
        if (text.isNotBlank()) { done(text); return }
        val pcm = if (cloudOn()) listener.audio?.clip(from, to) else null
        // 말소리가 실제로 있었을 때만 보내요 (조용한 소리에 요금이 들지 않게)
        if (pcm == null || pcm.size < 6400 || !Pcm.loud(pcm)) { done(text); return }
        val audio = if (pcm.size > 55 * 16000) pcm.copyOfRange(pcm.size - 55 * 16000, pcm.size) else pcm
        val tag = code?.let { bcp(it) }
        var finished = false
        val finish = { t: String -> if (!finished) { finished = true; done(t) } }
        main.postDelayed({ if (!finished) { note("Google 음성 인식 늦음 → 기기 글자"); finish(text) } }, 4000)
        activity.lifecycleScope.launch {
            try {
                val t = cloud.recognize(audio, tag ?: bcp(me), if (tag == null) bcp(partner) else null)
                note("Google 음성 인식 [${code ?: "?"}] $t  (기기: $text)")
                finish(t.ifBlank { text })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) { finish(text); throw e }
                note("Google 음성 인식 실패: ${e.message}")
                if (e is CloudSpeech.Failure && e.code in setOf(0, 400, 401, 403)) {
                    cloudOff = e.message
                    activity.callJs("notice", "Google 음성 인식이 안 돼서 기기 인식으로 들어요. 설정 → 연결 테스트에서 이유를 볼 수 있어요.")
                }
                finish(text)
            }
        }
    }

    // ---- 빠진 구간 알림: 말소리는 들리는데 인식기가 글자를 안 낼 때 ----
    private val missed = MissedSpeech()
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private val rescue = MissedRescue()
    private fun checkMissed(db: Float) {
        val t = now()
        if (rescue.level(db, t) && flow.active) rescueGap()
        if (!missed.level(db, t)) return
        note("빠진 구간 알림")
        // Google 음성 인식이면 알림 대신, 소리가 잦아들 때 그 구간을 받아써요
        if (cloudOn() && flow.active && flow.seg.shown.isBlank()) { rescue.arm(t); return }
        missedNotice()
    }
    private fun missedNotice() = activity.callJs("notice", "잘 안 들렸어요. 다시 말해 주세요 · Tôi nghe không rõ, xin nói lại nhé")

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

    /**
     * 확정 번역. Google 키가 있으면 기기 번역(0.1초)과 Google 번역을 동시에 시작해서,
     * 기기 번역을 먼저 보여 주고 Google 번역이 오면 그 자리에서 바꿔요 (대화가 끊기지 않게).
     */
    @JavascriptInterface
    fun translate(id: Int, text: String, from: String, to: String) = ui {
        note("번역 요청 $from→$to: $text")
        activity.lifecycleScope.launch {
            if (engine.usingGoogle) {
                val quick = async { engine.offline.translate(text, from, to) }
                val best = async { engine.translate(text, from, to) } // 실패하면 안에서 기기 번역으로
                val q = quick.await()
                if (!best.isCompleted && q is TranslationResult.Success) {
                    note("기기 번역 먼저 $to: ${q.text}")
                    activity.callJs("friendlyNativeTranslation", id, true, q.text)
                    val b = best.await()
                    if (b is TranslationResult.Success) note("번역 결과 $to: ${b.text}") else note("번역 실패 $from→$to: ${(b as TranslationResult.Failure).message}")
                    note("번역 엔진 ${engine.lastEngine}"); reportGoogleFallback()
                    if (b is TranslationResult.Success && b.text != q.text) activity.callJs("friendlyNativeTranslationUpdate", id, b.text)
                    return@launch
                }
                when (val b = best.await()) {
                    is TranslationResult.Success -> { note("번역 결과 $to: ${b.text}"); note("번역 엔진 ${engine.lastEngine}"); reportGoogleFallback(); activity.callJs("friendlyNativeTranslation", id, true, b.text) }
                    is TranslationResult.Failure -> { note("번역 실패 $from→$to: ${b.message}"); activity.callJs("friendlyNativeTranslation", id, false, b.message) }
                }
                return@launch
            }
            when (val r = engine.translate(text, from, to)) {
                is TranslationResult.Success -> { note("번역 결과 $to: ${r.text}"); activity.callJs("friendlyNativeTranslation", id, true, r.text) }
                is TranslationResult.Failure -> {
                    note("번역 실패 $from→$to: ${r.message}")
                    activity.callJs("friendlyNativeTranslation", id, false, r.message)
                }
            }
        }
    }

    /**
     * 말하는 도중의 중간 번역 (듣기 · 실시간 대화). 항상 기기 번역(ML Kit)이라 무료이고 빨라요.
     * 문장이 끝나면 위의 translate() 가 Google(키가 있으면)로 확정 번역해요.
     */
    @JavascriptInterface
    fun translateQuick(id: Int, text: String, from: String, to: String) = ui {
        activity.lifecycleScope.launch {
            when (val r = engine.offline.translate(text, from, to)) {
                is TranslationResult.Success -> activity.callJs("friendlyNativeTranslation", id, true, r.text)
                is TranslationResult.Failure -> activity.callJs("friendlyNativeTranslation", id, false, r.message)
            }
        }
    }

    // ================= 읽기 (TTS) =================

    /** 듣기 모드에서는 통화 스피커(귀)로, 다른 모드는 일반 스피커로 읽어요. */
    @JavascriptInterface fun setEarpiece(on: Boolean) { earpiece = on }

    @JavascriptInterface
    fun speak(text: String, lang: String, rate: Double) = ui {
        // 번역을 소리 내 읽는 동안에는 연속 듣기를 멈춰요 (읽는 소리를 다시 받아쓰지 않게)
        if (flow.carry) stopFlow()
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
                appendLine("실시간 대화 양방향: " + when (dualState) {
                    "ready" -> "사용 중 (누가 말해도 바로 인식)"
                    "downloading" -> "준비 중"
                    "checking" -> "확인 중"
                    else -> "사용 안 함 (번갈아 듣기)"
                } + if (dualDetail.isNotEmpty()) " · $dualDetail" else "")
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
        listener.reset()
        speaker.shutdown()
        photos.close()
        engine.close()
    }
}
