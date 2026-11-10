package com.ftork.gamma.data

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

data class Language(val code: String, val name: String)

/** 지원 언어 목록. 코드는 BCP-47 ("en", "ko", "ja" ...) 이에요. */
object Languages {
    /** 출발 언어에서만 쓰는 "자동 감지" 값 */
    const val AUTO = "auto"

    val all: List<Language> by lazy {
        TranslateLanguage.getAllLanguages()
            .map { Language(it, nameOf(it)) }
            .sortedBy { it.name }
    }

    fun nameOf(code: String): String {
        if (code == AUTO) return "언어 감지"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.KOREAN)
        return name.ifBlank { code }
    }

    /** 음성 인식·음성 읽기에 넘길 언어 태그. 중국어는 지역까지 적어야 잘 동작해요. */
    fun speechTag(code: String): String = when (code) {
        "zh" -> "zh-CN"
        "pt" -> "pt-BR"
        else -> code
    }

    /** 언어 감지 결과(예: "zh-Latn")를 번역 엔진이 아는 코드로 바꿔요. 모르면 null. */
    fun normalize(tag: String): String? = TranslateLanguage.fromLanguageTag(tag)
}
