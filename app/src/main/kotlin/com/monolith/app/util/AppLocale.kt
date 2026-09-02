package com.monolith.app.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.monolith.app.domain.model.AppLanguage
import java.util.Locale

/**
 * The in-app language choice.
 *
 * From Android 13 the platform owns it: [LocaleManager] persists the tag per app, applies it to
 * every context in the process, recreates the visible activities itself, and stays in step with
 * the per-app language screen Android's own Settings shows. There is nothing to wrap and nothing
 * to store.
 *
 * Below 13 none of that exists. The tag is kept here and each context Monolith owns is wrapped on
 * its way in, because resources come from a base context the Application never sees: an activity,
 * a service posting a notification, and the widget's receiver each get their own. Storage is
 * SharedPreferences rather than the DataStore everything else uses -- [wrap] runs inside
 * `attachBaseContext`, before any coroutine can run, and needs the answer synchronously.
 */
object AppLocale {

    private const val PREFS_NAME = "monolith_locale"
    private const val KEY_TAG = "language_tag"

    /** Below API 33 nothing recreates the activity for us, so whoever changes the language must. */
    val needsManualRecreate: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /** The chosen language, or null when Monolith is following the system. */
    fun selected(context: Context): AppLanguage? = AppLanguage.fromTag(storedTag(context))

    fun select(context: Context, language: AppLanguage?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply { if (language == null) remove(KEY_TAG) else putString(KEY_TAG, language.tag) }
            .apply()
    }

    /**
     * Returns [context] with the chosen language applied, for callers whose resources would
     * otherwise resolve against the system language. A no-op on API 33+ and when following the
     * system, so it is safe to call from every entry point unconditionally.
     */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val language = selected(context) ?: return context
        val locale = Locale.forLanguageTag(language.tag)
        // Also the default for anything formatting outside the resource system -- dates, numbers,
        // lowercase() -- which would otherwise stay on the system language mid-sentence.
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    private fun storedTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                .applicationLocales
                .takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TAG, null)
        }
}
