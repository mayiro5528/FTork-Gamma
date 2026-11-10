package com.ftork.gamma.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * 번역 결과를 소리로 읽어 주는 도우미 (원본: TtsPlaybackControlsView, libaudioplayerjni).
 * earpiece=true 면 통화할 때처럼 귀에 대는 스피커로 소리를 보내요 ("듣기" 모드).
 */
class Speaker(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, () -> Unit>()
    private var ready = false
    private var earpieceOn = false

    var rate: Float = 1.0f
        set(value) { field = value; tts.setSpeechRate(value) }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        main.post { id?.let { pending.remove(it)?.invoke() } }
    }

    /**
     * 읽기 시작에 성공하면 null, 실패하면 이유를 돌려줘요.
     * 다 읽으면 onDone 을 메인 스레드에서 불러요. (실패해도 onDone 은 부르지 않아요)
     */
    fun speak(text: String, language: String, earpiece: Boolean = false, onDone: () -> Unit = {}): String? {
        if (!ready) return "음성 엔진이 아직 준비되지 않았어요."
        if (text.isBlank()) return "읽을 글이 없어요."
        val res = tts.setLanguage(Locale.forLanguageTag(language))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "이 기기에 해당 언어 음성이 없어요. 설정 > 텍스트 음성 변환에서 음성 데이터를 받아 주세요."
        }
        useEarpiece(earpiece)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val id = UUID.randomUUID().toString()
        pending[id] = onDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        return null
    }

    fun stop() {
        pending.clear()
        tts.stop()
    }

    /** 통화용 스피커(귀)로 소리 보내기 켜기/끄기 */
    @Suppress("DEPRECATION")
    fun useEarpiece(on: Boolean) {
        if (on == earpieceOn) return
        earpieceOn = on
        runCatching {
            if (on) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audio.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        ?.let { audio.setCommunicationDevice(it) }
                } else {
                    audio.isSpeakerphoneOn = false
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
                audio.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    fun shutdown() {
        stop()
        useEarpiece(false)
        tts.shutdown()
    }
}
