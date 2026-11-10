package com.ftork.gamma.speech

import android.content.Context
import android.content.Intent
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
    )

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, callbacks: Callbacks) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()
            override fun onRmsChanged(rmsdB: Float) = callbacks.onLevel(rmsdB)
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(callbacks.onPartial)
            }
            override fun onResults(results: Bundle?) = callbacks.onFinal(first(results).orEmpty())
            override fun onError(error: Int) = callbacks.onError(error)
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
        recognizer = r
        r.startListening(intent)
    }

    /** 듣기를 멈추고 지금까지 말한 내용으로 결과를 받아요. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
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
