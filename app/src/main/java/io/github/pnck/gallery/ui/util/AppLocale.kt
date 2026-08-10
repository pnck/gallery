package io.github.pnck.gallery.ui.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app locale override ("Follow system" / English / 简体中文).
 *
 * The choice is stored in a PLAIN SharedPreferences — attachBaseContext runs
 * before any DI/coroutine exists, so the read must be synchronous. Applied by
 * wrapping the base context in MainActivity.attachBaseContext (works on every
 * API level, incl. pre-33), and mirrored to the system LocaleManager on 33+ so
 * the OS per-app-language settings stay coherent.
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY = "language_tag"

    /** Empty = follow system. */
    const val FOLLOW_SYSTEM = ""

    /** Supported overrides (bcp47 tags matching res/values-* dirs). */
    val SUPPORTED = listOf("en", "zh-CN")

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, FOLLOW_SYSTEM) ?: FOLLOW_SYSTEM

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
        }
    }

    /** Wrap [base] with the chosen locale; identity when following the system. */
    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Display label for the settings row (self-named languages). */
    fun label(tag: String): String = when (tag) {
        "en" -> "English"
        "zh-CN" -> "简体中文"
        else -> ""
    }
}
