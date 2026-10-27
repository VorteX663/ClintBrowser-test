package com.jhaiian.clint.translation

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.preference.PreferenceManager
import com.jhaiian.clint.browser.webview.ClintWebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

const val PREF_TRANSLATION_OFFER = "translation_offer"
const val PREF_TRANSLATION_SHOW_BUTTON = "translation_show_button"
const val PREF_TRANSLATION_TARGET = "translation_target_language"
const val PREF_TRANSLATION_LANGUAGE_SETTINGS = "translation_language_settings"
const val PREF_TRANSLATION_NEVER_SITES = "translation_never_sites"

enum class TranslationLanguageSetting(val id: String) {
    OFFER("offer"),
    ALWAYS("always"),
    NEVER("never");

    companion object {
        fun fromId(value: String?): TranslationLanguageSetting =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) } ?: OFFER
    }
}

data class TranslationLanguage(val code: String, val name: String)

data class TranslationLanguageSupport(
    val fromLanguages: List<TranslationLanguage>,
    val toLanguages: List<TranslationLanguage>
)

data class TranslationModel(
    val language: TranslationLanguage,
    val isDownloaded: Boolean,
    val size: Long
)

data class PageTranslationState(
    val docLanguage: String? = null,
    val userLanguage: String? = null,
    val isDocLanguageSupported: Boolean = false,
    val requestedFrom: String? = null,
    val requestedTo: String? = null,
    val isEngineReady: Boolean = false,
    val isDownloading: Boolean = false,
    val error: String? = null
) {
    val isRequested: Boolean get() = requestedTo != null
    val isTranslating: Boolean get() = isRequested && error == null && !isEngineReady
    val isTranslated: Boolean get() = isRequested && error == null && isEngineReady
    val canTranslate: Boolean
        get() = isDocLanguageSupported && !docLanguage.isNullOrEmpty() && !userLanguage.isNullOrEmpty() &&
            !ClintTranslations.sameLanguage(docLanguage, userLanguage)
}

val EmptyPageTranslationState: StateFlow<PageTranslationState> = MutableStateFlow(PageTranslationState())

object ClintTranslations {

    @Volatile var offerHandler: ((ClintWebView) -> Unit)? = null

    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

    fun normalize(tag: String?): String? {
        val trimmed = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val locale = Locale.forLanguageTag(trimmed)
        val language = locale.language.lowercase(Locale.ROOT).ifEmpty { return null }
        return when (language) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "fil" -> "tl"
            "no", "nn" -> "nb"
            "sh" -> "sr"
            "zh" -> if (locale.script.equals("Hant", ignoreCase = true) ||
                (locale.script.isEmpty() && locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS)
            ) "zh-Hant" else "zh-Hans"
            else -> language
        }
    }

    fun sameLanguage(first: String?, second: String?): Boolean {
        val a = normalize(first) ?: return false
        val b = normalize(second) ?: return false
        return a.equals(b, ignoreCase = true)
    }

    fun displayName(code: String, locale: Locale, fallback: String? = null, capitalize: Boolean = true): String {
        val name = runCatching { Locale.forLanguageTag(code).getDisplayName(locale) }.getOrNull()
        val resolved = if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) fallback?.takeIf { it.isNotBlank() } ?: code else name
        if (!capitalize) return resolved
        return resolved.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    fun appLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (locales.size() > 0) locales.get(0) else Locale.getDefault()
    }

    fun userLanguage(context: Context): String? {
        val system = Resources.getSystem().configuration.locales
        val locale = if (system.size() > 0) system.get(0) else appLocale(context)
        return normalize(locale.toLanguageTag()) ?: normalize(appLocale(context).toLanguageTag())
    }

    fun findCompatible(code: String?, languages: List<TranslationLanguage>): TranslationLanguage? {
        val normalized = normalize(code) ?: return null
        return languages.firstOrNull { it.code.equals(normalized, ignoreCase = true) }
    }

    fun savedTarget(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_TRANSLATION_TARGET, null)?.takeIf { it.isNotBlank() }

    fun saveTarget(context: Context, code: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_TRANSLATION_TARGET, code).apply()
    }

    fun defaultTarget(context: Context, state: PageTranslationState): String? {
        val saved = savedTarget(context)
        if (saved != null && !sameLanguage(saved, state.docLanguage)) return saved
        return state.userLanguage?.takeIf { !sameLanguage(it, state.docLanguage) }
    }

    suspend fun isEngineSupported(context: Context): Boolean = TranslationEngine.isSupported(context)

    suspend fun supportedLanguages(context: Context, locale: Locale): TranslationLanguageSupport? {
        val registry = TranslationRepository.registry(context) ?: return null
        fun map(codes: Collection<String>): List<TranslationLanguage> =
            codes.distinct()
                .map { TranslationLanguage(it, displayName(it, locale)) }
                .sortedBy { it.name.lowercase(locale) }
        return TranslationLanguageSupport(map(registry.fromLanguages), map(registry.toLanguages))
    }

    suspend fun models(context: Context, locale: Locale): List<TranslationModel>? {
        val registry = TranslationRepository.registry(context) ?: return null
        return registry.modelLanguages
            .map { code ->
                val records = registry.recordsForLanguage(code)
                TranslationModel(
                    language = TranslationLanguage(code, displayName(code, locale)),
                    isDownloaded = records.isNotEmpty() && records.all { TranslationRepository.isDownloaded(context, it) },
                    size = records.sumOf { it.size }
                )
            }
            .sortedBy { it.language.name.lowercase(locale) }
    }

    suspend fun downloadLanguage(context: Context, code: String): Boolean = TranslationRepository.downloadLanguage(context, code)

    suspend fun deleteLanguage(context: Context, code: String): Boolean {
        TranslationEngine.unload()
        return TranslationRepository.deleteLanguage(context, code)
    }

    suspend fun deleteAllLanguages(context: Context): Boolean {
        TranslationEngine.dispose()
        return TranslationRepository.deleteAll(context)
    }

    suspend fun downloadSize(context: Context, from: String, to: String): Long? =
        TranslationRepository.missingSize(context, from, to)

    fun languageSettings(context: Context): Map<String, TranslationLanguageSetting> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_TRANSLATION_LANGUAGE_SETTINGS, null).orEmpty()
            .mapNotNull { entry ->
                val code = entry.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                code to TranslationLanguageSetting.fromId(entry.substringAfter('='))
            }
            .toMap()

    fun languageSetting(context: Context, code: String): TranslationLanguageSetting {
        val normalized = normalize(code) ?: return TranslationLanguageSetting.OFFER
        return languageSettings(context)[normalized] ?: TranslationLanguageSetting.OFFER
    }

    fun setLanguageSetting(context: Context, code: String, setting: TranslationLanguageSetting): Boolean {
        val normalized = normalize(code) ?: return false
        val updated = languageSettings(context).toMutableMap()
        if (setting == TranslationLanguageSetting.OFFER) updated.remove(normalized) else updated[normalized] = setting
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet(PREF_TRANSLATION_LANGUAGE_SETTINGS, updated.map { "${it.key}=${it.value.id}" }.toSet())
            .apply()
        return true
    }

    fun originOf(url: String?): String? {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    fun neverTranslateSites(context: Context): List<String> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_TRANSLATION_NEVER_SITES, null).orEmpty()
            .sorted()

    fun isNeverTranslateSite(context: Context, url: String?): Boolean {
        val origin = originOf(url) ?: return false
        return origin in neverTranslateSites(context)
    }

    fun setNeverTranslateSite(context: Context, url: String?, never: Boolean): Boolean {
        val origin = originOf(url) ?: return false
        return setNeverTranslateOrigin(context, origin, never)
    }

    fun removeNeverTranslateSite(context: Context, origin: String): Boolean = setNeverTranslateOrigin(context, origin, false)

    private fun setNeverTranslateOrigin(context: Context, origin: String, never: Boolean): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val sites = prefs.getStringSet(PREF_TRANSLATION_NEVER_SITES, null).orEmpty().toMutableSet()
        if (never) sites.add(origin) else sites.remove(origin)
        prefs.edit().putStringSet(PREF_TRANSLATION_NEVER_SITES, sites).apply()
        return true
    }
}
