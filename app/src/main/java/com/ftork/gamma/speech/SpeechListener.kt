package com.ftork.gamma.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 앱 안에서 바로 음성을 받아쓰는 도우미 (원본: DictationInputFragment, openmic).
 * 기기의 음성 인식 서비스(보통 Google 앱)를 써요. 한 번 말이 끝나면 결과를 주고 멈춰요.
 * 계속 듣고 싶으면 onFinal / onError 에서 다시 start() 를 부르면 돼요.
 * 반드시 메인 스레드에서 불러야 해요.
 */
class SpeechListener(private val context: Context) {

    class Callbacks(
        val onReady: () -> Unit = {},
        val onLevel: (Float) -> Unit = {},
        val onPartial: (String) -> Unit = {},
        val onFinal: (String) -> Unit = {},
        val onError: (code: Int) -> Unit = {},
        /** (Android 14+) 두 언어 중 어떤 언어로 들렸는지 — 예: "vi-VN" */
        val onLanguage: (String) -> Unit = {},
    )

    private var recognizer: SpeechRecognizer? = null
    /** 지금 듣기 순번. 이전 순번의 늦은 결과는 무시해요. */
    private var session = 0

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Google 번역 앱과 같은 음성 인식기(Google 앱의 GoogleRecognitionService).
     * 삼성폰처럼 기본 음성 인식이 다른 회사 것이어도 Google 것을 직접 지정해요. 없으면 null → 기기 기본값.
     */
    val googleService: ComponentName? by lazy {
        runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
                .firstOrNull { it.packageName == GOOGLE_APP }
        }.getOrNull()
    }

    /** 진단 화면용: 지금 쓰는 음성 인식기 이름 */
    val serviceLabel: String get() = if (googleService != null) "Google 음성 인식 (Google 앱)" else "기기 기본 음성 인식"

    private fun create(): SpeechRecognizer = googleService
        ?.let { runCatching { SpeechRecognizer.createSpeechRecognizer(context, it) }.getOrNull() }
        ?: SpeechRecognizer.createSpeechRecognizer(context)

    /**
     * 듣기 시작. 인식기는 한 번 만들어 계속 다시 써요.
     * (매번 없애고 새로 만들면 Google 음성 인식이 "사용 중" 오류를 내며 못 듣는 경우가 많아요 — 실시간 대화 문제의 원인)
     * @param alsoLanguage 실시간 대화용: 이 언어로 말해도 알아듣게 함께 지정 (Android 14+ 에서만 동작)
     */
    fun start(languageTag: String, callbacks: Callbacks, alsoLanguage: String? = null) {
        val r = recognizer ?: create().also { recognizer = it }
        val my = ++session
        runCatching { r.cancel() }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (my == session) callbacks.onReady() }
            override fun onRmsChanged(rmsdB: Float) { if (my == session) callbacks.onLevel(rmsdB) }
            override fun onPartialResults(partialResults: Bundle?) {
                if (my == session) first(partialResults)?.let(callbacks.onPartial)
            }
            override fun onResults(results: Bundle?) { if (my == session) callbacks.onFinal(first(results).orEmpty()) }
            override fun onError(error: Int) { if (my == session) callbacks.onError(error) }
            override fun onLanguageDetection(results: Bundle) {
                if (my != session) return
                results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let(callbacks.onLanguage)
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (alsoLanguage != null && Build.VERSION.SDK_INT >= 34) {
            // 두 언어 중 들리는 언어를 스스로 알아내고, 그 언어로 바꿔서 받아써요 (지원하는 기기만, 아니면 무시돼요).
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf(languageTag, alsoLanguage))
                .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf(languageTag, alsoLanguage))
        }
        r.startListening(intent)
    }

    /** 듣기를 멈추고 지금까지 말한 내용으로 결과를 받아요. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** 듣기 취소 (인식기는 남겨 두고 다음에 다시 써요) */
    fun stop() {
        session++
        runCatching { recognizer?.cancel() }
    }

    /** 인식기를 완전히 없애요. 오류가 이어질 때 새로 만들거나, 앱을 닫을 때. */
    fun reset() {
        session++
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

        /** 조용했거나 못 알아들은 경우 — 다시 듣기만 하면 되는 오류 */
        fun isSilence(code: Int) =
            code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        fun message(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "음성 인식에 인터넷 연결이 필요해요."
            SpeechRecognizer.ERROR_AUDIO -> "마이크를 쓸 수 없어요."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 바빠요. 잠시 후 다시 해 주세요."
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리를 듣지 못했어요."
            12, 13 -> "이 언어는 기기의 음성 인식이 지원하지 않아요." // LANGUAGE_NOT_SUPPORTED / UNAVAILABLE
            else -> "음성 인식 오류 ($code)"
        }
    }
}
