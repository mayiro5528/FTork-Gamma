package com.ftork.gamma.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ftork.gamma.data.HistoryEntry
import com.ftork.gamma.data.HistoryStore
import com.ftork.gamma.data.Languages
import com.ftork.gamma.data.MlKitTranslationEngine
import com.ftork.gamma.data.TranslationEngine
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
import java.util.Locale

data class TranslateUiState(
    val source: String = Languages.AUTO,
    val target: String = "ko",
    val input: String = "",
    val output: String = "",
    /** 자동 감지로 알아낸 언어 */
    val detected: String? = null,
    val loading: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    /** 지금 결과가 들어 있는 기록 id (별표용) */
    val currentId: Long? = null,
    /** 마이크로 받아쓰는 중 */
    val listening: Boolean = false,
    val micLevel: Float = 0f,
)

/** 메인(텍스트) 번역 화면 + 기록 + 언어 팩을 맡는 ViewModel. 카메라 화면도 이 언어 설정을 같이 써요. */
class TranslateViewModel(app: Application) : AndroidViewModel(app) {

    /** ← 번역 엔진을 바꾸려면 여기만 바꾸면 돼요. */
    val engine: TranslationEngine = MlKitTranslationEngine()

    private val historyStore = HistoryStore(app)
    val history: StateFlow<List<HistoryEntry>> = historyStore.entries

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _busyLanguages = MutableStateFlow<Set<String>>(emptySet())
    val busyLanguages: StateFlow<Set<String>> = _busyLanguages.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var translateJob: Job? = null
    private val speaker = Speaker(app)
    private val listener = SpeechListener(app)

    init {
        viewModelScope.launch { historyStore.load() }
        refreshDownloaded()
    }

    // ---------- 입력 ----------

    fun onInput(text: String) {
        _state.update { it.copy(input = text) }
        scheduleTranslate(debounce = true)
    }

    /** 공유·텍스트 선택·딥링크·카메라로 들어온 글 */
    fun onIncoming(text: String, source: String? = null, target: String? = null) {
        _state.update {
            it.copy(
                input = text,
                source = source ?: it.source,
                target = target?.let(Languages::normalize) ?: it.target,
                detected = null,
            )
        }
        scheduleTranslate(debounce = false)
    }

    fun clearInput() {
        translateJob?.cancel()
        _state.update {
            it.copy(input = "", output = "", detected = null, loading = false,
                status = null, error = null, currentId = null)
        }
    }

    fun setSource(code: String) {
        _state.update { it.copy(source = code, detected = null) }
        scheduleTranslate(debounce = false)
    }

    fun setTarget(code: String) {
        _state.update { it.copy(target = code) }
        scheduleTranslate(debounce = false)
    }

    /** 출발·도착 언어 바꾸기. 결과가 있으면 결과를 새 입력으로 올려요. */
    fun swap() {
        val s = _state.value
        val newTarget = if (s.source == Languages.AUTO) s.detected ?: return else s.source
        _state.update {
            it.copy(
                source = s.target, target = newTarget,
                input = if (s.output.isNotBlank()) s.output else s.input,
                detected = null,
            )
        }
        scheduleTranslate(debounce = false)
    }

    fun openEntry(entry: HistoryEntry) {
        translateJob?.cancel()
        _state.update {
            it.copy(source = entry.source, target = entry.target, input = entry.text,
                output = entry.result, detected = null, loading = false,
                status = null, error = null, currentId = entry.id)
        }
    }

    // ---------- 마이크 받아쓰기 (원본: DictationInputFragment) ----------

    fun toggleDictation() {
        if (_state.value.listening) {
            listener.finish()
            return
        }
        if (!listener.isAvailable) {
            _message.value = "이 기기에서는 음성 인식을 쓸 수 없어요. Google 앱을 설치하거나 업데이트해 주세요."
            return
        }
        val s = _state.value
        val lang = if (s.source == Languages.AUTO) Locale.getDefault().language else s.source
        speaker.stop()
        _state.update { it.copy(listening = true, error = null) }
        listener.start(Languages.speechTag(lang), SpeechListener.Callbacks(
            onLevel = { v -> _state.update { it.copy(micLevel = v) } },
            onPartial = { t -> _state.update { it.copy(input = t) } },
            onFinal = { t ->
                _state.update { it.copy(listening = false, micLevel = 0f) }
                if (t.isNotBlank()) {
                    // 자동 감지 상태에서 말한 언어를 알고 있으면 그 언어로 고정해요.
                    if (s.source == Languages.AUTO) _state.update { it.copy(source = Languages.normalize(lang) ?: Languages.AUTO) }
                    onIncoming(t)
                }
            },
            onError = { code ->
                _state.update { it.copy(listening = false, micLevel = 0f) }
                _message.value = SpeechListener.message(code)
            },
        ))
    }

    // ---------- 번역 ----------

    private fun scheduleTranslate(debounce: Boolean) {
        translateJob?.cancel()
        if (_state.value.input.isBlank()) {
            _state.update { it.copy(output = "", detected = null, loading = false, status = null, error = null, currentId = null) }
            return
        }
        translateJob = viewModelScope.launch {
            if (debounce) delay(600)
            translateNow()
        }
    }

    private suspend fun translateNow() {
        val s = _state.value
        val text = s.input.trim()
        _state.update { it.copy(loading = true, error = null) }

        val source = if (s.source == Languages.AUTO) {
            val found = engine.detectLanguage(text)
            if (found == null) {
                _state.update { it.copy(loading = false, error = "언어를 알아내지 못했어요. 출발 언어를 직접 골라 주세요.") }
                return
            }
            _state.update { it.copy(detected = found) }
            found
        } else s.source

        val needed = setOf(source, s.target) - _downloaded.value - "en"
        if (needed.isNotEmpty()) {
            _state.update { it.copy(status = "언어 팩 받는 중: " + needed.joinToString { c -> Languages.nameOf(c) }) }
        }

        when (val r = engine.translate(text, source, s.target)) {
            is TranslationResult.Success -> {
                val id = historyStore.record(source, s.target, text, r.text)
                _state.update { it.copy(output = r.text, loading = false, status = null, currentId = id) }
            }
            is TranslationResult.Failure ->
                _state.update { it.copy(loading = false, status = null, error = r.message) }
        }
        if (needed.isNotEmpty()) refreshDownloaded()
    }

    // ---------- 저장 / 기록 ----------

    fun toggleSaved(id: Long) {
        val entry = history.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { historyStore.setSaved(id, !entry.saved) }
    }

    fun deleteEntry(id: Long) = viewModelScope.launch { historyStore.delete(id) }

    fun clearHistory() = viewModelScope.launch { historyStore.clearUnsaved() }

    // ---------- 음성 읽기 ----------

    fun speak(text: String, language: String) {
        speaker.speak(text, language)?.let { _message.value = it }
    }

    // ---------- 언어 팩 ----------

    fun refreshDownloaded() = viewModelScope.launch {
        _downloaded.value = engine.downloadedLanguages()
    }

    fun downloadLanguage(code: String) = changeModel(code) {
        engine.download(code).onFailure { _message.value = "${Languages.nameOf(code)} 언어 팩을 받지 못했어요." }
    }

    fun deleteLanguage(code: String) = changeModel(code) {
        engine.delete(code).onFailure { _message.value = "${Languages.nameOf(code)} 언어 팩을 지우지 못했어요." }
    }

    private fun changeModel(code: String, block: suspend () -> Unit) = viewModelScope.launch {
        _busyLanguages.update { it + code }
        try {
            block()
        } finally {
            _busyLanguages.update { it - code }
            _downloaded.value = engine.downloadedLanguages()
        }
    }

    fun messageShown() { _message.value = null }

    fun showMessage(text: String) { _message.value = text }

    override fun onCleared() {
        listener.stop()
        speaker.shutdown()
        engine.close()
    }
}
