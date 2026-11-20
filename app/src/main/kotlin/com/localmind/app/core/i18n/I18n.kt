package com.localmind.app.core.i18n

import androidx.compose.runtime.mutableStateOf

/**
 * Resolves [UiKey] values against one language catalog.
 */
class Strings internal constructor(
    private val values: Map<UiKey, String>,
) {
    fun get(key: UiKey): String = values[key] ?: EnglishStrings[key] ?: key.name

    fun format(key: UiKey, vararg args: Any?): String = String.format(get(key), *args)
}

private val EnglishStrings: Map<UiKey, String> =
    Languages.byCode[AppLanguage.ENGLISH.code]?.map.orEmpty()

private val currentLanguage = mutableStateOf(AppLanguage.ENGLISH)
private val currentStrings = mutableStateOf(Strings(EnglishStrings))

/**
 * Process-wide translation entry point. Compose composables read a snapshot
 * state value, so changing the language recomposes everything that calls [t].
 * ViewModels can call the same functions outside composition.
 */
object I18n {

    val language: AppLanguage get() = currentLanguage.value

    fun update(language: AppLanguage) {
        if (currentLanguage.value == language) return
        currentLanguage.value = language
        currentStrings.value = Strings(Languages.byCode[language.code]?.map ?: EnglishStrings)
    }

    fun get(key: UiKey): String = currentStrings.value.get(key)

    fun format(key: UiKey, vararg args: Any?): String = currentStrings.value.format(key, *args)
}

fun t(key: UiKey): String = I18n.get(key)

fun t(key: UiKey, vararg args: Any?): String = I18n.format(key, *args)
