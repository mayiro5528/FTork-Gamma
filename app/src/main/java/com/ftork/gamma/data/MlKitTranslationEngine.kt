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

    override suspend fun translate(text: String, source: String, target: String): TranslationResult {
        if (source == target) return TranslationResult.Success(text)
        val translator = translators.getOrPut(source to target) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            TranslationResult.Success(translator.translate(text).await())
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
