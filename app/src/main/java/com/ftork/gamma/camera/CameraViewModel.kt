package com.ftork.gamma.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ftork.gamma.data.Languages
import com.ftork.gamma.data.MlKitTranslationEngine
import com.ftork.gamma.data.TranslationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 번역된 글자 덩어리 */
data class TranslatedBlock(val box: Rect, val original: String, val translated: String)

data class CameraState(
    /** null 이면 카메라 미리보기, 있으면 찍은(고른) 사진 결과 화면 */
    val photo: Bitmap? = null,
    val blocks: List<TranslatedBlock> = emptyList(),
    val sourceUsed: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val showOriginal: Boolean = false,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = TextScanner()
    private val engine = MlKitTranslationEngine()
    private var job: Job? = null

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** 사진 속 글자를 찾아 번역해요. source 가 AUTO 면 문자와 언어를 자동으로 알아내요. */
    fun process(photo: Bitmap, source: String, target: String) {
        job?.cancel()
        _state.update { CameraState(photo = photo, busy = true, status = "글자를 찾는 중…") }
        job = viewModelScope.launch {
            try {
                val auto = source == Languages.AUTO
                val (script, found) = scanner.scan(photo, if (auto) null else source)
                if (found.isEmpty()) {
                    _state.update { it.copy(busy = false, status = null, error = "사진에서 글자를 찾지 못했어요.") }
                    return@launch
                }
                val from = if (auto) {
                    engine.detectLanguage(found.joinToString(" ") { it.text }) ?: script.defaultLanguage
                } else source

                _state.update { it.copy(status = "${Languages.nameOf(from)} → ${Languages.nameOf(target)} 번역 중…", sourceUsed = from) }
                val out = found.map { b ->
                    val t = when (val r = engine.translate(b.text, from, target)) {
                        is TranslationResult.Success -> r.text
                        is TranslationResult.Failure -> {
                            _state.update { it.copy(busy = false, status = null, error = r.message) }
                            return@launch
                        }
                    }
                    TranslatedBlock(b.box, b.text, t)
                }
                _state.update { it.copy(blocks = out, busy = false, status = null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, status = null, error = "글자 인식에 실패했어요: ${e.message}") }
            }
        }
    }

    fun toggleOriginal() = _state.update { it.copy(showOriginal = !it.showOriginal) }

    fun retake() {
        job?.cancel()
        _state.value = CameraState()
    }

    fun setError(message: String) = _state.update { it.copy(error = message, busy = false) }

    override fun onCleared() {
        scanner.close()
        engine.close()
    }
}
