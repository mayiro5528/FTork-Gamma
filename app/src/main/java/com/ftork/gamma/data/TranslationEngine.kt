package com.ftork.gamma.data

/**
 * 번역 결과. 원본 앱의 TranslationResult(Success/Failure) 구조를 참고했어요.
 * 예외를 던지는 대신 결과 타입으로 성공·실패를 돌려줘요.
 */
sealed interface TranslationResult {
    data class Success(val text: String) : TranslationResult
    data class Failure(val message: String, val cause: Throwable? = null) : TranslationResult
}

/**
 * 번역 엔진 인터페이스.
 * 지금은 [MlKitTranslationEngine](기기 안 번역)을 써요.
 * 다른 번역 API나 자체 서버로 바꾸려면 이 인터페이스를 구현한 클래스를 만들고
 * TranslateViewModel 의 engine 한 줄만 바꾸면 돼요.
 */
interface TranslationEngine {
    /** 텍스트의 언어 코드를 추정해요. 모르면 null. */
    suspend fun detectLanguage(text: String): String?

    /** 번역해요. 필요한 언어 팩이 없으면 먼저 받아요. */
    suspend fun translate(text: String, source: String, target: String): TranslationResult

    /** 기기에 받아 둔 언어 코드 목록 */
    suspend fun downloadedLanguages(): Set<String>

    suspend fun download(language: String): Result<Unit>

    suspend fun delete(language: String): Result<Unit>

    fun close()
}
