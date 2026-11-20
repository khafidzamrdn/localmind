package com.localmind.app.core.i18n

import java.util.Locale

enum class AppLanguage(
    val code: String,
    val nativeName: String,
) {
    ENGLISH("en", "English"),
    INDONESIAN("id", "Bahasa Indonesia"),
    SPANISH("es", "Español"),
    PORTUGUESE("pt", "Português"),
    FRENCH("fr", "Français");

    val locale: Locale get() = Locale.forLanguageTag(code)

    companion object {
        fun from(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}