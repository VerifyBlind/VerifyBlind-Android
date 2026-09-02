package com.verifyblind.mobile.util

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object IntegrityManagerHelper {

    // Cloud Project Number - Updated by User
    private const val CLOUD_PROJECT_NUMBER = 295841149391

    /**
     * Play Integrity çağrısına ÜST SINIR.
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

    private var tokenProvider: StandardIntegrityTokenProvider? = null

    suspend fun prepare(context: Context) {
        if (tokenProvider != null) return
        try {
            val standardIntegrityManager = IntegrityManagerFactory.createStandard(context)
            val request = PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .build()
                
            tokenProvider = standardIntegrityManager.prepareIntegrityToken(request).await()
            Log.d("Integrity", "Standart Integrity Provider hazırlandı")
        } catch (e: Exception) {
            AppLog.warning("Integrity provider hazırlama başarısız", "Integrity", e)
            tokenProvider = null
        }
    }

    suspend fun requestIntegrityToken(context: Context, requestHash: String): String? {
        try {
            if (tokenProvider == null) {
                 Log.d("Integrity", "Provider hazır değil, şimdi hazırlanıyor...")
                 prepare(context)
            }
            
            val provider = tokenProvider ?: return null
            
            val tokenRequest = StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build()
                
            val response = withTimeoutOrNull(TOKEN_TIMEOUT_MS) { provider.request(tokenRequest).await() }
            if (response == null) {
                AppLog.warning("Integrity token $TOKEN_TIMEOUT_MS ms icinde gelmedi (Play Services yanit vermiyor)", "Integrity")
                return null
            }
            return response.token()

        } catch (e: Exception) {
            AppLog.warning("Integrity token alınamadı", "Integrity", e)
             // For testing/mocking when API is not set up yet, we might want to return a dummy token?
             // But in Real mode, this failure means "Unsafe Device" or "No Config".
             // Let's return null.
            return null
        }
    }
}
