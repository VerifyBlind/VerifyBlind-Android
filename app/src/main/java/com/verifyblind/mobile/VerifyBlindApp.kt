package com.verifyblind.mobile

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.NetworkStatus
import com.verifyblind.mobile.util.ServerErrorMessages
import io.sentry.android.core.SentryAndroid

class VerifyBlindApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // AppLog'un "cihazın interneti var mı?" sorusunu sorabilmesi için (offline taşıma
        // hataları Sentry'ye gönderilmez). Sentry init'ten ÖNCE — init de log üretebilir.
        AppLog.attach(this)

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

                // Son kapı: cihazın interneti YOKKEN oluşan taşıma hataları hiç gönderilmez.
                // AppLog zaten sınıflandırıyor, ama her istisna AppLog'dan geçmiyor (SDK içi
                // yakalamalar, ileride eklenecek çağrı yerleri). Kullanıcının uçak modu bizim
                // raporumuz değil; kota gerçek arızalar için ayrılır. DİKKAT: yalnız taşıma
                // hataları elenir — offline'ken oluşan bir crash diske yazılır ve sonra gider.
                options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                    val t = event.throwable
                    if (t != null &&
                        ServerErrorMessages.isTransportFailure(t) &&
                        NetworkStatus.isOffline(this)
                    ) null else event
                }
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
