package com.verifyblind.mobile.util

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object IntegrityManagerHelper {

    // Cloud Project Number - Updated by User
    private const val CLOUD_PROJECT_NUMBER = 295841149391

    /**
     * Play Integrity çağrısına ÜST SINIR (yeniden denemeler DAHİL toplam süre).
     *
     * Neden var: `provider.request(...).await()` kendi başına süresiz bekliyor.
     * Play Services / Play Store bozuk ya da eskiyse çağrı hiç dönmeyebiliyor ve
     * uygulama "Sunucu Doğrulanıyor" ekranında SONSUZA KADAR asılı kalıyor —
     * kullanıcının elinde geri tuşundan başka çıkış yok. Cihazda görüldü; aynı
     * cihazın Sentry kaydında sebebi de duruyor: StandardIntegrityException -9
     * ("Play Store'a bağlanılamadı") ve -15 ("Play Services güncellenmeli").
     *
     * Zaman aşımı null döndürür; çağıranlar bunu zaten "token yok" olarak
     * işliyor ve sunucu net bir hata veriyor. Yani sonsuz bekleme yerine
     * ANLAŞILIR bir hata çıkıyor.
     */
    private const val TOKEN_TIMEOUT_MS = 15_000L

    @Volatile
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    /**
     * Splash'te önceden ısıtma. Tek deneme: splash bunu bekliyor, açılışı uzatmasın.
     * Başarısızlık burada event DEĞİL (yalnız breadcrumb) — token isteği yeniden dener,
     * gerçekten alınamazsa uyarıyı o yazar.
     */
    suspend fun prepare(context: Context) {
        if (tokenProvider != null) return
        try {
            tokenProvider = createProvider(context)
            Log.d("Integrity", "Standart Integrity Provider hazırlandı")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.info("Integrity provider hazırlanamadı (kod ${errorCodeOf(e)}), token isteğinde yeniden denenecek", "Integrity")
        }
    }

    suspend fun requestIntegrityToken(context: Context, requestHash: String): String? {
        var lastError: Exception? = null
        val token = withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
            requestWithRetry(context, requestHash) { lastError = it }
        }
        if (token == null) {
            val error = lastError
            if (error != null) {
                AppLog.warning("Integrity token alınamadı", "Integrity", error)
            } else {
                AppLog.warning("Integrity token $TOKEN_TIMEOUT_MS ms icinde gelmedi (Play Services yanit vermiyor)", "Integrity")
            }
        }
        return token
    }

    /**
     * Google'ın önerdiği gibi geçici hatalarda artan aralıklarla yeniden dener
     * (pilotta Xiaomi'de soğuk açılışta -100 art arda iki kez geldi, handshake reddedildi).
     * Kalıcı hatalarda (-9 Play Store'a bağlanılamadı, -15 eski Play Services…) hemen vazgeçer.
     */
    private suspend fun requestWithRetry(
        context: Context,
        requestHash: String,
        onError: (Exception) -> Unit
    ): String? {
        var attempt = 0
        while (true) {
            try {
                val provider = tokenProvider ?: createProvider(context).also { tokenProvider = it }
                val tokenRequest = StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build()
                return provider.request(tokenRequest).await().token()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                val code = errorCodeOf(e)
                if (IntegrityRetryPolicy.invalidatesProvider(code)) tokenProvider = null
                val delayMs = IntegrityRetryPolicy.delayBeforeRetry(code, attempt) ?: return null
                AppLog.info("Integrity hatası (kod $code), $delayMs ms sonra yeniden deneniyor", "Integrity")
                delay(delayMs)
                attempt++
            }
        }
    }

    private suspend fun createProvider(context: Context): StandardIntegrityTokenProvider {
        val request = PrepareIntegrityTokenRequest.builder()
            .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
            .build()
        return IntegrityManagerFactory.createStandard(context).prepareIntegrityToken(request).await()
    }

    private fun errorCodeOf(e: Exception): Int? = (e as? StandardIntegrityException)?.errorCode
}

/** Hangi Play Integrity hatası yeniden denenir — saf mantık, birim testli. */
internal object IntegrityRetryPolicy {

    /** Deneme N başarısız olunca beklenecek süre; dizi bitince vazgeçilir (en fazla 4 deneme). */
    private val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 3_000L)

    /**
     * Google'ın "geri çekilerek yeniden dene" dediği geçici kodlar.
     * Bilerek DIŞARIDA: -3 ağ yok (sonraki sunucu çağrısı da zaten düşer; çevrimdışı
     * kullanıcıyı boşuna bekletmeyelim), -8 çok fazla istek (yeniden denemek kotayı
     * daha da zorlar), -9 Play Store'a bağlanılamadı (sahada kalıcı görüldü).
     */
    private val TRANSIENT_CODES = setOf(
        StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
        StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
        StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
        StandardIntegrityErrorCode.INTERNAL_ERROR
    )

    /** -19: önbellekteki provider artık geçersiz, yeniden hazırlanmalı. */
    fun invalidatesProvider(code: Int?): Boolean =
        code == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID

    /** Yeniden denenecekse beklenecek ms, denenmeyecekse null. */
    fun delayBeforeRetry(code: Int?, attempt: Int): Long? =
        if (code in TRANSIENT_CODES) RETRY_DELAYS_MS.getOrNull(attempt) else null
}
