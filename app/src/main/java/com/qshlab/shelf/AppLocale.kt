package com.qshlab.shelf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The app's interface language: follow the system (default), or "zh" / "en" chosen in settings.
 * Applied by wrapping each activity's (and the application's) context, which works on every
 * Android version.
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY = "language"

    /** null = follow the system. */
    fun get(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun wrap(base: Context): Context {
        val lang = get(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(lang)))
        return base.createConfigurationContext(config)
    }

    /** Saves [language] (null = system) and restarts the app so every screen picks it up. */
    fun set(activity: Activity, language: String?) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (language == null) remove(KEY) else putString(KEY, language)
        }.commit()
        val restart = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }
}
