package com.ftork.gamma.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit 으로 기기 안에서 번역해요.
 * - 언어 팩(약 30MB/언어)은 처음 쓸 때 한 번 받고, 그 뒤로는 오프라인으로 동작해요.
 * - 원본 앱의 libtranslate.so + 오프라인 언어 팩 관리와 같은 역할이에요.
 */
class MlKitTranslationEngine : TranslationEngine {

    private val languageId = LanguageIdentification.getClient()
    private val modelManager = RemoteModelManager.getInstance()
    private val translators = mutableMapOf<Pair<String, String>, Translator>()

    override suspend fun detectLanguage(text: String): String? {
        val tag = runCatching { languageId.identifyLanguage(text).await() }.getOrNull()
        if (tag == null || tag == "und") return null
        return Languages.normalize(tag)
    }

    /** 진단용 기록 (보정이 일어났을 때) */
    var log: ((String) -> Unit)? = null

    /** 대화용 보정을 할지 (실시간 카메라는 간판·상표 같은 짧은 글이 많아 끔) */
    var fix = true

    private fun client(source: String, target: String): Translator = translators.getOrPut(source to target) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
    }

    private suspend fun raw(text: String, source: String, target: String): String {
        val t = client(source, target)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }

    override suspend fun translate(text: String, source: String, target: String): TranslationResult {
        if (source == target) return TranslationResult.Success(text)
        return try {
            var out = raw(text, source, target)
            if (!fix) return TranslationResult.Success(out)
            // 짧은 글(상표·이름 등)은 영어 그대로가 맞을 수 있어서 3단어 이상만 봐요
            if (text.trim().split(Regex("\\s+")).size >= 3 && TranslationFix.wrongScript(out, target)) {
                // 목표 언어가 아닌 글자(대개 영어)로 나왔어요 → 문장을 조금 바꿔 다시, 그래도 안 되면 영어를 거쳐 번역
                val first = out
                var ok = false
                for (v in TranslationFix.variants(text)) {
                    val o = raw(v, source, target)
                    if (!TranslationFix.wrongScript(o, target)) { out = o; ok = true; break }
                }
                if (!ok && source != "en" && target != "en") {
                    val o = raw(raw(text, source, "en"), "en", target)
                    if (!TranslationFix.wrongScript(o, target)) { out = o; ok = true }
                }
                // 끝내 못 고치면 영어라도 보여 주되 영어라는 걸 알려요
                if (!ok) out = "(영어) $first"
                log?.invoke("번역 보정(글자) $first → $out")
            }
            val q = TranslationFix.keepQuestion(text, out, source, target)
            if (q != out) { log?.invoke("번역 보정(질문) $out → $q"); out = q }
            TranslationResult.Success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslationResult.Failure("번역하지 못했어요. 언어 팩을 받으려면 인터넷 연결이 필요해요.", e)
        }
    }

    override suspend fun downloadedLanguages(): Set<String> =
        runCatching {
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                .map { it.language }.toSet()
        }.getOrDefault(emptySet())

    override suspend fun download(language: String): Result<Unit> = runCatching {
        val model = TranslateRemoteModel.Builder(language).build()
        modelManager.download(model, DownloadConditions.Builder().build()).await()
        Unit
    }

    override suspend fun delete(language: String): Result<Unit> = runCatching {
        val model = TranslateRemoteModel.Builder(language).build()
        modelManager.deleteDownloadedModel(model).await()
        Unit
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        languageId.close()
    }
}
