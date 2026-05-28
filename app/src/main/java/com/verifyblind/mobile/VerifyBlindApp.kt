package com.verifyblind.mobile

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class VerifyBlindApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("vb_prefs", MODE_PRIVATE)
        val userLang = prefs.getString("user_lang", "system") ?: "system"

        val applied = when (userLang) {
            "tr" -> "tr"
            "en" -> "en"
            else -> {
                val phoneLang = android.content.res.Resources.getSystem().configuration.locales[0].language
                if (phoneLang == "tr") "tr" else "en"
            }
        }

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(applied))
    }
}
