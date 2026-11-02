package com.verifyblind.mobile.util

import android.content.Context

/**
 * Kurulumdan önce başlatılmış site isteği. İndirme sayfası isteği Google Play bağlantısına koyar,
 * Play de kurulumdan sonra uygulamaya verir; kişi siteye dönüp QR okutmadan kaldığı yerden devam eder.
 *
 * Metin bize Play üzerinden gelse de güvenilir bir kaynak sayılmaz: herkes böyle bir bağlantı
 * üretebilir. Bu, birine doğrulama bağlantısı göndermekle aynı şeydir; kişi onay ekranında sitenin
 * adını görür ve karar orada verilir. İstek süresi dolmuşsa sunucu zaten reddeder, tıklama zamanı
 * yalnız uygulama güncellemelerinde eski metnin yeniden işlenmemesi içindir.
 */
object InstallRequest {

    data class Request(val nonce: String, val pkHash: String)

    private const val PREFS = "user_prefs"
    private const val KEY_READ = "install_referrer_read"
    private const val MAX_AGE_SECONDS = 15 * 60L

    private val NONCE = Regex("^[A-Za-z0-9-]{8,64}$")
    private val PK_HASH = Regex("^[a-fA-F0-9]{64}$")

    fun alreadyRead(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READ, false)

    fun markRead(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READ, true).apply()
    }

    /** Play metnini çözer; bizim biçimimizde değilse ya da süresi geçmişse null. */
    fun parse(referrer: String?, clickSeconds: Long, nowSeconds: Long): Request? {
        if (referrer.isNullOrBlank()) return null
        if (clickSeconds <= 0 || nowSeconds - clickSeconds > MAX_AGE_SECONDS) return null
        val fields = referrer.split('&').mapNotNull { pair ->
            val at = pair.indexOf('=')
            if (at <= 0) null else pair.substring(0, at) to pair.substring(at + 1)
        }.toMap()
        val nonce = fields["nonce"]?.takeIf { NONCE.matches(it) } ?: return null
        val pkHash = fields["pk_hash"]?.takeIf { PK_HASH.matches(it) } ?: return null
        return Request(nonce, pkHash)
    }
}
