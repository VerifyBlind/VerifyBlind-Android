package com.verifyblind.mobile.util

import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Merkezi loglama yardımcısı — iOS `Log.swift` PII disiplininin Android portu.
 *
 * PII kuralları:
 *  - [sensitive] yalnızca Logcat (DEBUG, private) → Sentry'ye ASLA gönderilmez.
 *  - Nonce, personId, MRZ değerleri, TCKN, biyometrik (landmark/embedding) vb. asla Sentry'ye gitmez.
 *  - [info] Sentry'ye EVENT göndermez (kota tüketmez) — yalnız breadcrumb bırakır; bir crash/error
 *    olduğunda son adımların izi raporun "Breadcrumbs" bölümünde görünür (iOS paritesi).
 *  - NFC/ağ/biyometrik iptal/HTTP 4xx → Sentry WARNING (düşük öncelik).
 *  - Gerçek hatalar (kripto, decode, beklenmeyen exception) → Sentry ERROR.
 *  - Anlık loglama: her zaman logcat'e yaz; Sentry'ye yalnız Sentry seviyesindekiler gider.
 */
object AppLog {

    fun info(message: String, tag: String = "VB") {
        Log.i(tag, message)
        // EVENT olarak GİTMEZ (kota tüketmez) — yalnız breadcrumb. Crash anında akış izi raporda görünür.
        if (Sentry.isEnabled()) {
            Sentry.addBreadcrumb(Breadcrumb(message).apply { level = SentryLevel.INFO })
        }
    }

    /** PII barındıran değer logları — yalnız Logcat DEBUG, Sentry'ye (event VE breadcrumb) GÖNDERİLMEZ. */
    fun sensitive(label: String, value: Any?, tag: String = "VB") {
        Log.d(tag, "[$label] (sensitive — not sent to Sentry)")
    }

    /**
     * NFC/ağ/biyometrik iptal/4xx/beklenen-başarısızlık → Sentry WARNING.
     * Throwable verilirse stacktrace ile yakalanır (grup/iz korunur); yoksa salt mesaj gider.
     * PII/değer mesaja eklenmez.
     */
    fun warning(message: String, tag: String = "VB", throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        if (throwable != null) {
            Sentry.withScope { scope ->
                scope.level = SentryLevel.WARNING
                scope.setExtra("message", message)
                Sentry.captureException(throwable)
            }
        } else {
            Sentry.captureMessage(message, SentryLevel.WARNING)
        }
    }

    /** Kripto, decode, beklenmeyen exception → Sentry ERROR. */
    fun error(message: String, tag: String = "VB", throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (throwable != null) {
            Sentry.withScope { scope ->
                scope.level = SentryLevel.ERROR
                scope.setExtra("message", message)
                Sentry.captureException(throwable)
            }
        } else {
            Sentry.captureMessage(message, SentryLevel.ERROR)
        }
    }
}
