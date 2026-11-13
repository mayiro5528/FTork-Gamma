package com.ftork.gamma.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 시험용 (디버그 빌드에서만): 음성 파일 하나를 인식 방식별로 받아써서 로그로 남겨요. 화면·번역은 거치지 않아요.
 *  am start --es test_bench "v1.mp3" --es bench_mode dual|vi|online|vi_bias
 *  - dual   : 앱의 실시간 대화와 같음 (기기 안 인식기, 한국어+베트남어 자동 전환)
 *  - vi     : 기기 안 인식기, 베트남어만
 *  - online : Google 서버 인식기, 베트남어만
 *  - vi_bias: 기기 안 인식기, 베트남어만 + 자주 나올 단어 힌트
 *  - <방식>+cloud: 앱의 Google 음성 인식과 같음 — 확정된 문장 구간마다 Google Cloud 로 다시 받아써요 (예: dual+cloud)
 *  - cloudfull: 인식기 없이 파일 전체를 Google Cloud 로 50초씩 받아써요 (Google 인식의 최대 정확도 참고)
 *  (+cloud, cloudfull 은 앱에 저장된 Google 키를 써요)
 * 확정 문장은 앱과 같은 SentenceSegmenter 로 나눠요.
 */
class RecognitionBench(
    private val context: Context,
    private val mic: VirtualMic,
    mode: String,
    private val googleService: ComponentName?,
    private val cloud: CloudSpeech?,
    private val log: (String) -> Unit,
) {
    private val useCloud = "+cloud" in mode && cloud != null
    private val spans = SentenceSpans()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cloudJobs = mutableListOf<Pair<String, Deferred<String>>>()
    private val mode = mode.substringBefore("+")
    private val main = Handler(Looper.getMainLooper())
    private val seg = SentenceSegmenter()
    private var recognizer: SpeechRecognizer? = null
    private var lang: String? = null
    private var done = false
    private var sessions = 0
    private val missed = MissedSpeech()
    private var t0 = 0L
    private var rmsSum = 0f; private var rmsMax = -99f; private var rmsN = 0; private var rmsAt = 0L

    private val silence = object : Runnable {
        override fun run() {
            val more = seg.silenceWait(SystemClock.elapsedRealtime())
            if (more > 0) { main.postDelayed(this, more); return }
            seg.onSilence()?.let { commit(it) }
        }
    }

    fun start() {
        if (mode == "cloudfull") { runFull(); return }
        recognizer = if (mode == "online")
            (googleService?.let { SpeechRecognizer.createSpeechRecognizer(context, it) } ?: SpeechRecognizer.createSpeechRecognizer(context))
        else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer!!.setRecognitionListener(listener)
        log("벤치 시작 $mode ${mic.durationMs}ms")
        mic.play(0)
        t0 = SystemClock.elapsedRealtime(); missed.reset(t0); spans.reset(t0)
        listen()
        main.postDelayed(::finishIfDone, 1000)
    }

    private fun finishIfDone() {
        if (done) return
        if (mic.finished) {
            main.postDelayed({
                if (done) return@postDelayed
                done = true
                main.removeCallbacks(silence)
                seg.onSilence()?.let { commit(it) }
                runCatching { recognizer?.cancel(); recognizer?.destroy() }
                mic.close()
                if (!useCloud) log("벤치 끝 $mode 세션 $sessions")
                else scope.launch {
                    for ((l, job) in cloudJobs) log("벤치 확정 [$l] ${job.await()}")
                    log("벤치 끝 $mode 세션 $sessions")
                }
            }, 2500)
        } else main.postDelayed(::finishIfDone, 500)
    }

    private fun listen() {
        if (done) return
        sessions++
        val primary = if (mode == "dual") "ko-KR" else "vi-VN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        if (mode != "online") intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (mode == "dual" && Build.VERSION.SDK_INT >= 34) {
            val both = arrayListOf("ko-KR", "vi-VN")
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
        }
        if (mode == "vi_bias" && Build.VERSION.SDK_INT >= 33) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, BIAS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, mic.open())
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        }
        recognizer?.startListening(intent)
    }

    private fun commit(s: SentenceSegmenter.Sentence, to: Long = SystemClock.elapsedRealtime()) {
        val (from, end) = spans.take(to)
        val l = s.lang ?: lang
        if (!useCloud) { log("벤치 확정 [${l ?: "?"}] ${s.text}"); return }
        log("벤치 기기 [${l ?: "?"}] ${s.text}")
        val pcm = mic.clip(from, end)
        cloudJobs += (l ?: "?") to scope.async {
            if (pcm == null || pcm.size < 6400) s.text
            else runCatching { cloud!!.recognize(pcm, tag(l), if (l == null) "ko-KR" else null) }
                .getOrElse { log("벤치 클라우드 실패 ${it.message}"); "" }.ifBlank { s.text }
        }
    }

    private fun tag(l: String?) = when (l) { "ko" -> "ko-KR"; "en" -> "en-US"; else -> "vi-VN" }

    /** 파일 전체를 Google Cloud 로 (50초씩) */
    private fun runFull() {
        log("벤치 시작 cloudfull ${mic.durationMs}ms")
        scope.launch {
            val pcm = mic.pcmCopy(); val step = 50 * 16000
            var i = 0
            while (i < pcm.size && cloud != null) {
                val part = pcm.copyOfRange(i, minOf(pcm.size, i + step))
                val t = runCatching { cloud.recognize(part, "vi-VN") }.getOrElse { log("벤치 클라우드 실패 ${it.message}"); "" }
                log("벤치 확정 [vi] $t")
                i += step
            }
            if (cloud == null) log("벤치 클라우드 실패 키 없음")
            log("벤치 끝 cloudfull 세션 0")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (done) return
            val now = SystemClock.elapsedRealtime()
            // 0.5초마다 소리 크기 요약 (알림 기준 정하기용)
            rmsSum += rmsdB; rmsN++; if (rmsdB > rmsMax) rmsMax = rmsdB
            if (now - rmsAt >= 500) { log("벤치 소리 ${now - t0} 평균 ${"%.1f".format(rmsSum / rmsN)} 최대 ${"%.1f".format(rmsMax)}"); rmsAt = now; rmsSum = 0f; rmsN = 0; rmsMax = -99f }
            if (missed.level(rmsdB, now)) log("벤치 놓침 ${now - t0}")
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onLanguageDetection(results: Bundle) {
            results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { lang = it.substringBefore('-').lowercase() }
        }
        override fun onPartialResults(b: Bundle?) {
            if (done) return
            val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            log("벤치 부분 $p")
            val now = SystemClock.elapsedRealtime()
            val cut = spans.splitPoint(now)
            val r = seg.onPartial(p, now, lang ?: if (mode == "dual") null else "vi")
            r.commits.forEach { commit(it, cut) }
            if (r.changed) spans.partial(now)
            if (r.changed) missed.text(SystemClock.elapsedRealtime())
            if (r.changed) { main.removeCallbacks(silence); if (seg.shown.isNotBlank()) main.postDelayed(silence, seg.silenceDelay()) }
        }
        override fun onResults(b: Bundle?) {
            if (done) return
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            log("벤치 최종 $t")
            main.removeCallbacks(silence)
            seg.onFinal(t)?.let { commit(it) }
            seg.reset()
            main.post(::listen)
        }
        override fun onError(error: Int) {
            if (done) return
            log("벤치 오류 $error")
            main.removeCallbacks(silence)
            seg.onSilence()?.let { commit(it) }
            seg.reset()
            main.postDelayed(::listen, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 300 else 50)
        }
    }

    companion object {
        /** 일상 대화·여행에서 자주 틀리는 말 (힌트) */
        val BIAS = arrayListOf(
            "rẽ phải", "rẽ trái", "đi thẳng", "taxi", "ngon", "khách sạn", "nhà vệ sinh", "bao nhiêu tiền",
            "cảm ơn", "xin lỗi", "chúc anh", "chúc chị", "được không", "ở đâu", "phở", "cà phê", "chợ", "ga tàu",
        )
    }
}
