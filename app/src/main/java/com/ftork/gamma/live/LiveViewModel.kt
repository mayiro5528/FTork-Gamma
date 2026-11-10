package com.ftork.gamma.live

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ftork.gamma.data.Languages
import com.ftork.gamma.data.MlKitTranslationEngine
import com.ftork.gamma.data.TranslationResult
import com.ftork.gamma.speech.Speaker
import com.ftork.gamma.speech.SpeechListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 실시간 번역 방식 (원본: openmic 패키지의 AudioModePicker / Standard / Battleship 모드) */
enum class LiveMode(val title: String, val description: String, val icon: ImageVector) {
    LISTEN("듣기", "휴대전화를 귀에 대면 상대방 말의 번역이 통화 스피커로 들려요.", Icons.Default.Hearing),
    CONVERSATION("대화", "번갈아 말하세요. 번역된 내용이 소리 내어 재생되고 차례가 자동으로 넘어가요.", Icons.Default.People),
    FACE_TO_FACE("실시간 대화", "화면을 반으로 나눠 마주 보고 대화해요. 위쪽 절반은 상대방 쪽으로 뒤집혀 보여요.", Icons.Default.ScreenRotation),
    TEXT_ONLY("텍스트만", "상대방 말의 번역을 화면으로만 읽어요. 소리는 나지 않아요.", Icons.AutoMirrored.Filled.Notes),
}

/** 대화 한 줄 */
data class Turn(
    val id: Long,
    val fromPartner: Boolean,
    val original: String,
    val translated: String,
)

data class LiveState(
    val partner: String = "vi",
    val mine: String = "ko",
    val mode: LiveMode = LiveMode.LISTEN,
    val running: Boolean = false,
    /** 지금 말할 차례가 상대방인지 */
    val partnerTurn: Boolean = true,
    /** 듣는 중 / 번역 중 / 읽는 중 */
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val turns: List<Turn> = emptyList(),
    val level: Float = 0f,
    val status: String? = null,
    val error: String? = null,
) {
    enum class Phase { IDLE, PREPARING, LISTENING, TRANSLATING, SPEAKING }
}

/**
 * 실시간 번역 흐름:  듣기(음성 인식) → 번역 → (모드에 따라) 소리로 읽기 → 다시 듣기
 * 모든 콜백은 메인 스레드에서 돌아요.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = MlKitTranslationEngine()
    private val speaker = Speaker(app)
    private val listener = SpeechListener(app)
    private var restartJob: Job? = null
    private var errorStreak = 0

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    // ---------- 설정 ----------

    fun setPartner(code: String) = _state.update { it.copy(partner = code) }
    fun setMine(code: String) = _state.update { it.copy(mine = code) }
    fun setMode(mode: LiveMode) = _state.update { it.copy(mode = mode) }
    fun swapLanguages() = _state.update { it.copy(partner = it.mine, mine = it.partner) }

    // ---------- 시작 / 멈춤 ----------

    fun start() {
        if (!listener.isAvailable) {
            _state.update { it.copy(error = "이 기기에서는 음성 인식을 쓸 수 없어요. Google 앱을 설치하거나 업데이트해 주세요.") }
            return
        }
        errorStreak = 0
        _state.update {
            it.copy(running = true, partnerTurn = true, turns = emptyList(), partial = "",
                error = null, status = null, phase = LiveState.Phase.PREPARING)
        }
        viewModelScope.launch {
            // 언어 팩이 없으면 먼저 받아요. (처음 한 번만, 인터넷 필요)
            val s = _state.value
            val missing = setOf(s.partner, s.mine) - engine.downloadedLanguages() - "en"
            for (code in missing) {
                _state.update { it.copy(status = "${Languages.nameOf(code)} 언어 팩 받는 중…") }
                engine.download(code).onFailure {
                    _state.update { st -> st.copy(running = false, phase = LiveState.Phase.IDLE, status = null,
                        error = "${Languages.nameOf(code)} 언어 팩을 받지 못했어요. 인터넷 연결을 확인해 주세요.") }
                    return@launch
                }
            }
            _state.update { it.copy(status = null) }
            speaker.useEarpiece(_state.value.mode == LiveMode.LISTEN)
            listen()
        }
    }

    fun stop() {
        restartJob?.cancel()
        listener.stop()
        speaker.stop()
        speaker.useEarpiece(false)
        _state.update { it.copy(running = false, phase = LiveState.Phase.IDLE, partial = "", level = 0f) }
    }

    /** 대화 / 실시간 대화에서 말할 사람을 직접 고르기 */
    fun selectSpeaker(partner: Boolean) {
        if (!_state.value.running) return
        restartJob?.cancel()
        speaker.stop()
        _state.update { it.copy(partnerTurn = partner, partial = "") }
        listen()
    }

    /** 지금까지 말한 걸로 끝내고 번역하기 */
    fun finishUtterance() = listener.finish()

    // ---------- 내부 흐름 ----------

    private fun listen() {
        val s = _state.value
        if (!s.running) return
        val lang = if (s.partnerTurn) s.partner else s.mine
        _state.update { it.copy(phase = LiveState.Phase.LISTENING, partial = "") }
        listener.start(Languages.speechTag(lang), SpeechListener.Callbacks(
            onReady = { errorStreak = 0 },
            onLevel = { v -> _state.update { it.copy(level = v) } },
            onPartial = { t -> _state.update { it.copy(partial = t) } },
            onFinal = ::onHeard,
            onError = ::onListenError,
        ))
    }

    private fun onHeard(text: String) {
        val s = _state.value
        if (!s.running) return
        _state.update { it.copy(level = 0f) }
        if (text.isBlank()) { relisten(); return }

        val fromPartner = s.partnerTurn
        val from = if (fromPartner) s.partner else s.mine
        val to = if (fromPartner) s.mine else s.partner
        _state.update { it.copy(phase = LiveState.Phase.TRANSLATING, partial = text) }

        viewModelScope.launch {
            val translated = when (val r = engine.translate(text, from, to)) {
                is TranslationResult.Success -> r.text
                is TranslationResult.Failure -> {
                    _state.update { it.copy(error = r.message) }
                    relisten(); return@launch
                }
            }
            if (!_state.value.running) return@launch
            _state.update {
                it.copy(partial = "", error = null,
                    turns = it.turns + Turn(System.nanoTime(), fromPartner, text, translated))
            }
            afterTranslated(translated, to)
        }
    }

    private fun afterTranslated(translated: String, to: String) {
        val mode = _state.value.mode
        when (mode) {
            LiveMode.TEXT_ONLY -> relisten()
            LiveMode.LISTEN -> speakThen(translated, to, earpiece = true) { relisten() }
            LiveMode.CONVERSATION, LiveMode.FACE_TO_FACE -> speakThen(translated, to, earpiece = false) {
                // 번역을 다 읽으면 상대방 차례로 넘겨요.
                _state.update { it.copy(partnerTurn = !it.partnerTurn) }
                relisten()
            }
        }
    }

    private fun speakThen(text: String, lang: String, earpiece: Boolean, next: () -> Unit) {
        _state.update { it.copy(phase = LiveState.Phase.SPEAKING) }
        val problem = speaker.speak(text, Languages.speechTag(lang), earpiece) { next() }
        if (problem != null) {
            _state.update { it.copy(status = problem) }
            next()
        }
    }

    private fun relisten(delayMs: Long = 150) {
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            delay(delayMs)
            listen()
        }
    }

    private fun onListenError(code: Int) {
        if (!_state.value.running) return
        _state.update { it.copy(level = 0f) }
        if (SpeechListener.isSilence(code)) {
            relisten()
            return
        }
        errorStreak++
        if (errorStreak >= 4) {
            stop()
            _state.update { it.copy(error = SpeechListener.message(code)) }
        } else {
            _state.update { it.copy(status = SpeechListener.message(code)) }
            relisten(800L * errorStreak)
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        stop()
        speaker.shutdown()
        engine.close()
    }
}
