package com.ftork.gamma.camera

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** 사진에서 찾은 글자 덩어리 하나 */
data class ScannedBlock(val box: Rect, val text: String)

/** 어떤 문자(스크립트)로 읽었는지 */
enum class Script(val defaultLanguage: String) { LATIN("en"), KOREAN("ko"), CHINESE("zh"), JAPANESE("ja"), DEVANAGARI("hi") }

/**
 * 사진 속 글자 인식 (ML Kit Text Recognition v2, 기기 안에서 동작).
 * 원본 앱은 이 일을 Google Lens 에 맡겨요.
 */
class TextScanner {

    private val recognizers = mutableMapOf<Script, TextRecognizer>()

    private fun recognizer(script: Script) = recognizers.getOrPut(script) {
        when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }

    /**
     * @param language 출발 언어 코드. null(자동 감지)이면 여러 문자로 읽어 보고 가장 잘 맞는 걸 골라요.
     */
    suspend fun scan(bitmap: Bitmap, language: String?): Pair<Script, List<ScannedBlock>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val fixed = language?.let(::scriptFor)
        if (fixed != null) return fixed to blocks(recognizer(fixed).process(image).await())

        // 자동: 라틴·한국어·중국어·일본어로 읽어 보고, 그 문자 고유 글자가 가장 많은 결과를 골라요.
        var best: Pair<Script, List<ScannedBlock>> = Script.LATIN to emptyList()
        var bestScore = -1
        for (script in listOf(Script.LATIN, Script.KOREAN, Script.CHINESE, Script.JAPANESE)) {
            val found = blocks(recognizer(script).process(image).await())
            val all = found.joinToString("") { it.text }
            var score = all.count { c -> belongs(c, script) }
            // 일본어는 한자도 쓰니까, 가나가 하나라도 있으면 한자까지 일본어 점수에 더해요.
            if (script == Script.JAPANESE && score > 0) score += all.count { belongs(it, Script.CHINESE) }
            if (score > bestScore) { best = script to found; bestScore = score }
        }
        return best
    }

    private fun blocks(result: Text): List<ScannedBlock> = result.textBlocks.mapNotNull { b ->
        val box = b.boundingBox ?: return@mapNotNull null
        val text = b.lines.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) null else ScannedBlock(box, text)
    }

    fun close() = recognizers.values.forEach { it.close() }

    companion object {
        fun scriptFor(language: String): Script = when (language) {
            "ko" -> Script.KOREAN
            "zh" -> Script.CHINESE
            "ja" -> Script.JAPANESE
            "hi", "mr", "ne" -> Script.DEVANAGARI
            else -> Script.LATIN
        }

        private fun belongs(c: Char, script: Script): Boolean {
            val block = Character.UnicodeBlock.of(c)
            return when (script) {
                Script.LATIN -> c in 'A'..'Z' || c in 'a'..'z' || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
                    block == Character.UnicodeBlock.LATIN_EXTENDED_A || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
                Script.KOREAN -> block == Character.UnicodeBlock.HANGUL_SYLLABLES
                Script.CHINESE -> block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                Script.JAPANESE -> block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
                Script.DEVANAGARI -> block == Character.UnicodeBlock.DEVANAGARI
            }
        }
    }
}
