package com.verifyblind.mobile

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.sentry.android.core.SentryAndroid

class VerifyBlindApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Sentry crash reporting (iOS Log.swift + sentryDSN paritesi).
        // DSN boşsa (dev build) Sentry devre dışı — PII asla event'e girmez.
        val sentryDsn = BuildConfig.SENTRY_DSN
        if (sentryDsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = sentryDsn
                options.isAttachScreenshot = false   // Ekran görüntüsü = PII riski
                options.isAttachViewHierarchy = false
                options.isSendDefaultPii = false     // IP/cihaz adı vb. gönderilmez
                options.isEnableAutoSessionTracking = true
                // Session Replay bilinçli kilit: biyometrik/kimlik uygulaması için ekran kaydı ASLA açılmamalı
                options.sessionReplay.sessionSampleRate = 0.0
                options.sessionReplay.onErrorSampleRate = 0.0
            }
        }

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
