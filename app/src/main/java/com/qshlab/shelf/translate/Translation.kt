package com.qshlab.shelf.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation as MlTranslation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * On-device translation (Google ML Kit). Each language needs a ~30 MB model, downloaded once;
 * after that everything runs offline on the phone.
 */
object Translation {
    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val mutex = Mutex()
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    /** Maps a book language tag ("en", "zh-TW", "ja-JP") to an ML Kit language, or null. */
    fun languageOf(tag: String?): String? {
        val base = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
        return TranslateLanguage.fromLanguageTag(base)
    }

    /** Languages offered as translation targets. */
    val TARGETS = listOf(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE)

    /** The default target for a source language: Chinese → English, anything else → Chinese. */
    fun defaultTarget(source: String) = if (source == TranslateLanguage.CHINESE) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE

    fun hasKana(text: String) = text.any { it.code in 0x3040..0x30ff }

    fun looksChinese(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        return letters > 0 && text.count { it in '一'..'鿿' } * 2 >= letters
    }

    /**
     * The language of a selection: kana means Japanese; mostly Han characters means Chinese
     * (or Japanese in a Japanese book); otherwise the book's language, else English.
     */
    fun sourceOf(text: String, bookSource: String): String = when {
        hasKana(text) -> TranslateLanguage.JAPANESE
        looksChinese(text) -> if (bookSource == TranslateLanguage.JAPANESE) TranslateLanguage.JAPANESE else TranslateLanguage.CHINESE
        bookSource != TranslateLanguage.CHINESE && bookSource != TranslateLanguage.JAPANESE -> bookSource
        else -> TranslateLanguage.ENGLISH
    }

    suspend fun isDownloaded(lang: String): Boolean =
        runCatching { modelManager.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await() }.getOrDefault(false)

    private suspend fun translator(from: String, to: String): Translator = mutex.withLock {
        translators.getOrPut(from to to) {
            MlTranslation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
    }

    /** Downloads the models for this pair if needed (any network). */
    suspend fun prepare(from: String, to: String) {
        translator(from, to).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(text: String, from: String, to: String): String {
        val t = translator(from, to)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }

    val displayNames = mapOf(
        TranslateLanguage.CHINESE to "中文",
        TranslateLanguage.ENGLISH to "英文",
        TranslateLanguage.JAPANESE to "日文",
        TranslateLanguage.KOREAN to "韩语",
        TranslateLanguage.FRENCH to "法语",
        TranslateLanguage.GERMAN to "德语",
        TranslateLanguage.SPANISH to "西班牙语",
        TranslateLanguage.RUSSIAN to "俄语",
    )

    fun nameOf(lang: String) = displayNames[lang] ?: lang
}
